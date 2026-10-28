package app.ridemate.notify

import app.ridemate.core.guardian.CaptureReason
import app.ridemate.core.guardian.DriverCard
import app.ridemate.core.guardian.FeeNote
import app.ridemate.core.guardian.GuardianEffect
import app.ridemate.core.guardian.GuardianState
import app.ridemate.core.guardian.LegRecord
import app.ridemate.core.guardian.LegRole
import app.ridemate.core.guardian.UiAction
import app.ridemate.core.guardian.UiKey
import app.ridemate.core.model.EtaSource
import app.ridemate.core.model.EvidenceRung
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import app.ridemate.core.state.LegState
import app.ridemate.core.state.isAdmissible
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * The guardian's UI model: what the in-app screen and the notifications show. Built from core GuardianEffects and the
 * guardian's state. I5: it holds ids, enums and numbers only; all wording comes from string resources at render time.
 * Serializable so open sheets and prompts survive process death with the guardian snapshot.
 */

/** A driver as shown: ETA/distance only with an admissible source (I2 + D8), else "see in <app>". */
@Serializable
data class DriverUi(
    val legId: String,
    val provider: Provider,
    val generation: Int,
    val etaMin: Int? = null,
    val etaSource: EtaSource = EtaSource.NONE,
    val distanceMeters: Int? = null,
    val distanceSource: EtaSource = EtaSource.NONE,
) {
    companion object {
        fun of(card: DriverCard) = DriverUi(
            card.legId, card.provider, card.generation, card.etaMin, card.etaSource, card.distanceMeters, card.distanceSource,
        )

        /** The current driver of [leg], with values only where core says they may be shown. */
        fun of(leg: LegState, nowElapsedMs: Long): DriverUi? {
            if (leg.underlyingState !in LegState.DRIVER_STATES) return null
            val d = leg.driver
            val eta = d.remainingEtaMin(nowElapsedMs)
            val distance = d.distanceMeters?.takeIf { d.distanceSource.isAdmissible }
            return DriverUi(
                legId = leg.legId,
                provider = leg.provider,
                generation = leg.generation,
                etaMin = eta,
                etaSource = if (eta != null) d.etaSource else EtaSource.NONE,
                distanceMeters = distance,
                distanceSource = if (distance != null) d.distanceSource else EtaSource.NONE,
            )
        }
    }
}

@Serializable
data class LegFeeUi(val legId: String, val provider: Provider, val fee: FeeNote)

@Serializable
data class LegRefUi(val legId: String, val provider: Provider, val state: RideState)

/** One leg row on the guardian screen: state, evidence rung, role and driver. */
@Serializable
data class LegUi(
    val legId: String,
    val provider: Provider,
    val state: RideState,
    val generation: Int,
    val role: LegRole,
    val dismissed: Boolean,
    val booked: Boolean,
    val endedByApp: Boolean,
    /** The proof behind a cancel (rungs 1–3), or USER while "I cancelled it" awaits its +60 s check. */
    val evidence: EvidenceRung? = null,
    val driver: DriverUi? = null,
)

/** Something that needs the user: a sheet, a prompt, a card or an alert. Shown in-app and as a notification. */
@Serializable
sealed interface UiItem {
    val id: String

    /** The leg the item is about, when there is exactly one. */
    val legId: String?
}

@Serializable
@SerialName("driver_sheet")
data class DriverSheetItem(
    override val id: String,
    val key: UiKey,
    val drivers: List<DriverUi>,
    val others: List<LegFeeUi>,
    val actions: List<UiAction>,
) : UiItem {
    override val legId: String? get() = drivers.singleOrNull()?.legId
}

@Serializable
@SerialName("better_ride")
data class BetterRideItem(
    override val id: String,
    val key: UiKey,
    val current: DriverUi,
    val candidate: DriverUi,
    val currentFee: FeeNote,
    val candidateFee: FeeNote,
    val actions: List<UiAction>,
) : UiItem {
    override val legId: String? get() = candidate.legId
}

@Serializable
@SerialName("prompt")
data class PromptItem(
    override val id: String,
    override val legId: String,
    val provider: Provider,
    val generation: Int,
    val key: UiKey,
    val actions: List<UiAction>,
    val repeat: Int,
) : UiItem

@Serializable
@SerialName("check_screen")
data class CheckScreenItem(override val legId: String, val provider: Provider, val actions: List<UiAction>) : UiItem {
    override val id: String get() = "check:$legId"
}

