package app.ridemate.notify

import app.ridemate.R
import app.ridemate.core.config.CancelStep
import app.ridemate.core.guardian.CaptureReason
import app.ridemate.core.guardian.LegRole
import app.ridemate.core.guardian.OpenPurpose
import app.ridemate.core.guardian.UiAction
import app.ridemate.core.guardian.UiKey
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState

/** Where tapping a card's body leads. */
sealed interface CardOpen {
    /** RideMate's guardian screen. */
    data object Guardian : CardOpen

    /** The ride app itself (a user tap on the notification may open it from the background). */
    data class RideApp(val provider: Provider, val purpose: OpenPurpose) : CardOpen
}

/** How loud a card is. */
enum class CardStyle { QUIET, HEADS_UP, LOUD }

/**
 * A rendered guardian item: everything a notification or an in-app sheet shows. Built once by [GuardianPresenter],
 * so the notification, the in-app sheet and the bubble mirror carry the same words and the same buttons.
 */
data class GuardianCard(
    val id: String,
    val channel: String,
    val title: String,
    val lines: List<String>,
    /** Rich lines (cancel steps with bold control names); same text as [lines] when there is no markup. */
    val steps: List<CancelStep> = emptyList(),
    val actions: List<CardAction>,
    val style: CardStyle,
    /** Alert again when re-posted (a re-prompt or another reminder). */
    val alertAgain: Boolean = false,
    /** Auto-dismiss after this long (notices). */
    val timeoutMs: Long? = null,
    val open: CardOpen = CardOpen.Guardian,
)

/** The cancel steps for an app (Remote Config `cancel_cards`, in the UI language). */
fun interface CancelStepsSource {
    fun steps(provider: Provider): List<CancelStep>

    companion object {
        val NONE = CancelStepsSource { emptyList() }
    }
}

/**
 * Turns UI items into [GuardianCard]s. Pure apart from string lookups, so the wording and buttons of every guardian
 * effect are unit-tested in English and Hindi.
 */
class GuardianPresenter(val text: GuardianText, private val cancelSteps: CancelStepsSource) {

    fun present(item: UiItem): GuardianCard = when (item) {
        is DriverSheetItem -> driverSheet(item)
        is BetterRideItem -> betterRide(item)
        is PromptItem -> prompt(item)
        is CheckScreenItem -> GuardianCard(
            id = item.id,
            channel = GuardianChannels.PROMPTS,
            title = text.title(UiKey.PROMPT_CHECK_SCREEN, listOf(item.provider)),
            lines = listOf(text.string(R.string.guardian_prompt_check_screen_body)),
            actions = legActions(item.legId, item.provider, item.actions),
            style = CardStyle.HEADS_UP,
        )
        is CancelCardItem -> cancelCard(item)
        is CaptureItem -> GuardianCard(
            id = item.id,
            channel = GuardianChannels.PROMPTS,
            title = text.title(captureKey(item.reason), listOf(item.provider)),
            lines = listOf(text.captureBody(item.reason, item.provider), text.string(R.string.guardian_capture_share_fallback)),
            // I6: a frame counts only within 2 s of a user tap. A capture RideMate asked for by itself (the ride
            // notification went away during a cancel) needs this tap before Android's screen-capture prompt.
            actions = listOf(legAction(item.legId, item.provider, captureAction(item.reason))),
            style = CardStyle.QUIET,
            timeoutMs = GuardianUiReducer.CAPTURE_TTL_MS,
        )
        is ReminderItem -> reminder(item)
        is AlertItem -> alert(item)
        is PayItem -> GuardianCard(
            id = item.id,
            channel = GuardianChannels.PROMPTS,
            title = text.title(UiKey.PAY_HANDOFF_OFFER, listOf(item.provider)),
            lines = listOf(text.string(R.string.guardian_pay_offer_body, text.provider(item.provider))),
            actions = legActions(item.legId, item.provider, item.actions),
            style = CardStyle.HEADS_UP,
        )
        is OndcFeeItem -> GuardianCard(
            id = item.id,
            channel = GuardianChannels.PROMPTS,
            title = text.title(UiKey.ONDC_CANCEL_FEE),
            lines = listOf(
                if (item.feeInr > 0) text.string(R.string.guardian_ondc_fee, text.rupees(item.feeInr)) else text.string(R.string.guardian_ondc_fee_free),
            ),
            actions = legActions(item.legId, Provider.ONDC, item.actions),
            style = CardStyle.HEADS_UP,
        )
    }

