package app.ridemate.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.core.ocr.OcrFrame
import app.ridemate.demo.AndroidDemoAssets
import app.ridemate.demo.DemoImages
import app.ridemate.demo.DemoScript
import app.ridemate.e2e.DeviceE2e.context
import app.ridemate.e2e.DeviceE2e.graph
import app.ridemate.e2e.DeviceE2e.log
import app.ridemate.e2e.DeviceE2e.shell
import app.ridemate.integration.OcrEngineDemoOcr
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Records the real ML Kit Text Recognition v2 output (Latin + Devanagari, merged exactly as Channel B sees it in the
 * demo) for every bundled demo screen, in the contracts §6 OCR format (`engine: "mlkit-text-v2"`, `image_sha256` of
 * the exact PNG bytes). The JSON goes to the app's external files dir (`files/recorded_ocr/<sha256>.json`) and a copy
 * to `/data/local/tmp/ridemate-ocr/` (the shell's own folder, which survives the test run's uninstall) for
 * `adb pull` into `eval/recorded/ocr/`. Only counts are logged: no recognised text and no fare values.
 */
@RunWith(AndroidJUnit4::class)
class DemoOcrRecorderTest {

    @Test
    fun recordsMlKitOcrForEveryBundledDemoScreen() = runBlocking {
        val assets = AndroidDemoAssets(context.assets)
        val script = DemoScript.parse(assets.scriptText())
        val paths = script.providers.values.flatMap { it.screens.values }.distinct().sorted()
        assertEquals("every bundled demo PNG", 16, paths.size)

        val ocr = OcrEngineDemoOcr({ graph.fareReading.ocrEngine }, graph.clock, kotlinx.coroutines.Dispatchers.Default)
        val outDir = File(context.getExternalFilesDir(null), "recorded_ocr").apply { deleteRecursively(); mkdirs() }
        shell("rm -rf $SHELL_DIR")
        shell("mkdir -p $SHELL_DIR")
        val recordedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()

        for (path in paths) {
            val bytes = assets.bytes(path)
            val sha = DemoImages.sha256(bytes)
            val size = DemoImages.pngSize(bytes)
            assertNotNull("$path is a PNG", size)
            val (width, height) = size!!
            val frame = ocr.recognize(bytes)
            assertEquals("$path width", width, frame.width)
            assertEquals("$path height", height, frame.height)
            assertTrue("$path: ML Kit found text", frame.tokens.isNotEmpty())
            val recorded = frame.copy(imageSha256 = sha, engine = ENGINE, recordedAt = recordedAt)
            val json = recorded.toJson()
            // Round-trips in the shape the Kotlin AccuracyTest and the Python harness read.
            val back = OcrFrame.fromJson(json)
            assertEquals(sha, back.imageSha256)
            assertEquals(recorded.tokens.size, back.tokens.size)

            File(outDir, "$sha.json").writeText(json)
            DeviceE2e.shellWrite("$SHELL_DIR/$sha.json", json.toByteArray(Charsets.UTF_8))
            val lines = recorded.tokens.map { it.lineId }.distinct().size
            log("OCR_RECORDED path=$path sha=${sha.take(12)} tokens=${recorded.tokens.size} lines=$lines")
        }
        val listed = shell("ls $SHELL_DIR").lines().count { it.endsWith(".json") }
        assertEquals("copies for adb pull", paths.size, listed)
    }

    private companion object {
        const val ENGINE = "mlkit-text-v2"
        const val SHELL_DIR = "/data/local/tmp/ridemate-ocr"
    }
}