@Serializable
@SerialName("cancel_card")
data class CancelCardItem(
    override val legId: String,
    val provider: Provider,
    val providerState: RideState,
    val fee: FeeNote,
    val position: Int,
    val total: Int,
    val actions: List<UiAction>,
) : UiItem {
    override val id: String get() = "cancel:$legId"
}

@Serializable
@SerialName("capture")
data class CaptureItem(override val legId: String, val provider: Provider, val reason: CaptureReason, val atWallMs: Long) : UiItem {
    override val id: String get() = "capture:$legId"
}

@Serializable
@SerialName("reminder")
data class ReminderItem(val attempt: Int, val legs: List<LegRefUi>, val actions: List<UiAction>) : UiItem {
    override val id: String get() = ID
    override val legId: String? get() = legs.firstOrNull()?.legId

    companion object {
        const val ID = "reminder"
    }
}

@Serializable
@SerialName("alert")
data class AlertItem(override val legId: String, val provider: Provider, val key: UiKey, val actions: List<UiAction>) : UiItem {
    override val id: String get() = "alert:$legId:${key.name}"
}

@Serializable
@SerialName("pay")
data class PayItem(override val legId: String, val provider: Provider, val actions: List<UiAction>) : UiItem {
    override val id: String get() = "pay:$legId"
}

@Serializable
@SerialName("ondc_fee")
data class OndcFeeItem(override val legId: String, val feeInr: Int, val actions: List<UiAction>) : UiItem {
    override val id: String get() = "ondcfee:$legId"
}

/** A short update: banners, withdrawn-question reasons, "your ride" announcements. */
@Serializable
data class NoticeItem(
    val id: String,
    val key: UiKey,
    val legId: String? = null,
    val providers: List<Provider> = emptyList(),
    val driver: DriverUi? = null,
    val atWallMs: Long,
)

