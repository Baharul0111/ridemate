package app.ridemate.core.accuracy

import app.ridemate.core.config.FareParsersConfig
import app.ridemate.core.config.SanityBandsConfig
import app.ridemate.core.config.parseFareParsers
import app.ridemate.core.config.parseSanityBands
import app.ridemate.core.fare.FareParser
import app.ridemate.core.fare.TestConfigs
import app.ridemate.core.format.DistanceText
import app.ridemate.core.gate.ChannelARead
import app.ridemate.core.gate.GateResult
import app.ridemate.core.gate.SanityBands
import app.ridemate.core.gate.VerificationGate
import app.ridemate.core.gate.labelTextsMatch
import app.ridemate.core.model.Provider
import app.ridemate.core.model.QuoteStatus
import app.ridemate.core.model.VehicleClass
import app.ridemate.core.ocr.OcrFrame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs Channel B + the gate over the labelled eval screens (contracts §6) against every recorded and synthetic
 * Channel A read, including the adversarial variants, and requires ZERO wrong-shown values: a VERIFIED price, ETA or
 * driver value that differs from the label. It prints only counts and rates, never fare values (L6).
 *
 * Without fixtures the test is skipped, unless `-PrequireFixtures=true` (system property ridemate.requireFixtures).
 */
class AccuracyTest {
    private val repoRoot = File(System.getProperty("ridemate.repoRoot") ?: ".")
    private val requireFixtures = System.getProperty("ridemate.requireFixtures").toBoolean()
    private val json = Json { ignoreUnknownKeys = true }

    private data class LabelRow(val vehicleClass: VehicleClass?, val label: String?, val price: IntRange?, val eta: Int?)

    private data class Screen(
        val sha: String,
        val app: Provider,
        val tripKm: Double?,
        val rows: List<LabelRow>,
        val driverEta: Int?,
        val driverMeters: Int?,
    )

    private data class Tally(var screens: Int = 0, var labelRows: Int = 0, var verified: Int = 0, var wrong: Int = 0) {
        val rate: Double get() = if (labelRows == 0) 0.0 else verified.toDouble() / labelRows
    }

    @Test
    fun `zero wrong-shown values on the eval set`() {
        val labelsFile = File(repoRoot, "eval/demo_labels.json")
        requireOrSkip(labelsFile.isFile, "no ${labelsFile.path}")
        val screens = loadScreens(labelsFile)
        val parsers = loadParsers()
        val bands = SanityBands(loadBands())
        val honest = Tally()
        val adversarial = Tally()
        val problems = mutableListOf<String>()

        for (screen in screens) {
            val parser = FareParser.forApp(parsers, screen.app, null) ?: continue
            val frames = fixtures(screen.sha, "eval/synthetic/ocr", "eval/recorded/ocr").mapNotNull { file ->
                runCatching { OcrFrame.fromJson(file.readText()) }.getOrNull()?.let { file to it }
            }
            val honestReads = fixtures(screen.sha, "eval/synthetic/channel_a", "eval/recorded/channel_a").mapNotNull(::readOf)
            val attacks = fixtures(screen.sha, "eval/synthetic/channel_a_adversarial").mapNotNull(::readOf)
            for ((ocrFile, frame) in frames) {
                for ((aFile, read) in honestReads + attacks) {
                    val tally = if (aFile in attacks.map { it.first }) adversarial else honest
                    val result = VerificationGate.verify(screen.app, read, frame, frame, parser, bands, screen.tripKm, NOW)
                    tally.screens++
                    tally.labelRows += screen.rows.size
                    tally.verified += result.verifiedCount
                    val wrong = wrongShown(screen, result)
                    tally.wrong += wrong
                    if (wrong > 0) problems += "${ocrFile.name} × ${aFile.name}: $wrong wrong-shown"
                }
            }
        }
        val evaluated = honest.screens + adversarial.screens
        requireOrSkip(evaluated > 0, "no OCR + Channel A fixture pairs under eval/")
        println(
            "AccuracyTest: ${honest.screens} honest pairs, VERIFIED rate %.1f%% (%d/%d rows); %d adversarial pairs, %d verified; wrong-shown %d"
                .format(100 * honest.rate, honest.verified, honest.labelRows, adversarial.screens, adversarial.verified, honest.wrong + adversarial.wrong),
        )
        assertEquals(problems.joinToString("\n"), 0, honest.wrong + adversarial.wrong)
    }

