package app.ridemate.home

import app.ridemate.auth.AuthState
import app.ridemate.config.KillSwitches
import app.ridemate.core.model.Provider
import app.ridemate.data.SessionEntity
import app.ridemate.net.ApiClient
import app.ridemate.net.ApiResult
import app.ridemate.net.ConfigCheckResponse
import app.ridemate.update.UpdateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The active ride session shown on Home (tap → guardian). */
data class ActiveSessionSummary(
    val sessionId: String,
    val dropLabel: String,
    val apps: List<Provider>,
    val startedAtUtc: Long,
) {
    companion object {
        fun of(session: SessionEntity) = ActiveSessionSummary(session.id, session.drop.label, session.selectedApps, session.createdAtUtc)
    }
}

/** The pre-session check behind the Compare button. */
sealed interface CompareCheck {
    data object Idle : CompareCheck
    data object Checking : CompareCheck

    /** Compare cannot start until these are fixed (fix-it cards). */
    data class Blocked(val report: PreSessionReport) : CompareCheck

    /** Compare can start, but these are worth fixing first ("Continue anyway"). */
    data class Warnings(val report: PreSessionReport) : CompareCheck
}

data class HomeState(
    val ai: AiStatus,
    val killSwitches: KillSwitches,
    val update: UpdateState,
    val activeSession: ActiveSessionSummary?,
    val compare: CompareCheck,
    val signedInWithGoogle: Boolean,
)

/**
 * Home (goal 2 entry, 19 entry, 21–23, 28): Compare (after the pre-session check), Try the demo, the active session,
 * the update banner, the AI status line and the kill-switch status. `/v1/config-check` is asked once when Home
 * opens, again after each sign-in change and on [refreshAi].
 */
class HomeModel(
    private val auth: StateFlow<AuthState>,
    private val killSwitches: StateFlow<KillSwitches>,
    updates: StateFlow<UpdateState>,
    activeSessions: Flow<List<SessionEntity>>,
    private val api: ApiClient,
    private val gate: () -> PreSessionGate,
    private val scope: CoroutineScope,
) {
    private val check = MutableStateFlow<ApiResult<ConfigCheckResponse>?>(null)
    private val compare = MutableStateFlow<CompareCheck>(CompareCheck.Idle)

    private val session: Flow<ActiveSessionSummary?> = activeSessions
        .map { list -> list.firstOrNull()?.let(ActiveSessionSummary::of) }
        .onStart { emit(null) }
        .catch { emit(null) }

    private val base = combine(auth, killSwitches, updates, session, check) { a, k, u, s, c ->
        HomeState(AiStatus.of(a, k, c), k, u, s, CompareCheck.Idle, a is AuthState.Google)
    }

    val state: StateFlow<HomeState> = combine(base, compare) { b, cmp -> b.copy(compare = cmp) }
        .stateIn(scope, SharingStarted.Eagerly, initial(updates.value))

    private var aiJob: Job? = null

    init {
        refreshAi()
        scope.launch {
            // A sign-in change (or the kill switch) changes what the server would say.
            combine(auth.map { it is AuthState.Google }, killSwitches.map { it.aiReaderEnabled }) { g, k -> g to k }
                .distinctUntilChanged()
                .drop(1)
                .collect { refreshAi() }
        }
    }

    private fun initial(update: UpdateState) = HomeState(
        ai = AiStatus.of(auth.value, killSwitches.value, null),
        killSwitches = killSwitches.value,
        update = update,
        activeSession = null,
        compare = CompareCheck.Idle,
        signedInWithGoogle = auth.value is AuthState.Google,
    )

    /** Asks `/v1/config-check` (only when signed in with Google and AI reading is switched on). */
    fun refreshAi(): Job {
        aiJob?.cancel()
        check.value = null
        return scope.launch {
            if (!killSwitches.value.aiReaderEnabled || auth.value !is AuthState.Google) return@launch
            check.value = try {
                api.configCheck()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ApiResult.Offline
            }
        }.also { aiJob = it }
    }

    /** The Compare tap: runs the pre-session check, then [onReady] when nothing needs attention. */
    fun compare(onReady: () -> Unit): Job = scope.launch {
        compare.value = CompareCheck.Checking
        val report = try {
            gate().check()
        } catch (e: CancellationException) {
            compare.value = CompareCheck.Idle
            throw e
        } catch (e: Exception) {
            PreSessionReport(blocking = listOf(PreSessionIssue.LISTENER_OFF), warnings = emptyList())
        }
        compare.value = when {
            report.clean -> CompareCheck.Idle
            report.canStart -> CompareCheck.Warnings(report)
            else -> CompareCheck.Blocked(report)
        }
        if (report.clean) onReady()
    }

    /** "Continue anyway" on warnings. */
    fun continueAnyway(onReady: () -> Unit) {
        if (compare.value is CompareCheck.Warnings) {
            compare.value = CompareCheck.Idle
            onReady()
        }
    }

    fun dismissCompareCheck() {
        compare.value = CompareCheck.Idle
    }
}
