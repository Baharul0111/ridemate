package app.ridemate.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.ridemate.R
import app.ridemate.core.guardian.OpenPurpose
import app.ridemate.core.model.Provider
import app.ridemate.session.NotificationActionReceiver

/** Intents behind guardian notifications: action buttons (to [NotificationActionReceiver]) and content taps. */
object GuardianIntents {
    const val ACTION_TAP = "app.ridemate.action.GUARDIAN_TAP"
    const val EXTRA_TARGET = "app.ridemate.extra.GUARDIAN_TARGET"

    /** Extra on RideMate's launch intent: which screen to show. The UI shell routes [DESTINATION_GUARDIAN]. */
    const val EXTRA_DESTINATION = "app.ridemate.extra.DESTINATION"
    const val DESTINATION_GUARDIAN = "guardian"

    /** "Read fares" from the ongoing notification: the capture area starts a fare read (it needs an activity). */
    const val DESTINATION_READ_FARES = "read_fares"

    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    /** The broadcast a button sends. Each (card, button) gets a distinct data URI so extras never collide. */
    fun tapIntent(context: Context, cardId: String, index: Int, target: ActionTarget): Intent =
        Intent(context, NotificationActionReceiver::class.java)
            .setAction(ACTION_TAP)
            .setData(Uri.Builder().scheme("ridemate").authority("guardian").appendPath(cardId).appendPath(index.toString()).build())
            .putExtra(EXTRA_TARGET, target.encode())

    fun tapPendingIntent(context: Context, cardId: String, index: Int, target: ActionTarget): PendingIntent =
        PendingIntent.getBroadcast(context, requestCode(cardId, index), tapIntent(context, cardId, index, target), FLAGS)

    /** Opens RideMate on [destination] (the UI shell routes it). */
    fun rideMateIntent(context: Context, destination: String = DESTINATION_GUARDIAN): Intent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            putExtra(EXTRA_DESTINATION, destination)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

    fun guardianScreen(context: Context, cardId: String): PendingIntent? =
        rideMateIntent(context)?.let { PendingIntent.getActivity(context, requestCode(cardId, -1), it, FLAGS) }

    /**
     * A button's PendingIntent. Buttons that must show an activity (Read fares, Open RideMate) open it directly: Android
     * blocks activity starts from a receiver behind a notification tap.
     */
    fun buttonPendingIntent(context: Context, cardId: String, index: Int, target: ActionTarget): PendingIntent {
        val destination = when ((target as? SessionOp)?.op) {
            SessionOpKind.READ_FARES -> DESTINATION_READ_FARES
            SessionOpKind.OPEN_GUARDIAN -> DESTINATION_GUARDIAN
            else -> null
        }
        val activity = destination?.let { rideMateIntent(context, it) }
        return if (activity != null) {
            PendingIntent.getActivity(context, requestCode(cardId, index), activity, FLAGS)
        } else {
            tapPendingIntent(context, cardId, index, target)
        }
    }

    /** A user tap on the notification opens the ride app directly (allowed from the background). */
    fun rideApp(context: Context, cardId: String, intent: Intent): PendingIntent =
        PendingIntent.getActivity(context, requestCode(cardId, -2), Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), FLAGS)

    fun target(intent: Intent?): ActionTarget? =
        intent?.takeIf { it.action == ACTION_TAP }?.getStringExtra(EXTRA_TARGET)?.let(ActionTarget::decode)

    private fun requestCode(cardId: String, index: Int): Int = 31 * cardId.hashCode() + index
}

/**
 * Posts guardian cards as notifications: heads-up sheets and prompts with their exact buttons, loud alerts that
 * repeat their sound until seen, reminders, quiet updates, and the ongoing foreground-service notification. Works on
 * any thread. Nothing is posted when the user has turned notifications off (the in-app screen still shows everything).
 */
