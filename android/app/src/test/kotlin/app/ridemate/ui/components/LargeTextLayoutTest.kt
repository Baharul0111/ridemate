package app.ridemate.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.ridemate.core.model.Provider
import app.ridemate.core.model.VehicleClass
import app.ridemate.ui.RideMateTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * §6 "font scaling to 200 % without clipping" on a small common phone (360 dp wide): no fixed-copy text of the
 * design system is cut off or ellipsized, at normal size and at 200 %. Long user data (place names) may ellipsize
 * by design and is not part of this page.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h4000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real font metrics, so text widths are true

class LargeTextLayoutTest(private val fontScale: Float) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "fontScale={0}")
        fun params(): List<Array<Any>> = listOf(arrayOf(1f), arrayOf(1.3f), arrayOf(2f))
    }

    @get:Rule
    val rule = createComposeRule()

    @OptIn(ExperimentalLayoutApi::class)
    private fun show() = rule.setContent {
        RideMateTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    WhereToBar(onClick = {}, onMic = {})
                    RouteStack(pickup = "Current location", drop = "LGBI Airport", onDropClick = {})
                    RmSectionTitle("Auto", leading = { VehicleIcon(VehicleClass.AUTO, contentDescription = null) })
                    FareCard(Samples.cheapest, onClick = {})
                    FareCard(Samples.stale, onRefresh = {})
                    FareCard(Samples.unreadable, onTypePrice = {})
                    FareCard(Samples.typed)
                    FareCard(Samples.network)
                    FareCard(Samples.demo)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ProviderChoiceTile(Provider.UBER, true, {}, Modifier.weight(1f))
                        VehicleChoiceTile(VehicleClass.AUTO, false, {}, Modifier.weight(1f))
                        VehicleChoiceTile(VehicleClass.CAB_XL, false, {}, Modifier.weight(1f))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        RideStateGroup.entries.forEach { StatusPill(it) }
                    }
                    LegTimeline(Samples.legs + TimelineLeg(Provider.DEMO_KESTREL, app.ridemate.core.model.RideState.SEARCHING))
                    BigPrimaryButton("Compare 3 apps", {})
                    QuietButton("Keep all", {}, fillWidth = true)
                    InlineBanner("Offline", tone = RmTone.WARN, icon = Icons.Rounded.WifiOff, actionLabel = "Retry", onAction = {})
                    StepDots(count = 6, current = 2)
                    DemoTag()
                }
            }
        }
    }

    private fun layout(node: SemanticsNode): TextLayoutResult {
        val out = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(out)
        return out.single()
    }

    /**
     * Cut off: more lines than allowed (ellipsized or dropped), taller than its box, or — for unwrapped text such as
     * a price — narrower than its natural width. (`hasVisualOverflow` is not used: the semantics layout reports any
     * text narrower than its max width as overflowing.)
     */
    private fun isClipped(node: SemanticsNode, r: TextLayoutResult): Boolean =
        r.multiParagraph.didExceedMaxLines ||
            (0 until r.lineCount).any { r.isLineEllipsized(it) } ||
            node.size.height < r.multiParagraph.height - 0.5f ||
            (!r.layoutInput.softWrap && node.size.width < r.multiParagraph.intrinsics.maxIntrinsicWidth - 0.5f)

    @Test
    fun `no design-system text is clipped or ellipsized`() {
        show()
        val texts = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue("expected the page's texts", texts.size > 40)
        val clipped = texts.filter { isClipped(it, layout(it)) }.map { "'${layout(it).layoutInput.text}'" }
        assertTrue("clipped at fontScale=$fontScale: $clipped", clipped.isEmpty())
    }

    @Test
    fun `the DEMO corner tag stays whole at any text size`() {
        show()
        val tags = rule.onAllNodes(hasTestTag(RmTags.DEMO_TAG), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(tags.size >= 2)
        // The word inside each corner tag is laid out in full (the tag may grow past the 40 dp avatar).
        val words = rule.onAllNodes(hasAnyAncestor(hasTestTag(RmTags.PROVIDER_AVATAR)) and hasTestTag(RmTags.DEMO_TAG), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue(words.isNotEmpty())
        // "DEMO" in bold is about 2.9 em wide; squashed into the 40 dp avatar at 200 % it showed only "DE".
        val density = rule.density.density
        val minPx = (2.5f * 8f * fontScale + 8f) * density
        for (tag in words) {
            assertTrue("DEMO tag squashed: ${tag.size.width}px < ${minPx}px", tag.size.width >= minPx)
        }
        rule.onNodeWithTag(RmTags.fareCard("demo_kestrel", "cab_xl")).assertExists()
    }
}
