package app.ridemate.core.format

import app.ridemate.core.model.LatLng
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS_KM = 6371.0088

    /** Great-circle distance in km (the "haversine km" of contracts §2.3). */
    fun haversineKm(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).let { it * it }
        return 2 * EARTH_RADIUS_KM * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}

/** Driver distances as ride apps print them (`1.2 km`, `800 m`, `१.२ किमी`). */
object DistanceText {
    private val PATTERN = Regex("^(\\d+(?:\\.\\d+)?)\\s*(km|kms|किमी|कि\\.मी\\.|m|mtr|meters?|metres?|मी)\\.?$", RegexOption.IGNORE_CASE)

    /** Metres, or null unless the whole text is one number and a unit. */
    fun parseMeters(text: String?): Int? {
        if (text == null) return null
        val m = PATTERN.matchEntire(normalizeSpaces(Digits.toAscii(text.trim()))) ?: return null
        val value = m.groupValues[1].toDoubleOrNull() ?: return null
        val unit = m.groupValues[2].lowercase(Locale.ROOT)
        val isKm = unit.startsWith("k") || unit.startsWith("कि")
        val meters = if (isKm) value * 1000 else value
        return if (meters in 0.0..1_000_000.0) meters.roundToInt() else null
    }

    /** `1.2 km` from 1200, `800 m` below one km; built from numbers so no OCR or model wording reaches the UI. */
    fun format(meters: Int, numerals: Numerals = Numerals.LATIN): String {
        val text = if (meters < 1000) {
            "$meters m"
        } else {
            val km = meters / 1000.0
            val shown = if (meters % 1000 == 0) km.toInt().toString() else String.format(Locale.ROOT, "%.1f", km)
            "$shown km"
        }
        return Digits.write(text, numerals)
    }
}
