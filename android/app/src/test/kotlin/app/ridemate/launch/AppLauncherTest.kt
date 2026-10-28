package app.ridemate.launch

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.core.model.LatLng
import app.ridemate.core.model.Provider
import app.ridemate.testing.FakeClock
import app.ridemate.trip.TripPlace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLauncherTest {
    private val apps = FakeInstalledApps()
    private val starter = RecordingStarter()
    private val notifier = FakeNotifier()
    private val clipboard = FakeClipboard()
    private val bubble = MutableBubble(isShowing = true)
    private val foreground = MutableForeground(isForeground = false)
    private val legs = RecordingLegSink()
    private val clock = FakeClock()

    private val launcher = AppLauncher(
        templates = { LaunchPlanner.DEFAULT_TEMPLATES },
        apps = apps,
        starter = starter,
        notifier = notifier,
        clipboard = clipboard,
        bubble = bubble,
        foreground = foreground,
        legOpened = legs,
        clock = clock,
    )

    private val pickup = TripPlace("Barpeta Road", LatLng(26.5032, 90.9757))
    private val drop = TripPlace("LGBI Airport, Guwahati", LatLng(26.1061, 91.5859))

    private fun request(provider: Provider, legId: String? = "leg-${provider.wireId}", to: TripPlace = drop) =
        LaunchRequest(provider, pickup, to, legId)

    @Test
    fun `uber opens the universal link inside uber, without client_id, and records OPENED`() = runBlocking {
        val result = launcher.open(request(Provider.UBER))

        assertEquals(LaunchResult(Provider.UBER, LaunchOutcome.OPENED, LaunchMethod.DEEP_LINK, dropCopied = false, legRecorded = true), result)
        val intent = starter.started.single()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("com.ubercab", intent.`package`)
        val uri = intent.dataString!!
        assertTrue(uri.startsWith("https://m.uber.com/ul/?action=setPickup&pickup[latitude]=26.503200"))
        assertTrue(uri.contains("dropoff[nickname]=LGBI%20Airport%2C%20Guwahati"))
        assertFalse(uri.contains("client_id"))
        assertTrue(clipboard.copies.isEmpty())
        assertNull(launcher.pasteDrop.value)
        assertEquals(listOf(Triple("leg-uber", Provider.UBER, clock.wall)), legs.opened)
    }

    @Test
    fun `uber falls back to a plain launch when the link can't be handled`() = runBlocking {
        apps.linksHandled = false
        val result = launcher.open(request(Provider.UBER))

        assertEquals(LaunchOutcome.OPENED, result.outcome)
        assertEquals(LaunchMethod.PLAIN_FALLBACK, result.method)
        val intent = starter.started.single()
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertEquals("com.ubercab", intent.`package`)
        assertTrue(clipboard.copies.isEmpty()) // fallback "launch" offers nothing to paste
        assertEquals(1, legs.opened.size)
    }

    @Test
    fun `uber falls back when android refuses the link`() = runBlocking {
        starter.accept = { it.action != Intent.ACTION_VIEW }
        val result = launcher.open(request(Provider.UBER))

        assertEquals(LaunchMethod.PLAIN_FALLBACK, result.method)
        assertEquals(1, starter.refused.size)
        assertEquals(Intent.ACTION_MAIN, starter.started.single().action)
    }

    @Test
    fun `a typed drop without coordinates opens uber plainly`() = runBlocking {
        val result = launcher.open(request(Provider.UBER, to = TripPlace("Near the old temple", null)))
        assertEquals(LaunchMethod.PLAIN, result.method)
        assertEquals(Intent.ACTION_MAIN, starter.started.single().action)
    }

    @Test
    fun `ola opens its launcher and puts the drop on the clipboard for the paste chip`() = runBlocking {
        val result = launcher.open(request(Provider.OLA))

        assertEquals(LaunchResult(Provider.OLA, LaunchOutcome.OPENED, LaunchMethod.PLAIN, dropCopied = true, legRecorded = true), result)
        assertEquals(listOf("LGBI Airport, Guwahati"), clipboard.copies)
        assertEquals("com.olacabs.customer", starter.started.single().`package`)
        assertEquals(PasteDropChip(Provider.OLA, "LGBI Airport, Guwahati", clock.wall), launcher.pasteDrop.value)
        assertEquals(listOf(Triple("leg-ola", Provider.OLA, clock.wall)), legs.opened)
    }

    @Test
    fun `rapido too, and opening uber afterwards clears the chip`() = runBlocking {
        launcher.open(request(Provider.RAPIDO))
        assertEquals(Provider.RAPIDO, launcher.pasteDrop.value?.provider)
        launcher.open(request(Provider.UBER))
        assertNull(launcher.pasteDrop.value)
    }

    @Test
    fun `a clipboard failure still opens the app but shows no chip`() = runBlocking {
        clipboard.works = false
        val result = launcher.open(request(Provider.OLA))
        assertEquals(LaunchOutcome.OPENED, result.outcome)
        assertFalse(result.dropCopied)
        assertNull(launcher.pasteDrop.value)
    }

    @Test
    fun `in the background without the bubble a heads-up notification is posted instead`() = runBlocking {
        bubble.isShowing = false
        foreground.isForeground = false
        assertFalse(launcher.canOpenDirectly())

        val result = launcher.open(request(Provider.OLA))

        assertEquals(LaunchResult(Provider.OLA, LaunchOutcome.NOTIFICATION_POSTED), result)
        assertEquals(listOf(request(Provider.OLA)), notifier.posted)
        assertTrue(starter.started.isEmpty())
        assertTrue(clipboard.copies.isEmpty())
        assertTrue(legs.opened.isEmpty()) // OPENED only once the app really opens
    }

    @Test
    fun `when notifications are off too, the launch is reported as blocked`() = runBlocking {
        bubble.isShowing = false
        notifier.canPost = false
        assertEquals(LaunchOutcome.BLOCKED, launcher.open(request(Provider.RAPIDO)).outcome)
        assertTrue(starter.started.isEmpty())
    }

    @Test
    fun `ridemate in the foreground may open directly without the bubble`() = runBlocking {
        bubble.isShowing = false
        foreground.isForeground = true
        assertTrue(launcher.canOpenDirectly())
        assertEquals(LaunchOutcome.OPENED, launcher.open(request(Provider.OLA)).outcome)
        assertTrue(notifier.posted.isEmpty())
    }

    @Test
    fun `the notification tap opens the app regardless and clears the notification`() = runBlocking {
        bubble.isShowing = false
        val result = launcher.openFromUserTap(request(Provider.RAPIDO))
        assertEquals(LaunchOutcome.OPENED, result.outcome)
        assertEquals(listOf(Provider.RAPIDO), notifier.cancelled)
        assertEquals(listOf("LGBI Airport, Guwahati"), clipboard.copies)
        assertEquals(1, legs.opened.size)
    }

    @Test
    fun `apps that are missing, demo providers and ONDC are never launched`() = runBlocking {
        apps.installed = setOf(Provider.UBER)
        assertEquals(LaunchOutcome.NOT_INSTALLED, launcher.open(request(Provider.OLA)).outcome)
        assertEquals(LaunchOutcome.NOT_A_RIDE_APP, launcher.open(request(Provider.DEMO_KESTREL)).outcome)
        assertEquals(LaunchOutcome.NOT_A_RIDE_APP, launcher.open(request(Provider.ONDC)).outcome)
        assertTrue(starter.started.isEmpty())
        assertTrue(notifier.posted.isEmpty())
        assertTrue(legs.opened.isEmpty())
    }

    @Test
    fun `android refusing the launch is a failure and nothing is recorded`() = runBlocking {
        starter.accept = { false }
        val result = launcher.open(request(Provider.OLA))
        assertEquals(LaunchOutcome.FAILED, result.outcome)
        assertTrue(legs.opened.isEmpty())
        assertNull(launcher.pasteDrop.value)
    }

    @Test
    fun `without a leg id nothing is recorded`() = runBlocking {
        val result = launcher.open(request(Provider.OLA, legId = null))
        assertTrue(legs.opened.isEmpty())
        assertFalse(result.legRecorded)
    }

    @Test
    fun `a failed OPENED write still reports the app as opened and is passed on, not thrown`() = runBlocking {
        val failures = mutableListOf<Throwable>()
        val failing = AppLauncher(
            templates = { LaunchPlanner.DEFAULT_TEMPLATES },
            apps = apps,
            starter = starter,
            notifier = notifier,
            clipboard = clipboard,
            bubble = bubble,
            foreground = foreground,
            legOpened = { _, _, _ -> throw IllegalStateException("disk full") },
            clock = clock,
            onRecordFailure = { failures += it },
        )

        val result = failing.open(request(Provider.OLA))

        assertEquals(LaunchOutcome.OPENED, result.outcome)
        assertTrue(result.dropCopied)
        assertFalse(result.legRecorded)
        assertEquals("disk full", failures.single().message)
        assertEquals("com.olacabs.customer", starter.started.single().`package`)
        assertEquals(Provider.OLA, failing.pasteDrop.value?.provider)
    }

    @Test
    fun `the paste chip copies the drop again`() = runBlocking {
        assertFalse(launcher.copyDropAgain())
        launcher.open(request(Provider.OLA))
        clock.advance(30_000)
        assertTrue(launcher.copyDropAgain())
        assertEquals(listOf("LGBI Airport, Guwahati", "LGBI Airport, Guwahati"), clipboard.copies)
        assertEquals(clock.wall, launcher.pasteDrop.value!!.copiedAtWallMs)
        launcher.clearPasteDrop()
        assertNull(launcher.pasteDrop.value)
    }

    @Test
    fun `plan exposes what open would do`() {
        assertTrue(launcher.plan(request(Provider.UBER)) is LaunchPlan.DeepLink)
        assertEquals(LaunchPlan.Launch(Provider.OLA, drop.label), launcher.plan(request(Provider.OLA)))
    }
}
