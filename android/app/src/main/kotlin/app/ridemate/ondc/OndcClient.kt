package app.ridemate.ondc

import app.ridemate.core.gate.GatedRow
import app.ridemate.core.guardian.GuardianEffect
import app.ridemate.core.guardian.OndcCommandKind
import app.ridemate.core.model.LatLng
import app.ridemate.core.state.Protocol
import app.ridemate.core.state.TapId
import app.ridemate.net.ApiClient
import app.ridemate.net.ApiResult
import app.ridemate.util.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Delivers ONDC protocol data to the guardian (`Guardian.onProtocolEvent(legId, event)`); bound at integration. */
fun interface OndcEventSink {
    fun onProtocol(legId: String, event: Protocol)
}

enum class OndcPhase {
    IDLE,
    SEARCHING,
    QUOTES,

    /** Searched for [OndcClient.SEARCH_TIMEOUT_MS] without a quote. */
    NO_RESULTS,

    /** select → init → confirm after the user's Book tap. */
    BOOKING,
    BOOKED,
    ARRIVING,
    ON_TRIP,
    ENDED,

    /** Soft cancel sent; waiting for the network's fee. */
    CANCEL_REQUESTED,

    /** The fee is on screen; nothing is cancelled until the user confirms. */
    CANCEL_FEE_SHOWN,

    /** Confirm sent; waiting for `on_cancel`. */
    CANCELLING,
    CANCELLED,
    FAILED,

    /** Shown as the "ONDC unavailable" row, with [OndcView.unavailable] saying why and what to do. */
    UNAVAILABLE,
}

enum class OndcUnavailableReason { SWITCHED_OFF, SIGN_IN_NEEDED, OFFLINE, TIMEOUT, SERVER, NOT_CONFIGURED, LISTENER_FAILED, QUOTA }

/** A seller quote as a comparison row: [row] is a NETWORK row ("network quote"); [demoSeller] adds "ONDC demo seller". */
data class OndcQuoteView(val itemId: String, val row: GatedRow, val demoSeller: Boolean)

/** What the ONDC row and sheet show. Every number is protocol data (I2); every label is a string resource (I5). */
data class OndcView(
    val legId: String? = null,
    val txnId: String? = null,
    val phase: OndcPhase = OndcPhase.IDLE,
    val quotes: List<OndcQuoteView> = emptyList(),
    val selectedItemId: String? = null,
    /** The network's cancellation fee (₹), shown before the user confirms. */
    val feeInr: Int? = null,
    val driverEtaMin: Int? = null,
    val driverDistanceMeters: Int? = null,
    val demoSeller: Boolean = false,
    val unavailable: OndcUnavailableReason? = null,
)

/** The outcome of one client call, for the caller's feedback. */
sealed interface OndcResult {
    data object Ok : OndcResult
    data class Unavailable(val reason: OndcUnavailableReason) : OndcResult

    /** Confirm-cancel refused: the network's fee has not been shown to the user yet. */
    data object FeeNotShown : OndcResult

    /** No transaction for that leg (search first). */
    data object NoTransaction : OndcResult

    /** The item is not among the current quotes. */
    data object NoSuchItem : OndcResult

    /** A booked ride is still live on the network; it must end or be cancelled before a new search. */
    data object Busy : OndcResult

    /** The booking or cancellation is not in a state that allows this call. */
    data object NotAllowed : OndcResult

    /** The seller's callback did not arrive in time. */
    data object Timeout : OndcResult

    /** The seller failed the transaction. */
    data object Failed : OndcResult
}

