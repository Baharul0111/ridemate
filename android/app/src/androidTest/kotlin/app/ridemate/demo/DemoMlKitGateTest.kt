package app.ridemate.demo

import android.content.Context
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.capture.FrameImage
import app.ridemate.capture.FrameOrigin
import app.ridemate.core.config.parseFareParsers
import app.ridemate.core.config.parseSanityBands
import app.ridemate.core.fare.FareParser
import app.ridemate.core.gate.SanityBands
import app.ridemate.core.gate.VerificationGate
import app.ridemate.core.model.EtaSource
import app.ridemate.core.model.Provider
import app.ridemate.core.model.QuoteStatus
import app.ridemate.ocr.MlKitOcrEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On the phone (goal 19): the production ML Kit reader (bundled Latin + Devanagari) reads every bundled demo screen,
 * and the two-reader check (VerificationGate) over that OCR and the bundled recorded Channel A read verifies the
 * prices and the driver's ETA. The JVM tests use synthetic OCR fixtures; this is the check that the real reader
 * agrees with them: ₹ glyph confusions, the struck-through price, token merging, against the recorded boxes.
 */
@RunWith(AndroidJUnit4::class)
class DemoMlKitGateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val assets = AndroidDemoAssets(context.assets)
    private val script = DemoScript.parse(assets.scriptText())
    private val parsers = parseFareParsers(context.assets.open("config/fare_parsers.json").bufferedReader().use { it.readText() })
    private val bands = SanityBands(parseSanityBands(context.assets.open("config/sanity_bands.json").bufferedReader().use { it.readText() }))
    private val ocr = MlKitOcrEngine()

    @After
    fun tearDown() = ocr.close()

    private fun read(provider: Provider, screen: String) = runBlocking {
        val spec = script.provider(provider)
        val bytes = assets.bytes(spec.screens.getValue(screen))
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val frame = FrameImage(bitmap, 0L, FrameOrigin.DEMO).use { ocr.recognize(it) }
        val recorded = assets.recordedRead(DemoImages.sha256(bytes))
        assertNotNull("$provider $screen: a recorded Channel A read is bundled", recorded)
        val parser = FareParser.forApp(parsers, provider, spec.versionCode)!!
        assertTrue("$provider $screen: ML Kit's text looks like this screen", screen != "fare_options" || parser.looksLikeFareScreen(frame))
        VerificationGate.verify(provider, recorded, frame, frame, parser, bands, tripKm = null, nowMs = 0L) to recorded!!
    }

    @Test
    fun everyFareScreen_twoReadersAgreeOnEveryPrice() {
        for (provider in script.providerOrder) {
            val (gate, recorded) = read(provider, "fare_options")
            val verified = gate.rows.filter { it.status == QuoteStatus.VERIFIED }
            assertTrue("$provider: at least one price verified by ML Kit + Channel A", verified.isNotEmpty())
            // Every price the recorded read gives is confirmed by the on-device reader (nothing left "Couldn't read").
            val readPrices = recorded.options.mapNotNull { o -> o.priceMin?.let { min -> min..(o.priceMax ?: min) } }.toSet()
            assertEquals("$provider: the verified prices are exactly the recorded ones", readPrices, verified.mapNotNull { it.price }.toSet())
            assertTrue("$provider: no unreadable rows", gate.rows.none { it.status == QuoteStatus.UNREADABLE })
        }
    }

    @Test
    fun everyDriverScreen_verifiesTheDriversEta() {
        for (provider in script.providerOrder) {
            val (gate, recorded) = read(provider, "driver_assigned")
            if (recorded.driverEtaMin == null) continue
            assertEquals("$provider: the driver's ETA", recorded.driverEtaMin, gate.driver.etaMin)
            assertEquals(EtaSource.VERIFIED, gate.driver.etaSource)
        }
    }
}
