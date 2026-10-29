package app.ridemate.ui.components

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.ridemate.R
import app.ridemate.ui.RideMateTheme

/**
 * Onboarding progress: one dot per step, the current one a wider brand pill (shape, not only colour), done steps
 * brand, upcoming steps grey. TalkBack reads "Step 2 of 6".
 *
 * @param current the 0-based index of the current step.
 */
@Composable
fun StepDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    require(count > 0) { "StepDots needs at least one step" }
    val step = current.coerceIn(0, count - 1)
    val spoken = stringResource(R.string.rm_step_of, step + 1, count)
    val colors = RideMateTheme.colors
    val motion = RideMateTheme.motion
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .testTag(RmTags.STEP_DOTS)
            .clearAndSetSemantics {
                contentDescription = spoken
                progressBarRangeInfo = ProgressBarRangeInfo((step + 1).toFloat(), 1f..count.toFloat(), steps = (count - 2).coerceAtLeast(0))
            },
    ) {
        repeat(count) { index ->
            val active = index == step
            val width by animateDpAsState(if (active) 24.dp else 8.dp, motion.tween(), label = "dot-width")
            val color by animateColorAsState(
                // Upcoming dots use `outline` (≥ 3:1 on canvas and sheet), not the faint `line`, so the number of
                // steps left is visible (WCAG 1.4.11).
                if (index <= step) colors.brand else colors.outline,
                motion.tween(),
                label = "dot-color",
            )
            Box(Modifier.height(8.dp).width(width).background(color, CircleShape))
        }
    }
}

/** The in-house vector illustrations for onboarding and empty states (no stock images, no other brands). */
enum class Hero(@get:DrawableRes val drawable: Int) {
    WELCOME(R.drawable.rm_hero_welcome),
    PRIVACY(R.drawable.rm_hero_privacy),
    SIGN_IN(R.drawable.rm_hero_sign_in),
    PERMISSIONS(R.drawable.rm_hero_permissions),
    ALL_SET(R.drawable.rm_hero_all_set),
    DEMO(R.drawable.rm_hero_demo),
    EMPTY(R.drawable.rm_hero_empty),
    OFFLINE(R.drawable.rm_hero_offline),
}

/**
 * A hero illustration (3:2), drawn in the theme's light or dark palette (`res/values[-night]/colors_ui.xml`)
 * even when the app's theme differs from the phone's. Decorative by default — the screen's title says the same
 * thing; pass [contentDescription] when it carries meaning on its own.
 */
@Composable
fun HeroIllustration(
    hero: Hero,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
    contentDescription: String? = null,
) {
    val dark = RideMateTheme.colors.isDark
    val context = LocalContext.current
    val vector: ImageVector = remember(context, dark, hero) {
        val themed = context.withNightMode(dark)
        ImageVector.vectorResource(themed.theme, themed.resources, hero.drawable)
    }
    Image(
        painter = rememberVectorPainter(vector),
        contentDescription = contentDescription,
        modifier = modifier
            .height(height)
            .aspectRatio(1.5f)
            .testTag(RmTags.HERO)
            .then(if (contentDescription == null) Modifier.clearAndSetSemantics {} else Modifier.semantics {}),
    )
}

/** This context, or a copy whose resources resolve `-night` (or not) to match [dark]. */
internal fun Context.withNightMode(dark: Boolean): Context {
    val config = resources.configuration
    val wanted = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    if (config.uiMode and Configuration.UI_MODE_NIGHT_MASK == wanted) return this
    val override = Configuration(config).apply { uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or wanted }
    return createConfigurationContext(override)
}

@Preview(name = "Heroes and step dots", showBackground = true, heightDp = 1500)
@Composable
private fun OnboardingPreview() {
    RideMateTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            StepDots(count = 6, current = 1)
            Hero.entries.forEach { HeroIllustration(it) }
            Box(Modifier.size(1.dp))
        }
    }
}
