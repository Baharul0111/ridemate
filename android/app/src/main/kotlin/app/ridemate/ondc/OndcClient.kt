package app.ridemate.ondc

import app.ridemate.core.gate.GatedRow
import app.ridemate.core.guardian.GuardianEffect
import app.ridemate.core.guardian.OndcCommandKind
import app.ridemate.core.model.LatLng
import app.ridemate.core.state.Protocol
import app.ridemate.core.state.ProtocolKind
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
import kotlinx.coroutines.flow.combine
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

enum class OndcUnavailableReason {
    SWITCHED_OFF, SIGN_IN_NEEDED, OFFLINE, TIMEOUT, SERVER, NOT_CONFIGURED, LISTENER_FAILED, QUOTA,

    /** The soft cancel was not answered (call failed, or no fee within [OndcClient.STEP_TIMEOUT_MS]): nothing was cancelled. */
    CANCEL_NO_ANSWER,

    /** The user confirmed the cancel but the network has not confirmed it yet (RideMate keeps re-sending and checking). */
    CANCEL_UNCONFIRMED,
}

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
 *   callback in Firestore; one booking at a time (a second tap while it runs is refused);
 * - cancel → soft cancel first: the network's fee is shown, and [confirmCancel] is refused until it has been. "Shown"
 *   means its SOFT_CANCEL_QUOTED event reached the guardian, which shows it and only then accepts the user's Confirm, so
 *   the two never disagree (also for a fee that arrives late, or after [resume]); `on_cancel` is evidence rung 1;
 * - every step has a time limit (goal 21): select/init callbacks and the soft-cancel fee [STEP_TIMEOUT_MS]; a confirmed
 *   cancel that the network has not confirmed is re-sent with the user's same tap (at most [MAX_CONFIRM_SENDS] sends)
 *   and the seller is asked for its status, while the guardian keeps reminding;
 * - any failure → the "ONDC unavailable" row with what to do next. `ondc_enabled` off blocks new searches and bookings;
 *   a booked ride can still be cancelled;
 * - [stop] ends the transaction: nothing still running for it changes the view or calls the network again;
 * - [resume] re-attaches to a transaction after the process was restarted (integration persists [OndcView.txnId]).
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

    /** Bumped by every search and [stop]: a search answered after a newer search or a stop is dropped. */
    private var searchGeneration = 0L

    private class Txn(val legId: String, val txnId: String) {
        val doc = MutableStateFlow<OndcTxnDoc?>(null)

        /** Set by [stop]: in-flight work for this transaction ends without touching the view or the network. */
        val stopped = MutableStateFlow(false)
        var listener: AutoCloseable? = null
        var searchTimeout: Job? = null
        val delivered = HashSet<String>()
        var bookTap: TapId? = null

        /** select → init → confirm is running (a second Book tap is refused). */
        var booking = false

        /** Why the last booking attempt stopped before confirm (a late seller callback must not revive "Booking…"). */
        var bookFailed: OndcUnavailableReason? = null
        var cancelRound = 0
        var cancelRequested = false
        var cancelTimer: Job? = null

        /** The soft cancel went unanswered: the row says nothing was cancelled and to tap Cancel ride again. */
        var cancelNoAnswer = false
        var confirmRequested = false
        var confirmTap: TapId? = null
        var confirmSends = 0
        var confirmTimer: Job? = null

        /** The user's confirmed cancel has not been confirmed by the network in time. */
        var confirmStalled = false
        var feeShownRound = -1

        /** The soft-cancel round the user answered "Keep ride" to. */
        var keptRound = -1
        var listenFailure: OndcListenFailure? = null

        /** When each quote (by item id, price and ETA) was first seen: its "seen at" time (goal 6). */
        val quoteSeenAt = HashMap<OndcQuoteDoc, Long>()

        val live: Boolean get() = !stopped.value
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
        val generation = ++searchGeneration
        if (!ondcEnabled.value) return unavailable(null, legId, OndcUnavailableReason.SWITCHED_OFF)
        _view.value = OndcView(legId = legId, phase = OndcPhase.SEARCHING)
        val result = api.ondcSearch(sessionId, pickup, drop)
        // Stopped, or superseded by a newer search, while this one was in flight: it neither listens nor shows anything.
        if (generation != searchGeneration) return OndcResult.NoTransaction
        val txnId = (result as? ApiResult.Ok)?.body?.txnId ?: return unavailable(null, legId, reasonOf(result))
        val t = attach(legId, txnId, bookTap = null)
        t.searchTimeout = scope.launch {
            delay(searchTimeoutMs)
            if (txn === t && _view.value.phase == OndcPhase.SEARCHING) _view.update { it.copy(phase = OndcPhase.NO_RESULTS) }
        }
        return OndcResult.Ok
    }

    /**
     * Re-attaches to an existing transaction after the process restarted (the guardian was restored from its snapshot):
     * listens to `ondc_txn/{txnId}` again so status, the cancel fee and `on_cancel` reach the guardian, and cancelling
     * works. [bookTap] is the user's Book tap when known (the guardian also remembers it). No network call is made.
     */
    fun resume(legId: String, txnId: String, bookTap: TapId? = null) {
        val current = txn
        if (current != null && current.legId == legId && current.txnId == txnId) return
        stop()
        attach(legId, txnId, bookTap)
    }

    private fun attach(legId: String, txnId: String, bookTap: TapId?): Txn {
        val t = Txn(legId, txnId)
        t.bookTap = bookTap
        txn = t
        _view.value = OndcView(legId = legId, txnId = txnId, phase = OndcPhase.SEARCHING)
        t.listener = txns.listen(txnId) { snapshot -> onSnapshot(t, snapshot) }
        return t
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
        if (!ondcEnabled.value) return unavailable(t, legId, OndcUnavailableReason.SWITCHED_OFF)
        val doc = t.doc.value ?: return OndcResult.NoSuchItem
        if (t.booking || doc.state != OndcTxnState.SEARCHING_NET) return OndcResult.NotAllowed
        if (doc.quotes.none { it.itemId == itemId }) return OndcResult.NoSuchItem
        t.booking = true
        t.bookFailed = null
        t.bookTap = tapId
        t.searchTimeout?.cancel()
        _view.update { it.copy(phase = OndcPhase.BOOKING, selectedItemId = itemId, unavailable = null) }
        try {
            api.ondcSelect(t.txnId, itemId).failure()?.let { return bookFailed(t, reasonOf(it)) }
            awaitProgress(t, OndcTxnState.ON_SELECT)?.let { return it }
            api.ondcInit(t.txnId).failure()?.let { return bookFailed(t, reasonOf(it)) }
            awaitProgress(t, OndcTxnState.INIT)?.let { return it }
            api.ondcConfirm(t.txnId, tapId.value).failure()?.let { return bookFailed(t, reasonOf(it)) }
            return if (t.live) OndcResult.Ok else OndcResult.NoTransaction
        } finally {
            t.booking = false
        }
    }

    private fun bookFailed(t: Txn, reason: OndcUnavailableReason): OndcResult {
        if (!t.live) return OndcResult.NoTransaction
        t.bookFailed = reason
        return unavailable(t, t.legId, reason)
    }

    /** Asks the network what cancelling costs now (`confirm = false`); nothing is cancelled yet. */
    suspend fun softCancel(legId: String, tapId: TapId): OndcResult {
        val t = txnFor(legId) ?: return OndcResult.NoTransaction
        val state = t.doc.value?.state
        if (state == null || !(state.cancellable || state == OndcTxnState.SOFT_CANCEL)) return OndcResult.NotAllowed
        val round = ++t.cancelRound
        t.cancelRequested = true
        t.cancelNoAnswer = false
        t.cancelTimer?.cancel()
        refreshView(t)
        val result = api.ondcCancel(t.txnId, tapId.value, confirm = false)
        if (!t.live) return OndcResult.NoTransaction
        result.failure()?.let {
            if (t.cancelRound == round && t.cancelRequested) {
                t.cancelRequested = false
                t.cancelNoAnswer = true
                refreshView(t)
            }
            return OndcResult.Unavailable(reasonOf(it))
        }
        if (t.cancelRequested && t.cancelRound == round) {
            t.cancelTimer = scope.launch {
                delay(stepTimeoutMs)
                if (t.live && t.cancelRequested && t.cancelRound == round) {
                    t.cancelRequested = false
                    t.cancelNoAnswer = true
                    refreshView(t)
                }
            }
        }
        return OndcResult.Ok
    }

    /**
     * Confirms the cancel after the user saw the fee and tapped Confirm. Refused with [OndcResult.FeeNotShown] unless
     * the network's fee for the latest soft cancel has been shown (delivered to the guardian). If the network does not
     * confirm in time, the same request (the user's tap) is re-sent, up to [MAX_CONFIRM_SENDS] sends in all.
     */
    suspend fun confirmCancel(legId: String, tapId: TapId): OndcResult {
        val t = txnFor(legId) ?: return OndcResult.NoTransaction
        val feeOnScreen = t.feeShownRound == t.cancelRound && t.keptRound != t.cancelRound
        if (t.doc.value?.state != OndcTxnState.SOFT_CANCEL || !feeOnScreen) {
            return OndcResult.FeeNotShown
        }
        if (t.confirmRequested) return OndcResult.NotAllowed
        t.confirmRequested = true
        t.confirmTap = tapId
        t.confirmSends = 1
        t.confirmStalled = false
        refreshView(t)
        val result = api.ondcCancel(t.txnId, tapId.value, confirm = true)
        if (!t.live) return OndcResult.NoTransaction
        val failure = result.failure()
        if (t.confirmRequested) {
            // Still waiting for on_cancel (a fast seller may already have answered while the call returned).
            if (failure != null) {
                t.confirmStalled = true
                refreshView(t)
            }
            watchConfirm(t, tapId)
        }
        return failure?.let { OndcResult.Unavailable(reasonOf(it)) } ?: OndcResult.Ok
    }

    /**
     * Until `on_cancel` arrives: every [stepTimeoutMs] without it, the row says the cancel is not confirmed yet, the seller
     * is asked for its status and the user's confirm (same tap, I1) is sent again, at most [MAX_CONFIRM_SENDS] sends.
     */
    private fun watchConfirm(t: Txn, tapId: TapId) {
        t.confirmTimer?.cancel()
        t.confirmTimer = scope.launch {
            fun waiting() = t.live && t.confirmRequested && t.doc.value?.state?.terminal != true
            while (true) {
                delay(stepTimeoutMs)
                if (!waiting()) return@launch
                t.confirmStalled = true
                refreshView(t)
                api.ondcStatus(t.txnId)
                if (!waiting() || t.confirmSends >= MAX_CONFIRM_SENDS) return@launch
                t.confirmSends++
                api.ondcCancel(t.txnId, tapId.value, confirm = true)
            }
        }
    }

    /** The user kept the ride after seeing the fee (the guardian's ONDC_KEEP_RIDE); the soft cancel simply lapses. */
    fun keepRide(legId: String) {
        val t = txnFor(legId) ?: return
        t.cancelRequested = false
        t.cancelNoAnswer = false
        t.cancelTimer?.cancel()
        t.confirmRequested = false
        t.confirmTimer?.cancel()
        t.confirmStalled = false
        t.keptRound = t.cancelRound
        refreshView(t)
    }

    /** Asks the seller for a status update (`on_status` lands in Firestore). */
    suspend fun refreshStatus(legId: String): OndcResult {
        val t = txnFor(legId) ?: return OndcResult.NoTransaction
        return api.ondcStatus(t.txnId).failure()?.let { OndcResult.Unavailable(reasonOf(it)) } ?: OndcResult.Ok
    }

    /** Stops listening (session closed); the view returns to idle and nothing still running for it has any effect. */
    fun stop() {
        searchGeneration++
        txn?.let {
            it.stopped.value = true
            it.listener?.close()
            it.searchTimeout?.cancel()
            it.cancelTimer?.cancel()
            it.confirmTimer?.cancel()
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
                if (doc.state?.terminal == true) {
                    t.cancelRequested = false
                    t.cancelNoAnswer = false
                    t.cancelTimer?.cancel()
                    t.confirmRequested = false
                    t.confirmStalled = false
                    t.confirmTimer?.cancel()
                }
                t.doc.value = doc
                val fresh = OndcEvents.from(doc, t.bookTap, t.cancelRound).filter { event -> t.delivered.add(event.messageId!!) }
                if (fresh.any { it.kind == ProtocolKind.SOFT_CANCEL_QUOTED }) {
                    // The guardian shows this fee now and accepts the user's Confirm only after it: from here on the
                    // client may send the confirm (also for a fee that came after the wait timed out, or after resume).
                    // A new quote after "Keep ride" is shown again, so that answer no longer stands.
                    t.feeShownRound = t.cancelRound
                    if (t.keptRound == t.cancelRound) t.keptRound = -1
                    t.cancelRequested = false
                    t.cancelNoAnswer = false
                    t.cancelTimer?.cancel()
                }
                fresh.forEach { deliver(t.legId, it) }
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
        if (txn !== t) return
        val doc = t.doc.value
        val now = clock.wallMs()
        val quotes = doc?.quotes.orEmpty().map { q ->
            val seenAt = t.quoteSeenAt.getOrPut(q) { now }
            OndcQuoteView(q.itemId, GatedRow.network(q.vehicleClass, q.providerName.orEmpty(), q.priceInr, q.etaMin, seenAt), doc?.demoSeller == true)
        }
        val stalled = doc?.state?.let { stallOf(it, t) }
        val phase = when {
            t.listenFailure != null -> OndcPhase.UNAVAILABLE
            doc?.state == null -> _view.value.phase.takeIf { it != OndcPhase.UNAVAILABLE } ?: OndcPhase.SEARCHING
            stalled != null -> OndcPhase.UNAVAILABLE
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
                unavailable = when {
                    phase != OndcPhase.UNAVAILABLE -> null
                    t.listenFailure == null && stalled != null -> stalled
                    else -> current.unavailable
                },
            )
        }
    }

    /**
     * Why the row shows "ONDC unavailable" while the transaction itself is still open, or null: a booking that stopped
     * before confirm (a late seller callback must not bring back "Booking…"), an unanswered soft cancel, or a confirmed
     * cancel the network has not confirmed yet.
     */
    private fun stallOf(state: OndcTxnState, t: Txn): OndcUnavailableReason? = when (state) {
        OndcTxnState.ON_SELECT, OndcTxnState.INIT -> t.bookFailed?.takeIf { !t.booking }
        OndcTxnState.CONFIRMED, OndcTxnState.ARRIVING ->
            OndcUnavailableReason.CANCEL_NO_ANSWER.takeIf { t.cancelNoAnswer && !t.cancelRequested }
        OndcTxnState.SOFT_CANCEL -> when {
            t.keptRound == t.cancelRound -> null
            t.confirmRequested -> OndcUnavailableReason.CANCEL_UNCONFIRMED.takeIf { t.confirmStalled }
            t.feeShownRound == t.cancelRound -> null
            t.cancelNoAnswer -> OndcUnavailableReason.CANCEL_NO_ANSWER
            else -> null
        }
        else -> null
    }

    private fun phaseOf(state: OndcTxnState, hasQuotes: Boolean, t: Txn): OndcPhase = when (state) {
        OndcTxnState.SEARCHING_NET -> if (t.booking) OndcPhase.BOOKING else if (hasQuotes) OndcPhase.QUOTES else OndcPhase.SEARCHING
        OndcTxnState.ON_SELECT, OndcTxnState.INIT -> OndcPhase.BOOKING
        OndcTxnState.CONFIRMED -> if (t.cancelRequested) OndcPhase.CANCEL_REQUESTED else OndcPhase.BOOKED
        OndcTxnState.ARRIVING -> if (t.cancelRequested) OndcPhase.CANCEL_REQUESTED else OndcPhase.ARRIVING
        OndcTxnState.ON_TRIP -> OndcPhase.ON_TRIP
        OndcTxnState.ENDED -> OndcPhase.ENDED
        OndcTxnState.SOFT_CANCEL -> when {
            t.keptRound == t.cancelRound -> OndcPhase.BOOKED
            t.confirmRequested -> OndcPhase.CANCELLING
            t.feeShownRound == t.cancelRound -> OndcPhase.CANCEL_FEE_SHOWN
            else -> OndcPhase.CANCEL_REQUESTED
        }
        OndcTxnState.CANCEL_CONFIRMED -> OndcPhase.CANCELLED
        OndcTxnState.FAILED -> OndcPhase.FAILED
    }

    /**
     * Waits for the seller's callback to move the transaction to [target] (or beyond); null when it did. A [stop] while
     * waiting ends the wait at once with [OndcResult.NoTransaction] and leaves the (new) view alone.
     */
    private suspend fun awaitProgress(t: Txn, target: OndcTxnState): OndcResult? {
        if (!t.live) return OndcResult.NoTransaction
        val reached = withTimeoutOrNull(stepTimeoutMs) {
            combine(t.doc, t.stopped) { doc, stopped -> doc to stopped }
                .first { (doc, stopped) -> stopped || doc?.state == OndcTxnState.FAILED || (doc?.state?.progress ?: 0) >= target.progress }
                .first
        }
        return when {
            !t.live -> OndcResult.NoTransaction
            reached == null -> {
                bookFailed(t, OndcUnavailableReason.TIMEOUT)
                OndcResult.Timeout
            }
            reached.state == OndcTxnState.FAILED -> OndcResult.Failed
            else -> null
        }
    }

    private fun txnFor(legId: String): Txn? = txn?.takeIf { it.legId == legId }

    /** Shows the "ONDC unavailable" row, unless [t] is a transaction that has been stopped since. */
    private fun unavailable(t: Txn?, legId: String, reason: OndcUnavailableReason): OndcResult {
        if (t == null || txn === t) {
            _view.update { it.copy(legId = legId, txnId = t?.txnId ?: it.txnId, phase = OndcPhase.UNAVAILABLE, unavailable = reason) }
        }
        return OndcResult.Unavailable(reason)
    }

    private fun ApiResult<*>.failure(): ApiResult<*>? = takeIf { it !is ApiResult.Ok }

    companion object {
        /** How long each select/init step waits for the seller's callback, and a soft cancel for the network's fee. */
        const val STEP_TIMEOUT_MS: Long = 15_000

        /** A search without any quote after this shows "No rides on the open network". */
        const val SEARCH_TIMEOUT_MS: Long = 30_000

        /** A confirmed cancel is sent at most this many times (the user's one tap) while the network stays silent. */
        const val MAX_CONFIRM_SENDS: Int = 3

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
