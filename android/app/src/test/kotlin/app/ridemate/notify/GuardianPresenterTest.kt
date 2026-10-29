package app.ridemate.notify

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.core.guardian.CaptureReason
import app.ridemate.core.guardian.DriverCard
import app.ridemate.core.guardian.FeeNote
import app.ridemate.core.guardian.FeeNoteKind
import app.ridemate.core.guardian.GuardianEffect
import app.ridemate.core.guardian.LegFee
import app.ridemate.core.guardian.LegRef
import app.ridemate.core.guardian.LegRole
import app.ridemate.core.guardian.OndcCommandKind
import app.ridemate.core.guardian.OpenPurpose
import app.ridemate.core.guardian.UiAction
import app.ridemate.core.guardian.UiKey
import app.ridemate.core.model.EtaSource
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import app.ridemate.core.state.TapId
import app.ridemate.session.AssetGuardianConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class GuardianPresenterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = AssetGuardianConfig(context)
    private val en = presenter(context)
    private val hi = presenter(localized("hi"))

    private fun localized(language: String): Context =
        context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale(language)) })

    private fun presenter(ctx: Context) = GuardianPresenter(GuardianText(ctx), CancelStepsSource { config.cancelSteps(it) })

    private val uber = DriverCard("s:uber", Provider.UBER, 0, 4, EtaSource.TEMPLATE, 1_200, EtaSource.TEMPLATE)
    private val ola = DriverCard("s:ola", Provider.OLA, 0, null, EtaSource.NONE, null, EtaSource.NONE)
    private val rapidoCloser = DriverCard("s:rapido", Provider.RAPIDO, 1, 2, EtaSource.VERIFIED, 600, EtaSource.VERIFIED)

    private val base = GuardianUiState(
        sessionId = "s",
        legs = listOf(Provider.UBER, Provider.OLA, Provider.RAPIDO, Provider.ONDC).map { p ->
            LegUi("s:${p.wireId}", p, RideState.SEARCHING, 0, LegRole.NONE, dismissed = false, booked = true, endedByApp = false)
        },
    )

    /** One instance of every GuardianEffect type (20), with the variants that change wording or buttons. */
    private val sheetCancelOthers = GuardianEffect.DriverSheet(
        "sheet-1", UiKey.SHEET_CANCEL_OTHERS, listOf(uber),
        listOf(LegFee("s:rapido", Provider.RAPIDO, FeeNote(FeeNoteKind.FREE_WHILE_SEARCHING))), listOf(UiAction.YES_CANCEL_OTHERS, UiAction.NOT_NOW),
    )
    private val sheetTwoDrivers = GuardianEffect.DriverSheet("sheet-2", UiKey.SHEET_TWO_DRIVERS_PICK_ONE, listOf(uber, ola), emptyList(), listOf(UiAction.PICK, UiAction.NOT_NOW))
    private val sheetSameDriver = GuardianEffect.DriverSheet("sheet-3", UiKey.SHEET_SAME_DRIVER_KEEP_ONE, listOf(uber, ola), emptyList(), listOf(UiAction.PICK, UiAction.NOT_NOW))
    private val betterRide = GuardianEffect.BetterRideSheet(
        "sheet-4", UiKey.SHEET_BETTER_RIDE, uber.copy(etaMin = 9), rapidoCloser, FeeNote(FeeNoteKind.MAY_APPLY, upToInr = 50),
        FeeNote(FeeNoteKind.FREE_WINDOW, freeForSeconds = 40), listOf(UiAction.SWITCH, UiAction.KEEP_CURRENT),
    )
    private val maybeAssigned = GuardianEffect.MaybePrompt(
        "prompt-1", "s:rapido", Provider.RAPIDO, 0, UiKey.PROMPT_MAYBE_ASSIGNED, listOf(UiAction.YES_CANCEL_OTHERS, UiAction.NOT_YET, UiAction.CHECK_SCREEN), 0,
    )
    private val maybeChanged = GuardianEffect.MaybePrompt(
        "prompt-2", "s:rapido", Provider.RAPIDO, 1, UiKey.PROMPT_MAYBE_CHANGED, listOf(UiAction.YES, UiAction.DRIVER_CANCELLED, UiAction.CHECK_SCREEN), 0,
    )
    private val maybeNewDriver = GuardianEffect.MaybePrompt(
        "prompt-3", "s:uber", Provider.UBER, 1, UiKey.PROMPT_MAYBE_NEW_DRIVER, listOf(UiAction.YES, UiAction.NO_IT_ENDED, UiAction.CHECK_SCREEN), 1,
    )
    private val cancelCard = GuardianEffect.CancelCard(
        "s:ola", Provider.OLA, RideState.SEARCHING, FeeNote(FeeNoteKind.FREE_WHILE_SEARCHING), 1, 2,
        listOf(UiAction.SHOW_ME, UiAction.I_CANCELLED_IT, UiAction.CHECK_SCREEN, UiAction.DISMISS),
    )
    private val keptLost = GuardianEffect.LoudAlert("s:rapido", Provider.RAPIDO, UiKey.ALERT_KEPT_LEG_LOST, listOf(UiAction.COMPARE_AGAIN, UiAction.OPEN_APP))

    private val everyEffect: List<GuardianEffect> = listOf(
        sheetCancelOthers,
        betterRide,
        GuardianEffect.SheetWithdrawn("sheet-1", UiKey.SHEET_WITHDRAWN_DRIVER_CANCELLED),
        maybeAssigned,
        GuardianEffect.PromptWithdrawn("prompt-1", "s:rapido", UiKey.PROMPT_WITHDRAWN_DRIVER_ASSIGNED),
        GuardianEffect.CheckScreenPrompt("s:ola", Provider.OLA),
        GuardianEffect.OpenApp("s:ola", Provider.OLA, OpenPurpose.CANCEL, TapId("t1")),
        cancelCard,
        GuardianEffect.RequestCapture("s:ola", Provider.OLA, CaptureReason.CANCEL_PROOF),
        GuardianEffect.Reminder(2, listOf(LegRef("s:ola", Provider.OLA, RideState.CANCEL_GUIDED)), headsUp = true, bubblePulse = true),
        keptLost,
        GuardianEffect.RideBanner(uber, RideState.ASSIGNED),
        GuardianEffect.Banner("s:ola", Provider.OLA, UiKey.BANNER_CANCEL_CONFIRMED),
        GuardianEffect.SameDriverNotice(listOf("s:uber", "s:ola")),
        GuardianEffect.PayHandoffOffer("s:uber", Provider.UBER),
        GuardianEffect.OndcCommand("s:ondc", OndcCommandKind.BOOK, TapId("t2"), "item-1"),
        GuardianEffect.OndcCancelFee("s:ondc", 30),
        GuardianEffect.ForegroundService(true),
        GuardianEffect.CompareAgain("s"),
        GuardianEffect.AnswerRejected(TapId("t3")),
    )

    /** The cards [effect] adds on top of [before] (items, then notices). */
    private fun cards(effect: GuardianEffect, p: GuardianPresenter = en, before: GuardianUiState = base): List<GuardianCard> {
        val after = GuardianUiReducer.apply(before, effect, NOW)
        return after.items.filter { it !in before.items }.map(p::present) + after.notices.filter { it !in before.notices }.map(p::notice)
    }

    private fun GuardianCard.labels() = actions.map { it.label }

    @Test
    fun `every effect type renders, except the two that are side effects`() {
        assertEquals(20, everyEffect.map { it::class }.distinct().size)
        var state = base
        for (effect in everyEffect) {
            val rendered = cards(effect, before = state)
            val sideEffect = effect is GuardianEffect.OpenApp || effect is GuardianEffect.ForegroundService
            assertEquals(effect.toString(), !sideEffect, rendered.isNotEmpty())
            rendered.forEach { card -> assertTrue(card.title.isNotBlank()) }
            state = GuardianUiReducer.apply(state, effect, NOW)
        }
    }

    @Test
    fun `cancel the others shows driver ETA and distance with their source, and every other leg's fee note`() {
        val card = cards(sheetCancelOthers).single()
        assertEquals("Cancel the others?", card.title)
        assertEquals(GuardianChannels.PROMPTS, card.channel)
        assertEquals(CardStyle.HEADS_UP, card.style)
        assertEquals(listOf("Yes, cancel the others", "Not now"), card.labels())
        assertEquals(GuardianTap(TapScope.SHEET, "sheet-1", UiAction.YES_CANCEL_OTHERS), card.actions[0].target)
        assertTrue(card.lines.toString(), "Uber found a driver." in card.lines)
        assertTrue(card.lines.toString(), "Uber: 4 min away (from Uber notification) · 1.2 km away (from Uber notification)" in card.lines)
        assertTrue(card.lines.toString(), "Rapido: Usually free to cancel: no driver yet" in card.lines)
    }

    @Test
    fun `two drivers lets the user pick, and an unverified driver says see in the app`() {
        val card = cards(sheetTwoDrivers).single()
        assertEquals("Two drivers found. Pick one", card.title)
        assertEquals(listOf("Pick Uber", "Pick Ola", "Not now"), card.labels())
        assertEquals(GuardianTap(TapScope.SHEET, "sheet-2", UiAction.PICK, pickedLegId = "s:ola"), card.actions[1].target)
        assertTrue(card.lines.toString(), "Ola: ETA and distance: see in Ola" in card.lines)

        assertEquals("Same driver on both. Keep one.", cards(sheetSameDriver).single().title)
    }

    @Test
    fun `better ride shows both drivers and the fee exposure, and changes nothing without Yes`() {
        val card = cards(betterRide).single()
        assertEquals("A closer driver is available", card.title)
        assertEquals(listOf("Yes, switch", "No, keep current"), card.labels())
        assertTrue(card.lines.toString(), "Nothing changes unless you tap Yes." in card.lines)
        assertTrue(card.lines.toString(), card.lines.any { it.startsWith("Now: Uber: 9 min away") })
        assertTrue(card.lines.toString(), card.lines.any { it.startsWith("New: Rapido: 2 min away (checked on screen) · 600 m away") })
        assertTrue(card.lines.toString(), "If you switch, cancelling Uber: A cancellation fee may apply, up to ₹50" in card.lines)
        assertTrue(card.lines.toString(), "If you keep yours, cancelling Rapido: Free to cancel for about 40 more seconds" in card.lines)
    }

    @Test
    fun `MAYBE prompts carry their exact buttons (section 4_5_6)`() {
        val assigned = cards(maybeAssigned).single()
        assertEquals("Rapido has an update. Got a driver?", assigned.title)
        assertEquals(listOf("Yes, cancel the others", "Not yet", "Check screen"), assigned.labels())
        assertEquals(GuardianTap(TapScope.PROMPT, "prompt-1", UiAction.YES_CANCEL_OTHERS), assigned.actions[0].target)

        val changed = cards(maybeChanged).single()
        assertEquals("Rapido has an update. Is your captain still coming?", changed.title)
        assertEquals(listOf("Yes", "Driver cancelled", "Check screen"), changed.labels())

        val newDriver = cards(maybeNewDriver).single()
        assertEquals("Uber has an update. Is a new driver coming?", newDriver.title)
        assertEquals(listOf("Yes", "No, it ended", "Check screen"), newDriver.labels())
        assertTrue(newDriver.alertAgain)
    }

    @Test
    fun `withdrawn sheets and prompts leave a notice that says why`() {
        val withSheet = GuardianUiReducer.apply(base, sheetCancelOthers, NOW)
        val sheetNotice = cards(GuardianEffect.SheetWithdrawn("sheet-1", UiKey.SHEET_WITHDRAWN_DRIVER_CANCELLED), before = withSheet).single()
        assertEquals("Uber's driver cancelled. Keep the other searches?", sheetNotice.title)

        val withPrompt = GuardianUiReducer.apply(base, maybeAssigned, NOW)
        val promptNotice = cards(GuardianEffect.PromptWithdrawn("prompt-1", "s:rapido", UiKey.PROMPT_WITHDRAWN_SEARCH_EXPIRED), before = withPrompt).single()
        assertEquals("Rapido ended the search, so the question was closed.", promptNotice.title)

        // Answered questions and replaced sheets just go away.
        assertTrue(cards(GuardianEffect.PromptWithdrawn("prompt-1", "s:rapido", UiKey.PROMPT_ANSWERED), before = withPrompt).isEmpty())
        assertTrue(cards(GuardianEffect.SheetWithdrawn("sheet-1", UiKey.SHEET_REPLACED), before = withSheet).isEmpty())
    }

    @Test
    fun `cancel card, reminder, kept-leg-lost alert and pay handoff`() {
        val card = cards(cancelCard).single()
        assertEquals("Cancel Ola (1 of 2)", card.title)
        assertEquals(listOf("Show me", "I cancelled it", "Check screen", "Dismiss"), card.labels())
        assertTrue(card.steps.isNotEmpty())
        assertTrue(card.lines.toString(), card.lines.any { it.contains("Cancel") })
        assertEquals("Stop reminding about Ola", card.actions.last().description)
        assertEquals(CardOpen.RideApp(Provider.OLA, OpenPurpose.CANCEL), card.open)

        val reminder = cards(everyEffect.filterIsInstance<GuardianEffect.Reminder>().single()).single()
        assertEquals("Still booked: Ola", reminder.title)
        assertEquals(GuardianChannels.REMINDERS, reminder.channel)
        assertEquals(listOf("Open Ola", "I cancelled it", "Dismiss"), reminder.labels())
        assertTrue(reminder.lines.toString(), reminder.lines.first().endsWith("Reminder 2."))

        val lost = cards(keptLost).single()
        assertEquals("Your Rapido captain cancelled. Compare again?", lost.title)
        assertEquals(CardStyle.LOUD, lost.style)
        assertEquals(GuardianChannels.ALERTS, lost.channel)
        assertEquals(listOf("Compare again (same trip)", "Open Rapido"), lost.labels())
        assertEquals(GuardianTap(TapScope.SESSION, null, UiAction.COMPARE_AGAIN), lost.actions[0].target)

        val pay = cards(GuardianEffect.PayHandoffOffer("s:uber", Provider.UBER)).single()
        assertEquals("Ride ended. Open Uber to pay", pay.title)
        assertEquals(listOf("Open Uber to pay"), pay.labels())
    }

    @Test
    fun `background opening falls back to a heads-up whose tap opens the app`() {
        val open = en.openApp("s:ola", Provider.OLA, OpenPurpose.CANCEL, hasApp = true)
        assertEquals("Open Ola to cancel", open.title)
        assertEquals(CardOpen.RideApp(Provider.OLA, OpenPurpose.CANCEL), open.open)
        assertEquals("Open Ola on your phone to continue.", en.openApp("s:ola", Provider.OLA, OpenPurpose.PAY, hasApp = false).lines.single())
    }

    @Test
    fun `the ongoing notification mirrors the bubble for each phase`() {
        val booking = en.ongoing(base.copy(legs = base.legs.map { it.copy(booked = false, state = RideState.QUOTED) }))
        // "Booked here" names the app it records (nothing opened yet: the first unbooked app).
        assertEquals(listOf("Read fares", "Booked in Uber", "Stop"), booking.labels())
        assertEquals(SessionOp(SessionOpKind.BOOKED_HERE, base.legs[0].legId), booking.actions[1].target)
        val opened = en.ongoing(base.copy(legs = base.legs.map { it.copy(booked = false, state = RideState.QUOTED) }), openedLegId = base.legs[1].legId)
        assertEquals("Booked in Ola", opened.actions[1].label)
        assertEquals(SessionOp(SessionOpKind.BOOKED_HERE, base.legs[1].legId), opened.actions[1].target)
        assertEquals(SessionOp(SessionOpKind.READ_FARES), booking.actions[0].target)

        val chain = base.copy(
            legs = listOf(
                base.legs[0].copy(state = RideState.ASSIGNED, role = LegRole.KEPT),
                base.legs[1].copy(state = RideState.CANCEL_GUIDED, role = LegRole.EXTRA),
            ),
        )
        val guided = en.ongoing(chain)
        assertEquals(listOf("I cancelled it", "Show me", "Stop"), guided.labels())
        assertTrue(guided.lines.toString(), "Cancel Ola now" in guided.lines)
        assertTrue(guided.lines.toString(), "2 booked · 1 to cancel" in guided.lines)

        val riding = en.ongoing(chain.copy(legs = listOf(chain.legs[0])))
        assertEquals(listOf("Done", "Stop"), riding.labels())
        assertTrue(riding.lines.toString(), "Your ride: Uber" in riding.lines)

        // I10: once the kept driver cancels, the ongoing notification says what is happening, not "Your ride".
        val lost = en.ongoing(chain.copy(legs = listOf(chain.legs[0].copy(provider = Provider.RAPIDO, state = RideState.DRIVER_CANCELLED))))
        assertTrue(lost.lines.toString(), lost.lines.none { it.startsWith("Your ride") })
        assertEquals("Rapido: " + en.text.state(RideState.DRIVER_CANCELLED, Provider.RAPIDO), lost.lines.first())
        val onTrip = en.ongoing(chain.copy(legs = listOf(chain.legs[0].copy(state = RideState.ON_TRIP))))
        assertTrue(onTrip.lines.toString(), onTrip.lines.none { it.startsWith("Your ride") })
    }

    @Test
    fun `a capture card has the button whose tap Android's capture prompt must follow (I6)`() {
        val proof = cards(GuardianEffect.RequestCapture("s:ola", Provider.OLA, CaptureReason.CANCEL_PROOF)).single()
        assertEquals("Show Ola's cancelled screen", proof.title)
        assertEquals(listOf("Check screen"), proof.labels())
        assertEquals(GuardianTap(TapScope.LEG, "s:ola", UiAction.CHECK_SCREEN), proof.actions.single().target)
        assertEquals("Check screen, Ola", proof.actions.single().description)

        val check = cards(GuardianEffect.RequestCapture("s:rapido", Provider.RAPIDO, CaptureReason.CHECK_SCREEN)).single()
        assertEquals(GuardianTap(TapScope.LEG, "s:rapido", UiAction.CHECK_SCREEN), check.actions.single().target)

        val showMe = cards(GuardianEffect.RequestCapture("s:ola", Provider.OLA, CaptureReason.SHOW_ME)).single()
        assertEquals(listOf("Show me"), showMe.labels())
        assertEquals(GuardianTap(TapScope.LEG, "s:ola", UiAction.SHOW_ME), showMe.actions.single().target)
        assertEquals(listOf("स्क्रीन जाँचें"), cards(GuardianEffect.RequestCapture("s:ola", Provider.OLA, CaptureReason.CANCEL_PROOF), hi).single().labels())
    }

    @Test
    fun `Hindi strings for sheets, prompts, buttons and alerts`() {
        val sheet = cards(sheetCancelOthers, hi).single()
        assertEquals("बाकी बुकिंग रद्द करें?", sheet.title)
        assertEquals(listOf("हाँ, बाकी रद्द करें", "अभी नहीं"), sheet.labels())
        assertTrue(sheet.lines.toString(), sheet.lines.any { it.contains("4 मिनट दूर") })

        val prompt = cards(maybeChanged, hi).single()
        assertEquals("Rapido का अपडेट आया है। क्या आपका कैप्टन अभी भी आ रहा है?", prompt.title)
        assertEquals(listOf("हाँ", "ड्राइवर ने रद्द किया", "स्क्रीन जाँचें"), prompt.labels())

        val lost = cards(keptLost, hi).single()
        assertEquals("आपके Rapido कैप्टन ने रद्द किया। फिर से तुलना करें?", lost.title)

        assertEquals("दो ड्राइवर मिले। एक चुनें", cards(sheetTwoDrivers, hi).single().title)
        assertEquals("दोनों में एक ही ड्राइवर है। एक रखें।", cards(sheetSameDriver, hi).single().title)
    }

    @Test
    fun `every key and button has English and Hindi wording`() {
        val enText = GuardianText(context)
        val hiText = GuardianText(localized("hi"))
        val devanagari = Regex("[\\u0900-\\u097F]")
        for (key in UiKey.entries) {
            val e = enText.title(key, listOf(Provider.OLA))
            val h = hiText.title(key, listOf(Provider.OLA))
            assertTrue(key.name, e.isNotBlank())
            assertTrue("$key: $h", devanagari.containsMatchIn(h))
        }
        for (action in UiAction.entries) {
            val h = hiText.action(action, Provider.UBER)
            assertNotEquals(action.name, enText.action(action, Provider.UBER), h)
            assertTrue("$action: $h", devanagari.containsMatchIn(h))
        }
        for (state in RideState.entries) assertTrue(state.name, devanagari.containsMatchIn(hiText.state(state, Provider.OLA)))
        for (kind in FeeNoteKind.entries) assertTrue(kind.name, devanagari.containsMatchIn(hiText.fee(FeeNote(kind, upToInr = 50, freeForSeconds = 30))))
    }

    @Test
    fun `the notifier posts cards with their channel, at most three buttons, and loud alerts repeat`() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifier = GuardianNotifier(context, { en }) { _, _ -> null }
        val manager = context.getSystemService(NotificationManager::class.java)
        val lostTag = "alert:s:rapido:ALERT_KEPT_LEG_LOST"
        var lost: Notification? = null
        var state = base
        for (effect in everyEffect) {
            val next = GuardianUiReducer.apply(state, effect, NOW)
            notifier.sync(state, next)
            state = next
            if (effect === keptLost) lost = shadowOf(manager).getNotification(lostTag, GuardianNotifier.ITEM_NOTIFICATION_ID)
        }
        val posted = shadowOf(manager).allNotifications
        assertTrue(posted.isNotEmpty())
        assertTrue(posted.all { it.actions.orEmpty().size <= GuardianNotifier.MAX_NOTIFICATION_ACTIONS })
        GuardianChannels.ALL.forEach { assertNotNull(it, manager.getNotificationChannel(it)) }
        val alerts = manager.getNotificationChannel(GuardianChannels.ALERTS)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, alerts.importance)
        assertNotNull(alerts.sound)
        assertTrue(alerts.shouldVibrate())

        assertNotNull(lost)
        assertEquals(GuardianChannels.ALERTS, lost!!.channelId)
        assertTrue(lost.flags and Notification.FLAG_INSISTENT != 0)
        val compare = shadowOf(lost.actions[0].actionIntent).savedIntent
        assertEquals(GuardianTap(TapScope.SESSION, null, UiAction.COMPARE_AGAIN), GuardianIntents.target(compare))
        // "Compare again" closed the kept-leg-lost alert, so its notification left the shade.
        assertTrue(shadowOf(manager).getNotification(lostTag, GuardianNotifier.ITEM_NOTIFICATION_ID) == null)

        // Every remaining card goes away when its item does.
        val pay = shadowOf(manager).getNotification("pay:s:uber", GuardianNotifier.ITEM_NOTIFICATION_ID)
        assertEquals("Ride ended. Open Uber to pay", pay.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        notifier.sync(state, state.copy(items = emptyList()))
        assertTrue(shadowOf(manager).getNotification("pay:s:uber", GuardianNotifier.ITEM_NOTIFICATION_ID) == null)
    }

    @Test
    fun `a reminder for an app already said to be cancelled offers Check screen, not I cancelled it again`() {
        val guided = cards(GuardianEffect.Reminder(1, listOf(LegRef("s:ola", Provider.OLA, RideState.CANCEL_GUIDED)), true, true)).single()
        assertEquals(listOf("Open Ola", "I cancelled it", "Dismiss"), guided.labels())
        for (state in listOf(RideState.USER_SAYS_CANCELLED, RideState.CANCEL_UNCONFIRMED)) {
            val said = cards(GuardianEffect.Reminder(2, listOf(LegRef("s:ola", Provider.OLA, state)), true, true)).single()
            assertEquals(state.name, listOf("Open Ola", "Check screen", "Dismiss"), said.labels())
            assertEquals(GuardianTap(TapScope.LEG, "s:ola", UiAction.CHECK_SCREEN), said.actions[1].target)
        }
    }

    @Test
    fun `after a quiet +60 s check the leg waits for the app's proof instead of checking again in a minute`() {
        assertEquals("You said you cancelled it. Checking again in a minute", en.text.state(RideState.USER_SAYS_CANCELLED, Provider.OLA))
        assertEquals(
            "You said you cancelled it. Waiting for Ola to confirm",
            en.text.state(RideState.USER_SAYS_CANCELLED, Provider.OLA, followUpPending = false),
        )
    }

    @Test
    fun `the same driver on another booking while a ride is kept is a heads-up with Open and Dismiss`() {
        val kept = base.copy(
            legs = listOf(
                base.legs[0].copy(state = RideState.ASSIGNED, role = LegRole.KEPT),
                base.legs[2].copy(state = RideState.ASSIGNED, role = LegRole.EXTRA),
            ),
        )
        val card = cards(GuardianEffect.SameDriverNotice(listOf("s:uber", "s:rapido")), before = kept).single()
        assertEquals(GuardianChannels.PROMPTS, card.channel)
        assertEquals(CardStyle.HEADS_UP, card.style)
        assertEquals(listOf("Open Rapido", "Dismiss"), card.labels())
        assertEquals(GuardianTap(TapScope.LEG, "s:rapido", UiAction.OPEN_APP), card.actions[0].target)
        assertTrue(card.lines.single(), card.lines.single().contains("Rapido"))
    }

    @Test
    fun `the mock ONDC seller is named as such in the guardian's texts`() {
        val demo = GuardianPresenter(GuardianText(context) { true }, CancelStepsSource.NONE)
        assertEquals("Open network (ONDC demo seller)", demo.text.provider(Provider.ONDC))
        assertEquals("Open network (ONDC)", en.text.provider(Provider.ONDC))
        assertEquals("ओपन नेटवर्क (ONDC डेमो विक्रेता)", GuardianText(localized("hi")) { true }.provider(Provider.ONDC))
    }

    @Test
    fun `a booked here confirmation names the app that was recorded`() {
        val card = en.bookedRecorded("s:rapido", Provider.RAPIDO)
        assertEquals("Recorded: booked in Rapido", card.title)
        assertEquals(CardStyle.QUIET, card.style)
        assertEquals("booked:s:rapido", card.id)
    }

    private companion object {
        const val NOW = 1_793_163_600_000L
    }
}
