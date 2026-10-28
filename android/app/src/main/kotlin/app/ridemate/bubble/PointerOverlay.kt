package app.ridemate.bubble

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import app.ridemate.core.ocr.Box

/** Which side of the target the arrow sits on; it always points toward the target. */
enum class PointerSide { LEFT, RIGHT, ABOVE, BELOW }

/** Where the arrow window goes ([box], screen pixels) and which side of the target it is on. */
data class PointerPlacement(val box: Box, val side: PointerSide)

/**
 * Places the "Show me" arrow BESIDE the target control (§4.2 PointerOverlay), never on it: the arrow's box is at
 * least [gapPx] away from the target, fully on screen, and centred on the target's middle along the other axis.
 * Sides are tried left, right, above, below; null when none fits (the instruction card still guides the user).
 */
object PointerLayout {
    fun place(target: Box, screenWidth: Int, screenHeight: Int, longPx: Int, shortPx: Int, gapPx: Int): PointerPlacement? {
        if (screenWidth <= 0 || screenHeight <= 0 || longPx <= 0 || shortPx <= 0) return null
        val cy = target.centerY.toInt()
        val cx = target.centerX.toInt()
        fun vertical(left: Int, side: PointerSide): PointerPlacement? {
            if (left < 0 || left + longPx > screenWidth || shortPx > screenHeight) return null
            val top = (cy - shortPx / 2).coerceIn(0, screenHeight - shortPx)
            return PointerPlacement(Box(left, top, left + longPx, top + shortPx), side)
        }
        fun horizontal(top: Int, side: PointerSide): PointerPlacement? {
            if (top < 0 || top + longPx > screenHeight || shortPx > screenWidth) return null
            val left = (cx - shortPx / 2).coerceIn(0, screenWidth - shortPx)
            return PointerPlacement(Box(left, top, left + shortPx, top + longPx), side)
        }
        val candidates = listOf(
            { vertical(target.l - gapPx - longPx, PointerSide.LEFT) },
            { vertical(target.r + gapPx, PointerSide.RIGHT) },
            { horizontal(target.t - gapPx - longPx, PointerSide.ABOVE) },
            { horizontal(target.b + gapPx, PointerSide.BELOW) },
        )
        return candidates.firstNotNullOfOrNull { it() }?.takeIf { !overlaps(it.box, target) }
    }

    /** True when the two boxes share any area. */
    fun overlaps(a: Box, b: Box): Boolean = a.l < b.r && b.l < a.r && a.t < b.b && b.t < a.b
}

/** Draws one arrow pointing from [side] toward the target. Not touchable and not focusable (window flags). */
@SuppressLint("ViewConstructor")
class PointerView(context: Context, val side: PointerSide, color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }
    private val path = Path()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        path.reset()
        when (side) {
            // Arrow left of the target, pointing right.
            PointerSide.LEFT -> arrow(0f, h / 2, w, h / 2, h)
            PointerSide.RIGHT -> arrow(w, h / 2, 0f, h / 2, h)
            PointerSide.ABOVE -> arrow(w / 2, 0f, w / 2, h, w)
            PointerSide.BELOW -> arrow(w / 2, h, w / 2, 0f, w)
        }
        canvas.drawPath(path, paint)
    }

    /** A shaft from (x0, y0) to a head at (x1, y1); [thickness] is the arrow's short side. */
    private fun arrow(x0: Float, y0: Float, x1: Float, y1: Float, thickness: Float) {
        val horizontal = y0 == y1
        val length = if (horizontal) kotlin.math.abs(x1 - x0) else kotlin.math.abs(y1 - y0)
        val dir = if (horizontal) kotlin.math.sign(x1 - x0) else kotlin.math.sign(y1 - y0)
        val head = length * 0.45f
        val shaft = thickness * 0.18f
        if (horizontal) {
            val neck = x1 - dir * head
            path.moveTo(x0, y0 - shaft)
            path.lineTo(neck, y0 - shaft)
            path.lineTo(neck, 0f)
            path.lineTo(x1, y1)
            path.lineTo(neck, thickness)
            path.lineTo(neck, y0 + shaft)
            path.lineTo(x0, y0 + shaft)
        } else {
            val neck = y1 - dir * head
            path.moveTo(x0 - shaft, y0)
            path.lineTo(x0 - shaft, neck)
            path.lineTo(0f, neck)
            path.lineTo(x1, y1)
            path.lineTo(thickness, neck)
            path.lineTo(x0 + shaft, neck)
            path.lineTo(x0 + shaft, y0)
        }
        path.close()
    }
}
