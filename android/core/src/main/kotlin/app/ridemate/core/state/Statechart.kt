package app.ridemate.core.state

import app.ridemate.core.config.NotificationEvent
import app.ridemate.core.model.EtaSource
import app.ridemate.core.model.EvidenceRung
import app.ridemate.core.model.RideState
import app.ridemate.core.model.RideState.ARRIVING
import app.ridemate.core.model.RideState.ASSIGNED
import app.ridemate.core.model.RideState.BOOK_REQUESTED
import app.ridemate.core.model.RideState.CANCEL_CONFIRMED
import app.ridemate.core.model.RideState.CANCEL_GUIDED
import app.ridemate.core.model.RideState.CANCEL_REQUESTED
import app.ridemate.core.model.RideState.CANCEL_UNCONFIRMED
import app.ridemate.core.model.RideState.CONFIRMED
import app.ridemate.core.model.RideState.CONFIRM_CANCEL
import app.ridemate.core.model.RideState.DRIVER_CANCELLED
import app.ridemate.core.model.RideState.ENDED
import app.ridemate.core.model.RideState.INIT
import app.ridemate.core.model.RideState.MAYBE_ASSIGNED
import app.ridemate.core.model.RideState.MAYBE_CHANGED
import app.ridemate.core.model.RideState.MAYBE_NEW_DRIVER
import app.ridemate.core.model.RideState.ON_SELECT
import app.ridemate.core.model.RideState.ON_TRIP
import app.ridemate.core.model.RideState.OPENED
import app.ridemate.core.model.RideState.PAY_HANDOFF
import app.ridemate.core.model.RideState.PROVIDER_CANCELLED
import app.ridemate.core.model.RideState.QUOTED
import app.ridemate.core.model.RideState.SEARCHING
import app.ridemate.core.model.RideState.SEARCHING_NET
import app.ridemate.core.model.RideState.SEARCH_EXPIRED
import app.ridemate.core.model.RideState.SELECTED
import app.ridemate.core.model.RideState.SOFT_CANCEL
import app.ridemate.core.model.RideState.USER_SAYS_CANCELLED
import app.ridemate.core.time.Moment

/**
 * The per-leg statechart of §4.4, as a pure function. Every cell of the transition table is implemented here and
 * unit-tested as a table (StatechartTableTest). Generation bumps exactly where the table marks "gen+1"; prompts are
 * raised on entering a MAYBE state and withdrawn (with the reason) on leaving one.
 */
object Statechart {
    /** Rung 3 needs the removal and the cancelled-screen capture within this window of each other. */
    const val RUNG3_WINDOW_MS: Long = 5 * 60_000L

    fun transition(leg: LegState, event: RideEvent, now: Moment): Transition {
        val step = Step(leg, now)
        if (leg.isNetwork) step.network(event) else step.rideApp(event)
        return Transition(leg, step.leg, step.outputs.toList())
    }

    /** States from which the guardian may start a user-cancel chain for a ride-app leg. */
    val QUEUEABLE: Set<RideState> = setOf(
        BOOK_REQUESTED, SEARCHING, MAYBE_ASSIGNED, ASSIGNED, ARRIVING, MAYBE_CHANGED, DRIVER_CANCELLED, MAYBE_NEW_DRIVER,
    )

    /** ONDC states in which a booking exists and can be soft-cancelled. */
    val ONDC_CANCELLABLE: Set<RideState> = setOf(CONFIRMED, ASSIGNED, ARRIVING)

    /** §4.5.6: the states in which a classifier label may propose each status; anywhere else it is an UNKNOWN_UPDATE. */
    internal val CLASSIFIER_PROPOSALS: Map<NotificationEvent, Set<RideState>> = mapOf(
        NotificationEvent.SEARCHING to setOf(BOOK_REQUESTED, SEARCHING, DRIVER_CANCELLED),
        NotificationEvent.ASSIGNED to setOf(SEARCHING, DRIVER_CANCELLED),
    )

    /**
     * Ride-app states in which a classifier label can lead somewhere an UNKNOWN_UPDATE would not. In every other state
     * the outcome is the same whatever the label, so RideWatch does not spend a classification (Modal credit) on it.
     */
    val CLASSIFIABLE: Set<RideState> = CLASSIFIER_PROPOSALS.values.flatten().toSet()
}

