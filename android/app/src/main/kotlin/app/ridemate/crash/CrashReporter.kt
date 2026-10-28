package app.ridemate.crash

/**
 * Crash reporting that never sends text (§4.18 B12): exception messages can carry OCR or notification text, so
 * only exception class names and stack frames leave the phone, and custom keys / breadcrumbs are enum names.
 */

/** Custom-key names. Values are always enum names. Later areas add entries here. */
enum class CrashKey { AUTH_KIND, SESSION_STATE, AI_MODE, CONFIG_LAYER, SCREEN, LAST_SITE }

/** Where a non-fatal or breadcrumb happened. */
enum class CrashSite { APP_START, PURGE, OUTBOX, CONFIG, AUTH, ERASE, NETWORK, UPDATE, CAPTURE, WATCH, GUARDIAN, UI, OTHER }

/** The Crashlytics seam (production: [FirebaseCrashBackend]; tests: a fake). */
interface CrashBackend {
    fun setCollectionEnabled(enabled: Boolean)
    fun recordException(throwable: Throwable)
    fun setCustomKey(key: String, value: String)
    fun setUserId(id: String)
    fun deleteUnsentReports()
    fun log(message: String)
}

/**
 * A throwable that carries only the original's class name (as its message) and stack frames. Causes are
 * sanitized the same way; suppressed exceptions are dropped.
 */
class SanitizedThrowable private constructor(
    val originalClassName: String,
    cause: SanitizedThrowable?,
) : Throwable(originalClassName, cause) {

    override fun fillInStackTrace(): Throwable = this

    override fun toString(): String = originalClassName

    companion object {
        private const val MAX_CAUSE_DEPTH = 8

        fun of(original: Throwable): SanitizedThrowable = build(original, 0, HashSet())

        private fun build(original: Throwable, depth: Int, seen: MutableSet<Throwable>): SanitizedThrowable {
            if (original is SanitizedThrowable) return original
            seen += original
            val next = original.cause
            val cause = if (next != null && next !in seen && depth < MAX_CAUSE_DEPTH) build(next, depth + 1, seen) else null
            return SanitizedThrowable(original.javaClass.name, cause).also { it.stackTrace = original.stackTrace }
        }
    }
}

/**
 * The only crash API the app uses. Every call is safe when Crashlytics is unavailable (no Firebase in tests):
 * failures of the backend itself are swallowed.
 */
class CrashReporter(private val backend: () -> CrashBackend?) {

    private fun withBackend(block: CrashBackend.() -> Unit) {
        try {
            backend()?.block()
        } catch (_: Exception) {
            // Crash reporting must never crash the app.
        }
    }

    /** Collection is on in release builds only (RideMateApp). */
    fun setCollectionEnabled(enabled: Boolean) = withBackend { setCollectionEnabled(enabled) }

    fun recordNonFatal(throwable: Throwable, site: CrashSite) = withBackend {
        setCustomKey(CrashKey.LAST_SITE.name, site.name)
        recordException(SanitizedThrowable.of(throwable))
    }

    fun setKey(key: CrashKey, value: Enum<*>) = withBackend { setCustomKey(key.name, value.name) }

    /** A breadcrumb: the site name only. */
    fun breadcrumb(site: CrashSite) = withBackend { log(site.name) }

    /** Erasure (§4.11): forget the user id and drop reports not yet sent. */
    fun clearUserAndUnsentReports() = withBackend {
        setUserId("")
        deleteUnsentReports()
    }

    /**
     * Wraps the current default uncaught-exception handler (Crashlytics') so fatal crashes are recorded sanitized
     * too. Call after Firebase has initialised. Idempotent.
     */
    fun installUncaughtSanitizer() {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current is SanitizingHandler) return
        Thread.setDefaultUncaughtExceptionHandler(SanitizingHandler(current))
    }

    /** Passes a sanitized copy of every uncaught exception to [delegate]. */
    class SanitizingHandler(val delegate: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            val sanitized = SanitizedThrowable.of(throwable)
            if (delegate != null) delegate.uncaughtException(thread, sanitized) else throw sanitized
        }
    }
}
