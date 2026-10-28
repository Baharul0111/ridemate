package app.ridemate.onboarding

import app.ridemate.ui.KeyValueStore
import app.ridemate.ui.getBoolean
import app.ridemate.ui.getEnum
import app.ridemate.ui.getEnumSet
import app.ridemate.ui.putBoolean
import app.ridemate.ui.putEnumSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the user chose at the Google sign-in step. */
enum class SignInChoice { GOOGLE, MANUAL }

/** How the onboarding health check ended. */
enum class HealthCheckOutcome { PASSED, SKIPPED }

/**
 * The user's onboarding answers, persisted so onboarding resumes where it stopped (architecture §4.2).
 *
 * @property done steps the user finished or chose "Not now" on (permission steps are also done once granted).
 * @property attempted steps whose system screen the user opened at least once (drives the restricted-settings
 *   guidance: a toggle that stays off after a visit on a sideloaded Android 13+ phone is most likely blocked).
 */
data class OnboardingAnswers(
    val adultConfirmed: Boolean = false,
    val underAge: Boolean = false,
    val noticeVersion: String? = null,
    val signIn: SignInChoice? = null,
    val done: Set<OnboardingStep> = emptySet(),
    val attempted: Set<OnboardingStep> = emptySet(),
    val healthCheck: HealthCheckOutcome? = null,
    val completed: Boolean = false,
    /** Sign-in was offered again at the first "Read fares" (it is offered only once more). */
    val signInReoffered: Boolean = false,
)

/** Persists [OnboardingAnswers]; every write updates [answers] synchronously. Erase wipes it ([clear]). */
class OnboardingStore(private val kv: KeyValueStore) {
    private val _answers = MutableStateFlow(read())
    val answers: StateFlow<OnboardingAnswers> = _answers.asStateFlow()

    private fun read() = OnboardingAnswers(
        adultConfirmed = kv.getBoolean(K_ADULT),
        underAge = kv.getBoolean(K_UNDER_AGE),
        noticeVersion = kv.get(K_NOTICE),
        signIn = kv.getEnum<SignInChoice>(K_SIGN_IN),
        done = kv.getEnumSet(K_DONE),
        attempted = kv.getEnumSet(K_ATTEMPTED),
        healthCheck = kv.getEnum<HealthCheckOutcome>(K_HEALTH),
        completed = kv.getBoolean(K_COMPLETED),
        signInReoffered = kv.getBoolean(K_REOFFERED),
    )

    @Synchronized
    private fun write(block: KeyValueStore.() -> Unit) {
        kv.block()
        _answers.value = read()
    }

    fun confirmAdult() = write { putBoolean(K_ADULT, true); putBoolean(K_UNDER_AGE, false) }

    /** The user said they are under 18: RideMate stays blocked (DPDP, Muse Spark Open licence). */
    fun declareUnderAge() = write { putBoolean(K_UNDER_AGE, true); putBoolean(K_ADULT, false) }

    fun acceptNotice(version: String) = write { put(K_NOTICE, version) }

    fun chooseSignIn(choice: SignInChoice) = write { put(K_SIGN_IN, choice.name) }

    fun markDone(step: OnboardingStep) = write { putEnumSet(K_DONE, answers.value.done + step) }

    fun markAttempted(step: OnboardingStep) = write { putEnumSet(K_ATTEMPTED, answers.value.attempted + step) }

    fun setHealthCheck(outcome: HealthCheckOutcome) = write { put(K_HEALTH, outcome.name) }

    fun markCompleted() = write { putBoolean(K_COMPLETED, true) }

    fun markSignInReoffered() = write { putBoolean(K_REOFFERED, true) }

    /** Erase: forget every answer, so the next start shows onboarding from the 18+ check. */
    fun clear() = write { clear() }

    private companion object {
        const val K_ADULT = "adult_confirmed"
        const val K_UNDER_AGE = "under_age"
        const val K_NOTICE = "notice_version"
        const val K_SIGN_IN = "sign_in"
        const val K_DONE = "done_steps"
        const val K_ATTEMPTED = "attempted_steps"
        const val K_HEALTH = "health_check"
        const val K_COMPLETED = "completed"
        const val K_REOFFERED = "sign_in_reoffered"
    }
}
