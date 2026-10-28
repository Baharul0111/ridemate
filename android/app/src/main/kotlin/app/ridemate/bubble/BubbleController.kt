package app.ridemate.bubble

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import app.ridemate.R
import app.ridemate.capture.CaptureOverlayControl
import app.ridemate.core.ocr.Box
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * RideMate's live bubble as other areas see it. A3's session service owns the instance ([BubbleController]); the
 * fare reader and capture reach it through [BubbleHost]. Call from the main thread.
 */
interface BubbleSurface : CaptureOverlayControl {
    val isShowing: Boolean

    /** Adds the bubble window; false without the overlay permission (the notification actions remain). */
    fun show(state: BubbleState): Boolean

    fun update(state: BubbleState)

    /**
     * A transient status line (fare reader outcomes, reminders), shown over [BubbleState.status] until replaced;
     * null clears it.
     */
    fun setReaderStatus(status: BubbleStatus?, busy: Boolean = false)

    /** Removes the bubble and any pointer. */
    fun hide()

    /**
     * Reminder pulse (§4.7): a short scale animation (skipped when animations are off); a non-blank [announcement]
     * becomes the status line (kind REMINDER, a polite live region, so screen readers speak it).
     */
    fun pulse(announcement: String?)

    /** Draws the arrow beside [target] (screen pixels); false when it can't be placed without covering it. */
    fun showPointer(target: Box, description: String): Boolean

    fun hidePointer()
}

/**
 * The session foreground service that hosts the bubble (A3's RideSessionService implements this and attaches itself
 * to [BubbleHostRegistry] while it runs).
 */
interface BubbleHost {
    /** The live bubble, or null while none is shown. */
    val bubble: BubbleSurface?
}

/** Where the running [BubbleHost] is found (one per process, in `AppGraph.fareReading`). */
class BubbleHostRegistry {
    @Volatile
    var host: BubbleHost? = null

    val surface: BubbleSurface? get() = host?.bubble
}

/**
 * GuardianBubble + PointerOverlay (§4.2): a draggable `TYPE_APPLICATION_OVERLAY` window that never takes focus, and
 * an arrow window that can't be touched and is at most 80% opaque. Created by the session service with its own
 * context; every button tap goes to [actions] with a fresh tap id.
 */
