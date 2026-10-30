package io.github.sloppytopp.n0rma.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sloppytopp.n0rma.data.Prefs
import io.github.sloppytopp.n0rma.detect.Geo
import io.github.sloppytopp.n0rma.detect.Level
import io.github.sloppytopp.n0rma.detect.Snapshot
import kotlin.math.cos
import kotlin.math.sin

private data class Blip(val id: String, val label: String, val meters: Double, val color: Color, val triangle: Boolean = false, val pulse: Boolean = false, val key: String = id)

private fun blips(s: Snapshot): List<Blip> {
    val out = ArrayList<Blip>()
    for (w in s.wifi) {
        val c = when {
            w.klass == "drone" || w.klass == "camera" -> UiColors.alert
            w.ssid in Prefs.mySsids -> UiColors.good
            else -> UiColors.neutral
        }
        out += Blip(w.bssid, w.ssid, Geo.rssiToMeters(w.level, -45), c, key = "wifi:" + w.bssid)
    }
    val tcol = if (s.tracker.level == Level.ALERT) UiColors.alert else UiColors.watch
    for (t in s.trackers) out += Blip(t.addr, (if (t.mine) "Yours: " else "") + t.label.substringBefore(" SEPARATED"), Geo.rssiToMeters(t.rssi, -59), if (t.mine) UiColors.good else tcol, pulse = !t.mine)
    for (d in s.drones) out += Blip(d.addr, "DRONE", Geo.rssiToMeters(d.rssi, if (d.via == "Wi-Fi") -45 else -59), UiColors.alert, triangle = true, pulse = true)
    return out
}

private fun DrawScope.tri(cx: Float, cy: Float, r: Float, color: Color) {
    val p = Path().apply { moveTo(cx, cy - r); lineTo(cx + r * 0.9f, cy + r * 0.7f); lineTo(cx - r * 0.9f, cy + r * 0.7f); close() }
    drawPath(p, color)
}

/** How brightly the sweep beam "paints" a blip: full as it passes, fading over the next revolution. Alerts never drop below 0.7. */
fun radarAlpha(sweep: Double, angle: Double, pulse: Boolean): Float {
    val tau = 2 * Math.PI
    val dl = ((sweep - angle) % tau + tau) % tau
    val f = (0.15 + 0.85 * Math.exp(-dl / 1.7)).toFloat()
    return if (pulse) maxOf(0.7f, f) else f
}

private const val LANE_ROWS = 6

