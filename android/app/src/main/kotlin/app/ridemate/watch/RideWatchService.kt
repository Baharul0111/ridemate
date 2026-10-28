package app.ridemate.watch

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.ridemate.RideMateApp
import app.ridemate.crash.CrashSite

/**
 * RideWatch's NotificationListenerService (§4.5). It only reads notifications; it never acts on them or on the apps
 * that posted them (no replies, no actions, no cancelling another app's posts).
 *
 * - Posts and removals go through [RideWatchAdapter], which drops every package but the three ride apps unread (I7).
 * - Allowlisted posts reach core's RideWatchPipeline immediately (no debounce) via the guardian runtime.
 * - On disconnect the service asks the system to rebind; on (re)connect the whole shade is re-read and every
 *   allowlisted post is processed as new (I9).
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
