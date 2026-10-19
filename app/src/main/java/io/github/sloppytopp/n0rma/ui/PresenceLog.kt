package io.github.sloppytopp.n0rma.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sloppytopp.n0rma.data.Prefs
import io.github.sloppytopp.n0rma.data.Store
import io.github.sloppytopp.n0rma.detect.PresenceSource
import io.github.sloppytopp.n0rma.detect.PresenceSummary
import io.github.sloppytopp.n0rma.scan.Monitor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val DAY = 86_400_000L

private fun dur(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60} h ${m % 60} min" else if (m >= 1) "$m min" else "under a minute"
}

/** Hidden and private-address Wi-Fi sources over the last 24 hours: when each one was there, as a strip you can read at a glance. */
@Composable
fun PresenceLog() {
    val now = rememberNow()
    val minute = now / 60_000
    val events = remember(minute) { Store.presenceSince(now - DAY) }
    val scanning = Monitor.snapshot.running
    val scanned = PresenceSummary.scanningMs(events, now, DAY, scanning)
    val sources = PresenceSummary.build(events, now, DAY, scanning)
        .filter { !Prefs.isMine(it.key) }
        .sortedWith(compareByDescending<PresenceSource> { it.hidden }.thenByDescending { it.appearances }.thenByDescending { it.lastSeen })
    val f = SimpleDateFormat("HH:mm", Locale.getDefault())

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Presence log (24 h)", color = UiColors.text, fontSize = 16.sp)
        Text("Hidden networks and networks with a private (changing-type) address, and when each was in range. It records only while N0RMA is scanning: " +
            "N0RMA scanned ${dur(scanned)} of the last 24 hours, so a quiet strip may just mean it wasn't listening.", color = UiColors.dim, fontSize = 12.sp)
        if (sources.isEmpty()) Text("Nothing logged yet. Leave N0RMA scanning and this fills in.", color = UiColors.dim, fontSize = 13.sp)
        sources.take(12).forEach { src ->
            var open by remember(src.key) { mutableStateOf(false) }
            Column(
                Modifier.fillMaxWidth().background(UiColors.card, RoundedCornerShape(12.dp)).clickable { open = !open }.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val maker = io.github.sloppytopp.n0rma.detect.WifiClassifier.vendorOf(src.key.removePrefix("wifi:"))
                Text(src.label + (if (src.hidden && src.label != "(hidden)") "  (hides its name at times)" else "") + (maker?.let { "  ·  ${it.name}" + if (it.privateCopy) " (private address)" else "" } ?: ""), color = UiColors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("${src.key.removePrefix("wifi:")}  ·  ${src.appearances} ${if (src.appearances == 1) "appearance" else "appearances"}  ·  here ${dur(src.totalMs)}  ·  last seen ${dur(now - src.lastSeen)} ago",
                    color = UiColors.dim, fontSize = 11.sp)
                val bar = UiColors.good; val track = UiColors.ring
                Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                    drawRect(track.copy(alpha = 0.35f), Offset.Zero, size)
                    src.intervals.forEach { r ->
                        val x0 = ((r.first - (now - DAY)).toFloat() / DAY * size.width).coerceIn(0f, size.width)
                        val x1 = ((r.last - (now - DAY)).toFloat() / DAY * size.width).coerceIn(0f, size.width)
                        drawRect(bar, Offset(x0, 0f), Size(maxOf(x1 - x0, 2.dp.toPx()), size.height))
                    }
                }
                Text("24 hours ago  →  now", color = UiColors.faint, fontSize = 10.sp)
                if (open) src.intervals.sortedByDescending { it.first }.take(12).forEach {
                    Text("${f.format(Date(it.first))} to ${f.format(Date(it.last))}  (${dur(it.last - it.first)})", color = UiColors.text, fontSize = 12.sp)
                }
            }
        }
        if (sources.size > 12) Text("...and ${sources.size - 12} more.", color = UiColors.faint, fontSize = 12.sp)
    }
}