@Composable
fun RadarView(s: Snapshot) {
    val tm = rememberTextMeasurer()
    val label = TextStyle(color = UiColors.dim, fontSize = 11.sp)
    val read = TextStyle(color = UiColors.good, fontSize = 12.sp)
    val still = reducedMotion()
    val compass = rememberCompass()
    val heading by compass.heading
    val phase by rememberInfiniteTransition(label = "sweep").animateFloat(
        0f, 1f, infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart), label = "p")
    val frac = if (still) 0f else phase
    val sw = frac * 2 * Math.PI - Math.PI / 2
    val g = UiColors.good
    val bl = blips(s)
    val now = System.currentTimeMillis()
    val bearingOf = bl.associate { it.key to Bearings.get(it.key, now) }
    val placed = bl.filter { bearingOf[it.key] != null }
    val lane = bl.filter { bearingOf[it.key] == null }.sortedBy { it.meters }
    var finding by remember { mutableStateOf<Pair<String, String>?>(null) }
    var help by remember { mutableStateOf(false) }
    val density = LocalDensity.current.density

    fun scopeXY(b: Blip, w: Float): Offset {
        val c = w / 2; val r = w / 2 - 14 * density
        val a = Math.toRadians(bearingOf[b.key]!! - 90.0)
        val rad = r * Geo.radarFraction(b.meters).toFloat()
        return Offset(c + rad * cos(a).toFloat(), c + rad * sin(a).toFloat())
    }
    fun laneXY(i: Int, b: Blip, w: Float, h: Float): Offset {
        val x = 14 * density + (w - 28 * density) * Geo.radarFraction(b.meters).toFloat()
        val row = i % LANE_ROWS
        return Offset(x, 26 * density + row * (h - 34 * density) / LANE_ROWS)
    }
    fun nearest(pts: List<Pair<Blip, Offset>>, at: Offset): Blip? =
        pts.minByOrNull { (_, o) -> (o - at).getDistance() }?.takeIf { (_, o) -> (o - at).getDistance() <= 30 * density }?.first

    Column {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f).pointerInput(placed) {
            detectTapGestures { at -> nearest(placed.map { it to scopeXY(it, size.width.toFloat()) }, at)?.let { finding = it.key to it.label } }
        }) {
            val w = size.width
            val c = Offset(w / 2, w / 2)
            val r = w / 2 - 14.dp.toPx()
            // scope face
            drawCircle(Brush.radialGradient(listOf(Color(0xD9003418), Color(0xEB000804)), c, r), r, c)
            // rim ticks
            for (i in 0 until 36) {
                val a = i * Math.PI / 18; val l = if (i % 3 == 0) 9.dp.toPx() else 4.dp.toPx()
                drawLine(g.copy(alpha = 0.7f), Offset(c.x + (r + 3.dp.toPx()) * cos(a).toFloat(), c.y + (r + 3.dp.toPx()) * sin(a).toFloat()),
                    Offset(c.x + (r + 3.dp.toPx() + l) * cos(a).toFloat(), c.y + (r + 3.dp.toPx() + l) * sin(a).toFloat()), 1.dp.toPx())
            }
            listOf(0.33f to "VERY CLOSE", 0.66f to "CLOSE", 1f to "FAR").forEach { (f, name) ->
                drawCircle(g.copy(alpha = 0.12f), r * f, c, style = Stroke(5.dp.toPx()))
                drawCircle(UiColors.ring, r * f, c, style = Stroke(1.dp.toPx()))
                drawText(tm, name, Offset(c.x + 4.dp.toPx(), c.y - r * f + 2.dp.toPx()), label)
            }
            drawLine(UiColors.ring, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.dp.toPx())
            drawLine(UiColors.ring, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.dp.toPx())
            drawText(tm, "N", Offset(c.x - 4.dp.toPx(), c.y - r - 1.dp.toPx() + 14.dp.toPx()), label.copy(color = UiColors.alert))
            // where the phone is facing (real compass heading)
            if (compass.available) drawArc(g.copy(alpha = 0.10f), heading - 90f - 22f, 44f, true, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r))
            // sweep trail + beam
            if (!still) {
                val sweepDeg = Math.toDegrees(sw).toFloat()
                for (i in 0 until 44) {
                    val al = 0.26f * (1f - i / 44f) * (1f - i / 44f)
                    drawArc(g.copy(alpha = al), sweepDeg - (i + 1) * 1.6f, 1.7f, true, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r))
                }
            }
            drawLine(g, c, Offset(c.x + r * cos(sw).toFloat(), c.y + r * sin(sw).toFloat()), 2.dp.toPx())
            // contacts with a MEASURED bearing sit at that real compass direction
            for (b in placed) {
                val o = scopeXY(b, w)
                val a = Math.toRadians(bearingOf[b.key]!! - 90.0)
                val al = if (still) 1f else radarAlpha(sw, a, b.pulse)
                if (b.pulse) drawCircle(b.color.copy(alpha = 0.3f * al), (12 + 9 * Math.abs(sin(phase * 8 * Math.PI))).toFloat().dp.toPx(), o)
                drawCircle(b.color.copy(alpha = 0.25f * al), 11.dp.toPx(), o)
                if (b.triangle) tri(o.x, o.y, 9.dp.toPx(), b.color.copy(alpha = al)) else drawCircle(b.color.copy(alpha = al), 5.dp.toPx(), o)
                drawText(tm, b.label.take(14).uppercase(), Offset(o.x + 9.dp.toPx(), o.y - 6.dp.toPx()), label.copy(color = UiColors.dim.copy(alpha = al)))
            }
            // you
            drawRect(Color(0xFF58A6FF), Offset(c.x - 5.dp.toPx(), c.y - 5.dp.toPx()), Size(10.dp.toPx(), 10.dp.toPx()))
            drawRect(Color(0xFF58A6FF), Offset(c.x - 9.dp.toPx(), c.y - 9.dp.toPx()), Size(18.dp.toPx(), 18.dp.toPx()), style = Stroke(1.dp.toPx()))
            // readout
            val near = bl.minOfOrNull { it.meters }
            drawText(tm, "CONTACTS  ${bl.size}", Offset(4.dp.toPx(), 2.dp.toPx()), read)
            drawText(tm, "NEAREST   ${if (near == null) "--" else "${Math.round(near)} M (EST)"}", Offset(4.dp.toPx(), 18.dp.toPx()), read)
            drawText(tm, "BEARINGS  ${placed.size}/${bl.size}", Offset(4.dp.toPx(), 34.dp.toPx()), read)
        }
        // lane: contacts whose direction has not been measured. Position = distance only.
        val laneH = 150.dp
        Canvas(Modifier.fillMaxWidth().height(laneH).pointerInput(lane) {
            detectTapGestures { at -> nearest(lane.mapIndexed { i, b -> b to laneXY(i, b, size.width.toFloat(), size.height.toFloat()) }, at)?.let { finding = it.key to it.label } }
        }) {
            drawRect(g.copy(alpha = 0.05f))
            drawRect(UiColors.ring, style = Stroke(1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
            drawText(tm, "DIRECTION UNKNOWN", Offset(6.dp.toPx(), 3.dp.toPx()), label.copy(color = UiColors.warn))
            drawText(tm, "NEAR", Offset(size.width - 76.dp.toPx(), 3.dp.toPx()), label)
            drawText(tm, "FAR >", Offset(size.width - 38.dp.toPx(), 3.dp.toPx()), label)
            for ((i, b) in lane.withIndex()) {
                val o = laneXY(i, b, size.width, size.height)
                val al = if (still) 1f else (0.7f + 0.3f * sin(phase * 2 * Math.PI)).toFloat()
                drawCircle(b.color.copy(alpha = 0.25f * al), 10.dp.toPx(), o)
                if (b.triangle) tri(o.x, o.y, 8.dp.toPx(), b.color.copy(alpha = al)) else drawCircle(b.color.copy(alpha = al), 5.dp.toPx(), o)
                drawText(tm, b.label.take(12).uppercase(), Offset(if (o.x > size.width * 0.7f) o.x - 8.dp.toPx() - 78.dp.toPx() else o.x + 8.dp.toPx(), o.y - 6.dp.toPx()), label.copy(color = UiColors.dim.copy(alpha = al)), softWrap = false, maxLines = 1)
            }
            if (lane.isEmpty()) drawText(tm, if (bl.isEmpty()) "no contacts - start scanning" else "none - every contact has a measured direction", Offset(6.dp.toPx(), 40.dp.toPx()), label)
        }
        Text("DIST: ESTIMATED FROM SIGNAL  ·  BEARING: ${placed.size} MEASURED / ${lane.size} UNKNOWN", color = UiColors.dim, fontSize = 10.sp)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("TAP A CONTACT TO MEASURE ITS DIRECTION", color = UiColors.good, fontSize = 10.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { help = !help }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp), modifier = Modifier.defaultMinSize(minWidth = 1.dp)) { Text("?", color = UiColors.dim, fontSize = 14.sp) }
        }
        if (help) Text("Distance is estimated from signal strength and can be off indoors. Tap a contact and turn slowly in place for about 40 seconds: the strongest side becomes its bearing (roughly +/-45 degrees). Measured contacts move onto the scope at that real compass direction for 10 minutes (they only hold from where you stood). Contacts with no measured direction stay in the lane, ordered by distance only. The sweep beam is decoration.",
            color = UiColors.faint, fontSize = 11.sp)
    }
    finding?.let { (k, l) -> Finder(k, l) { finding = null } }
}

