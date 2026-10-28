package app.ridemate.demo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.RideMateApp
import app.ridemate.ondc.OndcPhase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The Android side: bundled assets as the APK ships them, and the AppGraph members of this area. */
@RunWith(AndroidJUnit4::class)
class DemoAndroidTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `AndroidDemoAssets returns the bundled bytes unchanged and the script`() {
        val assets = AndroidDemoAssets(context.assets)
        val script = DemoScript.parse(assets.scriptText())
        assertEquals(BundledAssets.script, script)
        script.providers.values.flatMap { it.screens.values }.forEach { path ->
            assertArrayEquals(path, BundledAssets.bytes(path), assets.bytes(path))
        }
        // The owner's metered Muse Spark Open recordings (eval/recorded/channel_a) are bundled as the offline fallback,
        // one per screen, each bound to its image's SHA-256.
        script.providers.values.flatMap { it.screens.values }.forEach { path ->
            val sha = DemoImages.sha256(assets.bytes(path))
            assertNotNull("recorded read of $path", assets.recordedRead(sha))
        }
        val pepper = assets.recordedRead(DemoImages.sha256(assets.bytes("demo/pepper/fare_options.png")))!!
        assertEquals(3, pepper.options.size)
        assertNull(assets.recordedRead("../../config/fee_rules"))
    }

    @Test
    fun `the graph builds the demo, ONDC and owner messaging lazily, without Firebase`() {
        val graph = RideMateApp.graph(context)
        val demo = graph.demoController
        assertTrue("bound to the capture area's OCR and the guardian", demo.isBound)
        assertEquals(OndcPhase.IDLE, graph.ondcClient.view.value.phase)
        // Signed out: an alert push is not shown and a token is not sent.
        assertFalse(graph.ownerMessaging.onMessage(mapOf("type" to "owner_alert", "alert" to "test"), null, null))
    }
}
