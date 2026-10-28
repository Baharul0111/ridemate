package app.ridemate.demo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.RideMateApp
import app.ridemate.ondc.OndcPhase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        // No recorded reads are bundled until the owner records them (eval/recorded/channel_a is empty).
        val sha = DemoImages.sha256(assets.bytes("demo/pepper/fare_options.png"))
        assertNull(assets.recordedRead(sha))
        assertNull(assets.recordedRead("../../config/fee_rules"))
    }

    @Test
    fun `the graph builds the demo, ONDC and owner messaging lazily, without Firebase`() {
        val graph = RideMateApp.graph(context)
        val demo = graph.demoController
        assertFalse(demo.isBound)
        assertEquals(DemoStartResult.NOT_READY, demo.start())
        assertEquals(DemoUnavailable.NOT_READY, demo.state.value.unavailable)
        assertEquals(OndcPhase.IDLE, graph.ondcClient.view.value.phase)
        // Signed out: an alert push is not shown and a token is not sent.
        assertFalse(graph.ownerMessaging.onMessage(mapOf("type" to "owner_alert", "alert" to "test"), null, null))
    }
}
