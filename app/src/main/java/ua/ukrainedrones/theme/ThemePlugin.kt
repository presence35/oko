package ua.ukrainedrones.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography

/** Theme plugin contract — the app is dark-only today, but the architecture allows adding
 *  light/other themes later without touching consumers. */
interface ThemePlugin {
    val name: String
    val isDark: Boolean
    val colors: ColorScheme
    val typography: Typography
}