/** Provider statuses that mean the booking is still alive in the ride app. */
private val LIVE_SIGNALS = setOf(
    NotificationEvent.SEARCHING, NotificationEvent.ASSIGNED, NotificationEvent.ARRIVING,
    NotificationEvent.DRIVER_CANCELLED, NotificationEvent.TRIP_STARTED,
)

private class Step(private val before: LegState, private val now: Moment) {
    var leg: LegState = before
    val outputs = mutableListOf<StateOutput>()

    // ---------------------------------------------------------------- ride-app legs

    fun rideApp(event: RideEvent) {
        if (leg.state == ENDED) return endedRow(event)
        if (leg.state.isTerminal) return log(LogReason.TERMINAL_STATE)
        when (event) {
            is ProviderUpdate -> provider(event)
            is UnknownUpdate -> unknown(event)
            is NotificationRemoved -> removal(event)
            is CaptureVerdict -> capture(event)
            is DriverData -> driverData(event)
            is UserAnswer -> answer(event)
            is Timeout -> timeout(event)
            is Chain -> chain(event)
            is Lifecycle -> lifecycle(event)
            is Protocol -> log(LogReason.WRONG_LEG_KIND)
        }
    }

    private fun endedRow(event: RideEvent) {
        if (event is UserAnswer && event.answer == Answer.PAY) moveTo(PAY_HANDOFF) else log(LogReason.NO_TRANSITION)
    }

    private fun provider(u: ProviderUpdate) {
        if (u.source == SignalSource.CLASSIFIER && !classifierMayApply(u.signal)) {
            return unknown(UnknownUpdate(UnknownReason.CLASSIFIER_LABEL, u.sbnKey, u.postTimeWallMs, u.replayed))
        }
        val stale = isStaleReplay(u.replayed, u.postTimeWallMs)
        // The app says the ride is live after the removal or capture was seen: neither half of rung 3 still counts.
        if (u.isDeterministic && !stale && u.signal in LIVE_SIGNALS) dropCancelProofHalves()
        val key = u.sbnKey
        if (u.isDeterministic && key != null && leg.marks[key] == AppliedMark(u.type, leg.generation)) {
            log(LogReason.IDEMPOTENT)
            if (u.signal == NotificationEvent.ASSIGNED || u.signal == NotificationEvent.ARRIVING) refreshDriverValues(u)
            return
        }
        if (stale) return log(LogReason.STALE_REPLAY)
        applyProvider(u)
        if (u.isDeterministic) {
            if (key != null) mark(key, u.type)
            u.postTimeWallMs?.let { t -> leg = leg.copy(lastEvidenceWallMs = maxOf(t, leg.lastEvidenceWallMs ?: t)) }
        }
    }

    /** §4.5.6: a classifier label may propose SEARCHING/ASSIGNED only where that neither ends nor resolves anything. */
    private fun classifierMayApply(signal: NotificationEvent): Boolean =
        leg.state in Statechart.CLASSIFIER_PROPOSALS[signal].orEmpty()

