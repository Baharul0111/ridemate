package app.ridemate.integration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.bubble.BubbleAction
import app.ridemate.core.guardian.FeeNote
import app.ridemate.core.guardian.FeeNoteKind
import app.ridemate.core.guardian.LegRole
import app.ridemate.core.guardian.UiAction
import app.ridemate.core.guardian.UserAction
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import app.ridemate.core.state.TapId
import app.ridemate.notify.CancelCardItem
import app.ridemate.notify.CancelStepsSource
import app.ridemate.notify.GuardianPresenter
import app.ridemate.notify.GuardianText
import app.ridemate.notify.GuardianUiState
import app.ridemate.notify.LegUi
import app.ridemate.notify.SessionOp
import app.ridemate.notify.SessionOpKind
import app.ridemate.session.GuardianStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** What the session service's bubble shows for the guardian's state, and where each of its buttons goes. */
@RunWith(AndroidJUnit4::class)
class SessionBubbleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private class Deps(private val context: Context) : SessionBubbleDeps {
        override val status = MutableStateFlow(GuardianStatus())
        override val uiState = MutableStateFlow(GuardianUiState.EMPTY)
        override val pasteDrop = MutableStateFlow<Any?>(null)
        var showMe = true
        val calls = mutableListOf<Any>()
        override fun presenter() = GuardianPresenter(GuardianText(context), CancelStepsSource.NONE)
        override fun showMeAvailable(provider: Provider) = showMe
        override fun readFares(tapId: String) {
            calls += "read:$tapId"
        }
        override fun userAction(action: UserAction) {
            calls += action
        }
        override fun sessionOp(op: SessionOp) {
            calls += op
        }
        override fun endCapture() {
            calls += "endCapture"
        }
        override fun copyDropAgain() {
            calls += "paste"
        }
        override fun openNextApp(provider: Provider, legId: String) {
            calls += "open:${provider.name}:$legId"
        }
    }

    private val deps = Deps(context)
    private val bubble = SessionBubble(deps, Dispatchers.Unconfined)

    private fun leg(provider: Provider, state: RideState, role: LegRole = LegRole.NONE, booked: Boolean = false) =
        LegUi("s1:${provider.wireId}", provider, state, 1, role, dismissed = false, booked = booked, endedByApp = false)

    private val open = GuardianStatus(restored = true, sessionId = "s1", sessionOpen = true)

    @Test
    fun `hidden without a live session, comparing while booking, the cancel guide during the chain, watching after`() {
        val booking = GuardianUiState(sessionId = "s1", legs = listOf(leg(Provider.UBER, RideState.OPENED), leg(Provider.OLA, RideState.SELECTED)))
        assertNull(bubble.stateFor(GuardianStatus(restored = true), booking, pasteDrop = false))
        assertNull("Try the demo draws its own screens", bubble.stateFor(open.copy(demo = true), booking, pasteDrop = false))

        val comparing = bubble.stateFor(open, booking, pasteDrop = true)!!
        // Ola is still SELECTED: "Next: Ola" opens it with the route (goal 3).
        assertEquals(
            listOf(BubbleAction.READ_FARES, BubbleAction.PASTE_DROP, BubbleAction.NEXT_APP, BubbleAction.BOOKED_HERE, BubbleAction.STOP),
            comparing.actions,
        )
        assertEquals("Next: Ola", comparing.labelFor(BubbleAction.NEXT_APP))
        // Nothing opened yet: "Booked here" names the first unbooked app it would record.
        assertEquals("Booked in Uber", comparing.labelFor(BubbleAction.BOOKED_HERE))

        val card = CancelCardItem(
            "s1:uber", Provider.UBER, RideState.SEARCHING, FeeNote(FeeNoteKind.FREE_WHILE_SEARCHING), 1, 1,
            listOf(UiAction.SHOW_ME, UiAction.I_CANCELLED_IT),
        )
        val chain = GuardianUiState(
            sessionId = "s1",
            legs = listOf(leg(Provider.UBER, RideState.CANCEL_GUIDED, LegRole.EXTRA, booked = true), leg(Provider.OLA, RideState.ASSIGNED, LegRole.KEPT, booked = true)),
            items = listOf(card),
        )
        val guide = bubble.stateFor(open.copy(stopRequested = true, needsForegroundService = true), chain, pasteDrop = false)!!
        assertTrue("Stop with a pending cancel keeps guiding it (I8)", guide.card != null)
        // "I cancelled it", never a "Done" that would end the session while the kept ride is still coming.
        assertEquals(listOf(BubbleAction.SHOW_ME, BubbleAction.I_CANCELLED_IT, BubbleAction.STOP), guide.actions)
        deps.showMe = false
        assertEquals(listOf(BubbleAction.I_CANCELLED_IT, BubbleAction.STOP), bubble.stateFor(open, chain, pasteDrop = false)!!.actions)

        val kept = GuardianUiState(sessionId = "s1", legs = listOf(leg(Provider.OLA, RideState.ASSIGNED, LegRole.KEPT, booked = true)))
        assertEquals(listOf(BubbleAction.STOP), bubble.stateFor(open, kept, pasteDrop = false)!!.actions)
    }

    @Test
    fun `each button reaches its owner with the tap's own id`() {
        deps.uiState.value = GuardianUiState(
            sessionId = "s1",
            legs = listOf(leg(Provider.UBER, RideState.CANCEL_GUIDED, LegRole.EXTRA, booked = true)),
            items = listOf(CancelCardItem("s1:uber", Provider.UBER, RideState.SEARCHING, FeeNote(FeeNoteKind.FREE_WHILE_SEARCHING), 1, 1, emptyList())),
        )
        bubble.onBubbleAction(BubbleAction.READ_FARES, "t1")
        bubble.onBubbleAction(BubbleAction.SHOW_ME, "t2")
        bubble.onBubbleAction(BubbleAction.BOOKED_HERE, "t3")
        bubble.onBubbleAction(BubbleAction.PASTE_DROP, "t4")
        bubble.onBubbleAction(BubbleAction.I_CANCELLED_IT, "t5")
        bubble.onBubbleAction(BubbleAction.DONE, "t6")
        bubble.onBubbleAction(BubbleAction.STOP, "t7")
        assertEquals(
            listOf(
                "read:t1",
                UserAction.ShowMe("s1:uber", TapId("t2")),
                SessionOp(SessionOpKind.BOOKED_HERE),
                "paste",
                UserAction.ICancelledIt("s1:uber", TapId("t5")),
                "endCapture", SessionOp(SessionOpKind.DONE),
                "endCapture", SessionOp(SessionOpKind.STOP),
            ),
            deps.calls,
        )
        assertTrue("no bubble window without the service", !bubble.isVisible)
    }

    @Test
    fun `Next app opens the next selected app with its leg, and Booked here records the app it names`() {
        deps.status.value = open.copy(openedLegId = "s1:uber")
        deps.uiState.value = GuardianUiState(
            sessionId = "s1",
            legs = listOf(leg(Provider.UBER, RideState.QUOTED), leg(Provider.OLA, RideState.SELECTED), leg(Provider.RAPIDO, RideState.SELECTED)),
        )
        val state = bubble.stateFor(deps.status.value, deps.uiState.value, pasteDrop = false)!!
        assertEquals("Next: Ola", state.labelFor(BubbleAction.NEXT_APP))
        assertEquals("Booked in Uber", state.labelFor(BubbleAction.BOOKED_HERE))

        bubble.onBubbleAction(BubbleAction.NEXT_APP, "t1")
        bubble.onBubbleAction(BubbleAction.BOOKED_HERE, "t2")
        assertEquals(listOf("open:OLA:s1:ola", SessionOp(SessionOpKind.BOOKED_HERE, "s1:uber")), deps.calls)

        // Once Ola is opened too, the next one is Rapido. RideMate can't see whether the user is on Ola or went back to
        // Uber with Recents, so "Booked here" asks: one button per opened app, the app opened last first.
        val afterOla = GuardianUiState(
            sessionId = "s1",
            legs = listOf(leg(Provider.UBER, RideState.QUOTED), leg(Provider.OLA, RideState.OPENED), leg(Provider.RAPIDO, RideState.SELECTED)),
        )
        val next = bubble.stateFor(open.copy(openedLegId = "s1:ola"), afterOla, pasteDrop = false)!!
        assertEquals("Next: Rapido", next.labelFor(BubbleAction.NEXT_APP))
        assertEquals(
            listOf(BubbleAction.READ_FARES, BubbleAction.NEXT_APP, BubbleAction.BOOKED_HERE, BubbleAction.BOOKED_HERE_2, BubbleAction.STOP),
            next.actions,
        )
        assertEquals("Booked in Ola", next.labelFor(BubbleAction.BOOKED_HERE))
        assertEquals("Booked in Uber", next.labelFor(BubbleAction.BOOKED_HERE_2))
        assertEquals("Booked a ride? Tap the app you booked it in.", next.status!!.text)

        // Every app opened: no Next button.
        val allOpened = afterOla.copy(legs = afterOla.legs.map { it.copy(state = RideState.OPENED) })
        assertTrue(BubbleAction.NEXT_APP !in bubble.stateFor(open, allOpened, pasteDrop = false)!!.actions)
    }

    @Test
    fun `back on an earlier app with Recents, the user picks the app they booked in and that app is recorded`() {
        // Uber, Ola and Rapido were all opened through RideMate; the user went back to Uber and booked there.
        deps.status.value = open.copy(openedLegId = "s1:rapido")
        deps.uiState.value = GuardianUiState(
            sessionId = "s1",
            legs = listOf(leg(Provider.UBER, RideState.QUOTED), leg(Provider.OLA, RideState.QUOTED), leg(Provider.RAPIDO, RideState.OPENED)),
        )
        val state = bubble.stateFor(deps.status.value, deps.uiState.value, pasteDrop = false)!!
        assertEquals("Booked in Rapido", state.labelFor(BubbleAction.BOOKED_HERE))
        assertEquals("Booked in Uber", state.labelFor(BubbleAction.BOOKED_HERE_2))
        assertEquals("Booked in Ola", state.labelFor(BubbleAction.BOOKED_HERE_3))
        assertTrue(BubbleAction.NEXT_APP !in state.actions)

        bubble.onBubbleAction(BubbleAction.BOOKED_HERE_2, "t1")
        assertEquals(listOf(SessionOp(SessionOpKind.BOOKED_HERE, "s1:uber")), deps.calls)

        // Only one app was ever opened through RideMate: that's the one, named on a single button.
        val one = GuardianUiState(
            sessionId = "s1",
            legs = listOf(leg(Provider.UBER, RideState.SELECTED), leg(Provider.OLA, RideState.QUOTED), leg(Provider.RAPIDO, RideState.SELECTED)),
        )
        val single = bubble.stateFor(open.copy(openedLegId = "s1:ola"), one, pasteDrop = false)!!
        assertEquals("Booked in Ola", single.labelFor(BubbleAction.BOOKED_HERE))
        assertTrue(BubbleAction.BOOKED_HERE_2 !in single.actions)
        assertNull(single.status)
    }

    @Test
    fun `a stale-row refresh arms the bubble's Read fares for that app`() {
        val booking = GuardianUiState(sessionId = "s1", legs = listOf(leg(Provider.UBER, RideState.QUOTED), leg(Provider.OLA, RideState.QUOTED)))
        val state = bubble.stateFor(open, booking, pasteDrop = false, refresh = Provider.OLA)!!
        assertEquals("Refresh Ola price", state.labelFor(BubbleAction.READ_FARES))
        assertTrue(state.status!!.text.contains("Refresh Ola price"))
        assertNull(bubble.stateFor(open, booking, pasteDrop = false)!!.labelFor(BubbleAction.READ_FARES))
    }

    @Test
    fun `the Show me arrow goes when the chain moves on to the next app`() {
        val shown = BubbleControllerHarness(context)
        val hosted = SessionBubble(deps, Dispatchers.Unconfined) { _, actions -> shown.controller(actions) }
        deps.status.value = open
        fun card(legId: String, provider: Provider) =
            CancelCardItem(legId, provider, RideState.SEARCHING, FeeNote(FeeNoteKind.FREE_WHILE_SEARCHING), 1, 2, listOf(UiAction.SHOW_ME))
        deps.uiState.value = GuardianUiState(
            sessionId = "s1",
            legs = listOf(
                leg(Provider.UBER, RideState.CANCEL_GUIDED, LegRole.EXTRA, booked = true),
                leg(Provider.OLA, RideState.SEARCHING, LegRole.EXTRA, booked = true),
                leg(Provider.RAPIDO, RideState.ASSIGNED, LegRole.KEPT, booked = true),
            ),
            items = listOf(card("s1:uber", Provider.UBER)),
        )
        hosted.attach(context)
        val controller = shown.last!!
        val metrics = context.resources.displayMetrics
        val target = app.ridemate.core.ocr.Box(metrics.widthPixels / 2, metrics.heightPixels / 2, metrics.widthPixels / 2 + 80, metrics.heightPixels / 2 + 40)
        assertTrue(controller.showPointer(target, "Cancel is here"))
        assertTrue(controller.pointerParams != null)

        // "I cancelled it" on Uber: the chain guides Ola now, and the arrow placed for Uber's Cancel goes.
        deps.uiState.value = deps.uiState.value.copy(
            legs = listOf(
                leg(Provider.UBER, RideState.USER_SAYS_CANCELLED, LegRole.EXTRA, booked = true),
                leg(Provider.OLA, RideState.CANCEL_GUIDED, LegRole.EXTRA, booked = true),
                leg(Provider.RAPIDO, RideState.ASSIGNED, LegRole.KEPT, booked = true),
            ),
            items = listOf(card("s1:ola", Provider.OLA)),
        )
        assertNull(controller.pointerParams)
        hosted.detach()
    }
}

/** Builds real [app.ridemate.bubble.BubbleController]s over fake overlay windows (the overlay is allowed). */
private class BubbleControllerHarness(private val context: Context) {
    var last: app.ridemate.bubble.BubbleController? = null

    fun controller(actions: app.ridemate.bubble.BubbleActions): app.ridemate.bubble.BubbleController =
        app.ridemate.bubble.BubbleController(
            context = context,
            actions = actions,
            windows = app.ridemate.bubble.FakeOverlayWindows(context),
            canDrawOverlays = { true },
            animationsEnabled = { false },
        ).also { last = it }
}
