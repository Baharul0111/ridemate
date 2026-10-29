package app.ridemate.e2e

import android.app.NotificationManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import app.ridemate.R
import app.ridemate.auth.AuthState
import app.ridemate.core.gate.ComparisonList
import app.ridemate.core.guardian.LegRole
import app.ridemate.core.guardian.UiAction
import app.ridemate.core.guardian.UiKey
import app.ridemate.core.model.EvidenceRung
import app.ridemate.core.model.Provider
import app.ridemate.core.model.QuoteStatus
import app.ridemate.core.model.RideState
import app.ridemate.demo.DemoAppPurpose
import app.ridemate.demo.DemoPhase
import app.ridemate.demo.DemoReadIssue
import app.ridemate.demo.DemoReadResult
import app.ridemate.demo.DemoText
import app.ridemate.demo.DemoTrackChoice
import app.ridemate.demo.ReadSource
import app.ridemate.e2e.DeviceE2e.device
import app.ridemate.e2e.DeviceE2e.graph
import app.ridemate.e2e.DeviceE2e.instrumentation
import app.ridemate.e2e.DeviceE2e.log
import app.ridemate.e2e.DeviceE2e.scrollAndTap
import app.ridemate.e2e.DeviceE2e.scrollToTop
import app.ridemate.e2e.DeviceE2e.str
import app.ridemate.e2e.DeviceE2e.waitObject
import app.ridemate.notify.BetterRideItem
import app.ridemate.notify.CancelCardItem
import app.ridemate.notify.DriverSheetItem
import app.ridemate.notify.GuardianChannels
import app.ridemate.notify.GuardianUiState
import app.ridemate.notify.PayItem
import app.ridemate.notify.ReminderItem
import app.ridemate.onboarding.HealthCheckOutcome
import app.ridemate.onboarding.OnboardingFlow
import app.ridemate.onboarding.OnboardingStep
import app.ridemate.onboarding.SignInChoice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Goal "Try the demo runs end to end on the phone": Home → Try the demo, signed out (the demo signs in anonymously by
 * itself), every fare screen read through the real pipeline — ML Kit OCR on the phone + Muse Spark Open on Modal through
 * RideMate's deployed backend + the two-reader VerificationGate — then the scripted guardian flow through the real
 * guardian, RideWatch and in-app sheets.
 *
 * - [mainTrack_liveReads_cancelChainWithProofAndReminders_payHandoff]: "Book three, keep one" — VERIFIED prices whose
 *   Channel A read is live or the server's cache (never "recorded"); driver found → "Cancel the others?" → Yes → cancel
 *   card → a reminder while the cancels are pending → rung-2 proof for each other booking → pay hand-off.
 * - [keepSearchingTrack_betterRideOffer_yes_cancelsTheOthers_payHandoff]: keep searching on — driver found → Keep
 *   searching → better-ride offer → Yes, switch → the old ride is guided to cancel; the new driver is close, so the
 *   still-searching Kestrel gets "Cancel the others?" → Yes → rung-2 proof for both → pay hand-off in the better ride.
 *
 * What is checked beyond the walk itself: Channel A's read is Muse Spark Open's (the server's `model`), every VERIFIED
 * price and ETA equals the labelled truth of that exact screen (`eval/demo_labels.json`, in the test APK only), the
 * first reminder arrives 30 s after the Yes and is posted as RideMate's own heads-up, reminders stop once every extra
 * booking is proven, and the better-ride offer shows both drivers' ETA and distance and changes nothing before Yes (I4).
 *
 * The script runs at its real pace (reminders at 30 s, rides ending 45 s after the last cancel), so no time-scale hook is
 * needed. Live AI reads: 3 (the main track's three fare screens); the keep-searching track books without reading.
 */
@RunWith(AndroidJUnit4::class)
class DemoEndToEndTest {

    private val demo get() = graph.demoController
    private val guardian get() = graph.guardianRuntime
    private val presenter by lazy { graph.guardian.presenter() }
    private val problems = mutableListOf<String>()

    @Before
    fun setUp() {
        ensureOnboarded()
        // The deployed backend's URL comes from Remote Config (bundled default is empty → NotConfigured).
        val baseUrl = waitFor("Remote Config api_base_url", 90_000, pollMs = 500) {
            graph.configRepository.apiBaseUrl().takeIf { it.startsWith("https://") }
        }
        log("api base url host: ${android.net.Uri.parse(baseUrl).host}")
        assertTrue("demo_enabled must be on", graph.configRepository.demoEnabled.value)
        // Signed out, so the demo itself must fetch an anonymous token (M9: no Google sign-in needed).
        runBlocking { graph.authGateway.signOut() }
        waitUntil("signed out", 10_000) { graph.authGateway.state.value == AuthState.SignedOut }
        DeviceE2e.launchMain()
        waitObject(By.desc(str(R.string.home_try_demo_cd)), "Home's Try the demo")
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { if (demo.state.value.running) demo.stop() }
        DeviceE2e.finishActivities()
    }

    @Test
    fun mainTrack_liveReads_cancelChainWithProofAndReminders_payHandoff() {
        startTrack(DemoTrackChoice.MAIN)
        val providers = demo.state.value.providers.map { it.provider }
        assertEquals(Provider.DEMO_APPS, providers)

        // ---- read and book in each demo app, in walk order
        var liveReads = 0
        for (provider in providers) {
            waitUntil("$provider's fare screen in front", 15_000) { appIs(provider, DemoAppPurpose.BOOK) }
            scrollToTop()
            scrollAndTap(
                By.desc(str(R.string.demo_action_read_fares_description, provider.displayName)),
                "Read fares in ${provider.displayName}",
                obstructionTop = ::sheetTop,
                confirm = { demo.state.value.provider(provider)?.let { it.reading || it.read != null } == true },
            )
            liveReads++
            val read = waitFor("$provider's two-reader result", 60_000) {
                demo.state.value.provider(provider)?.takeIf { !it.reading }?.read
            }
            if (provider == providers.first()) {
                assertTrue("the demo signed in anonymously by itself", graph.authGateway.state.value is AuthState.Anonymous)
            }
            assertGatedRead(provider, read)
            assertRowsOnScreen(provider, read)
            scrollToTop()
            scrollAndTap(
                By.desc(str(R.string.demo_action_booked_here_description, provider.displayName)),
                "Booked here in ${provider.displayName}",
                obstructionTop = ::sheetTop,
                confirm = { demo.state.value.provider(provider)?.booked == true },
            )
        }
        log("LIVE_READS_USED main=$liveReads")
        waitUntil("all booked → watching", 10_000) { demo.state.value.phase == DemoPhase.WATCHING }
        assertEquals(3, demo.state.value.progress.pricesRead)

        // ---- first driver (Pepper, 9 min · 2.8 km) → "Cancel the others?"
        val sheet = waitFor("Cancel the others? sheet", 45_000) {
            ui().items.filterIsInstance<DriverSheetItem>().firstOrNull { it.key == UiKey.SHEET_CANCEL_OTHERS }
        }
        val driver = sheet.drivers.single()
        assertEquals(Provider.DEMO_PEPPER, driver.provider)
        assertTrue("driver ETA ${driver.etaMin}", driver.etaMin in 8..9)
        assertEquals(2800, driver.distanceMeters)
        assertEquals(setOf(Provider.DEMO_KESTREL, Provider.DEMO_TAMARIND), sheet.others.map { it.provider }.toSet())
        waitObject(By.text(str(R.string.guardian_sheet_cancel_others)).pkg(DeviceE2e.PKG), "the sheet's title on screen")
        assertTrue(demo.state.value.progress.driverFound)
        // Goal 13: the first reminder comes 30 s after the extra bookings become pending (the Yes). Its arrival is
        // timed by a watcher on the guardian's state, so the UI steps below cannot blur it.
        val firstReminder = CompletableDeferred<Pair<Long, ReminderItem>>()
        val watcher = CoroutineScope(Dispatchers.Default).launch {
            val state = guardian.uiState.first { s -> s.items.any { it is ReminderItem } }
            firstReminder.complete(SystemClock.elapsedRealtime() to state.items.filterIsInstance<ReminderItem>().first())
        }
        val yesAt = SystemClock.elapsedRealtime()
        tapSheetAction(str(R.string.guardian_action_yes_cancel_others))

        // ---- the cancel chain: Kestrel first, with its cancel card; reminders while both are pending
        waitUntil("Kestrel's cancel screen", 15_000) { appIs(Provider.DEMO_KESTREL, DemoAppPurpose.CANCEL) }
        val card = waitFor("Kestrel's cancel card", 10_000) { cancelCard(Provider.DEMO_KESTREL) }
        assertEquals(1, card.position)
        assertEquals(2, card.total)
        assertTrue(demo.state.value.progress.cancelGuideShown)
        waitObject(By.text(presenter.present(card).title).pkg(DeviceE2e.PKG), "the cancel card on screen")

        val reminder = waitFor("a reminder while the cancels are pending", 75_000) {
            ui().items.filterIsInstance<ReminderItem>().firstOrNull()
        }
        assertTrue(reminder.attempt >= 1)
        assertEquals(setOf(Provider.DEMO_KESTREL, Provider.DEMO_TAMARIND), reminder.legs.map { it.provider }.toSet())
        assertTrue(ui().remindersSent >= 1)
        val (firstAt, first) = runBlocking { withTimeout(5_000) { firstReminder.await() } }
        val sinceYes = firstAt - yesAt
        assertEquals("the first reminder is attempt 1", 1, first.attempt)
        assertTrue("first reminder $sinceYes ms after Yes (goal 13: after 30 s)", sinceYes in FIRST_REMINDER_MS)
        // It reaches Android as RideMate's own heads-up notification, not only the in-app list.
        waitUntil("the reminder posted on RideMate's reminders channel", 5_000) { ownNotificationOn(GuardianChannels.REMINDERS) }
        waitUntil("reminder counted in the demo checklist", 5_000) { demo.state.value.progress.reminders >= 1 }
        log("reminder ${first.attempt} fired ${sinceYes} ms after Yes while Kestrel and Tamarind were pending")

        cancelAndProve(Provider.DEMO_KESTREL)
        // One extra booking is still pending: the reminders keep going (I8).
        assertTrue("reminders stay on while Tamarind is pending", ui().items.any { it is ReminderItem })
        waitUntil("Tamarind's cancel screen", 15_000) { appIs(Provider.DEMO_TAMARIND, DemoAppPurpose.CANCEL) }
        val second = waitFor("Tamarind's cancel card", 10_000) { cancelCard(Provider.DEMO_TAMARIND) }
        assertEquals(2, second.position)
        cancelAndProve(Provider.DEMO_TAMARIND)
        assertEquals(2, demo.state.value.progress.cancelsProven)
        // Every extra booking proven cancelled: the reminders stop, in the app and in the notification shade.
        waitUntil("reminders stop once every extra booking is proven", 5_000) {
            ui().items.none { it is ReminderItem } && !ownNotificationOn(GuardianChannels.REMINDERS)
        }
        watcher.cancel()

        // ---- Pepper's ride ends → pay hand-off in Pepper Rides
        payAndFinish(Provider.DEMO_PEPPER)
        leaveDemo()
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun keepSearchingTrack_betterRideOffer_yes_cancelsTheOthers_payHandoff() {
        startTrack(DemoTrackChoice.KEEP_SEARCHING)
        assertTrue("keep searching is on for this walk", ui().keepSearching)
        for (provider in demo.state.value.providers.map { it.provider }) {
            waitUntil("$provider's fare screen in front", 15_000) { appIs(provider, DemoAppPurpose.BOOK) }
            scrollToTop()
            scrollAndTap(
                By.desc(str(R.string.demo_action_booked_here_description, provider.displayName)),
                "Booked here in ${provider.displayName}",
                obstructionTop = ::sheetTop,
                confirm = { demo.state.value.provider(provider)?.booked == true },
            )
        }

        val sheet = waitFor("first-driver sheet", 45_000) {
            ui().items.filterIsInstance<DriverSheetItem>().firstOrNull { it.key == UiKey.SHEET_CANCEL_OTHERS }
        }
        assertEquals(Provider.DEMO_PEPPER, sheet.drivers.single().provider)
        val keepLooking = if (UiAction.KEEP_SEARCHING in sheet.actions) R.string.guardian_action_keep_searching else R.string.guardian_action_not_now
        tapSheetAction(str(keepLooking))

        // Tamarind finds a driver 2 min away → better-ride offer (nothing changes until Yes).
        val offer = waitFor("better-ride offer", 40_000) {
            ui().items.filterIsInstance<BetterRideItem>().firstOrNull { it.key == UiKey.SHEET_BETTER_RIDE }
        }
        assertEquals(Provider.DEMO_PEPPER, offer.current.provider)
        assertEquals(Provider.DEMO_TAMARIND, offer.candidate.provider)
        // Goal 16: both drivers' time and distance (the demo's notifications: 9 min · 2.8 km vs 2 min · 0.6 km).
        assertTrue("current ETA ${offer.current.etaMin}", offer.current.etaMin in 8..9)
        assertEquals(2800, offer.current.distanceMeters)
        assertTrue("candidate ETA ${offer.candidate.etaMin}", (offer.candidate.etaMin ?: 99) <= 2)
        assertEquals(600, offer.candidate.distanceMeters)
        waitObject(By.text(str(R.string.guardian_sheet_better_ride)).pkg(DeviceE2e.PKG), "the better-ride sheet on screen")
        assertTrue(demo.state.value.progress.betterRideOffered)
        // I4: the offer changes nothing by itself. For a few seconds with no tap: no cancel guide, no app opened to
        // cancel, both drivers still assigned and nothing marked extra.
        holdsFor("nothing switches without Yes", 3_000) {
            ui().items.none { it is CancelCardItem } &&
                demo.state.value.app?.purpose != DemoAppPurpose.CANCEL &&
                ui().legs.none { it.role == LegRole.EXTRA } &&
                ui().legs.filter { it.provider == Provider.DEMO_PEPPER || it.provider == Provider.DEMO_TAMARIND }
                    .all { it.state == RideState.ASSIGNED }
        }
        tapSheetAction(str(R.string.guardian_action_switch))
        // §4.8 Yes → guided cancel of the old leg (Pepper) first. The new driver is ≤ 2 min away, so better-ride
        // alerts stop and Kestrel (still searching) gets "Cancel the others?" → Yes.
        waitUntil("Pepper (the old ride) guided first", 15_000) { appIs(Provider.DEMO_PEPPER, DemoAppPurpose.CANCEL) }
        val second = waitFor("Cancel the others? for the remaining search", 20_000) {
            ui().items.filterIsInstance<DriverSheetItem>().firstOrNull { it.key == UiKey.SHEET_CANCEL_OTHERS }
        }
        assertEquals(Provider.DEMO_TAMARIND, second.drivers.single().provider)
        assertTrue(Provider.DEMO_KESTREL in second.others.map { it.provider })
        waitObject(By.text(str(R.string.guardian_sheet_cancel_others)).pkg(DeviceE2e.PKG), "the second sheet on screen")
        tapSheetAction(str(R.string.guardian_action_yes_cancel_others))

        // The two others are cancelled with proof, one after the other.
        val proven = mutableSetOf<Provider>()
        while (proven.size < 2) {
            val next = waitFor("the next cancel screen", 20_000) {
                demo.state.value.app?.takeIf { it.purpose == DemoAppPurpose.CANCEL && it.provider !in proven }?.provider
            }
            assertTrue("only the other bookings are cancelled", next == Provider.DEMO_KESTREL || next == Provider.DEMO_PEPPER)
            assertNotNull(waitFor("$next's cancel card", 10_000) { cancelCard(next) })
            cancelAndProve(next)
            proven += next
        }
        payAndFinish(Provider.DEMO_TAMARIND)
        leaveDemo()
    }

    // ------------------------------------------------------------------ steps

    private fun startTrack(choice: DemoTrackChoice) {
        scrollAndTap(By.desc(str(R.string.home_try_demo_cd)), "Try the demo",
            confirm = { DeviceE2e.has(By.desc(str(R.string.demo_start_description, str(DemoText.trackTitle(choice))))) })
        val title = str(DemoText.trackTitle(choice))
        scrollAndTap(By.desc(str(R.string.demo_start_description, title)), "Start: $title",
            confirm = { demo.state.value.phase == DemoPhase.BOOKING })
        waitUntil("demo running ($choice)", 15_000) { demo.state.value.phase == DemoPhase.BOOKING }
        assertTrue(ui().demo)
    }

    /** The two-reader gate's output for one demo screen (I2): VERIFIED numbers only, cheapest only among them. */
    private fun assertGatedRead(provider: Provider, read: DemoReadResult) {
        log("LIVE_READ provider=${provider.wireId} source=${read.source} model=${read.model} issue=${read.issue} rows=${read.rows.size} verified=${read.rows.count { it.status == QuoteStatus.VERIFIED }} " +
            "gate=${read.rows.map { "${it.vehicleClass}:${it.status}:${it.failures.sorted()}" }}")
        assertTrue("$provider: Channel A must be the real model via the backend (live or cache), was ${read.source}",
            read.source == ReadSource.LIVE || read.source == ReadSource.CACHE)
        assertFalse("$provider: not the recorded read bundled in the app", read.recordedOnPhone)
        assertNull("$provider: the server answered", read.apiFailure)
        assertEquals(DemoReadIssue.NONE, read.issue)
        assertEquals("$provider: the server says Muse Spark Open made Channel A's read", MUSE_SPARK_OPEN, read.model)
        assertVerifiedRowsMatchLabels(provider, read)
        // Collected rather than thrown, so one provider without a VERIFIED price still lets the rest of the walk run;
        // the test fails at the end with the gate's reasons (enums only).
        if (read.rows.none { it.status == QuoteStatus.VERIFIED && it.price != null }) {
            problems += "$provider: no VERIFIED price (source ${read.source}); gate: " +
                read.rows.joinToString { "${it.vehicleClass}=${it.failures.sorted()}" }
        }
        for (row in read.rows.filter { it.status != QuoteStatus.VERIFIED }) {
            assertEquals(QuoteStatus.UNREADABLE, row.status)
            assertNull("$provider: a non-verified row shows no number", row.price)
            assertNull(row.eta)
        }
        val entries = ComparisonList.build(read.rows, System.currentTimeMillis()).groups.flatMap { it.entries }
        assertEquals("cheapest is flagged exactly when a VERIFIED row exists",
            read.rows.any { it.status == QuoteStatus.VERIFIED }, entries.any { it.cheapest })
        for (entry in entries) {
            if (entry.cheapest) assertEquals("cheapest only among VERIFIED", QuoteStatus.VERIFIED, entry.row.status)
            if (entry.row.status != QuoteStatus.VERIFIED) assertNull(entry.priceText)
        }
    }

    /**
     * Goal 5 "never show a wrong price", on the phone: every VERIFIED row is a labelled option of this exact screen, with
     * the labelled price (and ETA, when one shows). The ground truth is `eval/demo_labels.json`, bundled in the test APK
     * only. Messages name the row, never a value (logs carry no fares).
     */
    private fun assertVerifiedRowsMatchLabels(provider: Provider, read: DemoReadResult) {
        val truth = labelledRows(read.imageSha256)
        for (row in read.rows.filter { it.status == QuoteStatus.VERIFIED }) {
            val label = truth[row.vehicleClass.wireId]
            assertNotNull("$provider ${row.vehicleClass}: a VERIFIED row is a labelled option of this screen", label)
            val min = label!!.getInt("price_min")
            val max = if (label.isNull("price_max")) min else label.getInt("price_max")
            assertTrue("$provider ${row.vehicleClass}: the VERIFIED price equals the labelled price", row.price == min..max)
            if (row.eta != null && !label.isNull("eta_min")) {
                assertTrue("$provider ${row.vehicleClass}: the shown ETA equals the labelled ETA", row.eta == label.getInt("eta_min"))
            }
        }
    }

    /** The labelled rows of the demo screen with this SHA-256, by vehicle-class wire id. */
    private fun labelledRows(sha256: String): Map<String, JSONObject> {
        val screens = instrumentation.context.assets.open(LABELS_ASSET).bufferedReader().use { JSONObject(it.readText()) }
            .getJSONArray("screens")
        val screen = (0 until screens.length()).map(screens::getJSONObject).firstOrNull { it.getString("sha256") == sha256 }
        assertNotNull("a label for the demo screen ${sha256.take(12)}", screen)
        val rows = screen!!.getJSONArray("rows")
        return (0 until rows.length()).map(rows::getJSONObject).associateBy { it.getString("vehicle_class") }
    }

    /** The same rows on screen: the source label, a price only on VERIFIED rows, "cheapest" only on them. */
    private fun assertRowsOnScreen(provider: Provider, read: DemoReadResult) {
        val context = DeviceE2e.context
        val name = DemoText.providerName(context, provider)
        val header = str(R.string.demo_rows_label, provider.displayName, str(DemoText.source(read.source)))
        scrollAndTap(By.text(header), "the $provider rows' header (source label)", obstructionTop = ::sheetTop, tap = false)
        val booked = demo.state.value.provider(provider)?.booked == true
        val entries = ComparisonList.build(read.rows, System.currentTimeMillis()).groups.flatMap { it.entries }
        val unreadable = str(R.string.demo_row_unreadable)
        val cheapest = str(R.string.demo_row_cheapest)
        for (entry in entries) {
            val description = str(R.string.demo_row_description_with_app, DemoText.rowDescription(context, entry, booked), name)
            if (entry.row.status == QuoteStatus.VERIFIED) {
                assertTrue(description.contains("₹"))
            } else {
                assertTrue(description.contains(unreadable))
                assertFalse("no number on a non-verified row", description.contains("₹"))
                assertFalse(description.contains(cheapest))
            }
            scrollAndTap(By.desc(description), "a $provider row on screen", obstructionTop = ::sheetTop, tap = false)
        }
    }

    /** "Cancel ride" in the demo app (the user's tap inside the ride app), then rung-2 proof from its own notification. */
    private fun cancelAndProve(provider: Provider) {
        waitUntil("$provider's cancel screen in front", 15_000) { appIs(provider, DemoAppPurpose.CANCEL) }
        scrollToTop()
        scrollAndTap(
            By.desc(str(R.string.demo_action_cancel_ride_description, provider.displayName)),
            "Cancel ride in ${provider.displayName}",
            obstructionTop = ::sheetTop,
            confirm = { demo.state.value.provider(provider)?.cancelTapped == true },
        )
        waitUntil("$provider cancel tapped", 10_000) { demo.state.value.provider(provider)?.cancelTapped == true }
        val leg = waitFor("$provider cancel proven", 20_000) {
            ui().legs.firstOrNull { it.provider == provider && it.state == RideState.CANCEL_CONFIRMED }
        }
        assertEquals("$provider proven by rung 2 (its own cancelled notification)", EvidenceRung.TEMPLATE, leg.evidence)
        waitUntil("$provider proven in the demo", 5_000) { demo.state.value.provider(provider)?.cancelProven == true }
        log("cancel proven: ${provider.wireId} rung=${leg.evidence?.rung}")
    }

    private fun payAndFinish(provider: Provider) {
        waitFor("pay hand-off offer for $provider", 80_000) {
            ui().items.filterIsInstance<PayItem>().firstOrNull { it.provider == provider }
        }
        waitUntil("pay offered in the demo", 5_000) {
            demo.state.value.progress.let { it.payOffered && it.payProvider == provider }
        }
        if (demo.state.value.app != null) {
            scrollAndTap(By.desc(str(R.string.demo_action_back_description)), "Back to overview", obstructionTop = ::sheetTop,
                confirm = { demo.state.value.app == null })
            waitUntil("overview", 5_000) { demo.state.value.app == null }
        }
        scrollToTop()
        scrollAndTap(
            By.desc(str(R.string.demo_action_pay_description, provider.displayName)),
            "Pay in ${provider.displayName}",
            obstructionTop = ::sheetTop,
            confirm = { demo.state.value.progress.paymentHandedOff },
        )
        waitUntil("handed off to pay", 15_000) {
            demo.state.value.let { it.phase == DemoPhase.FINISHED && it.progress.paymentHandedOff }
        }
        assertTrue(appIs(provider, DemoAppPurpose.PAY))
        scrollToTop()
        waitObject(By.text(str(R.string.demo_step_finished)), "Demo complete")
    }

    private fun leaveDemo() {
        scrollAndTap(By.desc(str(R.string.demo_leave_description)), "Leave demo", obstructionTop = ::sheetTop,
            confirm = { !demo.state.value.running })
        waitUntil("demo left", 10_000) { !demo.state.value.running }
    }

    // ------------------------------------------------------------------ helpers

    /** [DeviceE2e.waitFor] that logs the guardian and demo state (enums and counts only) when it times out. */
    private fun <T : Any> waitFor(what: String, timeoutMs: Long, pollMs: Long = 250, value: () -> T?): T = try {
        DeviceE2e.waitFor(what, timeoutMs, pollMs, value)
    } catch (e: AssertionError) {
        log("STATE at timeout ($what): ${describe()}")
        throw e
    }

    private fun waitUntil(what: String, timeoutMs: Long, condition: () -> Boolean) {
        waitFor(what, timeoutMs) { if (condition()) Unit else null }
    }

    private fun describe(): String {
        val d = demo.state.value
        val g = ui()
        val legs = g.legs.joinToString { "${it.provider.wireId}:${it.state}/${it.role}/ev=${it.evidence}/dismissed=${it.dismissed}" }
        val items = g.items.joinToString { item ->
            item::class.simpleName + when (item) {
                is DriverSheetItem -> "(${item.key},${item.actions})"
                is BetterRideItem -> "(${item.key},${item.actions})"
                is CancelCardItem -> "(${item.provider.wireId},${item.position}/${item.total},${item.providerState})"
                is ReminderItem -> "(attempt=${item.attempt})"
                is PayItem -> "(${item.provider.wireId})"
                else -> ""
            }
        }
        val views = d.providers.joinToString { "${it.provider.wireId}:${it.screen}/reading=${it.reading}/read=${it.read != null}/booked=${it.booked}/tapped=${it.cancelTapped}/proven=${it.cancelProven}" }
        return "demo=${d.phase} app=${d.app?.provider?.wireId}/${d.app?.purpose}/${d.app?.screen} progress=${d.progress} views=[$views] " +
            "guardian session=${g.sessionId != null} closed=${g.closed} keepSearching=${g.keepSearching} reminders=${g.remindersSent} legs=[$legs] items=[$items] active=${g.activeItem?.let { it::class.simpleName }}"
    }

    private fun ui(): GuardianUiState = guardian.uiState.value

    /** Fails unless [condition] stays true for the whole of [durationMs] (a negative check: nothing happens unasked). */
    private fun holdsFor(what: String, durationMs: Long, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + durationMs
        while (SystemClock.uptimeMillis() < until) {
            if (!condition()) {
                log("STATE when broken ($what): ${describe()}")
                fail("did not hold for $durationMs ms: $what")
            }
            SystemClock.sleep(100)
        }
    }

    /** True while RideMate itself has a notification showing on [channelId] (its own notifications only). */
    private fun ownNotificationOn(channelId: String): Boolean =
        DeviceE2e.context.getSystemService(NotificationManager::class.java).activeNotifications
            .any { it.notification.channelId == channelId }

    private fun appIs(provider: Provider, purpose: DemoAppPurpose): Boolean =
        demo.state.value.app?.let { it.provider == provider && it.purpose == purpose } == true

    private fun cancelCard(provider: Provider): CancelCardItem? =
        ui().items.filterIsInstance<CancelCardItem>().firstOrNull { it.provider == provider }

    /** Taps a button on the guardian's sheet (drawn over the bottom of the screen). */
    private fun tapSheetAction(label: String) {
        val button = waitObject(By.desc(label).pkg(DeviceE2e.PKG), "sheet button \"$label\"", 15_000)
        val bounds = button.visibleBounds
        log("tap sheet: $label at ${bounds.centerX()},${bounds.centerY()}")
        device.click(bounds.centerX(), bounds.centerY())
    }

    /** Top edge of the guardian's sheet over the bottom of the screen, or null when none is open. */
    private fun sheetTop(): Int? {
        val item = ui().activeItem ?: return null
        val title = presenter.present(item).title
        val node = DeviceE2e.find(By.text(title).pkg(DeviceE2e.PKG)) ?: return null
        val padding = (32 * DeviceE2e.context.resources.displayMetrics.density).toInt()
        return node.visibleBounds.top - padding
    }

    private fun ensureOnboarded() {
        val store = graph.appShell.onboardingStore
        if (store.answers.value.completed) return
        // FreshInstallOnboardingTest walks the real screens; when it did not run first, finish onboarding directly.
        log("onboarding not completed: completing it through the store for the demo test")
        store.confirmAdult()
        store.acceptNotice(OnboardingFlow.NOTICE_VERSION)
        store.chooseSignIn(SignInChoice.MANUAL)
        listOf(
            OnboardingStep.NOTIFICATIONS, OnboardingStep.NOTIFICATION_ACCESS, OnboardingStep.OVERLAY,
            OnboardingStep.RESTRICTED_SETTINGS, OnboardingStep.LOCATION, OnboardingStep.BATTERY,
        ).forEach(store::markDone)
        store.setHealthCheck(HealthCheckOutcome.SKIPPED)
        store.markCompleted()
        SystemClock.sleep(200)
    }

    private companion object {
        /** The pinned model (backend `PINNED_MODEL_ID`, architecture §4.10). */
        const val MUSE_SPARK_OPEN = "meta-models/Muse-Spark-Open-816B-A42B"

        /** `eval/demo_labels.json`, copied into the test APK's assets by the `demoLabelsAsset` Gradle task. */
        const val LABELS_ASSET = "e2e/demo_labels.json"

        /** When the first reminder may arrive after the Yes: 30 s (goal 13), with a little room for the tap and the wake. */
        val FIRST_REMINDER_MS = 29_000L..45_000L
    }
}