    private fun applyProvider(u: ProviderUpdate) {
        val s = u.signal
        when (leg.state) {
            SELECTED, OPENED, QUOTED, BOOK_REQUESTED -> when (s) {
                NotificationEvent.SEARCHING -> moveTo(SEARCHING)
                NotificationEvent.ASSIGNED -> moveTo(ASSIGNED, driver = driverFrom(u))
                NotificationEvent.ARRIVING -> moveTo(ARRIVING, driver = driverFrom(u))
                NotificationEvent.NO_DRIVERS -> moveTo(SEARCH_EXPIRED)
                NotificationEvent.CANCELLED -> moveTo(PROVIDER_CANCELLED)
                NotificationEvent.DRIVER_CANCELLED, NotificationEvent.ENDED, NotificationEvent.TRIP_STARTED -> log(LogReason.NO_TRANSITION)
            }
            SEARCHING, MAYBE_ASSIGNED -> when (s) {
                NotificationEvent.SEARCHING -> if (leg.state == SEARCHING) dup() else moveTo(SEARCHING)
                NotificationEvent.ASSIGNED -> moveTo(ASSIGNED, driver = driverFrom(u))
                NotificationEvent.ARRIVING -> moveTo(ARRIVING, driver = driverFrom(u))
                NotificationEvent.NO_DRIVERS -> moveTo(SEARCH_EXPIRED)
                NotificationEvent.CANCELLED -> moveTo(PROVIDER_CANCELLED)
                NotificationEvent.DRIVER_CANCELLED, NotificationEvent.ENDED, NotificationEvent.TRIP_STARTED -> log(LogReason.NO_TRANSITION)
            }
            ASSIGNED, ARRIVING -> when (s) {
                NotificationEvent.SEARCHING -> moveTo(SEARCHING, bump = true, driver = DriverInfo())
                NotificationEvent.ASSIGNED ->
                    if (isNewPlate(u)) moveTo(ASSIGNED, bump = true, driver = driverFrom(u)) else refreshAndDrop(u)
                NotificationEvent.ARRIVING ->
                    if (leg.state == ARRIVING) refreshAndDrop(u) else moveTo(ARRIVING, driver = leg.driver.merge(u))
                NotificationEvent.DRIVER_CANCELLED -> moveTo(DRIVER_CANCELLED, bump = true, driver = DriverInfo())
                NotificationEvent.NO_DRIVERS, NotificationEvent.CANCELLED -> moveTo(PROVIDER_CANCELLED)
                NotificationEvent.ENDED -> moveTo(ENDED)
                NotificationEvent.TRIP_STARTED -> moveTo(ON_TRIP)
            }
            MAYBE_CHANGED -> when (s) {
                NotificationEvent.SEARCHING -> moveTo(SEARCHING, bump = true, driver = DriverInfo())
                NotificationEvent.ASSIGNED ->
                    if (isNewPlate(u)) {
                        moveTo(ASSIGNED, bump = true, driver = driverFrom(u))
                    } else {
                        moveTo(leg.returnState ?: ASSIGNED, driver = leg.driver.merge(u), withdraw = WithdrawReason.SAME_DRIVER_STILL_COMING)
                    }
                NotificationEvent.ARRIVING -> moveTo(ARRIVING, driver = leg.driver.merge(u))
                NotificationEvent.DRIVER_CANCELLED -> moveTo(DRIVER_CANCELLED, bump = true, driver = DriverInfo())
                NotificationEvent.NO_DRIVERS, NotificationEvent.CANCELLED -> moveTo(PROVIDER_CANCELLED)
                NotificationEvent.ENDED -> moveTo(ENDED)
                NotificationEvent.TRIP_STARTED -> moveTo(ON_TRIP)
            }
            DRIVER_CANCELLED, MAYBE_NEW_DRIVER -> when (s) {
                NotificationEvent.SEARCHING -> moveTo(SEARCHING)
                NotificationEvent.ASSIGNED -> moveTo(ASSIGNED, driver = driverFrom(u))
                NotificationEvent.ARRIVING -> moveTo(ARRIVING, driver = driverFrom(u))
                NotificationEvent.DRIVER_CANCELLED -> dup()
                NotificationEvent.NO_DRIVERS, NotificationEvent.CANCELLED -> moveTo(PROVIDER_CANCELLED)
                NotificationEvent.ENDED, NotificationEvent.TRIP_STARTED -> log(LogReason.NO_TRANSITION)
            }
            ON_TRIP -> if (s == NotificationEvent.ENDED) moveTo(ENDED) else log(LogReason.NO_TRANSITION)
            CANCEL_REQUESTED, CANCEL_GUIDED -> when (s) {
                NotificationEvent.SEARCHING, NotificationEvent.ASSIGNED, NotificationEvent.ARRIVING, NotificationEvent.DRIVER_CANCELLED -> {
                    updateProviderState(u)
                    outputs += StateOutput.CancelCardUpdate(leg.providerState!!)
                }
                NotificationEvent.NO_DRIVERS -> endedByApp(SEARCH_EXPIRED)
                NotificationEvent.CANCELLED ->
                    if (u.source == SignalSource.TEMPLATE) confirm(EvidenceRung.TEMPLATE) else outputs += StateOutput.CheckScreenPrompt
                NotificationEvent.ENDED, NotificationEvent.TRIP_STARTED -> log(LogReason.NO_TRANSITION)
            }
            USER_SAYS_CANCELLED, CANCEL_UNCONFIRMED -> when (s) {
                NotificationEvent.SEARCHING, NotificationEvent.ASSIGNED, NotificationEvent.ARRIVING, NotificationEvent.DRIVER_CANCELLED -> {
                    updateProviderState(u)
                    outputs += StateOutput.Alert(AlertKind.STILL_LIVE)
                    moveTo(CANCEL_UNCONFIRMED)
                }
                NotificationEvent.NO_DRIVERS -> endedByApp(SEARCH_EXPIRED)
                NotificationEvent.CANCELLED ->
                    if (u.source == SignalSource.TEMPLATE) confirm(EvidenceRung.TEMPLATE) else outputs += StateOutput.Alert(AlertKind.CHECK_SCREEN)
                NotificationEvent.ENDED, NotificationEvent.TRIP_STARTED -> log(LogReason.NO_TRANSITION)
            }
            else -> log(LogReason.WRONG_LEG_KIND)
        }
    }

