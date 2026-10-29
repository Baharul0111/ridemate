package app.ridemate.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * RideMate design tokens (docs/design/ui-redesign.md §2). Colours, type, shapes, spacing and motion live here;
 * the components in app.ridemate.ui.components read them through [RideMateTheme] and never hard-code values.
 * RideMate's own identity: deep teal + marigold. No ride app's colours, logos or typefaces are used anywhere.
 */

// ---------------------------------------------------------------------------------------------------- colours

/**
 * RideMate's colour roles beyond the Material [ColorScheme]: the spec's named tokens (`brand`, `accent`, `ink`,
 * `muted`, `canvas`, `sheet`, `line`, `good`, `warn`, `danger`) plus soft "container" pairs for pills, banners
 * and cards. Every container/on-container pair meets WCAG AA (4.5:1) for text; state is never shown by colour
 * alone (goal 27), the colour only reinforces an icon and a word.
 */
@Immutable
data class RideMateColors(
    val brand: Color,
    val onBrand: Color,
    val brandContainer: Color,
    val onBrandContainer: Color,
    val accent: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    val ink: Color,
    val muted: Color,
    val canvas: Color,
    val sheet: Color,
    val line: Color,
    val good: Color,
    val onGood: Color,
    val goodContainer: Color,
    val onGoodContainer: Color,
    val warn: Color,
    val onWarn: Color,
    val warnContainer: Color,
    val onWarnContainer: Color,
    val danger: Color,
    val onDanger: Color,
    val dangerContainer: Color,
    val onDangerContainer: Color,
    val neutralContainer: Color,
    val onNeutralContainer: Color,
    val demoContainer: Color,
    val onDemoContainer: Color,
    val isDark: Boolean,
)

/** The spec's light tokens. */
val LightRideMateColors = RideMateColors(
    brand = Color(0xFF0E6B57),
    onBrand = Color(0xFFFFFFFF),
    brandContainer = Color(0xFFD3EFE6),
    onBrandContainer = Color(0xFF00201A),
    accent = Color(0xFFF2B33D),
    onAccent = Color(0xFF2A1C00),
    accentContainer = Color(0xFFFFF0CF),
    onAccentContainer = Color(0xFF4A3300),
    ink = Color(0xFF121614),
    muted = Color(0xFF5C6661),
    canvas = Color(0xFFF5F7F6),
    sheet = Color(0xFFFFFFFF),
    line = Color(0xFFE3E8E5),
    good = Color(0xFF1E8E4E),
    onGood = Color(0xFFFFFFFF),
    goodContainer = Color(0xFFDDF3E5),
    onGoodContainer = Color(0xFF0B4A26),
    warn = Color(0xFFB26A00),
    onWarn = Color(0xFFFFFFFF),
    warnContainer = Color(0xFFFFEDCC),
    onWarnContainer = Color(0xFF5A3600),
    danger = Color(0xFFC62828),
    onDanger = Color(0xFFFFFFFF),
    dangerContainer = Color(0xFFFDE3E1),
    onDangerContainer = Color(0xFF7A1515),
    neutralContainer = Color(0xFFEBEFED),
    onNeutralContainer = Color(0xFF2F3633),
    demoContainer = Color(0xFFECE4FA),
    onDemoContainer = Color(0xFF3A1F70),
    isDark = false,
)

/** The spec's dark tokens. */
val DarkRideMateColors = RideMateColors(
    brand = Color(0xFF6FD3B8),
    onBrand = Color(0xFF003829),
    brandContainer = Color(0xFF0E4F41),
    onBrandContainer = Color(0xFFB4F2E0),
    accent = Color(0xFFF5C25E),
    onAccent = Color(0xFF2A1C00),
    accentContainer = Color(0xFF4A3708),
    onAccentContainer = Color(0xFFFFE3A6),
    ink = Color(0xFFE6EAE7),
    muted = Color(0xFFA9B4AF),
    canvas = Color(0xFF0E1110),
    sheet = Color(0xFF181C1A),
    line = Color(0xFF2A302D),
    good = Color(0xFF5FD08F),
    onGood = Color(0xFF00391B),
    goodContainer = Color(0xFF173A26),
    onGoodContainer = Color(0xFFBFF0D2),
    warn = Color(0xFFFFB74D),
    onWarn = Color(0xFF452B00),
    warnContainer = Color(0xFF47330C),
    onWarnContainer = Color(0xFFFFDDA6),
    danger = Color(0xFFFF8A80),
    onDanger = Color(0xFF5F0F0F),
    dangerContainer = Color(0xFF5C1A16),
    onDangerContainer = Color(0xFFFFDAD6),
    neutralContainer = Color(0xFF262B29),
    onNeutralContainer = Color(0xFFDDE4E0),
    demoContainer = Color(0xFF3A2A60),
    onDemoContainer = Color(0xFFE8DDFF),
    isDark = true,
)

