package app.ridemate.compare


import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.R
import app.ridemate.capture.CaptureState
import app.ridemate.capture.ShareReceiverActivity
import app.ridemate.config.ConfigKeys
import app.ridemate.core.model.Provider
import app.ridemate.integration.GuardianCaptureHost
import app.ridemate.integration.GuardianCompareCommands
import app.ridemate.integration.RouteFareLauncher
import app.ridemate.core.model.QuoteStatus
import app.ridemate.core.model.RideState
import app.ridemate.core.model.VehicleClass
import app.ridemate.data.QuoteEntity
import app.ridemate.testing.TestData
import app.ridemate.testing.TestRideMateApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h1600dp")
class FareReadingModuleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = context as TestRideMateApp

    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun `the graph exposes one fare-reading module with idle capture and default bindings`() {
        val module = app.graph.fareReading
        assertSame(module, app.graph.fareReading)
        assertSame(module.capture, module.capture)
        assertSame(module.readFares, module.readFares)
        assertSame(module.coordinator, module.coordinator)
        assertSame(module.showMe, module.showMe)
        assertFalse("Show me needs Google sign-in", module.showMe.isAvailable(Provider.UBER))
        assertEquals(CaptureState.Idle, module.capture.state.value)
        // Bound by the integration: the session service hosts capture, the guardian takes "Booked here", the launcher
        // opens apps with the route; no bubble until the session service shows one.
        assertTrue(module.capture.foregroundHost is GuardianCaptureHost)
        assertNull(module.bubbleHosts.surface)
        assertTrue(module.sessionCommands is GuardianCompareCommands)
        assertTrue(module.appLauncher is RouteFareLauncher)
        assertFalse("ride apps aren't installed here", module.appLauncher.open(Provider.UBER))
        assertFalse(module.appLauncher.open(Provider.DEMO_PEPPER))
        assertNull(module.installedVersions.versionCode(Provider.UBER))
    }

    @Test
    fun `kill switches gate AI reading per app, and demo screens follow demo_enabled`() = runBlocking {
        val config = app.graph.fareReading.config
        assertTrue(config.aiReaderEnabled(Provider.UBER))
        assertFalse("ONDC is never screen-read", config.aiReaderEnabled(Provider.ONDC))

        app.fakes.remoteConfig.fetched[ConfigKeys.readerEnabled(Provider.OLA)] = "false"
        app.fakes.remoteConfig.fetched[ConfigKeys.AI_READER_ENABLED] = "true"
        app.graph.configRepository.refreshInBackground().join()
        assertTrue(config.aiReaderEnabled(Provider.UBER))
        assertFalse(config.aiReaderEnabled(Provider.OLA))

        app.fakes.remoteConfig.fetched[ConfigKeys.AI_READER_ENABLED] = "false"
        app.graph.configRepository.refreshInBackground().join()
        assertFalse(config.aiReaderEnabled(Provider.UBER))
        assertTrue("M9: the demo works when AI is off", config.aiReaderEnabled(Provider.DEMO_KESTREL))
    }

    @Test
    fun `fare parsers come from Remote Config layers, and without any the reader is unavailable`() {
        assertNull(app.graph.fareReading.config.fareParsers())
        assertEquals(15, app.graph.fareReading.config.sanityBands().priceMinInr)
        app.fakes.remoteConfig.active[ConfigKeys.FARE_PARSERS] = FareConfigs.FARE_PARSERS_JSON
        assertNotNull(app.graph.fareReading.config.fareParsers()!!.entryFor(Provider.UBER.packageName, 1))
    }

    @Test
    fun `the comparison activity lists the active session's prices`() {
        runBlocking {
            val db = app.graph.db
            db.sessionDao().insert(TestData.session("s1"))
            db.legDao().upsert(TestData.leg("l1", "s1", Provider.UBER, RideState.QUOTED))
            db.quoteDao().insert(
                QuoteEntity(
                    legId = "l1", vehicleClass = VehicleClass.AUTO, labelText = "Uber Auto", priceMin = 120, priceMax = 120,
                    etaMin = 3, seenAtUtc = app.fakes.clock.wall, status = QuoteStatus.VERIFIED, sourceVersionCode = 1,
                ),
            )
        }
        ActivityScenario.launch(ComparisonActivity::class.java).use {
            // Room emits on its own threads, which Compose's idling doesn't track.
            compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithText("Uber Auto").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Uber Auto").assertIsDisplayed()
            compose.onNodeWithText("₹120").assertIsDisplayed()
            compose.onNodeWithText("cheapest").assertIsDisplayed()
        }
    }

    @Test
    fun `the comparison activity opens the price form for the app asked`() {
        runBlocking {
            app.graph.db.sessionDao().insert(TestData.session("s1"))
            app.graph.db.legDao().upsert(TestData.leg("l1", "s1", Provider.RAPIDO, RideState.OPENED))
        }
        ActivityScenario.launch<ComparisonActivity>(ComparisonActivity.intent(context, "s1", Provider.RAPIDO)).use {
            compose.onNodeWithText(context.getString(R.string.compare_manual_title)).assertIsDisplayed()
        }
    }

    @Test
    fun `a share that isn't an image is refused with what to do`() {
        val intent = Intent(context, ShareReceiverActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, Uri.parse("content://x/1"))
        val activity = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).create().get()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertTrue(activity.isFinishing)
        assertEquals(context.getString(R.string.capture_share_not_image), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `a share from a file path is refused - RideMate never reads its own files for a share`() {
        val intent = Intent(context, ShareReceiverActivity::class.java).setAction(Intent.ACTION_SEND).setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///data/data/in.ridemate.app/databases/ridemate.db"))
        val activity = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).create().get()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertTrue(activity.isFinishing)
        assertEquals(context.getString(R.string.capture_share_not_image), ShadowToast.getTextOfLatestToast())
    }
}