/**
 * The ONDC buyer flow on the phone (goal 17, architecture §4.2 OndcClient, §4.4 ONDC states, §4.6 rung 1): calls to the
 * `/v1/ondc` endpoints plus a Firestore listener on `ondc_txn/{txnId}` whose snapshots become core ONDC [Protocol]
 * events for the guardian ([OndcEvents]).
 *
 * - search → quotes as NETWORK rows ("network quote", and "ONDC demo seller" for the hackathon's mock seller);
 * - book (after the user's tap, carrying its tap id, I1) → select → init → confirm, each step waiting for the seller's
 *   callback in Firestore;
 * - cancel → soft cancel first: the network's fee is shown, and [confirmCancel] is refused until it has been (the
 *   guardian also waits for the user's CONFIRM_CANCEL tap); `on_cancel` is evidence rung 1;
 * - any failure → the "ONDC unavailable" row with what to do next. `ondc_enabled` off blocks new searches and bookings;
 *   a booked ride can still be cancelled.
 *
 * One ONDC leg per session. Confinement: main thread ([scope] main-confined; Firestore calls back on main).
 */
class OndcClient(
    private val api: ApiClient,
    private val txns: OndcTxnSource,
    private val ondcEnabled: StateFlow<Boolean>,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val stepTimeoutMs: Long = STEP_TIMEOUT_MS,
    private val searchTimeoutMs: Long = SEARCH_TIMEOUT_MS,
) {
    private val _view = MutableStateFlow(OndcView())
    val view: StateFlow<OndcView> = _view.asStateFlow()

    private var sink: OndcEventSink? = null
    private val undelivered = ArrayDeque<Pair<String, Protocol>>()
    private var txn: Txn? = null

    private class Txn(val legId: String, val txnId: String) {
        val doc = MutableStateFlow<OndcTxnDoc?>(null)
        var listener: AutoCloseable? = null
        var searchTimeout: Job? = null
        val delivered = HashSet<String>()
        var bookTap: TapId? = null
        var cancelRound = 0
        var cancelRequested = false
        var confirmRequested = false
        var feeShownRound = -1

        /** The soft-cancel round the user answered "Keep ride" to. */
        var keptRound = -1
        var listenFailure: OndcListenFailure? = null

        /** When each quote (by item id, price and ETA) was first seen: its "seen at" time (goal 6). */
        val quoteSeenAt = HashMap<OndcQuoteDoc, Long>()
    }

    /** Integration binds the guardian; events that arrived before are delivered now, in order. */
    fun bind(sink: OndcEventSink) {
        this.sink = sink
        while (undelivered.isNotEmpty()) {
            val (legId, event) = undelivered.removeFirst()
            sink.onProtocol(legId, event)
        }
    }

    // ------------------------------------------------------------------ search

    suspend fun search(sessionId: String, legId: String, pickup: LatLng, drop: LatLng): OndcResult {
        txn?.let { current ->
            val state = current.doc.value?.state
            if (state != null && state.progress >= OndcTxnState.CONFIRMED.progress && !state.terminal) return OndcResult.Busy
            stop()
        }
        if (!ondcEnabled.value) return unavailable(legId, null, OndcUnavailableReason.SWITCHED_OFF)
        _view.value = OndcView(legId = legId, phase = OndcPhase.SEARCHING)
        val result = api.ondcSearch(sessionId, pickup, drop)
        val txnId = (result as? ApiResult.Ok)?.body?.txnId ?: return unavailable(legId, null, reasonOf(result))
        val t = Txn(legId, txnId)
        txn = t
        _view.value = OndcView(legId = legId, txnId = txnId, phase = OndcPhase.SEARCHING)
        t.listener = txns.listen(txnId) { snapshot -> onSnapshot(t, snapshot) }
        t.searchTimeout = scope.launch {
            delay(searchTimeoutMs)
            if (txn === t && _view.value.phase == OndcPhase.SEARCHING) _view.update { it.copy(phase = OndcPhase.NO_RESULTS) }
        }
        return OndcResult.Ok
    }

    // ------------------------------------------------------------------ guardian commands

    /** Runs a guardian [GuardianEffect.OndcCommand] (every one follows a user tap, I1). */
    suspend fun execute(command: GuardianEffect.OndcCommand): OndcResult = when (command.command) {
        OndcCommandKind.BOOK -> command.itemId?.let { book(command.legId, it, command.tapId) } ?: OndcResult.NoSuchItem
        OndcCommandKind.SOFT_CANCEL -> softCancel(command.legId, command.tapId)
        OndcCommandKind.CONFIRM_CANCEL -> confirmCancel(command.legId, command.tapId)
    }

    /** select → init → confirm for the quote the user tapped; [tapId] is that tap (sent with confirm, I1). */
    suspend fun book(legId: String, itemId: String, tapId: TapId): OndcResult {
        val t = txnFor(legId) ?: return OndcResult.NoTransaction
        if (!ondcEnabled.value) return unavailable(legId, t.txnId, OndcUnavailableReason.SWITCHED_OFF)
        val doc = t.doc.value ?: return OndcResult.NoSuchItem
        if (doc.state != OndcTxnState.SEARCHING_NET) return OndcResult.NotAllowed
        if (doc.quotes.none { it.itemId == itemId }) return OndcResult.NoSuchItem
        t.bookTap = tapId
        t.searchTimeout?.cancel()
        _view.update { it.copy(phase = OndcPhase.BOOKING, selectedItemId = itemId, unavailable = null) }

        api.ondcSelect(t.txnId, itemId).failure()?.let { return unavailable(legId, t.txnId, reasonOf(it)) }
        awaitProgress(t, OndcTxnState.ON_SELECT)?.let { return it }
        api.ondcInit(t.txnId).failure()?.let { return unavailable(legId, t.txnId, reasonOf(it)) }
        awaitProgress(t, OndcTxnState.INIT)?.let { return it }
        api.ondcConfirm(t.txnId, tapId.value).failure()?.let { return unavailable(legId, t.txnId, reasonOf(it)) }
        return OndcResult.Ok
    }

    /** Asks the network what cancelling costs now (`confirm = false`); nothing is cancelled yet. */
    suspend fun softCancel(legId: String, tapId: TapId): OndcResult {
        val t = txnFor(legId) ?: return OndcResult.NoTransaction
        val state = t.doc.value?.state
        if (state == null || !(state.cancellable || state == OndcTxnState.SOFT_CANCEL)) return OndcResult.NotAllowed
        t.cancelRound++
        t.cancelRequested = true
        refreshView(t)
        val result = api.ondcCancel(t.txnId, tapId.value, confirm = false)
        result.failure()?.let {
            t.cancelRequested = false
            refreshView(t)
            return OndcResult.Unavailable(reasonOf(it))
        }
        return OndcResult.Ok
    }

    /**
     * Confirms the cancel after the user saw the fee and tapped Confirm. Refused with [OndcResult.FeeNotShown] unless
     * the network's fee for the latest soft cancel has been shown.
     */
    suspend fun confirmCancel(legId: String, tapId: TapId): OndcResult {
        val t = txnFor(legId) ?: return OndcResult.NoTransaction
        val feeOnScreen = t.cancelRound > 0 && t.feeShownRound == t.cancelRound && t.keptRound != t.cancelRound
        if (t.doc.value?.state != OndcTxnState.SOFT_CANCEL || !feeOnScreen) {
            return OndcResult.FeeNotShown
        }
        t.confirmRequested = true
        refreshView(t)
        val result = api.ondcCancel(t.txnId, tapId.value, confirm = true)
        result.failure()?.let {
            t.confirmRequested = false
            refreshView(t)
            return OndcResult.Unavailable(reasonOf(it))
        }
        return OndcResult.Ok
    }

    /** The user kept the ride after seeing the fee (the guardian's ONDC_KEEP_RIDE); the soft cancel simply lapses. */
    fun keepRide(legId: String) {
        val t = txnFor(legId) ?: return
        t.cancelRequested = false
        t.confirmRequested = false
        t.keptRound = t.cancelRound
        refreshView(t)
    }

    /** Asks the seller for a status update (`on_status` lands in Firestore). */
    suspend fun refreshStatus(legId: String): OndcResult {
        val t = txnFor(legId) ?: return OndcResult.NoTransaction
        return api.ondcStatus(t.txnId).failure()?.let { OndcResult.Unavailable(reasonOf(it)) } ?: OndcResult.Ok
    }

    /** Stops listening (session closed); the view returns to idle. */
    fun stop() {
        txn?.let {
            it.listener?.close()
            it.searchTimeout?.cancel()
        }
        txn = null
        _view.value = OndcView()
    }

    // ------------------------------------------------------------------ Firestore

    private fun onSnapshot(t: Txn, snapshot: OndcTxnSnapshot) {
        if (txn !== t) return
        when (snapshot) {
            is OndcTxnSnapshot.Failed -> {
                t.listenFailure = snapshot.reason
                val reason = if (snapshot.reason == OndcListenFailure.QUOTA) OndcUnavailableReason.QUOTA else OndcUnavailableReason.LISTENER_FAILED
                _view.update { it.copy(phase = OndcPhase.UNAVAILABLE, unavailable = reason) }
            }
            is OndcTxnSnapshot.Doc -> {
                val doc = snapshot.doc ?: return
                t.listenFailure = null
                when (doc.state) {
                    OndcTxnState.SOFT_CANCEL -> if (doc.feeInr != null && t.cancelRequested && t.cancelRound > 0) {
                        t.cancelRequested = false
                        t.feeShownRound = t.cancelRound
                    }
                    OndcTxnState.CANCEL_CONFIRMED, OndcTxnState.FAILED, OndcTxnState.ENDED -> {
                        t.cancelRequested = false
                        t.confirmRequested = false
                    }
                    else -> Unit
                }
                t.doc.value = doc
                OndcEvents.from(doc, t.bookTap, t.cancelRound)
                    .filter { event -> t.delivered.add(event.messageId!!) }
                    .forEach { deliver(t.legId, it) }
                refreshView(t)
            }
        }
    }

    private fun deliver(legId: String, event: Protocol) {
        val s = sink
        if (s != null) {
            s.onProtocol(legId, event)
        } else {
            if (undelivered.size >= MAX_UNDELIVERED) undelivered.removeFirst()
            undelivered.addLast(legId to event)
        }
    }

    private fun refreshView(t: Txn) {
        val doc = t.doc.value
        val now = clock.wallMs()
        val quotes = doc?.quotes.orEmpty().map { q ->
            val seenAt = t.quoteSeenAt.getOrPut(q) { now }
            OndcQuoteView(q.itemId, GatedRow.network(q.vehicleClass, q.providerName.orEmpty(), q.priceInr, q.etaMin, seenAt), doc?.demoSeller == true)
        }
        val phase = when {
            t.listenFailure != null -> OndcPhase.UNAVAILABLE
            doc?.state == null -> _view.value.phase.takeIf { it != OndcPhase.UNAVAILABLE } ?: OndcPhase.SEARCHING
            else -> phaseOf(doc.state, quotes.isNotEmpty(), t)
        }
        _view.update { current ->
            current.copy(
                legId = t.legId,
                txnId = t.txnId,
                phase = if (current.phase == OndcPhase.NO_RESULTS && phase == OndcPhase.SEARCHING) current.phase else phase,
                quotes = quotes,
                selectedItemId = doc?.selectedItemId ?: current.selectedItemId,
                feeInr = doc?.feeInr?.takeIf { phase == OndcPhase.CANCEL_FEE_SHOWN || phase == OndcPhase.CANCELLING },
                driverEtaMin = doc?.driver?.etaMin,
                driverDistanceMeters = doc?.driver?.distanceMeters,
                demoSeller = doc?.demoSeller == true,
                unavailable = if (phase == OndcPhase.UNAVAILABLE) current.unavailable else null,
            )
        }
    }

    private fun phaseOf(state: OndcTxnState, hasQuotes: Boolean, t: Txn): OndcPhase = when (state) {
        OndcTxnState.SEARCHING_NET -> if (_view.value.phase == OndcPhase.BOOKING) OndcPhase.BOOKING else if (hasQuotes) OndcPhase.QUOTES else OndcPhase.SEARCHING
        OndcTxnState.ON_SELECT, OndcTxnState.INIT -> OndcPhase.BOOKING
        OndcTxnState.CONFIRMED -> if (t.cancelRequested) OndcPhase.CANCEL_REQUESTED else OndcPhase.BOOKED
        OndcTxnState.ARRIVING -> if (t.cancelRequested) OndcPhase.CANCEL_REQUESTED else OndcPhase.ARRIVING
        OndcTxnState.ON_TRIP -> OndcPhase.ON_TRIP
        OndcTxnState.ENDED -> OndcPhase.ENDED
        OndcTxnState.SOFT_CANCEL -> when {
            t.keptRound == t.cancelRound -> OndcPhase.BOOKED
            t.confirmRequested -> OndcPhase.CANCELLING
            t.feeShownRound == t.cancelRound && t.cancelRound > 0 -> OndcPhase.CANCEL_FEE_SHOWN
            else -> OndcPhase.CANCEL_REQUESTED
        }
        OndcTxnState.CANCEL_CONFIRMED -> OndcPhase.CANCELLED
        OndcTxnState.FAILED -> OndcPhase.FAILED
    }

    /** Waits for the seller's callback to move the transaction to [target] (or beyond); null when it did. */
    private suspend fun awaitProgress(t: Txn, target: OndcTxnState): OndcResult? {
        val reached = withTimeoutOrNull(stepTimeoutMs) {
            t.doc.first { doc -> doc?.state == OndcTxnState.FAILED || (doc?.state?.progress ?: 0) >= target.progress }
        }
        return when {
            reached == null -> {
                _view.update { it.copy(phase = OndcPhase.UNAVAILABLE, unavailable = OndcUnavailableReason.TIMEOUT) }
                OndcResult.Timeout
            }
            reached.state == OndcTxnState.FAILED -> OndcResult.Failed
            else -> null
        }
    }

    private fun txnFor(legId: String): Txn? = txn?.takeIf { it.legId == legId }

    private fun unavailable(legId: String, txnId: String?, reason: OndcUnavailableReason): OndcResult {
        _view.update { it.copy(legId = legId, txnId = txnId ?: it.txnId, phase = OndcPhase.UNAVAILABLE, unavailable = reason) }
        return OndcResult.Unavailable(reason)
    }

    private fun ApiResult<*>.failure(): ApiResult<*>? = takeIf { it !is ApiResult.Ok }

    companion object {
        /** How long each select/init step waits for the seller's callback. */
        const val STEP_TIMEOUT_MS: Long = 15_000

        /** A search without any quote after this shows "No rides on the open network". */
        const val SEARCH_TIMEOUT_MS: Long = 30_000

        private const val MAX_UNDELIVERED = 256

        fun reasonOf(result: ApiResult<*>): OndcUnavailableReason = when (result) {
            is ApiResult.Ok -> OndcUnavailableReason.SERVER
            ApiResult.AuthNeeded, ApiResult.GoogleRequired -> OndcUnavailableReason.SIGN_IN_NEEDED
            ApiResult.Offline -> OndcUnavailableReason.OFFLINE
            ApiResult.Timeout -> OndcUnavailableReason.TIMEOUT
            ApiResult.NotConfigured -> OndcUnavailableReason.NOT_CONFIGURED
            is ApiResult.Http, is ApiResult.AiPaused, is ApiResult.RateLimited -> OndcUnavailableReason.SERVER
        }
    }
}
