package app.ridemate.demo

import app.ridemate.core.config.FareParsersConfig
import app.ridemate.core.model.EtaSource
import app.ridemate.core.model.Provider
import app.ridemate.core.model.Provider.DEMO_KESTREL
import app.ridemate.core.model.Provider.DEMO_PEPPER
import app.ridemate.core.model.Provider.DEMO_TAMARIND
import app.ridemate.core.model.QuoteStatus
import app.ridemate.core.model.VehicleClass
import app.ridemate.core.ocr.Box
import app.ridemate.core.ocr.OcrFrame
import app.ridemate.core.ocr.OcrToken
import app.ridemate.net.ApiResult
import app.ridemate.net.TapBudget
import app.ridemate.testing.FakeAuthBackend
import app.ridemate.testing.FakeClock
import app.ridemate.testing.ProgrammableApiClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One "Read fares" tap on a demo screen through the real gate, with every Channel A outcome. */
class DemoFareReaderTest {
    private val clock = FakeClock(wall = DEMO_TEST_WALL_BASE)
    private val api = ProgrammableApiClient()
    private val ocr = FixtureOcr()
    private val backend = FakeAuthBackend(user = FakeAuthBackend.googleUser())
    private val assets = FileDemoAssets()
    private var aiEnabled = true
    private var parsers: FareParsersConfig? = BundledAssets.fareParsers
    private val reader = DemoFareReader(assets, api, authGateway(backend, clock), clock, { aiEnabled }, { parsers }, { BundledAssets.sanityBands })
    private val script = BundledAssets.script

    private suspend fun read(provider: Provider, screen: String = DemoScreens.FARE_OPTIONS) =
        reader.read(provider, script.provider(provider), screen, "demo-s1", script.trip.tripKm, ocr, TapBudget.start(clock))

    @Test
    fun `a server read of the bundled bytes verifies every demo row, labelled with its source`() = runTest {
        api.answerDemoReads(source = "live")
        val result = read(DEMO_PEPPER)
        assertEquals(ReadSource.LIVE, result.source)
        assertEquals(DemoReadIssue.NONE, result.issue)
        assertNull(result.apiFailure)
        assertFalse(result.recordedOnPhone)
        // Pepper: ऑटो ₹१७२ (Devanagari), Pepper Go ₹264 (struck ₹299 ignored), Prime Sedan.
        val byClass = result.rows.associateBy { it.vehicleClass }
        assertEquals(setOf(VehicleClass.AUTO, VehicleClass.CAB_ECONOMY, VehicleClass.CAB_PREMIUM), byClass.keys)
        assertTrue(result.rows.all { it.status == QuoteStatus.VERIFIED })
        assertEquals(172..172, byClass.getValue(VehicleClass.AUTO).price)
        assertEquals(264..264, byClass.getValue(VehicleClass.CAB_ECONOMY).price)
        assertEquals(3, byClass.getValue(VehicleClass.AUTO).eta)
        assertEquals(EtaSource.VERIFIED, byClass.getValue(VehicleClass.AUTO).etaSource)
        assertEquals(DEMO_TEST_WALL_BASE, result.seenAtMs)

        val sent = api.readRequests.single()
        assertTrue(sent.demo)
        assertEquals(DEMO_PEPPER, sent.provider)
        assertEquals("demo-s1", sent.sessionId)
        assertEquals(DemoImages.sha256(BundledAssets.bytes("demo/pepper/fare_options.png")), DemoImages.sha256(sent.image))
        assertEquals(result.imageSha256, DemoImages.sha256(sent.image))
    }

    @Test
    fun `ranges, struck prices and Indian grouping read correctly on the other two providers`() = runTest {
        api.answerDemoReads(source = "cache")
        val kestrel = read(DEMO_KESTREL).rows.associateBy { it.vehicleClass }
        assertEquals(180..210, kestrel.getValue(VehicleClass.AUTO).price)
        assertEquals(112..112, kestrel.getValue(VehicleClass.BIKE).price)
        val tamarind = read(DEMO_TAMARIND).rows.associateBy { it.vehicleClass }
        assertEquals(1040..1040, tamarind.getValue(VehicleClass.CAB_XL).price)
        assertEquals(176..176, tamarind.getValue(VehicleClass.AUTO).price)
        assertEquals(2, api.readRequests.size)
    }

    @Test
    fun `server down - the recorded read bundled in the app is used and labelled recorded`() = runTest {
        api.readScreen = { ApiResult.Offline }
        assets.bundleAllRecorded()
        val result = read(DEMO_KESTREL)
        assertEquals(ReadSource.RECORDED, result.source)
        assertTrue(result.recordedOnPhone)
        assertEquals(ApiResult.Offline, result.apiFailure)
        assertEquals(DemoReadIssue.NONE, result.issue)
        assertTrue(result.rows.isNotEmpty() && result.rows.all { it.status == QuoteStatus.VERIFIED })
    }

    @Test
    fun `AI paused and nothing recorded - rows honestly say Couldn't read, with no number`() = runTest {
        api.readScreen = { ApiResult.AiPaused("ai_paused", "13:30", "budget") }
        val result = read(DEMO_TAMARIND)
        assertEquals(ReadSource.NONE, result.source)
        assertEquals(DemoReadIssue.AI_UNAVAILABLE, result.issue)
        assertTrue(result.rows.isNotEmpty())
        assertTrue(result.rows.all { it.status == QuoteStatus.UNREADABLE && it.price == null && it.eta == null })
    }