@Composable
fun DroneMapView(s: Snapshot) {
    val openMaps = rememberMapsOpener()
    val home = Prefs.displayHome
    val fixes = s.fixes.filter { it.lat != null && it.lon != null }
    if (home == null) {
        Text("Set your home location in Settings to see drones plotted on a map.", color = UiColors.dim, fontSize = 13.sp); return
    }
    if (fixes.isEmpty()) {
        Text("No drone is broadcasting a position right now.", color = UiColors.dim, fontSize = 13.sp); return
    }
    val ctx = LocalContext.current
    val tm = rememberTextMeasurer()
    val label = TextStyle(color = UiColors.dim, fontSize = 11.sp)
    val pts = ArrayList<Pair<Double, Double>>()
    fixes.forEach { f ->
        pts += Geo.offsetM(f.lat!!, f.lon!!, home.lat, home.lon)
        if (f.opLat != null && f.opLon != null) pts += Geo.offsetM(f.opLat, f.opLon, home.lat, home.lon)
    }
    val maxD = maxOf(100.0, pts.maxOf { Math.hypot(it.first, it.second) }) * 1.25
    Column {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            val w = size.width; val c = Offset(w / 2, w / 2); val sc = (w / 2 - 8.dp.toPx()) / maxD.toFloat()
            listOf(0.25, 0.5, 1.0).forEach { f ->
                drawCircle(UiColors.ring, (maxD * f).toFloat() * sc, c, style = Stroke(1.dp.toPx()))
                drawText(tm, "${Math.round(maxD * f)} m", Offset(c.x + 4.dp.toPx(), c.y - (maxD * f).toFloat() * sc), label)
            }
            drawText(tm, "N", Offset(c.x - 4.dp.toPx(), 2.dp.toPx()), label)
            drawRect(Color(0xFF58708A).copy(alpha = if (Prefs.night) 0.4f else 0.9f), Offset(c.x - 5.dp.toPx(), c.y - 5.dp.toPx()), Size(10.dp.toPx(), 10.dp.toPx()))
            drawText(tm, "HOME", Offset(c.x + 8.dp.toPx(), c.y + 4.dp.toPx()), label)
            fixes.forEach { f ->
                val (e, n) = Geo.offsetM(f.lat!!, f.lon!!, home.lat, home.lon)
                val x = c.x + e.toFloat() * sc; val y = c.y - n.toFloat() * sc
                if (f.opLat != null && f.opLon != null) {
                    val (oe, on) = Geo.offsetM(f.opLat, f.opLon, home.lat, home.lon)
                    val ox = c.x + oe.toFloat() * sc; val oy = c.y - on.toFloat() * sc
                    drawLine(UiColors.watch, Offset(x, y), Offset(ox, oy), 1.dp.toPx())
                    drawCircle(UiColors.watch, 6.dp.toPx(), Offset(ox, oy))
                }
                tri(x, y, 10.dp.toPx(), UiColors.alert)
            }
        }
        fixes.forEach { f ->
            val d = Geo.distanceM(f.lat!!, f.lon!!, home.lat, home.lon)
            val dir = Geo.compass(Geo.bearingDeg(f.lat, f.lon, home.lat, home.lon))
            Text("Drone ${f.id}: ${Math.round(d)} m $dir of home, alt ${f.alt?.let { Math.round(it).toString() } ?: "?"} m" +
                (if (f.opLat != null) " (dot = claimed operator position, shown live only)" else ""), color = UiColors.text, fontSize = 12.sp)
            TextButton(onClick = { openMaps(f.lat, f.lon!!, "Drone claim ${f.id}") }) { Text("Open this position in your Maps app") }
        }
        Text("Positions are whatever the broadcast claims - Remote ID can be faked.", color = UiColors.faint, fontSize = 11.sp)
    }
}
