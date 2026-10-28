package app.ridemate.demo

import app.ridemate.core.config.CancelCardsConfig
import app.ridemate.core.guardian.CancelledKeywords
import app.ridemate.core.guardian.CaptureReason
import app.ridemate.core.guardian.GuardianEffect
import app.ridemate.core.guardian.OpenPurpose
import app.ridemate.core.guardian.SheetChoice
import app.ridemate.core.guardian.UiKey
import app.ridemate.core.guardian.UserAction
import app.ridemate.core.model.Provider
import app.ridemate.core.model.QuoteStatus
import app.ridemate.core.state.Answer
import app.ridemate.core.state.TapId
import app.ridemate.core.watch.PostRemoval
import app.ridemate.core.watch.PostSnapshot
import app.ridemate.net.ApiClient
import app.ridemate.net.ApiResult
import app.ridemate.net.PointRequest
import app.ridemate.net.TapBudget
import app.ridemate.util.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Try the demo (goal 19, architecture §4.2 DemoSource, §4.18 M9): the API the home screen starts and the demo screen
 * drives. It plays `assets/demo/script.json` through the real guardian and RideWatch (via [DemoSessionHost]) and reads
 * the bundled screens through the real pipeline ([DemoFareReader]):
 *
 * searching on all three demo providers → first driver (Pepper) → "Cancel the others?" → guided cancel with scripted
 * proof (rung 2 cancelled templates) and reminders while pending → better-ride offer (keep searching on) → ride ends →
 * pay hand-off. It needs no ride app, no sign-in and no AI budget, and every provider is fictional and labelled DEMO.
 *
 * Confinement: call it from the main thread; [scope] must be main-confined (state updates and host calls run there).
 */
