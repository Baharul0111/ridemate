package app.ridemate.dev

import app.ridemate.core.model.Provider
import app.ridemate.core.model.ScreenType
import app.ridemate.core.model.VehicleClass
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** `labels.json` of the golden set (contracts §6, shared with `eval/demo_labels.json`). */
@Serializable
data class GoldenLabels(
    val schema: String = SCHEMA,
    val screens: List<GoldenScreen> = emptyList(),
) {
    companion object {
        const val SCHEMA = "1.0"
    }
}

/** One labelled screen. Field names are the contracts §6 wire names. */
@Serializable
data class GoldenScreen(
    val file: String,
    val sha256: String,
    /** `uber|ola|rapido|demo_kestrel|demo_pepper|demo_tamarind` ([Provider.wireId]); "" until labelled. */
    val app: String = "",
    /** A [ScreenType] wire name; "" until labelled. */
    @SerialName("screen_type") val screenType: String = "",
    @SerialName("frame_w") val frameW: Int,
    @SerialName("frame_h") val frameH: Int,
    @SerialName("trip_km") val tripKm: Double? = null,
    val rows: List<GoldenRow> = emptyList(),
    @SerialName("driver_eta_min") val driverEtaMin: Int? = null,
    @SerialName("driver_distance_text") val driverDistanceText: String? = null,
    val tags: List<String> = emptyList(),
)

/** One fare row: the true price and ETA the owner read off the screen. */
@Serializable
data class GoldenRow(
    @SerialName("vehicle_class") val vehicleClass: String,
    val label: String,
    @SerialName("price_min") val priceMin: Int?,
    @SerialName("price_max") val priceMax: Int?,
    @SerialName("eta_min") val etaMin: Int?,
    @SerialName("struck_price") val struckPrice: Int? = null,
)

/** Why a screen cannot be exported yet. */
enum class GoldenProblem { NO_APP, NO_SCREEN_TYPE, NO_ROWS, ROW_LABEL_MISSING, ROW_PRICE_MISSING, ROW_PRICE_ORDER, ROW_BAD_CLASS, BAD_NUMBER }

object GoldenWire {
    /** Apps the label form offers (live apps and the demo providers; never ONDC). */
    val APPS: List<Provider> = Provider.LIVE_APPS + Provider.DEMO_APPS

    fun screenType(type: ScreenType): String = ScreenType.serializer().descriptor.getElementName(type.ordinal)

    fun screenTypeOf(wire: String): ScreenType? = ScreenType.entries.firstOrNull { screenType(it) == wire }

    fun vehicleClass(cls: VehicleClass): String = cls.wireId

    /** The `file` of a capture inside the export (and the golden repo). */
    fun fileFor(sha256: String): String = "images/$sha256.png"
}

object GoldenValidator {
    fun problems(screen: GoldenScreen): Set<GoldenProblem> = buildSet {
        if (GoldenWire.APPS.none { it.wireId == screen.app }) add(GoldenProblem.NO_APP)
        val type = GoldenWire.screenTypeOf(screen.screenType)
        if (type == null) add(GoldenProblem.NO_SCREEN_TYPE)
        if (type == ScreenType.FARE_OPTIONS && screen.rows.isEmpty()) add(GoldenProblem.NO_ROWS)
        for (row in screen.rows) {
            if (VehicleClass.fromWireId(row.vehicleClass) == null) add(GoldenProblem.ROW_BAD_CLASS)
            if (row.label.isBlank()) add(GoldenProblem.ROW_LABEL_MISSING)
            if (row.priceMin == null || row.priceMax == null) add(GoldenProblem.ROW_PRICE_MISSING)
            if (row.priceMin != null && row.priceMax != null && row.priceMin > row.priceMax) add(GoldenProblem.ROW_PRICE_ORDER)
            if (listOfNotNull(row.priceMin, row.priceMax, row.etaMin, row.struckPrice).any { it < 0 }) add(GoldenProblem.BAD_NUMBER)
        }
        if ((screen.driverEtaMin ?: 0) < 0 || (screen.tripKm ?: 0.0) < 0.0) add(GoldenProblem.BAD_NUMBER)
    }

    fun isComplete(screen: GoldenScreen): Boolean = problems(screen).isEmpty()
}

/**
 * The owner's golden captures on this phone (app-private `files/golden`): masked PNGs named by their SHA-256 and a
 * draft `labels.json`. Export writes a zip with `labels.json` (contracts §6) and `images/<sha256>.png`. Erase
 * deletes everything ([clear]).
 */
class GoldenStore(private val dir: File) {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
    }
    private val labelsFile get() = File(dir, LABELS)

    @Synchronized
    fun screens(): List<GoldenScreen> = read().screens

    /** Saves a masked capture and adds an (unlabelled, pre-filled) screen for it; a repeat capture is ignored. */
    @Synchronized
    fun add(capture: MaskedCapture): GoldenScreen {
        val sha = sha256(capture.png)
        val existing = read().screens.firstOrNull { it.sha256 == sha }
        if (existing != null) return existing
        dir.mkdirs()
        File(dir, "$sha.png").writeBytes(capture.png)
        val screen = GoldenScreen(
            file = GoldenWire.fileFor(sha),
            sha256 = sha,
            app = capture.provider?.takeIf { it in GoldenWire.APPS }?.wireId.orEmpty(),
            screenType = capture.screenType?.let(GoldenWire::screenType).orEmpty(),
            frameW = capture.width,
            frameH = capture.height,
        )
        write(GoldenLabels(screens = read().screens + screen))
        return screen
    }

    @Synchronized
    fun update(screen: GoldenScreen) {
        write(GoldenLabels(screens = read().screens.map { if (it.sha256 == screen.sha256) screen else it }))
    }

    @Synchronized
    fun remove(sha256: String) {
        File(dir, "$sha256.png").delete()
        write(GoldenLabels(screens = read().screens.filterNot { it.sha256 == sha256 }))
    }

    fun imageFile(sha256: String): File = File(dir, "$sha256.png")

    /** The export document: complete screens whose masked image is still on the phone (labels and images match). */
    @Synchronized
    fun labelsForExport(): GoldenLabels =
        GoldenLabels(screens = read().screens.filter { GoldenValidator.isComplete(it) && imageFile(it.sha256).isFile })

    fun labelsJson(labels: GoldenLabels): String = json.encodeToString(GoldenLabels.serializer(), labels)

    /** Writes the export zip to [out]; returns how many screens it holds (0: nothing written). */
    @Synchronized
    fun exportZip(out: File): Int {
        val labels = labelsForExport()
        if (labels.screens.isEmpty()) return 0
        out.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(out)).use { zip ->
            zip.putNextEntry(ZipEntry(LABELS))
            zip.write(labelsJson(labels).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (screen in labels.screens) {
                val image = imageFile(screen.sha256)
                zip.putNextEntry(ZipEntry(screen.file))
                image.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return labels.screens.size
    }

    @Synchronized
    fun clear() {
        dir.deleteRecursively()
    }

    private fun read(): GoldenLabels =
        if (!labelsFile.exists()) GoldenLabels() else runCatching { json.decodeFromString(GoldenLabels.serializer(), labelsFile.readText()) }.getOrDefault(GoldenLabels())

    private fun write(labels: GoldenLabels) {
        dir.mkdirs()
        val tmp = File(dir, "$LABELS.tmp")
        tmp.writeText(json.encodeToString(GoldenLabels.serializer(), labels))
        if (!tmp.renameTo(labelsFile)) {
            labelsFile.writeText(tmp.readText())
            tmp.delete()
        }
    }

    companion object {
        const val LABELS = "labels.json"

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
