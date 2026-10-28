package app.ridemate.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The §4.2 onboarding order, auto-advance and the conditional steps, as a table over pure state. */
class OnboardingFlowTest {
    private val pixel = DeviceProfile(sdkInt = 35, release = "15", manufacturer = "Google", sideloaded = true)
    private val playInstall = pixel.copy(sideloaded = false)
    private val android12 = pixel.copy(sdkInt = 31, release = "12")

    private fun env(
        granted: Set<AppPermission> = emptySet(),
        google: Boolean = false,
        device: DeviceProfile = pixel,
        oem: Boolean = false,
    ) = OnboardingEnvironment(granted, google, device, oem)

    @Test
    fun `steps come in the architecture order and each answer moves to the next`() {
        var answers = OnboardingAnswers()
        val env = env()
        val seen = mutableListOf<OnboardingStep>()
        repeat(20) {
            val step = OnboardingFlow.current(answers, env)
            seen += step
            answers = when (step) {
                OnboardingStep.AGE_GATE -> answers.copy(adultConfirmed = true)
                OnboardingStep.PRIVACY_NOTICE -> answers.copy(noticeVersion = OnboardingFlow.NOTICE_VERSION)
                OnboardingStep.SIGN_IN -> answers.copy(signIn = SignInChoice.MANUAL)
                OnboardingStep.HEALTH_CHECK -> answers.copy(healthCheck = HealthCheckOutcome.PASSED)
                OnboardingStep.DONE -> return@repeat
                else -> answers.copy(done = answers.done + step)
            }
        }
        assertEquals(
            listOf(
                OnboardingStep.AGE_GATE,
                OnboardingStep.PRIVACY_NOTICE,
                OnboardingStep.SIGN_IN,
                OnboardingStep.NOTIFICATIONS,
                OnboardingStep.NOTIFICATION_ACCESS,
                OnboardingStep.OVERLAY,
                OnboardingStep.LOCATION,
                OnboardingStep.BATTERY,
                OnboardingStep.HEALTH_CHECK,
                OnboardingStep.DONE,
            ),
            seen.distinct(),
        )
    }

    @Test
    fun `under 18 blocks everything, whatever else is answered`() {
        val answers = OnboardingAnswers(underAge = true, noticeVersion = OnboardingFlow.NOTICE_VERSION, signIn = SignInChoice.GOOGLE)
        assertEquals(OnboardingStep.UNDER_AGE_BLOCKED, OnboardingFlow.current(answers, env(granted = AppPermission.entries.toSet())))
        assertEquals(null, OnboardingFlow.position(OnboardingStep.UNDER_AGE_BLOCKED))
    }

    @Test
    fun `nothing before the 18+ check, and the notice must be accepted before sign-in`() {
        assertEquals(OnboardingStep.AGE_GATE, OnboardingFlow.current(OnboardingAnswers(), env(google = true)))
        assertEquals(OnboardingStep.PRIVACY_NOTICE, OnboardingFlow.current(OnboardingAnswers(adultConfirmed = true), env(google = true)))
        // An older notice version must be accepted again.
        assertEquals(
            OnboardingStep.PRIVACY_NOTICE,
            OnboardingFlow.current(OnboardingAnswers(adultConfirmed = true, noticeVersion = "0.9"), env()),
        )
    }

    @Test
    fun `granted permissions and a Google sign-in auto-advance`() {
        val answers = OnboardingAnswers(adultConfirmed = true, noticeVersion = OnboardingFlow.NOTICE_VERSION)
        val all = AppPermission.entries.toSet()
        assertEquals(OnboardingStep.HEALTH_CHECK, OnboardingFlow.current(answers, env(granted = all, google = true)))
        assertEquals(
            OnboardingStep.LOCATION,
            OnboardingFlow.current(answers, env(granted = all - AppPermission.LOCATION, google = true)),
        )
        assertEquals(
            OnboardingStep.NOTIFICATION_ACCESS,
            OnboardingFlow.current(answers.copy(signIn = SignInChoice.MANUAL), env(granted = setOf(AppPermission.POST_NOTIFICATIONS))),
        )
    }

