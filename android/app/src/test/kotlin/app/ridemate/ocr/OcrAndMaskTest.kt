package app.ridemate.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.capture.FrameImage
import app.ridemate.core.mask.MASK_MARKER
import app.ridemate.core.mask.PlateHash
import app.ridemate.core.ocr.Box
import app.ridemate.core.ocr.OcrFrame
import app.ridemate.core.ocr.OcrToken
import app.ridemate.net.ApiLimits
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Latin + Devanagari merge (pure). */
class TokenMergeTest {
    private fun el(text: String, l: Int, t: Int, r: Int, b: Int, line: Int = 0, block: Int = 0) =
        RecognizedElement(text, Box(l, t, r, b), line, block)

    @Test
    fun `the same Latin word read by both models is kept once, from the Latin model`() {
        val frame = TokenMerge.merge(
            1080, 2400,
            latin = listOf(el("₹110", 800, 400, 1000, 448), el("Auto", 150, 400, 260, 440)),
            devanagari = listOf(el("₹11O", 802, 402, 998, 446), el("Auto", 152, 401, 258, 439)),
        )
        assertEquals(listOf("₹110", "Auto"), frame.tokens.map { it.text })
        assertEquals(TokenMerge.ENGINE, frame.engine)
    }

    @Test
    fun `Hindi words and digits come from the Devanagari model`() {
        val frame = TokenMerge.merge(
            1080, 2400,
            latin = listOf(el("Ia", 150, 600, 300, 640, line = 0), el("₹920", 800, 600, 1000, 648, line = 1)),
            devanagari = listOf(el("ऑटो", 150, 600, 300, 640, line = 0), el("₹१२०", 800, 600, 1000, 648, line = 0), el("मिनट", 150, 700, 260, 730, line = 1)),
        )
        assertEquals(setOf("ऑटो", "₹१२०", "मिनट"), frame.tokens.map { it.text }.toSet())
        // Devanagari line ids are shifted past the Latin ones, so engines never share a line.
        val latinLines = 2
        assertTrue(frame.tokens.all { it.lineId >= latinLines })
    }

    @Test
    fun `words found by only one model are kept, blanks dropped, boxes clamped to the frame`() {
        val frame = TokenMerge.merge(
            1080, 2400,
            latin = listOf(el("Uber", 10, 10, 100, 40), el("  ", 200, 10, 260, 40)),
            devanagari = listOf(el("दूर", 500, 900, 560, 930), el("edge", 1000, 2390, 1200, 2500)),
        )
        assertEquals(listOf("Uber", "दूर", "edge"), frame.tokens.map { it.text })
        assertEquals(Box(1000, 2390, 1080, 2400), frame.tokens.last().box)
    }

    @Test
    fun `overlap is measured against the smaller box`() {
        assertEquals(1.0, TokenMerge.overlapOfSmaller(Box(0, 0, 10, 10), Box(0, 0, 100, 100)), 1e-9)
        assertEquals(0.0, TokenMerge.overlapOfSmaller(Box(0, 0, 10, 10), Box(20, 20, 30, 30)), 1e-9)
        assertEquals(0.5, TokenMerge.overlapOfSmaller(Box(0, 0, 10, 10), Box(5, 0, 15, 10)), 1e-9)
        assertTrue(TokenMerge.hasDevanagari("१२०"))
        assertFalse(TokenMerge.hasDevanagari("₹120"))
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaskingRendererTest {
    private val w = 540
    private val h = 1200

    /** A light screen with a phone number line and a fare line. */
    private fun screen(): Pair<FrameImage, OcrFrame> {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val ocr = OcrFrame(
            w, h,
            listOf(
                OcrToken("Call", Box(40, 400, 110, 430), 1, 0),
                OcrToken("98765", Box(120, 400, 220, 430), 1, 0),
                OcrToken("43210", Box(230, 400, 330, 430), 1, 0),
                OcrToken("Auto", Box(40, 600, 120, 630), 2, 0),
                OcrToken("₹110", Box(400, 596, 500, 634), 2, 0),
                OcrToken("AS01AB1234", Box(40, 800, 260, 830), 3, 0),
            ),
        )
        return FrameImage(bitmap, 1_000) to ocr
    }

    @Test
    fun `PII and the status bar are painted over on a copy, prices are left alone`() {
        val (frame, ocr) = screen()
        MaskingRenderer().maskForUpload(frame, ocr).use { upload ->
            val decoded = BitmapFactory.decodeByteArray(upload.jpeg, 0, upload.jpeg.size)
            fun dark(x: Int, y: Int) = Color.red(decoded.getPixel(x, y)) < 60
            assertTrue("status bar", dark(w / 2, 10))
            assertTrue("phone digits", dark(170, 415))
            assertTrue("phone digits", dark(280, 415))
            assertTrue("plate", dark(150, 815))
            val price = Box(400, 596, 500, 634)
            assertTrue("the price stays readable", upload.maskedRects.none { it.horizontalOverlap(price) > 0 && it.verticalOverlap(price) > 0 })
            assertFalse(dark(450, 615))
            assertFalse("background", dark(450, 1000))

            val texts = upload.maskedOcr.tokens.map { it.text }
            assertTrue(texts.contains("₹110"))
            assertFalse(texts.any { it.contains("98765") || it.contains("43210") || it.contains("AS01") })
            assertTrue(texts.any { it.contains(MASK_MARKER) })
            assertEquals(setOf(PlateHash.sha256("AS01AB1234")), upload.plateHashes)
            assertTrue(upload.maskedRects.isNotEmpty())
            assertEquals(MaskingRenderer.sha256Hex(upload.jpeg), upload.sha256)
            assertEquals(w, upload.imageWidth)
            // JPEG magic bytes: the API client accepts it.
            assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), upload.jpeg.copyOfRange(0, 3))
        }
        // The captured frame itself is untouched and still owned by the caller.
        assertFalse(frame.isReleased)
        assertEquals(Color.WHITE, frame.read { it.getPixel(170, 415) })
        frame.close()
    }

