package app.ridemate.integration

import android.content.Context
import app.ridemate.R
import app.ridemate.bubble.BubbleAction
import app.ridemate.bubble.BubbleActions
import app.ridemate.bubble.BubbleController
import app.ridemate.bubble.BubbleHost
import app.ridemate.bubble.BubbleState
import app.ridemate.bubble.BubbleStatus
import app.ridemate.bubble.BubbleSurface
import app.ridemate.bubble.InstructionCard
import app.ridemate.bubble.StatusKind
import app.ridemate.core.guardian.GuardianEffect
import app.ridemate.core.guardian.LegRole
import app.ridemate.core.guardian.UserAction
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import app.ridemate.core.state.TapId
import app.ridemate.launch.BubbleVisibility
import app.ridemate.notify.BookChoice
import app.ridemate.notify.BookTargets
import app.ridemate.notify.CancelCardItem
import app.ridemate.notify.GuardianPresenter
import app.ridemate.notify.GuardianUiState
import app.ridemate.notify.LegUi
import app.ridemate.notify.ReminderItem
import app.ridemate.notify.SessionOp
import app.ridemate.notify.SessionOpKind
import app.ridemate.session.GuardianBubble
import app.ridemate.session.GuardianStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** What the session bubble drives (the guardian runtime, fare reading, the launcher's paste chip). */
interface SessionBubbleDeps {
    val status: StateFlow<GuardianStatus>
    val uiState: StateFlow<GuardianUiState>
    /** The launcher's "Paste drop" chip (non-null while it is offered). */
    val pasteDrop: StateFlow<Any?>

    /** The app whose stale price the user asked to refresh from the list (the next Read fares reads it); null when none. */
    val refreshPending: StateFlow<Provider?> get() = NO_REFRESH

    fun presenter(): GuardianPresenter

    /** "Show me" is offered only while grounding is served (`point_supported`) and AI may run for [provider]. */
    fun showMeAvailable(provider: Provider): Boolean

    fun readFares(tapId: String)
    fun userAction(action: UserAction)
    fun sessionOp(op: SessionOp)
    fun endCapture()
    fun copyDropAgain()

    /**
     * "Next: <app>" (goal 3): opens [provider]'s app for leg [legId] with the trip's route through the launcher's
     * non-tap path (directly while the bubble is on screen, else its "open the next ride app" heads-up).
     */
    fun openNextApp(provider: Provider, legId: String) = Unit

    private companion object {
        val NO_REFRESH: StateFlow<Provider?> = MutableStateFlow(null)
    }
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

    /** The leg the cancel card (and any Show me arrow placed for it) was last shown for. */
    private var cardLegId: String? = null

    override val isVisible: Boolean get() = controller?.isShowing == true
    override val isShowing: Boolean get() = isVisible
    override val bubble: BubbleSurface? get() = controller

    override fun attach(context: Context) {
        if (controller != null) return
        controller = newController(context, this)
        val s = CoroutineScope(SupervisorJob() + main)
        scope = s
        s.launch {
            combine(deps.status, deps.uiState, deps.pasteDrop, deps.refreshPending) { status, ui, paste, refresh -> Rendered(status, ui, paste != null, refresh) }
                .collect { render(it) }
        }
    }

    override fun detach() {
        scope?.cancel()
        scope = null
        controller?.hide()
        controller = null
        effectCard = null
        cardLegId = null
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
        val status = deps.status.value
        val ui = deps.uiState.value
        when (action) {
            BubbleAction.READ_FARES -> deps.readFares(tapId)
            BubbleAction.SHOW_ME -> currentCard(ui)?.let { deps.userAction(UserAction.ShowMe(it.legId, TapId(tapId))) }
            // The same answer as the notification's "I cancelled it": recorded for that leg, checked +60 s later,
            // and the chain moves on to the next app. It never ends the session.
            BubbleAction.I_CANCELLED_IT -> currentCard(ui)?.let {
                controller?.hidePointer()
                deps.userAction(UserAction.ICancelledIt(it.legId, TapId(tapId)))
            }
            // The leg the button names: what the user saw is what gets recorded. Without one (nothing to book here
            // any more), the guardian decides, and asks which app when it can't tell.
            BubbleAction.BOOKED_HERE, BubbleAction.BOOKED_HERE_2, BubbleAction.BOOKED_HERE_3 -> {
                val slot = BubbleState.BOOKED_SLOTS.indexOf(action)
                val legId = bookChoice(status, ui).legIds.getOrNull(slot)
                if (legId != null || slot == 0) deps.sessionOp(SessionOp(SessionOpKind.BOOKED_HERE, legId))
            }
            BubbleAction.NEXT_APP -> nextApp(status, ui)?.let { deps.openNextApp(it.provider, it.legId) }
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
    fun stateFor(status: GuardianStatus, ui: GuardianUiState, pasteDrop: Boolean, refresh: Provider? = null): BubbleState? {
        // Stop with extra bookings still pending keeps the session (I8): the bubble keeps guiding those cancels.
        if (!status.sessionOpen || status.demo) return null
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
        if (kept || !booking) return BubbleState.watching()
        val text = deps.presenter().text
        val next = nextApp(status, ui)
        val choice = bookChoice(status, ui)
        val refreshing = refresh?.takeIf { p -> ui.legs.any { it.provider == p && !it.booked && !it.state.isTerminal } }
        val labels = buildMap {
            refreshing?.let { put(BubbleAction.READ_FARES, text.string(R.string.bubble_action_refresh_price, text.provider(it))) }
            next?.let { put(BubbleAction.NEXT_APP, text.string(R.string.bubble_action_next_app_named, text.provider(it.provider))) }
            choice.legIds.forEachIndexed { i, legId ->
                ui.leg(legId)?.let { put(BubbleState.BOOKED_SLOTS[i], text.string(R.string.bubble_action_booked_in, text.provider(it.provider))) }
            }
        }
        val line = when {
            refreshing != null -> BubbleStatus(text.string(R.string.bubble_status_refresh_pending, text.provider(refreshing)), StatusKind.INFO)
            // RideMate can't see which app is on screen: one "Booked in <app>" per app it could be.
            choice is BookChoice.Ask -> BubbleStatus(text.string(R.string.bubble_status_book_which_app), StatusKind.INFO)
            else -> null
        }
        return BubbleState.comparing(
            pasteDrop,
            nextApp = next != null,
            labels = labels,
            status = line,
            bookedChoices = choice.legIds.size.coerceAtLeast(1),
        )
    }

    /**
     * The next selected app to compare (goal 3): the first ride-app leg RideMate has not opened yet (still SELECTED),
     * other than the one on screen.
     */
    fun nextApp(status: GuardianStatus, ui: GuardianUiState): LegUi? = ui.legs.firstOrNull {
        it.state == RideState.SELECTED && !it.booked && !it.dismissed && !it.provider.isNetwork && it.legId != status.openedLegId
    }

    /**
     * The app(s) "Booked here" can record ([BookTargets]): the only one it can be, named on the button ("Booked in
     * Rapido"), or one button per app when RideMate opened several and can't see which one the user booked in.
     */
    fun bookChoice(status: GuardianStatus, ui: GuardianUiState): BookChoice = BookTargets.choose(ui.legs, status.openedLegId)

    private fun currentCard(ui: GuardianUiState): CancelCardItem? {
        val fromUi = ui.items.filterIsInstance<CancelCardItem>().lastOrNull { ui.leg(it.legId)?.state == RideState.CANCEL_GUIDED }
        if (fromUi != null) return fromUi
        val card = effectCard ?: return null
        val leg = ui.leg(card.legId)
        if (leg != null && leg.state != RideState.CANCEL_GUIDED) return null
        return CancelCardItem(card.legId, card.provider, card.providerState, card.fee, card.position, card.total, card.actions)
    }

    private data class Rendered(val status: GuardianStatus, val ui: GuardianUiState, val pasteDrop: Boolean, val refresh: Provider?)

    private fun rerender() = render(Rendered(deps.status.value, deps.uiState.value, deps.pasteDrop.value != null, deps.refreshPending.value))

    private fun render(r: Rendered) {
        val c = controller ?: return
        // The Show me arrow points at one app's Cancel button: once the chain moves to another app (or ends), it goes.
        val legId = currentCard(r.ui)?.legId
        if (legId != cardLegId) {
            c.hidePointer()
            cardLegId = legId
        }
        val state = stateFor(r.status, r.ui, r.pasteDrop, r.refresh)
        if (state == null) {
            if (c.isShowing) c.hide()
        } else {
            c.show(state)
        }
    }
}