@Serializable
data class GuardianUiState(
    val sessionId: String? = null,
    val demo: Boolean = false,
    val keepSearching: Boolean = false,
    val closed: Boolean = false,
    val legs: List<LegUi> = emptyList(),
    /** Open items, oldest first. */
    val items: List<UiItem> = emptyList(),
    /** Recent updates, newest first. */
    val notices: List<NoticeItem> = emptyList(),
    val remindersSent: Int = 0,
    val nextReminderAtWallMs: Long? = null,
    /** Set when the user asked to stop while extra bookings still need cancelling (I8). */
    val stopBlocked: Boolean = false,
    val noticeSeq: Long = 0,
) {
    val hasSession: Boolean get() = sessionId != null && !closed

    /** The item to show first: loud alerts, then sheets and questions, then cards. */
    val activeItem: UiItem? get() = items.maxByOrNull { priority(it) * 10_000 + items.indexOf(it) }

    fun leg(legId: String?): LegUi? = legs.firstOrNull { it.legId == legId }

    companion object {
        val EMPTY = GuardianUiState()

        fun priority(item: UiItem): Int = when (item) {
            is AlertItem -> 9
            is DriverSheetItem, is BetterRideItem -> 8
            is PromptItem -> 7
            is OndcFeeItem -> 6
            is CheckScreenItem -> 5
            is CancelCardItem -> 4
            is PayItem -> 3
            is CaptureItem -> 2
            is ReminderItem -> 1
        }

        private val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            classDiscriminator = "t"
        }

        fun encode(state: GuardianUiState): String = JSON.encodeToString(serializer(), state)

        fun decode(raw: String?): GuardianUiState? = raw?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/**
 * Folds core [GuardianEffect]s into the [GuardianUiState], and drops items the guardian's own state no longer backs.
 * Every effect type is handled (the compiler checks the `when`).
 */
object GuardianUiReducer {
    const val MAX_NOTICES = 6
    const val NOTICE_TTL_MS = 10 * 60_000L
    const val CAPTURE_TTL_MS = 2 * 60_000L

    fun apply(state: GuardianUiState, effect: GuardianEffect, nowWallMs: Long): GuardianUiState = when (effect) {
        is GuardianEffect.DriverSheet -> state.upsert(
            DriverSheetItem(effect.sheetId, effect.key, effect.drivers.map(DriverUi::of), effect.others.map { LegFeeUi(it.legId, it.provider, it.fee) }, effect.actions),
        )
        is GuardianEffect.BetterRideSheet -> state.upsert(
            BetterRideItem(
                effect.sheetId, effect.key, DriverUi.of(effect.current), DriverUi.of(effect.candidate), effect.currentFee,
                effect.candidateFee, effect.actions,
            ),
        )
        is GuardianEffect.SheetWithdrawn -> {
            val old = state.items.firstOrNull { it.id == effect.sheetId }
            val providers = when (old) {
                is DriverSheetItem -> old.drivers.map { it.provider }
                is BetterRideItem -> listOf(old.candidate.provider)
                else -> emptyList()
            }
            val removed = state.remove(effect.sheetId)
            if (effect.key == UiKey.SHEET_CLOSED || effect.key == UiKey.SHEET_REPLACED) {
                removed
            } else {
                removed.notice(effect.key, old?.legId, providers, nowWallMs = nowWallMs)
            }
        }
        is GuardianEffect.MaybePrompt -> state.upsert(
            PromptItem(effect.promptId, effect.legId, effect.provider, effect.generation, effect.key, effect.actions, effect.repeat),
        )
        is GuardianEffect.PromptWithdrawn -> {
            val removed = state.remove(effect.promptId)
            if (effect.key == UiKey.PROMPT_ANSWERED) {
                removed
            } else {
                removed.notice(effect.key, effect.legId, listOfNotNull(state.leg(effect.legId)?.provider), nowWallMs = nowWallMs)
            }
        }
        is GuardianEffect.CheckScreenPrompt -> state.upsert(CheckScreenItem(effect.legId, effect.provider, effect.actions))
        // Opening an app is a side effect (background-start rule), not a screen item.
        is GuardianEffect.OpenApp -> state
        is GuardianEffect.CancelCard -> state.copy(items = state.items.filterNot { it is CancelCardItem && it.legId != effect.legId })
            .upsert(
                CancelCardItem(effect.legId, effect.provider, effect.providerState, effect.fee, effect.position, effect.total, effect.actions),
            )
        is GuardianEffect.RequestCapture -> state.upsert(CaptureItem(effect.legId, effect.provider, effect.reason, nowWallMs))
        is GuardianEffect.Reminder -> state.upsert(
            ReminderItem(effect.attempt, effect.legs.map { LegRefUi(it.legId, it.provider, it.state) }, effect.actions),
        ).copy(remindersSent = effect.attempt)
        is GuardianEffect.LoudAlert -> state.upsert(AlertItem(effect.legId, effect.provider, effect.key, effect.actions))
        is GuardianEffect.RideBanner -> state.notice(
            UiKey.BANNER_YOUR_RIDE, effect.driver.legId, listOf(effect.driver.provider), DriverUi.of(effect.driver), nowWallMs,
        )
        is GuardianEffect.Banner -> banner(state, effect, nowWallMs)
        is GuardianEffect.SameDriverNotice -> state.notice(
            UiKey.BANNER_SAME_DRIVER, null, effect.legIds.mapNotNull { state.leg(it)?.provider }, nowWallMs = nowWallMs,
        )
        is GuardianEffect.PayHandoffOffer -> state.upsert(PayItem(effect.legId, effect.provider, effect.actions))
        is GuardianEffect.OndcCommand -> state.notice(effect.key, effect.legId, listOf(Provider.ONDC), nowWallMs = nowWallMs)
        is GuardianEffect.OndcCancelFee -> state.upsert(OndcFeeItem(effect.legId, effect.feeInr, effect.actions))
        is GuardianEffect.ForegroundService -> state
        is GuardianEffect.CompareAgain -> state.copy(items = state.items.filterNot { it is AlertItem && it.key == UiKey.ALERT_KEPT_LEG_LOST })
            .notice(UiKey.COMPARE_AGAIN, null, emptyList(), nowWallMs = nowWallMs)
        is GuardianEffect.AnswerRejected -> state.notice(UiKey.ANSWER_REJECTED_STALE, null, emptyList(), nowWallMs = nowWallMs)
    }

    private fun banner(state: GuardianUiState, effect: GuardianEffect.Banner, nowWallMs: Long): GuardianUiState {
        var next = state
        when (effect.key) {
            UiKey.BANNER_CANCEL_CONFIRMED, UiKey.BANNER_ENDED_BY_APP, UiKey.BANNER_LEG_DISMISSED ->
                next = next.copy(items = next.items.filterNot { it.legId == effect.legId && it.isLegCard() })
            UiKey.BANNER_ALL_CANCELS_DONE -> next = next.remove(ReminderItem.ID)
            else -> Unit
        }
        return next.notice(effect.key, effect.legId, listOfNotNull(effect.provider), nowWallMs = nowWallMs)
    }

    private fun UiItem.isLegCard(): Boolean =
        this is CancelCardItem || this is CheckScreenItem || this is CaptureItem ||
            (this is AlertItem && key != UiKey.ALERT_KEPT_LEG_LOST)

    /**
     * Drops items the guardian no longer backs (answered or withdrawn elsewhere, legs that moved on), refreshes the
     * leg rows and the reminder schedule, and expires old notices.
     */
    fun reconcile(
        state: GuardianUiState,
        guardian: GuardianState,
        nowElapsedMs: Long,
        nowWallMs: Long,
        nextReminderAtWallMs: Long?,
        stopBlocked: Boolean,
    ): GuardianUiState {
        val session = guardian.session
        val records = guardian.legs.values.toList()
        val legs = records.map { legUi(it, guardian, nowElapsedMs) }
        val byId = records.associateBy { it.legId }
        val sheetIds = guardian.sheets.map { it.sheetId }.toSet()
        val promptIds = guardian.prompts.map { it.promptId }.toSet()
        val items = state.items.filter { item ->
            val rec = item.legId?.let { byId[it] }
            val live = rec != null && !rec.dismissed && !rec.leg.state.isTerminal
            when (item) {
                is DriverSheetItem, is BetterRideItem -> item.id in sheetIds
                is PromptItem -> item.id in promptIds
                is CancelCardItem -> live && rec!!.leg.state in CARD_STATES
                is CheckScreenItem -> live && rec!!.leg.state in LegState.CHAIN_STATES
                is CaptureItem -> live && nowWallMs - item.atWallMs < CAPTURE_TTL_MS
                is ReminderItem -> guardian.reminders != null
                is AlertItem -> if (item.key == UiKey.ALERT_KEPT_LEG_LOST) rec != null && session?.closed != true else live
                is PayItem -> rec?.leg?.state == RideState.ENDED
                is OndcFeeItem -> rec?.leg?.state == RideState.SOFT_CANCEL
            }
        }
        return state.copy(
            sessionId = session?.sessionId,
            demo = session?.demo == true,
            keepSearching = session?.keepSearching == true,
            closed = session?.closed == true,
            legs = legs,
            items = items,
            notices = state.notices.filter { nowWallMs - it.atWallMs < NOTICE_TTL_MS }.take(MAX_NOTICES),
            remindersSent = guardian.reminders?.sent ?: 0,
            nextReminderAtWallMs = nextReminderAtWallMs,
            stopBlocked = stopBlocked,
        )
    }

    fun dismissNotice(state: GuardianUiState, noticeId: String): GuardianUiState =
        state.copy(notices = state.notices.filterNot { it.id == noticeId })

    private val CARD_STATES = setOf(RideState.CANCEL_REQUESTED, RideState.CANCEL_GUIDED)

    private fun legUi(rec: LegRecord, guardian: GuardianState, nowElapsedMs: Long): LegUi {
        val leg = rec.leg
        val userRung = leg.state == RideState.USER_SAYS_CANCELLED || leg.state == RideState.CANCEL_UNCONFIRMED
        val evidence = leg.confirmedBy
            ?: guardian.evidence.lastOrNull { it.legId == rec.legId }?.rung
            ?: if (userRung) EvidenceRung.USER else null
        return LegUi(
            legId = rec.legId,
            provider = rec.provider,
            state = leg.state,
            generation = leg.generation,
            role = rec.role,
            dismissed = rec.dismissed,
            booked = leg.isBooked,
            endedByApp = leg.endedByApp,
            evidence = evidence,
            driver = DriverUi.of(leg, nowElapsedMs + guardian.elapsedOffsetMs),
        )
    }

    private fun GuardianUiState.upsert(item: UiItem): GuardianUiState {
        val index = items.indexOfFirst { it.id == item.id }
        return copy(items = if (index < 0) items + item else items.toMutableList().also { it[index] = item })
    }

    private fun GuardianUiState.remove(id: String): GuardianUiState = copy(items = items.filterNot { it.id == id })

    private fun GuardianUiState.notice(
        key: UiKey,
        legId: String?,
        providers: List<Provider>,
        driver: DriverUi? = null,
        nowWallMs: Long,
    ): GuardianUiState {
        val seq = noticeSeq + 1
        val notice = NoticeItem("notice:$seq", key, legId, providers, driver, nowWallMs)
        return copy(notices = (listOf(notice) + notices).take(MAX_NOTICES), noticeSeq = seq)
    }
}
