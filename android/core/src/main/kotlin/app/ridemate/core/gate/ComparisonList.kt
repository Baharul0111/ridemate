package app.ridemate.core.gate

import app.ridemate.core.format.IndianFormat
import app.ridemate.core.format.IstTime
import app.ridemate.core.format.Numerals
import app.ridemate.core.model.QuoteStatus
import app.ridemate.core.model.VehicleClass

/** String resource names the app defines in English and Hindi; core never builds user-facing sentences. */
object GateText {
    /** "Couldn't read · check in app" */
    const val UNREADABLE = "quote_unreadable"

    /** "ETA: see in app" */
    const val ETA_SEE_IN_APP = "eta_see_in_app"

    /** "see in %1$s" (driver ETA or distance not confirmed) */
    const val DRIVER_SEE_IN_APP = "driver_see_in_app"

    /** "entered by you" */
    const val ENTERED_BY_YOU = "quote_entered_by_you"

    /** "network quote" */
    const val NETWORK_QUOTE = "quote_network"

    /** "seen in %1$s at %2$s" */
    const val SEEN_AT = "quote_seen_at"

    /** "old, tap to refresh" */
    const val STALE = "quote_stale"

    /** "cheapest" */
    const val CHEAPEST = "quote_cheapest"
}

/** Goal 6: a price greys out (and says so in text) once it is 60 s old. */
object Freshness {
    const val STALE_AFTER_MS: Long = 60_000

    /** Stale from the moment the row is 60 s old; a pure function of the clock. */
    fun isStale(seenAtMs: Long, nowMs: Long): Boolean = nowMs - seenAtMs >= STALE_AFTER_MS
}

/** One line of the comparison list, with every state as a flag plus a text key (never colour alone). */
data class ComparisonEntry(
    val row: GatedRow,
    val cheapest: Boolean,
    val stale: Boolean,
    /** `₹110–130`; null when the row shows no number. */
    val priceText: String?,
    /** [GateText.UNREADABLE], [GateText.ENTERED_BY_YOU], [GateText.NETWORK_QUOTE], or null for VERIFIED rows. */
    val statusTextKey: String?,
    /** [GateText.ETA_SEE_IN_APP] when no ETA is shown. */
    val etaTextKey: String?,
    /** [GateText.SEEN_AT] while fresh, [GateText.STALE] after 60 s. */
    val freshnessTextKey: String,
    /** The `h:mm:ss a` IST time for "seen in <app> at …". */
    val seenAtText: String,
)

data class ClassGroup(val vehicleClass: VehicleClass, val entries: List<ComparisonEntry>)

/**
 * Goal 7: rows grouped by vehicle class (in [VehicleClass] order). Within a group VERIFIED rows come first, sorted
 * by price, then the others in their original order: only VERIFIED rows are sorted or flagged cheapest (§4.3.7).
 *
 * Cheapest compares the upper end of a price range first (what you may end up paying), then the lower end; every
 * VERIFIED row equal to the best is flagged, so ties are all cheapest.
 */
data class ComparisonList(val groups: List<ClassGroup>) {
    companion object {
        private val BY_PRICE = compareBy<GatedRow>({ it.price!!.last }, { it.price!!.first })

        fun build(rows: List<GatedRow>, nowMs: Long, numerals: Numerals = Numerals.LATIN): ComparisonList {
            val groups = rows.groupBy { it.vehicleClass }.toSortedMap(compareBy { it.ordinal }).map { (vehicleClass, inClass) ->
                val (verified, others) = inClass.partition { it.status == QuoteStatus.VERIFIED }
                val sorted = verified.sortedWith(BY_PRICE)
                val best = sorted.firstOrNull()
                val entries = (sorted + others).map { row ->
                    val stale = Freshness.isStale(row.seenAtMs, nowMs)
                    ComparisonEntry(
                        row = row,
                        cheapest = best != null && row.status == QuoteStatus.VERIFIED && BY_PRICE.compare(row, best) == 0,
                        stale = stale,
                        priceText = row.price?.let { IndianFormat.rupeeRange(it, numerals) },
                        statusTextKey = when (row.status) {
                            QuoteStatus.VERIFIED -> null
                            QuoteStatus.UNREADABLE -> GateText.UNREADABLE
                            QuoteStatus.USER_ENTERED -> GateText.ENTERED_BY_YOU
                            QuoteStatus.NETWORK -> GateText.NETWORK_QUOTE
                        },
                        etaTextKey = if (row.eta == null && row.status != QuoteStatus.UNREADABLE) GateText.ETA_SEE_IN_APP else null,
                        freshnessTextKey = if (stale) GateText.STALE else GateText.SEEN_AT,
                        seenAtText = IstTime.seenAt(row.seenAtMs, numerals),
                    )
                }
                ClassGroup(vehicleClass, entries)
            }
            return ComparisonList(groups)
        }
    }
}
