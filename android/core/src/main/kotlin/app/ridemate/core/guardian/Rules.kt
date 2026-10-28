package app.ridemate.core.guardian

import app.ridemate.core.model.EtaSource
import app.ridemate.core.state.DriverInfo

/** §4.8: a later driver is better only if the ETA gain is at least max(5 min, 40% of the current ETA). */
data class BetterRideRule(
    val minGainMin: Int = 5,
    val minGainFraction: Double = 0.4,
    /** Alerts stop once the current driver is this close (or the trip starts). */
    val stopWithinMin: Int = 2,
) {
    fun isBetter(current: DriverInfo, candidate: DriverInfo, nowElapsedMs: Long): Boolean {
        val currentEta = comparableEta(current, nowElapsedMs) ?: return false
        val candidateEta = comparableEta(candidate, nowElapsedMs) ?: return false
        return isBetter(currentEta, candidateEta)
    }

    fun isBetter(currentEtaMin: Int, candidateEtaMin: Int): Boolean {
        val gain = currentEtaMin - candidateEtaMin
        val needed = maxOf(minGainMin.toDouble(), minGainFraction * currentEtaMin)
        return gain >= needed
    }

    /** Only VERIFIED and TEMPLATE ETAs may drive a better-ride alert. */
    fun comparableEta(driver: DriverInfo, nowElapsedMs: Long): Int? =
        if (driver.etaSource in COMPARABLE) driver.remainingEtaMin(nowElapsedMs) else null

    /** True once the current driver is within [stopWithinMin] (by a comparable ETA). */
    fun currentIsClose(current: DriverInfo, nowElapsedMs: Long): Boolean {
        val eta = comparableEta(current, nowElapsedMs) ?: return false
        return eta <= stopWithinMin
    }

    /** When the current driver's countdown reaches [stopWithinMin], or null without a comparable ETA. */
    fun closeAtElapsedMs(current: DriverInfo): Long? {
        if (current.etaSource !in COMPARABLE) return null
        val eta = current.etaMin ?: return null
        val readAt = current.etaAtElapsedMs ?: return null
        return readAt + (eta - stopWithinMin).coerceAtLeast(0) * 60_000L
    }

    private companion object {
        val COMPARABLE = setOf(EtaSource.VERIFIED, EtaSource.TEMPLATE)
    }
}

/** §4.7: reminders at +30 s, +60 s, +120 s, then every 120 s, counted from when the first extra leg became pending. */
data class ReminderSchedule(
    val offsetsMs: List<Long> = listOf(30_000L, 60_000L, 120_000L),
    val repeatMs: Long = 120_000L,
) {
    init {
        require(offsetsMs.isNotEmpty() && repeatMs > 0)
    }

    /** Offset of the [index]-th reminder (0-based) from the anchor. */
    fun offsetMs(index: Int): Long =
        if (index < offsetsMs.size) offsetsMs[index] else offsetsMs.last() + repeatMs * (index - offsetsMs.size + 1)

    /** The index of the first reminder due strictly after [elapsedSinceAnchorMs] (used to skip a backlog after a restart). */
    fun nextIndexAfter(elapsedSinceAnchorMs: Long): Int {
        var index = 0
        while (offsetMs(index) <= elapsedSinceAnchorMs) index++
        return index
    }
}

/** Rung 3's capture half: the app's OCR text checked against that app's cancelled-state keywords. */
object CancelledKeywords {
    fun found(ocrText: String, keywords: List<String>): Boolean {
        val haystack = ocrText.lowercase()
        return keywords.any { it.isNotBlank() && haystack.contains(it.lowercase()) }
    }
}
