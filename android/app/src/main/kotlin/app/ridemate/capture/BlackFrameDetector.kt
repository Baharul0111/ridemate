package app.ridemate.capture

import android.graphics.Bitmap
import androidx.core.graphics.get

/**
 * FLAG_SECURE detection (§4.2 CaptureManager). Android renders a secure window as pure black in a screen capture,
 * while the status and navigation bars around it still draw. A frame counts as "mostly uniform dark" when nearly
 * every sample of its content band (the frame minus the top [TOP_BAND] and bottom [BOTTOM_BAND]) is near-black.
 *
 * This is only a suspicion: a dark-mode screen with very little text can look the same, so the fare reader also
 * checks that OCR found no text in the content band before it gives up on the capture. A secure screen is never
 * worked around; the user is offered the share-a-screenshot fallback or manual entry instead.
 */
object BlackFrameDetector {
    /** Top share of the frame skipped (status bar). */
    const val TOP_BAND = 0.06

    /** Bottom share of the frame skipped (navigation bar). */
    const val BOTTOM_BAND = 0.08

    /** A sample is dark when its luma (0–255) is at most this. */
    const val DARK_LUMA = 16

    /** The frame is suspected secure when at least this share of samples is dark. */
    const val DARK_SHARE = 0.995

    const val COLUMNS = 54
    const val ROWS = 120

    /**
     * True when the frame's content band is mostly uniform dark. [pixelAt] returns an ARGB colour; it is sampled on a
     * [COLUMNS] × [ROWS] grid only, so this is cheap on full-resolution frames.
     */
    fun isMostlyUniformDark(width: Int, height: Int, pixelAt: (x: Int, y: Int) -> Int): Boolean {
        if (width <= 0 || height <= 0) return true
        val top = (height * TOP_BAND).toInt()
        val bottom = (height * (1 - BOTTOM_BAND)).toInt().coerceAtLeast(top + 1)
        val cols = minOf(COLUMNS, width)
        val rows = minOf(ROWS, bottom - top)
        var dark = 0
        var total = 0
        for (r in 0 until rows) {
            val y = top + ((r + 0.5) * (bottom - top) / rows).toInt().coerceIn(0, height - 1)
            for (c in 0 until cols) {
                val x = ((c + 0.5) * width / cols).toInt().coerceIn(0, width - 1)
                if (luma(pixelAt(x, y)) <= DARK_LUMA) dark++
                total++
            }
        }
        return total == 0 || dark >= DARK_SHARE * total
    }

    fun isMostlyUniformDark(bitmap: Bitmap): Boolean =
        isMostlyUniformDark(bitmap.width, bitmap.height) { x, y -> bitmap[x, y] }

    /** Rec. 601 luma of an ARGB colour, 0–255. */
    fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (299 * r + 587 * g + 114 * b) / 1000
    }

    /** True when the content band (as in [isMostlyUniformDark]) of a `height`-tall frame contains [centerY]. */
    fun inContentBand(centerY: Double, height: Int): Boolean =
        centerY >= height * TOP_BAND && centerY <= height * (1 - BOTTOM_BAND)
}
