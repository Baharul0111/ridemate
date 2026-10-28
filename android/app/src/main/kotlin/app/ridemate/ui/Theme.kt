package app.ridemate.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Brand = Color(0xFF0F6E5A)
private val Accent = Color(0xFFD9A441)

private val Light = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8EFE3),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF7A5900),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDFA0),
    onSecondaryContainer = Color(0xFF261A00),
    background = Color(0xFFFBF7F2),
    onBackground = Color(0xFF1B1C1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1C1A),
    surfaceVariant = Color(0xFFE6ECE8),
    onSurfaceVariant = Color(0xFF3F4945),
    outline = Color(0xFF6F7975),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF7FD6BF),
    onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF005142),
    onPrimaryContainer = Color(0xFF9CF2DA),
    secondary = Accent,
    onSecondary = Color(0xFF412D00),
    secondaryContainer = Color(0xFF5C4300),
    onSecondaryContainer = Color(0xFFFFDFA0),
    background = Color(0xFF121412),
    onBackground = Color(0xFFE2E3DF),
    surface = Color(0xFF1B1E1C),
    onSurface = Color(0xFFE2E3DF),
    surfaceVariant = Color(0xFF3F4945),
    onSurfaceVariant = Color(0xFFBFC9C4),
    outline = Color(0xFF89938F),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

/**
 * Colours for status chips and cards. Every status is also shown with an icon and words (goal 27: information is
 * never carried by colour alone); the colours only reinforce it. All pairs meet WCAG AA contrast.
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

private val LightStatus = StatusColors(
    good = Color(0xFFD7F0DE), onGood = Color(0xFF0D4F27),
    warning = Color(0xFFFFE8B8), onWarning = Color(0xFF5C3B00),
    bad = Color(0xFFFADBD8), onBad = Color(0xFF7F1414),
    neutral = Color(0xFFE3E8E5), onNeutral = Color(0xFF2F3633),
    demo = Color(0xFFE8DDFB), onDemo = Color(0xFF3A1F70),
)

private val DarkStatus = StatusColors(
    good = Color(0xFF1E4D2E), onGood = Color(0xFFC9F0D4),
    warning = Color(0xFF5A4100), onWarning = Color(0xFFFFE3A6),
    bad = Color(0xFF6B1A1A), onBad = Color(0xFFFFDAD6),
    neutral = Color(0xFF353B38), onNeutral = Color(0xFFDDE4E0),
    demo = Color(0xFF452C78), onDemo = Color(0xFFEADDFF),
)

private val LocalStatusColors = staticCompositionLocalOf { LightStatus }

/** Readable defaults; sizes are in sp so the phone's font-size setting scales every text. */
private val RideMateTypography: Typography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(lineHeight = 24.sp),
        bodyMedium = base.bodyMedium.copy(lineHeight = 21.sp),
        labelLarge = base.labelLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    )
}

private val RideMateShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
)

/** RideMate's Material 3 theme: light and dark colour schemes, typography, shapes and [StatusColors]. */
@Composable
fun RideMateTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalStatusColors provides if (darkTheme) DarkStatus else LightStatus) {
        MaterialTheme(
            colorScheme = if (darkTheme) Dark else Light,
            typography = RideMateTypography,
            shapes = RideMateShapes,
            content = content,
        )
    }
}

object RideMateTheme {
    val status: StatusColors
        @Composable
        @ReadOnlyComposable
        get() = LocalStatusColors.current

    /** Monospace style for fingerprints and checksums. */
    val mono: TextStyle
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
}
