package io.github.sloppytopp.n0rma.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sloppytopp.n0rma.data.Prefs
import io.github.sloppytopp.n0rma.detect.Geo
import io.github.sloppytopp.n0rma.detect.Snapshot
import io.github.sloppytopp.n0rma.scan.Monitor
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// Two proximity scopes (Bluetooth, Wi-Fi) with plain-language result cards underneath.
// Distance comes from signal strength; the angle of a dot carries NO meaning (a phone can't measure direction), and the screen says so.

private data class Item(val key: String, val title: String, val sentence: String, val meters: Double, val color: Color, val flagged: Boolean, val bt: Boolean, val detail: String = "", val ssid: String = "")

private val bandEdges = doubleArrayOf(0.0, 3.0, 8.0, 25.0, 60.0)
private val bandFrac = doubleArrayOf(0.0, 0.25, 0.5, 0.75, 0.95)

private fun frac(m: Double): Float {
    if (m >= bandEdges.last()) return bandFrac.last().toFloat()
    for (i in 1 until bandEdges.size) if (m < bandEdges[i]) {
        val t = (m - bandEdges[i - 1]) / (bandEdges[i] - bandEdges[i - 1])
        return (bandFrac[i - 1] + t * (bandFrac[i] - bandFrac[i - 1])).toFloat()
    }
    return 0.95f
}

private fun band(m: Double) = when { m < 3 -> "right here"; m < 8 -> "in this room"; m < 25 -> "in the house"; else -> "outside or far" }

private fun items(s: Snapshot): List<Item> {
    val out = ArrayList<Item>()
    for (w in s.wifi) {
        val m = Geo.rssiToMeters(w.level, -45)
        val mine = w.ssid in Prefs.mySsids || Prefs.isMine("wifi:" + w.bssid)
        val hidden = w.ssid.isBlank() || w.ssid == "(hidden)"
        val cam = w.klass == "camera" || w.klass == "drone"
        val (sentence, col, flag) = when {
            cam && m >= 25 -> Triple("Looks like a camera's Wi-Fi, but it's far away - most likely a neighbor's. Only a concern if it gets stronger.", UiColors.watch, false)
            cam -> Triple("Looks like a camera or drone link, ${band(m)}. Worth a look.", UiColors.alert, true)
            mine -> Triple("Your network.", UiColors.good, false)
            "wifi:" + w.bssid in Prefs.neighbors -> Triple("A neighbor's or far-away network. It was weak in every room of your house walk.", UiColors.neutral, false)
            hidden && m < 8 -> Triple("A hidden network ${band(m)}. It doesn't broadcast a name, so check whether one of your devices makes it.", UiColors.watch, true)
            hidden -> Triple("A hidden network, ${band(m)}.", UiColors.neutral, false)
            else -> Triple("A Wi-Fi network, ${band(m)}.", UiColors.neutral, false)
        }
        out += Item("wifi:" + w.bssid, w.ssid.ifBlank { "(hidden)" }, sentence, m, col, flag, bt = false,
            detail = "MAC (BSSID): ${w.bssid}\nMaker: ${w.vendor.ifEmpty { "unknown (private address)" }}\nSignal: ${w.level} dBm (about ${"%.0f".format(m)} m)\nChannel frequency: ${w.freq} MHz\nSecurity: ${w.caps.ifEmpty { "unknown" }}",
            ssid = w.ssid)
    }
    for (t in s.trackers) {
        val m = Geo.rssiToMeters(t.rssi, -59)
        val name = t.label.substringBefore(" SEPARATED")
        val det = "Address: ${t.addr}\nSignal: ${t.rssi} dBm (about ${"%.0f".format(m)} m)\nHeard for: ${t.seenS} s"
        out += if (t.mine) Item("ble:" + t.addr, name, "Your tracker.", m, UiColors.good, false, true, det)
        else Item("ble:" + t.addr, name, "A tracker that isn't yours, ${band(m)}. Seen for ${maxOf(1, t.seenS / 60)} min.", m, UiColors.watch, true, true, det)
    }
    return out
}