private fun RideMateColors.toLightScheme(): ColorScheme = lightColorScheme(
    primary = brand,
    onPrimary = onBrand,
    primaryContainer = brandContainer,
    onPrimaryContainer = onBrandContainer,
    inversePrimary = DarkRideMateColors.brand,
    secondary = accent,
    onSecondary = onAccent,
    secondaryContainer = accentContainer,
    onSecondaryContainer = onAccentContainer,
    tertiary = Color(0xFF3F6374),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD5E8F2),
    onTertiaryContainer = Color(0xFF0B2A37),
    background = canvas,
    onBackground = ink,
    surface = sheet,
    onSurface = ink,
    surfaceVariant = Color(0xFFEDF1EF),
    onSurfaceVariant = muted,
    surfaceTint = brand,
    inverseSurface = Color(0xFF2B302E),
    inverseOnSurface = Color(0xFFEEF2F0),
    error = danger,
    onError = onDanger,
    errorContainer = dangerContainer,
    onErrorContainer = onDangerContainer,
    outline = Color(0xFF8A9590),
    outlineVariant = line,
    scrim = Color.Black,
    surfaceBright = sheet,
    surfaceDim = Color(0xFFDDE2DF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF9FBFA),
    surfaceContainer = Color(0xFFF2F5F3),
    surfaceContainerHigh = Color(0xFFEEF2F0),
    surfaceContainerHighest = Color(0xFFE9EEEB),
)

private fun RideMateColors.toDarkScheme(): ColorScheme = darkColorScheme(
    primary = brand,
    onPrimary = onBrand,
    primaryContainer = brandContainer,
    onPrimaryContainer = onBrandContainer,
    inversePrimary = LightRideMateColors.brand,
    secondary = accent,
    onSecondary = onAccent,
    secondaryContainer = accentContainer,
    onSecondaryContainer = onAccentContainer,
    tertiary = Color(0xFFA6CCDE),
    onTertiary = Color(0xFF0B2A37),
    tertiaryContainer = Color(0xFF26495A),
    onTertiaryContainer = Color(0xFFD5E8F2),
    background = canvas,
    onBackground = ink,
    surface = sheet,
    onSurface = ink,
    surfaceVariant = Color(0xFF232826),
    onSurfaceVariant = muted,
    surfaceTint = brand,
    inverseSurface = Color(0xFFE6EAE7),
    inverseOnSurface = Color(0xFF2B302E),
    error = danger,
    onError = onDanger,
    errorContainer = dangerContainer,
    onErrorContainer = onDangerContainer,
    outline = Color(0xFF7D8883),
    outlineVariant = line,
    scrim = Color.Black,
    surfaceBright = Color(0xFF2E3431),
    surfaceDim = canvas,
    surfaceContainerLowest = Color(0xFF0B0D0C),
    surfaceContainerLow = Color(0xFF141816),
    surfaceContainer = sheet,
    surfaceContainerHigh = Color(0xFF202523),
    surfaceContainerHighest = Color(0xFF2A302D),
)

/**
 * Soft colours for the legacy status chips and cards ([StatusChip], [InfoCard]). Kept for source compatibility;
 * new code reads [RideMateTheme.colors]. Every status is also shown with an icon and words (goal 27).
 */
@Immutable
data class StatusColors(
    val good: Color,
    val onGood: Color,
    val warning: Color,
    val onWarning: Color,
    val bad: Color,
    val onBad: Color,
    val neutral: Color,
    val onNeutral: Color,
    val demo: Color,
    val onDemo: Color,
)

