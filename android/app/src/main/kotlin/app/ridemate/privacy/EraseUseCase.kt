package app.ridemate.privacy

import app.ridemate.auth.AuthGateway
import app.ridemate.auth.AuthState
import app.ridemate.auth.DeleteAccountResult
import app.ridemate.crash.CrashReporter
import app.ridemate.crash.CrashSite
import app.ridemate.data.OutboxKind
import app.ridemate.net.ApiClient
import app.ridemate.net.ApiResult
import app.ridemate.outbox.OutboxDriver
import app.ridemate.outbox.OutboxHandler
import app.ridemate.outbox.OutboxItem
import app.ridemate.outbox.OutboxOutcome
import app.ridemate.outbox.OutboxRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Something else on the phone that Erase must wipe (recent places, onboarding answers, Recorder exports, …). */
fun interface LocalDataWiper {
    suspend fun wipe()
}

/** Server part of an erase. */
enum class ServerErase {
    /** `/v1/erase` succeeded. */
    DONE,

    /** The server was unreachable: an ERASE_SERVER outbox row retries it; the account is deleted after that. */
    QUEUED,

    /** Nothing to erase on the server (signed out, anonymous, or no server configured). */
    SKIPPED,

    /** The server refused (e.g. the token is no longer valid): handled by the manual erasure runbook. */
    FAILED,
}

/** Firebase Auth account part of an erase. */
enum class AccountErase { DELETED, WAITING_FOR_SERVER, REAUTH_REQUIRED, FAILED, SKIPPED }

data class EraseResult(
    val server: ServerErase,
    val account: AccountErase,
    val signedOut: Boolean,
)

/** For the Settings screen. */
sealed interface EraseStatus {
    data object Idle : EraseStatus
    data object Running : EraseStatus

    /** Local data is gone; the server erase (then account deletion) is queued until the server answers. */
    data object WaitingForServer : EraseStatus

    /** Sign in with Google again, then erase again, to finish deleting the account. */
    data object NeedsReauth : EraseStatus
    data class Finished(val result: EraseResult) : EraseStatus
}

/**
 * Erase (§4.11): wipe Room (every table) and other local stores → `/v1/erase` (queued as an ERASE_SERVER outbox row
 * and retried if the server is down) → `FirebaseUser.delete()` → Crashlytics `setUserId("")` +
 * `deleteUnsentReports()` → sign out.
 *
 * The account is deleted only after the server erase succeeded (or had nothing to do), because the retry needs
 * the account's token. The queued row remembers the uid and is dropped if another account is signed in by then.
 */
