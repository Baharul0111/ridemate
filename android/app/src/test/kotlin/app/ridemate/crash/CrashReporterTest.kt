package app.ridemate.crash

import app.ridemate.testing.FakeCrashBackend
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReporterTest {
    private val backend = FakeCrashBackend()
    private val reporter = CrashReporter { backend }
    private val originalHandler = Thread.getDefaultUncaughtExceptionHandler()

    @After
    fun restore() = Thread.setDefaultUncaughtExceptionHandler(originalHandler)

    @Test
    fun `non-fatals keep class names and stacks but never messages`() {
        val cause = IllegalArgumentException("OTP 4321 for +91 98765 43210")
        val error = IllegalStateException("fare ₹110 captain Ravi", cause)

        reporter.recordNonFatal(error, CrashSite.WATCH)

        val sent = backend.recorded.single()
        assertTrue(sent is SanitizedThrowable)
        assertEquals("java.lang.IllegalStateException", sent.message)
        assertEquals("java.lang.IllegalStateException", sent.toString())
        assertEquals(error.stackTrace.toList(), sent.stackTrace.toList())
        assertEquals("java.lang.IllegalArgumentException", sent.cause!!.message)
        assertEquals(cause.stackTrace.toList(), sent.cause!!.stackTrace.toList())
        assertEquals("WATCH", backend.keys["LAST_SITE"])
        val everything = backend.events.joinToString() + sent.message + sent.cause!!.message
        assertTrue(!everything.contains("4321") && !everything.contains("Ravi") && !everything.contains("110"))
    }

    @Test
    fun `cyclic and deep cause chains are bounded`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        val sanitized = SanitizedThrowable.of(b)
        assertEquals("java.lang.RuntimeException", sanitized.cause!!.message)
        assertNull(sanitized.cause!!.cause)

        var deep: Throwable = Exception("root")
        repeat(20) { deep = Exception("level $it", deep) }
        var depth = 0
        var t: Throwable? = SanitizedThrowable.of(deep)
        while (t != null) {
            depth++
            t = t.cause
        }
        assertEquals(9, depth)
        val already = SanitizedThrowable.of(a)
        assertSame(already, SanitizedThrowable.of(already))
    }

    @Test
    fun `custom keys and breadcrumbs are enum names only`() {
        reporter.setKey(CrashKey.SESSION_STATE, CrashSite.GUARDIAN)
        reporter.breadcrumb(CrashSite.CAPTURE)
        assertEquals("GUARDIAN", backend.keys["SESSION_STATE"])
        assertTrue(backend.events.contains("log:CAPTURE"))
    }

    @Test
    fun `collection switch and erase clearing`() {
        reporter.setCollectionEnabled(false)
        assertEquals(false, backend.collectionEnabled)
        reporter.clearUserAndUnsentReports()
        assertEquals(listOf("collection:false", "userId:", "deleteUnsent"), backend.events)
    }

    @Test
    fun `a broken or missing backend never crashes the app`() {
        backend.failAll = true
        reporter.recordNonFatal(RuntimeException("x"), CrashSite.OTHER)
        reporter.setKey(CrashKey.AI_MODE, CrashSite.OTHER)
        reporter.clearUserAndUnsentReports()
        CrashReporter { null }.recordNonFatal(RuntimeException("x"), CrashSite.OTHER)
        CrashReporter { error("no firebase") }.breadcrumb(CrashSite.OTHER)
    }

    @Test
    fun `uncaught exceptions reach the previous handler sanitized`() {
        val received = mutableListOf<Throwable>()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> received += e }
        reporter.installUncaughtSanitizer()
        reporter.installUncaughtSanitizer()
        val installed = Thread.getDefaultUncaughtExceptionHandler()
        assertTrue(installed is CrashReporter.SanitizingHandler)
        assertTrue((installed as CrashReporter.SanitizingHandler).delegate !is CrashReporter.SanitizingHandler)

        installed.uncaughtException(Thread.currentThread(), IllegalStateException("plate AS01 AB 1234"))

        assertEquals("java.lang.IllegalStateException", received.single().message)
    }
}
