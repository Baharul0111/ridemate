package app.ridemate.dev

import android.app.Activity
import app.ridemate.data.RecorderPostEntity
import app.ridemate.net.ApiClient
import app.ridemate.net.ApiResult
import app.ridemate.net.ConfigCheckResponse
import app.ridemate.util.BuildInfo
import app.ridemate.util.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

sealed interface CaptureTestStatus {
    data object Idle : CaptureTestStatus
    data object Running : CaptureTestStatus
    data class Finished(val result: CaptureTestResult) : CaptureTestStatus
}

sealed interface BudgetStatus {
    data object Idle : BudgetStatus
    data object Loading : BudgetStatus
    data class Loaded(val check: ConfigCheckResponse) : BudgetStatus
    data class Failed(val result: ApiResult<*>) : BudgetStatus
}

/** Owner alert registration (FCM token → `/v1/owner/fcm-token`). */
sealed interface OwnerAlertsStatus {
    data object Idle : OwnerAlertsStatus
    data object Working : OwnerAlertsStatus
    data object Registered : OwnerAlertsStatus
    data object Off : OwnerAlertsStatus
    data object NoToken : OwnerAlertsStatus
    data class Failed(val result: ApiResult<*>) : OwnerAlertsStatus
}

/** One-line outcomes of developer actions. */
enum class DevMessage {
    RECORDER_EMPTY,
    RECORDER_CLEARED,
    GOLDEN_SAVED,
    GOLDEN_NOTHING_TO_EXPORT,
    GOLDEN_DENIED,
    GOLDEN_BLACK_FRAME,
    GOLDEN_UNAVAILABLE,
    GOLDEN_CAPTURED,
    EXPORT_FAILED,

    /** Reading or writing the phone's storage failed (full, or removed); nothing changed. */
    STORAGE_FAILED,
}

data class DevState(
    val enabled: Boolean,
    val recorderOn: Boolean,
    val recent: List<RecorderPostEntity>,
    val captureTest: CaptureTestStatus,
    val golden: List<GoldenScreen>,
    val budget: BudgetStatus,
    val ownerAlertsOn: Boolean,
    val ownerAlerts: OwnerAlertsStatus,
    val message: DevMessage?,
)

/**
 * Developer mode (architecture §4.19): the Recorder (masked posts, JSON export), the capture test, golden captures
 * with labels (contracts §6 export), the owner budget view (`/v1/config-check` budget block) and owner alerts.
 * Exports are written to app-private files and handed to [onShare]-style callbacks for the share sheet; nothing is
 * uploaded.
 */
