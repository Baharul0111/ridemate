package app.ridemate.core.invariants

import app.ridemate.core.guardian.ActionKind
import app.ridemate.core.guardian.DriverCard
import app.ridemate.core.guardian.Guardian
import app.ridemate.core.guardian.GuardianEffect
import app.ridemate.core.guardian.GuardianState
import app.ridemate.core.guardian.LegRole
import app.ridemate.core.guardian.SheetKind
import app.ridemate.core.guardian.TimedEffect
import app.ridemate.core.guardian.TimerKind
import app.ridemate.core.guardian.TransitionLogEntry
import app.ridemate.core.model.RideState
import app.ridemate.core.state.EventType
import app.ridemate.core.state.LegState
import app.ridemate.core.state.isAdmissible
import app.ridemate.core.watch.PostAuditEntry
import app.ridemate.core.watch.PostKind
import app.ridemate.core.watch.PostOutcome
import java.lang.reflect.Modifier

/** §4.9 invariants, checked over the guardian's and RideWatch's audit trails (tests and debug builds). */
enum class Invariant { I1, I2, I3, I4, I5, I6, I7, I8, I9, I10 }

enum class ViolationCode {
    ACTION_WITHOUT_TAP, TAP_NOT_RECORDED, TAP_AFTER_ACTION, ONDC_CONFIRM_WITHOUT_BOOK_TAP,
    VALUE_WITHOUT_ADMISSIBLE_SOURCE,
    CONFIRMED_WITHOUT_EVIDENCE,
    SWITCH_WITHOUT_RECORD, SWITCH_FROM_WRONG_SHEET, SWITCH_ON_STALE_GENERATION,
    FREE_TEXT_IN_EFFECT,
    FRAME_WITHOUT_TAP, FRAME_OUTSIDE_TAP_WINDOW,
    NON_ALLOWLISTED_POST_READ, NON_ALLOWLISTED_POST_AUDITED,
    EXTRA_LEG_WITHOUT_REMINDER, FOREGROUND_SERVICE_NOT_REQUIRED, FOLLOW_UP_NOT_SCHEDULED,
    POST_UNRESOLVED, POST_RESOLVED_LATE, POST_DROPPED_UNEXPLAINED,
    CHAIN_FOR_LEG_WITHOUT_DRIVER, RIDE_BANNER_WITHOUT_DRIVER,
}

data class Violation(val invariant: Invariant, val code: ViolationCode, val legId: String? = null, val ref: String? = null)

data class InvariantReport(val violations: List<Violation>) {
    val ok: Boolean get() = violations.isEmpty()
    fun of(invariant: Invariant): List<Violation> = violations.filter { it.invariant == invariant }
    operator fun plus(other: InvariantReport) = InvariantReport(violations + other.violations)
}

/** Debug builds crash on a violation; release builds block the action (done in the guardian) and log it. */
enum class InvariantMode { CRASH, LOG }

class InvariantViolationException(val report: InvariantReport) : IllegalStateException("invariant violations: ${report.violations}")

fun InvariantReport.enforce(mode: InvariantMode, log: (Violation) -> Unit = {}) {
    if (ok) return
    violations.forEach(log)
    if (mode == InvariantMode.CRASH) throw InvariantViolationException(this)
}

object Invariants {
    /** I9 bounds: rule hits and redacted posts within 3 s; rule misses within 3 s plus the 6 s classifier deadline. */
    const val HIT_BOUND_MS = 3_000L
    const val MISS_BOUND_MS = 3_000L + 6_000L

    private val TAPPED_ACTIONS = setOf(
        ActionKind.BOOKED_HERE, ActionKind.CANCEL_CHAIN_START, ActionKind.CANCEL_GUIDE, ActionKind.SWITCH,
        ActionKind.ONDC_BOOK, ActionKind.ONDC_SOFT_CANCEL, ActionKind.ONDC_CONFIRM_CANCEL, ActionKind.PAY_HANDOFF,
    )

