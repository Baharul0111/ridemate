package app.ridemate.session

import android.app.Notification
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.core.guardian.UiAction
import app.ridemate.core.guardian.UiKey
import app.ridemate.core.model.EvidenceRung
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import app.ridemate.data.EvidenceSource
import app.ridemate.data.OutboxKind
import app.ridemate.data.SessionStatus
import app.ridemate.notify.CancelCardItem
import app.ridemate.notify.DriverSheetItem
import app.ridemate.notify.GuardianChannels
import app.ridemate.notify.GuardianTap
import app.ridemate.notify.PromptItem
import app.ridemate.notify.ReminderItem
import app.ridemate.notify.SessionOp
import app.ridemate.notify.SessionOpKind
import app.ridemate.notify.TapScope
import app.ridemate.session.GuardianTestRig.Companion.RAPIDO_ASSIGNED
import app.ridemate.session.GuardianTestRig.Companion.RAPIDO_SEARCHING
import app.ridemate.session.GuardianTestRig.Companion.UBER_CANCELLED
import app.ridemate.session.GuardianTestRig.Companion.UBER_SEARCHING
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuardianRuntimeTest {
    private val rigs = mutableListOf<GuardianTestRig>()

    private fun rig(make: () -> GuardianTestRig = { GuardianTestRig() }): GuardianTestRig = make().also { rigs += it }.start()

    @After
    fun tearDown() {
        rigs.forEach { it.kill() }
        rigs.firstOrNull()?.db?.close()
    }

    @Test
    fun `a session start mirrors the session and legs to Room and starts the service`() = runBlocking {
        val r = rig()
        r.startSession(Provider.UBER, Provider.RAPIDO)

        assertTrue(r.status.sessionOpen)
        assertTrue(r.status.shouldRunService)
        assertEquals(1, r.serviceStarts.get())
        val session = r.db.sessionDao().get(GuardianTestRig.SESSION)!!
        assertEquals(SessionStatus.ACTIVE, session.status)
        assertEquals(listOf(Provider.UBER, Provider.RAPIDO), session.selectedApps)
        assertEquals(setOf(RideState.SELECTED), r.db.legDao().forSession(session.id).map { it.state }.toSet())
        assertNotNull(r.db.guardianSnapshotDao().get(session.id))
    }

    @Test
    fun `first driver shows the cancel-the-others sheet as a heads-up notification with its buttons`() = runBlocking {
        val r = rig()
        r.startSession(Provider.UBER, Provider.RAPIDO)
        r.book(Provider.UBER, Provider.RAPIDO)
        r.post(Provider.UBER, "u1", UBER_SEARCHING.first, UBER_SEARCHING.second)
        r.post(Provider.RAPIDO, "r1", RAPIDO_SEARCHING.first, RAPIDO_SEARCHING.second)
        r.post(Provider.RAPIDO, "r1", RAPIDO_ASSIGNED.first, RAPIDO_ASSIGNED.second)

        val sheet = r.ui.items.filterIsInstance<DriverSheetItem>().single()
        assertEquals(UiKey.SHEET_CANCEL_OTHERS, sheet.key)
        // The driver's ETA comes from Rapido's own notification (D8), the other leg's fee note from fee_rules.
        assertEquals(4, sheet.drivers.single().etaMin)
        assertEquals(Provider.UBER, sheet.others.single().provider)
        val notification = r.notification(sheet.id)!!
        assertEquals(GuardianChannels.PROMPTS, notification.channelId)
        assertEquals("Cancel the others?", r.title(notification))
        assertEquals(listOf("Yes, cancel the others", "Not now"), r.actionTitles(sheet.id))
        val body = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertTrue(body, body.contains("Rapido found a captain."))
        assertTrue(body, body.contains("4 min away (from Rapido notification)"))
        assertTrue(body, body.contains("Uber: Usually free to cancel: no driver yet"))
    }

    @Test
    fun `yes starts the guided cancel, opens the next app under the background rule and keeps the service (I8)`() = runBlocking {
        val r = rig()
        r.installApp(Provider.UBER)
        r.firstDriverChain()
        val uber = r.legId(Provider.UBER)

        assertTrue(r.status.needsForegroundService)
        assertEquals(1, r.status.pendingExtras)
        val card = r.ui.items.filterIsInstance<CancelCardItem>().single()
        assertEquals(uber, card.legId)
        assertEquals(Provider.UBER, r.bubble.cards.last().provider)
        // Background, bubble hidden: a heads-up whose tap opens Uber, never a silent activity start.
        val open = r.notification("open:$uber")!!
        assertEquals("Open Uber to cancel", r.title(open))
        assertNotNull(open.contentIntent)
        // The cancel steps come from cancel_cards, rendered with the card.
        val cardText = r.notification(card.id)!!.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertTrue(cardText, cardText.contains("Cancel ride"))
        // The reminder schedule is mirrored in the outbox: first reminder 30 s after the extra leg became pending.
        val reminder = r.outboxRepository.pending().single { it.kind == OutboxKind.REMINDER }
        assertEquals(uber, reminder.legId)
        assertEquals(r.clock.elapsed + 30_000, reminder.dueElapsedMs)
        assertTrue(r.outboxRepository.pending().none { it.kind == OutboxKind.OPEN_NEXT_APP })
    }

    @Test
    fun `with RideMate in front the next app opens directly`() = runBlocking {
        val r = rig()
        r.installApp(Provider.UBER)
        r.appInForeground = true
        r.firstDriverChain()

        val started = org.robolectric.Shadows.shadowOf(r.context as android.app.Application).nextStartedActivity
        assertEquals("com.ubercab", started.component?.packageName)
        assertNull(r.notification("open:${r.legId(Provider.UBER)}"))
    }

    @Test
    fun `reminders fire at 30 s and 60 s with a heads-up and a bubble pulse until the cancel is proven`() = runBlocking {
        val r = rig()
        r.firstDriverChain()
        val uber = r.legId(Provider.UBER)

        r.advance(29_000)
        r.serviceTick()
        assertEquals(0, r.bubble.pulses.get())

        r.advance(1_000)
        r.serviceTick()
        assertEquals(1, r.bubble.pulses.get())
        val reminder = r.notification(ReminderItem.ID)!!
        assertEquals(GuardianChannels.REMINDERS, reminder.channelId)
        assertEquals("Still booked: Uber", r.title(reminder))
        assertEquals(listOf("Open Uber", "I cancelled it", "Dismiss"), r.actionTitles(ReminderItem.ID))
        assertEquals(r.clock.elapsed + 30_000, r.outboxRepository.pending().single { it.kind == OutboxKind.REMINDER }.dueElapsedMs)

        r.advance(30_000)
        r.serviceTick()
        assertEquals(2, r.bubble.pulses.get())

        // Uber's own cancelled post: rung 2. The leg is proven, reminders stop, the evidence is stored.
        r.post(Provider.UBER, "u2", UBER_CANCELLED.first, UBER_CANCELLED.second, ongoing = false)
        assertEquals(RideState.CANCEL_CONFIRMED, r.db.legDao().get(uber)!!.state)
        assertEquals(EvidenceRung.TEMPLATE, r.ui.leg(uber)!!.evidence)
        val evidence = r.db.evidenceDao().forLeg(uber).single()
        assertEquals(EvidenceRung.TEMPLATE, evidence.rung)
        assertEquals(EvidenceSource.TEMPLATE, evidence.source)
        assertFalse(r.status.needsForegroundService)
        assertTrue(r.outboxRepository.pending().none { it.kind == OutboxKind.REMINDER })
        assertNull(r.notification(ReminderItem.ID))
        assertTrue(r.ui.notices.any { it.key == UiKey.BANNER_CANCEL_CONFIRMED })
        assertTrue(r.bubble.clears.get() > 0)
    }

    @Test
    fun `stop waits for I8 and closes the session once every extra booking is settled`() = runBlocking {
        val r = rig()
        r.firstDriverChain()

        r.runtime.perform(SessionOp(SessionOpKind.STOP))
        r.idle()
        assertTrue(r.status.sessionOpen)
        assertTrue(r.status.shouldRunService)
        assertTrue(r.ui.stopBlocked)

        r.post(Provider.UBER, "u2", UBER_CANCELLED.first, UBER_CANCELLED.second, ongoing = false)
        assertFalse(r.status.sessionOpen)
        assertFalse(r.status.shouldRunService)
        assertEquals(SessionStatus.CLOSED, r.db.sessionDao().get(GuardianTestRig.SESSION)!!.status)
    }

    @Test
    fun `a dismissed extra leg stops reminders and lets the service stop (logged dismissal)`() = runBlocking {
        val r = rig()
        r.firstDriverChain()
        val uber = r.legId(Provider.UBER)

        r.runtime.perform(GuardianTap(TapScope.LEG, uber, UiAction.DISMISS))
        r.idle()

        assertFalse(r.status.needsForegroundService)
        assertTrue(r.db.legDao().get(uber)!!.dismissed)
        assertTrue(r.outboxRepository.pending().none { it.kind == OutboxKind.REMINDER })
        assertTrue(r.ui.notices.any { it.key == UiKey.BANNER_LEG_DISMISSED })
    }

    @Test
    fun `an unreadable update raises the MAYBE prompt with its exact buttons, and a template hit withdraws it with the reason`() = runBlocking {
        val r = rig()
        r.startSession(Provider.UBER, Provider.RAPIDO)
        r.book(Provider.UBER, Provider.RAPIDO)
        r.post(Provider.RAPIDO, "r1", RAPIDO_SEARCHING.first, RAPIDO_SEARCHING.second)
        // Reworded, no classifier: UNKNOWN_UPDATE → MAYBE_ASSIGNED.
        r.post(Provider.RAPIDO, "r1", "Rapido", "Ravi will reach you soon")

        val prompt = r.ui.items.filterIsInstance<PromptItem>().single()
        assertEquals(UiKey.PROMPT_MAYBE_ASSIGNED, prompt.key)
        assertEquals("Rapido has an update. Got a driver?", r.title(r.notification(prompt.id)))
        assertEquals(listOf("Yes, cancel the others", "Not yet", "Check screen"), r.actionTitles(prompt.id))

        r.post(Provider.RAPIDO, "r1", RAPIDO_ASSIGNED.first, RAPIDO_ASSIGNED.second)
        assertTrue(r.ui.items.none { it is PromptItem })
        assertNull(r.notification(prompt.id))
        val notice = r.ui.notices.first { it.key == UiKey.PROMPT_WITHDRAWN_DRIVER_ASSIGNED }
        assertEquals(listOf(Provider.RAPIDO), notice.providers)
        assertEquals("Rapido confirmed a driver, so the question was closed.", r.title(r.notification(notice.id)))
    }

    @Test
    fun `the snapshot survives process death and the restored guardian keeps reminding`() = runBlocking {
        val first = rig()
        first.firstDriverChain()
        val uber = first.legId(Provider.UBER)
        first.kill()

        val second = rig { GuardianTestRig(db = first.db, clock = first.clock) }
        assertTrue(second.status.restored)
        assertTrue(second.status.sessionOpen)
        assertTrue(second.status.needsForegroundService)
        assertEquals(uber, second.legId(Provider.UBER))
        // The open card and sheet state came back with it.
        assertTrue(second.ui.items.any { it is CancelCardItem && it.legId == uber })
        // The restarted process brings the session service back.
        assertEquals(1, second.serviceStarts.get())

        second.advance(30_000)
        second.serviceTick()
        assertEquals(1, second.bubble.pulses.get())
        assertEquals("Still booked: Uber", second.title(second.notification(ReminderItem.ID)))
    }

    @Test
    fun `after a reboot overdue reminders collapse into one and the outbox follows the rebased schedule`() = runBlocking {
        val first = rig()
        first.firstDriverChain()
        first.kill()
        // Five minutes powered off; the monotonic clock restarts near zero.
        first.clock.wall += 5 * 60_000L
        first.clock.elapsed = 10_000L

        val second = rig { GuardianTestRig(db = first.db, clock = first.clock, bootIds = app.ridemate.testing.FakeBootIds("boot-2")) }

        assertEquals(1, second.bubble.pulses.get())
        assertTrue(second.ui.items.any { it is ReminderItem })
        val reminder = second.outboxRepository.pending().single { it.kind == OutboxKind.REMINDER }
        assertTrue(reminder.dueElapsedMs > second.clock.elapsed)
    }

    @Test
    fun `I cancelled it schedules the +60 s follow-up, which alerts loudly while the ride notification is still there`() = runBlocking {
        val r = rig()
        r.firstDriverChain()
        val uber = r.legId(Provider.UBER)

        r.runtime.perform(GuardianTap(TapScope.LEG, uber, UiAction.I_CANCELLED_IT))
        r.idle()
        assertEquals(RideState.USER_SAYS_CANCELLED, r.ui.leg(uber)!!.state)
        assertEquals(EvidenceRung.USER, r.ui.leg(uber)!!.evidence)
        val followUp = r.outboxRepository.pending().single { it.kind == OutboxKind.FOLLOWUP_CHECK }
        assertEquals(r.clock.elapsed + 60_000, followUp.dueElapsedMs)
        assertTrue(r.status.needsForegroundService)

        // Only the outbox drives this check (as WorkManager would with no service running).
        r.advance(60_000)
        runBlocking { r.outboxDriver.tick() }
        r.idle()
        val alertId = "alert:$uber:${UiKey.ALERT_STILL_LIVE.name}"
        val alert = r.notification(alertId)!!
        assertEquals(GuardianChannels.ALERTS, alert.channelId)
        assertTrue(alert.flags and Notification.FLAG_INSISTENT != 0)
        assertEquals("Uber still looks active", r.title(alert))
        assertEquals(listOf("Open Uber", "Check screen", "Dismiss"), r.actionTitles(alertId))
        assertEquals(RideState.CANCEL_UNCONFIRMED, r.ui.leg(uber)!!.state)
    }

    @Test
    fun `the follow-up passes quietly when the ride notification went away`() = runBlocking {
        val r = rig()
        r.firstDriverChain()
        val uber = r.legId(Provider.UBER)
        r.runtime.perform(GuardianTap(TapScope.LEG, uber, UiAction.I_CANCELLED_IT))
        r.idle()
        r.remove(Provider.UBER, "u1", app.ridemate.core.watch.RemovalReason.REASON_CANCEL)

        r.advance(60_000)
        r.serviceTick()

        assertNull(r.notification("alert:$uber:${UiKey.ALERT_STILL_LIVE.name}"))
        assertEquals(RideState.USER_SAYS_CANCELLED, r.ui.leg(uber)!!.state)
        assertTrue(r.outboxRepository.pending().none { it.kind == OutboxKind.FOLLOWUP_CHECK })
    }

    @Test
    fun `demo sessions take demo posts only and never live ones`() = runBlocking {
        val r = rig()
        r.startSession(Provider.DEMO_KESTREL, Provider.DEMO_PEPPER, demo = true)
        r.book(Provider.DEMO_KESTREL, Provider.DEMO_PEPPER)
        val pepper = r.legId(Provider.DEMO_PEPPER)
        r.runtime.onDemoPost(
            app.ridemate.core.watch.PostSnapshot(
                "demo.pepper", "p1", 1, null, "pepper_ride_status", "status", true,
                "Driver assigned", "Ravi · XX 00 DM 4821 · arriving in 9 min · 2.8 km away", r.clock.wall, 1L,
            ),
        )
        r.idle()

        assertTrue(r.status.demo)
        assertEquals(RideState.ASSIGNED, r.ui.leg(pepper)!!.state)
        assertEquals(9, r.ui.leg(pepper)!!.driver!!.etaMin)
        assertEquals(2800, r.ui.leg(pepper)!!.driver!!.distanceMeters)
        assertEquals(null, r.runtime.legIdFor(Provider.UBER))
    }

    @Test
    fun `booked here from the ongoing notification books the app opened last`() = runBlocking {
        val r = rig()
        r.startSession(Provider.UBER, Provider.RAPIDO)
        r.runtime.appOpened(Provider.RAPIDO)
        r.idle()
        r.runtime.perform(SessionOp(SessionOpKind.BOOKED_HERE))
        r.idle()

        assertEquals(RideState.BOOK_REQUESTED, r.ui.leg(r.legId(Provider.RAPIDO))!!.state)
        assertEquals(RideState.SELECTED, r.ui.leg(r.legId(Provider.UBER))!!.state)
    }

    @Test
    fun `keep searching toggles the preference and applies to a session with nothing booked`() = runBlocking {
        val r = rig()
        r.startSession(Provider.UBER, Provider.RAPIDO)
        r.runtime.appOpened(Provider.UBER)
        r.idle()
        r.runtime.perform(SessionOp(SessionOpKind.TOGGLE_KEEP_SEARCHING))
        r.idle()

        assertTrue(r.keepSearching.enabled)
        assertTrue(r.ui.keepSearching)
        assertEquals(RideState.OPENED, r.ui.leg(r.legId(Provider.UBER))!!.state)
    }

    @Test
    fun `a new session is refused while the previous one still has bookings to cancel`() = runBlocking {
        val r = rig()
        r.firstDriverChain()
        r.startSession(Provider.OLA, id = "s2")

        assertEquals(GuardianTestRig.SESSION, r.status.sessionId)
        assertNotNull(r.notification(app.ridemate.notify.GuardianPresenter.START_BLOCKED_ID))
    }

    @Test
    fun `when Android refuses the service start from the background a resume notification is posted`() = runBlocking {
        val r = GuardianTestRig().also { rigs += it }
        r.serviceStartResult = false
        r.start()
        r.startSession(Provider.UBER)

        assertNotNull(r.notification(app.ridemate.notify.GuardianPresenter.RESUME_ID))
    }

    @Test
    fun `erase forgets the session and its notifications`() = runBlocking {
        val r = rig()
        r.firstDriverChain()
        r.runtime.wipe()
        r.idle()

        assertFalse(r.status.sessionOpen)
        assertTrue(r.ui.items.isEmpty())
        assertTrue(r.notifications().isEmpty())
    }

    @Test
    fun `no exceptions were swallowed along the way`() = runBlocking {
        val r = rig()
        r.firstDriverChain()
        r.advance(30_000)
        r.serviceTick()
        assertTrue(r.crashBackend.recorded.toString(), r.crashBackend.recorded.isEmpty())
    }
}
