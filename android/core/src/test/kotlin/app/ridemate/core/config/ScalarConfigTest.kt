package app.ridemate.core.config

import app.ridemate.core.model.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ScalarConfigTest {
    private val hashA = "a".repeat(64)
    private val hashB = "0123456789abcdef".repeat(4)

    @Test
    fun `defaults follow contracts 2_7`() {
        val d = ScalarConfig.from(emptyMap())
        assertEquals(ScalarConfig(), d)
        assertTrue(d.aiReaderEnabled && d.ondcEnabled && d.demoEnabled)
        assertEquals(1L, d.minSupportedVersion)
        assertEquals(CapProfile.NORMAL, d.capProfile)
        assertNull(d.creditExpiryDate)
        assertEquals(90, d.budgetHorizonDays)
        assertEquals(LocalDate.of(2026, 10, 28), d.budgetHorizonStart)
        assertEquals("", d.apiBaseUrl)
        assertTrue(Provider.LIVE_APPS.all { d.aiReaderEnabledFor(it) })
    }

    @Test
    fun `reads every key`() {
        val c = ScalarConfig.from(
            mapOf(
                ScalarKeys.AI_READER_ENABLED to "true",
                ScalarKeys.READER_ENABLED_OLA to "false",
                ScalarKeys.ONDC_ENABLED to "0",
                ScalarKeys.DEMO_ENABLED to "off",
                ScalarKeys.MODEL_ROUTE to """{"schema":"1.0","read":"modal_spark_open","parse":"modal_spark_open"}""",
                ScalarKeys.LATEST_VERSION to """{"schema":"1.0","version_code":3,"version_name":"1.0.2","url":"https://github.com/Baharul0111/ridemate/releases/latest","sha256":""}""",
                ScalarKeys.MIN_SUPPORTED_VERSION to "2",
                ScalarKeys.CAP_PROFILE to "judging",
                ScalarKeys.CREDIT_EXPIRY_DATE to "2027-01-26",
                ScalarKeys.BUDGET_HORIZON_DAYS to "60",
                ScalarKeys.BUDGET_HORIZON_START to "2026-11-01",
                ScalarKeys.API_BASE_URL to "https://ridemate-api.modal.run",
                ScalarKeys.JUDGE_ALLOWLIST to """{"schema":"1.0","sha256_emails":["$hashA"]}""",
                ScalarKeys.OWNER_EMAILS_SHA256 to """["${hashB.uppercase()}"]""",
            ),
        )
        assertTrue(c.aiReaderEnabledFor(Provider.UBER))
        assertFalse(c.aiReaderEnabledFor(Provider.OLA))
        assertTrue(c.aiReaderEnabledFor(Provider.DEMO_PEPPER))
        assertFalse(c.ondcEnabled || c.demoEnabled)
        assertEquals(ModelRoute("1.0", "modal_spark_open", "modal_spark_open"), c.modelRoute)
        assertEquals(3L, c.latestVersion!!.versionCode)
        assertEquals(2L, c.minSupportedVersion)
        assertEquals(CapProfile.JUDGING, c.capProfile)
        assertEquals(LocalDate.of(2027, 1, 26), c.creditExpiryDate)
        assertEquals(60, c.budgetHorizonDays)
        assertEquals(LocalDate.of(2026, 11, 1), c.budgetHorizonStart)
        assertEquals("https://ridemate-api.modal.run", c.apiBaseUrl)
        assertEquals(setOf(hashA), c.judgeAllowlistSha256)
        assertEquals(setOf(hashB), c.ownerEmailsSha256)
        assertFalse(ScalarConfig.from(mapOf(ScalarKeys.AI_READER_ENABLED to "false")).aiReaderEnabledFor(Provider.UBER))
    }

    @Test
    fun `each malformed key falls back on its own`() {
        val c = ScalarConfig.from(
            mapOf(
                ScalarKeys.AI_READER_ENABLED to "maybe",
                ScalarKeys.MODEL_ROUTE to """{"schema":"2.0","read":"x","parse":"y"}""",
                ScalarKeys.LATEST_VERSION to """{"schema":"1.0","version_code":3,"version_name":"x","url":"http://insecure"}""",
                ScalarKeys.MIN_SUPPORTED_VERSION to "-4",
                ScalarKeys.CAP_PROFILE to "turbo",
                ScalarKeys.CREDIT_EXPIRY_DATE to "unknown",
                ScalarKeys.BUDGET_HORIZON_DAYS to "lots",
                ScalarKeys.API_BASE_URL to "http://plain-http.example",
                ScalarKeys.JUDGE_ALLOWLIST to """{"schema":"1.0","sha256_emails":["not-a-hash"]}""",
                ScalarKeys.OWNER_EMAILS_SHA256 to "{",
                ScalarKeys.DEMO_ENABLED to "false",
            ),
        )
        assertEquals(ScalarConfig(demoEnabled = false), c)
    }
}
