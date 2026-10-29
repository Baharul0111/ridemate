package app.ridemate.core.guardian

import app.ridemate.core.config.NotificationEvent
import app.ridemate.core.model.EtaSource
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import app.ridemate.core.state.AlertKind
import app.ridemate.core.state.Answer
import app.ridemate.core.state.CapturePurpose
import app.ridemate.core.state.CaptureVerdict
import app.ridemate.core.state.Chain
import app.ridemate.core.state.ChainStep
import app.ridemate.core.state.DriverData
import app.ridemate.core.state.EventType
import app.ridemate.core.state.LegState
import app.ridemate.core.state.Lifecycle
import app.ridemate.core.state.LifecycleStep
import app.ridemate.core.state.LogReason
import app.ridemate.core.state.PromptKind
import app.ridemate.core.state.Protocol
import app.ridemate.core.state.ProtocolKind
import app.ridemate.core.state.ProviderUpdate
import app.ridemate.core.state.RideEvent
import app.ridemate.core.state.SignalSource
import app.ridemate.core.state.StateOutput
import app.ridemate.core.state.Statechart
import app.ridemate.core.state.TapId
import app.ridemate.core.state.Timeout
import app.ridemate.core.state.TimeoutKind
import app.ridemate.core.state.Transition
import app.ridemate.core.state.UserAnswer
import app.ridemate.core.state.WithdrawReason
import app.ridemate.core.state.isAdmissible
import app.ridemate.core.state.promptKind
import app.ridemate.core.time.Clock
import app.ridemate.core.time.Moment
import app.ridemate.core.watch.Disposition
import app.ridemate.core.watch.LegDirectory
import app.ridemate.core.watch.LegView
import app.ridemate.core.watch.OngoingNotifications
import app.ridemate.core.watch.RideEventSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GuardianConfig(
    val repromptMs: Long = 120_000L,
    val driverCancelledWaitMs: Long = 180_000L,
    val followUpMs: Long = 60_000L,
    val twoDriversWindowMs: Long = 10_000L,
    /** I6: a capture frame must follow its tap within this window (demo frames are exempt). */
    val captureTapWindowMs: Long = 2_000L,
    val reminders: ReminderSchedule = ReminderSchedule(),
    val betterRide: BetterRideRule = BetterRideRule(),
)

/** One applied event, for the debug log and tests (ids and enums only). */
data class TransitionLogEntry(
    val atElapsedMs: Long,
    val legId: String,
    val eventType: EventType,
    val from: RideState,
    val to: RideState,
    val generationFrom: Int,
    val generationTo: Int,
    val outputs: List<StateOutput>,
)

/**
 * The ride guardian (§4.6–§4.8): one session's legs, the first-driver sheet, the guided cancel chain, the evidence
 * ladder, reminders, better-ride and same-driver checks. It never acts inside a ride app: every output is a
 * [GuardianEffect] for the app to render, and every book/cancel/switch links to a user tap (I1).
 *
 * Confinement: call it from one dispatcher (the one RideWatch uses). Persist [snapshot] after every call.
 */
