package app.ridemate.session

import app.ridemate.core.format.DistanceText
import app.ridemate.core.guardian.Guardian
import app.ridemate.core.guardian.GuardianConfig
import app.ridemate.core.guardian.GuardianEffect
import app.ridemate.core.guardian.GuardianState
import app.ridemate.core.guardian.LegRecord
import app.ridemate.core.guardian.LegRole
import app.ridemate.core.guardian.OpenPurpose
import app.ridemate.core.guardian.TimerKind
import app.ridemate.core.guardian.UiKey
import app.ridemate.core.guardian.UserAction
import app.ridemate.core.model.EtaSource
import app.ridemate.core.model.EvidenceRung
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import app.ridemate.core.state.Answer
import app.ridemate.core.state.Protocol
import app.ridemate.core.state.RideEvent
import app.ridemate.core.state.TapId
import app.ridemate.core.state.isAdmissible
import app.ridemate.core.watch.NotificationClassifier
import app.ridemate.core.watch.OngoingNotifications
import app.ridemate.core.watch.PostRemoval
import app.ridemate.core.watch.PostSnapshot
import app.ridemate.core.watch.PostSource
import app.ridemate.core.watch.RemovalSource
import app.ridemate.core.watch.RideWatchPipeline
import app.ridemate.core.watch.TextMasker
import app.ridemate.crash.CrashReporter
import app.ridemate.crash.CrashSite
import app.ridemate.data.LegEntity
import app.ridemate.data.OutboxKind
import app.ridemate.data.PlaceColumns
import app.ridemate.data.SessionEntity
import app.ridemate.data.SessionStatus
import app.ridemate.notify.ActionTarget
import app.ridemate.notify.GuardianIntents
import app.ridemate.notify.GuardianNotifier
import app.ridemate.notify.GuardianPresenter
import app.ridemate.notify.GuardianTap
import app.ridemate.notify.GuardianUiReducer
import app.ridemate.notify.GuardianUiState
import app.ridemate.notify.SessionOp
import app.ridemate.notify.SessionOpKind
import app.ridemate.outbox.OutboxDriver
import app.ridemate.outbox.OutboxHandler
import app.ridemate.outbox.OutboxItem
import app.ridemate.outbox.OutboxOutcome
import app.ridemate.util.Clock
import app.ridemate.util.SessionActivity
import app.ridemate.watch.RideWatchSink
import app.ridemate.watch.RideWatchStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import app.ridemate.core.time.Clock as CoreClock

/**
 * The ride guardian of this process: owns core's [Guardian] for the active session and its [RideWatchPipeline], and
 * wires them to Android.
 *
 * - **Confinement.** The guardian and the pipeline are touched only on [guardianDispatcher] (one thread). Every public
 *   call returns at once and is applied there in order.
 * - **Restore.** [start] restores the newest ACTIVE session's snapshot (Guardian.restore + resume, which rebases its
 *   clock after a reboot and fires anything that came due while the process was dead), then asks RideWatch to re-read
 *   the shade (I9). Calls made before the restore finishes wait for it.
 * - **Persistence.** After every step the snapshot, the Session/Leg rows, evidence rungs and the outbox mirror of the
 *   reminder/follow-up schedule are written in order on [ioDispatcher] ([GuardianPersistence]).
 * - **Effects.** Every [GuardianEffect] updates the UI model, the notifications ([GuardianNotifier]) and the bubble;
 *   OpenApp goes through the outbox (OPEN_NEXT_APP) and the background-start rule ([GuardianAppOpener]).
 * - **M7.** [status] tells the session service whether it must keep running (I8 via [Guardian.needsForegroundService]).
 */
