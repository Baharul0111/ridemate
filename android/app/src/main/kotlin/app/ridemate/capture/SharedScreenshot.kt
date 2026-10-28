package app.ridemate.capture

import android.content.ContentResolver
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * The FLAG_SECURE fallback (§4.2): the user shares a screenshot of a fare screen to RideMate (`ACTION_SEND` of an image).
 * The image is read into memory only (never copied to disk), size-limited, and downsampled to capture size.
 */
object SharedScreenshot {
    /** Larger shares are refused (a phone screenshot is a few MB at most). */
    const val MAX_BYTES: Int = 15 * 1024 * 1024

    /** Decoded bitmaps keep their longest side at or below this (the live capture's size). */
    const val MAX_LONG_SIDE: Int = CaptureSize.MAX_LONG_SIDE

    /**
     * The shared image's `content://` URI, or null unless [intent] is `ACTION_SEND` of an image MIME type with a
     * content stream. Other schemes (`file://`) are refused so a share can never make RideMate read its own files.
     */
    fun streamUri(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val type = intent.type ?: return null
        if (!type.startsWith("image/")) return null
        val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        return uri?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
    }

    /** Reads at most [maxBytes]; null when the stream is longer (or empty). */
    fun readBounded(input: InputStream, maxBytes: Int = MAX_BYTES): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > maxBytes) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    /** The power-of-two sample size that brings the longest side to at most [maxLongSide]. */
    fun sampleSizeFor(width: Int, height: Int, maxLongSide: Int = MAX_LONG_SIDE): Int {
        var sample = 1
        val longSide = maxOf(width, height)
        while (longSide / sample > maxLongSide) sample *= 2
        return sample
    }

    /** Decodes [bytes] (JPEG/PNG/WebP) downsampled to capture size; null when it isn't an image. */
    fun decode(bytes: ByteArray, maxLongSide: Int = MAX_LONG_SIDE): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxLongSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /** Reads and decodes the shared image as a frame taken at [receivedAtElapsedMs]; null when unreadable. */
    fun load(resolver: ContentResolver, uri: Uri, receivedAtElapsedMs: Long): FrameImage? {
        val bytes = runCatching { resolver.openInputStream(uri)?.use { readBounded(it) } }.getOrNull() ?: return null
        val bitmap = runCatching { decode(bytes) }.getOrNull() ?: return null
        return FrameImage(
            bitmap = bitmap,
            capturedAtElapsedMs = receivedAtElapsedMs,
            origin = FrameOrigin.SHARED,
            suspectedSecure = BlackFrameDetector.isMostlyUniformDark(bitmap),
        )
    }
}
