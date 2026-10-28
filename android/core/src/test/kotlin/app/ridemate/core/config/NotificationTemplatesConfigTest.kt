package app.ridemate.core.config

import app.ridemate.core.replay.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTemplatesConfigTest {

    @Test
    fun `parses the contract example verbatim`() {
        val raw = """
            {"schema": "1.0",
             "apps": {
              "com.rapido.passenger": [
               {"min_version_code": 0,
                "templates": [
                  {"id": "rapido.assigned.v1", "event": "ASSIGNED",
                   "pattern": "(?i)captain (?P<name>[a-z ]+) is (?:on the way|arriving)(?:.*?(?P<eta>\\d+)\\s*min)?",
                   "channel_id": null, "ongoing": null}
                ],
                "redacted": [{"event": "ASSIGNED", "channel_id": "ride_status", "ongoing": true, "category": null}],
                "cancelled_keywords": ["cancelled", "रद्द"],
                "ongoing_channel_ids": ["ride_status"]}
              ]}}
        """.trimIndent()
        val config = NotificationTemplatesConfig.parse(raw)
        val entry = config.entryFor("com.rapido.passenger", 1234)!!.entry
        assertEquals(listOf("rapido.assigned.v1"), entry.templates.map { it.id })
        assertEquals(setOf("name", "eta"), entry.templates.single().groupNames)
        assertEquals(NotificationEvent.ASSIGNED, entry.redacted.single().spec.event)
        assertEquals("com.rapido.passenger.redacted.0", entry.redacted.single().id)
        assertEquals(listOf("cancelled", "रद्द"), entry.cancelledKeywords)
        assertEquals(listOf("ride_status"), entry.ongoingChannelIds)
        assertTrue(config.rejected.isEmpty())
    }

    @Test
    fun `patterns RE2J cannot compile are rejected and the rest of the payload kept`() {
        val raw = """
            {"schema": "1.2", "apps": {"com.ubercab": [{"min_version_code": 0, "templates": [
              {"id": "lookahead", "event": "ASSIGNED", "pattern": "driver(?= is near)"},
              {"id": "backref", "event": "ASSIGNED", "pattern": "(a)\\1"},
              {"id": "unbalanced", "event": "ASSIGNED", "pattern": "(oops"},
              {"id": "good", "event": "SEARCHING", "pattern": "(?i)finding your ride"},
              {"id": "future-event", "event": "OTP_SHOWN", "pattern": "otp"},
              {"id": "good", "event": "ENDED", "pattern": "duplicate id"}
            ]}]}}
        """.trimIndent()
        val config = NotificationTemplatesConfig.parse(raw)
        val entry = config.entryFor("com.ubercab", 1)!!.entry
        assertEquals(listOf("good"), entry.templates.map { it.id })
        val reasons = config.rejected.associate { it.templateId to it.reason }
        assertEquals(TemplateRejectReason.BAD_PATTERN, reasons["lookahead"])
        assertEquals(TemplateRejectReason.BAD_PATTERN, reasons["backref"])
        assertEquals(TemplateRejectReason.BAD_PATTERN, reasons["unbalanced"])
        assertEquals(TemplateRejectReason.MALFORMED, reasons["future-event"])
        assertTrue(config.rejected.any { it.templateId == "good" && it.reason == TemplateRejectReason.DUPLICATE_ID })
    }

    @Test
    fun `an unsupported schema major or a malformed payload throws so the layered resolver falls back`() {
        assertThrows(IllegalArgumentException::class.java) { NotificationTemplatesConfig.parse("""{"schema": "2.0", "apps": {}}""") }
        assertThrows(Exception::class.java) { NotificationTemplatesConfig.parse("""{"schema": "1.0", "apps": {"x": 3}}""") }
        val layered = resolveLayered(
            remote = """{"schema": "2.0", "apps": {}}""",
            lastGood = "not json",
            bundled = Fixtures.TEMPLATES_JSON,
            parse = NotificationTemplatesConfig::parse,
        )
        assertEquals(Layer.BUNDLED, layered.source)
        assertTrue(layered.value.apps.containsKey(Fixtures.RAPIDO))
    }

    @Test
    fun `newest entry at or below the installed version wins, unknown versions are flagged as fallback`() {
        val raw = """
            {"schema": "1.0", "apps": {"com.olacabs.customer": [
              {"min_version_code": 0, "templates": [{"id": "old", "event": "SEARCHING", "pattern": "searching"}]},
              {"min_version_code": 500, "max_known_version_code": 600,
               "templates": [{"id": "new", "event": "SEARCHING", "pattern": "looking"}]}
            ]}}
        """.trimIndent()
        val config = NotificationTemplatesConfig.parse(raw)
        fun pick(version: Long?) = config.entryFor("com.olacabs.customer", version)!!
        assertEquals("old", pick(100).entry.templates.single().id)
        assertFalse(pick(100).isFallback)
        assertEquals("new", pick(550).entry.templates.single().id)
        assertFalse(pick(550).isFallback)
        assertEquals("new", pick(900).entry.templates.single().id)
        assertTrue(pick(900).isFallback)
        assertTrue(pick(null).isFallback)
        assertNull(config.entryFor("com.example.other", 1))
    }

    @Test
    fun `the replay fixtures compile without rejections`() {
        assertTrue(Fixtures.templates.rejected.toString(), Fixtures.templates.rejected.isEmpty())
        assertEquals(3, Fixtures.templates.apps.size)
        assertTrue(Fixtures.templatesWithoutStructural.entryFor(Fixtures.RAPIDO, 1)!!.entry.redacted.isEmpty())
    }
}