    private fun unknown(e: UnknownUpdate) {
        if (isStaleReplay(e.replayed, e.postTimeWallMs)) return log(LogReason.STALE_REPLAY)
        when (leg.state) {
            SELECTED, OPENED, QUOTED -> log(LogReason.NO_TRANSITION)
            BOOK_REQUESTED, SEARCHING -> moveTo(MAYBE_ASSIGNED)
            MAYBE_ASSIGNED, MAYBE_CHANGED, MAYBE_NEW_DRIVER -> dup()
            ASSIGNED, ARRIVING -> moveTo(MAYBE_CHANGED, returnState = leg.state)
            DRIVER_CANCELLED -> moveTo(MAYBE_NEW_DRIVER)
            ON_TRIP -> log(LogReason.IGNORED_ON_TRIP)
            CANCEL_REQUESTED, CANCEL_GUIDED -> outputs += StateOutput.CheckScreenPrompt
            USER_SAYS_CANCELLED, CANCEL_UNCONFIRMED -> outputs += StateOutput.Alert(AlertKind.CHECK_SCREEN)
            else -> log(LogReason.WRONG_LEG_KIND)
        }
        e.sbnKey?.let { mark(it, EventType.UNKNOWN_UPDATE) }
    }

    private fun removal(e: NotificationRemoved) {
        if (!(e.appCancel && e.wasOngoing)) return log(LogReason.REMOVAL_RECORDED)
        leg = leg.copy(removalAtElapsedMs = now.elapsedMs)
        when {
            rung3Complete() -> cancelledColumn(EvidenceRung.REMOVAL_PLUS_CAPTURE)
            leg.state in LegState.CHAIN_STATES -> {
                log(LogReason.REMOVAL_RECORDED)
                outputs += StateOutput.RequestCapture(CapturePurpose.CANCEL_PROOF)
            }
            else -> log(LogReason.REMOVAL_RECORDED)
        }
    }

    private fun capture(e: CaptureVerdict) {
        if (!e.cancelledKeywordsFound) return log(LogReason.CAPTURE_RECORDED)
        leg = leg.copy(cancelledCaptureAtElapsedMs = now.elapsedMs)
        if (rung3Complete()) cancelledColumn(EvidenceRung.REMOVAL_PLUS_CAPTURE) else log(LogReason.CAPTURE_RECORDED)
    }

    private fun rung3Complete(): Boolean {
        val removal = leg.removalAtElapsedMs ?: return false
        val capture = leg.cancelledCaptureAtElapsedMs ?: return false
        return kotlin.math.abs(removal - capture) <= Statechart.RUNG3_WINDOW_MS
    }

    /** The "cancelled tpl / removal rung" column. */
    private fun cancelledColumn(rung: EvidenceRung) {
        when (leg.state) {
            SELECTED, OPENED, QUOTED, BOOK_REQUESTED, SEARCHING, MAYBE_ASSIGNED, ASSIGNED, ARRIVING, MAYBE_CHANGED,
            DRIVER_CANCELLED, MAYBE_NEW_DRIVER -> moveTo(PROVIDER_CANCELLED)
            in LegState.CHAIN_STATES -> confirm(rung)
            else -> log(LogReason.NO_TRANSITION)
        }
    }

    private fun driverData(e: DriverData) {
        val driverState = leg.underlyingState in LegState.DRIVER_STATES
        if (!driverState || !e.source.isAdmissible) return log(LogReason.NOT_A_DRIVER_STATE)
        leg = leg.copy(driver = leg.driver.withValues(e.etaMin, e.distanceMeters, e.source, now))
        outputs += StateOutput.DriverUpdated
    }