class Guardian(
    private val clock: Clock,
    private val feeEstimator: FeeEstimator,
    private val config: GuardianConfig = GuardianConfig(),
    initialState: GuardianState = GuardianState(),
) : LegDirectory, RideEventSink {

    /** Wired to RideWatch after construction (RideWatch itself needs the guardian as its [LegDirectory]). */
    var ongoingNotifications: OngoingNotifications = OngoingNotifications.NONE

    /** Receives every effect as it is emitted (the app renders them). */
    var effectListener: ((GuardianEffect) -> Unit)? = null

    private var s: GuardianState = initialState
    private val pending = mutableListOf<GuardianEffect>()
    private val effects = mutableListOf<TimedEffect>()
    private val transitions = mutableListOf<TransitionLogEntry>()
    private var depth = 0
    private val schedule = MutableStateFlow(0L)

    /** Bumped after every call; the ticker re-reads [nextDueElapsedMs] when it changes. */
    val scheduleVersion: StateFlow<Long> = schedule.asStateFlow()
    val effectLog: List<TimedEffect> get() = effects
    val transitionLog: List<TransitionLogEntry> get() = transitions

    /** Read-only view for the invariant checkers. */
    val state: GuardianState get() = s

    /** I8 / M7: true while any extra leg is non-terminal and not dismissed, or a follow-up check is pending. */
    val needsForegroundService: Boolean get() = computeForegroundRequired()

    // ------------------------------------------------------------------ session

    fun startSession(
        sessionId: String,
        providers: List<Provider>,
        keepSearching: Boolean = false,
        demo: Boolean = false,
    ): List<GuardianEffect> = step {
        check(pendingExtras().isEmpty()) { "the previous session still has bookings to cancel" }
        require(providers.isNotEmpty() && providers.distinct().size == providers.size) { "one leg per provider" }
        require(providers.all { it.isDemo == demo }) { "demo providers never mix with live legs" }
        val now = now()
        s = GuardianState(nextId = s.nextId, elapsedOffsetMs = s.elapsedOffsetMs)
        s.session = SessionState(sessionId, demo, keepSearching, now.elapsedMs)
        providers.forEach { provider -> addLeg(sessionId, provider, round = 1, now) }
    }

    /** Closes the session; refused while any booking still needs cancelling or checking (I8). */
    fun closeSession(): Boolean {
        val session = s.session ?: return true
        if (computeForegroundRequired()) return false
        session.closed = true
        schedule.value++
        return true
    }

    /**
     * Ends a Try-the-demo session at once. Its bookings are fictional, so every leg still owed a cancel is dismissed,
     * its checks and reminders are dropped, open sheets and prompts are withdrawn, and the session closes. Live
     * sessions are never ended this way (I8): they close only through [closeSession].
     */
    fun endDemoSession(): List<GuardianEffect> = step {
        val session = s.session ?: return@step
        if (!session.demo || session.closed) return@step
        activeLegs().filter { !it.leg.state.isTerminal }.forEach { it.dismissed = true }
        s.prompts.toList().forEach { prompt ->
            s.prompts.remove(prompt)
            emit(GuardianEffect.PromptWithdrawn(prompt.promptId, prompt.legId, UiKey.PROMPT_ANSWERED))
        }
        s.sheets.toList().forEach { sheet -> closeSheet(sheet, UiKey.SHEET_CLOSED) }
        s.timers.clear()
        s.reminders = null
        s.chain = null
        session.closed = true
        schedule.value++
    }

    /**
     * Remote Config `better_ride_enabled` went off (goal 22): this session stops keeping searching. Better-ride offers end
     * as under the §4.8 stop rule (open better-ride sheets close, and a booking still searching under "Keep searching"
     * gets "Cancel the others?"), an open "Cancel the others?" loses its Keep searching button, and a lost kept leg no
     * longer re-arms the offers. Bookings, roles already chosen by a tap and every other sheet stay as they are.
     */
    fun stopKeepSearching(): List<GuardianEffect> = step {
        val session = s.session?.takeIf { it.keepSearching && !it.closed } ?: return@step
        stopBetterRide(UiKey.SHEET_CLOSED)
        s.session = session.copy(keepSearching = false)
        for (sheet in s.sheets.filter { it.kind == SheetKind.CANCEL_OTHERS && UiAction.KEEP_SEARCHING in it.actions }) {
            val updated = sheet.copy(actions = sheet.actions - UiAction.KEEP_SEARCHING)
            s.sheets[s.sheets.indexOf(sheet)] = updated
            val subjects = updated.bindings.mapNotNull { leg(it.legId) }
            val others = activeLegs().filter { o -> subjects.none { it === o } && o.leg.isBooked && !o.leg.state.isTerminal && !o.dismissed }
            emit(driverSheetEffect(updated, subjects, others))
        }
    }

    fun markOpened(legId: String): List<GuardianEffect> = step { leg(legId)?.let { apply(it, Lifecycle(LifecycleStep.OPENED)) } }

    fun markQuoted(legId: String): List<GuardianEffect> = step { leg(legId)?.let { apply(it, Lifecycle(LifecycleStep.QUOTED)) } }

    /** The fare the user booked at (from the comparison list), so fee notes can say "up to 10% of ₹X". */
    fun recordBookedFare(legId: String, fareInr: Int) {
        leg(legId)?.bookedFareInr = fareInr
    }

    // ------------------------------------------------------------------ RideWatch side

    override val activeSessionId: String?
        get() = s.session?.takeIf { !it.closed }?.sessionId

    override fun legFor(packageName: String): LegView? {
        if (activeSessionId == null) return null
        val rec = s.legs.values.firstOrNull { it.provider.packageName == packageName } ?: return null
        return LegView(rec.legId, rec.leg.state, rec.leg.generation, rec.leg.isBooked)
    }

    override fun onRideEvent(legId: String, event: RideEvent): Disposition {
        var disposition = Disposition.LOGGED
        step {
            val rec = leg(legId) ?: return@step
            disposition = dispositionOf(apply(rec, event))
        }
        return disposition
    }

    /** ONDC protocol data mirrored from Firestore. */
    fun onProtocolEvent(legId: String, event: Protocol): List<GuardianEffect> = step { leg(legId)?.let { apply(it, event) } }

    /**
     * Rung 3's capture half: OCR of a user-tapped capture checked against the app's cancelled keywords.
     * I6: the frame must follow a recorded tap within [GuardianConfig.captureTapWindowMs] (demo frames exempt).
     */
    fun onCaptureVerdict(legId: String, cancelledKeywordsFound: Boolean, tapId: TapId, frameAtElapsedMs: Long): List<GuardianEffect> =
        step {
            val rec = leg(legId) ?: return@step
            if (acceptFrame(rec, tapId, frameAtElapsedMs)) apply(rec, CaptureVerdict(cancelledKeywordsFound, tapId))
        }

    /** A driver ETA/distance read through the VerificationGate from a user-tapped capture (VERIFIED). */
    fun onVerifiedDriverReading(
        legId: String,
        etaMin: Int?,
        distanceMeters: Int?,
        tapId: TapId,
        frameAtElapsedMs: Long,
    ): List<GuardianEffect> = step {
        val rec = leg(legId) ?: return@step
        if (!acceptFrame(rec, tapId, frameAtElapsedMs)) return@step
        val event = if (rec.leg.underlyingState in LegState.DRIVER_STATES) {
            DriverData(etaMin, distanceMeters, EtaSource.VERIFIED)
        } else {
            ProviderUpdate(
                NotificationEvent.ASSIGNED, SignalSource.SCREEN_CHECK, etaMin = etaMin, distanceMeters = distanceMeters,
                postTimeWallMs = now().wallMs,
            )
        }
        apply(rec, event)
    }

    // ------------------------------------------------------------------ user taps

    fun onUserAction(action: UserAction): List<GuardianEffect> = step {
        s.taps += TapRecord(action.tapId, now().elapsedMs)
        when (action) {
            is UserAction.BookedHere -> bookedHere(action)
            is UserAction.AnswerPrompt -> answerPrompt(action)
            is UserAction.AnswerSheet -> answerSheet(action)
            is UserAction.ICancelledIt -> answerLeg(action.legId, Answer.I_CANCELLED_IT, action.tapId)
            is UserAction.CheckScreen -> checkScreen(action)
            is UserAction.ShowMe -> showMe(action)
            is UserAction.OpenApp -> openApp(action)
            is UserAction.DismissLeg -> dismissLeg(action)
            is UserAction.CompareAgain -> compareAgain(action)
            is UserAction.Pay -> pay(action)
            is UserAction.OndcBook -> ondcBook(action)
            is UserAction.OndcCancel -> ondcCancel(action)
            is UserAction.OndcConfirmCancel -> ondcConfirmCancel(action)
            is UserAction.OndcKeepRide -> ondcKeepRide(action)
        }
    }

    private fun bookedHere(a: UserAction.BookedHere) {
        val rec = leg(a.legId) ?: return reject(a.tapId)
        val t = apply(rec, UserAnswer(Answer.BOOKED_HERE, a.tapId))
        if (t.after.state == RideState.BOOK_REQUESTED && t.stateChanged) record(ActionKind.BOOKED_HERE, rec, a.tapId)
        if (t.logReason == LogReason.ANSWER_NOT_APPLICABLE) reject(a.tapId)
    }

    private fun answerPrompt(a: UserAction.AnswerPrompt) {
        val prompt = s.prompts.firstOrNull { it.promptId == a.promptId } ?: return reject(a.tapId)
        val rec = leg(prompt.legId) ?: return reject(a.tapId)
        if (rec.leg.generation != prompt.generation || rec.leg.state.promptKind() != prompt.kind) return reject(a.tapId)
        val session = s.session ?: return reject(a.tapId)
        // J9: "Yes, cancel the others" on MAYBE_ASSIGNED is itself the Yes for the chain; no second sheet.
        val startsChain = prompt.kind == PromptKind.MAYBE_ASSIGNED && a.answer == Answer.YES && session.keptLegId == null
        if (startsChain) makeKept(rec)
        val t = apply(rec, UserAnswer(a.answer, a.tapId))
        if (t.logReason == LogReason.ANSWER_NOT_APPLICABLE) {
            if (startsChain) unKeep(rec)
            return reject(a.tapId)
        }
        if (startsChain) {
            if (rec.leg.state in LegState.DRIVER_STATES) startChain(rec, a.tapId) else unKeep(rec)
        }
    }

    private fun answerSheet(a: UserAction.AnswerSheet) {
        val sheet = s.sheets.firstOrNull { it.sheetId == a.sheetId } ?: return reject(a.tapId)
        val session = s.session ?: return reject(a.tapId)
        when (sheet.kind) {
            SheetKind.CANCEL_OTHERS -> {
                val binding = sheet.bindings.single()
                val rec = leg(binding.legId) ?: return reject(a.tapId)
                when (a.choice) {
                    SheetChoice.YES_CANCEL_OTHERS -> {
                        // I10: re-checked on Yes.
                        if (!bindingValid(binding)) return withdrawStale(sheet, rec, a.tapId)
                        closeSheet(sheet, key = null)
                        makeKept(rec)
                        startChain(rec, a.tapId)
                    }
                    SheetChoice.KEEP_SEARCHING -> {
                        if (UiAction.KEEP_SEARCHING !in sheet.actions || !session.keepSearching) return reject(a.tapId)
                        if (!bindingValid(binding)) return withdrawStale(sheet, rec, a.tapId)
                        closeSheet(sheet, key = null)
                        makeKept(rec)
                        cancellableOthers(rec).forEach { it.role = LegRole.CANDIDATE }
                        announceRide(rec)
                        scheduleBetterRideStop(rec)
                    }
                    SheetChoice.NOT_NOW -> {
                        s.notNowDrivers += bindingKey(binding)
                        closeSheet(sheet, key = null)
                    }
                    else -> reject(a.tapId)
                }
            }
            SheetKind.TWO_DRIVERS, SheetKind.SAME_DRIVER -> when (a.choice) {
                SheetChoice.PICK -> {
                    val binding = sheet.bindings.firstOrNull { it.legId == a.pickedLegId } ?: return reject(a.tapId)
                    val rec = leg(binding.legId) ?: return reject(a.tapId)
                    if (!bindingValid(binding)) return withdrawStale(sheet, rec, a.tapId)
                    closeSheet(sheet, key = null)
                    makeKept(rec)
                    startChain(rec, a.tapId)
                }
                SheetChoice.NOT_NOW -> {
                    sheet.bindings.forEach { s.notNowDrivers += bindingKey(it) }
                    closeSheet(sheet, key = null)
                }
                else -> reject(a.tapId)
            }
            SheetKind.BETTER_RIDE, SheetKind.SECOND_DRIVER -> {
                val (currentBinding, candidateBinding) = sheet.bindings
                val current = leg(currentBinding.legId) ?: return reject(a.tapId)
                val candidate = leg(candidateBinding.legId) ?: return reject(a.tapId)
                val valid = bindingValid(currentBinding) && bindingValid(candidateBinding) && session.keptLegId == current.legId
                when (a.choice) {
                    SheetChoice.SWITCH -> {
                        if (UiAction.SWITCH !in sheet.actions) return reject(a.tapId)
                        // I4: nothing switches without this Yes, on a sheet bound to the current generations.
                        if (!valid) return withdrawStale(sheet, if (bindingValid(currentBinding)) candidate else current, a.tapId)
                        s.switches += SwitchRecord(
                            sheet.sheetId, sheet.kind, current.legId, candidate.legId, sheet.bindings,
                            listOf(current, candidate).map { SheetBinding(it.legId, it.leg.generation) }, a.tapId, now().elapsedMs,
                        )
                        record(ActionKind.SWITCH, candidate, a.tapId)
                        closeSheet(sheet, key = null)
                        current.role = LegRole.NONE
                        makeKept(candidate)
                        announceRide(candidate)
                        enqueueExtras(listOf(current), a.tapId)
                        scheduleBetterRideStop(candidate)
                    }
                    SheetChoice.KEEP_CURRENT -> {
                        if (!bindingValid(candidateBinding)) return withdrawStale(sheet, candidate, a.tapId)
                        closeSheet(sheet, key = null)
                        candidate.role = LegRole.NONE
                        enqueueExtras(listOf(candidate), a.tapId)
                    }
                    SheetChoice.NOT_NOW -> closeSheet(sheet, key = null)
                    else -> reject(a.tapId)
                }
            }
        }
    }

    private fun answerLeg(legId: String, answer: Answer, tap: TapId) {
        val rec = leg(legId) ?: return reject(tap)
        val t = apply(rec, UserAnswer(answer, tap))
        if (t.logReason == LogReason.ANSWER_NOT_APPLICABLE) reject(tap)
    }

    private fun checkScreen(a: UserAction.CheckScreen) {
        val rec = leg(a.legId) ?: return reject(a.tapId)
        val t = apply(rec, UserAnswer(Answer.CHECK_SCREEN, a.tapId))
        if (t.logReason != LogReason.ANSWER_NOT_APPLICABLE) return
        // A driver leg: the capture reads a VERIFIED driver ETA/distance.
        if (rec.leg.state in LegState.DRIVER_STATES) emit(GuardianEffect.RequestCapture(rec.legId, rec.provider, CaptureReason.CHECK_SCREEN)) else reject(a.tapId)
    }

    private fun showMe(a: UserAction.ShowMe) {
        val rec = leg(a.legId) ?: return reject(a.tapId)
        if (rec.leg.state == RideState.CANCEL_GUIDED) {
            emit(GuardianEffect.RequestCapture(rec.legId, rec.provider, CaptureReason.SHOW_ME))
        } else {
            reject(a.tapId)
        }
    }

    private fun openApp(a: UserAction.OpenApp) {
        val rec = leg(a.legId) ?: return reject(a.tapId)
        val guidable = rec.leg.state in setOf(RideState.CANCEL_REQUESTED, RideState.CANCEL_UNCONFIRMED, RideState.USER_SAYS_CANCELLED)
        if (rec.role == LegRole.EXTRA && !rec.dismissed && guidable) {
            s.chain?.let { chain ->
                chain.queue.remove(rec.legId)
                chain.currentLegId?.takeIf { it != rec.legId }?.let { chain.queue.add(0, it) }
                chain.currentLegId = rec.legId
            }
            guide(rec, a.tapId)
        } else {
            emit(GuardianEffect.OpenApp(rec.legId, rec.provider, OpenPurpose.VIEW, a.tapId))
        }
    }

    private fun dismissLeg(a: UserAction.DismissLeg) {
        val rec = leg(a.legId) ?: return reject(a.tapId)
        if (rec.dismissed) return
        rec.dismissed = true
        record(ActionKind.DISMISS, rec, a.tapId)
        s.timers.removeAll { it.legId == rec.legId }
        s.prompts.filter { it.legId == rec.legId }.forEach { prompt ->
            s.prompts.remove(prompt)
            emit(GuardianEffect.PromptWithdrawn(prompt.promptId, rec.legId, UiKey.PROMPT_ANSWERED))
        }
        emit(GuardianEffect.Banner(rec.legId, rec.provider, UiKey.BANNER_LEG_DISMISSED))
        if (s.chain?.currentLegId == rec.legId) advanceChain()
        ensureReminders()
    }

    /** "Compare again (same trip)": legs the apps already ended get a fresh leg so they can be booked again. */
    private fun compareAgain(a: UserAction.CompareAgain) {
        val session = s.session ?: return reject(a.tapId)
        session.compareRound++
        val now = now()
        s.legs.values.filter { it.leg.state.isTerminal }.toList().forEach { old ->
            s.legs.remove(old.legId)
            s.retiredLegs += old
            s.timers.removeAll { it.legId == old.legId }
            addLeg(session.sessionId, old.provider, session.compareRound, now)
        }
        emit(GuardianEffect.CompareAgain(session.sessionId))
    }

    private fun pay(a: UserAction.Pay) {
        val rec = leg(a.legId) ?: return reject(a.tapId)
        val t = apply(rec, UserAnswer(Answer.PAY, a.tapId))
        if (t.after.state == RideState.PAY_HANDOFF && t.stateChanged) {
            record(ActionKind.PAY_HANDOFF, rec, a.tapId)
            emit(GuardianEffect.OpenApp(rec.legId, rec.provider, OpenPurpose.PAY, a.tapId))
        } else {
            reject(a.tapId)
        }
    }

    private fun ondcBook(a: UserAction.OndcBook) {
        val rec = leg(a.legId) ?: return reject(a.tapId)
        if (!rec.leg.isNetwork || rec.leg.state !in LegState.ONDC_PRE_CONFIRM) return reject(a.tapId)
        rec.ondcBookTap = a.tapId
        record(ActionKind.ONDC_BOOK, rec, a.tapId)
        emit(GuardianEffect.OndcCommand(rec.legId, OndcCommandKind.BOOK, a.tapId, a.itemId))
    }

    private fun ondcCancel(a: UserAction.OndcCancel) {
        val rec = leg(a.legId) ?: return reject(a.tapId)
        if (!rec.leg.isNetwork || rec.leg.state !in Statechart.ONDC_CANCELLABLE) return reject(a.tapId)
        rec.cancelTap = a.tapId
        record(ActionKind.ONDC_SOFT_CANCEL, rec, a.tapId)
        emit(GuardianEffect.OndcCommand(rec.legId, OndcCommandKind.SOFT_CANCEL, a.tapId))
    }

    private fun ondcConfirmCancel(a: UserAction.OndcConfirmCancel) {
        val rec = leg(a.legId) ?: return reject(a.tapId)
        val t = apply(rec, UserAnswer(Answer.ONDC_CONFIRM_CANCEL, a.tapId))
        if (t.after.state != RideState.CONFIRM_CANCEL || !t.stateChanged) return reject(a.tapId)
        record(ActionKind.ONDC_CONFIRM_CANCEL, rec, a.tapId)
        emit(GuardianEffect.OndcCommand(rec.legId, OndcCommandKind.CONFIRM_CANCEL, a.tapId))
    }

    private fun ondcKeepRide(a: UserAction.OndcKeepRide) {
        val rec = leg(a.legId) ?: return reject(a.tapId)
        val t = apply(rec, UserAnswer(Answer.ONDC_KEEP_RIDE, a.tapId))
        if (!t.stateChanged) return reject(a.tapId)
        // Keeping an extra ONDC ride is the user's call: stop reminding about it (logged as a dismissal).
        if (rec.role == LegRole.EXTRA && !rec.dismissed) {
            rec.dismissed = true
            record(ActionKind.DISMISS, rec, a.tapId)
            if (s.chain?.currentLegId == rec.legId) advanceChain()
            ensureReminders()
        }
    }

    // ------------------------------------------------------------------ timers

    /** Fires every due reminder and timer. Call when [nextDueElapsedMs] is reached (see [GuardianTicker]). */
    fun tick(): List<GuardianEffect> = step {
        repeat(MAX_FIRES_PER_TICK) {
            val now = now().elapsedMs
            val reminderDue = reminderDueAt()
            val timer = s.timers.minByOrNull { it.dueElapsedMs }
            val timerDue = timer?.dueElapsedMs
            when {
                reminderDue != null && reminderDue <= now && (timerDue == null || reminderDue <= timerDue) -> fireReminder()
                timer != null && timerDue!! <= now -> fireTimer(timer)
                else -> return@step
            }
        }
    }

    /** The next due time on the injected clock's own scale, or null when nothing is scheduled. */
    fun nextDueElapsedMs(): Long? {
        val due = listOfNotNull(reminderDueAt(), s.timers.minOfOrNull { it.dueElapsedMs }).minOrNull() ?: return null
        return due - s.elapsedOffsetMs
    }

    private fun reminderDueAt(): Long? {
        val r = s.reminders ?: return null
        return r.anchorElapsedMs + config.reminders.offsetMs(r.sent)
    }

    private fun fireReminder() {
        val r = s.reminders ?: return
        val legs = pendingExtras()
        if (legs.isEmpty()) {
            s.reminders = null
            return
        }
        val attempt = r.sent + 1
        emit(GuardianEffect.Reminder(attempt, legs.map { LegRef(it.legId, it.provider, it.leg.state) }, headsUp = true, bubblePulse = true))
        // After a restart a backlog collapses into this one reminder; the schedule then continues normally.
        r.sent = maxOf(attempt, config.reminders.nextIndexAfter(now().elapsedMs - r.anchorElapsedMs))
    }

    private fun fireTimer(timer: TimerRecord) {
        s.timers.remove(timer)
        val rec = leg(timer.legId) ?: return
        if (timer.kind == TimerKind.BETTER_RIDE_STOP) return checkBetterRideStop()
        if (rec.leg.stateSeq != timer.stateSeq) return
        val ongoing = ongoingNotifications.hasOngoingRide(rec.provider.packageName)
        val event = when (timer.kind) {
            TimerKind.REPROMPT -> Timeout(TimeoutKind.REPROMPT)
            TimerKind.DRIVER_CANCELLED_WAIT -> Timeout(TimeoutKind.DRIVER_CANCELLED_WAIT, ongoing)
            TimerKind.FOLLOW_UP_CHECK -> Timeout(TimeoutKind.FOLLOW_UP_CHECK, ongoing)
            TimerKind.BETTER_RIDE_STOP -> return
        }
        apply(rec, event)
    }

    // ------------------------------------------------------------------ persistence

    /** The whole guardian state as JSON for Room. Survives process death (and reboots, via wall-clock rebasing). */
    fun snapshot(): String {
        s.savedAtElapsedMs = now().elapsedMs
        s.savedAtWallMs = clock.wallMs()
        return GuardianJson.encodeToString(GuardianState.serializer(), s)
    }

    /** After a restore: re-announce the foreground-service need and fire anything that came due while dead. */
    fun resume(): List<GuardianEffect> = step {
        val required = computeForegroundRequired()
        s.foregroundRequired = required
        if (required) emit(GuardianEffect.ForegroundService(true))
        tick()
    }

    // ------------------------------------------------------------------ core

    private fun apply(rec: LegRecord, event: RideEvent): Transition {
        val effective = if (event is Protocol && event.kind == ProtocolKind.CONFIRMED && event.tapId == null) {
            event.copy(tapId = rec.ondcBookTap)
        } else {
            event
        }
        val now = now()
        val t = Statechart.transition(rec.leg, effective, now)
        rec.leg = t.after
        transitions += TransitionLogEntry(
            now.elapsedMs, rec.legId, effective.type, t.before.state, t.after.state, t.before.generation, t.after.generation, t.outputs,
        )
        react(rec, t)
        return t
    }

    private fun react(rec: LegRecord, t: Transition) {
        for (output in t.outputs) {
            when (output) {
                is StateOutput.RaisePrompt -> openPrompt(rec, output.kind)
                is StateOutput.RePrompt -> rePrompt(rec)
                is StateOutput.WithdrawPrompt -> closePrompt(rec, output.reason)
                is StateOutput.CancelCardUpdate -> {
                    rec.driverUncertain = false
                    onCancelCardUpdate(rec)
                }
                is StateOutput.EndedByApp -> emit(GuardianEffect.Banner(rec.legId, rec.provider, UiKey.BANNER_ENDED_BY_APP))
                is StateOutput.CancelConfirmed -> {
                    s.evidence += EvidenceRecord(rec.legId, output.rung, now().elapsedMs)
                    emit(GuardianEffect.Banner(rec.legId, rec.provider, UiKey.BANNER_CANCEL_CONFIRMED))
                }
                StateOutput.UserSaysCancelled -> {
                    scheduleTimer(TimerKind.FOLLOW_UP_CHECK, rec, config.followUpMs)
                    emit(GuardianEffect.Banner(rec.legId, rec.provider, UiKey.BANNER_USER_SAYS_CANCELLED))
                }
                is StateOutput.Alert -> emit(
                    GuardianEffect.LoudAlert(
                        rec.legId, rec.provider,
                        if (output.kind == AlertKind.STILL_LIVE) UiKey.ALERT_STILL_LIVE else UiKey.ALERT_CHECK_SCREEN,
                        listOf(UiAction.OPEN_APP, UiAction.CHECK_SCREEN, UiAction.DISMISS),
                    ),
                )
                StateOutput.CheckScreenPrompt -> emit(GuardianEffect.CheckScreenPrompt(rec.legId, rec.provider))
                is StateOutput.RequestCapture -> emit(
                    GuardianEffect.RequestCapture(
                        rec.legId, rec.provider,
                        if (output.purpose == CapturePurpose.CANCEL_PROOF) CaptureReason.CANCEL_PROOF else CaptureReason.CHECK_SCREEN,
                    ),
                )
                StateOutput.DriverUpdated -> onDriverUpdated(rec)
                is StateOutput.OndcFeeShown -> emit(GuardianEffect.OndcCancelFee(rec.legId, output.feeInr))
                StateOutput.ReminderDue, StateOutput.DupDrop, is StateOutput.Logged, is StateOutput.GenerationBumped -> Unit
            }
        }
        if (t.stateChanged || t.generationChanged) onLegChanged(rec, t.before, t.after)
        // A plate can arrive without a state change (a leg being cancelled gets a driver), so check every step.
        checkSameDriver()
    }

    private fun onLegChanged(rec: LegRecord, before: LegState, after: LegState) {
        s.timers.removeAll { it.legId == rec.legId && it.kind != TimerKind.BETTER_RIDE_STOP && it.stateSeq != after.stateSeq }
        if (after.state == RideState.DRIVER_CANCELLED) scheduleTimer(TimerKind.DRIVER_CANCELLED_WAIT, rec, config.driverCancelledWaitMs)
        validateSheets(rec)
        val newDriver = after.state in LegState.DRIVER_STATES &&
            (before.state !in LegState.DRIVER_STATES || before.generation != after.generation)
        when (rec.role) {
            LegRole.KEPT -> onKeptChanged(rec, before, after, newDriver)
            LegRole.EXTRA -> if (after.state.isTerminal || after.state == RideState.USER_SAYS_CANCELLED) chainLegDone(rec)
            LegRole.NONE, LegRole.CANDIDATE -> {
                if (after.state == RideState.ON_TRIP && keptLeg() == null) {
                    // The user is riding this one without having answered the sheet: it is the kept ride now, and
                    // every other live booking is pointed out.
                    makeKept(rec)
                    onTripStarted(rec)
                }
                if (newDriver) driverFound(rec)
                if (after.state == RideState.SEARCH_EXPIRED || after.state == RideState.PROVIDER_CANCELLED) {
                    emit(GuardianEffect.Banner(rec.legId, rec.provider, UiKey.BANNER_ENDED_BY_APP))
                }
            }
        }
        if (after.state == RideState.ENDED) emit(GuardianEffect.PayHandoffOffer(rec.legId, rec.provider))
        firstDriverDecision()
        ensureReminders()
    }

    private fun onKeptChanged(rec: LegRecord, before: LegState, after: LegState, newDriver: Boolean) {
        val hadDriver = before.underlyingState in LegState.DRIVER_STATES
        when {
            after.state == RideState.PROVIDER_CANCELLED || after.state == RideState.SEARCH_EXPIRED -> keptLegLost(rec)
            after.state == RideState.DRIVER_CANCELLED || (after.state == RideState.SEARCHING && hadDriver) ->
                emit(GuardianEffect.Banner(rec.legId, rec.provider, UiKey.BANNER_FINDING_ANOTHER_CAPTAIN))
            newDriver -> {
                announceRide(rec)
                scheduleBetterRideStop(rec)
                // Bookings that found a driver while this one had none are offered now (keep one, cancel the other).
                activeLegs().filter {
                    it !== rec && (it.role == LegRole.NONE || it.role == LegRole.CANDIDATE) && !it.dismissed &&
                        it.leg.state in LegState.DRIVER_STATES
                }.forEach { betterRideCheck(rec, it) }
            }
            after.state == RideState.ON_TRIP -> onTripStarted(rec)
        }
    }

    /**
     * The kept trip started: better-ride alerts stop. I10 allows no chain any more, so any other booking still live
     * gets a loud alert with [Open <App>] for the user to cancel it there.
     */
    private fun onTripStarted(kept: LegRecord) {
        stopBetterRide(UiKey.SHEET_WITHDRAWN_TRIP_UNDERWAY)
        activeLegs().filter {
            it !== kept && (it.role == LegRole.CANDIDATE || it.role == LegRole.NONE) && it.leg.isBooked &&
                !it.leg.state.isTerminal && !it.dismissed && it.leg.state != RideState.ON_TRIP
        }.forEach {
            emit(GuardianEffect.LoudAlert(it.legId, it.provider, UiKey.ALERT_STILL_LIVE, listOf(UiAction.OPEN_APP, UiAction.DISMISS)))
        }
    }

    private fun announceRide(rec: LegRecord) {
        if (rec.leg.state !in LegState.DRIVER_STATES) return
        val key = bindingKey(rec)
        if (s.lastRideBanner == key) return
        s.lastRideBanner = key
        emit(GuardianEffect.RideBanner(card(rec), rec.leg.state))
    }

    // ------------------------------------------------------------------ first driver (§4.7)

    private fun driverFound(rec: LegRecord) {
        val kept = keptLeg() ?: return // with no kept leg, firstDriverDecision decides
        if (kept.leg.state in LegState.DRIVER_STATES) {
            betterRideCheck(kept, rec)
        } else if (rec.leg.state in LegState.DRIVER_STATES) {
            // The kept ride has no driver right now (re-searching, uncertain, on the trip or over): a driver heading to
            // another booking is never passed over in silence.
            emit(GuardianEffect.LoudAlert(rec.legId, rec.provider, UiKey.ALERT_STILL_LIVE, listOf(UiAction.OPEN_APP, UiAction.DISMISS)))
        }
    }

    private fun firstDriverDecision() {
        val session = s.session ?: return
        if (session.closed || session.keptLegId != null) return
        val drivers = activeLegs()
            .filter { it.role == LegRole.NONE && !it.dismissed && it.leg.state in LegState.DRIVER_STATES }
            .sortedWith(compareBy({ it.leg.driver.assignedAtWallMs ?: Long.MAX_VALUE }, { it.legId }))
        val current = s.sheets.firstOrNull { it.kind in FIRST_DRIVER_KINDS }
        if (drivers.isEmpty()) {
            current?.let { closeSheet(it, UiKey.SHEET_CLOSED) }
            return
        }
        val samePlate = drivers.filter { it.leg.driver.plateHash != null }
            .groupBy { it.leg.driver.plateHash }.values.firstOrNull { it.size >= 2 }
        val first = drivers.first()
        val firstAt = first.leg.driver.assignedAtWallMs ?: 0L
        val near = drivers.filter { (it.leg.driver.assignedAtWallMs ?: 0L) - firstAt <= config.twoDriversWindowMs }
        val notNowBefore = drivers.any { bindingKey(it) in s.notNowDrivers }
        val (kind, subjects) = when {
            samePlate != null -> SheetKind.SAME_DRIVER to samePlate
            near.size >= 2 -> SheetKind.TWO_DRIVERS to near
            drivers.size >= 2 && notNowBefore -> SheetKind.TWO_DRIVERS to drivers
            else -> SheetKind.CANCEL_OTHERS to listOf(first)
        }
        val bindings = subjects.map { SheetBinding(it.legId, it.leg.generation) }
        if (current != null && current.kind == kind && current.bindings == bindings) return
        if (kind == SheetKind.TWO_DRIVERS && bindings.all { bindingKey(it) in s.notNowDrivers }) return
        if (kind == SheetKind.CANCEL_OTHERS && bindingKey(bindings.single()) in s.notNowDrivers) {
            current?.let { closeSheet(it, UiKey.SHEET_CLOSED) }
            return
        }
        current?.let { closeSheet(it, UiKey.SHEET_REPLACED) }
        if (kind == SheetKind.CANCEL_OTHERS && cancellableOthers(first).isEmpty()) {
            // Nothing else is booked: this is simply the user's ride.
            makeKept(first)
            announceRide(first)
            scheduleBetterRideStop(first)
            return
        }
        val actions = when (kind) {
            SheetKind.CANCEL_OTHERS -> listOfNotNull(
                UiAction.YES_CANCEL_OTHERS,
                UiAction.KEEP_SEARCHING.takeIf { session.keepSearching },
                UiAction.NOT_NOW,
            )
            else -> listOf(UiAction.PICK, UiAction.NOT_NOW)
        }
        val others = activeLegs().filter { other ->
            subjects.none { it === other } && other.leg.isBooked && !other.leg.state.isTerminal && !other.dismissed
        }
        showDriverSheet(kind, subjects, others, actions)
    }

    private fun showDriverSheet(kind: SheetKind, subjects: List<LegRecord>, others: List<LegRecord>, actions: List<UiAction>) {
        val sheet = OpenSheet(nextId("sheet"), kind, subjects.map { SheetBinding(it.legId, it.leg.generation) }, now().elapsedMs, actions)
        s.sheets += sheet
        emit(driverSheetEffect(sheet, subjects, others))
    }

    private fun driverSheetEffect(sheet: OpenSheet, subjects: List<LegRecord>, others: List<LegRecord>) =
        GuardianEffect.DriverSheet(
            sheet.sheetId,
            when (sheet.kind) {
                SheetKind.TWO_DRIVERS -> UiKey.SHEET_TWO_DRIVERS_PICK_ONE
                SheetKind.SAME_DRIVER -> UiKey.SHEET_SAME_DRIVER_KEEP_ONE
                else -> UiKey.SHEET_CANCEL_OTHERS
            },
            subjects.map(::card),
            others.map { LegFee(it.legId, it.provider, feeNote(it)) },
            sheet.actions,
        )

    /** A sheet whose leg left ASSIGNED/ARRIVING or moved to a new generation is withdrawn, with the reason. */
    private fun validateSheets(rec: LegRecord) {
        for (sheet in s.sheets.toList()) {
            val binding = sheet.bindings.firstOrNull { it.legId == rec.legId } ?: continue
            if (!bindingValid(binding)) closeSheet(sheet, withdrawKey(rec.leg, binding))
        }
    }

    private fun bindingValid(binding: SheetBinding): Boolean {
        val rec = leg(binding.legId) ?: return false
        return rec.leg.state in LegState.DRIVER_STATES && rec.leg.generation == binding.generation && !rec.dismissed
    }

    private fun withdrawKey(leg: LegState, binding: SheetBinding): UiKey = when {
        leg.generation != binding.generation && leg.state in LegState.DRIVER_STATES -> UiKey.SHEET_WITHDRAWN_NEW_DRIVER
        leg.state == RideState.DRIVER_CANCELLED || leg.state == RideState.MAYBE_NEW_DRIVER -> UiKey.SHEET_WITHDRAWN_DRIVER_CANCELLED
        leg.state == RideState.SEARCHING || leg.state == RideState.MAYBE_ASSIGNED -> UiKey.SHEET_WITHDRAWN_SEARCHING_AGAIN
        leg.state == RideState.MAYBE_CHANGED -> UiKey.SHEET_WITHDRAWN_UPDATE_PENDING
        leg.state == RideState.SEARCH_EXPIRED -> UiKey.SHEET_WITHDRAWN_SEARCH_EXPIRED
        leg.state == RideState.PROVIDER_CANCELLED -> UiKey.SHEET_WITHDRAWN_PROVIDER_CANCELLED
        leg.state == RideState.ON_TRIP || leg.state == RideState.ENDED -> UiKey.SHEET_WITHDRAWN_TRIP_UNDERWAY
        else -> UiKey.SHEET_CLOSED
    }

    private fun withdrawStale(sheet: OpenSheet, rec: LegRecord, tap: TapId) {
        val binding = sheet.bindings.firstOrNull { it.legId == rec.legId } ?: sheet.bindings.first()
        closeSheet(sheet, withdrawKey(rec.leg, binding))
        reject(tap)
    }

    private fun closeSheet(sheet: OpenSheet, key: UiKey?) {
        if (!s.sheets.remove(sheet)) return
        if (key != null) emit(GuardianEffect.SheetWithdrawn(sheet.sheetId, key))
    }

    // ------------------------------------------------------------------ cancel chain (§4.7)

    private fun startChain(kept: LegRecord, tap: TapId) {
        // I10: no chain for a leg whose current generation is not ASSIGNED or ARRIVING.
        if (kept.leg.state !in LegState.DRIVER_STATES) return reject(tap)
        makeKept(kept)
        record(ActionKind.CANCEL_CHAIN_START, kept, tap)
        s.sheets.filter { it.kind in FIRST_DRIVER_KINDS || it.kind == SheetKind.CANCEL_OTHERS }.forEach { closeSheet(it, UiKey.SHEET_CLOSED) }
        announceRide(kept)
        scheduleBetterRideStop(kept)
        enqueueExtras(cancellableOthers(kept), tap)
    }

    private fun cancellableOthers(kept: LegRecord): List<LegRecord> = activeLegs().filter {
        it !== kept && it.role != LegRole.EXTRA && it.role != LegRole.KEPT && !it.dismissed && it.leg.isBooked &&
            !it.leg.state.isTerminal && it.leg.state != RideState.ON_TRIP
    }

    private fun enqueueExtras(extras: List<LegRecord>, tap: TapId) {
        if (extras.isEmpty()) return ensureReminders()
        val now = now().elapsedMs
        val chain = s.chain ?: ChainState(tap, mutableListOf(), null, now).also { s.chain = it }
        for (rec in extras) {
            rec.role = LegRole.EXTRA
            rec.extraSinceElapsedMs = now
            rec.cancelTap = tap
            s.sheets.filter { sheet -> sheet.bindings.any { it.legId == rec.legId } }.forEach { closeSheet(it, UiKey.SHEET_CLOSED) }
            rec.driverUncertain = rec.leg.state in MAYBE_DRIVER_STATES
            if (!rec.leg.isNetwork) apply(rec, Chain(ChainStep.QUEUE))
            if (rec.legId !in chain.queue && chain.currentLegId != rec.legId) chain.queue += rec.legId
        }
        sortQueue(chain)
        ensureReminders()
        advanceChain()
    }

    /** SEARCHING-type legs first (usually free), then driver legs by when their free window closes. */
    private fun sortQueue(chain: ChainState) {
        val ordered = chain.queue.mapNotNull { leg(it) }.sortedWith(
            compareBy<LegRecord>(
                { if (it.leg.underlyingState in LegState.DRIVER_STATES) 1 else 0 },
                { feeEstimator.freeWindowEndsAtElapsedMs(feeQuery(it)) ?: Long.MAX_VALUE },
            ),
        )
        chain.queue.clear()
        chain.queue += ordered.map { it.legId }
    }

    private fun advanceChain() {
        val chain = s.chain ?: return
        chain.currentLegId?.let { id ->
            val current = leg(id)
            if (current != null && awaitingEvidence(current)) return
            chain.currentLegId = null
        }
        while (chain.queue.isNotEmpty()) {
            val rec = leg(chain.queue.removeAt(0)) ?: continue
            if (rec.role != LegRole.EXTRA || rec.dismissed || rec.leg.state.isTerminal) continue
            chain.currentLegId = rec.legId
            guide(rec, rec.cancelTap ?: chain.yesTap)
            return
        }
        if (pendingExtras().isEmpty()) s.chain = null
    }

    private fun awaitingEvidence(rec: LegRecord): Boolean {
        if (rec.role != LegRole.EXTRA || rec.dismissed || rec.leg.state.isTerminal) return false
        return if (rec.leg.isNetwork) {
            rec.leg.state in Statechart.ONDC_CANCELLABLE || rec.leg.state == RideState.SOFT_CANCEL || rec.leg.state == RideState.CONFIRM_CANCEL
        } else {
            rec.leg.state == RideState.CANCEL_REQUESTED || rec.leg.state == RideState.CANCEL_GUIDED
        }
    }

    /** One step of the chain: open the app, show the card (+ fee note); Show me is offered; then wait for evidence. */
    private fun guide(rec: LegRecord, tap: TapId) {
        if (rec.leg.isNetwork) {
            when (rec.leg.state) {
                in Statechart.ONDC_CANCELLABLE -> {
                    rec.cancelTap = tap
                    record(ActionKind.ONDC_SOFT_CANCEL, rec, tap)
                    emit(GuardianEffect.OndcCommand(rec.legId, OndcCommandKind.SOFT_CANCEL, tap))
                }
                RideState.SOFT_CANCEL -> emit(GuardianEffect.OndcCancelFee(rec.legId, rec.leg.ondcFeeInr ?: 0))
                else -> Unit
            }
            return
        }
        apply(rec, Chain(ChainStep.GUIDE))
        record(ActionKind.CANCEL_GUIDE, rec, tap)
        emit(GuardianEffect.OpenApp(rec.legId, rec.provider, OpenPurpose.CANCEL, tap))
        emit(cancelCard(rec))
    }

    private fun cancelCard(rec: LegRecord): GuardianEffect.CancelCard {
        val extras = activeLegs().filter { it.role == LegRole.EXTRA }
        val position = extras.count { it.legId != rec.legId && (it.dismissed || !awaitingEvidence(it)) } + 1
        return GuardianEffect.CancelCard(
            rec.legId, rec.provider, rec.leg.underlyingState, feeNote(rec), position.coerceAtMost(extras.size), extras.size,
            listOf(UiAction.SHOW_ME, UiAction.I_CANCELLED_IT, UiAction.CHECK_SCREEN, UiAction.DISMISS),
        )
    }

    private fun onCancelCardUpdate(rec: LegRecord) {
        val chain = s.chain
        if (chain?.currentLegId == rec.legId) emit(cancelCard(rec)) else chain?.let(::sortQueue)
    }

    private fun chainLegDone(rec: LegRecord) {
        val chain = s.chain ?: return
        chain.queue.remove(rec.legId)
        if (chain.currentLegId == rec.legId) {
            chain.currentLegId = null
            advanceChain()
        }
    }

    // ------------------------------------------------------------------ kept leg

    private fun keptLeg(): LegRecord? = s.session?.keptLegId?.let { leg(it) }

    private fun makeKept(rec: LegRecord) {
        val session = s.session ?: return
        session.keptLegId = rec.legId
        rec.role = LegRole.KEPT
    }

    private fun unKeep(rec: LegRecord) {
        val session = s.session ?: return
        if (session.keptLegId == rec.legId) session.keptLegId = null
        if (rec.role == LegRole.KEPT) rec.role = LegRole.NONE
    }

    /**
     * §4.4 "Kept leg lost": loud alert with [Compare again (same trip)] [Open <App>]. Bookings the chain had not yet
     * cancelled go back to the user (they may be the ride now), so the first-driver logic re-arms for them.
     */
    private fun keptLegLost(rec: LegRecord) {
        emit(
            GuardianEffect.LoudAlert(rec.legId, rec.provider, UiKey.ALERT_KEPT_LEG_LOST, listOf(UiAction.COMPARE_AGAIN, UiAction.OPEN_APP)),
        )
        unKeep(rec)
        s.session?.betterRideStopped = false
        s.sheets.filter { it.kind == SheetKind.BETTER_RIDE || it.kind == SheetKind.SECOND_DRIVER || it.kind == SheetKind.CANCEL_OTHERS }
            .forEach { closeSheet(it, UiKey.SHEET_WITHDRAWN_PROVIDER_CANCELLED) }
        for (other in activeLegs()) {
            if (other.role == LegRole.CANDIDATE) other.role = LegRole.NONE
            val untouched = other.role == LegRole.EXTRA && !other.dismissed &&
                (other.leg.state in setOf(RideState.CANCEL_REQUESTED, RideState.CANCEL_GUIDED, RideState.CANCEL_UNCONFIRMED) ||
                    (other.leg.isNetwork && other.leg.state in Statechart.ONDC_CANCELLABLE))
            if (untouched) {
                other.role = LegRole.NONE
                other.extraSinceElapsedMs = null
                other.cancelTap = null
                other.driverUncertain = false
                s.chain?.queue?.remove(other.legId)
                if (s.chain?.currentLegId == other.legId) s.chain?.currentLegId = null
                if (!other.leg.isNetwork) apply(other, Chain(ChainStep.ABORT))
            }
        }
    }

    // ------------------------------------------------------------------ better ride (§4.8)

    private fun betterRideCheck(kept: LegRecord, candidate: LegRecord) {
        val session = s.session ?: return
        if (candidate.leg.state !in LegState.DRIVER_STATES) return
        val offerKey = bindingKey(candidate)
        val existing = s.sheets.firstOrNull { sheet ->
            (sheet.kind == SheetKind.BETTER_RIDE || sheet.kind == SheetKind.SECOND_DRIVER) && sheet.bindings[1].legId == candidate.legId
        }
        val better = session.keepSearching && !session.betterRideStopped &&
            config.betterRide.isBetter(kept.leg.driver, candidate.leg.driver, now().elapsedMs)
        val kind = if (better) SheetKind.BETTER_RIDE else SheetKind.SECOND_DRIVER
        val bindings = listOf(SheetBinding(kept.legId, kept.leg.generation), SheetBinding(candidate.legId, candidate.leg.generation))
        if (existing != null && existing.kind == kind && existing.bindings == bindings) return
        if (existing == null && !better && offerKey in s.betterRideOffered) return
        if (existing != null && existing.kind == SheetKind.BETTER_RIDE && !better) return
        existing?.let { closeSheet(it, UiKey.SHEET_REPLACED) }
        s.betterRideOffered += offerKey
        // After the stop rule (current driver ≤ 2 min or trip started) switching is no longer offered.
        val actions = if (!better && session.betterRideStopped) {
            listOf(UiAction.KEEP_CURRENT, UiAction.NOT_NOW)
        } else {
            listOf(UiAction.SWITCH, UiAction.KEEP_CURRENT)
        }
        val sheet = OpenSheet(nextId("sheet"), kind, bindings, now().elapsedMs, actions)
        s.sheets += sheet
        emit(
            GuardianEffect.BetterRideSheet(
                sheet.sheetId,
                if (better) UiKey.SHEET_BETTER_RIDE else UiKey.SHEET_SECOND_DRIVER,
                card(kept), card(candidate), feeNote(kept), feeNote(candidate), actions,
            ),
        )
    }

    private fun onDriverUpdated(rec: LegRecord) {
        val kept = keptLeg()
        if (rec === kept) {
            scheduleBetterRideStop(rec)
            checkBetterRideStop()
        } else if (kept != null && rec.role != LegRole.EXTRA && rec.leg.state in LegState.DRIVER_STATES) {
            betterRideCheck(kept, rec)
        }
        // Refresh the numbers on any open driver sheet showing this leg.
        s.sheets.filter { sheet -> sheet.kind in FIRST_DRIVER_KINDS && sheet.bindings.any { it.legId == rec.legId } }.forEach { sheet ->
            val subjects = sheet.bindings.mapNotNull { leg(it.legId) }
            val others = activeLegs().filter { o -> subjects.none { it === o } && o.leg.isBooked && !o.leg.state.isTerminal && !o.dismissed }
            emit(driverSheetEffect(sheet, subjects, others))
        }
    }

    private fun scheduleBetterRideStop(kept: LegRecord) {
        val session = s.session ?: return
        s.timers.removeAll { it.kind == TimerKind.BETTER_RIDE_STOP }
        if (!session.keepSearching || session.betterRideStopped) return
        val at = config.betterRide.closeAtElapsedMs(kept.leg.driver) ?: return
        s.timers += TimerRecord(TimerKind.BETTER_RIDE_STOP, kept.legId, kept.leg.stateSeq, at)
    }

    private fun checkBetterRideStop() {
        val kept = keptLeg() ?: return
        if (kept.leg.state in LegState.DRIVER_STATES && config.betterRide.currentIsClose(kept.leg.driver, now().elapsedMs)) {
            stopBetterRide(UiKey.SHEET_WITHDRAWN_CURRENT_DRIVER_CLOSE)
        }
    }

    /** Better-ride alerts stop; any leg still searching under "Keep searching" gets the "Cancel the others?" question. */
    private fun stopBetterRide(reason: UiKey) {
        val session = s.session ?: return
        if (!session.keepSearching || session.betterRideStopped) return
        session.betterRideStopped = true
        s.timers.removeAll { it.kind == TimerKind.BETTER_RIDE_STOP }
        s.sheets.filter { it.kind == SheetKind.BETTER_RIDE }.forEach { closeSheet(it, reason) }
        val kept = keptLeg() ?: return
        val candidates = cancellableOthers(kept).filter { it.role == LegRole.CANDIDATE }
        if (candidates.isEmpty() || kept.leg.state !in LegState.DRIVER_STATES) return
        showDriverSheet(SheetKind.CANCEL_OTHERS, listOf(kept), candidates, listOf(UiAction.YES_CANCEL_OTHERS, UiAction.NOT_NOW))
    }

    // ------------------------------------------------------------------ same driver (§4.7)

    private fun checkSameDriver() {
        val withPlates = activeLegs().filter {
            it.leg.underlyingState in LegState.DRIVER_STATES && !it.leg.state.isTerminal && it.leg.driver.plateHash != null
        }
        for (group in withPlates.groupBy { it.leg.driver.plateHash }.values.filter { it.size >= 2 }) {
            val key = group.map(::bindingKey).sorted().joinToString("|")
            if (!s.sameDriverReported.add(key)) continue
            // Without a kept leg the first-driver logic shows the "Same driver on both. Keep one." sheet instead.
            if (keptLeg() != null) emit(GuardianEffect.SameDriverNotice(group.map { it.legId }))
        }
    }

    // ------------------------------------------------------------------ prompts

    private fun openPrompt(rec: LegRecord, kind: PromptKind) {
        s.prompts.removeAll { it.legId == rec.legId }
        val prompt = OpenPrompt(nextId("prompt"), rec.legId, rec.leg.generation, kind, now().elapsedMs)
        s.prompts += prompt
        emit(promptEffect(rec, prompt))
        scheduleTimer(TimerKind.REPROMPT, rec, config.repromptMs)
    }

    private fun rePrompt(rec: LegRecord) {
        val prompt = s.prompts.firstOrNull { it.legId == rec.legId } ?: return
        prompt.repeats++
        emit(promptEffect(rec, prompt))
        scheduleTimer(TimerKind.REPROMPT, rec, config.repromptMs)
    }

    private fun promptEffect(rec: LegRecord, prompt: OpenPrompt): GuardianEffect.MaybePrompt {
        val (key, actions) = when (prompt.kind) {
            PromptKind.MAYBE_ASSIGNED -> {
                val cancelsOthers = keptLeg() == null && cancellableOthers(rec).isNotEmpty()
                UiKey.PROMPT_MAYBE_ASSIGNED to listOf(
                    if (cancelsOthers) UiAction.YES_CANCEL_OTHERS else UiAction.YES, UiAction.NOT_YET, UiAction.CHECK_SCREEN,
                )
            }
            PromptKind.MAYBE_CHANGED -> UiKey.PROMPT_MAYBE_CHANGED to listOf(UiAction.YES, UiAction.DRIVER_CANCELLED, UiAction.CHECK_SCREEN)
            PromptKind.MAYBE_NEW_DRIVER -> UiKey.PROMPT_MAYBE_NEW_DRIVER to listOf(UiAction.YES, UiAction.NO_IT_ENDED, UiAction.CHECK_SCREEN)
        }
        return GuardianEffect.MaybePrompt(prompt.promptId, rec.legId, rec.provider, prompt.generation, key, actions, prompt.repeats)
    }

    private fun closePrompt(rec: LegRecord, reason: WithdrawReason) {
        val prompt = s.prompts.firstOrNull { it.legId == rec.legId } ?: return
        s.prompts.remove(prompt)
        emit(GuardianEffect.PromptWithdrawn(prompt.promptId, rec.legId, promptWithdrawKey(reason)))
    }

    private fun promptWithdrawKey(reason: WithdrawReason): UiKey = when (reason) {
        WithdrawReason.SEARCHING_AGAIN -> UiKey.PROMPT_WITHDRAWN_SEARCHING_AGAIN
        WithdrawReason.DRIVER_ASSIGNED -> UiKey.PROMPT_WITHDRAWN_DRIVER_ASSIGNED
        WithdrawReason.DRIVER_ARRIVING -> UiKey.PROMPT_WITHDRAWN_DRIVER_ARRIVING
        WithdrawReason.DRIVER_CANCELLED -> UiKey.PROMPT_WITHDRAWN_DRIVER_CANCELLED
        WithdrawReason.SEARCH_EXPIRED -> UiKey.PROMPT_WITHDRAWN_SEARCH_EXPIRED
        WithdrawReason.PROVIDER_CANCELLED -> UiKey.PROMPT_WITHDRAWN_PROVIDER_CANCELLED
        WithdrawReason.RIDE_ENDED -> UiKey.PROMPT_WITHDRAWN_RIDE_ENDED
        WithdrawReason.TRIP_STARTED -> UiKey.PROMPT_WITHDRAWN_TRIP_STARTED
        WithdrawReason.SAME_DRIVER_STILL_COMING -> UiKey.PROMPT_WITHDRAWN_SAME_DRIVER_STILL_COMING
        WithdrawReason.CANCELLING -> UiKey.PROMPT_WITHDRAWN_CANCELLING
        WithdrawReason.ANSWERED -> UiKey.PROMPT_ANSWERED
    }

    // ------------------------------------------------------------------ reminders (§4.7, I8)

    private fun pendingExtras(): List<LegRecord> =
        activeLegs().filter { it.role == LegRole.EXTRA && !it.dismissed && !it.leg.state.isTerminal }

    private fun ensureReminders() {
        val pendingNow = pendingExtras()
        if (pendingNow.isEmpty()) {
            if (s.reminders != null) {
                s.reminders = null
                // Only when the extra bookings were actually closed (not when a lost kept ride handed them back).
                if (activeLegs().any { it.role == LegRole.EXTRA }) emit(GuardianEffect.Banner(null, null, UiKey.BANNER_ALL_CANCELS_DONE))
            }
            val chain = s.chain
            if (chain != null && chain.queue.isEmpty() && chain.currentLegId == null) s.chain = null
        } else if (s.reminders == null) {
            s.reminders = ReminderState(now().elapsedMs)
        }
    }

    private fun computeForegroundRequired(): Boolean =
        pendingExtras().isNotEmpty() || s.timers.any { it.kind == TimerKind.FOLLOW_UP_CHECK }

    private fun updateForeground() {
        val required = computeForegroundRequired()
        if (required != s.foregroundRequired) {
            s.foregroundRequired = required
            emit(GuardianEffect.ForegroundService(required))
        }
    }

    // ------------------------------------------------------------------ helpers

    /** One public guardian call: nested calls fold their effects into the outermost one, which publishes them. */
    private inline fun step(block: () -> Unit): List<GuardianEffect> {
        val outermost = depth == 0
        depth++
        try {
            block()
        } finally {
            depth--
        }
        if (!outermost) return emptyList()
        updateForeground()
        val out = pending.toList()
        pending.clear()
        val at = now().elapsedMs
        out.forEach { effect ->
            effects += TimedEffect(at, effect)
            effectListener?.invoke(effect)
        }
        schedule.value++
        return out
    }

    private fun emit(effect: GuardianEffect) {
        pending += effect
    }

    private fun reject(tap: TapId) = emit(GuardianEffect.AnswerRejected(tap))

    private fun now(): Moment = Moment(clock.elapsedMs() + s.elapsedOffsetMs, clock.wallMs())

    private fun leg(legId: String): LegRecord? = s.legs[legId]

    private fun activeLegs(): Collection<LegRecord> = s.legs.values

    private fun addLeg(sessionId: String, provider: Provider, round: Int, now: Moment) {
        val legId = legIdOf(sessionId, provider, round)
        s.legs[legId] = LegRecord(
            LegState(legId = legId, provider = provider, enteredAtElapsedMs = now.elapsedMs, notBeforeWallMs = now.wallMs),
        )
    }

    private fun nextId(prefix: String): String = "$prefix-${s.nextId++}"

    private fun bindingKey(binding: SheetBinding) = "${binding.legId}:${binding.generation}"

    private fun bindingKey(rec: LegRecord) = "${rec.legId}:${rec.leg.generation}"

    private fun record(kind: ActionKind, rec: LegRecord, tap: TapId?) {
        s.actions += ActionRecord(kind, rec.legId, tap, now().elapsedMs, rec.leg.state, rec.leg.generation)
    }

    private fun scheduleTimer(kind: TimerKind, rec: LegRecord, delayMs: Long) {
        s.timers.removeAll { it.legId == rec.legId && it.kind == kind }
        s.timers += TimerRecord(kind, rec.legId, rec.leg.stateSeq, now().elapsedMs + delayMs)
    }

    private fun acceptFrame(rec: LegRecord, tap: TapId, frameAtClockMs: Long): Boolean {
        val frameAt = frameAtClockMs + s.elapsedOffsetMs
        val tapAt = s.taps.lastOrNull { it.tapId == tap }?.atElapsedMs
        val demo = s.session?.demo == true
        val accepted = demo || (tapAt != null && frameAt >= tapAt && frameAt - tapAt <= config.captureTapWindowMs)
        s.captures += CaptureRecord(rec.legId, tap, tapAt, frameAt, demo, accepted)
        return accepted
    }

    private fun feeQuery(rec: LegRecord) = FeeQuery(
        provider = rec.provider,
        rideState = rec.leg.underlyingState,
        assignedAtElapsedMs = rec.leg.driver.assignedAtElapsedMs,
        promisedEtaMin = rec.leg.driver.etaMin?.takeIf { rec.leg.driver.etaSource.isAdmissible },
        nowElapsedMs = now().elapsedMs,
        fareInr = rec.bookedFareInr,
        ondcFeeInr = rec.leg.ondcFeeInr,
        driverPossible = rec.leg.state in MAYBE_DRIVER_STATES || rec.driverUncertain,
    )

    private fun feeNote(rec: LegRecord): FeeNote = feeEstimator.note(feeQuery(rec))

    /** I2 + D8: numbers only with an admissible source; otherwise "see in <app>". */
    private fun card(rec: LegRecord): DriverCard {
        val driver = rec.leg.driver
        return DriverCard(
            legId = rec.legId,
            provider = rec.provider,
            generation = rec.leg.generation,
            etaMin = driver.etaMin?.takeIf { driver.etaSource.isAdmissible },
            etaSource = if (driver.etaMin != null && driver.etaSource.isAdmissible) driver.etaSource else EtaSource.NONE,
            distanceMeters = driver.distanceMeters?.takeIf { driver.distanceSource.isAdmissible },
            distanceSource = if (driver.distanceMeters != null && driver.distanceSource.isAdmissible) driver.distanceSource else EtaSource.NONE,
        )
    }

    private fun dispositionOf(t: Transition): Disposition = when {
        t.outputs.any {
            it is StateOutput.RaisePrompt || it is StateOutput.RePrompt || it == StateOutput.CheckScreenPrompt || it is StateOutput.Alert
        } -> Disposition.PROMPT
        t.stateChanged || t.generationChanged -> Disposition.TRANSITION
        t.isDupDrop -> Disposition.DUP_DROP
        else -> Disposition.LOGGED
    }

    companion object {
        /** The id of [provider]'s leg in session [sessionId] ([round] > 1 after "Compare again" re-adds it). */
        fun legIdOf(sessionId: String, provider: Provider, round: Int = 1): String =
            if (round == 1) "$sessionId:${provider.wireId}" else "$sessionId:${provider.wireId}:r$round"

        private const val MAX_FIRES_PER_TICK = 64

        /** Wall-clock corrections (NTP/NITZ) smaller than this are not taken for a reboot. */
        private const val REBOOT_SLACK_MS = 60_000L
        private val FIRST_DRIVER_KINDS = setOf(SheetKind.CANCEL_OTHERS, SheetKind.TWO_DRIVERS, SheetKind.SAME_DRIVER)

        /** "Got a driver?" / "Is a new driver coming?" are open: a driver may already be on the way. */
        private val MAYBE_DRIVER_STATES = setOf(RideState.MAYBE_ASSIGNED, RideState.MAYBE_NEW_DRIVER)

        /** Rebuilds a guardian from [snapshot]. After a reboot the monotonic clock restarted, so its frame is rebased. */
        fun restore(json: String, clock: Clock, feeEstimator: FeeEstimator, config: GuardianConfig = GuardianConfig()): Guardian {
            val state = GuardianJson.decodeFromString(GuardianState.serializer(), json)
            val frameGap = clock.elapsedMs() + state.elapsedOffsetMs - state.savedAtElapsedMs
            val wallGap = clock.wallMs() - state.savedAtWallMs
            // The monotonic clock counts deep sleep, so it only falls behind the wall clock when it restarted: it went
            // backwards, or the phone rebooted and has now been up longer than it had been at the save.
            val rebooted = frameGap < 0 || wallGap - frameGap > REBOOT_SLACK_MS
            if (rebooted) state.elapsedOffsetMs = state.savedAtElapsedMs + wallGap.coerceAtLeast(0) - clock.elapsedMs()
            return Guardian(clock, feeEstimator, config, state)
        }
    }
}
