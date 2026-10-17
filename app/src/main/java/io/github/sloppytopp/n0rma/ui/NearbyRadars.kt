package io.github.sloppytopp.n0rma.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sloppytopp.n0rma.data.Prefs
import io.github.sloppytopp.n0rma.detect.Export
import io.github.sloppytopp.n0rma.detect.Geo
import io.github.sloppytopp.n0rma.detect.Level
import io.github.sloppytopp.n0rma.detect.Snapshot
import io.github.sloppytopp.n0rma.detect.WifiRow
import kotlin.math.cos
import kotlin.math.sin

/** One contact on a scope. [angle] is clockwise degrees from the top and means band/type, never compass direction (see [ScopeLayout]). */
private class Dot(
    val label: String, val angle: Float, val meters: Double, val color: Color,
    val triangle: Boolean = false, val pulse: Boolean = false, val onTap: () -> Unit,
)

/** One line in the list under a scope. */
private class ScopeRow(val title: String, val sub: String, val rssi: Int, val color: Color, val tag: String? = null, val onTap: () -> Unit)

private fun DrawScope.triangle(cx: Float, cy: Float, r: Float, color: Color) {
    val p = Path().apply { moveTo(cx, cy - r); lineTo(cx + r * 0.9f, cy + r * 0.7f); lineTo(cx - r * 0.9f, cy + r * 0.7f); close() }
    drawPath(p, color)
}

/**
 * A small radar: rings = distance, wedges = band or type, a sweep beam that lights each dot as it passes, and a ripple every time fresh
 * scan data arrives ([pingKey] changes). The beam and ripple are the proof the radio is being listened to.
 */
