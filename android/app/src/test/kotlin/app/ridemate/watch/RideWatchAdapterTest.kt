package app.ridemate.watch

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Process
import android.provider.Settings
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.core.invariants.Invariant
import app.ridemate.core.invariants.Invariants
import app.ridemate.core.model.Provider
import app.ridemate.core.watch.PostRemoval
import app.ridemate.core.watch.PostSnapshot
import app.ridemate.core.watch.PostSource
import app.ridemate.core.watch.RemovalReason
import app.ridemate.core.watch.RemovalSource
import app.ridemate.notify.GuardianChannels
import app.ridemate.onboarding.AndroidSelfTestNotifier
import app.ridemate.onboarding.HealthCheck
import app.ridemate.onboarding.HealthResult
import app.ridemate.testing.FakeClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** A listener post that counts what was read from it. */
class CountingPost(
    override val packageName: String,
    private val channel: String? = "ride",
    private val tag: String? = null,
    private val key: String = "k-$packageName",
) : ListenerPost {
    var contentReads = 0
    var channelReads = 0

    override fun read(): PostSnapshot {
        contentReads++
        return PostSnapshot(packageName, key, 1, tag, channel, "status", true, "title", "text", 1_000L, 1L)
    }

    override fun ownChannelId(): String? {
        channelReads++
        return channel
    }

    override fun ownTag(): String? = tag
}

class RecordingSink : RideWatchSink {
    val posts = mutableListOf<PostSource>()
    val removals = mutableListOf<RemovalSource>()
    val connections = mutableListOf<List<PostSource>>()
    var disconnects = 0

    override fun onRideAppPost(post: PostSource) {
        posts += post
    }

    override fun onRideAppRemoval(removal: RemovalSource) {
        removals += removal
    }

    override fun onListenerConnected(active: List<PostSource>) {
        connections += active
    }

    override fun onListenerDisconnected() {
        disconnects++
    }
}

