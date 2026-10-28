package app.ridemate.bubble

import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import app.ridemate.R
import java.util.UUID

/**
 * Everything the user can do from the GuardianBubble (§4.2). Every action is also offered on RideMate's ongoing
 * notification ([NotificationMirror]), because some apps hide overlays.
 */
enum class BubbleAction(
    @get:StringRes val labelRes: Int,
    @get:StringRes val descriptionRes: Int,
    @get:DrawableRes val iconRes: Int,
    /** Lower comes first when only a few actions fit (the notification shows at most three). */
    val mirrorPriority: Int,
) {
    READ_FARES(R.string.bubble_action_read_fares, R.string.bubble_action_read_fares_description, R.drawable.ic_bubble_read_fares, 0),
    SHOW_ME(R.string.bubble_action_show_me, R.string.bubble_action_show_me_description, R.drawable.ic_bubble_show_me, 1),
    BOOKED_HERE(R.string.bubble_action_booked_here, R.string.bubble_action_booked_here_description, R.drawable.ic_bubble_booked_here, 2),
    DONE(R.string.bubble_action_done, R.string.bubble_action_done_description, R.drawable.ic_bubble_done, 3),
    PASTE_DROP(R.string.bubble_action_paste_drop, R.string.bubble_action_paste_drop_description, R.drawable.ic_bubble_paste_drop, 4),
    STOP(R.string.bubble_action_stop, R.string.bubble_action_stop_description, R.drawable.ic_bubble_stop, 5),
}

/**
 * Receives bubble and notification taps. [tapId] is a fresh id per user tap; book, cancel and switch requests carry
 * it (I1). The session service (A3) implements this and binds the same instance to both surfaces.
 */
fun interface BubbleActions {
    fun onBubbleAction(action: BubbleAction, tapId: String)
}

/** One id per user tap (I1). */
object TapIds {
    fun newTapId(): String = UUID.randomUUID().toString()
}

/** One action as the ongoing notification should show it (A3 builds the `Notification.Action` from this). */
data class MirroredAction(
    val action: BubbleAction,
    @get:StringRes val labelRes: Int,
    @get:StringRes val descriptionRes: Int,
    @get:DrawableRes val iconRes: Int,
)

/**
 * The notification fallback for the bubble. Android shows at most [MAX_NOTIFICATION_ACTIONS] action buttons, so the
 * bubble's current actions are ranked by [BubbleAction.mirrorPriority]; Stop is always kept so the user can end the
 * session from the notification even when the overlay is hidden.
 */
object NotificationMirror {
    const val MAX_NOTIFICATION_ACTIONS = 3

    fun actionsFor(state: BubbleState, max: Int = MAX_NOTIFICATION_ACTIONS): List<MirroredAction> {
        if (max <= 0) return emptyList()
        val ranked = state.actions.distinct().sortedBy { it.mirrorPriority }
        val chosen = if (BubbleAction.STOP in ranked && ranked.size > max) {
            ranked.filter { it != BubbleAction.STOP }.take(max - 1) + BubbleAction.STOP
        } else {
            ranked.take(max)
        }
        return chosen.map { MirroredAction(it, it.labelRes, it.descriptionRes, it.iconRes) }
    }
}

/**
 * Extras for notification action intents. A3's receiver puts the action in with [put] and hands received intents to
 * [dispatch], which mints the tap id at the moment of the user's tap.
 */
object BubbleActionIntents {
    const val EXTRA_ACTION = "app.ridemate.bubble.extra.ACTION"

    fun put(intent: Intent, action: BubbleAction): Intent = intent.putExtra(EXTRA_ACTION, action.name)

    fun actionOf(intent: Intent?): BubbleAction? =
        intent?.getStringExtra(EXTRA_ACTION)?.let { name -> BubbleAction.entries.firstOrNull { it.name == name } }

    /** Sends the intent's action to [actions]; false when the intent carries none. */
    fun dispatch(intent: Intent?, actions: BubbleActions, newTapId: () -> String = TapIds::newTapId): Boolean {
        val action = actionOf(intent) ?: return false
        actions.onBubbleAction(action, newTapId())
        return true
    }
}
