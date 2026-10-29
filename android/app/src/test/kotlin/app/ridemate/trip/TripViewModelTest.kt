package app.ridemate.trip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.core.model.LatLng
import app.ridemate.core.model.Provider
import app.ridemate.core.model.VehicleClass
import app.ridemate.launch.FakeInstalledApps
import app.ridemate.net.ApiResult
import app.ridemate.net.PlacePickResponse
import app.ridemate.precheck.FixAction
import app.ridemate.precheck.PreSessionCheck
import app.ridemate.precheck.PreSessionCheckTest
import app.ridemate.precheck.PrecheckKind
import app.ridemate.testing.FakeClock
import app.ridemate.util.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class TripViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = FakeClock()

    private val geocoder = FakeGeocoder()
    private val location = FakeLocation()
    private val speech = FakeSpeech()
    private val api = PlacePickApi { ApiResult.Ok(PlacePickResponse("c2")) }
    private val apps = FakeInstalledApps()
    private val ondc = MutableStateFlow(true)
    private val map = MutableStateFlow(app.ridemate.map.MapConfig.DEFAULT)
    private val probes = PreSessionCheckTest.FakeProbes()
    private lateinit var recent: RecentPlacesStore

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val prefs = context.getSharedPreferences("trip-vm-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        recent = RecentPlacesStore(prefs, clock)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): Pair<TripViewModel, MutableList<TripEvent>> {
        val vm = TripViewModel(
            TripDeps(
                geocoder = geocoder,
                location = location,
                speech = speech,
                placePicker = VoicePlacePicker(geocoder, api),
                recentPlaces = recent,
                installedApps = apps,
                ondcEnabled = ondc,
                precheck = PreSessionCheck(probes, { null }, clock, io = dispatcher),
                currentLocationLabel = "Current location",
                defaultVoiceLanguage = VoiceLanguage.ENGLISH,
                mapConfig = map,
                io = dispatcher,
            ),
        )
        val events = mutableListOf<TripEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        advanceUntilIdle()
        return vm to events
    }

    /** Types [text] in the drop field and lets the 400 ms debounce and the lookup run. */
    private fun TestScope.searchDrop(vm: TripViewModel, text: String) {
        vm.onDropQuery(text)
        advanceTimeBy(PlaceSearchController.DEBOUNCE_MS)
        runCurrent()
    }

    private fun TestScope.confirmFirstSuggestion(vm: TripViewModel, text: String = "airport") {
        searchDrop(vm, text)
        val results = vm.state.value.dropSearch as PlaceSearchState.Results
        vm.selectSuggestion(results.suggestions.first())
        vm.confirmDrop()
        advanceUntilIdle()
    }

    // ---- pickup ----------------------------------------------------------------------------------------------

    @Test
    fun `pickup defaults to the current location, then shows its address`() = runTest(dispatcher) {
        val (vm, _) = viewModel()
        assertEquals(PickupState.Current(TripPlace("Barpeta Road, Assam", FakeLocation.HERE)), vm.state.value.pickup)
        assertEquals(1, location.calls)
    }

    @Test
    fun `when the address of the position is unknown the pickup says current location`() = runTest(dispatcher) {
        geocoder.reverseLabel = null
        val (vm, _) = viewModel()
        assertEquals(PickupState.Current(TripPlace("Current location", FakeLocation.HERE)), vm.state.value.pickup)
    }

    @Test
    fun `without permission the screen asks, and denied means a typed pickup`() = runTest(dispatcher) {
        location.permission = false
        val (vm, _) = viewModel()
        assertEquals(PickupState.NeedsPermission, vm.state.value.pickup)
        vm.onLocationPermissionResult(false)
        assertEquals(PickupState.Typing(TypingReason.LOCATION_DENIED), vm.state.value.pickup)
        assertEquals(0, location.calls)

        location.permission = true
        vm.onResume() // the user allowed it in Settings
        advanceUntilIdle()
        assertTrue(vm.state.value.pickup is PickupState.Current)
    }

    @Test
    fun `no position means a typed pickup`() = runTest(dispatcher) {
        location.fix = { LocationFix.Unavailable }
        val (vm, _) = viewModel()
        assertEquals(PickupState.Typing(TypingReason.LOCATION_UNAVAILABLE), vm.state.value.pickup)
    }

    @Test
    fun `a typed pickup comes from a suggestion or the text as typed`() = runTest(dispatcher) {
        val (vm, events) = viewModel()
        vm.changePickup()
        vm.onPickupQuery("college")
        advanceTimeBy(PlaceSearchController.DEBOUNCE_MS)
        runCurrent()
        val suggestion = (vm.state.value.pickupSearch as PlaceSearchState.Results).suggestions[1]
        vm.selectPickup(suggestion)
        assertEquals(PickupState.Typed(suggestion.toTripPlace()), vm.state.value.pickup)
        assertNull(geocoder.calls.last().near) // the pickup search is not biased by the old pickup

        vm.changePickup()
        vm.onPickupQuery("Hostel 3, B block")
        vm.usePickupAsTyped()
        assertEquals(PickupState.Typed(TripPlace("Hostel 3, B block", null)), vm.state.value.pickup)

        location.permission = false
        vm.useCurrentLocation()
        advanceUntilIdle()
        assertEquals(TripEvent.RequestLocationPermission, events.single())
    }

    // ---- drop ------------------------------------------------------------------------------------------------

    @Test
    fun `typing quickly makes one geocoder call, biased to the pickup`() = runTest(dispatcher) {
        val (vm, _) = viewModel()
        vm.onDropQuery("a")
        advanceTimeBy(150)
        vm.onDropQuery("ai")
        advanceTimeBy(150)
        vm.onDropQuery("air")
        advanceTimeBy(399)
        runCurrent()
        assertTrue(geocoder.calls.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(FakeGeocoder.Call("air", 5, FakeLocation.HERE)), geocoder.calls)
    }

    @Test
    fun `nothing happens until the user confirms the drop address`() = runTest(dispatcher) {
        val (vm, events) = viewModel()
        searchDrop(vm, "airport")
        val suggestion = (vm.state.value.dropSearch as PlaceSearchState.Results).suggestions.first()
        vm.selectSuggestion(suggestion)
        assertEquals(PendingDrop(suggestion.toTripPlace(), DropSource.SEARCH), vm.state.value.pending)
        assertNull(vm.state.value.drop)
        assertFalse(vm.state.value.canCompare)
        assertNull(vm.currentPlan())
        vm.compare()
        advanceUntilIdle()
        assertTrue(events.isEmpty())

        vm.rejectPending()
        assertNull(vm.state.value.pending)
        vm.selectSuggestion(suggestion)
        vm.confirmDrop()
        advanceUntilIdle()
        assertEquals(suggestion.toTripPlace(), vm.state.value.drop)
        assertTrue(vm.state.value.canCompare)
        assertEquals(listOf(suggestion.toTripPlace()), vm.state.value.recent)
        assertEquals("", vm.state.value.dropQuery)
    }

    @Test
    fun `recent places are offered and still need confirming`() = runTest(dispatcher) {
        recent.add(TripPlace("Fancy Bazaar", LatLng(26.18, 91.74)))
        val (vm, _) = viewModel()
        assertEquals(listOf("Fancy Bazaar"), vm.state.value.recent.map { it.label })
        vm.selectRecent(vm.state.value.recent.single())
        assertEquals(DropSource.RECENT, vm.state.value.pending?.source)
        assertNull(vm.state.value.drop)
    }

    @Test
    fun `without a geocoder the typed address is confirmed and the apps open without a route`() = runTest(dispatcher) {
        geocoder.isAvailable = false
        val (vm, events) = viewModel()
        assertFalse(vm.state.value.geocoderAvailable)
        searchDrop(vm, "Near Kali Mandir, Barpeta")
        assertEquals(PlaceSearchState.Unavailable("Near Kali Mandir, Barpeta"), vm.state.value.dropSearch)
        vm.useDropAsTyped()
        assertEquals(PendingDrop(TripPlace("Near Kali Mandir, Barpeta", null), DropSource.TYPED), vm.state.value.pending)
        vm.confirmDrop()
        advanceUntilIdle()
        assertFalse(vm.state.value.ondcSelectable)
        vm.setIncludeOndc(true)
        assertFalse(vm.state.value.includeOndc)

        vm.compare()
        advanceUntilIdle()
        val plan = (events.single() as TripEvent.StartComparison).plan
        assertNull(plan.drop.point)
        assertFalse(plan.includeOndc)
    }

    @Test
    fun `view on map is an event and a missing maps app becomes a notice`() = runTest(dispatcher) {
        val (vm, events) = viewModel()
        val place = TripPlace("LGBI Airport", LatLng(26.1, 91.58))
        vm.viewOnMap(place)
        advanceUntilIdle()
        assertEquals(TripEvent.ShowOnMap(place), events.single())
        vm.onNoMapsApp()
        assertEquals(TripNotice.NoMapsApp, vm.state.value.notice)
        vm.dismissNotice()
        assertNull(vm.state.value.notice)
    }

    // ---- voice -----------------------------------------------------------------------------------------------

    @Test
    fun `voice transcript goes through place-pick and waits for confirmation`() = runTest(dispatcher) {
        speech.script = { flow { emit(SpeechEvent.Ready); emit(SpeechEvent.Partial("air")); emit(SpeechEvent.Final("airport")) } }
        val (vm, _) = viewModel()
        vm.selectVoiceLanguage(VoiceLanguage.HINDI)
        vm.startVoice()
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals(listOf(VoiceLanguage.HINDI), speech.listenedIn)
        assertEquals("hi-IN", api.requests.single().lang)
        assertEquals(PendingDrop(TripPlace("airport place 2", s.choices[1].point), DropSource.VOICE), s.pending)
        assertEquals(3, s.choices.size)
        assertEquals("airport", s.dropQuery)
        assertEquals(VoicePhase.IDLE, s.voice.phase)
        assertNull(s.drop)
        vm.confirmDrop()
        assertEquals("airport place 2", vm.state.value.drop?.label)
    }

    @Test
    fun `when the model says none the user picks from the candidates`() = runTest(dispatcher) {
        api.answer = { ApiResult.Ok(PlacePickResponse(null)) }
        val (vm, _) = viewModel()
        vm.startVoice()
        advanceUntilIdle()
        assertNull(vm.state.value.pending)
        assertEquals(3, vm.state.value.choices.size)
        assertEquals(TripNotice.PickFromList(ChooseReason.MODEL_NONE, null), vm.state.value.notice)
        vm.selectSuggestion(vm.state.value.choices[2])
        assertEquals(DropSource.VOICE, vm.state.value.pending?.source)
    }

    @Test
    fun `AI unavailable keeps the list with a resume time`() = runTest(dispatcher) {
        val paused = ApiResult.AiPaused("ai_paused", "13:30", "budget")
        api.answer = { paused }
        val (vm, _) = viewModel()
        vm.startVoice()
        advanceUntilIdle()
        assertEquals(TripNotice.PickFromList(ChooseReason.AI_UNAVAILABLE, paused), vm.state.value.notice)
        assertEquals(3, vm.state.value.choices.size)
    }

    @Test
    fun `an unsupported voice language is dropped and english takes over`() = runTest(dispatcher) {
        speech.script = { flow { emit(SpeechEvent.Failed(SpeechError.LANGUAGE_UNSUPPORTED)) } }
        val (vm, _) = viewModel()
        vm.selectVoiceLanguage(VoiceLanguage.ASSAMESE)
        vm.startVoice()
        advanceUntilIdle()
        val voice = vm.state.value.voice
        assertFalse(VoiceLanguage.ASSAMESE in voice.languages)
        assertEquals(VoiceLanguage.ENGLISH, voice.language)
        assertTrue(voice.available)
        assertEquals(TripNotice.Voice(SpeechError.LANGUAGE_UNSUPPORTED, VoiceLanguage.ASSAMESE), vm.state.value.notice)
    }

    @Test
    fun `silence ends listening after the time limit`() = runTest(dispatcher) {
        speech.script = { flow { awaitCancellation() } }
        val (vm, _) = viewModel()
        vm.startVoice()
        runCurrent()
        assertEquals(VoicePhase.LISTENING, vm.state.value.voice.phase)
        advanceTimeBy(TripDeps.VOICE_TIMEOUT_MS + 1)
        runCurrent()
        assertEquals(VoicePhase.IDLE, vm.state.value.voice.phase)
        assertEquals(TripNotice.Voice(SpeechError.NO_MATCH, VoiceLanguage.ENGLISH), vm.state.value.notice)
    }

    @Test
    fun `a drop picked while voice is still matching is never replaced by the late voice result`() = runTest(dispatcher) {
        val modelAnswer = CompletableDeferred<ApiResult<PlacePickResponse>>()
        api.answer = { modelAnswer.await() }
        val (vm, _) = viewModel()
        vm.startVoice()
        runCurrent()
        assertEquals(VoicePhase.MATCHING, vm.state.value.voice.phase)

        // The user doesn't wait: types another place and picks a suggestion.
        searchDrop(vm, "market")
        val market = (vm.state.value.dropSearch as PlaceSearchState.Results).suggestions.first()
        vm.selectSuggestion(market)
        assertEquals(VoicePhase.IDLE, vm.state.value.voice.phase)

        modelAnswer.complete(ApiResult.Ok(PlacePickResponse("c2"))) // the voice lookup would have picked "airport"
        advanceUntilIdle()
        assertEquals(PendingDrop(market.toTripPlace(), DropSource.SEARCH), vm.state.value.pending)
        assertTrue(vm.state.value.choices.isEmpty())
        vm.confirmDrop()
        assertEquals("market place 1", vm.state.value.drop?.label)
    }

    @Test
    fun `a recent place tapped while voice is matching stays the one to confirm`() = runTest(dispatcher) {
        val (vm, _) = viewModel()
        confirmFirstSuggestion(vm, "fancy bazaar")
        vm.changeDrop()
        val modelAnswer = CompletableDeferred<ApiResult<PlacePickResponse>>()
        api.answer = { modelAnswer.await() }
        vm.startVoice()
        runCurrent()
        assertEquals(VoicePhase.MATCHING, vm.state.value.voice.phase)

        val recentPlace = vm.state.value.recent.single()
        vm.selectRecent(recentPlace)
        modelAnswer.complete(ApiResult.Ok(PlacePickResponse("c1")))
        advanceUntilIdle()

        assertEquals(PendingDrop(recentPlace, DropSource.RECENT), vm.state.value.pending)
        assertEquals(VoicePhase.IDLE, vm.state.value.voice.phase)
    }

    @Test
    fun `typing while listening stops the microphone and keeps what was typed`() = runTest(dispatcher) {
        var stopped = false
        speech.script = { flow<SpeechEvent> { emit(SpeechEvent.Ready); awaitCancellation() }.onCompletion { stopped = true } }
        val (vm, _) = viewModel()
        vm.startVoice()
        runCurrent()
        assertEquals(VoicePhase.LISTENING, vm.state.value.voice.phase)

        vm.onDropQuery("mark")
        runCurrent()
        assertTrue(stopped)
        assertEquals(VoicePhase.IDLE, vm.state.value.voice.phase)
        advanceUntilIdle()
        assertEquals("mark", vm.state.value.dropQuery)
        assertNull(vm.state.value.notice) // stopping is the user's choice, not an error
    }

    @Test
    fun `leaving the screen never leaves the microphone listening`() = runTest(dispatcher) {
        var stopped = false
        speech.script = { flow<SpeechEvent> { emit(SpeechEvent.Ready); awaitCancellation() }.onCompletion { stopped = true } }
        val (vm, _) = viewModel()
        vm.onPause() // idle: nothing to stop
        vm.startVoice()
        runCurrent()
        vm.onPause()
        runCurrent()
        assertTrue(stopped)
        assertEquals(VoicePhase.IDLE, vm.state.value.voice.phase)
    }

    @Test
    fun `a denied microphone and a missing recognizer fall back to typing`() = runTest(dispatcher) {
        val (vm, _) = viewModel()
        vm.onMicPermissionDenied()
        assertEquals(TripNotice.MicDenied, vm.state.value.notice)
        speech.script = { flow { emit(SpeechEvent.Failed(SpeechError.UNAVAILABLE)) } }
        vm.startVoice()
        advanceUntilIdle()
        assertFalse(vm.state.value.voice.available)
    }

    @Test
    fun `voice languages follow what the recognizer supports`() = runTest(dispatcher) {
        speech.supported = setOf(VoiceLanguage.HINDI, VoiceLanguage.ENGLISH)
        val (vm, _) = viewModel()
        assertEquals(listOf(VoiceLanguage.ENGLISH, VoiceLanguage.HINDI), vm.state.value.voice.languages)
        vm.selectVoiceLanguage(VoiceLanguage.BENGALI) // not offered: ignored
        assertEquals(VoiceLanguage.ENGLISH, vm.state.value.voice.language)
    }

    @Test
    fun `no supported voice language or no recognizer hides the mic`() = runTest(dispatcher) {
        speech.supported = emptySet()
        assertFalse(viewModel().first.state.value.voice.available)
        speech.supported = null
        speech.available = false
        assertFalse(viewModel().first.state.value.voice.available)
    }

    @Test
    fun `no candidates for the transcript leaves it editable`() = runTest(dispatcher) {
        geocoder.answer = { GeocodeResult.Found(emptyList()) }
        speech.script = { flow { emit(SpeechEvent.Final("xyzzy")) } }
        val (vm, _) = viewModel()
        vm.startVoice()
        advanceUntilIdle()
        assertEquals(TripNotice.NothingFoundFor("xyzzy"), vm.state.value.notice)
        assertEquals("xyzzy", vm.state.value.dropQuery)
        assertTrue(api.requests.isEmpty())
    }

    // ---- apps, vehicles, ONDC --------------------------------------------------------------------------------

    @Test
    fun `only installed apps can be chosen`() = runTest(dispatcher) {
        apps.installed = setOf(Provider.OLA, Provider.RAPIDO)
        val (vm, _) = viewModel()
        assertEquals(
            listOf(AppChoice(Provider.UBER, false, false), AppChoice(Provider.OLA, true, true), AppChoice(Provider.RAPIDO, true, true)),
            vm.state.value.apps,
        )
        vm.toggleApp(Provider.UBER)
        vm.toggleApp(Provider.OLA)
        assertEquals(listOf(Provider.RAPIDO), vm.state.value.selectedApps)

        apps.installed = setOf(Provider.OLA, Provider.RAPIDO, Provider.UBER) // installed meanwhile
        vm.onResume()
        advanceUntilIdle()
        assertEquals(listOf(Provider.UBER, Provider.RAPIDO), vm.state.value.selectedApps)
    }

    @Test
    fun `vehicle types map to vehicle classes`() = runTest(dispatcher) {
        val (vm, events) = viewModel()
        confirmFirstSuggestion(vm)
        vm.toggleVehicle(VehicleChoice.BIKE)
        vm.toggleVehicle(VehicleChoice.AUTO)
        vm.compare()
        advanceUntilIdle()
        val plan = (events.single() as TripEvent.StartComparison).plan
        assertEquals(setOf(VehicleChoice.CAB), plan.vehicles)
        assertEquals(listOf(VehicleClass.CAB_ECONOMY, VehicleClass.CAB_PREMIUM, VehicleClass.CAB_XL), plan.vehicleClasses)

        vm.toggleVehicle(VehicleChoice.CAB)
        assertFalse(vm.state.value.canCompare)
    }

    @Test
    fun `ONDC follows the remote switch and needs both pins`() = runTest(dispatcher) {
        ondc.value = false
        val (vm, events) = viewModel()
        confirmFirstSuggestion(vm)
        vm.setIncludeOndc(true)
        assertFalse(vm.state.value.includeOndc)

        ondc.value = true
        advanceUntilIdle()
        assertTrue(vm.state.value.ondcSelectable)
        vm.setIncludeOndc(true)
        assertTrue(vm.state.value.includeOndc)
        vm.compare()
        advanceUntilIdle()
        val plan = (events.single() as TripEvent.StartComparison).plan
        assertTrue(plan.includeOndc)
        assertEquals(Provider.ONDC, plan.providers.last())

        ondc.value = false // kill switch
        advanceUntilIdle()
        assertFalse(vm.state.value.includeOndc)
    }

    // ---- compare + pre-session check ------------------------------------------------------------------------

    @Test
    fun `a clean check starts the comparison with the trip`() = runTest(dispatcher) {
        val (vm, events) = viewModel()
        confirmFirstSuggestion(vm)
        vm.compare()
        advanceUntilIdle()
        val plan = (events.single() as TripEvent.StartComparison).plan
        assertEquals(TripPlace("Barpeta Road, Assam", FakeLocation.HERE), plan.pickup)
        assertEquals("airport place 1", plan.drop.label)
        assertEquals(Provider.LIVE_APPS, plan.apps)
        assertEquals(VehicleChoice.entries.toSet(), plan.vehicles)
        assertNull(vm.state.value.report)
    }

    @Test
    fun `a blind listener blocks compare with a fix-it card until fixed`() = runTest(dispatcher) {
        probes.listener = false
        val (vm, events) = viewModel()
        confirmFirstSuggestion(vm)
        vm.compare()
        advanceUntilIdle()
        val report = vm.state.value.report
        assertNotNull(report)
        report!!
        assertEquals(PrecheckKind.LISTENER_OFF, report.issues.single().kind)
        assertFalse(report.canProceed)
        vm.continueAnyway()
        advanceUntilIdle()
        assertTrue(events.isEmpty())

        probes.listener = true // the user turned notification access on and came back
        vm.onResume()
        advanceUntilIdle()
        assertNull(vm.state.value.report)
        assertTrue(events.isEmpty()) // coming back re-checks; Compare is still the user's tap
        vm.compare()
        advanceUntilIdle()
        assertTrue(events.single() is TripEvent.StartComparison)
    }

    @Test
    fun `warnings can be passed with continue anyway`() = runTest(dispatcher) {
        probes.overlay = false
        val (vm, events) = viewModel()
        confirmFirstSuggestion(vm)
        vm.compare()
        advanceUntilIdle()
        assertEquals(PrecheckKind.OVERLAY_OFF, vm.state.value.report!!.issues.single().kind)
        assertTrue(events.isEmpty())
        vm.continueAnyway()
        advanceUntilIdle()
        assertTrue(events.single() is TripEvent.StartComparison)
        assertNull(vm.state.value.report)
    }

    @Test
    fun `with no ride app the demo is offered`() = runTest(dispatcher) {
        apps.installed = emptySet()
        val (vm, events) = viewModel()
        assertFalse(vm.state.value.anyAppInstalled)
        confirmFirstSuggestion(vm)
        assertFalse(vm.state.value.canCompare)
        vm.onFix(FixAction.TRY_DEMO)
        vm.tryDemo()
        advanceUntilIdle()
        assertEquals(listOf(TripEvent.TryDemo, TripEvent.TryDemo), events)
    }

    @Test
    fun `in-app fixes`() = runTest(dispatcher) {
        probes.overlay = false
        val (vm, events) = viewModel()
        confirmFirstSuggestion(vm)
        vm.compare()
        advanceUntilIdle()
        vm.onFix(FixAction.CHOOSE_APPS)
        assertNull(vm.state.value.report)
        location.permission = false
        vm.onFix(FixAction.REQUEST_LOCATION)
        advanceUntilIdle()
        assertEquals(TripEvent.RequestLocationPermission, events.single())
    }

    @Test
    fun `a check that ends early never leaves compare disabled`() = runTest(dispatcher) {
        val failingClock = object : Clock {
            override fun elapsedRealtimeMs(): Long = 0
            override fun wallMs(): Long = throw IllegalStateException("clock unavailable")
        }
        val vm = TripViewModel(
            TripDeps(
                geocoder = geocoder,
                location = location,
                speech = speech,
                placePicker = VoicePlacePicker(geocoder, api),
                recentPlaces = recent,
                installedApps = apps,
                ondcEnabled = ondc,
                precheck = PreSessionCheck(probes, { null }, failingClock, io = dispatcher),
                currentLocationLabel = "Current location",
                defaultVoiceLanguage = VoiceLanguage.ENGLISH,
                io = dispatcher,
            ),
        )
        val events = mutableListOf<TripEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        advanceUntilIdle()
        confirmFirstSuggestion(vm)

        vm.compare()
        advanceUntilIdle()

        assertFalse(vm.state.value.checking)
        assertTrue(vm.state.value.canCompare)
        assertTrue(events.isEmpty()) // nothing starts without a finished check
    }

    @Test
    fun `changing the drop clears it and any shown check`() = runTest(dispatcher) {
        probes.overlay = false
        val (vm, _) = viewModel()
        confirmFirstSuggestion(vm)
        vm.compare()
        advanceUntilIdle()
        vm.changeDrop()
        assertNull(vm.state.value.drop)
        assertNull(vm.state.value.report)
        assertFalse(vm.state.value.canCompare)
    }

    // ---- pin on the map --------------------------------------------------------------------------------------

    @Test
    fun `set on map for the drop and pickup puts the exact pinned coordinates in the plan`() = runTest(dispatcher) {
        val (vm, events) = viewModel()
        assertTrue(vm.state.value.mapAvailable)

        vm.openPinPicker(app.ridemate.map.PinTarget.PICKUP)
        val pickupRequest = vm.state.value.pinRequest!!
        assertEquals(app.ridemate.map.PinTarget.PICKUP, pickupRequest.target)
        assertEquals(FakeLocation.HERE, pickupRequest.start)
        val pinnedPickup = LatLng(26.503311, 90.976042)
        vm.onPinConfirmed(app.ridemate.map.PinTarget.PICKUP, TripPlace("Gate 2, Barpeta Road", pinnedPickup))
        assertNull(vm.state.value.pinRequest)

        vm.openPinPicker(app.ridemate.map.PinTarget.DROP)
        val dropRequest = vm.state.value.pinRequest!!
        assertEquals(pinnedPickup, dropRequest.start)
        assertTrue(dropRequest.nonce > pickupRequest.nonce)
        val pinnedDrop = LatLng(26.512345, 90.998765)
        vm.onPinConfirmed(app.ridemate.map.PinTarget.DROP, TripPlace("Pinned location (26.51235, 90.99877)", pinnedDrop))
        advanceUntilIdle()

        val plan = vm.currentPlan()!!
        assertEquals(pinnedPickup, plan.pickup.point)
        assertEquals(pinnedDrop, plan.drop.point)
        assertEquals("Gate 2, Barpeta Road", plan.pickup.label)
        assertNull(vm.state.value.pending)
        assertTrue(vm.state.value.recent.any { it.point == pinnedDrop })
        vm.compare()
        advanceUntilIdle()
        val start = events.filterIsInstance<TripEvent.StartComparison>().single()
        assertEquals(pinnedDrop, start.plan.drop.point)
        assertEquals(pinnedPickup, start.plan.pickup.point)
    }

    @Test
    fun `map switched off offers no pin picker and the trip still works typed`() = runTest(dispatcher) {
        map.value = app.ridemate.map.MapConfig.DISABLED
        val (vm, _) = viewModel()
        assertFalse(vm.state.value.mapAvailable)
        vm.openPinPicker(app.ridemate.map.PinTarget.DROP)
        assertNull(vm.state.value.pinRequest)
        confirmFirstSuggestion(vm)
        assertNotNull(vm.currentPlan())
    }

    @Test
    fun `a map that fails to load closes the picker and leaves typing`() = runTest(dispatcher) {
        val (vm, _) = viewModel()
        vm.openPinPicker(app.ridemate.map.PinTarget.DROP)
        vm.onMapUnavailable()
        assertNull(vm.state.value.pinRequest)
        assertTrue(vm.state.value.mapFailed)
        assertFalse(vm.state.value.mapAvailable)
        confirmFirstSuggestion(vm)
        assertNotNull(vm.currentPlan())
    }

    @Test
    fun `cancelling the picker changes nothing`() = runTest(dispatcher) {
        val (vm, _) = viewModel()
        val before = vm.state.value.pickup
        vm.openPinPicker(app.ridemate.map.PinTarget.PICKUP)
        vm.closePinPicker()
        assertNull(vm.state.value.pinRequest)
        assertEquals(before, vm.state.value.pickup)
    }
}
