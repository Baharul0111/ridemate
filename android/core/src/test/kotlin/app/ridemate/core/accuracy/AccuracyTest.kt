package app.ridemate.core.accuracy

import app.ridemate.core.config.FareParsersConfig
import app.ridemate.core.config.SanityBandsConfig
import app.ridemate.core.config.parseFareParsers
import app.ridemate.core.config.parseSanityBands
import app.ridemate.core.fare.FareParser
import app.ridemate.core.fare.TestConfigs
import app.ridemate.core.format.Digits
import app.ridemate.core.format.DistanceText
import app.ridemate.core.gate.ChannelARead
import app.ridemate.core.gate.GateResult
import app.ridemate.core.gate.GatedRow
import app.ridemate.core.gate.SanityBands
import app.ridemate.core.gate.VerificationGate
import app.ridemate.core.model.Provider
import app.ridemate.core.model.QuoteStatus
import app.ridemate.core.model.VehicleClass
import app.ridemate.core.ocr.OcrFrame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * Channel A read, including the adversarial variants and the prompt-injection screens, and requires ZERO wrong-shown
 * values: a VERIFIED price, ETA or driver value that differs from the label. Rows a fixture's `expect.rows` marks
 * UNREADABLE (an attacked row) must not be VERIFIED either. It prints only counts and rates, never fare values (L6).
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

    /** One fixture file and the label rows it says must not be VERIFIED (`expect.rows[i] == "UNREADABLE"`). */
    private data class Fixture<T>(val file: File, val value: T, val mustNotVerify: Set<Int>)

    /**
     * A labels file and where its OCR and Channel A fixtures live. Reads under [attackDirs] are adversarial: they
     * count towards the zero-wrong-shown check but not towards the VERIFIED rate.
     */
    private data class Suite(
        val name: String,
        val labels: String,
        val ocrDirs: List<String>,
        val honestDirs: List<String>,
        val attackDirs: List<String>,
    )

    private data class Tally(var pairs: Int = 0, var labelRows: Int = 0, var verified: Int = 0, var wrong: Int = 0) {
        val rate: Double get() = if (labelRows == 0) 0.0 else verified.toDouble() / labelRows
    }

    private val suites = listOf(
        Suite(
            name = "demo",
            labels = "eval/demo_labels.json",
            // Noisy OCR variants (₹ read as R/Z/%, split prices, merged ETAs) sit in ocr/ or ocr_noisy/.
            ocrDirs = listOf("eval/synthetic/ocr", "eval/synthetic/ocr_noisy", "eval/recorded/ocr"),
            honestDirs = listOf("eval/synthetic/channel_a", "eval/recorded/channel_a"),
            attackDirs = listOf("eval/synthetic/channel_a_adversarial"),
        ),
        Suite(
            name = "injection",
            labels = "eval/injection/labels.json",
            ocrDirs = listOf("eval/injection/ocr"),
            honestDirs = emptyList(),
            // Every read of an injection screen is under attack: the screen text itself carries the instructions.
            attackDirs = listOf("eval/injection/channel_a"),
        ),
    )

    @Test
    fun `zero wrong-shown values on the eval set`() {
        val labelsFile = File(repoRoot, suites.first().labels)
        requireOrSkip(labelsFile.isFile, "no ${labelsFile.path}")
        val parsers = loadParsers()
        val bands = SanityBands(loadBands())
        val honest = Tally()
        val attacked = Tally()
        val problems = mutableListOf<String>()

        for (suite in suites) {
            val file = File(repoRoot, suite.labels)
            if (!file.isFile) continue
            for (screen in loadScreens(file)) {
                val parser = FareParser.forApp(parsers, screen.app, null)
                if (parser == null) {
                    problems += "${suite.name}: no fare parser for ${screen.app.packageName}"
                    continue
                }
                val frames = fixtures(screen.sha, suite.ocrDirs).mapNotNull { f ->
                    runCatching { OcrFrame.fromJson(f.readText()) }.getOrNull()?.let { Fixture(f, it, mustNotVerify(f)) }
                }
                val reads = fixtures(screen.sha, suite.honestDirs).mapNotNull(::readOf).map { it to honest } +
                    fixtures(screen.sha, suite.attackDirs).mapNotNull(::readOf).map { it to attacked }
                for (frame in frames) {
                    for ((read, tally) in reads) {
                        val result = VerificationGate.verify(screen.app, read.value, frame.value, frame.value, parser, bands, screen.tripKm, NOW)
                        tally.pairs++
                        tally.labelRows += screen.rows.size
                        tally.verified += result.verifiedCount
                        val wrong = wrongShown(screen, result, read.mustNotVerify + frame.mustNotVerify)
                        tally.wrong += wrong
                        if (wrong > 0) problems += "${frame.file.name} × ${read.file.name}: $wrong wrong-shown"
                    }
                }
            }
        }
        requireOrSkip(honest.pairs + attacked.pairs > 0, "no OCR + Channel A fixture pairs under eval/")
        println(
            "AccuracyTest: %d honest pairs, VERIFIED rate %.1f%% (%d/%d rows); %d adversarial/injection pairs, %d rows verified; wrong-shown %d"
                .format(
                    honest.pairs, 100 * honest.rate, honest.verified, honest.labelRows, attacked.pairs, attacked.verified,
                    honest.wrong + attacked.wrong,
                ),
        )
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
        assertEquals(0, honest.wrong + attacked.wrong)
    }

    /**
     * Values the gate let through that the label contradicts, plus VERIFIED rows on label rows the fixture marks as
     * attacked. A gated row belongs to a label row of its class whose label contains its OCR label as whole words.
     */
    private fun wrongShown(screen: Screen, result: GateResult, mustNotVerify: Set<Int>): Int {
        var wrong = 0
        for (row in result.rows.filter { it.status == QuoteStatus.VERIFIED }) {
            val candidates = screen.rows.indices.filter { belongsTo(screen.rows[it], row) }
            val match = candidates.firstOrNull { screen.rows[it].price == row.price }
            when {
                match == null -> wrong++
                match in mustNotVerify -> wrong++
                row.eta != null && row.eta != screen.rows[match].eta -> wrong++
            }
        }
        if (result.driver.etaMin != null && result.driver.etaMin != screen.driverEta) wrong++
        if (result.driver.distanceMeters != null && result.driver.distanceMeters != screen.driverMeters) wrong++
        return wrong
    }

    private fun belongsTo(label: LabelRow, row: GatedRow): Boolean {
        if (label.vehicleClass != row.vehicleClass) return false
        val full = words(label.label ?: return false)
        val shown = words(row.displayLabel)
        return shown.isNotEmpty() && " ${full.joinToString(" ")} ".contains(" ${shown.joinToString(" ")} ")
    }

    private fun words(text: String): List<String> =
        Digits.toAscii(text).lowercase().split(NON_WORD).filter { it.isNotEmpty() }

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
    private fun fixtures(sha: String, dirs: List<String>): List<File> = dirs.flatMap { dir ->
        File(repoRoot, dir).listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name.lowercase().startsWith(sha) }
            .orEmpty().sortedBy { it.name }
    }

    private fun readOf(file: File): Fixture<ChannelARead>? {
        val obj = runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: return null
        val read = obj["read"]?.let { ChannelARead.fromJsonOrNull(it.toString()) } ?: return null
        return Fixture(file, read, mustNotVerify(obj))
    }

    private fun mustNotVerify(file: File): Set<Int> =
        runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()?.let(::mustNotVerify).orEmpty()

    private fun mustNotVerify(fixture: JsonObject): Set<Int> {
        val rows = (fixture["expect"] as? JsonObject)?.get("rows") as? JsonArray ?: return emptySet()
        return rows.indices.filterTo(mutableSetOf()) { (rows[it] as? JsonPrimitive)?.contentOrNull == "UNREADABLE" }
    }

    private fun requireOrSkip(condition: Boolean, why: String) {
        if (requireFixtures) assertTrue("fixtures required: $why", condition) else assumeTrue(why, condition)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private companion object {
        const val NOW = 1_793_000_000_000L
        val NON_WORD = Regex("[^\\p{L}\\p{M}\\p{Nd}]+")
    }
}
