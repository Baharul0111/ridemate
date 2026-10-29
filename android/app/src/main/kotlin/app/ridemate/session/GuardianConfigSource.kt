package app.ridemate.session

import app.ridemate.config.ConfigKeys
import app.ridemate.config.ConfigRepository
import app.ridemate.core.config.CancelCardsConfig
import app.ridemate.core.config.CancelStep
import app.ridemate.core.config.NotificationTemplatesConfig
import app.ridemate.core.config.parseCancelCards
import app.ridemate.core.guardian.FeeEstimator
import app.ridemate.core.guardian.FeeNote
import app.ridemate.core.guardian.FeeQuery
import app.ridemate.core.guardian.FeeRules
import app.ridemate.core.guardian.RuleBasedFeeEstimator
import app.ridemate.core.model.Provider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The Remote Config payloads the guardian runs on (contracts §2.1, §2.4, §2.5). */
interface GuardianConfigSource {
    fun templates(): NotificationTemplatesConfig
    fun feeRules(): FeeRules

    /** [provider]'s cancel steps in the UI language (English fallback); empty when the app has no card. */
    fun cancelSteps(provider: Provider): List<CancelStep>

    /**
     * Remote Config `better_ride_enabled` (goal 22): false stops better-ride offers in live sessions and hides the
     * "Keep searching" toggle. Demo sessions follow `demo_enabled` instead.
     */
    val betterRideEnabled: StateFlow<Boolean> get() = BETTER_RIDE_ALWAYS_ON
}

private val BETTER_RIDE_ALWAYS_ON: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()

/**
 * [GuardianConfigSource] over [ConfigRepository]: each payload is resolved remote → last-good → bundled (core
 * `resolveLayered`) and re-resolved when Remote Config activates new values ([ConfigRepository.epoch]).
 */
class RemoteGuardianConfig(
    private val config: ConfigRepository,
    private val language: () -> String,
) : GuardianConfigSource {

    private class Resolved(
        val epoch: Long,
        val templates: NotificationTemplatesConfig,
        val fees: FeeRules,
        val cards: CancelCardsConfig?,
    )

    @Volatile
    private var cache: Resolved? = null

    private fun current(): Resolved {
        val epoch = config.epoch.value
        cache?.takeIf { it.epoch == epoch }?.let { return it }
        return Resolved(
            epoch = epoch,
            templates = config.resolve(ConfigKeys.NOTIFICATION_TEMPLATES, NotificationTemplatesConfig::parse)?.value
                ?: NotificationTemplatesConfig.EMPTY,
            fees = config.resolve(ConfigKeys.FEE_RULES, FeeRules::parse)?.value ?: FeeRules(),
            cards = config.resolve(ConfigKeys.CANCEL_CARDS, ::parseCancelCards)?.value,
        ).also { cache = it }
    }

    override fun templates(): NotificationTemplatesConfig = current().templates
    override fun feeRules(): FeeRules = current().fees

    override fun cancelSteps(provider: Provider): List<CancelStep> =
        current().cards?.cardFor(provider.packageName)?.stepsFor(language()).orEmpty()

    override val betterRideEnabled: StateFlow<Boolean> get() = config.betterRideEnabled
}

/**
 * Core's rule-based fee estimator over the latest `fee_rules` and the session's state ([stateCode], the ISO code of
 * the pickup's state, so `fee_rules.states` applies); rebuilt only when either changes.
 */
class LiveFeeEstimator(private val rules: () -> FeeRules, private val stateCode: () -> String? = { null }) : FeeEstimator {
    private class Built(val rules: FeeRules, val stateCode: String?, val estimator: RuleBasedFeeEstimator)

    @Volatile
    private var cached: Built? = null

    private fun delegate(): RuleBasedFeeEstimator {
        val current = rules()
        val state = stateCode()
        cached?.takeIf { it.rules === current && it.stateCode == state }?.let { return it.estimator }
        return RuleBasedFeeEstimator(current, state).also { cached = Built(current, state, it) }
    }

    override fun note(query: FeeQuery): FeeNote = delegate().note(query)

    override fun freeWindowEndsAtElapsedMs(query: FeeQuery): Long? = delegate().freeWindowEndsAtElapsedMs(query)
}
