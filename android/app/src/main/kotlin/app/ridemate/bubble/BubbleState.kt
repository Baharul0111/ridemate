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
    /**
     * Localised labels that name an app ("Next: Ola", "Booked in Rapido", "Refresh Uber price"), in place of the
     * action's generic label; the screen reader hears the same words.
     */
    val labels: Map<BubbleAction, String> = emptyMap(),
) {
    /** The text the control for [action] shows (and speaks), or null for its generic string resource. */
    fun labelFor(action: BubbleAction): String? = labels[action]

    companion object {
        /** The "Booked in <app>" buttons, in order: one, or one per app when RideMate must ask which. */
        val BOOKED_SLOTS: List<BubbleAction> = listOf(BubbleAction.BOOKED_HERE, BubbleAction.BOOKED_HERE_2, BubbleAction.BOOKED_HERE_3)

        /**
         * On a ride app's price screen: read, open the next selected app (goal 3), record a booking, maybe paste the
         * drop address, stop. [labels] name the apps the buttons are about; [status] says what a pending refresh is for.
         * [bookedChoices] > 1 shows one "Booked in <app>" per app RideMate can't tell apart (it can't see the screen).
         */
        fun comparing(
            pasteDrop: Boolean = false,
            nextApp: Boolean = false,
            labels: Map<BubbleAction, String> = emptyMap(),
            status: BubbleStatus? = null,
            bookedChoices: Int = 1,
        ): BubbleState = BubbleState(
            actions = buildList {
                add(BubbleAction.READ_FARES)
                if (pasteDrop) add(BubbleAction.PASTE_DROP)
                if (nextApp) add(BubbleAction.NEXT_APP)
                addAll(BOOKED_SLOTS.take(bookedChoices.coerceIn(1, BOOKED_SLOTS.size)))
                add(BubbleAction.STOP)
            },
            labels = labels,
            status = status,
        )

        /**
         * In a guided cancel: the instruction card, Show me (when grounding is supported), "I cancelled it" (the same
         * answer as on the notification: RideMate checks a minute later and moves on to the next app), Stop.
         */
        fun cancelGuide(card: InstructionCard, showMe: Boolean): BubbleState = BubbleState(
            actions = buildList {
                if (showMe) add(BubbleAction.SHOW_ME)
                add(BubbleAction.I_CANCELLED_IT)
                add(BubbleAction.STOP)
            },
            card = card,
        )

        /** Watching booked rides: only Stop. */
        fun watching(): BubbleState = BubbleState(actions = listOf(BubbleAction.STOP), expanded = false)
    }
}
