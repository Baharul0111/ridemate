package app.ridemate.e2e

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import app.ridemate.AppGraph
import app.ridemate.MainActivity
import app.ridemate.RideMateApp
import org.junit.Assert.fail

/**
 * Shared plumbing for the on-phone end-to-end tests. They run inside RideMate's own process (instrumentation of
 * `in.ridemate.app`), drive the real UI with UiAutomator and read the real [AppGraph] state to assert on it.
 *
 * Shell commands go through [android.app.UiAutomation.executeShellCommand] and only ever touch RideMate itself
 * (`in.ridemate.app`): its runtime permissions, its app-ops, its notification listener and its battery exemption.
 * No other app is launched, queried or automated.
 */
object DeviceE2e {
    const val TAG = "RideMateE2E"
    const val PKG = "in.ridemate.app"
    const val LISTENER = "$PKG/app.ridemate.watch.RideWatchService"

    val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val device: UiDevice get() = UiDevice.getInstance(instrumentation)
    val context: Context get() = instrumentation.targetContext
    val graph: AppGraph get() = RideMateApp.graph(context)

    fun log(message: String) {
        Log.i(TAG, message)
    }

    fun str(id: Int, vararg args: Any): String = context.getString(id, *args)

    /** Runs a shell command as the shell user and returns its stdout. Never used on any package but RideMate. */
    fun shell(command: String): String {
        require(command.contains(PKG) || OWN_TMP.matches(command)) { "shell is for RideMate only: $command" }
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        val out = ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
        log("shell: $command -> ${out.trim().take(200)}")
        return out
    }

