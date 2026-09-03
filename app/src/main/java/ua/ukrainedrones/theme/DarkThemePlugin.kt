package ua.ukrainedrones.theme

import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/** The only shipped theme — this app never switches to a light theme, regardless of the
 *  device's system setting. */
object DarkThemePlugin : ThemePlugin {
    override val name = "dark"
    override val isDark = true
    override val colors = darkColorScheme(
        primary = Color(0xFF64B5F6),
        background = Color(0xFF121212),
        surface = Color(0xFF1A1A1A),
        surfaceVariant = Color(0xFF232323),
        onBackground = Color(0xFFEDEDED),
        onSurface = Color(0xFFEDEDED),
        error = Color(0xFFE57373)
    )
    override val typography = Typography()
}