@Composable
private fun Scope(dots: List<Dot>, wedges: List<String>, pingKey: Long, beamOffset: Float) {
    val tm = rememberTextMeasurer()
    val label = TextStyle(color = UiColors.faint, fontSize = 9.sp)
    val name = TextStyle(color = UiColors.dim, fontSize = 9.sp)
    val still = reducedMotion()
    val density = LocalDensity.current.density
    val g = UiColors.good
    val phase by rememberInfiniteTransition(label = "scope").animateFloat(
        0f, 1f, infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart), label = "p")
    val ripple = remember { Animatable(1f) }
    LaunchedEffect(pingKey) {
        if (still || pingKey == 0L) return@LaunchedEffect
        ripple.snapTo(0f)
        ripple.animateTo(1f, tween(1400, easing = LinearEasing))
    }
    val frac = if (still) 0f else (phase + beamOffset) % 1f
    val sweep = frac * 2 * Math.PI - Math.PI / 2   // same convention as RadarView: radians, 0 = 3 o'clock
    val wedgeDeg = 360f / wedges.size

    fun posOf(d: Dot, w: Float): Offset {
        val c = w / 2; val r = w / 2 - 6 * density
        val a = Math.toRadians(d.angle - 90.0)
        val rad = r * ScopeLayout.radius(d.meters)
        return Offset(c + rad * cos(a).toFloat(), c + rad * sin(a).toFloat())
    }

    val nearestFirst = remember(dots) { dots.sortedBy { it.meters } }
    val live by rememberUpdatedState(dots)   // the list is rebuilt on every refresh; the tap detector must not restart each time
    Canvas(Modifier.fillMaxWidth().aspectRatio(1f).pointerInput(Unit) {
        detectTapGestures { at ->
            val hit = live.minByOrNull { (posOf(it, size.width.toFloat()) - at).getDistance() }
            if (hit != null && (posOf(hit, size.width.toFloat()) - at).getDistance() <= 28 * density) hit.onTap()
        }
    }) {
        val w = size.width
        val c = Offset(w / 2, w / 2)
        val r = w / 2 - 6.dp.toPx()
        drawCircle(Brush.radialGradient(listOf(Color(0xD9003418), Color(0xEB000804)), c, r), r, c)
        // wedges: alternate a faint fill so the bands/types read at a glance, divider + name per wedge
        for (i in wedges.indices) {
            if (i % 2 == 1) drawArc(g.copy(alpha = 0.05f), -90f + i * wedgeDeg, wedgeDeg, true, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r))
            val a = Math.toRadians(i * wedgeDeg - 90.0)
            drawLine(UiColors.ring, c, Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat()), 1.dp.toPx())
            val mid = Math.toRadians(i * wedgeDeg + wedgeDeg / 2 - 90.0)
            val m = tm.measure(wedges[i], label)
            drawText(m, topLeft = Offset(c.x + r * 0.9f * cos(mid).toFloat() - m.size.width / 2, c.y + r * 0.9f * sin(mid).toFloat() - m.size.height / 2))
        }
        listOf(0.33f, 0.66f, 1f).forEach { f -> drawCircle(UiColors.ring, r * f, c, style = Stroke(1.dp.toPx())) }
        // ripple: fresh scan data just landed
        if (!still && ripple.value < 1f) drawCircle(g.copy(alpha = 0.45f * (1f - ripple.value)), r * ripple.value, c, style = Stroke(2.dp.toPx()))
        // sweep trail + beam
        if (!still) {
            val sweepDeg = Math.toDegrees(sweep).toFloat()
            for (i in 0 until 30) {
                val al = 0.24f * (1f - i / 30f) * (1f - i / 30f)
                drawArc(g.copy(alpha = al), sweepDeg - (i + 1) * 2.2f, 2.3f, true, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r))
            }
            drawLine(g, c, Offset(c.x + r * cos(sweep).toFloat(), c.y + r * sin(sweep).toFloat()), 1.5.dp.toPx())
        }
        for (d in dots) {
            val o = posOf(d, w)
            val al = if (still) 1f else radarAlpha(sweep, Math.toRadians(d.angle - 90.0), d.pulse)
            if (d.pulse) drawCircle(d.color.copy(alpha = 0.25f * al), (8 + 6 * Math.abs(sin(phase * 8 * Math.PI))).toFloat().dp.toPx(), o)
            drawCircle(d.color.copy(alpha = 0.22f * al), 7.dp.toPx(), o)
            if (d.triangle) triangle(o.x, o.y, 6.dp.toPx(), d.color.copy(alpha = al)) else drawCircle(d.color.copy(alpha = al), 3.5.dp.toPx(), o)
        }
        // name only the three nearest, so a busy scope stays readable
        for (d in nearestFirst.take(3)) {
            val o = posOf(d, w)
            val m = tm.measure(d.label.take(9).uppercase(), name, softWrap = false, maxLines = 1)
            val x = if (o.x > w * 0.6f) o.x - m.size.width - 7.dp.toPx() else o.x + 7.dp.toPx()
            drawText(m, topLeft = Offset(x, o.y - m.size.height / 2))
        }
        // you
        drawRect(Color(0xFF58A6FF), Offset(c.x - 3.5.dp.toPx(), c.y - 3.5.dp.toPx()), Size(7.dp.toPx(), 7.dp.toPx()))
    }
}