    fun notice(notice: NoticeItem): GuardianCard {
        sameDriver(notice)?.let { return it }
        val lines = buildList {
            notice.driver?.let { add(text.driverDetails(it)) }
            if (notice.key == UiKey.BANNER_CANCEL_CONFIRMED || notice.key == UiKey.BANNER_USER_SAYS_CANCELLED) {
                add(text.string(R.string.guardian_banner_proof_hint))
            }
        }
        return GuardianCard(
            id = notice.id,
            channel = GuardianChannels.GUARDIAN,
            title = text.title(notice.key, notice.providers),
            lines = lines,
            actions = emptyList(),
            style = CardStyle.QUIET,
            timeoutMs = GuardianUiReducer.NOTICE_TTL_MS,
        )
    }

    /**
     * The same driver turned up on another booking while a ride is kept (goal 15): a heads-up, not a quiet update, with
     * [Open <App>] (to cancel that booking there) and [Dismiss] for the other booking.
     */
    private fun sameDriver(notice: NoticeItem): GuardianCard? {
        if (notice.key != UiKey.BANNER_SAME_DRIVER) return null
        val legId = notice.legId ?: return null
        // The reducer lists the other booking's app first.
        val other = notice.providers.firstOrNull() ?: return null
        return GuardianCard(
            id = notice.id,
            channel = GuardianChannels.PROMPTS,
            title = text.title(notice.key, notice.providers),
            lines = listOf(text.string(R.string.guardian_same_driver_body, text.provider(other))),
            actions = legActions(legId, other, listOf(UiAction.OPEN_APP, UiAction.DISMISS)),
            style = CardStyle.HEADS_UP,
            timeoutMs = GuardianUiReducer.NOTICE_TTL_MS,
            open = CardOpen.RideApp(other, OpenPurpose.CANCEL),
        )
    }

    /** The heads-up "Open <App> to cancel" used when the background-start rule forbids opening the app directly. */
    fun openApp(legId: String, provider: Provider, purpose: OpenPurpose, hasApp: Boolean): GuardianCard = GuardianCard(
        id = "open:$legId",
        channel = GuardianChannels.PROMPTS,
        title = text.openTitle(purpose, provider),
        lines = listOf(text.openBody(purpose, provider, hasApp)),
        actions = emptyList(),
        style = CardStyle.HEADS_UP,
        open = if (hasApp) CardOpen.RideApp(provider, purpose) else CardOpen.Guardian,
    )

    /**
     * The ongoing notification, mirroring the bubble's actions (fallback when an app hides overlays): before a driver
     * is kept [Read fares] [Booked here] [Stop]; during the cancel chain [I cancelled it] [Show me] [Stop]; otherwise
     * [Done] [Stop]. "Booked here" names the app it records ("Booked in Rapido", [BookTargets]); when RideMate opened
     * several apps and can't see which one is on screen, it is "Booked in…", which asks ([bookPick]).
     */
    fun ongoing(state: GuardianUiState, openedLegId: String? = null): GuardianCard {
        val guided = state.legs.firstOrNull { it.state == RideState.CANCEL_GUIDED && !it.dismissed }
        val kept = state.legs.firstOrNull { it.role == LegRole.KEPT }
        val booked = state.legs.count { it.booked }
        val toCancel = state.legs.count { it.role == LegRole.EXTRA && !it.dismissed && !it.state.isTerminal }
        val lines = buildList {
            when {
                guided != null -> add(text.string(R.string.guardian_ongoing_cancel_now, text.provider(guided.provider)))
                // I10: "Your ride" only while the kept leg's current generation has a driver; otherwise its state
                // ("Rapido: finding another captain").
                kept != null && kept.state in RIDE_STATES -> add(text.string(R.string.guardian_ongoing_your_ride, text.provider(kept.provider)))
                kept != null -> add(text.string(R.string.guardian_line, text.provider(kept.provider), text.state(kept.state, kept.provider)))
                else -> add(text.string(R.string.guardian_ongoing_booking_phase))
            }
            if (booked > 0) add(text.string(R.string.guardian_ongoing_summary, booked, toCancel))
            state.nextReminderAtWallMs?.let { add(text.string(R.string.guardian_screen_next_reminder, text.time(it))) }
            if (state.stopBlocked) add(stopBlockedLine(state))
        }
        val actions = when {
            guided != null -> listOf(
                legAction(guided.legId, guided.provider, UiAction.I_CANCELLED_IT),
                legAction(guided.legId, guided.provider, UiAction.SHOW_ME),
                sessionAction(SessionOpKind.STOP),
            )
            kept == null -> listOfNotNull(
                sessionAction(SessionOpKind.READ_FARES),
                when (val choice = BookTargets.choose(state.legs, openedLegId)) {
                    is BookChoice.One -> state.leg(choice.legId)?.let { leg ->
                        val label = text.string(R.string.guardian_action_booked_in, text.provider(leg.provider))
                        CardAction(SessionOp(SessionOpKind.BOOKED_HERE, leg.legId), label, label)
                    }
                    // No ref: the guardian asks which app ([bookPick]) instead of guessing.
                    is BookChoice.Ask -> text.string(R.string.guardian_action_booked_ask).let { label ->
                        CardAction(SessionOp(SessionOpKind.BOOKED_HERE), label, text.string(R.string.guardian_book_pick_title))
                    }
                    BookChoice.None -> null
                },
                sessionAction(SessionOpKind.STOP),
            )
            else -> listOf(sessionAction(SessionOpKind.DONE), sessionAction(SessionOpKind.STOP))
        }
        return GuardianCard(
            id = ONGOING_ID,
            channel = GuardianChannels.GUARDIAN,
            title = text.string(if (state.demo) R.string.guardian_ongoing_demo_title else R.string.guardian_ongoing_title),
            lines = lines,
            actions = actions,
            style = CardStyle.QUIET,
        )
    }