    private fun answer(e: UserAnswer) {
        val a = e.answer
        when (leg.state) {
            SELECTED, OPENED, QUOTED, BOOK_REQUESTED ->
                if (a == Answer.BOOKED_HERE) {
                    if (leg.state == BOOK_REQUESTED) dup() else moveTo(BOOK_REQUESTED)
                } else {
                    notApplicable()
                }
            MAYBE_ASSIGNED -> when (a) {
                Answer.YES -> moveTo(ASSIGNED, driver = DriverInfo(assignedAtElapsedMs = now.elapsedMs, assignedAtWallMs = now.wallMs), withdraw = WithdrawReason.ANSWERED)
                Answer.NOT_YET -> moveTo(SEARCHING, withdraw = WithdrawReason.ANSWERED)
                Answer.CHECK_SCREEN -> outputs += StateOutput.RequestCapture(CapturePurpose.CHECK_SCREEN)
                else -> notApplicable()
            }
            MAYBE_CHANGED -> when (a) {
                Answer.YES -> moveTo(leg.returnState ?: ASSIGNED, withdraw = WithdrawReason.ANSWERED)
                Answer.DRIVER_CANCELLED -> moveTo(DRIVER_CANCELLED, bump = true, driver = DriverInfo(), withdraw = WithdrawReason.ANSWERED)
                Answer.CHECK_SCREEN -> outputs += StateOutput.RequestCapture(CapturePurpose.CHECK_SCREEN)
                else -> notApplicable()
            }
            MAYBE_NEW_DRIVER -> when (a) {
                Answer.YES -> moveTo(SEARCHING, withdraw = WithdrawReason.ANSWERED)
                Answer.NO_IT_ENDED -> moveTo(PROVIDER_CANCELLED, withdraw = WithdrawReason.ANSWERED)
                Answer.CHECK_SCREEN -> outputs += StateOutput.RequestCapture(CapturePurpose.CHECK_SCREEN)
                else -> notApplicable()
            }
            CANCEL_REQUESTED, CANCEL_GUIDED -> when (a) {
                Answer.I_CANCELLED_IT -> {
                    moveTo(USER_SAYS_CANCELLED)
                    outputs += StateOutput.UserSaysCancelled
                }
                Answer.CHECK_SCREEN -> outputs += StateOutput.RequestCapture(CapturePurpose.CANCEL_PROOF)
                else -> notApplicable()
            }
            USER_SAYS_CANCELLED, CANCEL_UNCONFIRMED ->
                if (a == Answer.CHECK_SCREEN) outputs += StateOutput.RequestCapture(CapturePurpose.CANCEL_PROOF) else notApplicable()
            else -> notApplicable()
        }
    }

    private fun timeout(e: Timeout) {
        when (leg.state) {
            MAYBE_ASSIGNED, MAYBE_CHANGED, MAYBE_NEW_DRIVER ->
                if (e.kind == TimeoutKind.REPROMPT) outputs += StateOutput.RePrompt(leg.state.promptKind()!!) else log(LogReason.NO_TRANSITION)
            DRIVER_CANCELLED -> when {
                e.kind != TimeoutKind.DRIVER_CANCELLED_WAIT -> log(LogReason.NO_TRANSITION)
                e.ongoingPresent -> moveTo(MAYBE_NEW_DRIVER)
                else -> log(LogReason.NO_ONGOING_POST)
            }
            CANCEL_REQUESTED, CANCEL_GUIDED ->
                if (e.kind == TimeoutKind.REMINDER) outputs += StateOutput.ReminderDue else log(LogReason.NO_TRANSITION)
            USER_SAYS_CANCELLED, CANCEL_UNCONFIRMED -> when (e.kind) {
                TimeoutKind.FOLLOW_UP_CHECK ->
                    if (e.ongoingPresent) {
                        outputs += StateOutput.Alert(AlertKind.STILL_LIVE)
                        moveTo(CANCEL_UNCONFIRMED)
                    } else {
                        log(LogReason.FOLLOW_UP_CLEAR)
                    }
                TimeoutKind.REMINDER -> outputs += StateOutput.ReminderDue
                else -> log(LogReason.NO_TRANSITION)
            }
            else -> log(LogReason.NO_TRANSITION)
        }
    }

