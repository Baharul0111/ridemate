package app.ridemate.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.scale
import app.ridemate.capture.FrameImage
import app.ridemate.core.mask.MaskConfig
import app.ridemate.core.mask.MaskResult
import app.ridemate.core.mask.maskTokens
import app.ridemate.core.ocr.Box
import app.ridemate.core.ocr.OcrFrame
import app.ridemate.net.ApiLimits
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** One re-read row crop, JPEG-encoded from the masked frame. */
class RowCropJpeg(val rowId: String, val jpeg: ByteArray)

/**
 * A masked frame ready to leave the phone (goal 24): the JPEG to upload, the masked tokens, and the plate hashes for
 * same-driver detection. Holds the masked full-size copy in memory for re-read crops until [close].
 */
interface MaskedUpload : AutoCloseable {
    /** JPEG bytes, at most [ApiLimits.MAX_IMAGE_BYTES]. */
    val jpeg: ByteArray

    /** Size of the uploaded image (may be smaller than the frame after the size cap). */
    val imageWidth: Int
    val imageHeight: Int

    /** The frame's OCR with every PII span replaced by the mask marker (frame pixel coordinates). */
    val maskedOcr: OcrFrame

    /** SHA-256 of each plate seen (never the plate itself). */
    val plateHashes: Set<String>

    /** SHA-256 (lower-case hex) of [jpeg], stored with the quotes it produced. */
    val sha256: String

    /** Rects that were painted over (frame pixel coordinates). */
    val maskedRects: List<Box>

    /** JPEG crops of the masked frame for `/v1/reread`, all together at most [maxTotalBytes]; null if they can't fit. */
    fun cropJpegs(crops: List<Pair<String, Box>>, maxTotalBytes: Int = ApiLimits.MAX_IMAGE_BYTES): List<RowCropJpeg>?
}

/** Masks frames before upload (production: [MaskingRenderer]; tests may fake it). */
interface FrameMasker {
    /** PII spans of [ocr] masked in the tokens only (frames that are never uploaded). */
    fun maskTokensOnly(ocr: OcrFrame): MaskResult

    /** Paints every PII rect (status bar included) on a copy of [frame] and encodes it within the upload limit. */
    fun maskForUpload(frame: FrameImage, ocr: OcrFrame): MaskedUpload
}

/** Compresses a bitmap to JPEG at a quality (seam for size-cap tests). */
fun interface JpegEncoder {
    fun encode(bitmap: Bitmap, quality: Int): ByteArray

    companion object {
        val ANDROID = JpegEncoder { bitmap, quality ->
            ByteArrayOutputStream(256 * 1024).use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "jpeg encode failed" }
                out.toByteArray()
            }
        }
    }
}

/** The image was still larger than the limit at the smallest size and quality tried. */
class ImageTooLargeException : Exception("masked image over the upload limit")

/**
 * [FrameMasker] that draws opaque blocks over every rect core `maskTokens` returns (phone numbers, OTP/PIN, plates,
 * names, the status bar) on a copy of the bitmap, then JPEG-encodes it at most [maxBytes]: qualities [QUALITIES] at
 * full size, then the same at each of [SCALES]. Everything stays in memory; intermediate bitmaps are recycled.
 */
