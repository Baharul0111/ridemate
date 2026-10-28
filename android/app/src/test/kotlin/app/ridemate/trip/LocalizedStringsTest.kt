package app.ridemate.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Every user-visible string of trip / launch / precheck exists in English and Hindi with the same placeholders. */
class LocalizedStringsTest {
    private val res = listOf(File("src/main/res"), File("app/src/main/res"), File("android/app/src/main/res")).first { it.isDirectory }
    private val placeholder = Regex("%(\\d+)\\$[sd]")

    private fun strings(file: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }.associate { it.getAttribute("name") to it.textContent }
    }

    @Test
    fun `english and hindi files match key for key and placeholder for placeholder`() {
        for (area in listOf("trip", "launch", "precheck")) {
            val en = strings(File(res, "values/strings_$area.xml"))
            val hi = strings(File(res, "values-hi/strings_$area.xml"))
            assertTrue("$area has strings", en.isNotEmpty())
            assertEquals("$area: same keys", en.keys, hi.keys)
            en.forEach { (key, english) ->
                val hindi = hi.getValue(key)
                assertTrue("$key is blank", english.isNotBlank() && hindi.isNotBlank())
                assertEquals("$key placeholders", placeholder.findAll(english).map { it.value }.toSet(), placeholder.findAll(hindi).map { it.value }.toSet())
                assertTrue("$key is namespaced", key.startsWith("${area}_"))
            }
        }
    }
}
