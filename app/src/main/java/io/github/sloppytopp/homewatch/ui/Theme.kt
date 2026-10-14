package io.github.sloppytopp.homewatch.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.sloppytopp.homewatch.data.Prefs

/** Low-glare palette: true black, muted desaturated status colors, never a bright broadcast of light. */
enum class Level(val word: String, val icon: String, val accent: Color, val container: Color) {
    OFF("OFF", "–", Color(0xFF5F8A70), Color(0x990A1810)),
    OK("OK", "✓", Color(0xFF35E07A), Color(0x99002D16)),
    WATCH("WATCH", "◔", Color(0xFFFFB000), Color(0x99342400)),
    ALERT("ALERT", "▲", Color(0xFFFF3B3B), Color(0xA63C0808)),
}

/** Night mode: dim red on black, for walking around outside. */
object NightPalette {
    val text = Color(0xFF8C3030)
    val border = Color(0xFF3A1414)
}

private val Dark = darkColorScheme(
    background = Color.Black,
    surface = Color(0xFF0A0A0A),
    onBackground = Color(0xFFA8E6B8),
    onSurface = Color(0xFFA8E6B8),
    primary = Color(0xFF35E07A),
)

@Composable
fun HomewatchTheme(content: @Composable () -> Unit) {
    // Always dark: the app is meant to be used at night without lighting up the street.
    MaterialTheme(
        colorScheme = Dark,
        typography = androidx.compose.material3.Typography().let { t ->
            val m = androidx.compose.ui.text.font.FontFamily.Monospace
            t.copy(bodyLarge = t.bodyLarge.copy(fontFamily = m), bodyMedium = t.bodyMedium.copy(fontFamily = m), bodySmall = t.bodySmall.copy(fontFamily = m),
                labelLarge = t.labelLarge.copy(fontFamily = m), titleMedium = t.titleMedium.copy(fontFamily = m), titleLarge = t.titleLarge.copy(fontFamily = m))
        },
        content = content,
    )
}

/** Night mode = dim red on black (keeps night vision, shows no bright light). Status is still word + icon. */
fun Level.accentC(): Color = if (!Prefs.night) accent else when (this) {
    Level.OFF -> Color(0xFF4A1C1C); Level.OK -> Color(0xFF6E2A2A); Level.WATCH -> Color(0xFF8C3434); Level.ALERT -> Color(0xFFAA4040)
}
fun Level.containerC(): Color = if (Prefs.night) Color(0xCC0C0404) else container

object UiColors {
    val text get() = if (Prefs.night) Color(0xFF8C3030) else Color(0xFFA8E6B8)
    val dim get() = if (Prefs.night) Color(0xFF722B2B) else Color(0xFF6FA381)
    val faint get() = if (Prefs.night) Color(0xFF5A2222) else Color(0xFF4D7A5C)
    val warn get() = if (Prefs.night) Color(0xFF8C3030) else Color(0xFFFFB000)
    val ring get() = if (Prefs.night) Color(0xFF2A0D0D) else Color(0xFF14502F)
    val good get() = if (Prefs.night) Color(0xFF6E2A2A) else Color(0xFF35E07A)
    val neutral get() = if (Prefs.night) Color(0xFF4A1C1C) else Color(0xFF6F9A80)
    val alert get() = if (Prefs.night) Color(0xFFAA4040) else Color(0xFFFF3B3B)
    val watch get() = if (Prefs.night) Color(0xFF8C3434) else Color(0xFFFFB000)
    val dialogBg get() = if (Prefs.night) Color(0xFF0A0303) else Color(0xFF04120A)
    val buttonBg get() = if (Prefs.night) Color(0xFF1A0707) else Color(0xFF0A2A18)
    val buttonFg get() = if (Prefs.night) Color(0xFF8C3030) else Color(0xFF5DFF9A)
}