class MaskingRenderer(
    private val config: () -> MaskConfig = { MaskConfig.DEFAULT },
    private val encoder: JpegEncoder = JpegEncoder.ANDROID,
    private val maxBytes: Int = ApiLimits.MAX_IMAGE_BYTES,
) : FrameMasker {

    override fun maskTokensOnly(ocr: OcrFrame): MaskResult = maskTokens(ocr, config())

    override fun maskForUpload(frame: FrameImage, ocr: OcrFrame): MaskedUpload {
        val mask = maskTokens(ocr, config())
        val masked = frame.read { source -> source.copy(Bitmap.Config.ARGB_8888, true) }
            ?: throw IllegalStateException("bitmap copy failed")
        try {
            paint(masked, mask.rectsToBlur, ocr.width, ocr.height)
            val encoded = encodeWithinLimit(masked)
            return RenderedUpload(
                jpeg = encoded.bytes,
                imageWidth = encoded.width,
                imageHeight = encoded.height,
                maskedOcr = ocr.copy(tokens = mask.maskedTokens),
                plateHashes = mask.plateHashes,
                sha256 = sha256Hex(encoded.bytes),
                maskedRects = mask.rectsToBlur,
                bitmap = masked,
                encoder = encoder,
            )
        } catch (e: Throwable) {
            masked.recycle()
            throw e
        }
    }

    internal class Encoded(val bytes: ByteArray, val width: Int, val height: Int)

    /** Tries each quality at full size, then at each smaller scale, and returns the first result within the limit. */
    internal fun encodeWithinLimit(bitmap: Bitmap): Encoded {
        for (scale in SCALES) {
            val scaled = if (scale == 1.0) {
                bitmap
            } else {
                bitmap.scale(
                    (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                    (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                    filter = true,
                )
            }
            try {
                for (quality in QUALITIES) {
                    val bytes = encoder.encode(scaled, quality)
                    if (bytes.size <= maxBytes) return Encoded(bytes, scaled.width, scaled.height)
                }
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        }
        throw ImageTooLargeException()
    }

    private class RenderedUpload(
        override val jpeg: ByteArray,
        override val imageWidth: Int,
        override val imageHeight: Int,
        override val maskedOcr: OcrFrame,
        override val plateHashes: Set<String>,
        override val sha256: String,
        override val maskedRects: List<Box>,
        private var bitmap: Bitmap?,
        private val encoder: JpegEncoder,
    ) : MaskedUpload {

        @Synchronized
        override fun cropJpegs(crops: List<Pair<String, Box>>, maxTotalBytes: Int): List<RowCropJpeg>? {
            val source = bitmap ?: return null
            if (crops.isEmpty()) return emptyList()
            for (quality in QUALITIES) {
                val out = crops.mapNotNull { (rowId, box) -> crop(source, box)?.let { RowCropJpeg(rowId, encodeAndRecycle(it, quality)) } }
                if (out.size == crops.size && out.sumOf { it.jpeg.size } <= maxTotalBytes) return out
            }
            return null
        }

        private fun crop(source: Bitmap, box: Box): Bitmap? {
            val l = box.l.coerceIn(0, source.width - 1)
            val t = box.t.coerceIn(0, source.height - 1)
            val r = box.r.coerceIn(l + 1, source.width)
            val b = box.b.coerceIn(t + 1, source.height)
            return runCatching { Bitmap.createBitmap(source, l, t, r - l, b - t) }.getOrNull()
        }

        private fun encodeAndRecycle(bitmap: Bitmap, quality: Int): ByteArray =
            try {
                encoder.encode(bitmap, quality)
            } finally {
                bitmap.recycle()
            }

        @Synchronized
        override fun close() {
            bitmap?.recycle()
            bitmap = null
        }
    }

    companion object {
        val QUALITIES: List<Int> = listOf(85, 75, 65, 55, 45)
        val SCALES: List<Double> = listOf(1.0, 0.75, 0.5, 0.35)

        private val MASK_PAINT = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
            isAntiAlias = false
        }

        /** Paints [rects] (in `frameWidth × frameHeight` OCR coordinates) as opaque black blocks on [bitmap]. */
        fun paint(bitmap: Bitmap, rects: List<Box>, frameWidth: Int, frameHeight: Int) {
            if (rects.isEmpty()) return
            val sx = bitmap.width.toFloat() / frameWidth
            val sy = bitmap.height.toFloat() / frameHeight
            val canvas = Canvas(bitmap)
            for (r in rects) {
                canvas.drawRect(floor(r.l * sx), floor(r.t * sy), ceil(r.r * sx), ceil(r.b * sy), MASK_PAINT)
            }
        }

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
