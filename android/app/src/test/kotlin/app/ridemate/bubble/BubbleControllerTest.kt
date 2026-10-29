package app.ridemate.bubble

import android.content.Context
import android.os.SystemClock
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.R
import app.ridemate.core.config.CancelStep
import app.ridemate.core.ocr.Box
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/** Records overlay windows instead of talking to the WindowManager. */
class FakeOverlayWindows(override val viewContext: Context, var accept: Boolean = true) : OverlayWindows {
    val windows = linkedMapOf<View, WindowManager.LayoutParams>()
    var updates = 0

    override fun add(view: View, params: WindowManager.LayoutParams): Boolean {
        if (!accept) return false
        windows[view] = params
        return true
    }

    override fun update(view: View, params: WindowManager.LayoutParams) {
        if (view in windows) {
            windows[view] = params
            updates++
        }
    }

    override fun remove(view: View) {
        windows.remove(view)
    }
}

@RunWith(AndroidJUnit4::class)
class BubbleControllerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val windows = FakeOverlayWindows(context)
    private val taps = mutableListOf<Pair<BubbleAction, String>>()
    private var overlayAllowed = true
    private var tapCounter = 0
    private val controller = BubbleController(
        context = context,
        actions = { action, tapId -> taps += action to tapId },
        windows = windows,
        canDrawOverlays = { overlayAllowed },
        newTapId = { "tap-${++tapCounter}" },
        animationsEnabled = { false },
    )

    private val dp48: Int get() = (48 * context.resources.displayMetrics.density).roundToInt()

    private fun allViews(root: View): List<View> =
        if (root is ViewGroup) listOf(root) + (0 until root.childCount).flatMap { allViews(root.getChildAt(it)) } else listOf(root)

    @Test
    fun `the bubble is an application overlay that never takes focus`() {
        assertTrue(controller.show(BubbleState.comparing()))

        val params = windows.windows.values.single()
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, params.type)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertFalse("the bubble itself must take taps", params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, params.width)
        assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, params.height)
    }

    @Test
    fun `every control has a spoken label and a 48 dp touch target`() {
        controller.show(BubbleState(actions = BubbleAction.entries.toList()))
        val view = controller.bubbleView!!
        view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)

        val head = view.head
        assertEquals(context.getString(R.string.bubble_head_description), head.contentDescription)
        assertTrue(head.measuredHeight >= dp48 && head.measuredWidth >= dp48)
        for (action in BubbleAction.entries) {
            val control = view.controlFor(action) as Button
            assertEquals(context.getString(action.descriptionRes), control.contentDescription)
            assertEquals(context.getString(action.labelRes), control.text.toString())
            assertTrue("$action height", control.measuredHeight >= dp48)
            assertTrue("$action width", control.measuredWidth >= dp48)
            assertTrue(control.isClickable)
        }
        // Nothing clickable is left without a label.
        allViews(view).filter { it.isClickable }.forEach { assertFalse(it.contentDescription.isNullOrBlank()) }
    }

    @Test
    fun `each tap goes to the actions with a fresh tap id`() {
        controller.show(BubbleState.comparing(pasteDrop = true))
        val view = controller.bubbleView!!

        view.controlFor(BubbleAction.READ_FARES)!!.performClick()
        view.controlFor(BubbleAction.PASTE_DROP)!!.performClick()
        view.controlFor(BubbleAction.READ_FARES)!!.performClick()

        assertEquals(
            listOf(BubbleAction.READ_FARES to "tap-1", BubbleAction.PASTE_DROP to "tap-2", BubbleAction.READ_FARES to "tap-3"),
            taps,
        )
    }

    @Test
    fun `without the overlay permission nothing is added and the notification still mirrors every action`() {
        overlayAllowed = false
        val state = BubbleState.comparing(pasteDrop = true)

        assertFalse(controller.show(state))
        assertTrue(windows.windows.isEmpty())
        assertFalse(controller.isShowing)
        val mirrored = NotificationMirror.actionsFor(state, max = 10).map { it.action }
        assertEquals(state.actions.toSet(), mirrored.toSet())
    }

    @Test
    fun `a refused window leaves no bubble behind`() {
        windows.accept = false
        assertFalse(controller.show(BubbleState.comparing()))
        assertFalse(controller.isShowing)
    }

    @Test
    fun `the instruction card shows its steps with bold segments and the fee note`() {
        val card = InstructionCard(
            title = "Cancel in Rapido",
            steps = listOf(CancelStep.parse("Tap **Cancel**"), CancelStep.parse("Choose **Booked another ride**")),
            note = "A fee may apply, up to ₹50",
        )
        controller.show(BubbleState.cancelGuide(card, showMe = true))
        val texts = allViews(controller.bubbleView!!).filterIsInstance<TextView>().filter { it !is Button }

        assertNotNull(texts.firstOrNull { it.text.toString() == "Cancel in Rapido" })
        val step = texts.first { it.text.toString() == "1. Tap Cancel" }
        val spans = (step.text as Spanned).getSpans(0, step.text.length, StyleSpan::class.java)
        assertEquals("Cancel", step.text.subSequence((step.text as Spanned).getSpanStart(spans.single()), (step.text as Spanned).getSpanEnd(spans.single())).toString())
        assertNotNull(texts.firstOrNull { it.text.toString() == "A fee may apply, up to ₹50" })
        assertEquals(listOf(BubbleAction.SHOW_ME, BubbleAction.I_CANCELLED_IT, BubbleAction.STOP), controller.bubbleView!!.shownActions)
    }

    @Test
    fun `while reading, Read fares is disabled and the status is spoken with its kind`() {
        controller.show(BubbleState.comparing())
        controller.setReaderStatus(BubbleStatus("Reading prices…", StatusKind.WORKING), busy = true)
        val view = controller.bubbleView!!

        assertFalse(view.controlFor(BubbleAction.READ_FARES)!!.isEnabled)
        assertTrue(view.controlFor(BubbleAction.BOOKED_HERE)!!.isEnabled)

        controller.setReaderStatus(BubbleStatus("3 prices checked in Uber.", StatusKind.INFO), busy = false)
        assertTrue(view.controlFor(BubbleAction.READ_FARES)!!.isEnabled)
        val status = allViews(view).filterIsInstance<TextView>().first { it.text.toString() == "3 prices checked in Uber." }
        assertEquals("Info: 3 prices checked in Uber.", status.contentDescription)
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, status.accessibilityLiveRegion)
    }

    @Test
    fun `the head is a 56 dp round glyph with a badge, and the actions form a compact icon menu`() {
        controller.show(BubbleState.comparing(pasteDrop = true))
        val view = controller.bubbleView!!
        view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)

        val dp56 = (56 * context.resources.displayMetrics.density).roundToInt()
        assertEquals(dp56, view.head.measuredWidth)
        assertEquals(dp56, view.head.measuredHeight)
        assertEquals(View.GONE, view.badge.visibility)
        // Icon tiles: the icon sits on top of a short word, three to a row.
        val read = view.controlFor(BubbleAction.READ_FARES) as Button
        val booked = view.controlFor(BubbleAction.BOOKED_HERE) as Button
        val stop = view.controlFor(BubbleAction.STOP) as Button
        assertTrue(read.parent === booked.parent && booked.parent === stop.parent)
        assertNotNull(read.compoundDrawablesRelative[1])
        assertNull(read.compoundDrawablesRelative[0])
        // "Paste drop" stays a full-width chip of its own.
        assertFalse(view.controlFor(BubbleAction.PASTE_DROP)!!.parent === read.parent)

        // A problem shows "!" on the head; the badge is decoration (the status line speaks it).
        controller.setReaderStatus(BubbleStatus("No prices found on this screen.", StatusKind.PROBLEM), busy = false)
        assertEquals(View.VISIBLE, view.badge.visibility)
        assertEquals("!", view.badge.text.toString())
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, view.badge.importantForAccessibility)
    }

    @Test
    fun `a guided cancel counts its steps on the head badge`() {
        val card = InstructionCard("Cancel in Rapido", listOf(CancelStep.parse("Tap **Cancel**"), CancelStep.parse("Pick a reason")))
        controller.show(BubbleState.cancelGuide(card, showMe = true))

        assertEquals(View.VISIBLE, controller.bubbleView!!.badge.visibility)
        assertEquals("2", controller.bubbleView!!.badge.text.toString())
    }

    @Test
    fun `a reminder pulse puts the reminder in the spoken status line`() {
        controller.show(BubbleState.watching())

        controller.pulse("Rapido is still booked. Cancel it now to avoid a fee.")

        val status = allViews(controller.bubbleView!!).filterIsInstance<TextView>()
            .first { it.text.toString() == "Rapido is still booked. Cancel it now to avoid a fee." }
        assertEquals("Reminder: Rapido is still booked. Cancel it now to avoid a fee.", status.contentDescription)
        assertEquals(View.VISIBLE, controller.bubbleView!!.panel.visibility)
    }

    @Test
    fun `the bubble and pointer hide for a capture and come back`() = runBlocking {
        controller.show(BubbleState.comparing())
        controller.showPointer(Box(400, 1000, 600, 1100), "Cancel is here")
        val pointer = windows.windows.keys.last()

        controller.hideForCapture()
        assertEquals(View.INVISIBLE, controller.bubbleView!!.visibility)
        assertEquals(View.INVISIBLE, pointer.visibility)

        controller.restoreAfterCapture()
        assertEquals(View.VISIBLE, controller.bubbleView!!.visibility)
        assertEquals(View.VISIBLE, pointer.visibility)
    }

    @Test
    fun `the pointer sits beside the target, can't be touched and is at most 80 percent opaque`() {
        controller.show(BubbleState.cancelGuide(InstructionCard("Cancel", emptyList()), showMe = true))
        val metrics = context.resources.displayMetrics
        val target = Box(metrics.widthPixels / 2, metrics.heightPixels / 2, metrics.widthPixels / 2 + 80, metrics.heightPixels / 2 + 40)

        assertTrue(controller.showPointer(target, "Cancel is here"))

        val params = controller.pointerParams!!
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, params.type)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(params.alpha <= OverlayParams.POINTER_MAX_ALPHA)
        val placed = Box(params.x, params.y, params.x + params.width, params.y + params.height)
        assertFalse("never covers the control", PointerLayout.overlaps(placed, target))
        assertEquals(2, windows.windows.size)

        controller.hidePointer()
        assertNull(controller.pointerParams)
        assertEquals(1, windows.windows.size)
    }

    @Test
    fun `the pointer is placed in absolute screen pixels - system bars and the cutout never shift it onto the target`() {
        controller.show(BubbleState.cancelGuide(InstructionCard("Cancel", emptyList()), showMe = true))
        val metrics = context.resources.displayMetrics
        // A Cancel button near the top: an arrow ABOVE or BELOW it would land on it if the status bar inset shifted it.
        val target = Box(0, 40, metrics.widthPixels, 120)

        assertTrue(controller.showPointer(target, "Cancel is here"))

        val params = controller.pointerParams!!
        assertEquals("no system-bar insets", 0, params.fitInsetsTypes)
        assertTrue(params.isFitInsetsIgnoringVisibility)
        assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS, params.layoutInDisplayCutoutMode)
        val placed = Box(params.x, params.y, params.x + params.width, params.y + params.height)
        assertFalse(PointerLayout.overlaps(placed, target))
    }

    @Test
    fun `screen readers hear localised window names, not debug titles`() {
        controller.show(BubbleState.cancelGuide(InstructionCard("Cancel", emptyList()), showMe = true))
        val metrics = context.resources.displayMetrics
        controller.showPointer(Box(metrics.widthPixels / 2, metrics.heightPixels / 2, metrics.widthPixels / 2 + 80, metrics.heightPixels / 2 + 40), "x")

        assertEquals(context.getString(R.string.bubble_window_title), controller.bubbleParams!!.title.toString())
        assertEquals(context.getString(R.string.bubble_window_title), controller.bubbleView!!.accessibilityPaneTitle.toString())
        assertEquals(context.getString(R.string.bubble_pointer_window_title), controller.pointerParams!!.title.toString())
        val pointer = windows.windows.keys.single { it is PointerView }
        assertEquals(context.getString(R.string.bubble_pointer_window_title), pointer.accessibilityPaneTitle.toString())
    }

    @Test
    fun `dragging the head moves the window and a tap opens or closes the panel`() {
        controller.show(BubbleState.comparing())
        val head = controller.bubbleView!!.head
        val start = controller.bubbleParams!!.x to controller.bubbleParams!!.y
        val t = SystemClock.uptimeMillis()

        head.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 100f, 100f, 0))
        head.dispatchTouchEvent(MotionEvent.obtain(t, t + 16, MotionEvent.ACTION_MOVE, 70f, 80f, 0))
        head.dispatchTouchEvent(MotionEvent.obtain(t, t + 32, MotionEvent.ACTION_MOVE, 40f, 60f, 0))
        head.dispatchTouchEvent(MotionEvent.obtain(t, t + 48, MotionEvent.ACTION_UP, 40f, 60f, 0))

        assertEquals(start.first - 60, controller.bubbleParams!!.x)
        assertEquals(start.second - 40, controller.bubbleParams!!.y)
        assertEquals("a drag is not a tap", View.VISIBLE, controller.bubbleView!!.panel.visibility)

        head.dispatchTouchEvent(MotionEvent.obtain(t, t + 100, MotionEvent.ACTION_DOWN, 40f, 60f, 0))
        head.dispatchTouchEvent(MotionEvent.obtain(t, t + 150, MotionEvent.ACTION_UP, 40f, 60f, 0))
        assertEquals(View.GONE, controller.bubbleView!!.panel.visibility)
        head.performClick()
        assertEquals(View.VISIBLE, controller.bubbleView!!.panel.visibility)
    }

    @Test
    fun `the head always stays on screen`() {
        assertEquals(0 to 0, BubbleController.clampPosition(-50, -20, 56, 1080, 2400))
        assertEquals(1024 to 2344, BubbleController.clampPosition(5000, 9000, 56, 1080, 2400))
        assertEquals(500 to 700, BubbleController.clampPosition(500, 700, 56, 1080, 2400))
    }

    @Test
    fun `hide removes every window`() {
        controller.show(BubbleState.comparing())
        controller.showPointer(Box(400, 1000, 600, 1100), "Cancel is here")

        controller.hide()

        assertTrue(windows.windows.isEmpty())
        assertFalse(controller.isShowing)
    }
}
