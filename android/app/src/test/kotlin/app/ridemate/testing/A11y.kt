package app.ridemate.testing

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue

/**
 * Goal 27 checks over everything on screen: every clickable element has a spoken label (text or content
 * description) and a touch target of at least 48 × 48 dp.
 */
object A11y {
    fun spokenLabel(node: SemanticsNode): String {
        val config = node.config
        val description = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(" ")
        val text = config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }
        return "$description $text".trim()
    }

    fun assertClickablesAccessible(rule: ComposeContentTestRule) {
        val nodes = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue("expected something clickable", nodes.isNotEmpty())
        val minPx = with(rule.density) { 48.dp.toPx() } - 0.5f
        for (node in nodes) {
            val label = spokenLabel(node)
            assertTrue("clickable without a spoken label: $node", label.isNotBlank())
            // Layout size (unclipped, so nodes scrolled out of view count too) or the enlarged touch area.
            val touch = node.touchBoundsInRoot
            val width = maxOf(node.size.width.toFloat(), touch.width)
            val height = maxOf(node.size.height.toFloat(), touch.height)
            assertTrue("touch target too small for '$label': ${width}x$height", height >= minPx && width >= minPx)
        }
    }
}
