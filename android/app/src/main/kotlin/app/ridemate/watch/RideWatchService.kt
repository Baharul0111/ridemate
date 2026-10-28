package app.ridemate.watch

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.ridemate.RideMateApp
import app.ridemate.core.model.Provider
import app.ridemate.crash.CrashSite
import app.ridemate.dev.RecordedPost

/**
 * RideWatch's NotificationListenerService (§4.5). It only reads notifications; it never acts on them or on the apps
 * that posted them (no replies, no actions, no cancelling another app's posts).
 *
 * - Posts and removals go through [RideWatchAdapter], which drops every package but the three ride apps unread (I7).
 * - Allowlisted posts reach core's RideWatchPipeline immediately (no debounce) via the guardian runtime.
 * - On disconnect the service asks the system to rebind; on (re)connect the whole shade is re-read and every
 *   allowlisted post is processed as new (I9).
 * - In developer mode with the Recorder on, every allowlisted post and removal is also handed to the owner Recorder
 *   (§4.19 a), which masks it before storing it on the phone.
 */
class RideWatchService : NotificationListenerService() {

    private val graph by lazy { RideMateApp.graph(this) }
    private val reader by lazy { SbnPostReader(PackageVersionCodes(packageManager, graph.clock)) }
    private val adapter by lazy { RideWatchAdapter(packageName, graph.guardianRuntime, graph.rideWatchStatus) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        graph.guardianRuntime.start()
        adapter.onConnected(activePosts()) { adapter.onReread(activePosts()) }
    }

    override fun onListenerDisconnected() {
        adapter.onDisconnected()
        graph.rideWatchStatus.requestRebind()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        adapter.onPosted(SbnListenerPost(sbn, reader))
        record(sbn, removalReason = null)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) = onNotificationPosted(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        adapter.onRemoved(SbnRemoval(sbn, 0))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?) = onNotificationRemoved(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        sbn ?: return
        adapter.onRemoved(SbnRemoval(sbn, reason))
        record(sbn, removalReason = reason)
    }

    /** The owner Recorder: allowlisted packages only (I7), and only while developer mode records. */
    private fun record(sbn: StatusBarNotification, removalReason: Int?) {
        if (sbn.packageName !in Provider.RIDE_APP_PACKAGES) return
        val recorder = graph.appShell.recorderSink
        if (!recorder.isRecording) return
        try {
            recorder.record(recordedPost(reader, sbn, removalReason, graph.clock.wallMs()))
        } catch (e: RuntimeException) {
            graph.crashReporter.recordNonFatal(e, CrashSite.WATCH)
        }
    }

    /** The current shade. Only the package name of each entry is read before the allowlist check. */
    private fun activePosts(): List<ListenerPost> = try {
        activeNotifications.orEmpty().filterNotNull().map { SbnListenerPost(it, reader) }
    } catch (e: SecurityException) {
        graph.crashReporter.recordNonFatal(e, CrashSite.WATCH)
        emptyList()
    } catch (e: RuntimeException) {
        graph.crashReporter.recordNonFatal(e, CrashSite.WATCH)
        emptyList()
    }
}

/** One allowlisted post (or removal) as the Recorder stores it; the Recorder masks title, text and tag. */
internal fun recordedPost(reader: SbnPostReader, sbn: StatusBarNotification, removalReason: Int?, atUtcMs: Long): RecordedPost {
    val snapshot = reader.read(sbn)
    return RecordedPost(
        packageName = snapshot.packageName,
        channelId = snapshot.channelId,
        category = snapshot.category,
        isOngoing = snapshot.isOngoing,
        notificationId = snapshot.id,
        tag = snapshot.tag,
        title = snapshot.title,
        text = snapshot.text,
        redacted = snapshot.platformRedacted,
        removalReason = removalReason,
        atUtcMs = atUtcMs,
        versionCode = snapshot.versionCode,
    )
}