@Composable
private fun Scope(title: String, list: List<Item>, modifier: Modifier) {
    val spin by rememberInfiniteTransition(label = "sweep").animateFloat(0f, 360f, infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart), label = "a")
    val ring = UiColors.dim.copy(alpha = 0.35f)
    val accent = UiColors.good
    Column(modifier, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Text(title, color = UiColors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f).padding(4.dp)) {
            val c = center
            val r = size.minDimension / 2f
            for (f in bandFrac.drop(1).take(3)) drawCircle(ring, r * f.toFloat(), c, style = Stroke(1.dp.toPx()))
            drawCircle(ring, r * 0.98f, c, style = Stroke(1.5.dp.toPx()))
            drawLine(ring, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f)
            drawLine(ring, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)
            rotate(spin, c) {
                drawCircle(
                    brush = Brush.sweepGradient(0f to Color.Transparent, 0.8f to Color.Transparent, 1f to accent.copy(alpha = 0.30f), center = c),
                    radius = r * 0.98f, center = c,
                )
            }
            drawCircle(UiColors.text, 4.dp.toPx(), c) // you
            for (it in list.sortedByDescending { it.flagged }) {
                val h = (it.key.hashCode() and 0x7fffffff) % 3600 / 10.0 // arbitrary angle: spreads dots out, means nothing
                val a = Math.toRadians(h)
                val d = frac(it.meters) * r
                val o = Offset(c.x + (cos(a) * d).toFloat(), c.y + (sin(a) * d).toFloat())
                if (it.flagged) drawCircle(it.color.copy(alpha = 0.25f), 11.dp.toPx(), o)
                drawCircle(it.color, (if (it.flagged) 6 else 4).dp.toPx(), o)
            }
        }
    }
}

@Composable
fun RadarPair(s: Snapshot, maxCards: Int = 10) {
    val hist = remember { mutableStateMapOf<String, List<Int>>() }
    LaunchedEffect(Unit) {
        while (true) {
            val cur = Monitor.snapshot
            cur.wifi.forEach { w -> hist["wifi:" + w.bssid] = ((hist["wifi:" + w.bssid] ?: emptyList()) + w.level).takeLast(5) }
            cur.trackers.forEach { t -> hist["ble:" + t.addr] = ((hist["ble:" + t.addr] ?: emptyList()) + t.rssi).takeLast(5) }
            delay(5000)
        }
    }
    val all = items(s)
    val bt = all.filter { it.bt }
    val wf = all.filter { !it.bt }
    val check = all.count { it.flagged }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            if (s.running) "● Scanning  ·  ${wf.size} Wi-Fi networks  ·  ${bt.size} Bluetooth trackers heard" else "Not scanning. Start a scan above.",
            color = if (s.running) UiColors.good else UiColors.dim, fontSize = 13.sp,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Scope("Bluetooth", bt, Modifier.weight(1f))
            Scope("Wi-Fi", wf, Modifier.weight(1f))
        }
        Text("Closer to the middle = closer to you. Distance is estimated from signal strength and can be off indoors. The angle of a dot means nothing: a phone can't tell direction. Tap Find it on a result to get direction.", color = UiColors.dim, fontSize = 11.sp)
        if (s.running) Text(
            if (check == 0) "Nothing needs your attention." else "$check to check",
            color = if (check == 0) UiColors.good else UiColors.watch, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
        )
        all.sortedWith(compareByDescending<Item> { it.flagged }.thenBy { it.meters }).take(maxCards).forEach { it ->
            val h = hist[it.key] ?: emptyList()
            val trend = if (h.size >= 3) (h.last() - h.first()).let { d -> if (d >= 6) "▲ getting closer" else if (d <= -6) "▼ moving away" else "● steady" } else ""
            Column(Modifier.fillMaxWidth().background(UiColors.card, RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("●", color = it.color, fontSize = 14.sp)
                    Text((if (it.bt) "Bluetooth · " else "Wi-Fi · ") + it.title, color = UiColors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(it.sentence, color = UiColors.text, fontSize = 13.sp)
                if (trend.isNotEmpty()) Text(trend, color = UiColors.dim, fontSize = 12.sp)
                var open by androidx.compose.runtime.saveable.rememberSaveable(it.key) { androidx.compose.runtime.mutableStateOf(false) }
                if (open) Text(it.detail, color = UiColors.dim, fontSize = 12.sp)
                val mine = it.color == UiColors.good
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = { Monitor.finding = (if (it.bt) it.key.removePrefix("ble:") else it.key) to it.title }) { Text("Find it", fontSize = 13.sp) }
                    if (!mine) TextButton(onClick = {
                        if (it.bt) Prefs.markMine(it.key.removePrefix("ble:")) else if (it.ssid.isNotBlank() && it.ssid != "(hidden)") Prefs.learnSsid(it.ssid) else Prefs.markMine(it.key)
                        Monitor.refresh()
                    }) { Text("This is mine", fontSize = 13.sp) }
                    if (!it.bt) TextButton(onClick = { if (it.key in Prefs.watched) Prefs.unwatch(it.key) else Prefs.watch(it.key) }) {
                        Text(if (it.key in Prefs.watched) "Stop watching" else "Watch it", fontSize = 13.sp)
                    }
                    TextButton(onClick = { open = !open }) { Text(if (open) "Hide details" else "Details", fontSize = 13.sp) }
                }
            }
        }
    }
}