    @Test
    fun `restricted settings appear only on sideloaded Android 13+ after a blocked switch`() {
        val base = OnboardingAnswers(
            adultConfirmed = true,
            noticeVersion = OnboardingFlow.NOTICE_VERSION,
            signIn = SignInChoice.MANUAL,
            done = setOf(OnboardingStep.NOTIFICATION_ACCESS, OnboardingStep.OVERLAY),
            attempted = setOf(OnboardingStep.NOTIFICATION_ACCESS),
        )
        val granted = setOf(AppPermission.POST_NOTIFICATIONS)
        assertEquals(OnboardingStep.RESTRICTED_SETTINGS, OnboardingFlow.current(base, env(granted = granted)))
        assertTrue(OnboardingFlow.restrictedSettingsLikely(base, env(granted = granted)))

        // Installed from Play, Android 12, never attempted, or the switch now on: no extra step.
        assertEquals(OnboardingStep.LOCATION, OnboardingFlow.current(base, env(granted = granted, device = playInstall)))
        assertEquals(OnboardingStep.LOCATION, OnboardingFlow.current(base, env(granted = granted, device = android12)))
        assertEquals(OnboardingStep.LOCATION, OnboardingFlow.current(base.copy(attempted = emptySet()), env(granted = granted)))
        assertEquals(
            OnboardingStep.LOCATION,
            OnboardingFlow.current(base, env(granted = granted + AppPermission.NOTIFICATION_ACCESS)),
        )
        // An overlay switch that stayed off counts too; "Continue" finishes the step.
        val overlay = base.copy(attempted = setOf(OnboardingStep.OVERLAY))
        assertEquals(OnboardingStep.RESTRICTED_SETTINGS, OnboardingFlow.current(overlay, env(granted = granted)))
        assertEquals(
            OnboardingStep.LOCATION,
            OnboardingFlow.current(overlay.copy(done = overlay.done + OnboardingStep.RESTRICTED_SETTINGS), env(granted = granted)),
        )
    }

    @Test
    fun `battery step stays for OEM guidance even when the exemption is granted`() {
        val answers = OnboardingAnswers(
            adultConfirmed = true,
            noticeVersion = OnboardingFlow.NOTICE_VERSION,
            signIn = SignInChoice.MANUAL,
        )
        val all = AppPermission.entries.toSet()
        assertEquals(OnboardingStep.BATTERY, OnboardingFlow.current(answers, env(granted = all, oem = true)))
        assertEquals(OnboardingStep.HEALTH_CHECK, OnboardingFlow.current(answers, env(granted = all, oem = false)))
        assertEquals(
            OnboardingStep.HEALTH_CHECK,
            OnboardingFlow.current(answers.copy(done = setOf(OnboardingStep.BATTERY)), env(granted = all, oem = true)),
        )
    }

    @Test
    fun `a skipped or passed health check finishes onboarding`() {
        val answers = OnboardingAnswers(
            adultConfirmed = true,
            noticeVersion = OnboardingFlow.NOTICE_VERSION,
            signIn = SignInChoice.GOOGLE,
        )
        val all = AppPermission.entries.toSet()
        assertEquals(OnboardingStep.HEALTH_CHECK, OnboardingFlow.current(answers, env(granted = all)))
        assertEquals(OnboardingStep.DONE, OnboardingFlow.current(answers.copy(healthCheck = HealthCheckOutcome.SKIPPED), env(granted = all)))
        assertEquals(OnboardingStep.DONE, OnboardingFlow.current(answers.copy(healthCheck = HealthCheckOutcome.PASSED), env(granted = all)))
    }

    @Test
    fun `positions count the visible steps`() {
        assertEquals(1, OnboardingFlow.position(OnboardingStep.AGE_GATE))
        assertEquals(OnboardingFlow.ORDER.size, OnboardingFlow.position(OnboardingStep.HEALTH_CHECK))
        assertEquals(null, OnboardingFlow.position(OnboardingStep.DONE))
        // A Play-installed phone never sees the restricted-settings guidance, so it is not counted.
        val play = DeviceProfile(sdkInt = 35, release = "15", manufacturer = "Google", sideloaded = false)
        assertEquals(OnboardingFlow.ORDER.size - 1, OnboardingFlow.visibleOrder(play).size)
        assertEquals(6, OnboardingFlow.position(OnboardingStep.OVERLAY, play))
        assertEquals(7, OnboardingFlow.position(OnboardingStep.LOCATION, play))
        assertEquals(null, OnboardingFlow.position(OnboardingStep.RESTRICTED_SETTINGS, play))
        val sideloaded = play.copy(sideloaded = true)
        assertEquals(8, OnboardingFlow.position(OnboardingStep.LOCATION, sideloaded))
        assertEquals(OnboardingFlow.ORDER.size, OnboardingFlow.visibleOrder(sideloaded).size)
        assertFalse(OnboardingFlow.isFinished(OnboardingStep.UNDER_AGE_BLOCKED, OnboardingAnswers(), env()))
    }

    @Test
    fun `OEM families`() {
        assertEquals(OemFamily.XIAOMI, OemGuidance.familyOf("Xiaomi"))
        assertEquals(OemFamily.XIAOMI, OemGuidance.familyOf("POCO"))
        assertEquals(OemFamily.OPPO_REALME, OemGuidance.familyOf("realme"))
        assertEquals(OemFamily.VIVO, OemGuidance.familyOf("vivo"))
        assertEquals(OemFamily.SAMSUNG, OemGuidance.familyOf("samsung"))
        assertEquals(OemFamily.MOTOROLA, OemGuidance.familyOf("motorola"))
        assertEquals(OemFamily.OTHER, OemGuidance.familyOf("Google"))
        assertEquals(null, OemGuidance.stepsRes(OemFamily.OTHER))
        OemFamily.entries.filter { it != OemFamily.OTHER }.forEach { assertTrue(OemGuidance.stepsRes(it) != null) }
    }
}