    private fun chain(e: Chain) {
        when (e.step) {
            ChainStep.QUEUE ->
                if (leg.state in Statechart.QUEUEABLE) {
                    moveTo(CANCEL_REQUESTED, providerState = leg.underlyingState, withdraw = WithdrawReason.CANCELLING)
                } else {
                    log(LogReason.NO_TRANSITION)
                }
            ChainStep.GUIDE ->
                if (leg.state == CANCEL_REQUESTED || leg.state == CANCEL_UNCONFIRMED || leg.state == USER_SAYS_CANCELLED) {
                    moveTo(CANCEL_GUIDED)
                } else {
                    log(LogReason.NO_TRANSITION)
                }
            ChainStep.ABORT ->
                if (leg.state in LegState.CHAIN_STATES) moveTo(leg.providerState ?: SEARCHING, providerState = null) else log(LogReason.NO_TRANSITION)
        }
    }

    private fun lifecycle(e: Lifecycle) {
        when {
            e.step == LifecycleStep.OPENED && leg.state == SELECTED -> moveTo(OPENED)
            e.step == LifecycleStep.QUOTED && (leg.state == SELECTED || leg.state == OPENED) -> moveTo(QUOTED)
            else -> log(LogReason.NO_TRANSITION)
        }
    }

    // ---------------------------------------------------------------- ONDC legs

    fun network(event: RideEvent) {
        if (leg.state == ENDED) return endedRow(event)
        if (leg.state.isTerminal) return log(LogReason.TERMINAL_STATE)
        when (event) {
            is Protocol -> protocol(event)
            is UserAnswer -> when {
                leg.state == SOFT_CANCEL && event.answer == Answer.ONDC_CONFIRM_CANCEL ->
                    moveTo(CONFIRM_CANCEL, returnState = leg.returnState)
                leg.state == SOFT_CANCEL && event.answer == Answer.ONDC_KEEP_RIDE -> moveTo(leg.returnState ?: CONFIRMED)
                else -> notApplicable()
            }
            is DriverData -> driverData(event)
            is Lifecycle -> log(LogReason.NO_TRANSITION)
            is Timeout ->
                if (event.kind == TimeoutKind.REMINDER && (leg.state == SOFT_CANCEL || leg.state == CONFIRM_CANCEL)) {
                    outputs += StateOutput.ReminderDue
                } else {
                    log(LogReason.NO_TRANSITION)
                }
            else -> log(LogReason.WRONG_LEG_KIND)
        }
    }

