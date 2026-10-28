package app.ridemate.precheck

import app.ridemate.core.model.Provider
import app.ridemate.testing.FakeClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every distinct pre-session result (architecture §4.2) from fake probes. */
@OptIn(ExperimentalCoroutinesApi::class)
class PreSessionCheckTest {

    class FakeProbes(
        var listener: Boolean = true,
        var overlay: Boolean = true,
        var battery: Boolean = true,
        var notifications: Boolean = true,
        var online: Boolean = true,
        var location: Boolean = true,
        var installed: Set<Provider> = Provider.LIVE_APPS.toSet(),
        var throwing: Boolean = false,
    ) : PrecheckProbes {
        private fun <T> v(value: T): T = if (throwing) throw IllegalStateException("probe failed") else value
        override fun listenerEnabled() = v(listener)
        override fun overlayAllowed() = v(overlay)
        override fun batteryExempt() = v(battery)
        override fun notificationsAllowed() = v(notifications)
        override fun online() = v(online)
        override fun locationAllowed() = v(location)
        override fun installedRideApps() = v(installed)
    }

    class FakeRideWatch(var answer: suspend () -> Boolean = { true }) : RideWatchStatus {
        var pings = 0
        override suspend fun ping(): Boolean {
            pings++
            return answer()
        }
    }

    private val probes = FakeProbes()
    private val rideWatch = FakeRideWatch()
    private var bound: RideWatchStatus? = rideWatch
    private val clock = FakeClock()
    private val allApps = Provider.LIVE_APPS.toSet()

    /** Runs the check on the test scheduler (virtual time, so the ping timeout costs nothing). */
    private suspend fun run(request: PrecheckRequest = PrecheckRequest(allApps), dispatcherScope: TestScope): PrecheckReport =
        PreSessionCheck(probes, { bound }, clock, io = StandardTestDispatcher(dispatcherScope.testScheduler)).run(request)

    private fun PrecheckReport.kinds() = issues.map { it.kind }

    @Test
    fun `all clear means no issues and compare starts at once`() = runTest {
        val report = run(dispatcherScope = this)
        assertTrue(report.issues.isEmpty())
        assertTrue(report.canProceed)
        assertFalse(report.needsAttention)
        assertEquals(1, rideWatch.pings)
        assertEquals(clock.wall, report.checkedAtWallMs)
    }

    @Test
    fun `notification access off is a blind listener and blocks with a fix-it card`() = runTest {
        probes.listener = false
        val report = run(dispatcherScope = this)
        val issue = report.issues.single()
        assertEquals(PrecheckKind.LISTENER_OFF, issue.kind)
        assertEquals(IssueSeverity.BLOCKING, issue.severity)
        assertEquals(listOf(FixAction.OPEN_NOTIFICATION_ACCESS), issue.fixes)
        assertFalse(report.canProceed)
        assertEquals(0, rideWatch.pings)
    }

    @Test
    fun `access on but RideWatch not answering is blind too`() = runTest {
        rideWatch.answer = { false }
        val report = run(dispatcherScope = this)
        assertEquals(listOf(PrecheckKind.LISTENER_NOT_RESPONDING), report.kinds())
        assertFalse(report.canProceed)
    }

    @Test
    fun `a ping that throws counts as not responding`() = runTest {
        rideWatch.answer = { throw IllegalStateException("unbound") }
        assertEquals(listOf(PrecheckKind.LISTENER_NOT_RESPONDING), run(dispatcherScope = this).kinds())
    }

    @Test
    fun `a ping that never answers times out after 2 s instead of hanging`() = runTest {
        val never = CompletableDeferred<Boolean>()
        rideWatch.answer = { never.await() }
        val start = currentTime
        val report = run(dispatcherScope = this)
        assertEquals(listOf(PrecheckKind.LISTENER_NOT_RESPONDING), report.kinds())
        assertEquals(PreSessionCheck.PING_TIMEOUT_MS, currentTime - start)
    }

    @Test
    fun `without a RideWatch binding the enabled-listener list decides`() = runTest {
        bound = null
        assertTrue(run(dispatcherScope = this).issues.isEmpty())
        probes.listener = false
        assertEquals(listOf(PrecheckKind.LISTENER_OFF), run(dispatcherScope = this).kinds())
    }

    @Test
    fun `no supported app installed offers the demo`() = runTest {
        probes.installed = emptySet()
        val issue = run(dispatcherScope = this).issues.single()
        assertEquals(PrecheckKind.NO_SUPPORTED_APP, issue.kind)
        assertEquals(IssueSeverity.BLOCKING, issue.severity)
        assertEquals(listOf(FixAction.TRY_DEMO), issue.fixes)
    }

    @Test
    fun `no app but the open network is fine`() = runTest {
        probes.installed = emptySet()
        assertTrue(run(PrecheckRequest(emptySet(), includeOndc = true), this).issues.isEmpty())
    }