class EraseUseCase(
    private val wipeDatabase: suspend () -> Unit,
    private val outbox: OutboxRepository,
    private val api: ApiClient,
    private val auth: AuthGateway,
    private val crash: CrashReporter,
    private val extraWipers: () -> List<LocalDataWiper> = { emptyList() },
) : OutboxHandler {

    @Serializable
    private data class ErasePayload(val uid: String)

    private val mutex = Mutex()
    private val _status = MutableStateFlow<EraseStatus>(EraseStatus.Idle)
    val status: StateFlow<EraseStatus> = _status.asStateFlow()

    /** Registers this use case as the ERASE_SERVER handler. */
    fun registerWith(driver: OutboxDriver): AutoCloseable = driver.register(setOf(OutboxKind.ERASE_SERVER), this)

    suspend fun erase(): EraseResult = mutex.withLock {
        _status.value = EraseStatus.Running
        val state = auth.state.value
        val uid = auth.currentUid()

        wipeLocal()

        val server = when (state) {
            is AuthState.Google -> when (val r = api.erase()) {
                is ApiResult.Ok -> ServerErase.DONE
                ApiResult.NotConfigured -> ServerErase.SKIPPED
                else -> if (isTransient(r) && uid != null) {
                    outbox.schedule(OutboxKind.ERASE_SERVER, legId = null, delayMs = FIRST_RETRY_DELAY_MS, payload = payloadFor(uid))
                    ServerErase.QUEUED
                } else {
                    ServerErase.FAILED
                }
            }
            else -> ServerErase.SKIPPED
        }

        crash.clearUserAndUnsentReports()

        val result = if (server == ServerErase.QUEUED) {
            EraseResult(server, AccountErase.WAITING_FOR_SERVER, signedOut = false)
        } else {
            finishAccount(server, signedIn = state != AuthState.SignedOut)
        }
        _status.value = statusOf(result)
        result
    }

    /** ERASE_SERVER delivery (outbox). */
    override suspend fun handle(item: OutboxItem): OutboxOutcome = mutex.withLock {
        val uid = item.payload?.let { runCatching { PayloadJson.decodeFromString(ErasePayload.serializer(), it).uid }.getOrNull() }
        if (uid == null || auth.currentUid() != uid) return@withLock OutboxOutcome.Dead
        when (val r = api.erase()) {
            is ApiResult.Ok -> {
                val result = finishAccount(ServerErase.DONE, signedIn = true)
                _status.value = statusOf(result)
                if (result.account == AccountErase.FAILED) OutboxOutcome.Retry(OutboxDriver.backoffMs(item.attempts)) else OutboxOutcome.Done
            }
            ApiResult.NotConfigured -> OutboxOutcome.Retry(OutboxDriver.backoffMs(item.attempts))
            else -> if (isTransient(r)) {
                OutboxOutcome.Retry(OutboxDriver.backoffMs(item.attempts))
            } else {
                _status.value = EraseStatus.Finished(EraseResult(ServerErase.FAILED, AccountErase.SKIPPED, signedOut = false))
                OutboxOutcome.Dead
            }
        }
    }

    private suspend fun wipeLocal() {
        wipeDatabase()
        outbox.clearMemory()
        for (wiper in extraWipers()) {
            try {
                wiper.wipe()
            } catch (e: Exception) {
                crash.recordNonFatal(e, CrashSite.ERASE)
            }
        }
    }

    private suspend fun finishAccount(server: ServerErase, signedIn: Boolean): EraseResult {
        if (!signedIn) return EraseResult(server, AccountErase.SKIPPED, signedOut = true)
        val account = when (auth.deleteAccount()) {
            DeleteAccountResult.Deleted -> AccountErase.DELETED
            DeleteAccountResult.SignedOut -> AccountErase.SKIPPED
            DeleteAccountResult.ReauthRequired -> AccountErase.REAUTH_REQUIRED
            DeleteAccountResult.NetworkError, DeleteAccountResult.Failed -> AccountErase.FAILED
        }
        crash.clearUserAndUnsentReports()
        // Keep the session when Firebase needs a fresh sign-in, so "sign in again" can finish the deletion.
        val signOut = account != AccountErase.REAUTH_REQUIRED
        if (signOut) auth.signOut()
        return EraseResult(server, account, signedOut = signOut)
    }

    private fun statusOf(result: EraseResult): EraseStatus = when (result.account) {
        AccountErase.WAITING_FOR_SERVER -> EraseStatus.WaitingForServer
        AccountErase.REAUTH_REQUIRED -> EraseStatus.NeedsReauth
        else -> EraseStatus.Finished(result)
    }

    private fun payloadFor(uid: String) = PayloadJson.encodeToString(ErasePayload.serializer(), ErasePayload(uid))

    companion object {
        /** The first retry of a queued server erase (then the outbox backoff: 30 s, 60 s, … up to 1 h). */
        val FIRST_RETRY_DELAY_MS: Long = OutboxDriver.backoffMs(0)

        private val PayloadJson = Json { ignoreUnknownKeys = true }

        /** Failures worth retrying later: the server or the network was unavailable. */
        private fun isTransient(result: ApiResult<*>): Boolean = when (result) {
            ApiResult.Offline, ApiResult.Timeout, is ApiResult.RateLimited, is ApiResult.AiPaused -> true
            is ApiResult.Http -> result.code >= 500
            else -> false
        }
    }
}
