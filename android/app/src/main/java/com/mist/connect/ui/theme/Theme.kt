package com.mist.connect.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.mist.connect.AppTheme
import com.mist.connect.AppThemeId

// The approved Nous Girl assistant icon supplies the warm paper, ink and brass palette.
// Keep semantic error colors distinct; do not use the icon palette to imply a service state.
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF262321),
    onPrimary = Color(0xFFFFF7EB),
    primaryContainer = Color(0xFFEDE0CE),
    onPrimaryContainer = Color(0xFF201D1A),
    secondary = Color(0xFF6D5030),
    onSecondary = Color(0xFFFFF9F1),
    secondaryContainer = Color(0xFFF2DFC2),
    onSecondaryContainer = Color(0xFF382615),
    tertiary = Color(0xFF705321),
    onTertiary = Color(0xFFFFF9F0),
    tertiaryContainer = Color(0xFFF2DFC0),
    onTertiaryContainer = Color(0xFF382709),
    background = Color(0xFFF8EFE3),
    onBackground = Color(0xFF26221F),
    surface = Color(0xFFFFF8EE),
    onSurface = Color(0xFF26221F),
    surfaceVariant = Color(0xFFEFE5D8),
    onSurfaceVariant = Color(0xFF554C43),
    surfaceContainerHighest = Color(0xFFE9DDD0),
    outline = Color(0xFF7E756A),
    outlineVariant = Color(0xFFD4C7B8),
    error = Color(0xFFA12F31),
    onError = Color(0xFFFFF8F5),
    errorContainer = Color(0xFFF8DDDA),
    onErrorContainer = Color(0xFF671A1A)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFE8C895),
    onPrimary = Color(0xFF2C241A),
    primaryContainer = Color(0xFF4B3C29),
    onPrimaryContainer = Color(0xFFFFEBD2),
    secondary = Color(0xFFD4BC99),
    onSecondary = Color(0xFF30291F),
    secondaryContainer = Color(0xFF4A3B2A),
    onSecondaryContainer = Color(0xFFF7E4C9),
    tertiary = Color(0xFFE6C387),
    onTertiary = Color(0xFF30230E),
    tertiaryContainer = Color(0xFF4E3B1D),
    onTertiaryContainer = Color(0xFFFFE5B7),
    background = Color(0xFF211F1E),
    onBackground = Color(0xFFF2E6D8),
    surface = Color(0xFF292624),
    onSurface = Color(0xFFF2E6D8),
    surfaceVariant = Color(0xFF39332D),
    onSurfaceVariant = Color(0xFFD5C4B2),
    surfaceContainerHighest = Color(0xFF403A34),
    outline = Color(0xFFA89A8A),
    outlineVariant = Color(0xFF5B5249),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690C13),
    errorContainer = Color(0xFF852626),
    onErrorContainer = Color(0xFFFFDAD6)
)

@Composable
internal fun MistConnectTheme(
    theme: AppThemeId = AppTheme.state.value,
    content: @Composable () -> Unit
) {
    // The app's own theme choice, not the phone's dark mode.
    val colorScheme = when (theme) {
        AppThemeId.BASIC -> LightColorScheme
        AppThemeId.DARK -> DarkColorScheme
    }
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