    /** Values the gate let through that the label contradicts. */
    private fun wrongShown(screen: Screen, result: GateResult): Int {
        var wrong = 0
        for (row in result.rows.filter { it.status == QuoteStatus.VERIFIED }) {
            val candidates = screen.rows.filter { it.vehicleClass == row.vehicleClass && labelTextsMatch(it.label, row.displayLabel) }
            val match = candidates.firstOrNull { it.price == row.price }
            if (match == null) wrong++ else if (row.eta != null && row.eta != match.eta) wrong++
        }
        if (result.driver.etaMin != null && result.driver.etaMin != screen.driverEta) wrong++
        if (result.driver.distanceMeters != null && result.driver.distanceMeters != screen.driverMeters) wrong++
        return wrong
    }

    private fun loadScreens(file: File): List<Screen> {
        val root = json.parseToJsonElement(file.readText()).jsonObject
        return root["screens"]!!.jsonArray.mapNotNull { element ->
            val s = element.jsonObject
            val app = Provider.fromWireId(s.string("app") ?: return@mapNotNull null) ?: return@mapNotNull null
            Screen(
                sha = s.string("sha256")?.lowercase() ?: return@mapNotNull null,
                app = app,
                tripKm = s["trip_km"]?.jsonPrimitive?.doubleOrNull,
                rows = (s["rows"] as? JsonArray).orEmpty().map { r ->
                    val o = r.jsonObject
                    val min = o.int("price_min")
                    val max = o.int("price_max") ?: min
                    LabelRow(
                        vehicleClass = VehicleClass.fromWireId(o.string("vehicle_class")),
                        label = o.string("label"),
                        price = if (min != null && max != null) min..max else null,
                        eta = o.int("eta_min"),
                    )
                },
                driverEta = s.int("driver_eta_min"),
                driverMeters = DistanceText.parseMeters(s.string("driver_distance_text")),
            )
        }
    }

    /** The repo's bundled parsers when present, else the generic test parsers (one entry per app). */
    private fun loadParsers(): FareParsersConfig = listOf(
        "config/remote_config/fare_parsers.json",
        "android/app/src/main/assets/config/fare_parsers.json",
    ).map { File(repoRoot, it) }.firstOrNull { it.isFile }?.let { parseFareParsers(it.readText()) } ?: TestConfigs.fareParsers

    private fun loadBands(): SanityBandsConfig = listOf(
        "config/remote_config/sanity_bands.json",
        "android/app/src/main/assets/config/sanity_bands.json",
    ).map { File(repoRoot, it) }.firstOrNull { it.isFile }?.let { parseSanityBands(it.readText()) } ?: SanityBandsConfig.DEFAULT

    /** `<dir>/<sha>.json` plus variants named `<sha>.<variant>.json` or `<sha>_<variant>.json`. */
    private fun fixtures(sha: String, vararg dirs: String): List<File> = dirs.flatMap { dir ->
        File(repoRoot, dir).listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name.lowercase().startsWith(sha) }
            .orEmpty().sortedBy { it.name }
    }

    private fun readOf(file: File): Pair<File, ChannelARead>? {
        val obj = runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: return null
        val read = obj["read"]?.let { ChannelARead.fromJsonOrNull(it.toString()) } ?: return null
        return file to read
    }

    private fun requireOrSkip(condition: Boolean, why: String) {
        if (requireFixtures) assertTrue("fixtures required: $why", condition) else assumeTrue(why, condition)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull

    private companion object {
        const val NOW = 1_793_000_000_000L
    }
}