    private fun protocol(p: Protocol) {
        val key = p.messageId
        if (key != null && leg.marks[key] == AppliedMark(EventType.PROTOCOL, leg.generation)) return log(LogReason.IDEMPOTENT)
        val state = leg.state
        when (p.kind) {
            ProtocolKind.SEARCH_STARTED -> if (state in LegState.PRE_BOOKING) moveTo(SEARCHING_NET) else log(LogReason.NO_TRANSITION)
            ProtocolKind.QUOTES -> log(if (state == SEARCHING_NET) LogReason.QUOTES_UPDATED else LogReason.NO_TRANSITION)
            ProtocolKind.SELECTED -> if (state == SEARCHING_NET) moveTo(ON_SELECT) else log(LogReason.NO_TRANSITION)
            ProtocolKind.INITIALIZED -> if (state == SEARCHING_NET || state == ON_SELECT) moveTo(INIT) else log(LogReason.NO_TRANSITION)
            ProtocolKind.CONFIRMED -> when {
                state !in LegState.ONDC_PRE_CONFIRM -> log(LogReason.NO_TRANSITION)
                p.tapId == null -> log(LogReason.MISSING_TAP)
                else -> moveTo(CONFIRMED)
            }
            ProtocolKind.DRIVER_ASSIGNED -> when (state) {
                CONFIRMED -> moveTo(ASSIGNED, driver = networkDriver(p))
                ASSIGNED, ARRIVING -> {
                    leg = leg.copy(driver = leg.driver.withValues(p.etaMin, p.distanceMeters, EtaSource.NETWORK, now))
                    outputs += StateOutput.DriverUpdated
                }
                else -> log(LogReason.NO_TRANSITION)
            }
            ProtocolKind.ARRIVING -> when (state) {
                CONFIRMED -> moveTo(ARRIVING, driver = networkDriver(p))
                ASSIGNED -> moveTo(ARRIVING, driver = leg.driver.withValues(p.etaMin, p.distanceMeters, EtaSource.NETWORK, now))
                else -> log(LogReason.NO_TRANSITION)
            }
            ProtocolKind.TRIP_STARTED -> if (state in Statechart.ONDC_CANCELLABLE) moveTo(ON_TRIP) else log(LogReason.NO_TRANSITION)
            ProtocolKind.ENDED -> if (state in Statechart.ONDC_CANCELLABLE || state == ON_TRIP) moveTo(ENDED) else log(LogReason.NO_TRANSITION)
            ProtocolKind.SOFT_CANCEL_QUOTED -> {
                val fee = p.feeInr ?: 0
                when (state) {
                    in Statechart.ONDC_CANCELLABLE -> {
                        moveTo(SOFT_CANCEL, returnState = state)
                        leg = leg.copy(ondcFeeInr = fee)
                        outputs += StateOutput.OndcFeeShown(fee)
                    }
                    SOFT_CANCEL -> {
                        leg = leg.copy(ondcFeeInr = fee)
                        outputs += StateOutput.OndcFeeShown(fee)
                    }
                    else -> log(LogReason.NO_TRANSITION)
                }
            }
            ProtocolKind.CANCEL_CONFIRMED -> when (state) {
                CONFIRM_CANCEL -> confirm(EvidenceRung.ONDC_ON_CANCEL)
                in Statechart.ONDC_CANCELLABLE, SOFT_CANCEL -> moveTo(PROVIDER_CANCELLED)
                else -> log(LogReason.NO_TRANSITION)
            }
            ProtocolKind.FAILED -> when (state) {
                in LegState.ONDC_PRE_CONFIRM -> moveTo(SEARCH_EXPIRED)
                ON_TRIP -> log(LogReason.NO_TRANSITION)
                else -> moveTo(PROVIDER_CANCELLED)
            }
        }
        if (key != null) mark(key, EventType.PROTOCOL)
    }

    // ---------------------------------------------------------------- helpers

    private fun moveTo(
        target: RideState,
        bump: Boolean = false,
        driver: DriverInfo = leg.driver,
        returnState: RideState? = null,
        providerState: RideState? = leg.providerState,
        withdraw: WithdrawReason? = null,
    ) {
        val from = leg.state
        val generation = if (bump) leg.generation + 1 else leg.generation
        if (from != target) {
            from.promptKind()?.let { outputs += StateOutput.WithdrawPrompt(it, withdraw ?: reasonFor(target)) }
            target.promptKind()?.let { outputs += StateOutput.RaisePrompt(it) }
        }
        if (bump) outputs += StateOutput.GenerationBumped(leg.generation, generation)
        val changed = from != target || bump
        leg = leg.copy(
            state = target,
            generation = generation,
            driver = driver,
            returnState = returnState,
            providerState = providerState,
            stateSeq = if (changed) leg.stateSeq + 1 else leg.stateSeq,
            enteredAtElapsedMs = if (changed) now.elapsedMs else leg.enteredAtElapsedMs,
        )
    }

    private fun reasonFor(target: RideState): WithdrawReason = when (target) {
        SEARCHING -> WithdrawReason.SEARCHING_AGAIN
        ASSIGNED -> WithdrawReason.DRIVER_ASSIGNED
        ARRIVING -> WithdrawReason.DRIVER_ARRIVING
        DRIVER_CANCELLED -> WithdrawReason.DRIVER_CANCELLED
        SEARCH_EXPIRED -> WithdrawReason.SEARCH_EXPIRED
        PROVIDER_CANCELLED -> WithdrawReason.PROVIDER_CANCELLED
        ENDED -> WithdrawReason.RIDE_ENDED
        ON_TRIP -> WithdrawReason.TRIP_STARTED
        CANCEL_REQUESTED -> WithdrawReason.CANCELLING
        else -> WithdrawReason.ANSWERED
    }

    /** I3: only rungs 1–3 reach CANCEL_CONFIRMED. */
    private fun confirm(rung: EvidenceRung) {
        check(rung.confirms) { "rung ${rung.rung} cannot confirm a cancel" }
        moveTo(CANCEL_CONFIRMED)
        leg = leg.copy(confirmedBy = rung)
        outputs += StateOutput.CancelConfirmed(rung)
    }

