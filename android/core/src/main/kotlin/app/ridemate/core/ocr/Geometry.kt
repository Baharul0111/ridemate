package app.ridemate.core.ocr

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Axis-aligned box in image pixels (x right, y down). Serialised as `[l, t, r, b]` (contracts §6). */
@Serializable(with = BoxSerializer::class)
data class Box(val l: Int, val t: Int, val r: Int, val b: Int) {
    init {
        require(l <= r && t <= b) { "inverted box [$l, $t, $r, $b]" }
    }

    val width: Int get() = r - l
    val height: Int get() = b - t
    val centerX: Double get() = (l + r) / 2.0
    val centerY: Double get() = (t + b) / 2.0

    fun union(other: Box): Box = Box(min(l, other.l), min(t, other.t), max(r, other.r), max(b, other.b))

    fun contains(x: Double, y: Double): Boolean = x >= l && x <= r && y >= t && y <= b

    /** Pixels shared on the y axis (0 when the boxes don't overlap vertically). */
    fun verticalOverlap(other: Box): Int = max(0, min(b, other.b) - max(t, other.t))

    /** Pixels shared on the x axis (0 when the boxes don't overlap horizontally). */
    fun horizontalOverlap(other: Box): Int = max(0, min(r, other.r) - max(l, other.l))

    /** Grows the box by [dx]/[dy] pixels on each side, clamped to a `width × height` frame. */
    fun expand(dx: Int, dy: Int, frameWidth: Int, frameHeight: Int): Box {
        val nl = (l - dx).coerceIn(0, frameWidth)
        val nt = (t - dy).coerceIn(0, frameHeight)
        return Box(nl, nt, (r + dx).coerceIn(nl, frameWidth), (b + dy).coerceIn(nt, frameHeight))
    }

    /** This box on the 0–1000 grid of a `frameWidth × frameHeight` image (contracts §1.3 `bbox_0_1000`). */
    fun toGrid(frameWidth: Int, frameHeight: Int): GridBox = GridBox(
        x0 = l * GRID / frameWidth,
        y0 = t * GRID / frameHeight,
        x1 = r * GRID / frameWidth,
        y1 = b * GRID / frameHeight,
    )

    companion object {
        fun unionOf(boxes: Iterable<Box>): Box = boxes.reduce(Box::union)
    }
}

/** Size of the model's normalised coordinate grid (`bbox_0_1000`). */
const val GRID: Double = 1000.0

/** A box on the 0–1000 grid of an image; x0 ≤ x1 and y0 ≤ y1, each clamped to the grid. */
data class GridBox(val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
    init {
        require(x0 <= x1 && y0 <= y1) { "inverted grid box" }
    }

    val centerX: Double get() = (x0 + x1) / 2.0
    val centerY: Double get() = (y0 + y1) / 2.0

    fun contains(x: Double, y: Double): Boolean = x >= x0 && x <= x1 && y >= y0 && y <= y1

    /** Grows the box by [margin] grid units on each side (used for the ± 4% position tolerance of §4.3 b). */
    fun expand(margin: Double): GridBox = GridBox(x0 - margin, y0 - margin, x1 + margin, y1 + margin)

    /** Euclidean distance from (x, y) to the box; 0 when the point is inside. */
    fun distanceTo(x: Double, y: Double): Double {
        val dx = max(0.0, max(x0 - x, x - x1))
        val dy = max(0.0, max(y0 - y, y - y1))
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    fun toPixels(frameWidth: Int, frameHeight: Int): Box = Box(
        l = (x0 * frameWidth / GRID).roundToInt(),
        t = (y0 * frameHeight / GRID).roundToInt(),
        r = (x1 * frameWidth / GRID).roundToInt(),
        b = (y1 * frameHeight / GRID).roundToInt(),
    )

    fun toList(): List<Double> = listOf(x0, y0, x1, y1)

    companion object {
        /**
         * Reads a model-supplied `[x0, y0, x1, y1]`: null unless it has exactly four finite numbers. Corners are
         * re-ordered and clamped to 0–1000 so a sloppy box can never claim area outside the image.
         */
        fun fromList(values: List<Double>?): GridBox? {
            if (values == null || values.size != 4 || values.any { !it.isFinite() }) return null
            val (a, b, c, d) = values.map { it.coerceIn(0.0, GRID) }
            return GridBox(min(a, c), min(b, d), max(a, c), max(b, d))
        }
    }
}

/** Reads `[l, t, r, b]` written as integers or decimals; corners are re-ordered so the box is never inverted. */
object BoxSerializer : KSerializer<Box> {
    private val reader = ListSerializer(Double.serializer())
    private val writer = ListSerializer(Int.serializer())
    override val descriptor: SerialDescriptor = reader.descriptor

    override fun serialize(encoder: Encoder, value: Box) =
        encoder.encodeSerializableValue(writer, listOf(value.l, value.t, value.r, value.b))

    override fun deserialize(decoder: Decoder): Box {
        val v = decoder.decodeSerializableValue(reader)
        if (v.size != 4 || v.any { !it.isFinite() }) throw SerializationException("box must be [l, t, r, b]")
        val (a, b, c, d) = v.map { it.roundToInt() }
        return Box(min(a, c), min(b, d), max(a, c), max(b, d))
    }
}