    /** Writes [bytes] to [path] as the shell user (a place adb can pull from after the app is uninstalled). */
    fun shellWrite(path: String, bytes: ByteArray) {
        require(path.startsWith("/data/local/tmp/")) { "shellWrite only writes under /data/local/tmp" }
        val (out, input) = instrumentation.uiAutomation.executeShellCommandRw("dd of=$path")
        ParcelFileDescriptor.AutoCloseOutputStream(input).use { it.write(bytes) }
        ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes() }
    }

    /** Polls [value] until it is non-null; fails with [what] after [timeoutMs]. */
    fun <T : Any> waitFor(what: String, timeoutMs: Long, pollMs: Long = 250, value: () -> T?): T {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            value()?.let { return it }
            if (SystemClock.uptimeMillis() > deadline) fail("timed out after $timeoutMs ms waiting for $what")
            SystemClock.sleep(pollMs)
        }
    }

    fun waitUntil(what: String, timeoutMs: Long, pollMs: Long = 250, condition: () -> Boolean) {
        waitFor(what, timeoutMs, pollMs) { if (condition()) Unit else null }
    }

    // ------------------------------------------------------------------ the activity

    /** Starts RideMate's launcher activity as a user would (a fresh task), with the screen kept on while it runs. */
    fun launchMain(clearTask: Boolean = true) {
        device.wakeUp()
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or (if (clearTask) Intent.FLAG_ACTIVITY_CLEAR_TASK else 0)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(flags))
        waitFor("RideMate in front", 20_000) { has(By.pkg(PKG).depth(0)).takeIf { it } }
        keepScreenOn()
    }

    /** Leaves RideMate for the launcher and comes back, as a user does after changing a setting (onResume re-checks). */
    fun leaveAndReturn() {
        device.pressHome()
        device.waitForIdle()
        SystemClock.sleep(500)
        launchMain(clearTask = false)
    }

    fun resumedActivity(): Activity? {
        var activity: Activity? = null
        instrumentation.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
        }
        return activity
    }

    private fun keepScreenOn() {
        val activity = waitFor("a resumed RideMate activity", 10_000) { resumedActivity() }
        instrumentation.runOnMainSync { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    fun finishActivities() {
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            for (stage in listOf(Stage.RESUMED, Stage.PAUSED, Stage.STARTED, Stage.STOPPED, Stage.CREATED)) {
                monitor.getActivitiesInStage(stage).forEach { it.finish() }
            }
        }
    }

    // ------------------------------------------------------------------ finding and tapping

    /**
     * Compose answers accessibility queries but only sends change events while an accessibility *service* (TalkBack)
     * is enabled; the test's UiAutomation connection is not one, so its node cache goes stale between screens. Every
     * lookup therefore drops the cache first (API 34+; this phone is API 37).
     */
    fun find(selector: BySelector): UiObject2? {
        clearCache()
        return device.findObject(selector)
    }

    fun has(selector: BySelector): Boolean {
        clearCache()
        return device.hasObject(selector)
    }

    private fun clearCache() {
        if (android.os.Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
    }

    fun waitObject(selector: BySelector, what: String, timeoutMs: Long = 20_000): UiObject2 {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            find(selector)?.let { return it }
            SystemClock.sleep(200)
        }
        dumpHierarchy("missing")
        fail("timed out after $timeoutMs ms waiting for $what")
        error("unreachable")
    }

    /** A Compose button: the clickable node whose child text is [label] (the title above may carry the same words). */
    fun button(label: String): BySelector = By.clickable(true).hasDescendant(By.text(label))

    fun waitText(text: String, timeoutMs: Long = 20_000): UiObject2 = waitObject(By.text(text), "text \"$text\"", timeoutMs)

    fun hasText(text: String): Boolean = has(By.text(text))

    /**
     * Brings [selector] into the part of the screen a finger can reach (below the status bar and heads-up area, above
     * any bottom sheet RideMate shows) by scrolling the content like a user, then taps it. [obstructionTop] gives the
     * top edge of anything drawn over the bottom of the content (the guardian's sheet), or null when nothing is.
     */
    fun scrollAndTap(
        selector: BySelector,
        what: String,
        timeoutMs: Long = 30_000,
        obstructionTop: () -> Int? = { null },
        tap: Boolean = true,
        confirm: (() -> Boolean)? = null,
    ) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var downSwipes = 0
        var direction = 1 // 1: reveal content further down, -1: further up
        while (SystemClock.uptimeMillis() < deadline) {
            val safeTop = (device.displayHeight * SAFE_TOP_FRACTION).toInt()
            val safeBottom = (obstructionTop() ?: (device.displayHeight - navBarPx())) - TAP_MARGIN_PX
            val target = find(selector)
            if (target != null) {
                val bounds: Rect = target.visibleBounds
                val y = bounds.centerY()
                if (bounds.height() > MIN_HEIGHT_PX && y in safeTop..safeBottom) {
                    // A tap while the content still flings only stops the fling: wait until it has settled.
                    SystemClock.sleep(SETTLE_MS)
                    val settled = find(selector)?.visibleBounds
                    if (settled != bounds) continue
                    if (!tap) return
                    log("tap: $what at ${bounds.centerX()},$y")
                    device.click(bounds.centerX(), y)
                    if (confirm == null) return
                    val until = SystemClock.uptimeMillis() + CONFIRM_MS
                    while (SystemClock.uptimeMillis() < until) {
                        if (confirm()) return
                        SystemClock.sleep(100)
                    }
                    log("tap on $what had no effect yet; trying again")
                    continue
                }
                // Visible but in an unsafe band: move it towards the middle of the safe band.
                scrollContent(if (y > safeBottom || bounds.height() <= MIN_HEIGHT_PX) 1 else -1, safeTop, safeBottom)
            } else {
                scrollContent(direction, safeTop, safeBottom)
                if (direction == 1 && ++downSwipes >= MAX_DOWN_SWIPES) direction = -1
            }
        }
        dumpHierarchy("unreachable")
        fail("could not bring \"$what\" into reach within $timeoutMs ms")
    }

    /** Saves RideMate's window hierarchy to /data/local/tmp for a failure report (never logged). */
    fun dumpHierarchy(name: String) {
        runCatching {
            val out = java.io.ByteArrayOutputStream()
            device.dumpWindowHierarchy(out)
            shellWrite("/data/local/tmp/ridemate-ui-$name.xml", out.toByteArray())
        }
    }

    /** A user's swipe in the reachable band: [direction] 1 shows content further down, -1 further up. */
    fun scrollContent(direction: Int, safeTop: Int, safeBottom: Int) {
        val x = device.displayWidth / 2
        val span = ((safeBottom - safeTop) * 0.55).toInt().coerceAtLeast(120)
        val mid = (safeTop + safeBottom) / 2
        val (from, to) = if (direction > 0) (mid + span / 2) to (mid - span / 2) else (mid - span / 2) to (mid + span / 2)
        device.swipe(x, from, x, to, SWIPE_STEPS)
        SystemClock.sleep(350)
    }

    /** Scrolls up to the top of the content (a few long swipes). */
    fun scrollToTop() {
        repeat(6) { scrollContent(-1, (device.displayHeight * SAFE_TOP_FRACTION).toInt(), device.displayHeight - navBarPx()) }
    }

    private fun navBarPx(): Int = (48 * context.resources.displayMetrics.density).toInt()

    /** The only commands not naming RideMate: housekeeping of the tests' own folders under /data/local/tmp. */
    private val OWN_TMP = Regex("^(?:mkdir -p|rm -rf|ls) /data/local/tmp/ridemate[-\\w.]*$")

    private const val SAFE_TOP_FRACTION = 0.16
    private const val TAP_MARGIN_PX = 24
    private const val MIN_HEIGHT_PX = 20
    private const val MAX_DOWN_SWIPES = 12
    private const val SWIPE_STEPS = 30
    private const val SETTLE_MS = 400L
    private const val CONFIRM_MS = 4_000L
}