class BubbleController(
    context: Context,
    private val actions: BubbleActions,
    private val windows: OverlayWindows = WindowManagerOverlays(context),
    private val canDrawOverlays: () -> Boolean = { Settings.canDrawOverlays(context) },
    private val newTapId: () -> String = TapIds::newTapId,
    private val animationsEnabled: () -> Boolean = { ValueAnimator.areAnimatorsEnabled() },
) : BubbleSurface {

    private val viewContext: Context = windows.viewContext
    private var view: BubbleView? = null
    private var params: WindowManager.LayoutParams? = null
    private var pointer: PointerView? = null
    private var state: BubbleState = BubbleState()
    private var userExpanded: Boolean? = null
    private var readerStatus: BubbleStatus? = null
    private var readerBusy: Boolean = false
    private var hiddenForCapture = false

    override val isShowing: Boolean get() = view != null

    /** The window parameters in use (tests). */
    val bubbleParams: WindowManager.LayoutParams? get() = params

    /** The pointer's window parameters while one is shown (tests). */
    var pointerParams: WindowManager.LayoutParams? = null
        private set

    /** The bubble's view while shown (tests, accessibility checks). */
    val bubbleView: BubbleView? get() = view

    override fun show(state: BubbleState): Boolean {
        if (view != null) {
            update(state)
            return true
        }
        if (!canDrawOverlays()) return false
        this.state = state
        val v = BubbleView(
            viewContext,
            onAction = { action -> actions.onBubbleAction(action, newTapId()) },
            onHeadTap = {
                userExpanded = !(userExpanded ?: this.state.expanded)
                render()
            },
            onDrag = ::moveBy,
            onDragEnd = ::clampIntoScreen,
        )
        val metrics = viewContext.resources.displayMetrics
        val p = OverlayParams.bubble(
            x = metrics.widthPixels - dp(BubbleView.HEAD_DP + EDGE_MARGIN_DP),
            y = (metrics.heightPixels * START_HEIGHT_FRACTION).roundToInt(),
            windowTitle = viewContext.getString(R.string.bubble_window_title),
        )
        // Screen readers announce the window by this localised name when it appears.
        v.accessibilityPaneTitle = viewContext.getString(R.string.bubble_window_title)
        view = v
        params = p
        render()
        if (!windows.add(v, p)) {
            view = null
            params = null
            return false
        }
        clampIntoScreen()
        return true
    }

    override fun update(state: BubbleState) {
        val newInfo = state.card != this.state.card || state.status != this.state.status
        this.state = state
        if (newInfo && (state.card != null || state.status != null)) userExpanded = true
        render()
    }

    override fun setReaderStatus(status: BubbleStatus?, busy: Boolean) {
        if (status != null && status != readerStatus) userExpanded = true
        readerStatus = status
        readerBusy = busy
        render()
    }

    override fun hide() {
        hidePointer()
        view?.let { windows.remove(it) }
        view = null
        params = null
        hiddenForCapture = false
    }

    override fun pulse(announcement: String?) {
        val v = view ?: return
        if (!announcement.isNullOrBlank()) {
            // Shown in the status line, a polite live region, so TalkBack reads it out.
            readerStatus = BubbleStatus(announcement, StatusKind.REMINDER)
            readerBusy = false
            userExpanded = true
            render()
        }
        if (animationsEnabled()) {
            ObjectAnimator.ofPropertyValuesHolder(
                v.head,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, PULSE_SCALE, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, PULSE_SCALE, 1f),
            ).apply {
                duration = PULSE_MS
                repeatCount = PULSE_REPEATS
                start()
            }
        }
    }

    override fun showPointer(target: Box, description: String): Boolean {
        hidePointer()
        if (!canDrawOverlays()) return false
        val metrics = viewContext.resources.displayMetrics
        val placement = PointerLayout.place(
            target = target,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            longPx = dp(POINTER_LONG_DP),
            shortPx = dp(POINTER_SHORT_DP),
            gapPx = dp(POINTER_GAP_DP),
        ) ?: return false
        val p = OverlayParams.pointer(placement.box, viewContext.getString(R.string.bubble_pointer_window_title))
        val pv = PointerView(viewContext, placement.side, ContextCompat.getColor(viewContext, R.color.bubble_pointer)).apply {
            contentDescription = description
            accessibilityPaneTitle = viewContext.getString(R.string.bubble_pointer_window_title)
            if (hiddenForCapture) visibility = View.INVISIBLE
        }
        if (!windows.add(pv, p)) return false
        pointer = pv
        pointerParams = p
        return true
    }

    override fun hidePointer() {
        pointer?.let { windows.remove(it) }
        pointer = null
        pointerParams = null
    }

    override suspend fun hideForCapture() {
        if (view == null && pointer == null) return
        hiddenForCapture = true
        view?.visibility = View.INVISIBLE
        pointer?.visibility = View.INVISIBLE
        delay(CAPTURE_SETTLE_MS)
    }

    override fun restoreAfterCapture() {
        if (!hiddenForCapture) return
        hiddenForCapture = false
        view?.visibility = View.VISIBLE
        pointer?.visibility = View.VISIBLE
    }

    private fun render() {
        val v = view ?: return
        v.render(
            state = state,
            status = readerStatus ?: state.status,
            busy = readerBusy || state.busy,
            expanded = userExpanded ?: state.expanded,
        )
        params?.let { windows.update(v, it) }
    }

    private fun moveBy(dx: Int, dy: Int) {
        val p = params ?: return
        val v = view ?: return
        p.x += dx
        p.y += dy
        windows.update(v, p)
    }

    /** Keeps the head fully on screen after a drag. */
    private fun clampIntoScreen() {
        val p = params ?: return
        val v = view ?: return
        val metrics = viewContext.resources.displayMetrics
        val (x, y) = clampPosition(p.x, p.y, dp(BubbleView.HEAD_DP), metrics.widthPixels, metrics.heightPixels)
        if (x != p.x || y != p.y) {
            p.x = x
            p.y = y
            windows.update(v, p)
        }
    }

    private fun dp(value: Int): Int = (value * viewContext.resources.displayMetrics.density).roundToInt()

    companion object {
        /** Time for the hidden overlay to leave the screen before a frame is grabbed. */
        const val CAPTURE_SETTLE_MS: Long = 150
        const val PULSE_MS: Long = 500
        const val PULSE_REPEATS = 2
        const val PULSE_SCALE = 1.25f
        const val EDGE_MARGIN_DP = 8
        const val START_HEIGHT_FRACTION = 0.35
        const val POINTER_LONG_DP = 72
        const val POINTER_SHORT_DP = 44
        const val POINTER_GAP_DP = 8

        /** Clamps the window origin so a [headPx]-sized head stays inside a `screenWidth × screenHeight` screen. */
        fun clampPosition(x: Int, y: Int, headPx: Int, screenWidth: Int, screenHeight: Int): Pair<Int, Int> =
            x.coerceIn(0, (screenWidth - headPx).coerceAtLeast(0)) to y.coerceIn(0, (screenHeight - headPx).coerceAtLeast(0))
    }
}
