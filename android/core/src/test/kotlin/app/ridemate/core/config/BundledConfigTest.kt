package app.ridemate.core.config

import app.ridemate.core.guardian.FeeRules
import app.ridemate.core.model.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The APK-bundled Remote Config defaults are the last layer of [resolveLayered], parsed without a fallback: every one
 * the repo ships must parse with this build's models. Skipped when the repo has none yet, unless
 * `-PrequireFixtures=true`.
 */
class BundledConfigTest {
    private val repoRoot = File(System.getProperty("ridemate.repoRoot") ?: ".")
    private val requireFixtures = System.getProperty("ridemate.requireFixtures").toBoolean()
    private val dirs = listOf("config/remote_config", "android/app/src/main/assets/config").map { File(repoRoot, it) }

    private val payloads: Map<String, (String) -> Any> = mapOf(
        "fare_parsers" to ::parseFareParsers,
        "sanity_bands" to ::parseSanityBands,
        "cancel_cards" to ::parseCancelCards,
        "fee_rules" to FeeRules::parse,
        "launch_templates" to ::parseLaunchTemplates,
    )

    @Test
    fun `every bundled fare-side default parses`() {
        val found = dirs.flatMap { dir -> payloads.keys.map { File(dir, "$it.json") } }.filter { it.isFile }
        if (requireFixtures) assertTrue("no bundled config under $dirs", found.isNotEmpty()) else assumeTrue(found.isNotEmpty())
        for (file in found) {
            val raw = file.readText()
            val parsed = runCatching { payloads.getValue(file.nameWithoutExtension)(raw) }
            assertTrue("${file.path}: ${parsed.exceptionOrNull()}", parsed.isSuccess)
            // The same text must also win as the remote layer, so a published template equal to the default is used.
            assertEquals(file.path, Layer.REMOTE, resolveLayered(raw, null, raw, payloads.getValue(file.nameWithoutExtension)).source)
        }
        for (dir in dirs) {
            val parsers = File(dir, "fare_parsers.json").takeIf { it.isFile }?.let { parseFareParsers(it.readText()) } ?: continue
            for (app in Provider.LIVE_APPS + Provider.DEMO_APPS) {
                assertNotNull("${dir.path}: no fare parser for ${app.packageName}", parsers.entryFor(app.packageName, null))
            }
        }
    }

    @Test
    fun `bundled JSON scalar values parse`() {
        val values = dirs.flatMap { dir -> JSON_SCALARS.map { File(dir, "$it.json") } }.filter { it.isFile }
            .associate { it.nameWithoutExtension to it.readText() }
        if (requireFixtures) assertTrue("no bundled scalars under $dirs", values.isNotEmpty()) else assumeTrue(values.isNotEmpty())
        val config = ScalarConfig.from(values)
        if (ScalarKeys.MODEL_ROUTE in values) assertNotNull("model_route", config.modelRoute)
        if (ScalarKeys.LATEST_VERSION in values) assertNotNull("latest_version", config.latestVersion)
    }

    private companion object {
        val JSON_SCALARS = listOf(ScalarKeys.MODEL_ROUTE, ScalarKeys.LATEST_VERSION)
    }
}
