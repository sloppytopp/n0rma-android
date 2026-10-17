package io.github.sloppytopp.n0rma.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sloppytopp.n0rma.detect.Level
import io.github.sloppytopp.n0rma.detect.Snapshot
import io.github.sloppytopp.n0rma.scan.Monitor
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live channel-by-channel picture of the Wi-Fi the phone can hear: bar height = how crowded the channel is, a thin marker holds the recent
 * peak. Bars glide to new values every frame between scans, so it visibly breathes while the radio is being listened to.
 */
@Composable
internal fun SpectrumStrip(s: Snapshot) {
    val bins = Spectrum.bins(s.wifi)
    val still = reducedMotion()
    val shown = remember { HashMap<String, Float>() }
    val peak = remember { HashMap<String, Float>() }
    var tick by remember { mutableIntStateOf(0) }
    val cur by rememberUpdatedState(bins)
    LaunchedEffect(Unit) {
        while (true) {
            delay(60)
            for (b in cur) {
                val sh = shown[b.key] ?: 0f
                val n = if (still) b.load else sh + (b.load - sh) * 0.25f
                shown[b.key] = n
                peak[b.key] = maxOf(n, (peak[b.key] ?: 0f) * 0.985f)
            }
            tick++
        }
    }
    val tm = rememberTextMeasurer()
    val small = TextStyle(color = UiColors.faint, fontSize = 8.sp)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Wi-Fi spectrum", color = UiColors.text, fontSize = 14.sp)
        Text(Spectrum.busiest(bins) ?: if (s.wifiAt > 0) "No networks heard" else "Waiting for the first Wi-Fi scan...", color = UiColors.dim, fontSize = 11.sp)
        Canvas(Modifier.fillMaxWidth().height(96.dp)) {
            @Suppress("UNUSED_VARIABLE") val frame = tick   // reading it here redraws the bars on every tick
            val sections = bins.map { it.section }.distinct()
            val gap = 10.dp.toPx()
            val bw = (size.width - gap * (sections.size - 1)) / bins.size
            val labelH = 12.dp.toPx()
            val chartH = size.height - labelH
            var x = 0f
            var prev = -1
            for (b in bins) {
                if (prev != -1 && b.section != prev) x += gap
                if (b.section != prev) drawText(tm, listOf("2.4 GHz", "5 GHz", "6 GHz")[b.section], Offset(x, 0f), small)
                prev = b.section
                val v = shown[b.key] ?: 0f
                val color = when { v < 0.4f -> UiColors.good; v < 0.75f -> UiColors.watch; else -> UiColors.alert }
                val h = maxOf(v * chartH, 1.5.dp.toPx())
                drawRect(color.copy(alpha = if (v > 0.01f) 0.9f else 0.25f), Offset(x + 0.5f, chartH - h), Size(bw - 1f, h))
                val pk = peak[b.key] ?: 0f
                if (pk > 0.03f) drawRect(UiColors.text.copy(alpha = 0.8f), Offset(x + 0.5f, chartH - pk * chartH - 1.5.dp.toPx()), Size(bw - 1f, 1.5.dp.toPx()))
                if (b.ch in setOf(1, 6, 11, 36, 100, 149) || (b.section == 2 && b.ch == bins.first { it.section == 2 }.ch)) {
                    val m = tm.measure(b.ch.toString(), small)
                    drawText(m, topLeft = Offset(x + bw / 2 - m.size.width / 2, chartH + 1.dp.toPx()))
                }
                x += bw
            }
        }
    }
}