    fun checkGuardian(guardian: Guardian, captureWindowMs: Long = 2_000L): InvariantReport {
        val state = guardian.state
        val effects = guardian.effectLog
        return InvariantReport(
            checkI1(state, guardian.transitionLog) + checkI2(effects) + checkI3(state) + checkI4(state) +
                checkI5(effects) + checkI6(state, captureWindowMs) + checkI8(guardian) + checkI10(state, effects),
        )
    }

    /** I1: every book, cancel, switch and ONDC confirm/cancel links to a recorded user tap that came first. */
    fun checkI1(state: GuardianState, transitions: List<TransitionLogEntry>): List<Violation> {
        val out = mutableListOf<Violation>()
        for (action in state.actions.filter { it.kind in TAPPED_ACTIONS }) {
            val tap = action.tapId
            if (tap == null) {
                out += Violation(Invariant.I1, ViolationCode.ACTION_WITHOUT_TAP, action.legId, action.kind.name)
                continue
            }
            val recorded = state.taps.filter { it.tapId == tap }
            when {
                recorded.isEmpty() -> out += Violation(Invariant.I1, ViolationCode.TAP_NOT_RECORDED, action.legId, action.kind.name)
                recorded.none { it.atElapsedMs <= action.atElapsedMs } ->
                    out += Violation(Invariant.I1, ViolationCode.TAP_AFTER_ACTION, action.legId, action.kind.name)
            }
        }
        val booked = state.actions.filter { it.kind == ActionKind.ONDC_BOOK }.map { it.legId }.toSet()
        transitions.filter { it.to == RideState.CONFIRMED && it.from != RideState.CONFIRMED }.forEach {
            if (it.legId !in booked) out += Violation(Invariant.I1, ViolationCode.ONDC_CONFIRM_WITHOUT_BOOK_TAP, it.legId)
        }
        return out
    }

    /** I2 + D8: an ETA or distance is shown only with a VERIFIED, template, protocol or user source. */
    fun checkI2(effects: List<TimedEffect>): List<Violation> = effects.flatMap { (_, effect) ->
        driverCards(effect).flatMap { card ->
            buildList {
                if (card.etaMin != null && !card.etaSource.isAdmissible) {
                    add(Violation(Invariant.I2, ViolationCode.VALUE_WITHOUT_ADMISSIBLE_SOURCE, card.legId, "eta"))
                }
                if (card.distanceMeters != null && !card.distanceSource.isAdmissible) {
                    add(Violation(Invariant.I2, ViolationCode.VALUE_WITHOUT_ADMISSIBLE_SOURCE, card.legId, "distance"))
                }
            }
        }
    }

    /** I3: CANCEL_CONFIRMED only via rungs 1–3, each recorded as evidence. */
    fun checkI3(state: GuardianState): List<Violation> =
        (state.legs.values + state.retiredLegs).filter { it.leg.state == RideState.CANCEL_CONFIRMED }.mapNotNull { rec ->
            val evidence = state.evidence.filter { it.legId == rec.legId && it.rung.confirms }
            val rung = rec.leg.confirmedBy
            if (rung == null || !rung.confirms || evidence.isEmpty()) {
                Violation(Invariant.I3, ViolationCode.CONFIRMED_WITHOUT_EVIDENCE, rec.legId)
            } else {
                null
            }
        }

    /** I4: every switch follows a Yes on a better-ride sheet bound to the generations current at the tap. */
    fun checkI4(state: GuardianState): List<Violation> = state.actions.filter { it.kind == ActionKind.SWITCH }.flatMap { action ->
        val record = state.switches.firstOrNull { it.tapId == action.tapId && it.toLegId == action.legId }
        when {
            record == null -> listOf(Violation(Invariant.I4, ViolationCode.SWITCH_WITHOUT_RECORD, action.legId))
            record.sheetKind != SheetKind.BETTER_RIDE && record.sheetKind != SheetKind.SECOND_DRIVER ->
                listOf(Violation(Invariant.I4, ViolationCode.SWITCH_FROM_WRONG_SHEET, action.legId, record.sheetId))
            record.bound != record.current ->
                listOf(Violation(Invariant.I4, ViolationCode.SWITCH_ON_STALE_GENERATION, action.legId, record.sheetId))
            else -> emptyList()
        }
    }

