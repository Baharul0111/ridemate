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
        assertEquals(listOf(BubbleAction.READ_FARES, BubbleAction.PASTE_DROP, BubbleAction.BOOKED_HERE, BubbleAction.STOP), comparing.actions)

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
        assertEquals(listOf(BubbleAction.SHOW_ME, BubbleAction.DONE, BubbleAction.STOP), guide.actions)
        deps.showMe = false
        assertEquals(listOf(BubbleAction.DONE, BubbleAction.STOP), bubble.stateFor(open, chain, pasteDrop = false)!!.actions)

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
        bubble.onBubbleAction(BubbleAction.DONE, "t5")
        bubble.onBubbleAction(BubbleAction.STOP, "t6")
        assertEquals(
            listOf(
                "read:t1",
                UserAction.ShowMe("s1:uber", TapId("t2")),
                SessionOp(SessionOpKind.BOOKED_HERE),
                "paste",
                "endCapture", SessionOp(SessionOpKind.DONE),
                "endCapture", SessionOp(SessionOpKind.STOP),
            ),
            deps.calls,
        )
        assertTrue("no bubble window without the service", !bubble.isVisible)
    }
}
