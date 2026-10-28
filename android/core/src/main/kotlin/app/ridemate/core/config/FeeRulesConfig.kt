package app.ridemate.core.config

import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.min

/**
 * Remote Config `fee_rules` (contracts §2.5). [default] is the Motor Vehicle Aggregator Guidelines 2025 rule (10%,
 * capped at ₹100); [apps] holds each app's published policy keyed by package; [states] holds state-law caps keyed
 * by the two-letter state code.
 */
@Serializable
data class FeeRulesConfig(
    val schema: String,
    val default: FeeRule,
    val apps: Map<String, FeeRule> = emptyMap(),
    val states: Map<String, FeeRule> = emptyMap(),
) {
    internal fun validate() {
        requireNotNull(default.maxInr) { "default.max_inr is required" }
        default.validate("default")
        apps.forEach { (app, rule) -> rule.validate(app) }
        states.forEach { (code, rule) ->
            require(STATE_CODE.matches(code)) { "state code '$code' must be two capital letters" }
            rule.validate(code)
        }
    }
}

private val STATE_CODE = Regex("^[A-Z]{2}$")

/** Every field is optional so app and state entries override only what they set. */
@Serializable
data class FeeRule(
    val pct: Int? = null,
    @SerialName("max_inr") val maxInr: Int? = null,
    @SerialName("free_while_searching") val freeWhileSearching: Boolean? = null,
    @SerialName("free_within_s_after_accept") val freeWithinSAfterAccept: Int? = null,
    @SerialName("free_if_driver_late_min") val freeIfDriverLateMin: Int? = null,
) {
    internal fun validate(where: String) {
        require(pct == null || pct in 0..100) { "$where.pct must be 0–100" }
        require(maxInr == null || maxInr >= 0) { "$where.max_inr must be ≥ 0" }
        require(freeWithinSAfterAccept == null || freeWithinSAfterAccept >= 0) { "$where.free_within_s_after_accept must be ≥ 0" }
        require(freeIfDriverLateMin == null || freeIfDriverLateMin >= 0) { "$where.free_if_driver_late_min must be ≥ 0" }
    }
}

/** Parses and validates `fee_rules`; throws on a wrong schema major or an invalid rule. */
fun parseFeeRules(raw: String): FeeRulesConfig = decodeFareSidePayload(raw, FeeRulesConfig.serializer()).also { it.validate() }

/** The fee note on a cancel card: "Free to cancel", "May apply, up to ₹X", or nothing known. */
sealed interface FeeNote {
    /** Free now; [remainingS] is how long the free window still lasts (null when it is not time-limited). */
    data class Free(val remainingS: Long?) : FeeNote

    data class MayApply(val upToInr: Int) : FeeNote

    data object Unknown : FeeNote
}

/**
 * Estimates whether cancelling a leg now may cost money. It errs towards "may apply": a free window counts only
 * while [FREE_WINDOW_MARGIN_S] of it is left, because the phone sees the acceptance a little after it happened.
 *
 * The upper bound is the app's own cap when it has one, never above the legal cap (the state's, else the default);
 * with a known fare it is further limited to `pct` of the fare.
 */
class FeeEstimator(private val rules: FeeRulesConfig) {
    fun estimate(
        app: Provider,
        legState: RideState,
        secondsSinceAcceptance: Long?,
        driverLateMin: Int?,
        stateCode: String?,
        fareInr: Int? = null,
    ): FeeNote {
        if (app.isNetwork) return FeeNote.Unknown
        val appRule = rules.apps[app.packageName]
        val stateRule = stateCode?.uppercase()?.let { rules.states[it] }
        val assigned = when (legState) {
            in SEARCH_PHASE -> false
            in DRIVER_PHASE -> true
            in CANCEL_CHAIN -> secondsSinceAcceptance != null
            else -> return FeeNote.Unknown
        }
        if (!assigned) {
            val free = appRule?.freeWhileSearching ?: rules.default.freeWhileSearching ?: false
            return if (free) FeeNote.Free(remainingS = null) else mayApply(appRule, stateRule, fareInr)
        }
        val lateLimit = appRule?.freeIfDriverLateMin ?: rules.default.freeIfDriverLateMin
        if (lateLimit != null && driverLateMin != null && driverLateMin > lateLimit) return FeeNote.Free(remainingS = null)
        val window = appRule?.freeWithinSAfterAccept ?: rules.default.freeWithinSAfterAccept
        if (window != null && secondsSinceAcceptance != null) {
            val remaining = window - FREE_WINDOW_MARGIN_S - secondsSinceAcceptance
            if (remaining > 0) return FeeNote.Free(remainingS = remaining)
        }
        return mayApply(appRule, stateRule, fareInr)
    }

    private fun mayApply(appRule: FeeRule?, stateRule: FeeRule?, fareInr: Int?): FeeNote {
        val legalCap = stateRule?.maxInr ?: rules.default.maxInr!!
        var cap = appRule?.maxInr?.let { min(it, legalCap) } ?: legalCap
        val pct = appRule?.pct ?: stateRule?.pct ?: rules.default.pct
        if (fareInr != null && pct != null) cap = min(cap, ceil(fareInr * pct / 100.0).toInt())
        return if (cap <= 0) FeeNote.Free(remainingS = null) else FeeNote.MayApply(cap)
    }

    companion object {
        const val FREE_WINDOW_MARGIN_S: Long = 15

        private val SEARCH_PHASE = setOf(
            RideState.SELECTED, RideState.OPENED, RideState.QUOTED, RideState.BOOK_REQUESTED, RideState.SEARCHING,
            RideState.DRIVER_CANCELLED,
        )
        private val DRIVER_PHASE = setOf(
            RideState.ASSIGNED, RideState.ARRIVING, RideState.MAYBE_ASSIGNED, RideState.MAYBE_CHANGED,
            RideState.MAYBE_NEW_DRIVER,
        )

        /** In the cancel chain the acceptance time is the evidence of whether a driver was assigned. */
        private val CANCEL_CHAIN = setOf(
            RideState.CANCEL_REQUESTED, RideState.CANCEL_GUIDED, RideState.USER_SAYS_CANCELLED, RideState.CANCEL_UNCONFIRMED,
        )
    }
}
