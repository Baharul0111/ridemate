package app.ridemate.capture

import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream

/** I6 timing guard (pure). */
class FrameTimingTest {
    private val tap = 50_000L

    @Test
    fun `a frame is used only when taken and processed within 2 s of the tap`() {
        assertEquals(FrameTiming.Verdict.OK, FrameTiming.check(tap, tap + 200, tap + 900))
        assertEquals(FrameTiming.Verdict.OK, FrameTiming.check(tap, tap, tap))
        assertEquals(FrameTiming.Verdict.OK, FrameTiming.check(tap, tap + 2_000, tap + 2_000))
        assertEquals(FrameTiming.Verdict.TAKEN_BEFORE_TAP, FrameTiming.check(tap, tap - 1, tap + 100))
        assertEquals(FrameTiming.Verdict.TAKEN_TOO_LATE, FrameTiming.check(tap, tap + 2_001, tap + 2_001))
        assertEquals(FrameTiming.Verdict.PROCESSED_TOO_LATE, FrameTiming.check(tap, tap + 300, tap + 2_001))
        assertEquals(FrameTiming.Verdict.PROCESSED_BEFORE_TAKEN, FrameTiming.check(tap, tap + 300, tap + 299))
    }

    @Test
    fun `demo frames are exempt and shared screenshots are not`() {
        assertTrue(FrameTiming.allows(tap, tap - 60_000, tap + 60_000, FrameOrigin.DEMO))
        assertFalse(FrameTiming.allows(tap, tap, tap + 5_000, FrameOrigin.SHARED))
    }
}

/** FLAG_SECURE detection (pure: pixels come from a lambda). */
class BlackFrameDetectorTest {
    private val w = 1080
    private val h = 2400
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    @Test
    fun `a secure capture - black content with live system bars - is flagged`() {
        val statusBarTop = (h * 0.05).toInt()
        val navBarTop = (h * 0.93).toInt()
        assertTrue(BlackFrameDetector.isMostlyUniformDark(w, h) { _, y -> if (y < statusBarTop || y > navBarTop) white else black })
        assertTrue(BlackFrameDetector.isMostlyUniformDark(w, h) { _, _ -> 0xFF0A0A0A.toInt() })
    }

    @Test
    fun `normal and dark-mode screens are not flagged`() {
        assertFalse(BlackFrameDetector.isMostlyUniformDark(w, h) { _, _ -> 0xFFFBF7F2.toInt() })
        // Dark mode: black background with a map in the top half.
        assertFalse(BlackFrameDetector.isMostlyUniformDark(w, h) { _, y -> if (y < h / 2) 0xFF4A6B3C.toInt() else black })
        // Dark mode list: thin white text lines every 180 px (28 px tall).
        assertFalse(BlackFrameDetector.isMostlyUniformDark(w, h) { x, y -> if (y % 180 < 28 && x in 150..900) white else black })
    }

    @Test
    fun `luma follows Rec 601 and the content band skips the system bars`() {
        assertEquals(0, BlackFrameDetector.luma(black))
        assertEquals(255, BlackFrameDetector.luma(white))
        assertEquals(76, BlackFrameDetector.luma(0xFFFF0000.toInt()))
        assertFalse(BlackFrameDetector.inContentBand(30.0, h))
        assertTrue(BlackFrameDetector.inContentBand(1200.0, h))
        assertFalse(BlackFrameDetector.inContentBand(2350.0, h))
    }
}

@RunWith(AndroidJUnit4::class)
class CapturePlatformTest {

    @Test
    fun `the session service keeps mediaProjection only while projecting`() {
        val special = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        val projection = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        assertEquals(special or projection, ForegroundTypes.sessionTypes(projectionActive = true, sdkInt = 35))
        assertEquals(special, ForegroundTypes.sessionTypes(projectionActive = false, sdkInt = 37))
        assertEquals(projection, ForegroundTypes.sessionTypes(projectionActive = true, sdkInt = 29))
        assertEquals(0, ForegroundTypes.sessionTypes(projectionActive = false, sdkInt = 33))
    }

    @Test
    fun `only fare reading keeps its consent - later captures say Android will ask again`() {
        assertFalse(CapturePurpose.FARE_READING.asksAgain)
        assertNull(CapturePurpose.FARE_READING.askAgainLabelRes)
        listOf(CapturePurpose.SHOW_ME, CapturePurpose.DRIVER_ETA, CapturePurpose.CANCEL_PROOF, CapturePurpose.CAPTURE_TEST).forEach {
            assertTrue(it.asksAgain)
            assertEquals(R.string.capture_android_will_ask_again, it.askAgainLabelRes)
        }
    }

    @Test
    fun `the capture surface is the display size, capped at 2400 px on the long side`() {
        assertEquals(CaptureSize(1080, 2400, 420), CaptureSize.of(1080, 2400, 420))
        assertEquals(CaptureSize(1200, 2400, 320), CaptureSize.of(1440, 2880, 384))
    }

    @Test
    fun `a frame image is recycled only after the owner and every borrower let go`() {
        val frame = FrameImage(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888), 10)
        assertTrue(frame.retain())
        frame.close()
        assertFalse(frame.isReleased)
        assertEquals(4, frame.read { it.width })
        frame.release()
        assertTrue(frame.isReleased)
        assertFalse(frame.retain())
        frame.close() // idempotent
        assertTrue(runCatching { frame.read { it.width } }.isFailure)
    }

    @Test
    fun `only image shares with a content stream are accepted`() {
        val content = Uri.parse("content://com.android.providers.media/external/images/1")
        fun share(type: String?, uri: Uri?, action: String = Intent.ACTION_SEND) =
            Intent(action).apply { this.type = type; if (uri != null) putExtra(Intent.EXTRA_STREAM, uri) }

        assertEquals(content, SharedScreenshot.streamUri(share("image/png", content)))
        assertEquals(content, SharedScreenshot.streamUri(share("image/jpeg", content)))
        assertNull(SharedScreenshot.streamUri(share("text/plain", content)))
        assertNull(SharedScreenshot.streamUri(share("image/png", Uri.parse("file:///data/data/in.ridemate.app/databases/ridemate.db"))))
        assertNull(SharedScreenshot.streamUri(share("image/png", null)))
        assertNull(SharedScreenshot.streamUri(share("image/png", content, Intent.ACTION_VIEW)))
        assertNull(SharedScreenshot.streamUri(null))
    }

    @Test
    fun `shared images are size-limited and downsampled to capture size`() {
        assertEquals(3, SharedScreenshot.readBounded(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3)!!.size)
        assertNull(SharedScreenshot.readBounded(ByteArrayInputStream(ByteArray(4)), 3))
        assertNull(SharedScreenshot.readBounded(ByteArrayInputStream(ByteArray(0)), 3))
        assertEquals(1, SharedScreenshot.sampleSizeFor(1080, 2400))
        assertEquals(2, SharedScreenshot.sampleSizeFor(1440, 3200))
        assertEquals(4, SharedScreenshot.sampleSizeFor(3000, 9000))
    }
}
