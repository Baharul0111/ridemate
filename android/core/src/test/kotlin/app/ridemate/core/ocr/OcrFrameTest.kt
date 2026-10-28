package app.ridemate.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrFrameTest {
    private val fixture = """
        {"image_sha256": "ab12", "width": 1080, "height": 2400, "engine": "mlkit-text-v2",
         "recorded_at": "2026-10-28T10:00:00Z", "extra": {"ignored": true},
         "tokens": [{"text": "₹110", "box": [900, 600, 1000, 648], "line": 12, "block": 3},
                    {"text": "Auto", "box": [150.4, 604.6, 260, 644], "line": 11, "block": 2},
                    {"text": "ok", "box": [10, 20, 0, 5]}]}
    """.trimIndent()

    @Test
    fun `reads the contracts fixture format`() {
        val frame = OcrFrame.fromJson(fixture)
        assertEquals(1080, frame.width)
        assertEquals("ab12", frame.imageSha256)
        assertEquals("mlkit-text-v2", frame.engine)
        assertEquals(OcrToken("₹110", Box(900, 600, 1000, 648), 12, 3), frame.tokens[0])
        assertEquals(Box(150, 605, 260, 644), frame.tokens[1].box)
        // Inverted corners are re-ordered and a missing line/block means -1.
        assertEquals(OcrToken("ok", Box(0, 5, 10, 20), -1, -1), frame.tokens[2])
    }

    @Test
    fun `round-trips through JSON`() {
        val frame = OcrFrame.fromJson(fixture)
        val json = frame.toJson()
        assertTrue(json, json.contains("\"box\":[900,600,1000,648]"))
        assertTrue(json, json.contains("\"line\":12"))
        assertEquals(frame, OcrFrame.fromJson(json))
    }

    @Test
    fun `rejects malformed frames`() {
        assertThrows(Exception::class.java) { OcrFrame.fromJson("""{"width": 0, "height": 10, "tokens": []}""") }
        assertThrows(Exception::class.java) {
            OcrFrame.fromJson("""{"width": 10, "height": 10, "tokens": [{"text": "a", "box": [1, 2, 3]}]}""")
        }
        assertThrows(Exception::class.java) { OcrFrame.fromJson("""{"width": 10, "height": 10}""") }
    }

    @Test
    fun `box helpers and grid conversion`() {
        val box = Box(108, 240, 216, 480)
        assertEquals(108, box.width)
        assertEquals(240, box.height)
        assertEquals(162.0, box.centerX, 0.0)
        assertEquals(360.0, box.centerY, 0.0)
        val grid = box.toGrid(1080, 2400)
        assertEquals(GridBox(100.0, 100.0, 200.0, 200.0), grid)
        assertEquals(box, grid.toPixels(1080, 2400))
        assertTrue(grid.contains(150.0, 150.0))
        assertFalse(grid.contains(250.0, 150.0))
        assertTrue(grid.expand(60.0).contains(250.0, 150.0))
        assertEquals(0.0, grid.distanceTo(150.0, 150.0), 0.0)
        assertEquals(50.0, grid.distanceTo(250.0, 150.0), 1e-9)
        assertEquals(Box(100, 200, 300, 400), Box(100, 200, 150, 250).union(Box(250, 350, 300, 400)))
        assertEquals(10, Box(0, 0, 10, 10).verticalOverlap(Box(5, 0, 15, 20)))
        assertEquals(0, Box(0, 0, 10, 10).horizontalOverlap(Box(20, 0, 30, 10)))
        assertEquals(Box(0, 0, 30, 35), Box(5, 5, 25, 25).expand(10, 10, 30, 35))
        assertThrows(IllegalArgumentException::class.java) { Box(10, 0, 0, 10) }
    }

    @Test
    fun `model boxes are validated, ordered and clamped`() {
        assertEquals(GridBox(100.0, 200.0, 300.0, 400.0), GridBox.fromList(listOf(300.0, 400.0, 100.0, 200.0)))
        assertEquals(GridBox(0.0, 0.0, 1000.0, 500.0), GridBox.fromList(listOf(-20.0, -5.0, 1400.0, 500.0)))
        assertNull(GridBox.fromList(listOf(1.0, 2.0, 3.0)))
        assertNull(GridBox.fromList(listOf(1.0, 2.0, 3.0, Double.NaN)))
        assertNull(GridBox.fromList(null))
    }

    @Test
    fun `layout groups engine lines and clusters loose tokens`() {
        val frame = OcrFrame(
            1080, 2400,
            listOf(
                OcrToken("₹110", Box(900, 600, 1000, 648), lineId = 5, blockId = 1),
                OcrToken("Auto", Box(150, 604, 260, 644), lineId = 4, blockId = 0),
                OcrToken("min", Box(200, 700, 260, 730)),
                OcrToken("3", Box(150, 700, 170, 730)),
                OcrToken("away", Box(290, 702, 380, 730)),
                OcrToken("   ", Box(0, 0, 5, 5)),
            ),
        )
        val lines = Layout.textLines(frame)
        assertEquals(listOf(listOf(0), listOf(1), listOf(3, 2, 4)), lines.map { it.tokenIds })
        val doc = Layout.document(frame, lines)
        assertEquals("₹110\nAuto\n3 min away", doc.text)
        assertEquals(listOf(3, 2), doc.tokensIn(10, 14))
        assertEquals(5, doc.startOf(1))
        assertNull(doc.startOf(5))
    }
}
