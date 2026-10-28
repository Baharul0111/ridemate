@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.ridemate.core.replay

import app.ridemate.core.guardian.GuardianEffect
import app.ridemate.core.guardian.LegRole
import app.ridemate.core.guardian.SheetChoice
import app.ridemate.core.guardian.SheetKind
import app.ridemate.core.guardian.UiKey
import app.ridemate.core.guardian.UserAction
import app.ridemate.core.invariants.Invariants
import app.ridemate.core.model.EvidenceRung
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import app.ridemate.core.replay.Fixtures.OLA
import app.ridemate.core.replay.Fixtures.RAPIDO
import app.ridemate.core.replay.Fixtures.Text
import app.ridemate.core.replay.Fixtures.UBER
import app.ridemate.core.state.Answer
import app.ridemate.core.watch.ClassifierLabel
import app.ridemate.core.watch.Disposition
import app.ridemate.core.watch.PostOutcome
import app.ridemate.core.watch.RemovalReason
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The two product promises: "always reminds" (goal 13, I8) and "never silent" (goal 9, I9). */
class PromisesTest {

    @Test
    fun `always reminds - 30 s, 60 s, 120 s then every 2 min until proof, across a process restart and a reboot`() = runTest {
        val h = ReplayHarness(this)
        h.book(Provider.UBER, Provider.OLA, Provider.RAPIDO)
        h.post(UBER, "u1", Text.uberSearching)
        h.post(OLA, "o1", Text.olaSearching)
        h.post(RAPIDO, "r1", Text.rapidoAssigned())
        val yesAt = testScheduler.currentTime
        h.act(UserAction.AnswerSheet(h.effectsOf<GuardianEffect.DriverSheet>().single().sheetId, SheetChoice.YES_CANCEL_OTHERS, h.tap()))
        assertEquals(RideState.CANCEL_GUIDED, h.state(Provider.UBER))
        assertEquals(RideState.CANCEL_REQUESTED, h.state(Provider.OLA))
        assertTrue(h.history.any { it.second == GuardianEffect.ForegroundService(true) })

        h.advance(100_000)
        h.restart()
        assertEquals("state survives the process", RideState.CANCEL_GUIDED, h.state(Provider.UBER))
        assertEquals(LegRole.EXTRA, h.record(Provider.OLA).role)
        assertTrue(h.guardian.needsForegroundService)

        h.advance(150_000)
        h.restart(reboot = true)
        h.advance(150_000)

        // Rung 4: the user says Uber is cancelled, but its ride notification is still up a minute later.
        h.act(UserAction.ICancelledIt(h.legId(Provider.UBER), h.tap()))
        assertEquals(RideState.USER_SAYS_CANCELLED, h.state(Provider.UBER))
        assertEquals("the chain moves on to Ola", RideState.CANCEL_GUIDED, h.state(Provider.OLA))
        h.advance(60_000)
        val stillLive = h.historyOf<GuardianEffect.LoudAlert>().single()
        assertEquals(UiKey.ALERT_STILL_LIVE, stillLive.second.key)
        assertEquals(RideState.CANCEL_UNCONFIRMED, h.state(Provider.UBER))
        h.advance(30_000)

        h.post(UBER, "u1", Text.uberCancelled)
        assertEquals(RideState.CANCEL_CONFIRMED, h.state(Provider.UBER))
        h.advance(150_000)
        h.post(OLA, "o1", Text.olaCancelled)
        assertEquals(RideState.CANCEL_CONFIRMED, h.state(Provider.OLA))
        assertEquals(listOf(EvidenceRung.TEMPLATE, EvidenceRung.TEMPLATE), h.guardian.state.evidence.map { it.rung })
        h.advance(600_000)

        val reminders = h.historyOf<GuardianEffect.Reminder>()
        assertEquals(listOf(30L, 60L, 120L, 240L, 360L, 480L, 600L).map { yesAt + it * 1000 }, reminders.map { it.first })
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), reminders.map { it.second.attempt })
        assertEquals("both legs until Uber was proven", 2, reminders.first { it.first == yesAt + 480_000 }.second.legs.size)
        assertEquals(listOf(Provider.OLA), reminders.last().second.legs.map { it.provider })
        assertTrue(h.history.any { it.second == GuardianEffect.Banner(null, null, UiKey.BANNER_ALL_CANCELS_DONE) })
        assertEquals(GuardianEffect.ForegroundService(false), h.history.last { it.second is GuardianEffect.ForegroundService }.second)
        assertFalse(h.guardian.needsForegroundService)
        assertTrue(Invariants.checkGuardian(h.guardian).violations.isEmpty())
    }

    @Test
    fun `a restart after a long sleep sends one catch-up reminder, then the schedule continues`() = runTest {
        val h = ReplayHarness(this, providers = listOf(Provider.UBER, Provider.RAPIDO), startTicker = false)
        h.book(Provider.UBER, Provider.RAPIDO)
        h.post(UBER, "u1", Text.uberSearching)
        h.post(RAPIDO, "r1", Text.rapidoAssigned())
        val yesAt = testScheduler.currentTime
        h.act(UserAction.AnswerSheet(h.effectsOf<GuardianEffect.DriverSheet>().single().sheetId, SheetChoice.YES_CANCEL_OTHERS, h.tap()))
        // The process is dead (no ticker) for 7.5 minutes, past the 240, 360 and 420 s reminders.
        h.advance(450_000)
        h.restart()
        h.advance(100_000)
        val times = h.historyOf<GuardianEffect.Reminder>().map { it.first - yesAt }
        assertEquals(listOf(450_000L, 480_000L), times)
    }

    @Test
    fun `reminders stop for a dismissed leg and the foreground service follows I8`() = runTest {
        val h = ReplayHarness(this, providers = listOf(Provider.UBER, Provider.RAPIDO))
        h.book(Provider.UBER, Provider.RAPIDO)
        h.post(UBER, "u1", Text.uberSearching)
        h.post(RAPIDO, "r1", Text.rapidoAssigned())
        h.act(UserAction.AnswerSheet(h.effectsOf<GuardianEffect.DriverSheet>().single().sheetId, SheetChoice.YES_CANCEL_OTHERS, h.tap()))
        h.advance(65_000)
        assertEquals(2, h.historyOf<GuardianEffect.Reminder>().size)
        val dismiss = h.tap()
        h.act(UserAction.DismissLeg(h.legId(Provider.UBER), dismiss))
        assertTrue(h.guardian.state.actions.any { it.tapId == dismiss && it.kind == app.ridemate.core.guardian.ActionKind.DISMISS })
        assertFalse(h.guardian.needsForegroundService)
        h.advance(600_000)
        assertEquals(2, h.historyOf<GuardianEffect.Reminder>().size)
        assertEquals(GuardianEffect.ForegroundService(false), h.historyOf<GuardianEffect.ForegroundService>().last().second)
    }

    @Test
    fun `never silent - random allowlisted posts on booked legs each yield an event, a prompt or a logged duplicate`() {
        var covered = 0
        val dispositions = mutableSetOf<Disposition>()
        val outcomes = mutableSetOf<PostOutcome>()
        repeat(60) { seed ->
            runTest {
                val rnd = Random(seed)
                val classifier = FakeClassifier(
                    mode = listOf(
                        FakeClassifier.Mode.LABELS, FakeClassifier.Mode.LABELS, FakeClassifier.Mode.LABELS,
                        FakeClassifier.Mode.DOWN, FakeClassifier.Mode.ANONYMOUS, FakeClassifier.Mode.HANG,
                    ).random(rnd),
                    latencyMs = rnd.nextLong(0, 8_000),
                ).apply {
                    rules += "accepted" to ClassifierLabel.ASSIGNED
                    rules += "matching" to ClassifierLabel.SEARCHING
                    rules += "gone" to ClassifierLabel.CANCELLED
                }
                val h = ReplayHarness(this, classifier = classifier, keepSearching = rnd.nextBoolean())
                h.book(Provider.UBER, Provider.OLA, Provider.RAPIDO)
                repeat(40) { step -> randomStep(h, rnd, step) }
                h.advance(30_000)

                val audit = h.pipeline.audit
                val violations = Invariants.checkI9(audit, h.now)
                assertTrue("seed $seed: $violations\n${audit.joinToString("\n")}", violations.isEmpty())
                assertTrue("seed $seed", audit.filter { it.coveredByI9 }.none { it.outcome == PostOutcome.PENDING_CLASSIFICATION })
                val report = Invariants.checkGuardian(h.guardian)
                assertTrue("seed $seed: ${report.violations}", report.ok)
                covered += audit.count { it.coveredByI9 }
                audit.mapNotNullTo(dispositions) { it.disposition }
                audit.mapTo(outcomes) { it.outcome }
            }
        }
        assertTrue("the property saw real traffic: $covered", covered > 1_000)
        assertTrue("dispositions seen: $dispositions", dispositions.containsAll(Disposition.entries))
        assertTrue(
            "outcomes seen: $outcomes",
            outcomes.containsAll(listOf(PostOutcome.EVENT, PostOutcome.DUPLICATE_DROPPED, PostOutcome.SUPERSEDED, PostOutcome.COALESCED)),
        )
    }

    private val wordings: Map<String, List<Pair<String, String>>> = mapOf(
        RAPIDO to listOf(
            Text.rapidoSearching, Text.rapidoAssigned(), Text.rapidoAssigned(eta = 2, plate = "AS02XY9876"), Text.rapidoArriving,
            Text.rapidoDriverCancelled, Text.rapidoNoDrivers, Text.rapidoCancelled, Text.rapidoTripStarted, Text.rapidoEnded,
        ),
        UBER to listOf(Text.uberSearching, Text.uberAssigned(), Text.uberAssigned(eta = 9, plate = "AS01AB1234"), Text.uberDriverCancelled, Text.uberNoDrivers, Text.uberCancelled),
        OLA to listOf(Text.olaSearching, Text.olaAssigned(), Text.olaAssigned(eta = 1, plate = "AS01AB1234"), Text.olaDriverCancelled, Text.olaNoDrivers, Text.olaCancelled),
    )

    private val misses = listOf(
        "Captain accepted, meet at pickup %d", "Still matching you %d", "Your ride is gone %d", "Promo: %d%% off", "Update %d",
    )

    private fun randomStep(h: ReplayHarness, rnd: Random, step: Int) {
        val pkg = listOf(UBER, OLA, RAPIDO).random(rnd)
        val key = "$pkg-${rnd.nextInt(2)}"
        when (rnd.nextInt(13)) {
            in 0..4 -> h.post(pkg, key, wordings.getValue(pkg).random(rnd))
            12 -> repeat(3) { i ->
                // A burst of rewordings on one key while a classification is in flight.
                h.post(pkg, key, pkg.substringAfterLast('.'), misses.random(rnd).format(i * 7 + rnd.nextInt(5)) + " " + "!".repeat(i))
                h.advance(700)
            }
            5, 6 -> h.post(pkg, key, pkg.substringAfterLast('.'), misses.random(rnd).format(rnd.nextInt(60)))
            7 -> h.post(pkg, key, null, null, id = rnd.nextInt(1, 4), channel = listOf("otp", Fixtures.rideChannel(pkg)).random(rnd), ongoing = rnd.nextBoolean())
            8, 9 -> h.pipeline.audit.lastOrNull { it.packageName == pkg }?.let {
                h.post(pkg, key, wordings.getValue(pkg).first())
                h.post(pkg, key, wordings.getValue(pkg).first())
            }
            10 -> h.remove(pkg, key, if (rnd.nextBoolean()) RemovalReason.REASON_APP_CANCEL else RemovalReason.REASON_CANCEL)
            else -> answerSomething(h, rnd)
        }
        if (rnd.nextInt(3) == 0) answerSomething(h, rnd)
        h.advance(rnd.nextLong(0, 5_000) + if (step % 10 == 9) 130_000 else 0)
    }

    private fun answerSomething(h: ReplayHarness, rnd: Random) {
        val state = h.guardian.state
        val prompt = state.prompts.randomOrNull(rnd)
        val sheet = state.sheets.randomOrNull(rnd)
        when {
            prompt != null && rnd.nextBoolean() ->
                h.act(UserAction.AnswerPrompt(prompt.promptId, listOf(Answer.YES, Answer.NOT_YET, Answer.DRIVER_CANCELLED, Answer.NO_IT_ENDED, Answer.CHECK_SCREEN).random(rnd), h.tap()))
            sheet != null -> {
                val choice = when (sheet.kind) {
                    SheetKind.CANCEL_OTHERS -> listOf(SheetChoice.YES_CANCEL_OTHERS, SheetChoice.KEEP_SEARCHING, SheetChoice.NOT_NOW).random(rnd)
                    SheetKind.TWO_DRIVERS, SheetKind.SAME_DRIVER -> listOf(SheetChoice.PICK, SheetChoice.NOT_NOW).random(rnd)
                    else -> listOf(SheetChoice.SWITCH, SheetChoice.KEEP_CURRENT, SheetChoice.NOT_NOW).random(rnd)
                }
                h.act(UserAction.AnswerSheet(sheet.sheetId, choice, h.tap(), sheet.bindings.random(rnd).legId))
            }
            else -> state.legs.values.filter { it.role == LegRole.EXTRA }.randomOrNull(rnd)?.let {
                h.act(UserAction.ICancelledIt(it.legId, h.tap()))
            }
        }
    }
}