class DevModel(
    private val gate: DeveloperModeGate,
    recorderRecent: Flow<List<RecorderPostEntity>>,
    private val recorderAll: suspend () -> List<RecorderPostEntity>,
    private val recorderClear: suspend () -> Unit,
    private val golden: GoldenStore,
    private val writeText: (name: String, text: String) -> File,
    private val exportFile: (name: String) -> File,
    private val api: ApiClient,
    private val pushTokens: OwnerPushTokens,
    private val clock: Clock,
    private val build: BuildInfo,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope,
) {
    private val captureTest = MutableStateFlow<CaptureTestStatus>(CaptureTestStatus.Idle)
    private val goldenScreens = MutableStateFlow(golden.screens())
    private val budget = MutableStateFlow<BudgetStatus>(BudgetStatus.Idle)
    private val ownerAlerts = MutableStateFlow<OwnerAlertsStatus>(OwnerAlertsStatus.Idle)
    private val message = MutableStateFlow<DevMessage?>(null)

    private val recent = recorderRecent.onStart { emit(emptyList()) }.catch { emit(emptyList()) }

    private val switches = combine(gate.enabled, gate.recorderSwitch, gate.ownerAlertsSwitch) { e, r, o -> Triple(e, r, o) }

    val state: StateFlow<DevState> = combine(
        switches,
        recent,
        combine(captureTest, goldenScreens) { c, g -> c to g },
        combine(budget, ownerAlerts) { b, o -> b to o },
        message,
    ) { (enabled, recorderOn, ownerOn), rows, (capture, screens), (budgetStatus, owner), msg ->
        DevState(enabled, recorderOn, rows, capture, screens, budgetStatus, ownerOn, owner, msg)
    }.stateIn(scope, SharingStarted.Eagerly, initial())

    private fun initial() = DevState(
        enabled = gate.isEnabled(),
        recorderOn = gate.recorderSwitch.value,
        recent = emptyList(),
        captureTest = CaptureTestStatus.Idle,
        golden = goldenScreens.value,
        budget = BudgetStatus.Idle,
        ownerAlertsOn = gate.ownerAlertsSwitch.value,
        ownerAlerts = OwnerAlertsStatus.Idle,
        message = null,
    )

    fun setRecorderOn(on: Boolean) = gate.setRecorderOn(on)

    fun disableDeveloperMode() = gate.disable()

    fun clearMessage() {
        message.value = null
    }

    /** Writes the Recorder JSON (masked) and hands the file to [onReady]; nothing when the log is empty. */
    fun exportRecorder(onReady: (File) -> Unit): Job = scope.launch {
        val file = try {
            withContext(io) {
                val rows = recorderAll()
                if (rows.isEmpty()) return@withContext null
                val doc = RecorderExport.build(rows, clock.wallMs(), build.versionCode, build.versionName)
                writeText("ridemate-recorder-${stamp()}.json", RecorderExport.toJson(doc))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message.value = DevMessage.EXPORT_FAILED
            return@launch
        }
        if (file == null) message.value = DevMessage.RECORDER_EMPTY else onReady(file)
    }

    fun clearRecorder(): Job = scope.launch {
        message.value = if (storage { withContext(io) { recorderClear() } }) DevMessage.RECORDER_CLEARED else DevMessage.STORAGE_FAILED
    }

    fun runCaptureTest(activity: Activity, tester: CaptureTester): Job = scope.launch {
        captureTest.value = CaptureTestStatus.Running
        val result = try {
            tester.runCaptureTest(activity)
        } catch (e: CancellationException) {
            captureTest.value = CaptureTestStatus.Idle
            throw e
        } catch (e: Exception) {
            CaptureTestResult.Unavailable
        }
        captureTest.value = CaptureTestStatus.Finished(result)
    }

    fun captureGolden(activity: Activity, source: GoldenCaptureSource): Job = scope.launch {
        val result = try {
            source.captureMasked(activity)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            GoldenCaptureResult.Unavailable
        }
        message.value = when (result) {
            is GoldenCaptureResult.Captured -> {
                val saved = storage { withContext(io) { golden.add(result.capture) } }
                goldenScreens.value = golden.screens()
                if (saved) DevMessage.GOLDEN_CAPTURED else DevMessage.STORAGE_FAILED
            }
            GoldenCaptureResult.Denied -> DevMessage.GOLDEN_DENIED
            GoldenCaptureResult.BlackFrame -> DevMessage.GOLDEN_BLACK_FRAME
            GoldenCaptureResult.Unavailable -> DevMessage.GOLDEN_UNAVAILABLE
        }
    }

    fun saveGolden(screen: GoldenScreen): Job = scope.launch {
        val saved = storage { withContext(io) { golden.update(screen) } }
        goldenScreens.value = golden.screens()
        message.value = if (saved) DevMessage.GOLDEN_SAVED else DevMessage.STORAGE_FAILED
    }

    fun removeGolden(sha256: String): Job = scope.launch {
        val removed = storage { withContext(io) { golden.remove(sha256) } }
        goldenScreens.value = golden.screens()
        if (!removed) message.value = DevMessage.STORAGE_FAILED
    }

    /** Runs a storage [block]; false (instead of a crash) when the phone's storage fails. */
    private suspend fun storage(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /** Zips complete, labelled screens (contracts §6) and hands the file to [onReady]. */
    fun exportGolden(onReady: (File) -> Unit): Job = scope.launch {
        val file = try {
            withContext(io) {
                val out = exportFile("ridemate-golden-${stamp()}.zip")
                if (golden.exportZip(out) == 0) null else out
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message.value = DevMessage.EXPORT_FAILED
            return@launch
        }
        if (file == null) message.value = DevMessage.GOLDEN_NOTHING_TO_EXPORT else onReady(file)
    }

    /** The owner budget block of `/v1/config-check` (L1: "the remaining budget is shown in developer mode"). */
    fun loadBudget(): Job = scope.launch {
        budget.value = BudgetStatus.Loading
        budget.value = when (val r = safe { api.configCheck() }) {
            is ApiResult.Ok -> BudgetStatus.Loaded(r.body)
            else -> BudgetStatus.Failed(r)
        }
    }

    /**
     * Owner alerts on: fetch this phone's FCM token and register it; off: delete the token so the server's copy stops
     * working. Firebase calls get [PUSH_TOKEN_TIMEOUT_MS] (they can wait forever without Play services or network).
     */
    fun setOwnerAlerts(on: Boolean): Job = scope.launch {
        ownerAlerts.value = OwnerAlertsStatus.Working
        if (!on) {
            bounded { pushTokens.delete() }
            gate.setOwnerAlertsOn(false)
            ownerAlerts.value = OwnerAlertsStatus.Off
            return@launch
        }
        val token = bounded { pushTokens.token() }
        if (token.isNullOrBlank()) {
            ownerAlerts.value = OwnerAlertsStatus.NoToken
            return@launch
        }
        ownerAlerts.value = when (val r = safe { api.registerOwnerFcmToken(token) }) {
            is ApiResult.Ok -> {
                gate.setOwnerAlertsOn(true)
                OwnerAlertsStatus.Registered
            }
            else -> OwnerAlertsStatus.Failed(r)
        }
    }

    private suspend fun <T> bounded(block: suspend () -> T): T? = try {
        withTimeoutOrNull(PUSH_TOKEN_TIMEOUT_MS) { block() }
    } catch (e: TimeoutCancellationException) {
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun <T> safe(block: suspend () -> ApiResult<T>): ApiResult<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ApiResult.Offline
    }

    private fun stamp(): String = STAMP.format(Instant.ofEpochMilli(clock.wallMs()))

    companion object {
        /** Ceiling for each Firebase Messaging call (token / delete). */
        const val PUSH_TOKEN_TIMEOUT_MS = 10_000L

        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
    }
}
