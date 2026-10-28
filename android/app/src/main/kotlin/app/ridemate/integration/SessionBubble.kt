package app.ridemate.integration

import android.content.Context
import app.ridemate.bubble.BubbleAction
import app.ridemate.bubble.BubbleActions
import app.ridemate.bubble.BubbleController
import app.ridemate.bubble.BubbleHost
import app.ridemate.bubble.BubbleState
import app.ridemate.bubble.BubbleSurface
import app.ridemate.bubble.InstructionCard
import app.ridemate.core.guardian.GuardianEffect
import app.ridemate.core.guardian.LegRole
import app.ridemate.core.guardian.UserAction
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import app.ridemate.core.state.TapId
import app.ridemate.launch.BubbleVisibility
import app.ridemate.notify.CancelCardItem
import app.ridemate.notify.GuardianPresenter
import app.ridemate.notify.GuardianUiState
import app.ridemate.notify.ReminderItem
import app.ridemate.notify.SessionOp
import app.ridemate.notify.SessionOpKind
import app.ridemate.session.GuardianBubble
import app.ridemate.session.GuardianStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** What the session bubble drives (the guardian runtime, fare reading, the launcher's paste chip). */
interface SessionBubbleDeps {
    val status: StateFlow<GuardianStatus>
    val uiState: StateFlow<GuardianUiState>
    /** The launcher's "Paste drop" chip (non-null while it is offered). */
    val pasteDrop: StateFlow<Any?>

    fun presenter(): GuardianPresenter

    /** "Show me" is offered only while grounding is served (`point_supported`) and AI may run for [provider]. */
    fun showMeAvailable(provider: Provider): Boolean

    fun readFares(tapId: String)
    fun userAction(action: UserAction)
    fun sessionOp(op: SessionOp)
    fun endCapture()
    fun copyDropAgain()
}

/**
 * The one floating bubble of the process (GuardianBubble + PointerOverlay, §4.2), hosted by the session service:
 * [attach] runs in `RideSessionService.onCreate`, [detach] in `onDestroy`. It is at the same time
 * - the guardian's [GuardianBubble] (cancel card, reminder pulse),
 * - fare reading's [BubbleHost] (status lines, hide-for-capture, the Show me arrow),
 * - the launcher's [BubbleVisibility] (Android 15 background-start rule), and
 * - the [BubbleActions] receiver of its own buttons.
 *
 * The bubble shows only during a live session (Try the demo draws its own screens) and only with the overlay
 * permission; every action is mirrored on the ongoing notification either way. Call on the main thread.
 */
class SessionBubble(
    private val deps: SessionBubbleDeps,
    private val main: CoroutineDispatcher,
    private val newController: (Context, BubbleActions) -> BubbleController = { context, actions -> BubbleController(context, actions) },
) : GuardianBubble, BubbleHost, BubbleActions, BubbleVisibility {

    private var controller: BubbleController? = null
    private var scope: CoroutineScope? = null
    private var effectCard: GuardianEffect.CancelCard? = null

    override val isVisible: Boolean get() = controller?.isShowing == true
    override val isShowing: Boolean get() = isVisible
    override val bubble: BubbleSurface? get() = controller

    override fun attach(context: Context) {
        if (controller != null) return
        controller = newController(context, this)
        val s = CoroutineScope(SupervisorJob() + main)
        scope = s
        s.launch {
            combine(deps.status, deps.uiState, deps.pasteDrop) { status, ui, paste -> Triple(status, ui, paste != null) }
                .collect { (status, ui, paste) -> render(status, ui, paste) }
        }
    }

    override fun detach() {
        scope?.cancel()
        scope = null
        controller?.hide()
        controller = null
        effectCard = null
    }

    override fun pulse() {
        val c = controller ?: return
        val reminder = deps.uiState.value.items.filterIsInstance<ReminderItem>().lastOrNull()
        c.pulse(reminder?.let { deps.presenter().present(it).title })
    }

    override fun showCancelCard(card: GuardianEffect.CancelCard) {
        effectCard = card
        rerender()
    }

    override fun clearCancelCard() {
        effectCard = null
        controller?.hidePointer()
        rerender()
    }

    override fun onBubbleAction(action: BubbleAction, tapId: String) {
        when (action) {
            BubbleAction.READ_FARES -> deps.readFares(tapId)
            BubbleAction.SHOW_ME -> currentCard(deps.uiState.value)?.let { deps.userAction(UserAction.ShowMe(it.legId, TapId(tapId))) }
            BubbleAction.BOOKED_HERE -> deps.sessionOp(SessionOp(SessionOpKind.BOOKED_HERE))
            BubbleAction.DONE -> {
                controller?.hidePointer()
                deps.endCapture()
                deps.sessionOp(SessionOp(SessionOpKind.DONE))
            }
            BubbleAction.STOP -> {
                controller?.hidePointer()
                deps.endCapture()
                deps.sessionOp(SessionOp(SessionOpKind.STOP))
            }
            BubbleAction.PASTE_DROP -> deps.copyDropAgain()
        }
    }

    /** What the bubble should show for [ui]; null hides it. Public for tests. */
    fun stateFor(status: GuardianStatus, ui: GuardianUiState, pasteDrop: Boolean): BubbleState? {
        if (!status.sessionOpen || status.demo || status.stopRequested) return null
        currentCard(ui)?.let { card ->
            val presented = deps.presenter().present(card)
            val instruction = InstructionCard(
                title = presented.title,
                steps = presented.steps,
                note = presented.lines.lastOrNull()?.takeIf { presented.lines.size > presented.steps.size },
            )
            return BubbleState.cancelGuide(instruction, showMe = deps.showMeAvailable(card.provider))
        }
        val kept = ui.legs.any { it.role == LegRole.KEPT }
        val booking = ui.legs.any { !it.booked && !it.state.isTerminal && !it.provider.isNetwork }
        return if (kept || !booking) BubbleState.watching() else BubbleState.comparing(pasteDrop)
    }

    private fun currentCard(ui: GuardianUiState): CancelCardItem? {
        val fromUi = ui.items.filterIsInstance<CancelCardItem>().lastOrNull { ui.leg(it.legId)?.state == RideState.CANCEL_GUIDED }
        if (fromUi != null) return fromUi
        val card = effectCard ?: return null
        val leg = ui.leg(card.legId)
        if (leg != null && leg.state != RideState.CANCEL_GUIDED) return null
        return CancelCardItem(card.legId, card.provider, card.providerState, card.fee, card.position, card.total, card.actions)
    }

    private fun rerender() = render(deps.status.value, deps.uiState.value, deps.pasteDrop.value != null)

    private fun render(status: GuardianStatus, ui: GuardianUiState, pasteDrop: Boolean) {
        val c = controller ?: return
        val state = stateFor(status, ui, pasteDrop)
        if (state == null) {
            if (c.isShowing) c.hide()
        } else {
            c.show(state)
        }
    }
}
