package app.ridemate.watch

import app.ridemate.core.model.Provider
import app.ridemate.core.watch.PostSource
import app.ridemate.core.watch.RemovalSource
import app.ridemate.notify.GuardianChannels
import java.util.concurrent.atomic.AtomicLong

/** Where allowlisted ride-app posts go (the guardian runtime feeds them into core's RideWatchPipeline). */
interface RideWatchSink {
    fun onRideAppPost(post: PostSource)
    fun onRideAppRemoval(removal: RemovalSource)

    /** §4.5.8 / I9: every active allowlisted post, to be processed as new. */
    fun onListenerConnected(active: List<PostSource>)
    fun onListenerDisconnected()
}

/**
 * The NotificationListenerService side of RideWatch, kept free of Android types so it can be tested directly.
 *
 * I7: the package name is checked before anything else. Posts and removals from any app outside
 * `com.ubercab`, `com.olacabs.customer` and `com.rapido.passenger` are dropped unread. RideMate's own posts are dropped
 * too, except the onboarding self-test on channel [GuardianChannels.SELFTEST], of which only the channel id and tag
 * are read. Demo posts never come through here (DemoSource feeds the pipeline directly).
 */
class RideWatchAdapter(
    private val ownPackage: String,
    private val sink: RideWatchSink,
    private val status: RideWatchStatus,
) {
    private val dropped = AtomicLong()

    /** Posts and removals dropped unread (other apps, and RideMate's own non-self-test posts). */
    val droppedUnread: Long get() = dropped.get()

    fun onPosted(post: ListenerPost) {
        val pkg = post.packageName
        when {
            pkg in Provider.RIDE_APP_PACKAGES -> sink.onRideAppPost(post)
            pkg == ownPackage -> ownPost(post)
            else -> dropped.incrementAndGet()
        }
    }

    fun onRemoved(removal: RemovalSource) {
        if (removal.packageName in Provider.RIDE_APP_PACKAGES) sink.onRideAppRemoval(removal) else dropped.incrementAndGet()
    }

    /**
     * The listener (re)connected: re-read the shade, allowlisted posts only. [active] is null when the shade could not
     * be read: the guardian is then not told the shade is empty, so a +60 s check keeps assuming the ride may still be
     * live (a loud alert rather than a silent pass) until a later read succeeds.
     */
    fun onConnected(active: List<ListenerPost>?, rereadActive: () -> Unit) {
        status.onConnected(rereadActive)
        active?.let { sink.onListenerConnected(filterActive(it)) }
    }

    /** A re-read of the shade requested after a guardian restore or an erase (the listener stayed connected). */
    fun onReread(active: List<ListenerPost>?) {
        active?.let { sink.onListenerConnected(filterActive(it)) }
    }

    fun onDisconnected() {
        status.onDisconnected()
        sink.onListenerDisconnected()
    }

    private fun filterActive(active: List<ListenerPost>): List<PostSource> = active.filter { post ->
        when {
            post.packageName in Provider.RIDE_APP_PACKAGES -> true
            post.packageName == ownPackage -> {
                ownPost(post)
                false
            }
            else -> {
                dropped.incrementAndGet()
                false
            }
        }
    }

    private fun ownPost(post: ListenerPost) {
        if (post.ownChannelId() == GuardianChannels.SELFTEST) status.onSelfTestSeen(post.ownTag()) else dropped.incrementAndGet()
    }
}
