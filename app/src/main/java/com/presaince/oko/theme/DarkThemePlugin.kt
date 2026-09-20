package com.presaince.oko.theme

import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/** The only shipped theme — this app never switches to a light theme, regardless of the
 *  device's system setting. */
object DarkThemePlugin : ThemePlugin {
    override val name = "dark"
    override val isDark = true
    override val colors = darkColorScheme(
        primary = Color(AppPalette.Primary),
        background = Color(AppPalette.Background),
        surface = Color(AppPalette.Surface),
        surfaceVariant = Color(AppPalette.SurfaceVariant),
        onBackground = Color(AppPalette.TextPrimary),
        onSurface = Color(AppPalette.TextPrimary),
        error = Color(AppPalette.AlertRed)
    )
    override val typography = Typography()
}