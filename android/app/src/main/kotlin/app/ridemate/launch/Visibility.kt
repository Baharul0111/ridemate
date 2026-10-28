package app.ridemate.launch

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Whether RideMate's floating bubble (the overlay window drawn by the bubble area) is visible right now. While it
 * is, Android 15+ lets RideMate start another app's activity from the background; otherwise [AppLauncher] posts a
 * heads-up notification instead. The bubble area binds the real implementation (AppGraph.bubbleVisibility).
 */
interface BubbleVisibility {
    val isShowing: Boolean

    /** No bubble (before the bubble area is bound, or when overlays are not allowed). */
    object Hidden : BubbleVisibility {
        override val isShowing: Boolean = false
    }
}

/** Whether one of RideMate's own activities is in the foreground. */
interface AppForeground {
    val isForeground: Boolean
}

/** [AppForeground] from the process lifecycle: true between the first activity's onStart and the last onStop. */
class ProcessForeground(
    private val owner: () -> LifecycleOwner = { ProcessLifecycleOwner.get() },
) : AppForeground {
    override val isForeground: Boolean
        get() = runCatching { owner().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }.getOrDefault(false)
}
