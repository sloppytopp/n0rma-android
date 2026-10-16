package io.github.sloppytopp.n0rma.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.sloppytopp.n0rma.data.AppTheme
import io.github.sloppytopp.n0rma.data.Prefs

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

private val Night = darkColorScheme(
    background = Color.Black, surface = Color(0xFF0A0303), onBackground = Color(0xFF8C3030), onSurface = Color(0xFF8C3030), primary = Color(0xFF8C3030),
)

private val Executive = darkColorScheme(
    background = Color(0xFF0E1116), surface = Color(0xFF161B22), onBackground = Color(0xFFE6EDF3), onSurface = Color(0xFFE6EDF3), primary = Color(0xFF58A6FF),
)

private val Calm = darkColorScheme(
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    onBackground = Color(0xFFF2F2F2),
    onSurface = Color(0xFFF2F2F2),
    primary = Color(0xFF5CB874),
)

@Composable
fun N0rmaTheme(content: @Composable () -> Unit) {
    // Always dark: the app is meant to be used at night without lighting up the street.
    MaterialTheme(
        colorScheme = when { Prefs.hacker && !Prefs.night -> Dark; Prefs.night -> Night; Prefs.theme == AppTheme.EXECUTIVE -> Executive; else -> Calm },
        typography = if (!Prefs.hacker) androidx.compose.material3.Typography() else androidx.compose.material3.Typography().let { t ->
            val m = androidx.compose.ui.text.font.FontFamily.Monospace
            t.copy(bodyLarge = t.bodyLarge.copy(fontFamily = m), bodyMedium = t.bodyMedium.copy(fontFamily = m), bodySmall = t.bodySmall.copy(fontFamily = m),
                labelLarge = t.labelLarge.copy(fontFamily = m), titleMedium = t.titleMedium.copy(fontFamily = m), titleLarge = t.titleLarge.copy(fontFamily = m))
        },
        content = content,
    )
}

/** Per theme: Calm = grey cards, Executive = slate, Night vision / Classic-night = dim red on black, Classic = neon green. */
fun Level.accentC(): Color = when {
    Prefs.theme == AppTheme.CALM || Prefs.theme == AppTheme.EXECUTIVE -> when (this) {
        Level.OFF -> Color(0xFF9E9E9E); Level.OK -> Color(0xFF5CB874); Level.WATCH -> Color(0xFFFFB347); Level.ALERT -> Color(0xFFE5534B)
    }
    Prefs.night -> when (this) {
        Level.OFF -> Color(0xFF4A1C1C); Level.OK -> Color(0xFF6E2A2A); Level.WATCH -> Color(0xFF8C3434); Level.ALERT -> Color(0xFFAA4040)
    }
    else -> accent
}
fun Level.containerC(): Color = when {
    Prefs.theme == AppTheme.EXECUTIVE -> when (this) {
        Level.OFF -> Color(0xFF161B22); Level.OK -> Color(0xFF14231B); Level.WATCH -> Color(0xFF2A2312); Level.ALERT -> Color(0xFF2E1719)
    }
    Prefs.theme == AppTheme.CALM -> when (this) {
        Level.OFF -> Color(0xFF262626); Level.OK -> Color(0xFF1E2B22); Level.WATCH -> Color(0xFF2E2716); Level.ALERT -> Color(0xFF331B1B)
    }
    Prefs.night -> Color(0xCC0C0404)
    else -> container
}

object UiColors {
    /** calm, executive, night (red on black), classic neon */
    private fun pick(calm: Long, exec: Long, night: Long, neon: Long) = Color(when {
        Prefs.theme == AppTheme.CALM -> calm
        Prefs.theme == AppTheme.EXECUTIVE -> exec
        Prefs.night -> night
        else -> neon
    })
    val bg get() = when { Prefs.theme == AppTheme.CALM -> Color(0xFF121212); Prefs.theme == AppTheme.EXECUTIVE -> Color(0xFF0E1116); else -> Color.Black }
    val text get() = pick(0xFFF2F2F2, 0xFFE6EDF3, 0xFF8C3030, 0xFFA8E6B8)
    val dim get() = pick(0xFFC4C4C4, 0xFF9DA7B3, 0xFF722B2B, 0xFF6FA381)
    val faint get() = pick(0xFF8F8F8F, 0xFF6E7681, 0xFF5A2222, 0xFF4D7A5C)
    val warn get() = pick(0xFFFFB347, 0xFFE3B341, 0xFF8C3030, 0xFFFFB000)
    val ring get() = pick(0xFF3A3A3A, 0xFF30363D, 0xFF2A0D0D, 0xFF14502F)
    val good get() = pick(0xFF5CB874, 0xFF58A6FF, 0xFF6E2A2A, 0xFF35E07A)
    val neutral get() = pick(0xFF9E9E9E, 0xFF8B949E, 0xFF4A1C1C, 0xFF6F9A80)
    val alert get() = pick(0xFFE5534B, 0xFFF85149, 0xFFAA4040, 0xFFFF3B3B)
    val watch get() = pick(0xFFFFB347, 0xFFE3B341, 0xFF8C3434, 0xFFFFB000)
    val dialogBg get() = pick(0xFF1E1E1E, 0xFF161B22, 0xFF0A0303, 0xFF04120A)
    val buttonBg get() = pick(0xFF2E2E2E, 0xFF21262D, 0xFF1A0707, 0xFF0A2A18)
    val buttonFg get() = pick(0xFFFFFFFF, 0xFFE6EDF3, 0xFF8C3030, 0xFF5DFF9A)
    val card get() = pick(0xFF1E1E1E, 0xFF161B22, 0xFF1A0707, 0xFF0A2A18)
    /** Text on the filled primary button/chip. */
    val onGood get() = pick(0xFF0B1A10, 0xFF0B1220, 0xFF1A0707, 0xFF04120A)
}