    /**
     * "Which app did you book in?": RideMate opened several apps and can't see the screen, so "Booked here" asks
     * instead of guessing. One button per app ([choices]: leg id and app, the app opened last first); each records
     * that app.
     */
    fun bookPick(choices: List<Pair<String, Provider>>): GuardianCard = GuardianCard(
        id = BOOK_PICK_ID,
        channel = GuardianChannels.PROMPTS,
        title = text.string(R.string.guardian_book_pick_title),
        lines = listOf(text.string(R.string.guardian_book_pick_body)),
        actions = choices.take(BookTargets.MAX_CHOICES).map { (legId, provider) ->
            CardAction(
                SessionOp(SessionOpKind.BOOKED_HERE, legId),
                text.provider(provider),
                text.string(R.string.guardian_action_booked_in, text.provider(provider)),
            )
        },
        style = CardStyle.HEADS_UP,
        timeoutMs = BOOK_PICK_TTL_MS,
    )

    /** "Booked here" was recorded for [provider]: a short notice names the app, so a wrong one is noticed at once. */
    fun bookedRecorded(legId: String, provider: Provider): GuardianCard = GuardianCard(
        id = "booked:$legId",
        channel = GuardianChannels.GUARDIAN,
        title = text.string(R.string.guardian_booked_recorded, text.provider(provider)),
        lines = listOf(text.string(R.string.guardian_booked_recorded_body)),
        actions = emptyList(),
        style = CardStyle.QUIET,
        timeoutMs = GuardianUiReducer.NOTICE_TTL_MS,
    )

    /** Android refused to restart the guardian from the background: one tap on this brings it back. */
    fun resume(): GuardianCard = GuardianCard(
        id = RESUME_ID,
        channel = GuardianChannels.REMINDERS,
        title = text.string(R.string.guardian_resume_title),
        lines = listOf(text.string(R.string.guardian_resume_body)),
        actions = emptyList(),
        style = CardStyle.HEADS_UP,
    )

    /** A new comparison was refused because the previous session still has bookings to cancel (I8). */
    fun startBlocked(): GuardianCard = GuardianCard(
        id = START_BLOCKED_ID,
        channel = GuardianChannels.PROMPTS,
        title = text.string(R.string.guardian_start_blocked),
        lines = emptyList(),
        actions = emptyList(),
        style = CardStyle.HEADS_UP,
        timeoutMs = GuardianUiReducer.NOTICE_TTL_MS,
    )

    /** A new comparison waits for a live open-network booking: cancel it in RideMate, or let it finish, first. */
    fun startBlockedByNetwork(): GuardianCard = GuardianCard(
        id = START_BLOCKED_ID,
        channel = GuardianChannels.PROMPTS,
        title = text.string(R.string.guardian_start_blocked_network),
        lines = emptyList(),
        actions = emptyList(),
        style = CardStyle.HEADS_UP,
        timeoutMs = GuardianUiReducer.NOTICE_TTL_MS,
    )

