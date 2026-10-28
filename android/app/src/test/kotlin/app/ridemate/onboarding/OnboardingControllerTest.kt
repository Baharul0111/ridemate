package app.ridemate.onboarding

import android.app.Activity
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.auth.AuthState
import app.ridemate.auth.GoogleCredentialResult
import app.ridemate.testing.FakeAuthBackend
import app.ridemate.testing.FakeGoogleCredentialSource
import app.ridemate.testing.FakeNotifier
import app.ridemate.testing.FakePermissions
import app.ridemate.testing.FakeRideWatch
import app.ridemate.testing.ShellTestData
import app.ridemate.ui.InMemoryKeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class OnboardingControllerTest {
    private val kv = InMemoryKeyValueStore()
    private val store = OnboardingStore(kv)
    private val permissions = FakePermissions()
    private val notifier = FakeNotifier()
    private val rideWatch = FakeRideWatch(notifier)
    private val authBackend = FakeAuthBackend()
    private val google = FakeGoogleCredentialSource()
    private val auth = ShellTestData.authGateway(authBackend, google)

    private fun controller(scope: CoroutineScope, device: DeviceProfile = ShellTestData.SIDELOADED_PIXEL) =
        OnboardingController(store, permissions, device, auth, HealthCheck(permissions, notifier, { rideWatch }), scope)

    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).get()

    @Test
    fun `walks the whole order with a fake permission checker`() = runTest(UnconfinedTestDispatcher()) {
        val c = controller(backgroundScope)
        assertEquals(OnboardingStep.AGE_GATE, c.snapshot().step)
        c.confirmAdult()
        assertEquals(OnboardingStep.PRIVACY_NOTICE, c.snapshot().step)
        c.acceptNotice()
        assertEquals(OnboardingStep.SIGN_IN, c.snapshot().step)
        c.continueWithoutGoogle()
        assertEquals(OnboardingStep.NOTIFICATIONS, c.snapshot().step)
        assertTrue("manual mode signs in anonymously in the background", "signInAnonymous" in authBackend.log)

        // The user allows notifications in the system prompt: refresh() picks it up and the step advances.
        permissions.grant(AppPermission.POST_NOTIFICATIONS)
        c.refresh()
        assertEquals(OnboardingStep.NOTIFICATION_ACCESS, c.snapshot().step)
        c.opened(OnboardingStep.NOTIFICATION_ACCESS)
        permissions.grant(AppPermission.NOTIFICATION_ACCESS)
        c.refresh()
        assertEquals(OnboardingStep.OVERLAY, c.snapshot().step)
        c.finish(OnboardingStep.OVERLAY) // Not now
        assertEquals(OnboardingStep.LOCATION, c.snapshot().step)
        c.finish(OnboardingStep.LOCATION)
        assertEquals(OnboardingStep.BATTERY, c.snapshot().step)
        c.finish(OnboardingStep.BATTERY)
        assertEquals(OnboardingStep.HEALTH_CHECK, c.snapshot().step)
        c.runHealthCheck()
        assertEquals(OnboardingStep.DONE, c.snapshot().step)
        assertEquals(HealthCheckOutcome.PASSED, store.answers.value.healthCheck)
        assertFalse(store.answers.value.completed)
        c.complete()
        assertTrue(store.answers.value.completed)
        // The StateFlow the screens collect agrees with the snapshot.
        assertEquals(OnboardingStep.DONE, c.state.value.step)
    }

    @Test
    fun `onboarding resumes where it stopped`() = runTest(UnconfinedTestDispatcher()) {
        val first = controller(backgroundScope)
        first.confirmAdult()
        first.acceptNotice()
        first.continueWithoutGoogle()
        first.finish(OnboardingStep.NOTIFICATIONS)
        first.opened(OnboardingStep.NOTIFICATION_ACCESS)

        // Process death: a new store over the same persisted values, a new controller.
        val resumed = OnboardingController(
            OnboardingStore(kv), permissions, ShellTestData.SIDELOADED_PIXEL, auth, HealthCheck(permissions, notifier, { rideWatch }), backgroundScope,
        )
        assertEquals(OnboardingStep.NOTIFICATION_ACCESS, resumed.snapshot().step)
        assertEquals(setOf(OnboardingStep.NOTIFICATION_ACCESS), resumed.snapshot().answers.attempted)
    }

    @Test
    fun `already granted steps are never shown`() = runTest(UnconfinedTestDispatcher()) {
        permissions.grant(*AppPermission.entries.toTypedArray())
        authBackend.user = FakeAuthBackend.googleUser()
        val c = OnboardingController(
            store, permissions, ShellTestData.SIDELOADED_PIXEL, ShellTestData.authGateway(authBackend, google),
            HealthCheck(permissions, notifier, { rideWatch }), backgroundScope,
        )
        c.confirmAdult()
        c.acceptNotice()
        assertEquals(OnboardingStep.HEALTH_CHECK, c.snapshot().step)
    }

    @Test
    fun `under 18 is blocked and stays blocked`() = runTest(UnconfinedTestDispatcher()) {
        val c = controller(backgroundScope)
        c.declareUnderAge()
        assertEquals(OnboardingStep.UNDER_AGE_BLOCKED, c.snapshot().step)
        assertEquals(null, c.snapshot().position)
        val again = controller(backgroundScope)
        assertEquals(OnboardingStep.UNDER_AGE_BLOCKED, again.snapshot().step)
        // Erase forgets the answer.
        store.clear()
        assertEquals(OnboardingStep.AGE_GATE, controller(backgroundScope).snapshot().step)
    }

    @Test
    fun `google sign-in success finishes the step, a cancel says what to do next`() = runTest(UnconfinedTestDispatcher()) {
        val c = controller(backgroundScope)
        c.confirmAdult()
        c.acceptNotice()
        google.result = { GoogleCredentialResult.Cancelled }
        c.signInWithGoogle(activity())
        assertEquals(SignInStatus.Problem(SignInProblem.CANCELLED), c.snapshot().signIn)
        assertEquals(OnboardingStep.SIGN_IN, c.snapshot().step)

        google.result = { GoogleCredentialResult.NoGoogleAccount }
        c.signInWithGoogle(activity())
        assertEquals(SignInStatus.Problem(SignInProblem.NO_GOOGLE_ACCOUNT), c.snapshot().signIn)

        google.result = { GoogleCredentialResult.Success(FakeGoogleCredentialSource.jwtWithNonce(it)) }
        c.signInWithGoogle(activity())
        assertEquals(SignInStatus.Idle, c.snapshot().signIn)
        assertEquals(SignInChoice.GOOGLE, store.answers.value.signIn)
        assertTrue(auth.state.value is AuthState.Google)
        assertEquals(OnboardingStep.NOTIFICATIONS, c.snapshot().step)
    }

    @Test
    fun `sign-in is offered again once at the first read when the user chose manual mode`() = runTest(UnconfinedTestDispatcher()) {
        val reoffer = SignInReoffer(store, auth.state)
        assertFalse("not before the user chose", reoffer.shouldOfferAtFirstRead())
        controller(backgroundScope).continueWithoutGoogle()
        assertTrue(reoffer.shouldOfferAtFirstRead())
        reoffer.markOffered()
        assertFalse(reoffer.shouldOfferAtFirstRead())

        store.clear()
        store.chooseSignIn(SignInChoice.MANUAL)
        authBackend.user = FakeAuthBackend.googleUser()
        val signedIn = SignInReoffer(store, ShellTestData.authGateway(authBackend, google).state)
        assertFalse("a Google user is never asked again", signedIn.shouldOfferAtFirstRead())
    }

    @Test
    fun `health check polls at most 5 seconds and always removes the test notification`() = runTest(StandardTestDispatcher()) {
        permissions.grant(AppPermission.POST_NOTIFICATIONS, AppPermission.NOTIFICATION_ACCESS)
        rideWatch.seeAfterPolls = null
        val check = HealthCheck(permissions, notifier, { rideWatch }, newToken = { "token-1" })
        val result = async { check.run() }
        advanceTimeBy(HealthCheck.TIMEOUT_MS - 1)
        runCurrent()
        assertFalse("still polling just before 5 s", result.isCompleted)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(HealthResult.NOT_SEEN, result.await())
        assertEquals(listOf("token-1"), notifier.cancelled)
        assertTrue(rideWatch.polls > 10)
    }

    @Test
    fun `health check reports what is missing before posting`() = runTest {
        val check = HealthCheck(permissions, notifier, { rideWatch })
        assertEquals(HealthResult.NOTIFICATIONS_BLOCKED, check.run())
        permissions.grant(AppPermission.POST_NOTIFICATIONS)
        assertEquals(HealthResult.ACCESS_MISSING, check.run())
        permissions.grant(AppPermission.NOTIFICATION_ACCESS)
        notifier.allowed = false
        assertEquals(HealthResult.NOTIFICATIONS_BLOCKED, check.run())
        notifier.allowed = true
        rideWatch.seeAfterPolls = 3
        assertEquals(HealthResult.SEEN, check.run())
        assertTrue(notifier.posted.isNotEmpty())
        assertEquals(notifier.posted, notifier.cancelled)
    }

    @Test
    fun `a failed health check can be skipped`() = runTest(UnconfinedTestDispatcher()) {
        val c = controller(backgroundScope)
        c.runHealthCheck()
        assertEquals(HealthStatus.Finished(HealthResult.NOTIFICATIONS_BLOCKED), c.snapshot().health)
        assertEquals(null, store.answers.value.healthCheck)
        c.skipHealthCheck()
        assertEquals(HealthCheckOutcome.SKIPPED, store.answers.value.healthCheck)
    }

    @Test
    fun `self-test registry recognises only RideMate's own self-test channel`() {
        val registry = SelfTestRegistry()
        assertTrue(SelfTestRegistry.isSelfTestPost("in.ridemate.app", "rm_selftest", "in.ridemate.app"))
        assertFalse(SelfTestRegistry.isSelfTestPost("com.ubercab", "rm_selftest", "in.ridemate.app"))
        assertFalse(SelfTestRegistry.isSelfTestPost("in.ridemate.app", "reminders", "in.ridemate.app"))
        assertFalse(registry.hasSeen("t1"))
        registry.onPosted("t1")
        registry.onPosted(null)
        assertTrue(registry.hasSeen("t1"))
        repeat(40) { registry.onPosted("x$it") }
        assertFalse("bounded: old tags are forgotten", registry.hasSeen("t1"))
    }
}