/** Compact device list under a scope, strongest first. */
@Composable
private fun ScopeList(rows: List<ScopeRow>, empty: String) {
    if (rows.isEmpty()) { Text(empty, color = UiColors.dim, fontSize = 11.sp); return }
    Column {
        rows.forEach { r ->
            Row(
                Modifier.fillMaxWidth().clickable { r.onTap() }.padding(vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(r.color))
                Column(Modifier.weight(1f)) {
                    Text(r.title, color = if (r.tag != null) r.color else UiColors.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(r.tag, r.sub.ifEmpty { null }).joinToString(" · "), color = UiColors.dim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("${r.rssi}", color = UiColors.dim, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Header(title: String, sub: String) {
    Text(title, color = UiColors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    Text(sub, color = UiColors.dim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

private const val WIFI_LIST_MAX = 40
private const val BLE_LIST_MAX = 60

/** Wi-Fi radar + list. Dots sit in 2.4 / 5 / 6 GHz wedges by channel; distance is estimated from signal. */
@Composable
internal fun WifiRadar(s: Snapshot, now: Long, onPick: (WifiRow) -> Unit) {
    val sorted = s.wifi.sortedByDescending { it.level }
    val dots = sorted.map { w ->
        val color = when {
            w.klass == "drone" || w.klass == "camera" -> UiColors.alert
            w.ssid in Prefs.mySsids -> UiColors.good
            else -> UiColors.neutral
        }
        Dot(w.ssid, ScopeLayout.wifiAngle(w.freq, w.bssid), Geo.rssiToMeters(w.level, -45), color, onTap = { onPick(w) })
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Header("Wi-Fi", "${s.wifi.size} networks" + if (s.wifiAt > 0) " · scan ${ago(now - s.wifiAt)} ago" else "")
        Scope(dots, ScopeLayout.WIFI_BANDS, s.wifiAt, 0f)
        ScopeList(sorted.take(WIFI_LIST_MAX).mapIndexed { i, w ->
            val mine = w.ssid in Prefs.mySsids
            val ch = Export.channel(w.freq).takeIf { it > 0 }?.let { "ch $it" }
            ScopeRow(
                w.ssid, listOfNotNull(ch, w.vendor.ifEmpty { null }).joinToString(" · "), w.level, dots[i].color,
                tag = when { mine -> "YOURS"; w.klass == "camera" -> "CAMERA-LIKE"; w.klass == "drone" -> "DRONE-LIKE"; else -> null },
                onTap = { onPick(w) },
            )
        }, "No networks yet...")
    }
}

/** Bluetooth radar + list. Dots sit in drone / tracker / other wedges; "other" only fills when the show-all switch is on. */
@Composable
internal fun BluetoothRadar(s: Snapshot, inspecting: Boolean, running: Boolean, onInspect: (Boolean) -> Unit, onPick: (addr: String, label: String) -> Unit) {
    val trackerColor = if (s.tracker.level == Level.ALERT) UiColors.alert else UiColors.watch
    val known = s.trackers.map { it.addr }.toSet()
    val rows = ArrayList<Pair<Dot, ScopeRow>>()
    for (d in s.drones.filter { it.via == "Bluetooth" }.sortedByDescending { it.rssi }) {
        val tap = { onPick(d.addr, "Drone broadcast") }
        rows += Dot("DRONE", ScopeLayout.bleAngle(ScopeLayout.BLE_DRONE, d.addr), Geo.rssiToMeters(d.rssi, -59), UiColors.alert, triangle = true, pulse = true, onTap = tap) to
            ScopeRow("Drone (claims Remote ID)", d.info.basicId ?: d.addr, d.rssi, UiColors.alert, "DRONE", tap)
    }
    for (t in s.trackers.sortedByDescending { it.rssi }) {
        val tap = { onPick(t.addr, t.label) }
        val color = if (t.mine) UiColors.good else trackerColor
        rows += Dot(t.label.substringBefore(" SEPARATED"), ScopeLayout.bleAngle(ScopeLayout.BLE_TRACKER, t.addr), Geo.rssiToMeters(t.rssi, -59), color, pulse = !t.mine, onTap = tap) to
            ScopeRow(t.label, "seen ${ago(t.seenS * 1000)}", t.rssi, color, if (t.mine) "YOURS" else "TRACKER", tap)
    }
    if (inspecting) for (r in s.inspect.filter { it.addr !in known }.sortedByDescending { it.rssi }.take(BLE_LIST_MAX)) {
        val label = r.name.ifEmpty { r.company.ifEmpty { "Bluetooth device" } }
        val tap = { onPick(r.addr, label) }
        rows += Dot(label, ScopeLayout.bleAngle(ScopeLayout.BLE_OTHER, r.addr), Geo.rssiToMeters(r.rssi, -59), UiColors.neutral, onTap = tap) to
            ScopeRow(r.name.ifEmpty { "(no name)" }, r.company.ifEmpty { r.addr }, r.rssi, UiColors.neutral, null, tap)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Header("Bluetooth", "${rows.size} devices" + if (s.running) " · ${s.adsSeen} heard" else "")
        Scope(rows.map { it.first }, ScopeLayout.BLE_KINDS, s.adsSeen / 8, 0.5f)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Show every device", color = UiColors.dim, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Switch(checked = inspecting, onCheckedChange = onInspect, enabled = running)
        }
        ScopeList(rows.map { it.second }, if (inspecting) "Nothing heard yet..." else "No trackers or drones in range. Switch on \"Show every device\" to see all of them.")
    }
}
