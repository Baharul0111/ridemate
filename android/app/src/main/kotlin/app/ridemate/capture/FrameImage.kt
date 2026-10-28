package app.ridemate.capture

import android.graphics.Bitmap

/** Where a frame came from. I6: only [DEMO] frames are exempt from the 2 s tap rule. */
enum class FrameOrigin { CAPTURE, SHARED, DEMO }

/**
 * One screen frame held in memory only (goal 24: screenshots are never written to disk), with the elapsed-realtime
 * instant it was taken from the screen.
 *
 * The bitmap is reference counted so work that outlives its caller (ML Kit tasks keep running after a deadline
 * cancels the coroutine) can [retain] it; the bitmap is recycled when the owner's [close] and every [release] have
 * happened. All methods are thread-safe.
 */
class FrameImage(
    bitmap: Bitmap,
    val capturedAtElapsedMs: Long,
    val origin: FrameOrigin = FrameOrigin.CAPTURE,
    /** True when the pixels looked like a FLAG_SECURE frame (mostly uniform dark); see [BlackFrameDetector]. */
    val suspectedSecure: Boolean = false,
) : AutoCloseable {
    private var bitmap: Bitmap? = bitmap
    private var refs = 1
    private var ownerClosed = false

    val width: Int = bitmap.width
    val height: Int = bitmap.height

    /** True once the bitmap has been recycled. */
    val isReleased: Boolean
        @Synchronized get() = bitmap == null

    /** Takes an extra reference; false (and nothing taken) when the bitmap is already gone. */
    @Synchronized
    fun retain(): Boolean {
        if (bitmap == null) return false
        refs++
        return true
    }

    /** Drops a reference taken with [retain]. */
    @Synchronized
    fun release() {
        if (bitmap == null) return
        refs--
        if (refs <= 0) {
            bitmap?.recycle()
            bitmap = null
        }
    }

    /** Runs [block] with the bitmap while holding a reference; throws [IllegalStateException] once recycled. */
    fun <T> read(block: (Bitmap) -> T): T {
        val held = synchronized(this) {
            val b = bitmap ?: throw IllegalStateException("frame already released")
            refs++
            b
        }
        try {
            return block(held)
        } finally {
            release()
        }
    }

    /** The owner's release; idempotent. */
    override fun close() {
        synchronized(this) {
            if (ownerClosed) return
            ownerClosed = true
        }
        release()
    }
}