    private fun endedByApp(target: RideState) {
        moveTo(target)
        leg = leg.copy(endedByApp = true)
        outputs += StateOutput.EndedByApp(target)
    }

    private fun updateProviderState(u: ProviderUpdate) {
        val target = when (u.signal) {
            NotificationEvent.SEARCHING -> SEARCHING
            NotificationEvent.ASSIGNED -> ASSIGNED
            NotificationEvent.ARRIVING -> ARRIVING
            else -> DRIVER_CANCELLED
        }
        val driver = when (target) {
            SEARCHING, DRIVER_CANCELLED -> DriverInfo()
            ASSIGNED -> if (isNewPlate(u) || leg.providerState !in LegState.DRIVER_STATES) driverFrom(u) else leg.driver.merge(u)
            else -> if (leg.providerState in LegState.DRIVER_STATES) leg.driver.merge(u) else driverFrom(u)
        }
        leg = leg.copy(providerState = target, driver = driver)
    }

    private fun isNewPlate(u: ProviderUpdate): Boolean {
        val incoming = u.plateHash ?: return false
        val current = leg.driver.plateHash ?: return false
        return incoming != current
    }

    private fun refreshAndDrop(u: ProviderUpdate) {
        refreshDriverValues(u)
        dup()
    }

    private fun refreshDriverValues(u: ProviderUpdate) {
        val merged = leg.driver.merge(u)
        if (merged != leg.driver) {
            leg = leg.copy(driver = merged)
            outputs += StateOutput.DriverUpdated
        }
    }

    private fun driverFrom(u: ProviderUpdate): DriverInfo =
        DriverInfo(
            assignedAtElapsedMs = now.elapsedMs,
            assignedAtWallMs = u.postTimeWallMs ?: now.wallMs,
            plateHash = u.plateHash,
        ).withValues(u.etaMin, u.distanceMeters, u.valueSource, now)

    private fun networkDriver(p: Protocol): DriverInfo =
        DriverInfo(assignedAtElapsedMs = now.elapsedMs, assignedAtWallMs = now.wallMs)
            .withValues(p.etaMin, p.distanceMeters, EtaSource.NETWORK, now)

    private fun DriverInfo.merge(u: ProviderUpdate): DriverInfo =
        withValues(u.etaMin, u.distanceMeters, u.valueSource, now).let { merged ->
            if (merged.plateHash == null && u.plateHash != null) merged.copy(plateHash = u.plateHash) else merged
        }

    /** A re-read post older than the leg's latest evidence, or older than the leg itself (left over from an earlier ride). */
    private fun isStaleReplay(replayed: Boolean, postTimeWallMs: Long?): Boolean {
        if (!replayed || postTimeWallMs == null) return false
        val floor = maxOf(leg.lastEvidenceWallMs ?: Long.MIN_VALUE, leg.notBeforeWallMs ?: Long.MIN_VALUE)
        return postTimeWallMs < floor
    }

    private fun dropCancelProofHalves() {
        if (leg.removalAtElapsedMs != null || leg.cancelledCaptureAtElapsedMs != null) {
            leg = leg.copy(removalAtElapsedMs = null, cancelledCaptureAtElapsedMs = null)
        }
    }

    private fun mark(key: String, type: EventType) {
        leg = leg.copy(marks = leg.marks + (key to AppliedMark(type, leg.generation)))
    }

    private fun dup() {
        outputs += StateOutput.DupDrop
    }

    private fun log(reason: LogReason) {
        outputs += StateOutput.Logged(reason)
    }

    private fun notApplicable() = log(LogReason.ANSWER_NOT_APPLICABLE)
}

/** Replaces ETA/distance with readings that carry an admissible source; anything else is ignored (I2). */
internal fun DriverInfo.withValues(etaMin: Int?, distanceMeters: Int?, source: EtaSource, now: Moment): DriverInfo {
    if (!source.isAdmissible) return this
    var out = this
    if (etaMin != null) out = out.copy(etaMin = etaMin, etaSource = source, etaAtElapsedMs = now.elapsedMs)
    if (distanceMeters != null) out = out.copy(distanceMeters = distanceMeters, distanceSource = source)
    return out
}