class DemoController(
    private val assets: DemoAssetSource,
    private val reader: DemoFareReader,
    private val api: ApiClient,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val demoEnabled: StateFlow<Boolean>,
    private val cancelCards: () -> CancelCardsConfig?,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val _state = MutableStateFlow(DemoUiState())
    val state: StateFlow<DemoUiState> = _state.asStateFlow()

    private var ocr: DemoOcr? = null
    private var host: DemoSessionHost? = null

    private var script: DemoScript? = null
    private var source: DemoEventSource? = null
    private var sessionJobs = mutableListOf<Job>()
    private var tapCounter = 0L

    /** Open sheets and prompts by id, so an answer can be mapped to the script's user step. */
    private val sheetKeys = HashMap<String, UiKey>()
    private val promptKeys = HashMap<String, UiKey>()

    init {
        scope.launch {
            demoEnabled.collect { enabled ->
                if (!enabled && _state.value.running) stopInternal(DemoNotice.SWITCHED_OFF_WHILE_RUNNING)
            }
        }
    }

    /** Integration binds the capture area's OCR engine and the session area's guardian session API. */
    fun bind(ocr: DemoOcr, host: DemoSessionHost) {
        this.ocr = ocr
        this.host = host
    }

    val isBound: Boolean get() = ocr != null && host != null

    /** Ids of the scripted events fired in the running demo, in order (developer tools and tests). */
    val firedEventIds: List<String> get() = source?.firedEventIds?.toList().orEmpty()

    /** Loads the script once; null when the bundled files are broken. */
    fun script(): DemoScript? = script ?: runCatching { DemoScript.parse(assets.scriptText()) }.getOrNull()?.also { script = it }

    // ------------------------------------------------------------------ lifecycle

    fun start(choice: DemoTrackChoice = DemoTrackChoice.MAIN): DemoStartResult {
        if (_state.value.running) return DemoStartResult.ALREADY_RUNNING
        val host = host
        if (ocr == null || host == null) return unavailable(DemoUnavailable.NOT_READY, DemoStartResult.NOT_READY)
        if (!demoEnabled.value) return unavailable(DemoUnavailable.SWITCHED_OFF, DemoStartResult.SWITCHED_OFF)
        val script = script() ?: return unavailable(DemoUnavailable.ASSETS_BROKEN, DemoStartResult.ASSETS_BROKEN)
        val track = script.track(choice.trackId) ?: return unavailable(DemoUnavailable.ASSETS_BROKEN, DemoStartResult.ASSETS_BROKEN)
        val providers = track.providers
        val sessionId = "demo-${newId()}"
        sheetKeys.clear()
        promptKeys.clear()
        // Subscribed before the session starts, so no effect or tap of the demo session is missed.
        sessionJobs += scope.launch(start = CoroutineStart.UNDISPATCHED) { host.effects.collect(::onEffect) }
        sessionJobs += scope.launch(start = CoroutineStart.UNDISPATCHED) { host.userActions.collect(::onUserAction) }
        val legs = host.startDemoSession(sessionId, providers, choice.keepSearching)
        if (legs == null) {
            sessionJobs.forEach { it.cancel() }
            sessionJobs.clear()
            return unavailable(DemoUnavailable.SESSION_BUSY, DemoStartResult.SESSION_BUSY)
        }
        source = DemoEventSource(script, track, scope, clock::wallMs, Sink(host))

        val views = providers.map { DemoProviderView(it, legs.getValue(it), DemoScreens.FARE_OPTIONS) }
        _state.value = DemoUiState(
            phase = DemoPhase.BOOKING,
            track = choice,
            sessionId = sessionId,
            providers = views,
            progress = DemoProgress(toBook = views.size),
        )
        openApp(providers.first())
        return DemoStartResult.STARTED
    }

    /** Leaves the demo: pending scripted events stop and the demo session ends. */
    fun stop() = stopInternal(notice = null)

    private fun stopInternal(notice: DemoNotice?) {
        source?.cancel()
        source = null
        sessionJobs.forEach { it.cancel() }
        sessionJobs.clear()
        if (_state.value.sessionId != null) host?.endDemoSession()
        _state.value = DemoUiState(notice = notice)
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    // ------------------------------------------------------------------ the demo apps

    /** Brings [provider]'s demo app to the front: its fare screen until booked, then its current screen. */
    fun openApp(provider: Provider) {
        val view = _state.value.provider(provider) ?: return
        if (!view.opened) host?.markOpened(view.legId)
        val purpose = if (view.booked) DemoAppPurpose.VIEW else DemoAppPurpose.BOOK
        updateProvider(provider) { it.copy(opened = true) }
        showApp(provider, purpose)
    }

    /** Back to the demo overview. */
    fun closeApp() = _state.update { it.copy(app = null) }

    /** "Read fares" on a demo app's fare screen: the real two-reader check. */
    fun readFares(provider: Provider): Job? {
        val st = _state.value
        val view = st.provider(provider) ?: return null
        val ocr = ocr ?: return null
        val script = script ?: return null
        val sessionId = st.sessionId ?: return null
        if (view.reading) return null
        val budget = TapBudget.start(clock)
        updateProvider(provider) { it.copy(reading = true) }
        return scope.launch {
            val result = try {
                reader.read(provider, script.provider(provider), DemoScreens.FARE_OPTIONS, sessionId, script.trip.tripKm, ocr, budget)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (_state.value.sessionId != sessionId) return@launch
            updateProvider(provider) { it.copy(reading = false, read = result ?: it.read) }
            if (result != null && result.rows.isNotEmpty()) {
                host?.markQuoted(view.legId)
                _state.update { s -> s.copy(progress = s.progress.copy(pricesRead = s.providers.count { it.read?.rows?.isNotEmpty() == true })) }
            }
        }
    }

    /** "Booked here" in a demo app: the user's booking tap (I1), which also starts that provider's scripted posts. */
    fun bookedHere(provider: Provider) {
        val view = _state.value.provider(provider) ?: return
        val host = host ?: return
        if (view.booked) return
        val script = script ?: return
        bookedFare(script, view)?.let { host.recordBookedFare(view.legId, it) }
        host.userAction(UserAction.BookedHere(view.legId, tap()))
        markBooked(provider)
    }

    /** A booking tap from any surface (this screen, or the bubble's "Booked here"): walk on to the next demo app. */
    private fun markBooked(provider: Provider) {
        val view = _state.value.provider(provider) ?: return
        if (view.booked) return
        updateProvider(provider) { it.copy(booked = true) }
        step(DemoUserStep.Book(provider))
        _state.update { s -> s.copy(progress = s.progress.copy(booked = s.providers.count { it.booked })) }
        val next = _state.value.nextToBook
        if (next != null) {
            openApp(next.provider)
        } else {
            _state.update { it.copy(phase = DemoPhase.WATCHING, app = null) }
        }
    }

    /** "Cancel ride" on a demo app's cancel screen: stands in for the user's tap inside the ride app. */
    fun tapCancelRide(provider: Provider) {
        val view = _state.value.provider(provider) ?: return
        if (!view.booked || view.cancelTapped) return
        updateProvider(provider) { it.copy(cancelTapped = true) }
        step(DemoUserStep.TappedCancel(provider))
    }

    /** "Show me" on a demo cancel screen: asks the guardian, which requests the grounding capture. */
    fun showMe(provider: Provider) {
        val view = _state.value.provider(provider) ?: return
        host?.userAction(UserAction.ShowMe(view.legId, tap()))
    }

    /** "Pay in <app>" after the ride ended (the guardian opens the demo app; no money moves). */
    fun pay(provider: Provider) {
        val view = _state.value.provider(provider) ?: return
        host?.userAction(UserAction.Pay(view.legId, tap()))
    }

    // ------------------------------------------------------------------ guardian side

    /** Effects of the demo session. Public so a host that pushes effects (instead of a flow) can call it. */
    fun onEffect(effect: GuardianEffect) {
        when (effect) {
            is GuardianEffect.OpenApp -> if (effect.provider.isDemo) onOpenApp(effect)
            is GuardianEffect.RequestCapture -> if (effect.provider.isDemo) onCapture(effect)
            is GuardianEffect.DriverSheet -> {
                sheetKeys[effect.sheetId] = effect.key
                if (effect.key == UiKey.SHEET_CANCEL_OTHERS || effect.key == UiKey.SHEET_TWO_DRIVERS_PICK_ONE) progress { it.copy(driverFound = true) }
            }
            is GuardianEffect.BetterRideSheet -> {
                sheetKeys[effect.sheetId] = effect.key
                if (effect.key == UiKey.SHEET_BETTER_RIDE) progress { it.copy(betterRideOffered = true) }
            }
            is GuardianEffect.MaybePrompt -> promptKeys[effect.promptId] = effect.key
            is GuardianEffect.RideBanner -> progress { it.copy(driverFound = true) }
            is GuardianEffect.CancelCard -> progress { it.copy(cancelGuideShown = true) }
            is GuardianEffect.Reminder -> progress { it.copy(reminders = maxOf(it.reminders, effect.attempt)) }
            is GuardianEffect.PayHandoffOffer -> if (effect.provider.isDemo) progress { it.copy(payOffered = true, payProvider = effect.provider) }
            is GuardianEffect.Banner -> if (effect.key == UiKey.BANNER_CANCEL_CONFIRMED) {
                effect.provider?.let { p -> updateProvider(p) { it.copy(cancelProven = true) } }
                progress { p -> p.copy(cancelsProven = _state.value.providers.count { it.cancelProven }) }
            }
            else -> Unit
        }
    }

    private fun onUserAction(action: UserAction) {
        val step: DemoUserStep? = when (action) {
            is UserAction.BookedHere -> providerOf(action.legId)?.let { p ->
                markBooked(p)
                DemoUserStep.Book(p)
            }
            is UserAction.AnswerSheet -> when (action.choice) {
                SheetChoice.YES_CANCEL_OTHERS -> DemoUserStep.CancelOthersYes
                SheetChoice.KEEP_SEARCHING -> DemoUserStep.CancelOthersNotNow
                SheetChoice.NOT_NOW -> if (sheetKeys[action.sheetId] == UiKey.SHEET_CANCEL_OTHERS) DemoUserStep.CancelOthersNotNow else null
                SheetChoice.SWITCH -> if (sheetKeys[action.sheetId] == UiKey.SHEET_BETTER_RIDE) DemoUserStep.BetterRideYes else null
                SheetChoice.PICK, SheetChoice.KEEP_CURRENT -> null
            }
            // J9: "Yes, cancel the others" on a MAYBE_ASSIGNED prompt is itself the Yes for the chain.
            is UserAction.AnswerPrompt ->
                if (action.answer == Answer.YES && promptKeys[action.promptId] == UiKey.PROMPT_MAYBE_ASSIGNED) DemoUserStep.CancelOthersYes else null
            // "I cancelled it" on the cancel card stands in for the tap inside the demo app.
            is UserAction.ICancelledIt -> providerOf(action.legId)?.let { p ->
                updateProvider(p) { it.copy(cancelTapped = true) }
                DemoUserStep.TappedCancel(p)
            }
            else -> null
        }
        step ?: return
        val newStep = step(step)
        if (newStep && step == DemoUserStep.CancelOthersNotNow && _state.value.track?.keepSearching == false &&
            source?.track?.eventsFor(step).isNullOrEmpty()
        ) {
            _state.update { it.copy(notice = DemoNotice.NOT_NOW_CHOSEN) }
        }
    }

    private fun onOpenApp(effect: GuardianEffect.OpenApp) {
        _state.value.provider(effect.provider) ?: return
        val purpose = when (effect.purpose) {
            OpenPurpose.CANCEL -> DemoAppPurpose.CANCEL
            OpenPurpose.PAY -> DemoAppPurpose.PAY
            OpenPurpose.VIEW -> DemoAppPurpose.VIEW
        }
        showApp(effect.provider, purpose)
        if (purpose == DemoAppPurpose.PAY) {
            _state.update { it.copy(phase = DemoPhase.FINISHED, progress = it.progress.copy(paymentHandedOff = true)) }
        }
    }

    /** A capture of a demo app is its current bundled screen (demo frames are exempt from the tap window, I6). */
    private fun onCapture(effect: GuardianEffect.RequestCapture) {
        val view = _state.value.provider(effect.provider) ?: return
        val ocr = ocr ?: return
        val host = host ?: return
        val script = script ?: return
        val sessionId = _state.value.sessionId ?: return
        val spec = script.provider(effect.provider)
        val screen = displayedScreen(effect.provider, spec, _state.value.app?.takeIf { it.provider == effect.provider }?.purpose)
        val path = spec.screens.getValue(screen)
        scope.launch {
            when (effect.reason) {
                CaptureReason.SHOW_ME -> showMePointer(effect.provider, path, sessionId)
                CaptureReason.CANCEL_PROOF, CaptureReason.CHECK_SCREEN -> {
                    val text = try {
                        withTimeoutOrNull(DemoFareReader.OCR_TIMEOUT_MS) { ocr.recognize(assets.bytes(path)) }?.tokens?.joinToString(" ") { it.text }
                    } catch (_: TimeoutCancellationException) {
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    } ?: return@launch
                    // The demo was left (or restarted) while the screen was read: its ended session gets nothing.
                    if (_state.value.sessionId != sessionId) return@launch
                    val keywords = cancelCards()?.cardFor(effect.provider.packageName)?.cancelledKeywords.orEmpty()
                    val found = CancelledKeywords.found(text, keywords)
                    val tapId = tap()
                    if (effect.reason == CaptureReason.CHECK_SCREEN && !found) {
                        val read = try {
                            reader.read(effect.provider, spec, screen, sessionId, script.trip.tripKm, ocr, TapBudget.start(clock))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                        if (_state.value.sessionId != sessionId) return@launch
                        val driver = read?.driver
                        if (driver != null && (driver.etaMin != null || driver.distanceMeters != null)) {
                            host.verifiedDriverReading(view.legId, driver.etaMin, driver.distanceMeters, tapId)
                            return@launch
                        }
                    }
                    host.captureVerdict(view.legId, found, tapId)
                }
            }
        }
    }

    private suspend fun showMePointer(provider: Provider, path: String, sessionId: String) {
        val target = cancelCards()?.cardFor(provider.packageName)?.pointTarget ?: DEFAULT_POINT_TARGET
        val result = api.point(PointRequest(provider, assets.bytes(path), sessionId, target))
        val box = (result as? ApiResult.Ok)?.body?.takeIf { it.supported }?.box0to1000?.takeIf { b -> b.size == 4 && b.all { it in 0..1000 } }
        if (_state.value.sessionId != sessionId) return
        if (box != null) {
            _state.update { s -> s.copy(app = s.app?.takeIf { it.provider == provider }?.copy(pointer = box) ?: s.app) }
        } else {
            _state.update { it.copy(notice = DemoNotice.SHOW_ME_UNAVAILABLE) }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun step(step: DemoUserStep): Boolean = source?.onUserStep(step) ?: false

    private fun showApp(provider: Provider, purpose: DemoAppPurpose) {
        val script = script ?: return
        val spec = script.provider(provider)
        val screen = displayedScreen(provider, spec, purpose)
        _state.update { it.copy(app = DemoAppView(provider, purpose, screen, spec.screens.getValue(screen))) }
    }

    /** What the demo app shows: its cancel screen while RideMate guides the cancel, else its current screen. */
    private fun displayedScreen(provider: Provider, spec: DemoProviderSpec, purpose: DemoAppPurpose?): String {
        val current = _state.value.provider(provider)?.screen ?: DemoScreens.FARE_OPTIONS
        return if (purpose == DemoAppPurpose.CANCEL && current != DemoScreens.CANCELLED && DemoScreens.CANCEL_FLOW in spec.screens) {
            DemoScreens.CANCEL_FLOW
        } else {
            current
        }
    }

    private fun onScreenChanged(provider: Provider, screen: String) {
        updateProvider(provider) { it.copy(screen = screen) }
        val app = _state.value.app ?: return
        if (app.provider == provider) showApp(provider, app.purpose)
    }

    /** The VERIFIED price of the booked option, for fee notes ("up to 10% of ₹X"); never an unverified number (I2). */
    private fun bookedFare(script: DemoScript, view: DemoProviderView): Int? {
        val booked = script.provider(view.provider).booked ?: return null
        return view.read?.rows?.firstOrNull { it.vehicleClass == booked.vehicleClass && it.status == QuoteStatus.VERIFIED }?.price?.last
    }

    private fun providerOf(legId: String): Provider? = _state.value.providers.firstOrNull { it.legId == legId }?.provider

    private fun updateProvider(provider: Provider, change: (DemoProviderView) -> DemoProviderView) = _state.update { s ->
        s.copy(providers = s.providers.map { if (it.provider == provider) change(it) else it })
    }

    private fun progress(change: (DemoProgress) -> DemoProgress) = _state.update { it.copy(progress = change(it.progress)) }

    private fun unavailable(reason: DemoUnavailable, result: DemoStartResult): DemoStartResult {
        _state.value = DemoUiState(phase = DemoPhase.UNAVAILABLE, unavailable = reason)
        return result
    }

    private fun tap(): TapId = TapId("demo-tap-${++tapCounter}-${clock.elapsedRealtimeMs()}")

    private inner class Sink(private val host: DemoSessionHost) : DemoEventSink {
        override fun post(event: DemoEvent, snapshot: PostSnapshot) = host.post(snapshot)
        override fun remove(event: DemoEvent, removal: PostRemoval) = host.remove(removal)
        override fun screenChanged(provider: Provider, screen: String) = onScreenChanged(provider, screen)
    }

    companion object {
        const val DEFAULT_POINT_TARGET = "cancel_button"
    }
}