    fun stopBlockedLine(state: GuardianUiState): String {
        val pending = state.legs.filter { it.role == LegRole.EXTRA && !it.dismissed && !it.state.isTerminal }
        return text.string(R.string.guardian_stop_blocked, text.providers(pending.map { it.provider }))
    }

    // ------------------------------------------------------------------ items

    private fun driverSheet(item: DriverSheetItem): GuardianCard {
        val providers = item.drivers.map { it.provider }
        val lines = buildList {
            when (item.key) {
                UiKey.SHEET_CANCEL_OTHERS -> item.drivers.firstOrNull()?.let { driver ->
                    val intro = if (driver.provider == Provider.RAPIDO) R.string.guardian_sheet_cancel_others_intro_captain else R.string.guardian_sheet_cancel_others_intro
                    add(text.string(intro, text.provider(driver.provider)))
                }
                UiKey.SHEET_SAME_DRIVER_KEEP_ONE -> add(text.string(R.string.guardian_sheet_same_driver_intro, text.providers(providers)))
                else -> add(text.string(R.string.guardian_sheet_two_drivers_intro))
            }
            item.drivers.forEach { add(text.driverLine(it)) }
            if (item.others.isNotEmpty()) {
                add(text.string(R.string.guardian_sheet_others_header))
                item.others.forEach { add(text.feeLine(it.provider, it.fee)) }
            }
        }
        val actions = item.actions.flatMap { action ->
            if (action == UiAction.PICK) {
                item.drivers.map { driver ->
                    CardAction(
                        GuardianTap(TapScope.SHEET, item.id, UiAction.PICK, pickedLegId = driver.legId),
                        text.action(UiAction.PICK, driver.provider),
                        text.actionDescription(UiAction.PICK, driver.provider),
                    )
                }
            } else {
                listOf(CardAction(GuardianTap(TapScope.SHEET, item.id, action), text.action(action, null), text.action(action, null)))
            }
        }
        return GuardianCard(item.id, GuardianChannels.PROMPTS, text.title(item.key, providers), lines, actions = actions, style = CardStyle.HEADS_UP)
    }

    private fun betterRide(item: BetterRideItem): GuardianCard {
        val lines = listOf(
            text.string(R.string.guardian_sheet_better_ride_intro),
            text.string(R.string.guardian_sheet_current, text.driverLine(item.current)),
            text.string(R.string.guardian_sheet_candidate, text.driverLine(item.candidate)),
            text.string(R.string.guardian_sheet_fee_if_switch, text.provider(item.current.provider), text.fee(item.currentFee)),
            text.string(R.string.guardian_sheet_fee_if_keep, text.provider(item.candidate.provider), text.fee(item.candidateFee)),
        )
        val actions = item.actions.map { action ->
            CardAction(GuardianTap(TapScope.SHEET, item.id, action), text.action(action, null), text.action(action, null))
        }
        return GuardianCard(
            item.id, GuardianChannels.PROMPTS, text.title(item.key, listOf(item.candidate.provider)), lines,
            actions = actions, style = CardStyle.HEADS_UP,
        )
    }

    private fun prompt(item: PromptItem): GuardianCard {
        val lines = buildList { if (item.repeat > 0) add(text.string(R.string.guardian_prompt_repeat_hint)) }
        val actions = item.actions.map { action ->
            CardAction(GuardianTap(TapScope.PROMPT, item.id, action), text.action(action, item.provider), text.action(action, item.provider))
        }
        return GuardianCard(
            item.id, GuardianChannels.PROMPTS, text.title(item.key, listOf(item.provider)), lines,
            actions = actions, style = CardStyle.HEADS_UP, alertAgain = item.repeat > 0,
        )
    }

    private fun cancelCard(item: CancelCardItem): GuardianCard {
        val steps = cancelSteps.steps(item.provider)
        val app = text.provider(item.provider)
        val lines = buildList {
            if (steps.isEmpty()) add(text.string(R.string.guardian_cancel_card_no_steps, app)) else steps.forEach { add(it.plainText) }
            add(text.string(R.string.guardian_cancel_card_status, app, text.state(item.providerState, item.provider)))
            add(text.fee(item.fee))
        }
        return GuardianCard(
            id = item.id,
            channel = GuardianChannels.PROMPTS,
            title = text.string(R.string.guardian_cancel_card_title, app, item.position, item.total),
            lines = lines,
            steps = steps,
            actions = legActions(item.legId, item.provider, item.actions),
            style = CardStyle.HEADS_UP,
            open = CardOpen.RideApp(item.provider, OpenPurpose.CANCEL),
        )
    }

