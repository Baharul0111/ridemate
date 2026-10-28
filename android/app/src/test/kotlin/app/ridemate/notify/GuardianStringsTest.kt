package app.ridemate.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every guardian string exists in English and Hindi with the same placeholders (goal 26). */
class GuardianStringsTest {
    private fun strings(path: String): Map<String, String> {
        val file = listOf(File(path), File("app/$path")).first { it.exists() }
        return Regex("<string name=\"([^\"]+)\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private val en = strings("src/main/res/values/strings_guardian.xml")
    private val hi = strings("src/main/res/values-hi/strings_guardian.xml")
    private val placeholder = Regex("%\\d\\$[sd]")

    @Test
    fun `English and Hindi have the same keys`() {
        assertTrue(en.size > 150)
        assertEquals(en.keys, hi.keys)
    }

    @Test
    fun `placeholders match in both languages`() {
        for ((key, value) in en) {
            assertEquals(key, placeholder.findAll(value).map { it.value }.toSortedSet(), placeholder.findAll(hi.getValue(key)).map { it.value }.toSortedSet())
        }
    }

    @Test
    fun `Hindi strings are translated, not copied (brand names aside)`() {
        val brandOnly = setOf("guardian_provider_uber", "guardian_provider_ola", "guardian_provider_rapido", "guardian_list_separator", "guardian_line", "guardian_action_desc_with_app")
        val devanagari = Regex("[\\u0900-\\u097F]")
        val untranslated = hi.filter { (key, value) -> key !in brandOnly && !devanagari.containsMatchIn(value) }.keys
        assertTrue(untranslated.toString(), untranslated.isEmpty())
    }
}