class GuardianRuntime(
    private val clock: Clock,
    private val guardianDispatcher: CoroutineDispatcher,
    private val ioDispatcher: CoroutineDispatcher,
    parentScope: CoroutineScope,
    private val persistence: GuardianPersistence,
    private val outboxDriver: OutboxDriver,
    private val config: GuardianConfigSource,
    private val classifierFactory: (activeSessionId: () -> String?) -> NotificationClassifier?,
    private val masker: TextMasker,
    private val notifier: GuardianNotifier,
    private val presenter: () -> GuardianPresenter,
    private val bindings: SessionBindings,
    private val watchStatus: RideWatchStatus,
    private val serviceStarter: ServiceStarter,
    private val sessionActivity: SessionActivity,
    private val keepSearchingPreference: KeepSearchingPreference,
    private val appOpener: GuardianAppOpener,
    private val captureGate: CaptureGate,
    private val mainExecutor: Executor,
    private val crash: CrashReporter? = null,
    private val guardianConfig: GuardianConfig = GuardianConfig(),
) : GuardianSessionApi, SessionCommands, RideWatchSink {

    private val coreClock = object : CoreClock {
        override fun elapsedMs(): Long = clock.elapsedRealtimeMs()
        override fun wallMs(): Long = clock.wallMs()
    }

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + guardianDispatcher)
    private val fees = LiveFeeEstimator(config::feeRules)
    private val started = AtomicBoolean(false)
    private val restoredSignal = CompletableDeferred<Unit>()

    // ---- confined to guardianDispatcher ----
    private lateinit var guardian: Guardian
    private lateinit var pipeline: RideWatchPipeline
    private var ui = GuardianUiState.EMPTY
    private var publishedUi = GuardianUiState.EMPTY

    /** Calls in the order they were made; held until the restore finishes, then applied in that order. */
    private val calls = ArrayDeque<() -> Unit>()
    private var runningCalls = false
    private val pendingEffects = ArrayDeque<GuardianEffect>()
    private var draining = false
    private var drainScheduled = false
    private var dirty = false
    private var stopRequested = false
    private var startRefused = false
    private var sessionRow: SessionEntity? = null
    private var closedAtUtc: Long? = null

    /** Written on the guardian thread; read by the fare reader from any thread ([openedLegId]). */
    @Volatile
    private var lastOpenedLegId: String? = null
    private val captureTaps = HashMap<String, TapId>()
    private val pendingOpens = mutableListOf<OpenRequest>()
    private var version = 0L
    private var lastServiceStartAttempt: Long? = null
    private var resumePosted = false

    // ---- readable from any thread ----
    private val starting = AtomicInteger(0)
    private val requestedVersion = AtomicLong(0)
    private val persistedVersion = MutableStateFlow(0L)

    @Volatile
    private var legIdsByProvider: Map<Provider, String> = emptyMap()

    @Volatile
    private var captureTapsByProvider: Map<Provider, TapId> = emptyMap()

    private val persistQueue = Channel<PersistRequest>(Channel.UNLIMITED)
    private val _ui = MutableStateFlow(GuardianUiState.EMPTY)
    private val _status = MutableStateFlow(GuardianStatus())
    private var outboxRegistration: AutoCloseable? = null

    override val uiState: StateFlow<GuardianUiState> = _ui.asStateFlow()
    override val status: StateFlow<GuardianStatus> = _status.asStateFlow()

    private val _effects = MutableSharedFlow<GuardianEffect>(extraBufferCapacity = EVENT_BUFFER)
    private val _userActions = MutableSharedFlow<UserAction>(extraBufferCapacity = EVENT_BUFFER)

    /** Every effect the guardian emits, in order (Try the demo follows its session through this). */
    val effects: SharedFlow<GuardianEffect> = _effects.asSharedFlow()

    /** Every user tap the guardian receives, from any surface (sheets, bubble, notifications, the demo). */
    val userActions: SharedFlow<UserAction> = _userActions.asSharedFlow()

    /** Registers the outbox handlers, starts the persistence writer and restores an active session. Idempotent. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        outboxRegistration = outboxDriver.register(GUARDIAN_OUTBOX_KINDS, GuardianOutboxHandler(this, watchStatus))
        scope.launch(ioDispatcher) { persistLoop() }
        scope.launch { restore() }
        scope.launch {
            restoredSignal.await()
            // M7: if the service went away while the guardian still needs it (it stopped just as a new session
            // started, or Android stopped it), bring it back at once rather than at the next guardian step.
            sessionActivity.active.drop(1).collect { active ->
                if (active) {
                    // Running again: "Tap to resume" is answered (also one an earlier process posted).
                    withdrawResume(force = true)
                } else {
                    lastServiceStartAttempt = null
                    maybeStartService()
                }
            }
        }
    }

    /** Suspends until the guardian of an earlier process has been restored (or found absent). */
    suspend fun awaitRestored() {
        start()
        restoredSignal.await()
    }

    /** Suspends until every call made so far has been applied and its state written to storage. */
    suspend fun awaitIdle() {
        awaitRestored()
        repeat(IDLE_ROUNDS) {
            withContext(guardianDispatcher) { drain() }
            val target = requestedVersion.get()
            persistedVersion.first { it >= target }
        }
    }

    // ------------------------------------------------------------------ GuardianSessionApi

    override fun startSession(spec: SessionSpec) = begin(spec, demo = false)

    override fun startDemoSession(spec: SessionSpec) = begin(spec, demo = true)

    override fun legIdFor(provider: Provider): String? = legIdsByProvider[provider]

    override fun onRideEvent(legId: String, event: RideEvent) = enqueue { guardian.onRideEvent(legId, event) }

    override fun onDemoPost(post: PostSnapshot) = enqueue {
        if (pipeline.demoSessionActive && post.packageName in RideWatchPipeline.DEMO_PACKAGES) pipeline.onPosted(PostSource.of(post))
    }

    override fun onDemoRemoval(removal: PostRemoval) = enqueue {
        if (pipeline.demoSessionActive && removal.packageName in RideWatchPipeline.DEMO_PACKAGES) pipeline.onRemoved(RemovalSource.of(removal))
    }

    override fun onProtocolEvent(legId: String, event: Protocol) = enqueue { guardian.onProtocolEvent(legId, event) }

    override fun onUserAction(action: UserAction) = enqueue { applyUserAction(action) }

    override fun perform(target: ActionTarget) {
        when (target) {
            is GuardianTap -> target.toUserAction(newTap())?.let(::onUserAction)
            is SessionOp -> when (target.op) {
                SessionOpKind.READ_FARES -> onMain { bindings.fareReader?.readFares() ?: notifier.openRideMate(GuardianIntents.DESTINATION_GUARDIAN) }
                SessionOpKind.BOOKED_HERE -> enqueue {
                    if (guardian.state.session?.closed != false) return@enqueue
                    val legId = target.ref ?: lastOpenedLegId?.takeIf { it in guardian.state.legs } ?: firstUnbookedLeg()
                    if (legId != null) applyUserAction(UserAction.BookedHere(legId, newTap()))
                }
                SessionOpKind.DONE, SessionOpKind.STOP -> requestStop()
                SessionOpKind.TOGGLE_KEEP_SEARCHING -> toggleKeepSearching()
                SessionOpKind.DISMISS_NOTICE -> enqueue { target.ref?.let { ui = GuardianUiReducer.dismissNotice(ui, it) } }
                SessionOpKind.OPEN_GUARDIAN -> onMain { notifier.openRideMate(GuardianIntents.DESTINATION_GUARDIAN) }
            }
        }
    }

    /** Applies [target] and waits until it has been processed (notification actions use this inside goAsync). */
    suspend fun performAndWait(target: ActionTarget) {
        perform(target)
        awaitIdle()
    }

    /** Ends a Try-the-demo session at once: its bookings are fictional, nothing is left to remind about. */
    fun endDemoSession() = enqueue {
        val session = guardian.state.session ?: return@enqueue
        if (!session.demo || session.closed) return@enqueue
        guardian.endDemoSession()
        closedAtUtc = clock.wallMs()
        pipeline.demoSessionActive = false
        onMain { bindings.bubble.clearCancelCard() }
    }

    override fun requestStop() = enqueue {
        val session = guardian.state.session
        if (session != null && !session.closed) stopRequested = true
    }

    // ------------------------------------------------------------------ SessionCommands

    override fun appOpened(provider: Provider) = enqueue {
        legOf(provider)?.let {
            lastOpenedLegId = it
            guardian.markOpened(it)
        }
    }

    override fun faresRead(provider: Provider) = enqueue { legOf(provider)?.let(guardian::markQuoted) }

    // Leg-id forms of the commands, for callers that already know the leg (the launcher, Try the demo).

    /**
     * The leg whose app RideMate opened last in this session (null after a new session starts or Erase): the app on
     * screen as far as RideMate knows. "Booked here" from the bubble and the fare reader's "Read fares" both mean it.
     */
    val openedLegId: String? get() = lastOpenedLegId

    /** RideMate opened leg [legId]'s app (SELECTED → OPENED); "Booked here" then means this app. */
    fun markOpenedLeg(legId: String) = enqueue {
        if (guardian.state.legs.containsKey(legId)) {
            lastOpenedLegId = legId
            guardian.markOpened(legId)
        }
    }

    fun markQuotedLeg(legId: String) = enqueue { guardian.markQuoted(legId) }

    fun recordBookedFareLeg(legId: String, fareInr: Int) = enqueue { guardian.recordBookedFare(legId, fareInr) }

    fun captureVerdictLeg(legId: String, cancelledKeywordsFound: Boolean, tapId: TapId, frameAtElapsedMs: Long) = enqueue {
        guardian.onCaptureVerdict(legId, cancelledKeywordsFound, tapId, frameAtElapsedMs)
    }

    fun verifiedDriverReadingLeg(legId: String, etaMin: Int?, distanceMeters: Int?, tapId: TapId, frameAtElapsedMs: Long) = enqueue {
        guardian.onVerifiedDriverReading(legId, etaMin, distanceMeters, tapId, frameAtElapsedMs)
    }

    override fun bookedHere(provider: Provider) = enqueue { legOf(provider)?.let { applyUserAction(UserAction.BookedHere(it, newTap())) } }

    override fun recordBookedFare(provider: Provider, fareInr: Int) = enqueue { legOf(provider)?.let { guardian.recordBookedFare(it, fareInr) } }

    override fun showMe(provider: Provider) = enqueue { legOf(provider)?.let { applyUserAction(UserAction.ShowMe(it, newTap())) } }

    override fun captureVerdict(provider: Provider, cancelledKeywordsFound: Boolean, tapId: TapId, frameAtElapsedMs: Long) = enqueue {
        legOf(provider)?.let { guardian.onCaptureVerdict(it, cancelledKeywordsFound, tapId, frameAtElapsedMs) }
    }

    override fun verifiedDriverReading(provider: Provider, etaMin: Int?, distanceMeters: Int?, tapId: TapId, frameAtElapsedMs: Long) = enqueue {
        legOf(provider)?.let { guardian.onVerifiedDriverReading(it, etaMin, distanceMeters, tapId, frameAtElapsedMs) }
    }

    override fun captureTap(provider: Provider): TapId? = captureTapsByProvider[provider]

    override fun done() = requestStop()

    override fun stop() = requestStop()

    override fun captureStarted(): Boolean = captureGate.begin()

    override fun captureEnded() = captureGate.end()

    // ------------------------------------------------------------------ RideWatchSink

    override fun onRideAppPost(post: PostSource) = enqueue { pipeline.onPosted(post) }

    override fun onRideAppRemoval(removal: RemovalSource) = enqueue { pipeline.onRemoved(removal) }

    override fun onListenerConnected(active: List<PostSource>) = enqueue { pipeline.onListenerConnected(active) }

    override fun onListenerDisconnected() = enqueue { pipeline.onListenerDisconnected() }

    // ------------------------------------------------------------------ service, outbox, erase

    /** Fires due guardian timers and reminders (the session service calls this every second). */
    fun tick() {
        start()
        scope.launch {
            restoredSignal.await()
            fireDue()
            drain()
        }
    }

    /** REMINDER / FOLLOWUP_CHECK row came due: fire the guardian's due work (heads-up + bubble pulse, +60 s check). */
    suspend fun onOutboxWake(item: OutboxItem): OutboxOutcome {
        awaitRestored()
        return withContext(guardianDispatcher) {
            val known = item.legId != null && guardian.state.legs.containsKey(item.legId)
            if (!known) return@withContext OutboxOutcome.Dead
            fireDue()
            drain()
            OutboxOutcome.Done
        }
    }

    /** OPEN_NEXT_APP: open the app for a leg that still needs it, under the background-start rule. */
    suspend fun openFromOutbox(item: OutboxItem): OutboxOutcome {
        awaitRestored()
        val request = OpenRequest.decode(item.payload) ?: return OutboxOutcome.Dead
        val wanted = withContext(guardianDispatcher) {
            val rec = guardian.state.legs[request.legId] ?: return@withContext false
            when (request.purpose) {
                OpenPurpose.CANCEL -> rec.leg.state == RideState.CANCEL_GUIDED && !rec.dismissed
                OpenPurpose.PAY, OpenPurpose.VIEW -> true
            }
        }
        if (wanted) {
            appOpener.open(request.legId, request.provider, request.purpose)
            // The leg may have settled while the card was being posted.
            withContext(guardianDispatcher) { appOpener.withdrawSettled(::stillGuided) }
        }
        return OutboxOutcome.Done
    }

    /** Erase: forget the session in memory and remove its notifications (Room is wiped by the platform). */
    suspend fun wipe() {
        awaitRestored()
        withContext(guardianDispatcher) {
            notifier.cancelEverything()
            install(Guardian(coreClock, fees, guardianConfig))
            ui = GuardianUiState.EMPTY
            publishedUi = GuardianUiState.EMPTY
            _ui.value = ui
            stopRequested = false
            sessionRow = null
            lastOpenedLegId = null
            pendingOpens.clear()
            captureTaps.clear()
            keepSearchingPreference.clear()
            publishStatus()
            // The new pipeline starts blind: re-read the shade so it knows which ride posts are still there (a +60 s
            // check would otherwise always assume the ride is live until the listener reconnects).
            watchStatus.requestReread()
        }
    }

    // ------------------------------------------------------------------ internals

    private fun begin(spec: SessionSpec, demo: Boolean) {
        start()
        starting.incrementAndGet()
        _status.update { it.copy(starting = true) }
        enqueue {
            try {
                beginSession(spec, demo)
            } finally {
                starting.decrementAndGet()
            }
        }
    }

    private fun beginSession(spec: SessionSpec, demo: Boolean) {
        val g = guardian
        val current = g.state.session
        if (current != null && !current.closed) {
            if (!g.closeSession()) {
                // I8: the previous session still has bookings to cancel; it keeps running.
                startRefused = true
                notifier.post(presenter().startBlocked())
                return
            }
            closedAtUtc = clock.wallMs()
            requestPersist()
        }
        val providers = spec.providers.distinct().filter { it.isDemo == demo && (!demo || !it.isNetwork) }
        require(providers.isNotEmpty()) { "a session needs at least one provider" }
        g.startSession(spec.sessionId, providers, spec.keepSearching, demo)
        pipeline.demoSessionActive = demo
        startRefused = false
        stopRequested = false
        closedAtUtc = null
        lastOpenedLegId = null
        captureTaps.clear()
        ui = GuardianUiState.EMPTY.copy(noticeSeq = ui.noticeSeq)
        sessionRow = SessionEntity(
            id = spec.sessionId,
            createdAtUtc = clock.wallMs(),
            pickup = PlaceColumns.of(spec.pickup),
            drop = PlaceColumns.of(spec.drop),
            selectedApps = providers,
            vehicleClasses = spec.vehicleClasses,
            includeOndc = Provider.ONDC in providers,
            status = SessionStatus.ACTIVE,
            keptLegId = null,
            closedAtUtc = null,
        )
    }

    private fun applyUserAction(action: UserAction) {
        // I6: a capture is accepted only within 2 s of the tap that asked for it, so remember that tap for the
        // capture area (Check screen on a leg, on a MAYBE prompt, and Show me).
        when (action) {
            is UserAction.CheckScreen -> captureTaps[action.legId] = action.tapId
            is UserAction.ShowMe -> captureTaps[action.legId] = action.tapId
            is UserAction.AnswerPrompt -> if (action.answer == Answer.CHECK_SCREEN) {
                guardian.state.prompts.firstOrNull { it.promptId == action.promptId }?.let { captureTaps[it.legId] = action.tapId }
            }
            // The user kept the ONDC ride after seeing the fee: the soft cancel lapses on the network side too.
            is UserAction.OndcKeepRide -> bindings.ondc?.let { sink -> onMain { sink.keepRide(action.legId) } }
            else -> Unit
        }
        _userActions.tryEmit(action)
        guardian.onUserAction(action)
    }

    /**
     * "Keep searching" (off by default). The tap flips what the user sees: the open session's setting, or the saved
     * preference when there is none. It applies to the open session only while nothing is booked there yet (no
     * driver sheet can depend on it); otherwise it is saved for the next comparison.
     */
    private fun toggleKeepSearching() = enqueue {
        val g = guardian
        val session = g.state.session?.takeIf { !it.closed }
        val value = !(session?.keepSearching ?: keepSearchingPreference.enabled)
        keepSearchingPreference.enabled = value
        if (session == null || g.state.legs.values.any { it.leg.isBooked }) return@enqueue
        // Same session, same legs (ids, states and the user's earlier taps stay as they are).
        g.state.session = session.copy(keepSearching = value)
    }

    private suspend fun restore() {
        val active = try {
            withContext(ioDispatcher) { persistence.loadActive() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report(e)
            emptyList()
        }
        val newest = active.firstOrNull()
        val journal = newest?.let { (snapshot, _) ->
            try {
                withContext(ioDispatcher) { persistence.journal(snapshot.sessionId) }
            } catch (e: Exception) {
                report(e)
                null
            }
        }
        val restored = newest?.let { (snapshot, _) ->
            try {
                Guardian.restore(snapshot.json, coreClock, fees, guardianConfig)
            } catch (e: Exception) {
                report(e)
                null
            }
        }
        install(restored ?: Guardian(coreClock, fees, guardianConfig))
        if (restored != null) {
            val row = newest.second
            sessionRow = row
            closedAtUtc = row?.closedAtUtc
            ui = GuardianUiState.decode(journal)?.takeIf { it.sessionId == restored.state.session?.sessionId } ?: GuardianUiState.EMPTY
            restored.resume()
        }
        val stale = active.drop(if (restored != null) 1 else 0).map { it.first.sessionId }
        if (stale.isNotEmpty()) {
            try {
                withContext(ioDispatcher) { persistence.closeStale(stale, clock.wallMs()) }
            } catch (e: Exception) {
                report(e)
            }
        }
        restoredSignal.complete(Unit)
        dirty = true
        drain()
        // Calls made while restoring, in the order they were made.
        runCalls()
        // I9: re-read the shade so posts that arrived while the process was dead are processed.
        if (restored != null) watchStatus.requestReread()
    }

    private fun install(g: Guardian) {
        // A replaced guardian (erase) must not feed late classifier results into the new one's UI.
        if (::guardian.isInitialized) guardian.effectListener = null
        guardian = g
        g.effectListener = { effect ->
            pendingEffects.addLast(effect)
            scheduleDrain()
        }
        val p = RideWatchPipeline(
            clock = coreClock,
            scope = scope,
            legs = g,
            sink = g,
            templates = config::templates,
            classifier = classifierFactory { g.activeSessionId },
            masker = masker,
        )
        pipeline = p
        // Without a listener RideMate cannot tell, so a follow-up check assumes the ride may still be live.
        g.ongoingNotifications = OngoingNotifications { pkg -> if (p.listenerConnected) p.hasOngoingRide(pkg) else true }
        p.demoSessionActive = g.state.session?.let { it.demo && !it.closed } == true
    }

    /**
     * Applies [block] on the guardian thread, after the restore and in call order. The block joins [calls] as soon as
     * its coroutine runs (launches on the single guardian thread run in launch order), so a call made before the
     * restore finished can never be overtaken by one made after it.
     */
    private fun enqueue(block: () -> Unit) {
        start()
        scope.launch {
            calls.addLast(block)
            runCalls()
        }
    }

    private fun runCalls() {
        if (runningCalls || !restoredSignal.isCompleted) return
        runningCalls = true
        try {
            while (true) {
                val block = calls.removeFirstOrNull() ?: break
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    report(e)
                }
                dirty = true
                drain()
            }
        } finally {
            runningCalls = false
        }
    }

    private fun scheduleDrain() {
        if (draining || drainScheduled) return
        drainScheduled = true
        scope.launch {
            drainScheduled = false
            drain()
        }
    }

    private fun drain() {
        if (draining || !restoredSignal.isCompleted) return
        draining = true
        try {
            while (true) {
                val effect = pendingEffects.removeFirstOrNull() ?: break
                dirty = true
                handle(effect)
            }
            if (dirty) {
                dirty = false
                afterStep()
            }
        } finally {
            draining = false
        }
    }

    private fun fireDue() {
        val due = guardian.nextDueElapsedMs() ?: return
        if (due <= clock.elapsedRealtimeMs()) {
            guardian.tick()
            dirty = true
        }
    }

    private fun handle(effect: GuardianEffect) {
        ui = GuardianUiReducer.apply(ui, effect, clock.wallMs())
        _effects.tryEmit(effect)
        when (effect) {
            // Demo providers have no app and no screen to capture: Try the demo shows its bundled screens instead.
            is GuardianEffect.OpenApp -> if (!effect.provider.isDemo) {
                pendingOpens += OpenRequest.of(effect.legId, effect.provider, effect.purpose, effect.tapId)
            }
            is GuardianEffect.RequestCapture -> if (!effect.provider.isDemo) {
                val tap = captureTaps[effect.legId]
                bindings.capture?.let { capture -> onMain { capture.request(effect.legId, effect.provider, effect.reason, tap) } }
            }
            is GuardianEffect.OndcCommand -> bindings.ondc?.let { sink -> onMain { sink.execute(effect) } }
            is GuardianEffect.CompareAgain -> bindings.compareAgain?.let { handler -> onMain { handler.compareAgain(effect.sessionId) } }
            is GuardianEffect.Reminder -> if (effect.bubblePulse) onMain { bindings.bubble.pulse() }
            is GuardianEffect.CancelCard -> onMain { bindings.bubble.showCancelCard(effect) }
            is GuardianEffect.Banner -> if (effect.key in CHAIN_LEG_CLOSED) onMain { bindings.bubble.clearCancelCard() }
            else -> Unit
        }
    }

    private fun afterStep() {
        val g = guardian
        val session = g.state.session
        if (session != null && !session.closed && !g.needsForegroundService && (stopRequested || keptLegPaid(g.state))) {
            if (g.closeSession()) {
                closedAtUtc = clock.wallMs()
                pipeline.demoSessionActive = false
                onMain { bindings.bubble.clearCancelCard() }
            }
        }
        if (g.state.session?.closed != false) stopRequested = false
        val stopBlocked = stopRequested && g.needsForegroundService
        ui = GuardianUiReducer.reconcile(ui, g.state, clock.elapsedRealtimeMs(), clock.wallMs(), nextReminderWallMs(g.state), stopBlocked)
        _ui.value = ui
        try {
            notifier.sync(publishedUi, ui)
        } catch (e: RuntimeException) {
            report(e)
        }
        publishedUi = ui
        legIdsByProvider = g.state.legs.values.associate { it.provider to it.legId }
        captureTapsByProvider = captureTaps.mapNotNull { (legId, tap) -> g.state.legs[legId]?.let { it.provider to tap } }.toMap()
        publishStatus()
        maybeStartService()
        appOpener.withdrawSettled(::stillGuided)
        requestPersist()
    }

    private fun publishStatus() {
        val state = guardian.state
        val session = state.session
        _status.value = GuardianStatus(
            restored = true,
            starting = starting.get() > 0,
            sessionId = session?.sessionId,
            demo = session?.demo == true,
            sessionOpen = session != null && !session.closed,
            needsForegroundService = guardian.needsForegroundService,
            stopRequested = stopRequested,
            bookedLegs = state.legs.values.count { it.leg.isBooked },
            pendingExtras = pendingExtras(state).size,
        )
    }

    private fun maybeStartService() {
        val status = _status.value
        if (!status.shouldRunService || status.sessionId == null || !status.sessionOpen && !status.needsForegroundService) {
            withdrawResume()
            return
        }
        if (sessionActivity.active.value) return
        val now = clock.elapsedRealtimeMs()
        val last = lastServiceStartAttempt
        if (last != null && now - last < SERVICE_RETRY_MS) return
        lastServiceStartAttempt = now
        val ok = try {
            serviceStarter.ensureRunning()
        } catch (e: RuntimeException) {
            report(e)
            false
        }
        // Android refused a start from the background: the user's tap brings the guardian back.
        if (!ok) {
            notifier.post(presenter().resume())
            resumePosted = true
        }
    }

    private fun withdrawResume(force: Boolean = false) {
        if (!resumePosted && !force) return
        resumePosted = false
        notifier.cancel(GuardianPresenter.RESUME_ID)
    }

    /** An "Open <App> to cancel" card is wanted only while that leg is the one being cancelled. */
    private fun stillGuided(legId: String): Boolean =
        guardian.state.legs[legId]?.let { it.leg.state == RideState.CANCEL_GUIDED && !it.dismissed } == true

    private fun requestPersist() {
        val g = guardian
        val session = g.state.session ?: return
        val row = sessionRow?.let { base ->
            base.copy(
                status = if (session.closed) SessionStatus.CLOSED else SessionStatus.ACTIVE,
                keptLegId = session.keptLegId,
                closedAtUtc = if (session.closed) (closedAtUtc ?: clock.wallMs()) else null,
            )
        }
        sessionRow = row
        val request = PersistRequest(
            version = ++version,
            sessionId = session.sessionId,
            session = row,
            legs = (g.state.legs.values + g.state.retiredLegs).map { legEntity(session.sessionId, it, g.state) },
            snapshotJson = g.snapshot(),
            evidence = evidenceRows(g.state),
            desiredOutbox = desiredOutbox(g.state),
            opens = pendingOpens.toList(),
            uiJournal = GuardianUiState.encode(ui),
        )
        pendingOpens.clear()
        requestedVersion.set(request.version)
        persistQueue.trySend(request)
    }

    private suspend fun persistLoop() {
        for (first in persistQueue) {
            val batch = mutableListOf(first)
            while (true) batch += persistQueue.tryReceive().getOrNull() ?: break
            val merged = mutableListOf<PersistRequest>()
            for (request in batch) {
                val last = merged.lastOrNull()
                if (last != null && last.sessionId == request.sessionId) merged[merged.lastIndex] = last.mergedWith(request) else merged += request
            }
            var opened = false
            for (request in merged) {
                try {
                    persistence.write(request)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    report(e)
                }
                opened = opened || request.opens.isNotEmpty()
            }
            if (opened) {
                try {
                    outboxDriver.tick()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    report(e)
                }
            }
            persistedVersion.value = merged.last().version
        }
    }

    // ------------------------------------------------------------------ mapping

    private fun legEntity(sessionId: String, rec: LegRecord, state: GuardianState): LegEntity {
        val leg = rec.leg
        val driver = leg.driver
        val etaShown = driver.etaMin != null && driver.etaSource.isAdmissible
        return LegEntity(
            id = rec.legId,
            sessionId = sessionId,
            provider = rec.provider,
            state = leg.state,
            generation = leg.generation,
            sbnKey = leg.marks.keys.lastOrNull(),
            etaMin = if (etaShown) driver.etaMin else null,
            etaSource = if (etaShown) driver.etaSource else EtaSource.NONE,
            distanceText = driver.distanceMeters?.takeIf { driver.distanceSource.isAdmissible }?.let { DistanceText.format(it) },
            plateHash = driver.plateHash,
            tAssignedElapsed = driver.assignedAtElapsedMs?.minus(state.elapsedOffsetMs),
            feeRuleId = rec.provider.packageName,
            kept = rec.role == LegRole.KEPT,
            dismissed = rec.dismissed,
            lastEventAt = leg.lastEvidenceWallMs,
        )
    }

    private fun evidenceRows(state: GuardianState): List<EvidenceRow> {
        val frameNow = clock.elapsedRealtimeMs() + state.elapsedOffsetMs
        val wallNow = clock.wallMs()
        val proven = state.evidence.map { EvidenceRow(it.legId, it.rung, wallNow - (frameNow - it.atElapsedMs)) }
        // Rung 4 ("I cancelled it") is recorded as unverified evidence when the leg enters USER_SAYS_CANCELLED.
        val said = state.legs.values.filter { it.leg.state == RideState.USER_SAYS_CANCELLED }.map { rec ->
            EvidenceRow(rec.legId, EvidenceRung.USER, wallNow - (frameNow - rec.leg.enteredAtElapsedMs))
        }
        return proven + said
    }

    private fun desiredOutbox(state: GuardianState): List<DesiredRow> {
        val offset = state.elapsedOffsetMs
        val out = mutableListOf<DesiredRow>()
        val reminders = state.reminders
        val anchorLeg = pendingExtras(state).firstOrNull()
        if (reminders != null && anchorLeg != null) {
            out += DesiredRow(OutboxKind.REMINDER, anchorLeg.legId, reminders.anchorElapsedMs + guardianConfig.reminders.offsetMs(reminders.sent) - offset)
        }
        state.timers.filter { it.kind == TimerKind.FOLLOW_UP_CHECK }.forEach {
            out += DesiredRow(OutboxKind.FOLLOWUP_CHECK, it.legId, it.dueElapsedMs - offset)
        }
        return out
    }

    private fun nextReminderWallMs(state: GuardianState): Long? {
        val reminders = state.reminders ?: return null
        if (pendingExtras(state).isEmpty()) return null
        val dueOnClock = reminders.anchorElapsedMs + guardianConfig.reminders.offsetMs(reminders.sent) - state.elapsedOffsetMs
        return clock.wallMs() + (dueOnClock - clock.elapsedRealtimeMs())
    }

    private fun pendingExtras(state: GuardianState): List<LegRecord> =
        state.legs.values.filter { it.role == LegRole.EXTRA && !it.dismissed && !it.leg.state.isTerminal }

    private fun keptLegPaid(state: GuardianState): Boolean =
        state.session?.keptLegId?.let { state.legs[it]?.leg?.state == RideState.PAY_HANDOFF } == true

    /**
     * The open session's leg for [provider]. Bubble commands never touch a closed session: RideWatch no longer watches
     * its legs, so a "Booked here" there would be a booking nobody guards.
     */
    private fun legOf(provider: Provider): String? {
        if (guardian.state.session?.closed != false) return null
        return guardian.state.legs.values.firstOrNull { it.provider == provider }?.legId
    }

    private fun firstUnbookedLeg(): String? {
        if (guardian.state.session?.closed != false) return null
        return guardian.state.legs.values.firstOrNull { !it.leg.isBooked && !it.leg.state.isTerminal && !it.leg.isNetwork }?.legId
    }

    private fun onMain(block: () -> Unit) = mainExecutor.execute {
        try {
            block()
        } catch (e: RuntimeException) {
            report(e)
        }
    }

    private fun newTap(): TapId = TapId(UUID.randomUUID().toString())

    private fun report(e: Throwable) {
        crash?.recordNonFatal(e, CrashSite.GUARDIAN)
    }

    companion object {
        val GUARDIAN_OUTBOX_KINDS: Set<OutboxKind> = setOf(OutboxKind.REMINDER, OutboxKind.FOLLOWUP_CHECK, OutboxKind.OPEN_NEXT_APP)
        private val CHAIN_LEG_CLOSED = setOf(
            UiKey.BANNER_CANCEL_CONFIRMED, UiKey.BANNER_ENDED_BY_APP, UiKey.BANNER_LEG_DISMISSED, UiKey.BANNER_ALL_CANCELS_DONE,
        )
        private const val SERVICE_RETRY_MS = 15_000L
        private const val IDLE_ROUNDS = 3
        private const val EVENT_BUFFER = 1_024

        /** The leg id core's guardian gives [provider] in session [sessionId] (first round). */
        fun legIdOf(sessionId: String, provider: Provider): String = Guardian.legIdOf(sessionId, provider)
    }
}

/** The platform outbox handler for the guardian's rows (REMINDER, FOLLOWUP_CHECK, OPEN_NEXT_APP). */
class GuardianOutboxHandler(private val runtime: GuardianRuntime, private val watch: RideWatchStatus) : OutboxHandler {
    override suspend fun handle(item: OutboxItem): OutboxOutcome = when (item.kind) {
        OutboxKind.REMINDER -> runtime.onOutboxWake(item)
        OutboxKind.FOLLOWUP_CHECK -> {
            // The +60 s check reads the shade: nudge a dropped listener back first.
            if (!watch.connected.value) watch.requestRebind()
            runtime.onOutboxWake(item)
        }
        OutboxKind.OPEN_NEXT_APP -> runtime.openFromOutbox(item)
        OutboxKind.ERASE_SERVER -> OutboxOutcome.Dead
    }
}

/** Adds and drops the mediaProjection foreground-service type around fare capture (the service implements it). */
interface CaptureGate {
    fun begin(): Boolean
    fun end()
}
