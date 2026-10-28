package app.ridemate.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Brand = Color(0xFF0F6E5A)
private val Accent = Color(0xFFD9A441)

private val Light = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    secondary = Accent,
    onSecondary = Color(0xFF231A00),
    background = Color(0xFFFBF7F2),
    surface = Color(0xFFFFFFFF),
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF7FD6BF),
    onPrimary = Color(0xFF00382D),
    secondary = Accent,
    background = Color(0xFF121412),
    surface = Color(0xFF1B1E1C),
    error = Color(0xFFF2B8B5),
)

@Composable
fun RideMateTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
