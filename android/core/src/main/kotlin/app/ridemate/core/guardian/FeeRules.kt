package app.ridemate.core.guardian

import app.ridemate.core.config.ConfigJson
import app.ridemate.core.config.SupportedSchemas
import app.ridemate.core.config.schemaMajor
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

enum class FeeNoteKind {
    /** No driver yet: usually free to cancel. */
    FREE_WHILE_SEARCHING,

    /** Inside the app's free window after acceptance; [FeeNote.freeForSeconds] left. */
    FREE_WINDOW,

    /** The driver is later than promised by more than the app's grace minutes (Ola). */
    FREE_DRIVER_LATE,

    /** "May apply, up to ₹X". */
    MAY_APPLY,

    /** ONDC: the network quotes the fee before the user confirms. */
    SHOWN_BEFORE_CONFIRM,

    /** ONDC fee quoted by the network (protocol data). */
    NETWORK_FEE,
}

@Serializable
data class FeeNote(val kind: FeeNoteKind, val upToInr: Int? = null, val freeForSeconds: Int? = null)

/** What a fee estimate is computed from; times are elapsed (monotonic) ms. */
data class FeeQuery(
    val provider: Provider,
    /** The ride status as the provider sees it (looks through MAYBE and cancel-chain states). */
    val rideState: RideState,
    val assignedAtElapsedMs: Long?,
    /** The admissible pickup ETA at assignment, for "driver is late" rules. */
    val promisedEtaMin: Int?,
    val nowElapsedMs: Long,
    val fareInr: Int? = null,
    val ondcFeeInr: Int? = null,
)

/** Fee notes for sheets and cancel cards; also orders the cancel queue by when each free window closes. */
interface FeeEstimator {
    fun note(query: FeeQuery): FeeNote

    /** When the free cancellation window closes, or null when there is none (no driver, or no window). */
    fun freeWindowEndsAtElapsedMs(query: FeeQuery): Long?
}

/** Remote Config `fee_rules` (contracts §2.5): default ← per-app ← per-state overrides. */
@Serializable
data class FeeRule(
    val pct: Int? = null,
    @SerialName("max_inr") val maxInr: Int? = null,
    @SerialName("free_while_searching") val freeWhileSearching: Boolean? = null,
    @SerialName("free_within_s_after_accept") val freeWithinSAfterAccept: Int? = null,
    @SerialName("free_if_driver_late_min") val freeIfDriverLateMin: Int? = null,
) {
    fun overlay(over: FeeRule?): FeeRule = if (over == null) this else FeeRule(
        pct = over.pct ?: pct,
        maxInr = over.maxInr ?: maxInr,
        freeWhileSearching = over.freeWhileSearching ?: freeWhileSearching,
        freeWithinSAfterAccept = over.freeWithinSAfterAccept ?: freeWithinSAfterAccept,
        freeIfDriverLateMin = over.freeIfDriverLateMin ?: freeIfDriverLateMin,
    )
}

@Serializable
data class FeeRules(
    val schema: String = "1.0",
    val default: FeeRule = FeeRule(pct = 10, maxInr = 100, freeWhileSearching = true),
    val apps: Map<String, FeeRule> = emptyMap(),
    /** Indian state code (e.g. `AS`) → overrides; state rules win over app rules. */
    val states: Map<String, FeeRule> = emptyMap(),
) {
    fun ruleFor(packageName: String, stateCode: String?): FeeRule =
        default.overlay(apps[packageName]).overlay(stateCode?.let { states[it] })

    companion object {
        fun parse(raw: String): FeeRules {
            val obj = ConfigJson.parseToJsonElement(raw) as? JsonObject ?: throw IllegalArgumentException("not an object")
            require(schemaMajor(obj) == SupportedSchemas.MAJOR) { "unsupported schema" }
            return ConfigJson.decodeFromJsonElement(serializer(), obj)
        }
    }
}

class RuleBasedFeeEstimator(private val rules: FeeRules, private val stateCode: String? = null) : FeeEstimator {

    override fun note(query: FeeQuery): FeeNote {
        if (query.provider.isNetwork) {
            return query.ondcFeeInr?.let { FeeNote(FeeNoteKind.NETWORK_FEE, upToInr = it) }
                ?: FeeNote(FeeNoteKind.SHOWN_BEFORE_CONFIRM)
        }
        val rule = rules.ruleFor(query.provider.packageName, stateCode)
        val hasDriver = query.rideState == RideState.ASSIGNED || query.rideState == RideState.ARRIVING
        if (!hasDriver && rule.freeWhileSearching != false) return FeeNote(FeeNoteKind.FREE_WHILE_SEARCHING)
        if (hasDriver) {
            freeWindowEndsAtElapsedMs(query)?.let { end ->
                if (query.nowElapsedMs < end) {
                    val seconds = ((end - query.nowElapsedMs + 999) / 1000).toInt()
                    return FeeNote(FeeNoteKind.FREE_WINDOW, freeForSeconds = seconds)
                }
            }
            val lateGrace = rule.freeIfDriverLateMin
            val assignedAt = query.assignedAtElapsedMs
            val promised = query.promisedEtaMin
            if (lateGrace != null && assignedAt != null && promised != null) {
                val lateAt = assignedAt + (promised + lateGrace) * MINUTE_MS
                if (query.nowElapsedMs > lateAt) return FeeNote(FeeNoteKind.FREE_DRIVER_LATE)
            }
        }
        return FeeNote(FeeNoteKind.MAY_APPLY, upToInr = upTo(rule, query.fareInr))
    }

    override fun freeWindowEndsAtElapsedMs(query: FeeQuery): Long? {
        if (query.provider.isNetwork) return null
        val hasDriver = query.rideState == RideState.ASSIGNED || query.rideState == RideState.ARRIVING
        if (!hasDriver) return null
        val window = rules.ruleFor(query.provider.packageName, stateCode).freeWithinSAfterAccept ?: return null
        val assignedAt = query.assignedAtElapsedMs ?: return null
        return assignedAt + window * 1000L
    }

    /** Aggregator Guidelines 2025: pct of the fare capped at max; without a known fare, the cap. */
    private fun upTo(rule: FeeRule, fareInr: Int?): Int? {
        val max = rule.maxInr
        val pct = rule.pct
        if (fareInr == null || pct == null) return max
        val byPct = (fareInr * pct + 99) / 100
        return if (max == null) byPct else minOf(byPct, max)
    }

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}
