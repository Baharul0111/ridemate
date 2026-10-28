package app.ridemate.bubble

import app.ridemate.core.config.CancelStep

/** What a status line is about; shown as an icon plus spoken text, never by colour alone. */
enum class StatusKind { INFO, WORKING, PROBLEM, REMINDER }

/** A one-line status on the bubble. [text] is already localised and says what to do next for problems. */
data class BubbleStatus(val text: String, val kind: StatusKind = StatusKind.INFO)

/**
 * The instruction card shown during a guided cancel (§4.7): wording comes from config templates (`cancel_cards`) and
 * string resources, never from model output (I5).
 */
data class InstructionCard(val title: String, val steps: List<CancelStep>, val note: String? = null)

/**
 * Everything the bubble shows. [actions] is the full set of buttons right now ([BubbleAction.PASTE_DROP] shows as
 * the "Paste drop" chip); the notification mirrors the same set ([NotificationMirror]).
 */
data class BubbleState(
    val actions: List<BubbleAction> = listOf(BubbleAction.READ_FARES, BubbleAction.BOOKED_HERE, BubbleAction.STOP),
    val card: InstructionCard? = null,
    val status: BubbleStatus? = null,
    /** Initial expansion; the user's own open/close on the bubble head wins afterwards. */
    val expanded: Boolean = true,
    /** While true "Read fares" is disabled and a progress indicator shows. */
    val busy: Boolean = false,
) {
    companion object {
        /** On a ride app's price screen: read, record a booking, maybe paste the drop address, stop. */
        fun comparing(pasteDrop: Boolean = false): BubbleState = BubbleState(
            actions = buildList {
                add(BubbleAction.READ_FARES)
                if (pasteDrop) add(BubbleAction.PASTE_DROP)
                add(BubbleAction.BOOKED_HERE)
                add(BubbleAction.STOP)
            },
        )

        /** In a guided cancel: the instruction card, Show me (when grounding is supported), Done, Stop. */
        fun cancelGuide(card: InstructionCard, showMe: Boolean): BubbleState = BubbleState(
            actions = buildList {
                if (showMe) add(BubbleAction.SHOW_ME)
                add(BubbleAction.DONE)
                add(BubbleAction.STOP)
            },
            card = card,
        )

        /** Watching booked rides: only Stop. */
        fun watching(): BubbleState = BubbleState(actions = listOf(BubbleAction.STOP), expanded = false)
    }
}