/** Bluetooth signal strength over the last minute, one column per second: a device shows as a horizontal streak, one walking closer climbs. */
@Composable
internal fun BleWaterfall(s: Snapshot, inspecting: Boolean) {
    val buf = remember { WaterfallBuffer() }
    var tick by remember { mutableIntStateOf(0) }
    val cur by rememberUpdatedState(s)
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            val c = cur
            val trackerAddrs = c.trackers.map { it.addr }.toSet()
            buf.push(
                c.trackers.filter { it.agoS <= 3 }.map { it.rssi } +
                    c.drones.filter { it.via == "Bluetooth" && it.agoS <= 3 }.map { it.rssi } +
                    c.inspect.filter { it.addr !in trackerAddrs && it.ageS <= 3 }.map { it.rssi },
            )
            tick++
        }
    }
    val strong = buf.row(-60)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Bluetooth activity (last minute)", color = UiColors.text, fontSize = 14.sp)
        Text(if (inspecting) "Each streak is a device. Higher = stronger signal. A streak climbing means it is getting closer."
            else "Showing trackers and drones only. Switch on \"Show every device\" to see all Bluetooth.", color = UiColors.dim, fontSize = 11.sp)
        Canvas(Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(6.dp)).background(UiColors.card)) {
            @Suppress("UNUSED_VARIABLE") val frame = tick
            val cw = size.width / buf.cols
            val rh = size.height / buf.rows
            for (c in 0 until buf.cols) for (r in 0 until buf.rows) {
                val n = buf.cell(c, r)
                if (n == 0) continue
                val color = if (r >= strong) UiColors.watch else UiColors.good
                drawRect(color.copy(alpha = (0.35f + 0.25f * n).coerceAtMost(1f)), Offset(c * cw, size.height - (r + 1) * rh), Size(cw + 0.5f, rh - 0.5f))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("60 s ago", color = UiColors.faint, fontSize = 10.sp)
            Text("now", color = UiColors.faint, fontSize = 10.sp)
        }
    }
}

/**
 * The 30-second sweep and its receipt: after the sweep, lines appear one by one saying what was heard and, just as important, what a phone
 * cannot check. [enabled] is false when scanning is off (the Nearby tab); [beforeStart] lets the Status tab start scanning first.
 */
@Composable
internal fun SweepPanel(s: Snapshot, enabled: Boolean = true, beforeStart: () -> Unit = {}) {
    val now = rememberNow()
    var start by remember { mutableLongStateOf(0L) }
    var adsAtStart by remember { mutableLongStateOf(0L) }
    LaunchedEffect(start) {
        if (start == 0L) return@LaunchedEffect
        delay(30_000)
        val e = Monitor.snapshot
        val end = System.currentTimeMillis()
        fun hot(l: Level) = l == Level.WATCH || l == Level.ALERT
        Monitor.lastReceipt = SweepReceipt.build(
            at = end, wifiScanned = e.wifiAt >= end - 60_000, wifiCount = e.wifi.size, wifiSuspicious = e.wifi.count { it.klass == "camera" || it.klass == "drone" },
            bleSignals = e.adsSeen - adsAtStart, trackers = e.trackers.size, drones = e.drones.size,
            flagged = listOf(e.drone, e.tracker, e.camera).count { hot(it.level) }, rfOn = e.rf.level != Level.OFF,
        )
        start = 0L
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (start > 0) {
            Meter(((now - start) / 30_000f).coerceIn(0f, 1f), "Sweeping... ${(30 - (now - start) / 1000).coerceAtLeast(0)} s left - hold still, let it listen")
        } else {
            Button(
                onClick = { beforeStart(); adsAtStart = Monitor.snapshot.adsSeen; start = System.currentTimeMillis() },
                enabled = enabled, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = UiColors.buttonBg, contentColor = UiColors.buttonFg),
            ) { Text("Sweep now (30 seconds)") }
            if (!enabled) Text("Start scanning on the Status tab to run a sweep.", color = UiColors.dim, fontSize = 11.sp)
        }
        Monitor.lastReceipt?.let { ReceiptCard(it) }
    }
}

@Composable
private fun ReceiptCard(r: Receipt) {
    val still = reducedMotion()
    val lines = r.checked.map { true to it } + r.notChecked.map { false to it }
    var shown by remember(r) { mutableIntStateOf(if (still) lines.size else 0) }
    LaunchedEffect(r) { while (shown < lines.size) { delay(400); shown++ } }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(UiColors.card).padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("Sweep receipt · " + SimpleDateFormat("h:mm a", Locale.US).format(Date(r.at)) + " · " +
            if (r.flagged == 0) "nothing unusual" else "${r.flagged} flagged - see the Status tiles", color = UiColors.text, fontSize = 13.sp)
        lines.take(shown).forEachIndexed { i, (ok, text) ->
            if (i == r.checked.size) Text("Not checked (a phone cannot see these):", color = UiColors.warn, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            Text((if (ok) "✓ " else "○ ") + text, color = if (ok) UiColors.text else UiColors.dim, fontSize = 12.sp)
        }
    }
}
