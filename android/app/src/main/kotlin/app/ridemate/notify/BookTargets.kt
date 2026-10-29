package app.ridemate.notify

import app.ridemate.core.model.RideState

/** Which app a "Booked here" tap records (goal 8). */
sealed interface BookChoice {
    /** The leg ids the buttons offer, in order (one for [One], one per app for [Ask]). */
    val legIds: List<String>

    /** Only one app it can be: the button names it ("Booked in Rapido"). */
    data class One(val legId: String) : BookChoice {
        override val legIds: List<String> get() = listOf(legId)
    }

    /** More than one app it could be: ask which, one tap per app ("Booked in Uber", "Booked in Rapido"). */
    data class Ask(override val legIds: List<String>) : BookChoice

    /** Nothing left to book (every app booked, cancelled or dismissed). */
    data object None : BookChoice {
        override val legIds: List<String> get() = emptyList()
    }
}

/**
 * "Booked here" records the app actually on screen, or asks (goal 8). RideMate cannot see which app is in front: the
 * user may have gone back to an earlier app with Recents after RideMate opened the next one. So:
 * - one app still bookable → that app;
 * - several, but RideMate opened only one of them (OPENED, or QUOTED after Read fares) → that one, the only app the
 *   user has been taken to;
 * - several opened (or none) → ask which, the app RideMate opened last first.
 *
 * Network (ONDC) legs are booked in RideMate itself and never offered. The bubble, the ongoing notification and the
 * guardian all decide with this one rule, so what the button names is what gets recorded.
 */
object BookTargets {
    /** At most this many apps are offered (the notification shows three buttons; there are three ride apps). */
    const val MAX_CHOICES = 3

    private val OPENED_BY_RIDEMATE = setOf(RideState.OPENED, RideState.QUOTED)

    fun <T> choose(
        legs: Collection<T>,
        openedLegId: String?,
        id: (T) -> String,
        state: (T) -> RideState,
        bookable: (T) -> Boolean,
    ): BookChoice {
        val candidates = legs.filter(bookable)
        if (candidates.isEmpty()) return BookChoice.None
        if (candidates.size == 1) return BookChoice.One(id(candidates.single()))
        val opened = candidates.filter { state(it) in OPENED_BY_RIDEMATE }
        if (opened.size == 1) return BookChoice.One(id(opened.single()))
        val pool = if (opened.size > 1) opened else candidates
        val ordered = pool.sortedBy { if (id(it) == openedLegId) 0 else 1 }.map(id).take(MAX_CHOICES)
        return BookChoice.Ask(ordered)
    }

    /** The rule over the UI model's legs. */
    fun choose(legs: List<LegUi>, openedLegId: String?): BookChoice =
        choose(legs, openedLegId, LegUi::legId, LegUi::state) { leg ->
            !leg.booked && !leg.dismissed && !leg.state.isTerminal && !leg.provider.isNetwork
        }
}
