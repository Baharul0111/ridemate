package app.ridemate.onboarding

/** The onboarding screens, in architecture §4.2 order (plus the under-18 block and the end). */
enum class OnboardingStep {
    AGE_GATE,
    UNDER_AGE_BLOCKED,
    PRIVACY_NOTICE,
    SIGN_IN,
    NOTIFICATIONS,
    NOTIFICATION_ACCESS,
    OVERLAY,
    RESTRICTED_SETTINGS,
    LOCATION,
    BATTERY,
    HEALTH_CHECK,
    DONE,
}

/** What the flow needs to know about the phone right now. */
data class OnboardingEnvironment(
    val granted: Set<AppPermission>,
    val signedInWithGoogle: Boolean,
    val device: DeviceProfile,
    /** Whether the battery step has maker-specific autostart guidance to show (Xiaomi, Oppo/Realme/Vivo, …). */
    val hasOemGuidance: Boolean,
)

/**
 * The onboarding order and when each step counts as finished (pure, unit-tested as a table):
 *
 * 18+ → privacy notice → Google sign-in (or manual mode) → notifications → notification access → display over
 * other apps → "Allow restricted settings" (sideloaded Android 13+ only, when a switch stayed blocked) → location
 * → battery + OEM autostart → health check → done.
 *
 * A permission step that is already granted is finished without being shown (auto-advance); "Not now" finishes it
 * too, and the pre-session check asks again before a comparison.
 */
object OnboardingFlow {
    /** The privacy notice version the user must accept (docs/privacy-notice.md "Version 1.0"). */
    const val NOTICE_VERSION = "1.0"

    val ORDER: List<OnboardingStep> = listOf(
        OnboardingStep.AGE_GATE,
        OnboardingStep.PRIVACY_NOTICE,
        OnboardingStep.SIGN_IN,
        OnboardingStep.NOTIFICATIONS,
        OnboardingStep.NOTIFICATION_ACCESS,
        OnboardingStep.OVERLAY,
        OnboardingStep.RESTRICTED_SETTINGS,
        OnboardingStep.LOCATION,
        OnboardingStep.BATTERY,
        OnboardingStep.HEALTH_CHECK,
    )

    /** The permission each step asks for (null: not a permission step). */
    fun permissionOf(step: OnboardingStep): AppPermission? = when (step) {
        OnboardingStep.NOTIFICATIONS -> AppPermission.POST_NOTIFICATIONS
        OnboardingStep.NOTIFICATION_ACCESS -> AppPermission.NOTIFICATION_ACCESS
        OnboardingStep.OVERLAY -> AppPermission.OVERLAY
        OnboardingStep.LOCATION -> AppPermission.LOCATION
        OnboardingStep.BATTERY -> AppPermission.BATTERY_EXEMPTION
        else -> null
    }

    /** The screen to show now. */
    fun current(answers: OnboardingAnswers, env: OnboardingEnvironment): OnboardingStep {
        if (answers.underAge) return OnboardingStep.UNDER_AGE_BLOCKED
        return ORDER.firstOrNull { !isFinished(it, answers, env) } ?: OnboardingStep.DONE
    }

    /**
     * Whether a sideloaded Android 13+ phone most likely blocks the listener or overlay switch: the user opened
     * that system screen and the permission is still off.
     */
    fun restrictedSettingsLikely(answers: OnboardingAnswers, env: OnboardingEnvironment): Boolean {
        if (!env.device.restrictedSettingsPossible) return false
        val listenerBlocked = OnboardingStep.NOTIFICATION_ACCESS in answers.attempted && AppPermission.NOTIFICATION_ACCESS !in env.granted
        val overlayBlocked = OnboardingStep.OVERLAY in answers.attempted && AppPermission.OVERLAY !in env.granted
        return listenerBlocked || overlayBlocked
    }

    fun isFinished(step: OnboardingStep, answers: OnboardingAnswers, env: OnboardingEnvironment): Boolean = when (step) {
        OnboardingStep.AGE_GATE -> answers.adultConfirmed
        OnboardingStep.UNDER_AGE_BLOCKED -> false
        OnboardingStep.PRIVACY_NOTICE -> answers.noticeVersion == NOTICE_VERSION
        OnboardingStep.SIGN_IN -> env.signedInWithGoogle || answers.signIn != null
        OnboardingStep.NOTIFICATIONS,
        OnboardingStep.NOTIFICATION_ACCESS,
        OnboardingStep.OVERLAY,
        OnboardingStep.LOCATION,
        -> step in answers.done || permissionOf(step) in env.granted
        OnboardingStep.RESTRICTED_SETTINGS -> step in answers.done || !restrictedSettingsLikely(answers, env)
        // Granted exemption alone finishes the step only where the maker adds no autostart setting of its own.
        OnboardingStep.BATTERY -> step in answers.done || (AppPermission.BATTERY_EXEMPTION in env.granted && !env.hasOemGuidance)
        OnboardingStep.HEALTH_CHECK -> answers.healthCheck != null
        OnboardingStep.DONE -> true
    }

    /** 1-based position among the visible steps, for "Step 3 of 10"; null for the block and the end. */
    fun position(step: OnboardingStep): Int? = ORDER.indexOf(step).takeIf { it >= 0 }?.plus(1)
}