    @Test
    fun `apps installed but none selected, or the selected one was uninstalled`() = runTest {
        assertEquals(listOf(PrecheckKind.NO_APP_SELECTED), run(PrecheckRequest(emptySet()), this).kinds())
        probes.installed = setOf(Provider.OLA)
        val report = run(PrecheckRequest(setOf(Provider.UBER)), this)
        assertEquals(listOf(PrecheckKind.NO_APP_SELECTED), report.kinds())
        assertEquals(listOf(FixAction.CHOOSE_APPS), report.issues.single().fixes)
        assertTrue(run(PrecheckRequest(setOf(Provider.UBER, Provider.OLA)), this).issues.isEmpty())
    }

    @Test
    fun `notifications off is a warning while the bubble can still remind`() = runTest {
        probes.notifications = false
        val issue = run(dispatcherScope = this).issues.single()
        assertEquals(PrecheckKind.NOTIFICATIONS_OFF, issue.kind)
        assertEquals(IssueSeverity.WARNING, issue.severity)
        assertEquals(listOf(FixAction.OPEN_NOTIFICATION_SETTINGS), issue.fixes)
    }

    @Test
    fun `notifications and overlay both off block, because nothing could remind the user`() = runTest {
        probes.notifications = false
        probes.overlay = false
        val report = run(dispatcherScope = this)
        assertEquals(listOf(PrecheckKind.NOTIFICATIONS_OFF, PrecheckKind.OVERLAY_OFF), report.kinds())
        assertEquals(IssueSeverity.BLOCKING, report.issues[0].severity)
        assertEquals(IssueSeverity.WARNING, report.issues[1].severity)
        assertFalse(report.canProceed)
    }

    @Test
    fun `overlay off is a warning`() = runTest {
        probes.overlay = false
        val report = run(dispatcherScope = this)
        assertEquals(PrecheckIssue(PrecheckKind.OVERLAY_OFF, IssueSeverity.WARNING, listOf(FixAction.OPEN_OVERLAY_SETTINGS)), report.issues.single())
        assertTrue(report.canProceed)
        assertTrue(report.needsAttention)
    }

    @Test
    fun `battery optimisation is a warning`() = runTest {
        probes.battery = false
        assertEquals(
            PrecheckIssue(PrecheckKind.BATTERY_RESTRICTED, IssueSeverity.WARNING, listOf(FixAction.REQUEST_BATTERY_EXEMPTION)),
            run(dispatcherScope = this).issues.single(),
        )
    }

    @Test
    fun `offline is a warning (prices are typed) and only a note in the demo`() = runTest {
        probes.online = false
        assertEquals(
            PrecheckIssue(PrecheckKind.OFFLINE, IssueSeverity.WARNING, listOf(FixAction.OPEN_INTERNET_SETTINGS)),
            run(dispatcherScope = this).issues.single(),
        )
        val demo = run(PrecheckRequest(emptySet(), demo = true), this)
        assertEquals(IssueSeverity.INFO, demo.issues.single().severity)
        assertFalse(demo.needsAttention)
    }

    @Test
    fun `location denied is only a note`() = runTest {
        probes.location = false
        val report = run(dispatcherScope = this)
        assertEquals(PrecheckIssue(PrecheckKind.LOCATION_DENIED, IssueSeverity.INFO, listOf(FixAction.REQUEST_LOCATION)), report.issues.single())
        assertTrue(report.canProceed)
        assertFalse(report.needsAttention)
    }

    @Test
    fun `the demo needs no ride app, RideWatch, overlay, battery exemption or location`() = runTest {
        probes.listener = false
        probes.installed = emptySet()
        probes.location = false
        probes.overlay = false
        probes.battery = false
        val report = run(PrecheckRequest(emptySet(), demo = true), this)
        assertTrue(report.issues.isEmpty())
        assertEquals(0, rideWatch.pings)
    }

    @Test
    fun `a probe that throws counts as not granted, never as fine`() = runTest {
        probes.throwing = true
        val kinds = run(dispatcherScope = this).kinds()
        assertTrue(PrecheckKind.LISTENER_OFF in kinds)
        assertTrue(PrecheckKind.NO_SUPPORTED_APP in kinds)
        assertTrue(PrecheckKind.NOTIFICATIONS_OFF in kinds)
        assertTrue(PrecheckKind.OFFLINE in kinds)
    }

    @Test
    fun `issues are ordered most severe first`() = runTest {
        probes.location = false
        probes.battery = false
        probes.listener = false
        val severities = run(dispatcherScope = this).issues.map { it.severity }
        assertEquals(listOf(IssueSeverity.BLOCKING, IssueSeverity.WARNING, IssueSeverity.INFO), severities)
    }

    @Test
    fun `the later session outcomes have their own cards`() {
        assertEquals(
            PrecheckIssue(PrecheckKind.NO_FARES_FOUND, IssueSeverity.WARNING, listOf(FixAction.RETRY_READ, FixAction.TYPE_PRICE)),
            PreSessionCheck.noFaresFound(),
        )
        assertEquals(
            PrecheckIssue(PrecheckKind.NO_ONDC_RESULTS, IssueSeverity.WARNING, listOf(FixAction.CONTINUE_WITHOUT_ONDC)),
            PreSessionCheck.noOndcResults(),
        )
    }

    @Test
    fun `every kind is distinct and reachable`() {
        assertEquals(PrecheckKind.entries.size, PrecheckKind.entries.map { it.name }.toSet().size)
        assertEquals(11, PrecheckKind.entries.size)
    }
}
