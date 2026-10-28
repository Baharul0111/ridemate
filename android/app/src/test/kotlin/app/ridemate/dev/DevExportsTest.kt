package app.ridemate.dev

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DevExportsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `exports leave only through the share sheet, read-only, from RideMate's own provider`() {
        val exports = DevExports(context)
        val file = exports.writeText("ridemate-recorder-test.json", "{\"posts\":[]}")
        assertTrue("app-private cache", file.canonicalPath.startsWith(context.cacheDir.canonicalPath))

        val chooser = exports.shareIntent(file, "application/json", "Share export")
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/json", send.type)
        assertTrue("read-only grant", send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val uri = send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEquals("content", uri.scheme)
        assertEquals(DevExports.authority(context), uri.authority)
        assertFalse("no file path leaks into the uri", file.absolutePath in uri.toString())

        exports.clear()
        assertFalse(file.exists())
    }
}