    /**
     * I5: effects reach the UI as keys, enums, numbers and ids only. Any String-typed field (directly, in a list or in
     * a nested value) must be an id (named `…Id` or `…Ids`).
     */
    fun checkI5(effects: List<TimedEffect>): List<Violation> = checkI5Values(effects.map { it.effect })

    /** [checkI5] over any UI-bound values (the app reuses it for its own view models in debug builds). */
    fun checkI5Values(values: List<Any>): List<Violation> =
        values.flatMap { textFields(it, it.javaClass.simpleName) }
            .map { Violation(Invariant.I5, ViolationCode.FREE_TEXT_IN_EFFECT, ref = it) }

    /** I6: a frame is processed only within the window after a user tap (or from the demo). */
    fun checkI6(state: GuardianState, captureWindowMs: Long): List<Violation> = state.captures.filter { it.accepted && !it.demo }.mapNotNull {
        val tapAt = it.tapAtElapsedMs
        when {
            tapAt == null -> Violation(Invariant.I6, ViolationCode.FRAME_WITHOUT_TAP, it.legId)
            it.frameAtElapsedMs < tapAt || it.frameAtElapsedMs - tapAt > captureWindowMs ->
                Violation(Invariant.I6, ViolationCode.FRAME_OUTSIDE_TAP_WINDOW, it.legId)
            else -> null
        }
    }

    /**
     * I7: posts from other packages are dropped unread. [reads] pairs each delivered package with whether its
     * contents were read; [audit] must hold allowlisted packages only.
     */
    fun checkI7(reads: List<Pair<String, Boolean>>, audit: List<PostAuditEntry>, allowlist: Set<String>): List<Violation> =
        reads.filter { (pkg, read) -> read && pkg !in allowlist }
            .map { Violation(Invariant.I7, ViolationCode.NON_ALLOWLISTED_POST_READ, ref = it.first) } +
            audit.filter { it.packageName !in allowlist }
                .map { Violation(Invariant.I7, ViolationCode.NON_ALLOWLISTED_POST_AUDITED, ref = it.packageName) }

    /** I8: while an extra leg is non-terminal and not dismissed, reminders stay scheduled and the FGS stays up. */
    fun checkI8(guardian: Guardian): List<Violation> {
        val state = guardian.state
        val out = mutableListOf<Violation>()
        val pending = state.legs.values.filter { it.role == LegRole.EXTRA && !it.dismissed && !it.leg.state.isTerminal }
        for (rec in pending) {
            if (state.reminders == null || guardian.nextDueElapsedMs() == null) {
                out += Violation(Invariant.I8, ViolationCode.EXTRA_LEG_WITHOUT_REMINDER, rec.legId)
            }
            if (!guardian.needsForegroundService) out += Violation(Invariant.I8, ViolationCode.FOREGROUND_SERVICE_NOT_REQUIRED, rec.legId)
        }
        val followUpRan = guardian.transitionLog.filter { it.eventType == EventType.TIMEOUT }
        state.legs.values.filter { it.leg.state == RideState.USER_SAYS_CANCELLED && !it.dismissed }.forEach { rec ->
            val scheduled = state.timers.any { it.legId == rec.legId && it.kind == TimerKind.FOLLOW_UP_CHECK }
            val ran = followUpRan.any { it.legId == rec.legId && it.from == RideState.USER_SAYS_CANCELLED }
            if (!scheduled && !ran) out += Violation(Invariant.I8, ViolationCode.FOLLOW_UP_NOT_SCHEDULED, rec.legId)
        }
        return out
    }

