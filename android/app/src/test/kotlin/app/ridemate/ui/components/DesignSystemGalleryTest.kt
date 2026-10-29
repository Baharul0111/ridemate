package app.ridemate.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import app.ridemate.testing.A11y
import app.ridemate.ui.RideMateTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner

/**
 * Goal 27 over the whole design system, in light and dark, at normal text size and at 200 %: every component
 * renders, keeps its stable test tag, and every clickable has a spoken label and a ≥ 48 dp touch target.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class DesignSystemGalleryTest(private val dark: Boolean, private val fontScale: Float) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}, fontScale={1}")
        fun params(): List<Array<Any>> = listOf(
            arrayOf(false, 1f),
            arrayOf(true, 1f),
            arrayOf(false, 2f),
            arrayOf(true, 2f),
        )
    }

    @get:Rule
    val rule = createComposeRule()

    private val clicks = mutableListOf<String>()

    private fun show() = rule.setContent {
        RideMateTheme(darkTheme = dark) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ComponentGallery(clicks)
            }
        }
    }

    @Test
    fun `every component renders with its stable tag`() {
        show()
        for (tag in GalleryTags) {
            val found = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
            assertTrue("missing '$tag' (dark=$dark, fontScale=$fontScale)", found.isNotEmpty())
        }
        // One pill per ride-state group, one hero per illustration.
        rule.onAllNodesWithTag(RmTags.STATUS_PILL, useUnmergedTree = true).assertCountEquals(RideStateGroup.entries.size + Samples.legs.size)
        rule.onAllNodesWithTag(RmTags.HERO, useUnmergedTree = true).assertCountEquals(Hero.entries.size)
    }

    @Test
    fun `every clickable has a spoken label and a 48 dp target`() {
        show()
        A11y.assertClickablesAccessible(rule)
    }

    @Test
    fun `components answer taps`() {
        show()
        rule.onNodeWithContentDescription("Search where to go").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Say where to go").performScrollTo().performClick()
        rule.onNodeWithTag(RmTags.fareCard("rapido", "auto")).performScrollTo().performClick()
        rule.onNodeWithContentDescription("Refresh Ola price").performScrollTo().performClick()
        rule.onNodeWithTag(RmTags.FARE_TYPE_PRICE).performScrollTo().performClick()
        rule.onNodeWithTag(RmTags.WHY_BUTTON).performScrollTo().performClick()
        rule.onNodeWithTag(RmTags.BANNER_ACTION).performScrollTo().performClick()
        assertEquals(listOf("where", "mic", "fare", "refresh", "type", "why", "retry"), clicks)
    }
}
