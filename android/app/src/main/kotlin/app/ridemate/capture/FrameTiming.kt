package app.ridemate.capture

/**
 * I6 (§4.9): a frame is processed only within 2 s of a user tap (or when it comes from DemoSource). Every check uses
 * `SystemClock.elapsedRealtime()` instants, so changing the phone's clock cannot open the window.
 */
object FrameTiming {
    /** The window after a tap in which a frame may be taken and processing may start. */
    const val MAX_AFTER_TAP_MS: Long = 2_000

    enum class Verdict {
        OK,

        /** The frame was taken before the tap (a stale frame from an earlier moment). */
        TAKEN_BEFORE_TAP,

        /** The frame was taken more than 2 s after the tap. */
        TAKEN_TOO_LATE,

        /** Processing would start more than 2 s after the tap. */
        PROCESSED_TOO_LATE,

        /** Processing would start before the frame existed (clock misuse). */
        PROCESSED_BEFORE_TAKEN,
    }

    /** Whether [frameAtElapsedMs] may be processed at [processAtElapsedMs] for a tap at [tapAtElapsedMs]. */
    fun check(tapAtElapsedMs: Long, frameAtElapsedMs: Long, processAtElapsedMs: Long, origin: FrameOrigin = FrameOrigin.CAPTURE): Verdict {
        if (origin == FrameOrigin.DEMO) return Verdict.OK
        return when {
            frameAtElapsedMs < tapAtElapsedMs -> Verdict.TAKEN_BEFORE_TAP
            frameAtElapsedMs - tapAtElapsedMs > MAX_AFTER_TAP_MS -> Verdict.TAKEN_TOO_LATE
            processAtElapsedMs < frameAtElapsedMs -> Verdict.PROCESSED_BEFORE_TAKEN
            processAtElapsedMs - tapAtElapsedMs > MAX_AFTER_TAP_MS -> Verdict.PROCESSED_TOO_LATE
            else -> Verdict.OK
        }
    }

    fun allows(tapAtElapsedMs: Long, frameAtElapsedMs: Long, processAtElapsedMs: Long, origin: FrameOrigin = FrameOrigin.CAPTURE): Boolean =
        check(tapAtElapsedMs, frameAtElapsedMs, processAtElapsedMs, origin) == Verdict.OK
}
