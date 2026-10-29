package app.ridemate.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import app.ridemate.R
import app.ridemate.auth.AuthState
import app.ridemate.e2e.DeviceE2e.PKG
import app.ridemate.e2e.DeviceE2e.button
import app.ridemate.e2e.DeviceE2e.device
import app.ridemate.e2e.DeviceE2e.graph
import app.ridemate.e2e.DeviceE2e.hasText
import app.ridemate.e2e.DeviceE2e.leaveAndReturn
import app.ridemate.e2e.DeviceE2e.log
import app.ridemate.e2e.DeviceE2e.scrollAndTap
import app.ridemate.e2e.DeviceE2e.shell
import app.ridemate.e2e.DeviceE2e.str
import app.ridemate.e2e.DeviceE2e.waitFor
import app.ridemate.e2e.DeviceE2e.waitObject
import app.ridemate.e2e.DeviceE2e.waitText
import app.ridemate.e2e.DeviceE2e.waitUntil
import app.ridemate.onboarding.AndroidPermissionChecker
import app.ridemate.onboarding.AppPermission
import app.ridemate.onboarding.DeviceProfile
import app.ridemate.onboarding.HealthCheckOutcome
import app.ridemate.onboarding.OnboardingFlow
import app.ridemate.onboarding.SignInChoice
import app.ridemate.onboarding.granted
import app.ridemate.onboarding.permissionNameRes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Goal "installs fresh … and completes onboarding", on the owner's phone: the real onboarding screens in
 * architecture §4.2 order on a freshly installed RideMate. Each permission step is granted for RideMate only, through
 * the shell, at the moment the step asks for it, exactly where a user would flip the switch:
 *
 * 18+ → privacy notice → "Continue without signing in" (manual mode) → notifications (`pm grant POST_NOTIFICATIONS`)
 * → notification access (`cmd notification allow_listener` for RideWatch) → display over other apps (`appops`)
 * → location (`pm grant` fine + coarse) → battery (`dumpsys deviceidle whitelist +in.ridemate.app`, then the
 * Motorola autostart guidance's Continue) → health check (RideWatch sees RideMate's own test notification) → done
 * → Home.
 *
 * Needs a fresh install (the device suite uninstalls RideMate first and runs this class before the others).
 */
@RunWith(AndroidJUnit4::class)
class FreshInstallOnboardingTest {

    private val checker = AndroidPermissionChecker(DeviceE2e.context)

    @After
    fun tearDown() {
        DeviceE2e.finishActivities()
    }

