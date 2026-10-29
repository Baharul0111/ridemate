package app.ridemate.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.ridemate.ui.DarkRideMateColors
import app.ridemate.ui.LightRideMateColors
import app.ridemate.ui.RideMateColors
import app.ridemate.ui.RmSpacing
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG contrast ratio of two opaque colours. */
object Contrast {
    fun ratio(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}

/** The spec's tokens (docs/design/ui-redesign.md §2) and goal 27's contrast, checked on the JVM. */
class DesignTokensTest {
    @Test
    fun `colour tokens match the spec`() {
        val light = LightRideMateColors
        assertEquals(Color(0xFF0E6B57), light.brand)
        assertEquals(Color(0xFFF2B33D), light.accent)
        assertEquals(Color(0xFF121614), light.ink)
        assertEquals(Color(0xFF5C6661), light.muted)
        assertEquals(Color(0xFFF5F7F6), light.canvas)
        assertEquals(Color(0xFFFFFFFF), light.sheet)
        assertEquals(Color(0xFFE3E8E5), light.line)
        assertEquals(Color(0xFF1E8E4E), light.good)
        assertEquals(Color(0xFFB26A00), light.warn)
        assertEquals(Color(0xFFC62828), light.danger)
        val dark = DarkRideMateColors
        assertEquals(Color(0xFF6FD3B8), dark.brand)
        assertEquals(Color(0xFFF5C25E), dark.accent)
        assertEquals(Color(0xFFE6EAE7), dark.ink)
        assertEquals(Color(0xFFA9B4AF), dark.muted)
        assertEquals(Color(0xFF0E1110), dark.canvas)
        assertEquals(Color(0xFF181C1A), dark.sheet)
        assertEquals(Color(0xFF2A302D), dark.line)
        assertEquals(Color(0xFF5FD08F), dark.good)
        assertEquals(Color(0xFFFFB74D), dark.warn)
        assertEquals(Color(0xFFFF8A80), dark.danger)
    }

    private fun textPairs(c: RideMateColors) = mapOf(
        "ink/canvas" to (c.ink to c.canvas),
        "ink/sheet" to (c.ink to c.sheet),
        "muted/canvas" to (c.muted to c.canvas),
        "muted/sheet" to (c.muted to c.sheet),
        "brand text/sheet" to (c.brand to c.sheet),
        "danger text/sheet" to (c.danger to c.sheet),
        "onBrand/brand" to (c.onBrand to c.brand),
        "onAccent/accent" to (c.onAccent to c.accent),
        "onDanger/danger" to (c.onDanger to c.danger),
        "brand container" to (c.onBrandContainer to c.brandContainer),
        "accent container" to (c.onAccentContainer to c.accentContainer),
        "good container" to (c.onGoodContainer to c.goodContainer),
        "good text/sheet" to (c.onGoodContainer to c.sheet),
        "warn container" to (c.onWarnContainer to c.warnContainer),
        "danger container" to (c.onDangerContainer to c.dangerContainer),
        "neutral container" to (c.onNeutralContainer to c.neutralContainer),
        "demo container" to (c.onDemoContainer to c.demoContainer),
        "muted/neutral container" to (c.muted to c.neutralContainer),
        "ink/brand container" to (c.ink to c.brandContainer),
    )

    @Test
    fun `every text pair meets WCAG AA in both themes`() {
        for ((theme, colors) in listOf("light" to LightRideMateColors, "dark" to DarkRideMateColors)) {
            for ((name, pair) in textPairs(colors)) {
                val ratio = Contrast.ratio(pair.first, pair.second)
                assertTrue("$theme $name contrast ${"%.2f".format(ratio)} < 4.5", ratio >= 4.5)
            }
            // State icons and dashed borders (non-text) need 3:1 against the card.
            for ((name, color) in mapOf("good" to colors.good, "warn" to colors.warn, "brand" to colors.brand)) {
                assertTrue("$theme $name icon contrast", Contrast.ratio(color, colors.sheet) >= 3.0)
            }
        }
    }

    @Test
    fun `spacing follows the 4-pt grid and keeps 48 dp targets`() {
        listOf(RmSpacing.xs, RmSpacing.s, RmSpacing.m, RmSpacing.l, RmSpacing.xl, RmSpacing.xxl, RmSpacing.xxxl, RmSpacing.screen, RmSpacing.card, RmSpacing.cardGap)
            .forEach { assertEquals("$it on the 4-pt grid", 0f, it.value % 4f) }
        assertEquals(20.dp, RmSpacing.screen)
        assertEquals(16.dp, RmSpacing.card)
        assertEquals(12.dp, RmSpacing.cardGap)
        assertTrue(RmSpacing.minTouch >= 48.dp)
        assertEquals(56.dp, RmSpacing.bigButton)
    }
}