    @Test
    fun `a malformed server body falls back like a failure`() = runTest {
        api.readScreen = {
            ApiResult.Ok(app.ridemate.net.ReadScreenResponse(kotlinx.serialization.json.JsonObject(mapOf("options" to kotlinx.serialization.json.JsonPrimitive("x"))), "live", null, null, null))
        }
        val result = read(DEMO_PEPPER)
        assertEquals(ReadSource.NONE, result.source)
        assertEquals(ApiResult.Http(200, "malformed"), result.apiFailure)
    }

    @Test
    fun `AI switched off - no upload, the recorded read is used`() = runTest {
        aiEnabled = false
        assets.bundleAllRecorded()
        val result = read(DEMO_PEPPER)
        assertTrue(api.readRequests.isEmpty())
        assertEquals(ReadSource.RECORDED, result.source)
        assertEquals(DemoReadIssue.AI_SWITCHED_OFF, result.issue)
        assertTrue(result.rows.all { it.status == QuoteStatus.VERIFIED })

        assets.recorded.clear()
        val none = read(DEMO_PEPPER)
        assertEquals(ReadSource.NONE, none.source)
        assertEquals(DemoReadIssue.AI_SWITCHED_OFF, none.issue)
    }

    @Test
    fun `signed out - signs in anonymously first so the server's demo cache answers`() = runTest {
        val signedOut = FakeAuthBackend(user = null)
        val r = DemoFareReader(assets, api, authGateway(signedOut, clock), clock, { true }, { parsers }, { BundledAssets.sanityBands })
        api.answerDemoReads()
        val result = r.read(DEMO_KESTREL, script.provider(DEMO_KESTREL), DemoScreens.FARE_OPTIONS, "s", script.trip.tripKm, ocr, TapBudget.start(clock))
        assertTrue("signInAnonymous" in signedOut.log)
        assertEquals(ReadSource.RECORDED, result.source)
        assertTrue(result.rows.all { it.status == QuoteStatus.VERIFIED })
    }

    @Test
    fun `signed out and Auth unreachable - the read still completes from the recorded copy`() = runTest {
        val signedOut = FakeAuthBackend(user = null).also { it.nextFailure = app.ridemate.auth.AuthFailure.NETWORK }
        val r = DemoFareReader(assets.bundleAllRecorded(), api, authGateway(signedOut, clock), clock, { true }, { parsers }, { BundledAssets.sanityBands })
        api.readScreen = { ApiResult.AuthNeeded }
        val result = r.read(DEMO_KESTREL, script.provider(DEMO_KESTREL), DemoScreens.FARE_OPTIONS, "s", null, ocr, TapBudget.start(clock))
        assertEquals(ReadSource.RECORDED, result.source)
        assertEquals(ApiResult.AuthNeeded, result.apiFailure)
    }

    @Test
    fun `K3 - a screen without a price or status keyword is never uploaded`() = runTest {
        api.answerDemoReads()
        ocr.override = { OcrFrame(1080, 2400, listOf(OcrToken("hello", Box(10, 10, 100, 40)))) }
        val result = read(DEMO_PEPPER)
        assertEquals(DemoReadIssue.NOT_A_FARE_SCREEN, result.issue)
        assertTrue(api.readRequests.isEmpty())
        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun `OCR failure and missing parser rules are reported, nothing uploaded`() = runTest {
        api.answerDemoReads()
        ocr.failWith = IllegalStateException("mlkit unavailable")
        assertEquals(DemoReadIssue.OCR_FAILED, read(DEMO_PEPPER).issue)
        ocr.failWith = null
        parsers = FareParsersConfig("1.0", emptyMap())
        assertEquals(DemoReadIssue.NO_PARSER, read(DEMO_PEPPER).issue)
        assertTrue(api.readRequests.isEmpty())
    }

    @Test
    fun `a driver screen gives verified driver ETA and distance`() = runTest {
        api.answerDemoReads()
        val result = read(DEMO_PEPPER, DemoScreens.DRIVER_ASSIGNED)
        assertEquals(9, result.driver.etaMin)
        assertEquals(2800, result.driver.distanceMeters)
        assertEquals(EtaSource.VERIFIED, result.driver.etaSource)
    }

    @Test
    fun `recorded files for another image or malformed are ignored`() {
        val sha = DemoImages.sha256(BundledAssets.bytes("demo/pepper/fare_options.png"))
        val text = DemoFixtures.recordedFile(sha)
        assertEquals(DemoFixtures.channelA(sha), RecordedReads.parse(text, sha))
        assertNull(RecordedReads.parse(text, "0".repeat(64)))
        assertNull(RecordedReads.parse("not json", sha))
        assertNull(RecordedReads.parse("{\"image_sha256\": \"$sha\"}", sha))
    }

    @Test
    fun `png size comes from the header`() {
        assertEquals(1080 to 2400, DemoImages.pngSize(BundledAssets.bytes("demo/kestrel/searching.png")))
        assertNull(DemoImages.pngSize(ByteArray(30)))
        assertNull(DemoImages.pngSize(byteArrayOf(1, 2, 3)))
    }
}
