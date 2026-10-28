package app.ridemate.bubble

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import app.ridemate.core.ocr.Box

/** Adds, moves and removes RideMate's overlay windows (production: [WindowManagerOverlays]; tests: a fake). */
interface OverlayWindows {
    /** The context overlay views must be created with. */
    val viewContext: Context

    /** False when Android refused the window (overlay permission revoked, bad token). */
    fun add(view: View, params: WindowManager.LayoutParams): Boolean

    fun update(view: View, params: WindowManager.LayoutParams)

    fun remove(view: View)
}

/**
 * [OverlayWindows] over a window context for `TYPE_APPLICATION_OVERLAY` on the default display (API 30+), so a
 * service can add overlays without a visual context.
 */
class WindowManagerOverlays(context: Context) : OverlayWindows {
    override val viewContext: Context = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        context.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    } else {
        context
    }

    private val windowManager: WindowManager = viewContext.getSystemService(WindowManager::class.java)

    override fun add(view: View, params: WindowManager.LayoutParams): Boolean = try {
        windowManager.addView(view, params)
        true
    } catch (e: WindowManager.BadTokenException) {
        false
    } catch (e: SecurityException) {
        false
    } catch (e: IllegalStateException) {
        false
    }

    override fun update(view: View, params: WindowManager.LayoutParams) {
        if (view.isAttachedToWindow) runCatching { windowManager.updateViewLayout(view, params) }
    }

    override fun remove(view: View) {
        if (view.isAttachedToWindow) runCatching { windowManager.removeViewImmediate(view) }
    }
}

/** Window parameters of RideMate's overlays (§4.2 GuardianBubble / PointerOverlay). */
object OverlayParams {
    /** Highest alpha a non-touchable overlay may have before Android blocks touches through it (API 31+). */
    const val POINTER_MAX_ALPHA = 0.8f

    /**
     * The bubble: an application overlay that never takes key focus (`FLAG_NOT_FOCUSABLE`, so the ride app keeps its
     * keyboard), wraps its content, and is positioned from the top-left at ([x], [y]). [windowTitle] is the window's
     * localised name.
     */
    fun bubble(x: Int, y: Int, windowTitle: CharSequence? = null): WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        this.x = x
        this.y = y
        title = windowTitle ?: "RideMate bubble"
    }

    /**
     * The pointer arrow at [box] (screen pixels): it can't be touched (`FLAG_NOT_TOUCHABLE`, taps pass to the ride
     * app), never takes focus, and is at most [POINTER_MAX_ALPHA] opaque. Its position is absolute: system bars and
     * the display cutout don't shift it (API 30+ would otherwise inset the window by the status bar and could push
     * the arrow onto the very control it points at).
     */
    fun pointer(box: Box, windowTitle: CharSequence? = null): WindowManager.LayoutParams = WindowManager.LayoutParams(
        box.width,
        box.height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = box.l
        y = box.t
        alpha = POINTER_MAX_ALPHA
        title = windowTitle ?: "RideMate pointer"
        layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fitInsetsTypes = 0
            isFitInsetsIgnoringVisibility = true
        }
    }
}