private fun RideMateColors.toStatusColors() = StatusColors(
    good = goodContainer, onGood = onGoodContainer,
    warning = warnContainer, onWarning = onWarnContainer,
    bad = dangerContainer, onBad = onDangerContainer,
    neutral = neutralContainer, onNeutral = onNeutralContainer,
    demo = demoContainer, onDemo = onDemoContainer,
)

// ------------------------------------------------------------------------------------------------------- type

/**
 * The spec's type ramp on the default sans (Roboto / Noto; Hindi falls back to Noto Sans Devanagari). All sizes
 * are in sp so the phone's font-size setting (up to 200 %) scales every text.
 *
 * * Display 32 sp bold: one question per screen ("Where to?").
 * * Title 22 sp semibold.
 * * Body 16 sp.
 * * Label 13 sp medium.
 * * [RideMateType.price]: 24 sp bold with tabular figures, so ₹ amounts line up and never jitter.
 */
private val RideMateTypography: Typography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = base.bodyMedium.copy(lineHeight = 21.sp),
        labelLarge = base.labelLarge.copy(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    )
}

/** RideMate's extra text styles that Material's [Typography] has no slot for. */
@Immutable
data class RideMateType(
    /** Step questions and hero numbers ("4 min · 1.2 km"): 32 sp bold. */
    val display: TextStyle,
    /** Fares on option cards: 24 sp bold, tabular figures (`tnum`). */
    val price: TextStyle,
    /** A single hero fare: 32 sp bold, tabular figures. */
    val priceLarge: TextStyle,
    /** Fingerprints and checksums. */
    val mono: TextStyle,
)

private val DefaultRideMateType = RideMateType(
    display = RideMateTypography.displaySmall,
    price = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
    priceLarge = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
    mono = RideMateTypography.bodySmall.copy(fontFamily = FontFamily.Monospace),
)

// ------------------------------------------------------------------------------------------------------ shape

/**
 * Shapes: cards 20 dp, sheets 28 dp top corners, buttons and pills fully rounded, chips and fields 12 dp.
 * Material components pick these up through [MaterialTheme.shapes] (Card → medium, sheet/dialog → extraLarge).
 */
object RmShapes {
    val card = RoundedCornerShape(20.dp)
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    /** Fully rounded for anything up to 56 dp tall; text that wraps at 200 % gets a rounded rectangle, not an oval. */
    val pill = RoundedCornerShape(28.dp)
    val chip = RoundedCornerShape(12.dp)
    val tile = RoundedCornerShape(20.dp)
    val banner = RoundedCornerShape(16.dp)
}

private val RideMateShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

// ---------------------------------------------------------------------------------------------------- spacing

/** The 4-pt spacing grid and fixed sizes (§2): screen padding 20 dp, card padding 16 dp, 12 dp between cards. */
object RmSpacing {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 12.dp
    val l: Dp = 16.dp
    val xl: Dp = 20.dp
    val xxl: Dp = 24.dp
    val xxxl: Dp = 32.dp

    /** Side padding of every screen. */
    val screen: Dp = 20.dp

    /** Inner padding of cards. */
    val card: Dp = 16.dp

    /** Gap between stacked cards. */
    val cardGap: Dp = 12.dp

    /** Minimum touch target (goal 27). */
    val minTouch: Dp = 48.dp

    /** Height of the primary action in the thumb zone. */
    val bigButton: Dp = 56.dp

    /** Minimum height of a list row. */
    val row: Dp = 56.dp

    /** Provider avatar diameter. */
    val avatar: Dp = 40.dp
}

// ----------------------------------------------------------------------------------------------------- motion

/**
 * Calm motion (§1.6): content slides/fades ≤ 250 ms, sheets spring. When the phone's "Remove animations" is on
 * (Settings.Global animator duration scale 0) [enabled] is false and every spec collapses to an instant [snap]
 * and every transition to None, so nothing moves.
 */
@Immutable
class RmMotion(val enabled: Boolean) {
    /** Short duration (state changes, pills): 150 ms, or 0. */
    val shortMs: Int get() = if (enabled) SHORT_MS else 0

    /** Medium duration (step changes, cards): 250 ms, or 0. */
    val mediumMs: Int get() = if (enabled) MEDIUM_MS else 0

