package app.ridemate.onboarding

import android.app.Activity
import app.ridemate.auth.AuthGateway
import app.ridemate.auth.AuthState
import app.ridemate.auth.SignInResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Why Google sign-in did not finish; each maps to a message that says what to do next. */
enum class SignInProblem { CANCELLED, NO_GOOGLE_ACCOUNT, OFFLINE, FAILED }

sealed interface SignInStatus {
    data object Idle : SignInStatus
    data object InProgress : SignInStatus
    data class Problem(val problem: SignInProblem) : SignInStatus
}

sealed interface HealthStatus {
    data object Idle : HealthStatus
    data object Running : HealthStatus
    data class Finished(val result: HealthResult) : HealthStatus
}

/** Everything an onboarding screen renders. */
data class OnboardingUiState(
    val step: OnboardingStep,
    val answers: OnboardingAnswers,
    val granted: Set<AppPermission>,
    val device: DeviceProfile,
    val oemFamily: OemFamily,
    val restrictedSettingsLikely: Boolean,
    val signIn: SignInStatus,
    val health: HealthStatus,
) {
    /** "Step n of N" (null on the block and the end screen). */
    val position: Int? get() = OnboardingFlow.position(step)
    val total: Int get() = OnboardingFlow.ORDER.size

    fun isGranted(permission: AppPermission): Boolean = permission in granted
}

/**
 * Drives onboarding (architecture §4.2): the current step comes from [OnboardingFlow] over the persisted answers,
 * the live permission state and the sign-in state, so onboarding resumes where it stopped and steps that are
 * already granted are skipped. Call [refresh] whenever the user may have changed a permission (on resume, after a
 * permission prompt).
 */
class OnboardingController(
    private val store: OnboardingStore,
    private val permissions: PermissionChecker,
    private val device: DeviceProfile,
    private val auth: AuthGateway,
    private val healthCheck: HealthCheck,
    private val scope: CoroutineScope,
) {
    private val granted = MutableStateFlow(permissions.granted())
    private val signIn = MutableStateFlow<SignInStatus>(SignInStatus.Idle)
    private val health = MutableStateFlow<HealthStatus>(HealthStatus.Idle)
    private val oemFamily = OemGuidance.familyOf(device.manufacturer)

    val state: StateFlow<OnboardingUiState> =
        combine(store.answers, granted, auth.state, signIn, health) { answers, granted, authState, signIn, health ->
            compute(answers, granted, authState, signIn, health)
        }.stateIn(scope, SharingStarted.Eagerly, snapshot())

    /** The state computed right now from the sources (no waiting for [state] to update). */
    fun snapshot(): OnboardingUiState = compute(store.answers.value, granted.value, auth.state.value, signIn.value, health.value)

    private fun compute(
        answers: OnboardingAnswers,
        granted: Set<AppPermission>,
        authState: AuthState,
        signIn: SignInStatus,
        health: HealthStatus,
    ): OnboardingUiState {
        val env = OnboardingEnvironment(
            granted = granted,
            signedInWithGoogle = authState is AuthState.Google,
            device = device,
            hasOemGuidance = OemGuidance.stepsRes(oemFamily) != null,
        )
        return OnboardingUiState(
            step = OnboardingFlow.current(answers, env),
            answers = answers,
            granted = granted,
            device = device,
            oemFamily = oemFamily,
            restrictedSettingsLikely = OnboardingFlow.restrictedSettingsLikely(answers, env),
            signIn = signIn,
            health = health,
        )
    }

    /** Re-reads every permission (call on resume and after a permission prompt). */
    fun refresh() {
        granted.value = permissions.granted()
    }

    fun confirmAdult() = store.confirmAdult()

    fun declareUnderAge() = store.declareUnderAge()

    fun acceptNotice() = store.acceptNotice(OnboardingFlow.NOTICE_VERSION)

    /** Shows Google's account sheet; success finishes the step, any other outcome stays with a reason. */
    fun signInWithGoogle(activity: Activity): Job = scope.launch {
        signIn.value = SignInStatus.InProgress
        val result = try {
            auth.signInWithGoogle(activity)
        } catch (e: CancellationException) {
            signIn.value = SignInStatus.Idle
            throw e
        } catch (e: Exception) {
            SignInResult.Failed
        }
        signIn.value = when (result) {
            is SignInResult.Success -> {
                store.chooseSignIn(SignInChoice.GOOGLE)
                SignInStatus.Idle
            }
            SignInResult.Cancelled -> SignInStatus.Problem(SignInProblem.CANCELLED)
            SignInResult.NoGoogleAccount -> SignInStatus.Problem(SignInProblem.NO_GOOGLE_ACCOUNT)
            SignInResult.NetworkError -> SignInStatus.Problem(SignInProblem.OFFLINE)
            SignInResult.Failed -> SignInStatus.Problem(SignInProblem.FAILED)
        }
    }

    /**
     * Manual mode: the user types prices; RideMate offers Google sign-in again at the first "Read fares". An
     * anonymous sign-in runs in the background for the non-AI endpoints; its failure changes nothing.
     */
    fun continueWithoutGoogle() {
        store.chooseSignIn(SignInChoice.MANUAL)
        signIn.value = SignInStatus.Idle
        scope.launch {
            try {
                auth.signInAnonymously()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Manual mode works signed out too.
            }
        }
    }

    /** The user opened [step]'s system screen or prompt. */
    fun opened(step: OnboardingStep) = store.markAttempted(step)

    /** "Not now" on a permission step, or "Continue" on a guidance step. */
    fun finish(step: OnboardingStep) = store.markDone(step)

    fun runHealthCheck(): Job = scope.launch {
        refresh()
        health.value = HealthStatus.Running
        val result = healthCheck.run()
        health.value = HealthStatus.Finished(result)
        if (result == HealthResult.SEEN) store.setHealthCheck(HealthCheckOutcome.PASSED)
    }

    /** "Continue anyway" after a failed health check (the pre-session check will ask again). */
    fun skipHealthCheck() = store.setHealthCheck(HealthCheckOutcome.SKIPPED)

    /** The done screen's button: onboarding never shows again (until Erase). */
    fun complete() = store.markCompleted()
}

/**
 * "Offered again at the first Read fares" (architecture §4.2): TripInput / the bubble asks [shouldOfferAtFirstRead]
 * before the first read and, after showing the offer (route `signin`), calls [markOffered]. It is offered once.
 */
class SignInReoffer(private val store: OnboardingStore, private val auth: StateFlow<AuthState>) {
    fun shouldOfferAtFirstRead(): Boolean {
        val answers = store.answers.value
        return answers.signIn == SignInChoice.MANUAL && !answers.signInReoffered && auth.value !is AuthState.Google
    }

    fun markOffered() = store.markSignInReoffered()
}
