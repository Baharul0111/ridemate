package app.ridemate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Goal 26: every user-visible string of the app shell exists in English and Hindi, with the same placeholders, so
 * no screen falls back to English (or crashes on a format argument) in Hindi.
 */
class StringParityTest {
    private val areas = listOf("ui", "onboarding", "home", "settings", "dev")
    private val res = File("src/main/res")

    private data class Entry(val text: String, val translatable: Boolean)

    private fun read(file: File): Map<String, Entry> {
        assertTrue("missing $file", file.exists())
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = linkedMapOf<String, Entry>()
        val root = doc.documentElement
        val children = root.childNodes
        for (i in 0 until children.length) {
            val el = children.item(i) as? Element ?: continue
            val name = el.getAttribute("name")
            val translatable = el.getAttribute("translatable") != "false"
            when (el.tagName) {
                "string" -> out[name] = Entry(el.textContent, translatable)
                "plurals" -> {
                    val items = el.getElementsByTagName("item")
                    for (j in 0 until items.length) {
                        val item = items.item(j) as Element
                        out["$name#${item.getAttribute("quantity")}"] = Entry(item.textContent, translatable)
                    }
                }
            }
        }
        return out
    }

    private fun placeholders(text: String): List<String> =
        Regex("%(\\d+\\$)?[sd]").findAll(text).map { it.value }.sorted().toList()

    @Test
    fun `every shell string has a hindi translation with the same placeholders`() {
        for (area in areas) {
            val english = read(File(res, "values/strings_$area.xml"))
            val hindi = read(File(res, "values-hi/strings_$area.xml"))
            assertTrue("no strings in $area", english.isNotEmpty())
            for ((key, entry) in english) {
                if (!entry.translatable) continue
                val base = key.substringBefore('#')
                val hi = hindi[key] ?: hindi.entries.firstOrNull { it.key.substringBefore('#') == base }?.value
                assertTrue("strings_$area: '$key' has no Hindi translation", hi != null)
                assertEquals("strings_$area: placeholders of '$key'", placeholders(entry.text), placeholders(hi!!.text))
                assertTrue("strings_$area: '$key' looks untranslated", hi.text != entry.text || entry.text.none { it.isLetter() })
            }
            val extra = hindi.keys.map { it.substringBefore('#') }.toSet() - english.keys.map { it.substringBefore('#') }.toSet()
            assertTrue("strings_$area: Hindi-only keys $extra", extra.isEmpty())
        }
    }

    @Test
    fun `the erase wording is the exact K1 sentence in both places`() {
        val k1 = "Erase deletes your account, your RideMate data on this phone and on our server, except metadata logs " +
            "(1 year, required by law) and an anonymous usage counter (48 h, abuse prevention)."
        assertEquals(k1, read(File(res, "values/strings_settings.xml")).getValue("set_erase_k1").text)
        assertEquals(k1, read(File(res, "values/strings_onboarding.xml")).getValue("onb_notice_erase_body").text)
        val hi = read(File(res, "values-hi/strings_settings.xml")).getValue("set_erase_k1").text
        assertEquals(hi, read(File(res, "values-hi/strings_onboarding.xml")).getValue("onb_notice_erase_body").text)
    }

    @Test
    fun `the notice says AI processing happens on Modal's servers in the USA`() {
        val en = read(File(res, "values/strings_onboarding.xml"))
        assertEquals("AI processing happens on Modal\\'s servers in the USA.", en.getValue("onb_notice_usa_callout").text)
        assertTrue(en.getValue("onb_notice_where_body").text.startsWith("AI processing happens on Modal\\'s servers in the USA."))
    }

    @Test
    fun `the notice lists the K1 inventory additions`() {
        // K1: destination text sent to Modal by place-pick, and addresses visible in uploaded screenshots.
        val data = read(File(res, "values/strings_onboarding.xml")).getValue("onb_notice_data_body").text
        assertTrue("destination text", "Your destination text is sent to Modal" in data)
        assertTrue("addresses in screenshots", "Addresses visible in those screenshots: masked where detected" in data)
        assertTrue("processed transiently", "processed only while it is read" in data)
    }
}