    /** A tween of [durationMs] (default 250 ms), or an instant snap when motion is off. */
    fun <T> tween(durationMs: Int = MEDIUM_MS): FiniteAnimationSpec<T> =
        if (enabled) androidx.compose.animation.core.tween(durationMs, easing = FastOutSlowInEasing) else snap()

    /** A soft spring (sheets, selection), or an instant snap when motion is off. */
    fun <T> spring(): FiniteAnimationSpec<T> =
        if (enabled) androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow) else snap()

    /** Content entering between steps: fade + short rise, or none. */
    fun enter(): EnterTransition =
        if (enabled) fadeIn(tween(MEDIUM_MS)) + slideInVertically(tween(MEDIUM_MS)) { it / 8 } else EnterTransition.None

    /** Content leaving between steps: fade + short drop, or none. */
    fun exit(): ExitTransition =
        if (enabled) fadeOut(tween(SHORT_MS)) + slideOutVertically(tween(SHORT_MS)) { it / 8 } else ExitTransition.None

    override fun equals(other: Any?): Boolean = other is RmMotion && other.enabled == enabled
    override fun hashCode(): Int = enabled.hashCode()
    override fun toString(): String = "RmMotion(enabled=$enabled)"

    companion object {
        const val SHORT_MS = 150
        const val MEDIUM_MS = 250
        val Full = RmMotion(enabled = true)
        val None = RmMotion(enabled = false)

        /** The system animator duration scale (1 when unknown). 0 means the user turned animations off. */
        fun animatorScale(context: Context): Float =
            runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }
                .getOrDefault(1f)

        /** Motion that follows the phone's "Remove animations" setting. */
        fun fromSystem(context: Context): RmMotion = if (animatorScale(context) > 0f) Full else None
    }
}

// ------------------------------------------------------------------------------------------------------ theme

private val LocalRideMateColors = staticCompositionLocalOf { LightRideMateColors }
private val LocalStatusColors = staticCompositionLocalOf { LightRideMateColors.toStatusColors() }
private val LocalRideMateType = staticCompositionLocalOf { DefaultRideMateType }
private val LocalRmMotion = staticCompositionLocalOf { RmMotion.Full }

/**
 * RideMate's Material 3 theme: the spec's light and dark colour schemes, type ramp and shapes, plus the extended
 * [RideMateColors], [RideMateType] and [RmMotion] read through `RideMateTheme.colors / type / motion`.
 *
 * @param motion defaults to the phone's animator setting ([RmMotion.fromSystem]).
 */
@Composable
fun RideMateTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    motion: RmMotion? = null,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkRideMateColors else LightRideMateColors
    val context = LocalContext.current
    val resolvedMotion = motion ?: remember(context) { RmMotion.fromSystem(context) }
    CompositionLocalProvider(
        LocalRideMateColors provides colors,
        LocalStatusColors provides colors.toStatusColors(),
        LocalRideMateType provides DefaultRideMateType,
        LocalRmMotion provides resolvedMotion,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) colors.toDarkScheme() else colors.toLightScheme(),
            typography = RideMateTypography,
            shapes = RideMateShapes,
        ) {
            // Text and icons outside any Surface read as `ink`, not Material's default black (dark mode).
            CompositionLocalProvider(LocalContentColor provides colors.ink, content = content)
        }
    }
}

object RideMateTheme {
    /** The spec's colour tokens (brand, accent, ink, muted, canvas, sheet, line, good, warn, danger …). */
    val colors: RideMateColors
        @Composable
        @ReadOnlyComposable
        get() = LocalRideMateColors.current

    /** Legacy soft status colours. */
    val status: StatusColors
        @Composable
        @ReadOnlyComposable
        get() = LocalStatusColors.current

    /** Extra text styles: display, price (tabular figures), mono. */
    val type: RideMateType
        @Composable
        @ReadOnlyComposable
        get() = LocalRideMateType.current

    /** Motion tokens; collapse to no animation when the phone's animations are off. */
    val motion: RmMotion
        @Composable
        @ReadOnlyComposable
        get() = LocalRmMotion.current

    /** Monospace style for fingerprints and checksums. */
    val mono: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = LocalRideMateType.current.mono
}
