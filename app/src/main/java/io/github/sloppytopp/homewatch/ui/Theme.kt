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
    WATCH("SUSPECT", "◔", Color(0xFFFFB000), Color(0x99342400)),
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

private val Calm = darkColorScheme(
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    onBackground = Color(0xFFF2F2F2),
    onSurface = Color(0xFFF2F2F2),
    primary = Color(0xFF5CB874),
)

@Composable
fun HomewatchTheme(content: @Composable () -> Unit) {
    // Always dark: the app is meant to be used at night without lighting up the street.
    MaterialTheme(
        colorScheme = if (Prefs.hacker) Dark else Calm,
        typography = if (!Prefs.hacker) androidx.compose.material3.Typography() else androidx.compose.material3.Typography().let { t ->
            val m = androidx.compose.ui.text.font.FontFamily.Monospace
            t.copy(bodyLarge = t.bodyLarge.copy(fontFamily = m), bodyMedium = t.bodyMedium.copy(fontFamily = m), bodySmall = t.bodySmall.copy(fontFamily = m),
                labelLarge = t.labelLarge.copy(fontFamily = m), titleMedium = t.titleMedium.copy(fontFamily = m), titleLarge = t.titleLarge.copy(fontFamily = m))
        },
        content = content,
    )
}

/** Calm look by default: green = clear, amber = suspect, red only for alert. Hacker theme: the old neon / Night-mode palette. */
fun Level.accentC(): Color = if (!Prefs.hacker) when (this) {
    Level.OFF -> Color(0xFF9E9E9E); Level.OK -> Color(0xFF5CB874); Level.WATCH -> Color(0xFFFFB347); Level.ALERT -> Color(0xFFE5534B)
} else if (!Prefs.night) accent else when (this) {
    Level.OFF -> Color(0xFF4A1C1C); Level.OK -> Color(0xFF6E2A2A); Level.WATCH -> Color(0xFF8C3434); Level.ALERT -> Color(0xFFAA4040)
}
fun Level.containerC(): Color = if (!Prefs.hacker) when (this) {
    Level.OFF -> Color(0xFF262626); Level.OK -> Color(0xFF1E2B22); Level.WATCH -> Color(0xFF2E2716); Level.ALERT -> Color(0xFF331B1B)
} else if (Prefs.night) Color(0xCC0C0404) else container

object UiColors {
    private fun pick(calm: Long, neon: Long, red: Long) = Color(if (!Prefs.hacker) calm else if (Prefs.night) red else neon)
    val bg get() = if (!Prefs.hacker) Color(0xFF121212) else Color.Black
    val text get() = pick(0xFFF2F2F2, 0xFFA8E6B8, 0xFF8C3030)
    val dim get() = pick(0xFFC4C4C4, 0xFF6FA381, 0xFF722B2B)
    val faint get() = pick(0xFF8F8F8F, 0xFF4D7A5C, 0xFF5A2222)
    val warn get() = pick(0xFFFFB347, 0xFFFFB000, 0xFF8C3030)
    val ring get() = pick(0xFF3A3A3A, 0xFF14502F, 0xFF2A0D0D)
    val good get() = pick(0xFF5CB874, 0xFF35E07A, 0xFF6E2A2A)
    val neutral get() = pick(0xFF9E9E9E, 0xFF6F9A80, 0xFF4A1C1C)
    val alert get() = pick(0xFFE5534B, 0xFFFF3B3B, 0xFFAA4040)
    val watch get() = pick(0xFFFFB347, 0xFFFFB000, 0xFF8C3434)
    val dialogBg get() = pick(0xFF1E1E1E, 0xFF04120A, 0xFF0A0303)
    val buttonBg get() = pick(0xFF2E2E2E, 0xFF0A2A18, 0xFF1A0707)
    val buttonFg get() = pick(0xFFFFFFFF, 0xFF5DFF9A, 0xFF8C3030)
    val card get() = pick(0xFF1E1E1E, 0xFF0A2A18, 0xFF1A0707)
}
