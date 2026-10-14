package io.github.sloppytopp.homewatch.ui

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.sloppytopp.homewatch.data.Prefs
import io.github.sloppytopp.homewatch.detect.Level as DLevel
import io.github.sloppytopp.homewatch.detect.Snapshot

/** DEFCON-style readout of the app's own alert state. 5 = all clear ... 1 = confirmed alert; 0 = nothing is scanning. */
fun defconLevel(levels: List<DLevel>, droneAlert: Boolean): Int {
    if (levels.isEmpty() || levels.all { it == DLevel.OFF }) return 0
    val alerts = levels.count { it == DLevel.ALERT }
    val watches = levels.count { it == DLevel.WATCH }
    return when {
        droneAlert || alerts >= 2 -> 1
        alerts == 1 -> 2
        watches >= 2 -> 3
        watches == 1 -> 4
        else -> 5
    }
}

fun defconLevel(s: Snapshot): Int =
    defconLevel(listOf(s.drone.level, s.tracker.level, s.camera.level, s.spam.level, s.rf.level), s.drone.level == DLevel.ALERT)

private fun defconColor(n: Int) = when (n) {
    5 -> Color(0xFF35E07A); 4 -> Color(0xFF9BE035); 3 -> Color(0xFFFFB000); 2 -> Color(0xFFFF6A00); else -> Color(0xFFFF3B3B)
}

/** True when the phone's "remove animations" setting is on: the sweep is held and the rain does not move. */
@Composable
fun reducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

@Composable
fun DefconStrip(s: Snapshot) {
    val n = defconLevel(s)
    val blink by rememberInfiniteTransition(label = "dc").animateFloat(
        1f, 0.35f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "b")
    val still = reducedMotion()
    Row(Modifier.border(1.dp, UiColors.ring).padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("DEFCON", color = UiColors.dim, fontSize = 9.sp, maxLines = 1, softWrap = false, modifier = Modifier.padding(end = 2.dp))
        for (i in 5 downTo 1) {
            val on = i == n
            Box(Modifier.size(16.dp).let { if (on) it.alpha(if (n == 1 && !still) blink else 1f).background(if (Prefs.night) UiColors.alert else defconColor(i)) else it.border(1.dp, UiColors.ring) },
                contentAlignment = Alignment.Center) {
                Text("$i", color = if (on) Color.Black else UiColors.faint, fontSize = 10.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

private const val GLYPHS = "ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜﾝ0123456789"

/** Falling-glyph background. Stateless per frame (no bitmap buffer); stops while the app is not on screen and when animations are off. */
@Composable
fun MatrixRain(modifier: Modifier = Modifier, boost: Boolean = false) {
    if (!Prefs.rain) return
    val still = reducedMotion()
    var tick by remember { mutableLongStateOf(0L) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(still) {
        if (still) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { androidx.compose.runtime.withFrameMillis { tick = it / 70 }; kotlinx.coroutines.delay(30) }
        }
    }
    val paint = remember { android.graphics.Paint().apply { typeface = android.graphics.Typeface.MONOSPACE; isAntiAlias = true } }
    val base = if (Prefs.night) 0.08f else if (boost) 0.38f else 0.2f
    Canvas(modifier.fillMaxSize()) {
        val fs = 14.dp.toPx(); paint.textSize = fs
        val cols = (size.width / fs).toInt() + 1; val rows = (size.height / fs).toInt() + 1
        val rgb = if (Prefs.night) 0x6E2A2A else 0x35E07A
        drawIntoCanvas { c ->
            for (col in 0 until cols) {
                val speed = 1 + (col * 7 % 3); val len = 8 + (col * 13 % 10)
                val head = ((tick * speed + col * 37) % (rows + len + 20)).toInt() - len
                for (k in 0 until len) {
                    val row = head - k; if (row < 0 || row >= rows) continue
                    val a = base * (1f - k / len.toFloat())
                    paint.color = ((a * 255).toInt() shl 24) or (if (k == 0 && !Prefs.night) 0xE0FFE8 else rgb)
                    c.nativeCanvas.drawText(GLYPHS[((col * 31 + row * 17 + (if (still) 0 else (tick / 3).toInt() * (k + 1))) % GLYPHS.length + GLYPHS.length) % GLYPHS.length].toString(), col * fs, (row + 1) * fs, paint)
                }
            }
        }
    }
}
