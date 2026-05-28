package me.ghost.ffui.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

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

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  MaterialTheme(colorScheme = GeometricColorScheme, typography = Typography, content = content)
}