class GuardianNotifier(
    private val context: Context,
    private val presenter: () -> GuardianPresenter,
    private val rideAppIntent: (Provider, OpenPurpose) -> Intent?,
) {
    private val manager = NotificationManagerCompat.from(context)

    /** Posts what is new or changed between two UI states and removes what went away. */
    fun sync(previous: GuardianUiState, next: GuardianUiState) {
        val p = presenter()
        val before = previous.items.associateBy { it.id }
        val after = next.items.associateBy { it.id }
        before.keys.filter { it !in after }.forEach(::cancel)
        after.forEach { (id, item) -> if (before[id] != item) post(p.present(item)) }
        val oldNotices = previous.notices.map { it.id }.toSet()
        val newNotices = next.notices.map { it.id }.toSet()
        previous.notices.filter { it.id !in newNotices }.forEach { cancel(it.id) }
        next.notices.filter { it.id !in oldNotices }.reversed().forEach { post(p.notice(it)) }
    }

    @SuppressLint("MissingPermission") // canPost() checks POST_NOTIFICATIONS; a revoked permission is caught below.
    fun post(card: GuardianCard) {
        if (!canPost()) return
        GuardianChannels.ensure(context)
        try {
            manager.notify(card.id, ITEM_NOTIFICATION_ID, build(card))
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post: the in-app screen still shows the card.
        }
    }

    fun cancel(cardId: String) = manager.cancel(cardId, ITEM_NOTIFICATION_ID)

    fun cancelAll(state: GuardianUiState) {
        state.items.forEach { cancel(it.id) }
        state.notices.forEach { cancel(it.id) }
    }

    /** Erase: removes every notification RideMate posted. */
    fun cancelEverything() = manager.cancelAll()

    /** A guardian card as a notification. */
    fun build(card: GuardianCard): Notification {
        val body = body(card)
        val builder = NotificationCompat.Builder(context, card.channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(card.title)
            .setContentText(card.lines.firstOrNull())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (card.style == CardStyle.QUIET) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setCategory(category(card))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(!card.alertAgain)
            .setAutoCancel(card.actions.isEmpty())
            .setShowWhen(true)
        contentIntent(card)?.let(builder::setContentIntent)
        card.timeoutMs?.let(builder::setTimeoutAfter)
        if (card.style == CardStyle.LOUD) builder.setVibrate(GuardianChannels.LOUD_VIBRATION)
        card.actions.take(MAX_NOTIFICATION_ACTIONS).forEachIndexed { index, action ->
            builder.addAction(
                NotificationCompat.Action.Builder(0, action.label, GuardianIntents.buttonPendingIntent(context, card.id, index, action.target))
                    .setShowsUserInterface(false)
                    .build(),
            )
        }
        return builder.build().also { if (card.style == CardStyle.LOUD) it.flags = it.flags or Notification.FLAG_INSISTENT }
    }

    /** The foreground-service notification (ongoing, silent), mirroring the bubble's actions. */
    fun ongoing(card: GuardianCard): Notification {
        GuardianChannels.ensure(context)
        val builder = NotificationCompat.Builder(context, GuardianChannels.GUARDIAN)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(card.title)
            .setContentText(card.lines.firstOrNull())
            .setStyle(NotificationCompat.BigTextStyle().bigText(card.lines.joinToString("\n")))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        GuardianIntents.guardianScreen(context, card.id)?.let(builder::setContentIntent)
        card.actions.take(MAX_NOTIFICATION_ACTIONS).forEachIndexed { index, action ->
            builder.addAction(
                NotificationCompat.Action.Builder(0, action.label, GuardianIntents.buttonPendingIntent(context, card.id, index, action.target)).build(),
            )
        }
        return builder.build()
    }

    @SuppressLint("MissingPermission") // See post().
    fun updateOngoing(notificationId: Int, card: GuardianCard) {
        if (!canPost()) return
        try {
            manager.notify(notificationId, ongoing(card))
        } catch (_: SecurityException) {
            // See post().
        }
    }

    /** Brings RideMate to the front on [destination] (used for in-app taps, where RideMate is already in front). */
    fun openRideMate(destination: String): Boolean {
        val intent = GuardianIntents.rideMateIntent(context, destination) ?: return false
        return try {
            context.startActivity(intent)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    fun canPost(): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun contentIntent(card: GuardianCard): PendingIntent? = when (val open = card.open) {
        CardOpen.Guardian -> GuardianIntents.guardianScreen(context, card.id)
        is CardOpen.RideApp -> rideAppIntent(open.provider, open.purpose)?.let { GuardianIntents.rideApp(context, card.id, it) }
            ?: GuardianIntents.guardianScreen(context, card.id)
    }

    /** Lines with cancel steps rendered with bold control names ("Tap **Cancel ride**"). */
    private fun body(card: GuardianCard): CharSequence {
        if (card.steps.isEmpty()) return card.lines.joinToString("\n")
        val out = SpannableStringBuilder()
        card.steps.forEachIndexed { i, step ->
            if (i > 0) out.append('\n')
            out.append("${i + 1}. ")
            step.segments.forEach { segment ->
                val start = out.length
                out.append(segment.text)
                if (segment.bold) out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        card.lines.drop(card.steps.size).forEach { out.append('\n').append(it) }
        return out
    }

    private fun category(card: GuardianCard): String = when (card.channel) {
        GuardianChannels.REMINDERS -> NotificationCompat.CATEGORY_REMINDER
        GuardianChannels.ALERTS -> NotificationCompat.CATEGORY_ALARM
        GuardianChannels.PROMPTS -> NotificationCompat.CATEGORY_STATUS
        else -> NotificationCompat.CATEGORY_STATUS
    }

    companion object {
        /** Item notifications share one id and are told apart by tag (the card id). */
        const val ITEM_NOTIFICATION_ID = 4_100

        /** Android shows at most three action buttons; the in-app sheet always has them all. */
        const val MAX_NOTIFICATION_ACTIONS = 3
    }
}