    @Test
    fun `paint covers whole rects even when the bitmap is scaled`() {
        val bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        MaskingRenderer.paint(bitmap, listOf(Box(100, 100, 300, 200)), frameWidth = 1000, frameHeight = 2000)
        assertEquals(Color.BLACK, bitmap.getPixel(10, 10))
        assertEquals(Color.BLACK, bitmap.getPixel(29, 19))
        assertEquals(Color.WHITE, bitmap.getPixel(31, 21))
        assertEquals(Color.WHITE, bitmap.getPixel(9, 9))
    }

    @Test
    fun `uploads stay within 1 MB by stepping quality, then size`() {
        val tried = mutableListOf<Pair<Int, Int>>()
        // Pretends only 0.5 scale at quality 65 or lower fits.
        val encoder = JpegEncoder { bitmap, quality ->
            tried += bitmap.width to quality
            val fits = bitmap.width <= w / 2 && quality <= 65
            ByteArray(if (fits) 900_000 else 1_200_000)
        }
        val (frame, ocr) = screen()
        MaskingRenderer(encoder = encoder).maskForUpload(frame, ocr).use { upload ->
            assertTrue(upload.jpeg.size <= ApiLimits.MAX_IMAGE_BYTES)
            assertEquals(w / 2, upload.imageWidth)
            assertEquals(h / 2, upload.imageHeight)
        }
        assertEquals(
            MaskingRenderer.QUALITIES.map { w to it } + MaskingRenderer.QUALITIES.map { 405 to it } + listOf(270 to 85, 270 to 75, 270 to 65),
            tried,
        )
        frame.close()
    }

    @Test
    fun `an image that never fits is refused rather than uploaded`() {
        val (frame, ocr) = screen()
        val renderer = MaskingRenderer(encoder = { _, _ -> ByteArray(2_000_000) })
        assertTrue(runCatching { renderer.maskForUpload(frame, ocr) }.exceptionOrNull() is ImageTooLargeException)
        frame.close()
    }

    @Test
    fun `re-read crops come from the masked frame and are dropped with it`() {
        val (frame, ocr) = screen()
        val upload = MaskingRenderer().maskForUpload(frame, ocr)
        val crops = upload.cropJpegs(listOf("r0" to Box(30, 380, 340, 450), "r1" to Box(380, 580, 520, 650)))!!
        assertEquals(listOf("r0", "r1"), crops.map { it.rowId })
        val phoneCrop = BitmapFactory.decodeByteArray(crops[0].jpeg, 0, crops[0].jpeg.size)
        assertTrue("the phone number stays masked in its crop", Color.red(phoneCrop.getPixel(140, 35)) < 60)
        assertTrue(crops.sumOf { it.jpeg.size } <= ApiLimits.MAX_IMAGE_BYTES)
        assertNull(upload.cropJpegs(listOf("r0" to Box(30, 380, 340, 450)), maxTotalBytes = 10))

        upload.close()
        assertNull(upload.cropJpegs(listOf("r0" to Box(30, 380, 340, 450))))
        frame.close()
    }
}
