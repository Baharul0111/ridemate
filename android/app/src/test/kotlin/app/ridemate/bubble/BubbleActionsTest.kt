package app.ridemate.bubble

import android.content.Intent
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.core.ocr.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class BubbleActionsTest {

    @Test
    fun `the notification mirrors the bubble's actions, at most three, always keeping Stop`() {
        val comparing = BubbleState.comparing(pasteDrop = true)
        assertEquals(
            listOf(BubbleAction.READ_FARES, BubbleAction.BOOKED_HERE, BubbleAction.STOP),
            NotificationMirror.actionsFor(comparing).map { it.action },
        )
        val guide = BubbleState.cancelGuide(InstructionCard("Cancel", emptyList()), showMe = true)
        assertEquals(listOf(BubbleAction.SHOW_ME, BubbleAction.DONE, BubbleAction.STOP), NotificationMirror.actionsFor(guide).map { it.action })
        assertEquals(listOf(BubbleAction.STOP), NotificationMirror.actionsFor(BubbleState.watching()).map { it.action })
        // Same labels, descriptions and icons as the bubble.
        NotificationMirror.actionsFor(comparing).forEach {
            assertEquals(it.action.labelRes, it.labelRes)
            assertEquals(it.action.descriptionRes, it.descriptionRes)
            assertEquals(it.action.iconRes, it.iconRes)
        }
        assertTrue(NotificationMirror.actionsFor(comparing, max = 0).isEmpty())
    }

    @Test
    fun `notification intents carry the action and each tap gets its own tap id`() {
        val received = mutableListOf<Pair<BubbleAction, String>>()
        var n = 0
        for (action in BubbleAction.entries) {
            val intent = BubbleActionIntents.put(Intent("app.ridemate.session.ACTION"), action)
            assertEquals(action, BubbleActionIntents.actionOf(intent))
            assertTrue(BubbleActionIntents.dispatch(intent, { a, id -> received += a to id }) { "t${++n}" })
        }
        assertEquals(BubbleAction.entries.toList(), received.map { it.first })
        assertEquals(received.size, received.map { it.second }.toSet().size)

        assertFalse(BubbleActionIntents.dispatch(Intent("x"), { _, _ -> error("no action") }))
        assertNull(BubbleActionIntents.actionOf(Intent("x").putExtra(BubbleActionIntents.EXTRA_ACTION, "DELETE_EVERYTHING")))
        assertNull(BubbleActionIntents.actionOf(null))
    }

    @Test
    fun `overlay params - bubble focus-free, pointer untouchable and translucent`() {
        val bubble = OverlayParams.bubble(10, 20)
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, bubble.type)
        assertTrue(bubble.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertEquals(10, bubble.x)
        assertEquals(20, bubble.y)

        val pointer = OverlayParams.pointer(Box(5, 6, 105, 56))
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, pointer.type)
        assertTrue(pointer.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertTrue(pointer.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(pointer.alpha <= 0.8f)
        assertEquals(100, pointer.width)
        assertEquals(50, pointer.height)
    }

    @Test
    fun `the pointer is always beside its target, on screen, and never covers it`() {
        val random = Random(27)
        val (w, h) = 1080 to 2400
        repeat(2_000) {
            val l = random.nextInt(0, w - 10)
            val t = random.nextInt(0, h - 10)
            val target = Box(l, t, random.nextInt(l + 1, minOf(w, l + 600) + 1), random.nextInt(t + 1, minOf(h, t + 400) + 1))
            val placement = PointerLayout.place(target, w, h, longPx = 144, shortPx = 88, gapPx = 16) ?: return@repeat
            val box = placement.box
            assertFalse("covers $target", PointerLayout.overlaps(box, target))
            assertTrue("off screen $box", box.l >= 0 && box.t >= 0 && box.r <= w && box.b <= h)
        }
    }

    @Test
    fun `the pointer prefers the left side and gives up when nothing fits`() {
        val left = PointerLayout.place(Box(600, 1000, 900, 1100), 1080, 2400, 144, 88, 16)!!
        assertEquals(PointerSide.LEFT, left.side)
        assertEquals(600 - 16, left.box.r)
        val right = PointerLayout.place(Box(10, 1000, 300, 1100), 1080, 2400, 144, 88, 16)!!
        assertEquals(PointerSide.RIGHT, right.side)
        val below = PointerLayout.place(Box(0, 10, 1080, 100), 1080, 2400, 144, 88, 16)!!
        assertEquals(PointerSide.BELOW, below.side)
        assertNull(PointerLayout.place(Box(0, 0, 1080, 2400), 1080, 2400, 144, 88, 16))
    }
}
