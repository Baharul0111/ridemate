package app.ridemate.capture

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.Display
import androidx.core.graphics.createBitmap
import app.ridemate.util.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * [ProjectionGateway] over Android's MediaProjection. The projection mirrors the user's own default display into one
 * ImageReader (never another app's hidden or virtual display). On API 34+ the consent asks for the whole default
 * display (`createConfigForDefaultDisplay`), so there is no single-app mode to fall into.
 */
class MediaProjectionGateway(private val context: Context, private val clock: Clock) : ProjectionGateway {
    private val manager: MediaProjectionManager
        get() = context.getSystemService(MediaProjectionManager::class.java)

    override fun consentIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }

    override fun start(resultCode: Int, data: Intent, onStop: () -> Unit): ProjectionSession? {
        val projection = manager.getMediaProjection(resultCode, data) ?: return null
        val size = CaptureSize.of(defaultDisplayMetrics())
        return try {
            ImageReaderProjectionSession(projection, size, clock, onStop)
        } catch (e: RuntimeException) {
            runCatching { projection.stop() }
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun defaultDisplayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        display.getRealMetrics(metrics)
        return metrics
    }
}

/** The capture surface size: the real display size, scaled so the longest side is at most [MAX_LONG_SIDE] px. */
data class CaptureSize(val width: Int, val height: Int, val densityDpi: Int) {
    companion object {
        /** Keeps two frames and a masked copy at a few tens of MB while staying sharp enough for OCR. */
        const val MAX_LONG_SIDE = 2400

        fun of(metrics: DisplayMetrics): CaptureSize = of(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)

        fun of(width: Int, height: Int, densityDpi: Int): CaptureSize {
            val longSide = max(width, height).coerceAtLeast(1)
            val scale = if (longSide > MAX_LONG_SIDE) MAX_LONG_SIDE.toDouble() / longSide else 1.0
            return CaptureSize(
                width = (width * scale).roundToInt().coerceAtLeast(1),
                height = (height * scale).roundToInt().coerceAtLeast(1),
                densityDpi = (densityDpi * scale).roundToInt().coerceAtLeast(1),
            )
        }
    }
}

/**
 * One VirtualDisplay + ImageReader. The reader's listener always holds the latest image (closing the previous one),
 * so the queue never stalls; [grab] copies it into a bitmap under the same lock. Nothing touches the disk.
 */
internal class ImageReaderProjectionSession(
    private val projection: MediaProjection,
    private val size: CaptureSize,
    private val clock: Clock,
    onStop: () -> Unit,
) : ProjectionSession {
    private val thread = HandlerThread("ridemate-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val arrivals = MutableStateFlow(0L)

    private val lock = Any()
    private var latest: Image? = null // guarded by lock

    @Volatile
    private var stopped = false

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            release()
            onStop()
        }
    }

    @SuppressLint("WrongConstant")
    private val reader: ImageReader = ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, MAX_IMAGES)
    private val display: VirtualDisplay

    init {
        try {
            // Android 14+ requires the callback before createVirtualDisplay.
            projection.registerCallback(callback, handler)
            reader.setOnImageAvailableListener({ r ->
                synchronized(lock) {
                    if (!stopped) {
                        latest?.close()
                        latest = runCatching { r.acquireLatestImage() }.getOrNull()
                        if (latest != null) arrivals.value = clock.elapsedRealtimeMs()
                    }
                }
            }, handler)
            display = projection.createVirtualDisplay(
                "RideMate fare capture", size.width, size.height, size.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler,
            ) ?: throw IllegalStateException("virtual display")
        } catch (e: RuntimeException) {
            runCatching { reader.close() }
            thread.quitSafely()
            throw e
        }
    }

    override suspend fun grab(notBeforeElapsedMs: Long, timeoutMs: Long): FrameImage? {
        withTimeoutOrNull(timeoutMs) { arrivals.first { it >= notBeforeElapsedMs } }
        return withContext(Dispatchers.Default) {
            val bitmap = synchronized(lock) {
                val image = latest
                if (stopped || image == null) null else runCatching { toBitmap(image) }.getOrNull()
            } ?: return@withContext null
            FrameImage(
                bitmap = bitmap,
                capturedAtElapsedMs = clock.elapsedRealtimeMs(),
                origin = FrameOrigin.CAPTURE,
                suspectedSecure = BlackFrameDetector.isMostlyUniformDark(bitmap),
            )
        }
    }

    override fun stop() {
        runCatching { projection.unregisterCallback(callback) }
        release()
        runCatching { projection.stop() }
    }

    /** Idempotent: closes the held image, the virtual display and the reader, and ends the capture thread. */
    private fun release() {
        synchronized(lock) {
            if (stopped) return
            stopped = true
            latest?.close()
            latest = null
        }
        runCatching { display.release() }
        runCatching { reader.close() }
        thread.quitSafely()
    }

    private fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer.apply { rewind() }
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * image.width
        val padded = createBitmap(image.width + rowPadding / pixelStride, image.height)
        padded.copyPixelsFromBuffer(buffer)
        if (rowPadding == 0) return padded
        return Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
    }

    private companion object {
        const val MAX_IMAGES = 3
    }
}