    @Test
    fun freshInstall_walksEveryOnboardingStep_healthCheckSeesItsOwnNotification_reachesHome() {
        val store = graph.appShell.onboardingStore
        assertFalse("RideMate must be freshly installed (uninstall $PKG before the device suite)", store.answers.value.completed)
        assertFalse("fresh install: no 18+ answer yet", store.answers.value.adultConfirmed)
        assertEquals("fresh install: nothing granted yet", emptySet<AppPermission>(), checker.granted())

        DeviceE2e.launchMain()

        // 1. 18+ gate.
        waitText(str(R.string.onb_age_title))
        val steps = OnboardingFlow.visibleOrder(DeviceProfile.of(DeviceE2e.context)).size
        assertTrue(hasText(str(R.string.onb_step_of, 1, steps)))
        scrollAndTap(By.text(str(R.string.onb_age_yes)), "Yes, I'm 18 or older", confirm = { store.answers.value.adultConfirmed })
        waitUntil("18+ confirmed", 5_000) { store.answers.value.adultConfirmed }

        // 2. Privacy notice (long: the accept button is below the fold).
        waitText(str(R.string.onb_notice_title))
        scrollAndTap(By.text(str(R.string.onb_notice_accept)), "I agree, continue",
            confirm = { store.answers.value.noticeVersion == OnboardingFlow.NOTICE_VERSION })
        waitUntil("notice accepted", 5_000) { store.answers.value.noticeVersion == OnboardingFlow.NOTICE_VERSION }

        // 3. Google sign-in: skipped → manual mode (an anonymous token is fetched in the background).
        waitText(str(R.string.onb_signin_skip))
        assertTrue(hasText(str(R.string.onb_signin_manual_note)))
        scrollAndTap(By.text(str(R.string.onb_signin_skip)), "Continue without signing in",
            confirm = { store.answers.value.signIn == SignInChoice.MANUAL })
        waitUntil("manual mode chosen", 5_000) { store.answers.value.signIn == SignInChoice.MANUAL }

        // 4. Notifications: the step asks; RideMate's POST_NOTIFICATIONS is granted; the step's button re-checks.
        waitText(str(R.string.onb_notif_why))
        assertFalse(checker.isGranted(AppPermission.POST_NOTIFICATIONS))
        assertTrue(DeviceE2e.has(By.desc(chip(AppPermission.POST_NOTIFICATIONS, granted = false))))
        shell("pm grant $PKG android.permission.POST_NOTIFICATIONS")
        scrollAndTap(button(str(R.string.onb_notif_action)), "Allow notifications")

        // 5. Notification access for RideWatch (the user would flip it in Settings and come back).
        waitText(str(R.string.onb_access_title))
        assertTrue(checker.isGranted(AppPermission.POST_NOTIFICATIONS))
        shell("cmd notification allow_listener ${DeviceE2e.LISTENER}")
        waitUntil("notification access granted", 10_000) { checker.isGranted(AppPermission.NOTIFICATION_ACCESS) }
        leaveAndReturn()

        // 6. Display over other apps (the bubble).
        waitText(str(R.string.onb_overlay_title))
        shell("appops set $PKG SYSTEM_ALERT_WINDOW allow")
        waitUntil("overlay allowed", 10_000) { checker.isGranted(AppPermission.OVERLAY) }
        leaveAndReturn()

        // 7. Location while in use: granted, then the step's button re-checks (no system dialog once granted).
        waitText(str(R.string.onb_location_title))
        assertFalse("the restricted-settings step is only for blocked switches", hasText(str(R.string.onb_restricted_title)))
        shell("pm grant $PKG android.permission.ACCESS_FINE_LOCATION")
        shell("pm grant $PKG android.permission.ACCESS_COARSE_LOCATION")
        scrollAndTap(button(str(R.string.onb_location_action)), "Allow location")

        // 8. Battery: exemption for RideMate, then the maker's autostart guidance (Motorola) needs Continue.
        waitText(str(R.string.onb_battery_title))
        shell("dumpsys deviceidle whitelist +$PKG")
        waitUntil("battery exemption", 10_000) { checker.isGranted(AppPermission.BATTERY_EXEMPTION) }
        leaveAndReturn()
        waitFor("battery step re-checked", 15_000) {
            when {
                hasText(str(R.string.onb_health_title)) -> Unit
                hasText(str(R.string.onb_continue)) -> {
                    scrollAndTap(button(str(R.string.onb_continue)), "Continue (battery)")
                    Unit
                }
                else -> null
            }
        }

        // 9. Health check: RideWatch must see RideMate's own rm_selftest notification within 5 s.
        waitText(str(R.string.onb_health_title))
        scrollAndTap(button(str(R.string.onb_health_run)), "Run the self-test",
            confirm = { store.answers.value.healthCheck != null || !hasText(str(R.string.onb_health_run)) })
        waitUntil("health check PASSED (RideWatch saw the test notification)", 20_000) {
            store.answers.value.healthCheck == HealthCheckOutcome.PASSED
        }

        // 10. Done: every permission shows Allowed.
        waitText(str(R.string.onb_done_title))
        assertEquals(AppPermission.entries.toSet(), checker.granted())
        assertTrue(DeviceE2e.has(By.desc(chip(AppPermission.NOTIFICATION_ACCESS, granted = true))))
        assertFalse(DeviceE2e.has(By.descEndsWith(": " + str(R.string.onb_status_not_allowed))))
        scrollAndTap(button(str(R.string.onb_done_start)), "Start using RideMate", confirm = { store.answers.value.completed })

        // Home.
        waitObject(By.desc(str(R.string.home_try_demo_cd)), "Home's Try the demo", 20_000)
        assertTrue(hasText(str(R.string.home_compare)))
        assertTrue(store.answers.value.completed)
        // Manual mode: the background anonymous sign-in (non-AI endpoints) normally finishes; it may lag offline.
        log("auth after onboarding: ${graph.authGateway.state.value::class.simpleName}")
        assertFalse(graph.authGateway.state.value is AuthState.Google)

        // Voice input asks for the microphone at first use (not an onboarding step); granted for the device run.
        shell("pm grant $PKG android.permission.RECORD_AUDIO")
    }

    /** The status chip's spoken text: "Notifications: Allowed". */
    private fun chip(permission: AppPermission, granted: Boolean): String =
        str(permissionNameRes(permission)) + ": " + str(if (granted) R.string.onb_status_allowed else R.string.onb_status_not_allowed)
}