    /**
     * I9: every allowlisted post on a booked, non-terminal leg produced a RideEvent (incl. a MAYBE prompt) or a logged
     * exact-duplicate drop, within 3 s for rule hits and redacted posts and 3 s + 6 s for rule misses. A post covered by
     * a newer one on the same key counts when that one resolved within this post's own bound.
     */
    fun checkI9(audit: List<PostAuditEntry>, nowElapsedMs: Long): List<Violation> {
        val bySeq = audit.associateBy { it.seq }
        return audit.filter { it.coveredByI9 }.mapNotNull { entry ->
            val bound = if (entry.kind == PostKind.RULE_MISS) MISS_BOUND_MS else HIT_BOUND_MS
            val deadline = entry.arrivedAtElapsedMs + bound
            val resolvedAt: Long? = when (entry.outcome) {
                PostOutcome.EVENT, PostOutcome.DUPLICATE_DROPPED -> entry.resolvedAtElapsedMs
                PostOutcome.COALESCED, PostOutcome.SUPERSEDED -> {
                    val cover = entry.coveredBySeq?.let(bySeq::get)
                    if (cover == null || cover.outcome != PostOutcome.EVENT) {
                        if (nowElapsedMs <= deadline) return@mapNotNull null
                        return@mapNotNull Violation(Invariant.I9, ViolationCode.POST_DROPPED_UNEXPLAINED, entry.legId, "post#${entry.seq}")
                    }
                    if (entry.outcome == PostOutcome.COALESCED) cover.resolvedAtElapsedMs else entry.resolvedAtElapsedMs
                }
                PostOutcome.PENDING_CLASSIFICATION -> null
                PostOutcome.NO_ACTIVE_LEG -> return@mapNotNull Violation(Invariant.I9, ViolationCode.POST_DROPPED_UNEXPLAINED, entry.legId, "post#${entry.seq}")
            }
            when {
                resolvedAt == null && nowElapsedMs > deadline -> Violation(Invariant.I9, ViolationCode.POST_UNRESOLVED, entry.legId, "post#${entry.seq}")
                resolvedAt != null && resolvedAt > deadline -> Violation(Invariant.I9, ViolationCode.POST_RESOLVED_LATE, entry.legId, "post#${entry.seq}")
                else -> null
            }
        }
    }

    /** I10: no chain and no "your ride" banner for a leg whose current generation is not ASSIGNED or ARRIVING. */
    fun checkI10(state: GuardianState, effects: List<TimedEffect>): List<Violation> =
        state.actions.filter { it.kind == ActionKind.CANCEL_CHAIN_START && it.legState !in LegState.DRIVER_STATES }
            .map { Violation(Invariant.I10, ViolationCode.CHAIN_FOR_LEG_WITHOUT_DRIVER, it.legId) } +
            effects.mapNotNull { (_, effect) ->
                (effect as? GuardianEffect.RideBanner)?.takeIf { it.state !in LegState.DRIVER_STATES }
                    ?.let { Violation(Invariant.I10, ViolationCode.RIDE_BANNER_WITHOUT_DRIVER, it.driver.legId) }
            }

    private fun driverCards(effect: GuardianEffect): List<DriverCard> = when (effect) {
        is GuardianEffect.DriverSheet -> effect.drivers
        is GuardianEffect.BetterRideSheet -> listOf(effect.current, effect.candidate)
        is GuardianEffect.RideBanner -> listOf(effect.driver)
        else -> emptyList()
    }

    private fun textFields(value: Any, path: String, depth: Int = 0): List<String> {
        if (depth > 4) return emptyList()
        val out = mutableListOf<String>()
        var type: Class<*>? = value.javaClass
        while (type != null && type != Any::class.java) {
            for (field in type.declaredFields) {
                if (Modifier.isStatic(field.modifiers) || field.isSynthetic) continue
                field.isAccessible = true
                val v = field.get(value) ?: continue
                val name = field.name
                val isId = name.endsWith("Id") || name.endsWith("Ids")
                when {
                    v is String -> if (!isId) out += "$path.$name"
                    v is Collection<*> -> v.filterNotNull().forEachIndexed { i, item ->
                        when {
                            item is String -> if (!isId) out += "$path.$name[$i]"
                            isPlainValue(item) -> Unit
                            else -> out += textFields(item, "$path.$name[$i]", depth + 1)
                        }
                    }
                    isPlainValue(v) -> Unit
                    else -> out += textFields(v, "$path.$name", depth + 1)
                }
            }
            type = type.superclass
        }
        return out
    }

    private fun isPlainValue(v: Any): Boolean =
        v is Number || v is Boolean || v is Enum<*> || v is Char
}
