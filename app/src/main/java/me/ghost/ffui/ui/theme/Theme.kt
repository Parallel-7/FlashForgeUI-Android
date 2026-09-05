package me.ghost.ffui.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val GeometricColorScheme = darkColorScheme(
    primary = GeometricPrimary,
    onPrimary = Color.White,
    primaryContainer = GeometricPrimaryContainer,
    onPrimaryContainer = GeometricOnPrimaryContainer,
    secondary = GeometricPrimary,
    background = GeometricBackground,
    onBackground = GeometricOnBackground,
    surface = GeometricSurface,
    onSurface = GeometricOnBackground,
    surfaceVariant = GeometricSurface,
    onSurfaceVariant = GeometricOnSurfaceVariant,
    outline = GeometricOutline,
    outlineVariant = GeometricOutlineVariant,
    errorContainer = GeometricErrorContainer,
    onErrorContainer = GeometricOnErrorContainer,
)

/** The app's single dark Geometric scheme — there is no light/dynamic variant to pick. */
@Composable
fun MyApplicationTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = GeometricColorScheme, typography = Typography, content = content)
}