@RunWith(AndroidJUnit4::class)
class RideWatchAdapterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val status = RideWatchStatus(context, FakeClock())
    private val sink = RecordingSink()
    private val adapter = RideWatchAdapter(context.packageName, sink, status)

    @Test
    fun `posts from other apps are dropped before anything but the package name is read (I7)`() {
        val others = listOf("com.whatsapp", "com.google.android.apps.messaging", "in.amazon.mShop.android.shopping").map { CountingPost(it) }
        val rides = Provider.LIVE_APPS.map { CountingPost(it.packageName) }

        (others + rides).forEach(adapter::onPosted)

        assertEquals(rides, sink.posts)
        assertTrue(others.all { it.contentReads == 0 && it.channelReads == 0 })
        assertEquals(3, adapter.droppedUnread)
        // The core I7 checker agrees: nothing outside the allowlist was read.
        val reads = (others + rides).map { it.packageName to (it.contentReads > 0) }
        assertTrue(Invariants.checkI7(reads, emptyList(), Provider.RIDE_APP_PACKAGES).none { it.invariant != Invariant.I7 })
        assertTrue(Invariants.checkI7(reads, emptyList(), Provider.RIDE_APP_PACKAGES).isEmpty())
    }

    @Test
    fun `demo packages never come through the listener`() {
        val demo = CountingPost("demo.kestrel")
        adapter.onPosted(demo)
        assertTrue(sink.posts.isEmpty())
        assertEquals(0, demo.contentReads)
    }

    @Test
    fun `RideMate's own posts are dropped except the self-test, of which only channel and tag are read`() {
        val ongoing = CountingPost(context.packageName, channel = GuardianChannels.GUARDIAN)
        adapter.onPosted(ongoing)
        assertTrue(sink.posts.isEmpty())
        assertEquals(0, ongoing.contentReads)
        assertEquals(1, adapter.droppedUnread)

        assertFalse(status.hasSeen("token"))
        val selfTest = CountingPost(context.packageName, channel = GuardianChannels.SELFTEST, tag = "token")
        adapter.onPosted(selfTest)
        assertEquals(0, selfTest.contentReads)
        assertEquals(1, adapter.droppedUnread)
        // Onboarding's health check (bound to RideWatchStatus) now sees it; old tags are forgotten (bounded).
        assertTrue(status.hasSeen("token"))
        repeat(40) { adapter.onPosted(CountingPost(context.packageName, channel = GuardianChannels.SELFTEST, tag = "x$it")) }
        assertFalse(status.hasSeen("token"))
    }

    @Test
    fun `removals from other apps are dropped unread, ride apps' removals pass on`() {
        var otherRead = false
        val other = object : RemovalSource {
            override val packageName = "com.whatsapp"
            override fun read(): PostRemoval {
                otherRead = true
                return PostRemoval(packageName, "k", RemovalReason.REASON_APP_CANCEL)
            }
        }
        val ride = RemovalSource.of(PostRemoval("com.olacabs.customer", "k", RemovalReason.REASON_APP_CANCEL))

        adapter.onRemoved(other)
        adapter.onRemoved(ride)

        assertFalse(otherRead)
        assertEquals(listOf(ride), sink.removals)
    }

    @Test
    fun `on reconnect the whole shade is re-read, allowlisted posts only (I9)`() {
        val active = listOf(CountingPost("com.ubercab"), CountingPost("com.whatsapp"), CountingPost("com.rapido.passenger"))

        adapter.onConnected(active) {}

        assertTrue(status.connected.value)
        assertEquals(listOf(listOf(active[0], active[2])), sink.connections)
        assertEquals(0, active[1].contentReads)

        adapter.onDisconnected()
        assertFalse(status.connected.value)
        assertEquals(1, sink.disconnects)
    }

    @Test
    fun `a shade that cannot be read is never reported as an empty one`() {
        // The listener connected but reading the shade failed: the guardian is not told "nothing is there", so a
        // +60 s check keeps treating the ride as possibly live.
        var rereads = 0
        adapter.onConnected(null) { rereads++ }
        assertTrue(status.connected.value)
        assertTrue(sink.connections.isEmpty())

        adapter.onReread(null)
        assertTrue(sink.connections.isEmpty())

        // A later successful re-read (after a restore or an erase) does reach the guardian.
        assertTrue(status.requestReread())
        assertEquals(1, rereads)
        adapter.onReread(listOf(CountingPost("com.olacabs.customer")))
        assertEquals(1, sink.connections.single().size)
    }

    @Test
    fun `the self-test passes when the listener sees RideMate's own test post`() = runBlocking {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Settings.Secure.putString(
            context.contentResolver, "enabled_notification_listeners",
            ComponentName(context, RideWatchService::class.java).flattenToString(),
        )
        assertTrue(status.isAccessGranted())
        val manager = context.getSystemService(NotificationManager::class.java)
        val reader = SbnPostReader(VersionCodeLookup { null })

        // The one self-test: onboarding's HealthCheck posts on rm_selftest and polls what the listener saw.
        val check = HealthCheck({ true }, AndroidSelfTestNotifier(context), { status })
        val result = async(Dispatchers.Default) { check.run(timeoutMs = 5_000, pollMs = 10) }
        val posted: StatusBarNotification = withTimeout(5_000) {
            var found: StatusBarNotification? = null
            while (found == null) {
                found = manager.activeNotifications.firstOrNull { it.notification.channelId == GuardianChannels.SELFTEST }
                if (found == null) delay(10)
            }
            found
        }
        // What the system hands the listener: RideMate's own post on the self-test channel.
        val delivered = StatusBarNotification(
            context.packageName, context.packageName, posted.id, posted.tag, Process.myUid(), 0, 0,
            posted.notification, Process.myUserHandle(), posted.postTime,
        )
        adapter.onPosted(SbnListenerPost(delivered, reader))

        assertEquals(HealthResult.SEEN, result.await())
        assertTrue(manager.activeNotifications.none { it.notification.channelId == GuardianChannels.SELFTEST })
    }

    @Test
    fun `the ping and health report notification access that is off`() = runBlocking {
        // NotificationManagerCompat caches the enabled list across tests; an empty value refreshes it.
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", "")
        assertFalse(status.isAccessGranted())
        assertFalse("the pre-session ping fails", status.ping())
        assertEquals(WatchHealth.ACCESS_OFF, status.health())
    }

    @Test
    fun `the self-test is not seen when the listener never sees its post, and its post is removed`() = runBlocking {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Settings.Secure.putString(
            context.contentResolver, "enabled_notification_listeners",
            ComponentName(context, RideWatchService::class.java).flattenToString(),
        )
        val check = HealthCheck({ true }, AndroidSelfTestNotifier(context), { status })
        assertEquals(HealthResult.NOT_SEEN, check.run(timeoutMs = 50, pollMs = 10))
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue(manager.activeNotifications.none { it.notification.channelId == GuardianChannels.SELFTEST })
        assertEquals(WatchHealth.NOT_CONNECTED, status.health())
        status.onConnected {}
        assertEquals(WatchHealth.OK, status.health())
    }
}