    private fun reminder(item: ReminderItem): GuardianCard {
        val first = item.legs.firstOrNull()
        // A leg the user already said they cancelled can't take "I cancelled it" again (the guardian would reject it);
        // what helps there is the proof: "Check screen".
        val actions = if (first != null && first.state in USER_SAID_CANCELLED) {
            item.actions.map { if (it == UiAction.I_CANCELLED_IT) UiAction.CHECK_SCREEN else it }.distinct()
        } else {
            item.actions
        }
        return GuardianCard(
            id = item.id,
            channel = GuardianChannels.REMINDERS,
            title = text.title(UiKey.REMINDER_CANCEL_PENDING, item.legs.map { it.provider }),
            lines = listOf(text.string(R.string.guardian_reminder_body, item.attempt)) +
                item.legs.map { text.string(R.string.guardian_line, text.provider(it.provider), text.state(it.state, it.provider)) },
            actions = if (first == null) emptyList() else legActions(first.legId, first.provider, actions),
            style = CardStyle.HEADS_UP,
            alertAgain = true,
            open = first?.let { CardOpen.RideApp(it.provider, OpenPurpose.CANCEL) } ?: CardOpen.Guardian,
        )
    }

    private fun alert(item: AlertItem): GuardianCard {
        val app = text.provider(item.provider)
        val body = when (item.key) {
            UiKey.ALERT_KEPT_LEG_LOST -> text.string(R.string.guardian_alert_kept_leg_lost_body, app)
            UiKey.ALERT_STILL_LIVE -> text.string(R.string.guardian_alert_still_live_body, app)
            else -> text.string(R.string.guardian_alert_check_screen_body, app)
        }
        val actions = item.actions.map { action ->
            if (action == UiAction.COMPARE_AGAIN) {
                CardAction(GuardianTap(TapScope.SESSION, null, action), text.action(action, null), text.action(action, null))
            } else {
                legAction(item.legId, item.provider, action)
            }
        }
        return GuardianCard(
            item.id, GuardianChannels.ALERTS, text.title(item.key, listOf(item.provider)), listOf(body),
            actions = actions, style = CardStyle.LOUD, alertAgain = true,
        )
    }

    private fun legActions(legId: String, provider: Provider, actions: List<UiAction>): List<CardAction> =
        actions.map { legAction(legId, provider, it) }

    private fun legAction(legId: String, provider: Provider, action: UiAction): CardAction =
        CardAction(GuardianTap(TapScope.LEG, legId, action), text.action(action, provider), text.actionDescription(action, provider))

    private fun sessionAction(op: SessionOpKind, ref: String? = null): CardAction {
        val label = text.sessionAction(op)
        return CardAction(SessionOp(op, ref), label, label)
    }

    private fun captureKey(reason: CaptureReason): UiKey = when (reason) {
        CaptureReason.CHECK_SCREEN -> UiKey.CAPTURE_CHECK_SCREEN
        CaptureReason.CANCEL_PROOF -> UiKey.CAPTURE_CANCEL_PROOF
        CaptureReason.SHOW_ME -> UiKey.CAPTURE_SHOW_ME
    }

    /** The tap that backs a capture: Show me for the pointer, Check screen for a status or cancel-proof read. */
    private fun captureAction(reason: CaptureReason): UiAction = when (reason) {
        CaptureReason.SHOW_ME -> UiAction.SHOW_ME
        CaptureReason.CHECK_SCREEN, CaptureReason.CANCEL_PROOF -> UiAction.CHECK_SCREEN
    }

    companion object {
        /** I10: the states in which a leg is "your ride" (its current generation has a driver). */
        val RIDE_STATES: Set<RideState> = setOf(RideState.ASSIGNED, RideState.ARRIVING)

        /** The user already answered "I cancelled it" for a leg in these states; only proof moves it on. */
        val USER_SAID_CANCELLED: Set<RideState> = setOf(RideState.USER_SAYS_CANCELLED, RideState.CANCEL_UNCONFIRMED)

        const val ONGOING_ID = "ongoing"
        const val RESUME_ID = "resume"
        const val START_BLOCKED_ID = "start-blocked"

        /** "Which app did you book in?" (one at a time; replaced by a newer one). */
        const val BOOK_PICK_ID = "book-pick"
        const val BOOK_PICK_TTL_MS = 2 * 60_000L
    }
}
