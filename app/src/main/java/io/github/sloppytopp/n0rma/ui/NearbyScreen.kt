package io.github.sloppytopp.n0rma.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.sloppytopp.n0rma.detect.DirectionFinder
import io.github.sloppytopp.n0rma.detect.FindAid
import io.github.sloppytopp.n0rma.detect.Geo
import io.github.sloppytopp.n0rma.detect.Trend
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sloppytopp.n0rma.data.Prefs
import io.github.sloppytopp.n0rma.scan.Monitor
import kotlinx.coroutines.delay

@Composable
fun NearbyScreen() {
    val ctx = LocalContext.current
    val s = Monitor.snapshot
    val now = rememberNow()
    var finding by remember { mutableStateOf<Pair<String, String>?>(null) }
    var wifiDetail by remember { mutableStateOf<io.github.sloppytopp.n0rma.detect.WifiRow?>(null) }
    val wifiArrivals = remember { ArrivalTracker() }
    val bleArrivals = remember { ArrivalTracker() }
    // Wi-Fi is baselined once its first scan lands; trackers and drones once Bluetooth scanning is running. After that, anything that turns up is called out.
    wifiArrivals.update(s.wifi.map { it.bssid }, now, ready = s.wifiAt > 0)
    bleArrivals.update(s.trackers.map { it.addr } + s.drones.filter { it.via == "Bluetooth" }.map { it.addr }, now, ready = s.running)
    DisposableEffect(Unit) { onDispose { Monitor.setInspect(ctx, false) } } // unfiltered scan only while this screen is open

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("What's around you", color = UiColors.text, fontSize = 20.sp)
        if (!s.running) Text("Start scanning on the Status tab to fill these lists.", color = UiColors.warn, fontSize = 13.sp)

        if (s.wifi.isEmpty() && s.camera.level == io.github.sloppytopp.n0rma.detect.Level.OFF) Text(s.camera.message, color = UiColors.dim, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) { WifiRadar(s, now, wifiArrivals) { wifiDetail = it } }
            Column(Modifier.weight(1f)) { BluetoothRadar(s, now, bleArrivals, Monitor.inspecting, s.running, { Monitor.setInspect(ctx, it) }) { addr, label -> finding = addr to label } }
        }
        Text("Dots are placed by Wi-Fi band and channel, or by Bluetooth type - not by direction, because a phone radio cannot tell which way a signal comes from. " +
            "Distance is estimated from signal strength (rings at about 5 m and 20 m). The beam lights each dot as it passes, and a ring ripples out whenever fresh scan data arrives.",
            color = UiColors.faint, fontSize = 11.sp)
        Text("A ring around a dot and a NEW label mean it appeared after you opened this screen (Wi-Fi networks, trackers and drones).", color = UiColors.faint, fontSize = 11.sp)
        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SpectrumStrip(s)
            BleWaterfall(s, Monitor.inspecting)
            SweepPanel(s, enabled = s.running)
        }
        Text("Tap a dot or a row to hunt for it with the hot/cold finder. Wi-Fi: Android only allows a scan every ~30 s. Bluetooth: phones and watches change their address every few minutes, so one device can appear more than once.",
            color = UiColors.faint, fontSize = 11.sp)
    }

    finding?.let { (addr, label) -> Finder(addr, label) { finding = null } }
    wifiDetail?.let { w ->
        val mine = w.ssid in Prefs.mySsids
        AlertDialog(
            containerColor = UiColors.dialogBg, titleContentColor = UiColors.text, textContentColor = UiColors.text,
            onDismissRequest = { wifiDetail = null }, title = { Text(w.ssid) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("MAC (BSSID): ${w.bssid}\nMaker: ${w.vendor.ifEmpty { "unknown" }}\nSignal: ${w.level} dBm\nSecurity: ${w.caps.ifEmpty { "unknown" }}" +
                        (if (w.klass == "camera" || w.klass == "drone") "\nLooks like a ${w.klass} (by name or maker)" else ""), fontSize = 13.sp)
                    Text("Where is it? Two in-house ways to find out, using only this phone's own radio - no account, nothing sent anywhere:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { wifiDetail = null; finding = "wifi:${w.bssid}" to w.ssid }) { Text("Find it now (hot/cold, right here)") }
                    TextButton(onClick = { Monitor.requestTab = 3; wifiDetail = null }) { Text("Walk a Survey -> real position + Google Earth pin") }
                    TextButton(onClick = { if (mine) Prefs.forgetSsid(w.ssid) else Prefs.learnSsid(w.ssid); wifiDetail = null }) {
                        Text(if (mine) "Not mine - stop showing it as yours" else "This is my network")
                    }
                    Text("Advanced: WiGLE is a public database of networks other people have logged. A network WiGLE has never seen near you is worth a closer look - but it needs a free WiGLE account, and you " +
                        "have to paste the MAC into its search box yourself (WiGLE doesn't support filling it in for us). Only worth it once the two options above haven't settled it.", fontSize = 11.sp, color = UiColors.faint)
                    TextButton(onClick = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("MAC", w.bssid))
                        try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://wigle.net/search")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                    }) { Text("Copy MAC and open wigle.net/search", fontSize = 12.sp) }
                }
            },
            confirmButton = { TextButton(onClick = { wifiDetail = null }) { Text("Close") } },
        )
    }
}

/** Hot/cold finder with a warmer/colder trend and an optional turn-in-place compass sweep. */
@Composable
internal fun Finder(addr: String, label: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val compass = rememberCompass()
    val heading by compass.heading
    val hasCompass = compass.available
    val history = remember { androidx.compose.runtime.mutableStateListOf<Int>() }
    val graph = remember { androidx.compose.runtime.mutableStateListOf<Int>() }   // longer memory for the live graph
    var best by remember { mutableStateOf<Int?>(null) }
    var buzz by remember { mutableStateOf(false) }
    var beep by remember { mutableStateOf(false) }
    var sweeping by remember { mutableStateOf(false) }
    var sweepAt by remember { mutableLongStateOf(0L) }
    val finder = remember { DirectionFinder() }
    var answer by remember { mutableStateOf<DirectionFinder.Result?>(null) }
    var answered by remember { mutableStateOf(false) }

    val isWifi = addr.startsWith("wifi:")
    val bssid = addr.removePrefix("wifi:")

    fun rssiNow(): Pair<Int?, Long?> {
        val s = Monitor.snapshot
        if (isWifi) {
            if (s.wifiAt == 0L) return null to null
            return s.wifi.firstOrNull { it.bssid.equals(bssid, ignoreCase = true) }?.level to (System.currentTimeMillis() - s.wifiAt) / 1000
        }
        s.trackers.firstOrNull { it.addr == addr }?.let { return it.rssi to it.agoS }
        s.inspect.firstOrNull { it.addr == addr }?.let { return it.rssi to it.ageS }
        s.drones.firstOrNull { it.addr == addr }?.let { return it.rssi to it.agoS }
        return null to null
    }

    // Wi-Fi scans land roughly every 15-30 s (Android throttles them), not every second like BLE - ask for the faster cadence while this is open.
    DisposableEffect(isWifi) {
        val was = Monitor.fastWifi
        if (isWifi) Monitor.fastWifi = true
        onDispose { if (isWifi) Monitor.fastWifi = was }
    }

    LaunchedEffect(addr) {
        while (true) {
            Monitor.refresh()
            val (r, age) = rssiNow()
            if (r != null && age != null && age <= (if (isWifi) 40 else 1)) {
                history.add(r); if (history.size > 12) history.removeAt(0)
                graph.add(r); if (graph.size > 60) graph.removeAt(0)
                best = FindAid.best(listOf(r), best)
                if (sweeping) finder.add(heading.toDouble(), r)
            }
            if (sweeping && System.currentTimeMillis() - sweepAt > 40_000) { sweeping = false; answer = finder.result(); answered = true; answer?.takeIf { it.confident }?.let { Bearings.put(addr, it.bearingDeg) } }
            delay(500)
        }
    }

    val (rssi, age) = rssiNow()
    val trend = Trend.of(history.toList())

    // vibrate faster as it gets closer, so you can keep your eyes up while walking
    LaunchedEffect(buzz) {
        if (!buzz) return@LaunchedEffect
        val vib = ctx.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        while (true) {
            val r = history.lastOrNull()
            val gap = r?.let { FindAid.pulseGapMs(it) }
            if (gap != null) vib?.vibrate(android.os.VibrationEffect.createOneShot(40, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            delay(gap ?: 500L)
        }
    }
    // soft beeps that speed up as it gets closer; off by default and never offered in Discreet mode
    LaunchedEffect(beep) {
        if (!beep) return@LaunchedEffect
        val tone = try { android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 40) } catch (_: RuntimeException) { null }
        try {
            while (true) {
                val gap = history.lastOrNull()?.let { FindAid.pulseGapMs(it) }
                if (gap != null) tone?.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 60)
                delay(gap ?: 500L)
            }
        } finally { tone?.release() }
    }
    AlertDialog(
        containerColor = UiColors.dialogBg, titleContentColor = UiColors.text, textContentColor = UiColors.text,
        onDismissRequest = onClose,
        title = { Text("Finding: $label") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(addr, fontSize = 12.sp)
                if (graph.size >= 2) {
                    RssiGraph(graph.toList(), best)
                    best?.let { b -> rssi?.let { Text("Best so far: $b dBm. " + FindAid.versusBest(it, b), fontSize = 12.sp) } }
                }
                TextButton(onClick = { buzz = !buzz }) { Text(if (buzz) "Vibration guide ON - tap to turn off" else "Vibrate faster as I get closer") }
                if (!Prefs.discreet) TextButton(onClick = { beep = !beep }) { Text(if (beep) "Beep guide ON - tap to turn off" else "Beep faster as I get closer") }
                if (rssi == null) Text("Not heard right now. Wait a few seconds, or move closer.")
                else {
                    Meter(((rssi + 100) / 60f), "$rssi dBm - " + when { rssi >= -50 -> "very hot: it's right here"; rssi >= -62 -> "hot"; rssi >= -75 -> "warm"; else -> "cold" } +
                        (age?.let { if (it > (if (isWifi) 20 else 3)) " (last scan ${it}s ago)" else "" } ?: ""))
                    Text(when (trend) {
                        "warmer" -> "▲ Getting WARMER - keep going this way."
                        "colder" -> "▼ Getting COLDER - turn around."
                        "steady" -> "● No change yet - take a few steps in any direction."
                        else -> "Take a few steps: the arrow will tell you if you're getting warmer or colder."
                    }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    if (rssi < -78) Text("Heads up: at $rssi dBm it is faint - most likely in another building or far room. Direction hints get reliable once you're closer than about -70 dBm, so first walk toward wherever it gets stronger.", fontSize = 12.sp)
                }

                val likely = remember(addr) { io.github.sloppytopp.n0rma.detect.RoomBook.likelyRoom("ble:$addr", io.github.sloppytopp.n0rma.data.Store.latestScans()) }
                likely?.let { l ->
                    Text("From your room sweeps: probably in ${l.room} (${Math.round(l.rssi)} dBm" + (l.marginDb?.let { ", ${Math.round(it)} dB louder than the next room" } ?: ", heard only there") + ").",
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                val mineNow = addr in Prefs.myDevices
                TextButton(onClick = { if (mineNow) Prefs.unmarkMine(addr) else Prefs.markMine(addr); Monitor.refresh() }) {
                    Text(if (mineNow) "Marked as YOURS - tap to flag it again" else "This is mine - stop flagging it")
                }
                if (isWifi) {
                    Text("Wi-Fi only refreshes every 15-30 seconds (a phone limit, not ours), so the compass sweep doesn't work well here - use warmer/colder above, or get a real position estimate:", fontSize = 12.sp)
                    TextButton(onClick = { Monitor.requestTab = 3; onClose() }) { Text("Open Survey walk (Radar tab) -> export to Google Earth") }
                } else {
                Text("Compass sweep", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                if (hasCompass) CompassDial(heading, finder, answer?.bearingDeg, compass.accuracy.value <= 1)
                if (!hasCompass) Text("This phone has no compass sensor, so use the warmer/colder arrow.", fontSize = 12.sp)
                else if (sweeping) {
                    val left = (40 - (System.currentTimeMillis() - sweepAt) / 1000).coerceAtLeast(0)
                    Text("Turn slowly in a full circle, phone flat in front of you. ${finder.coveredSectors()}/8 directions covered - $left s left. The orange wedges grow toward the strongest signal.", fontSize = 12.sp)
                    TextButton(onClick = { sweeping = false; answer = finder.result(); answered = true; answer?.takeIf { it.confident }?.let { Bearings.put(addr, it.bearingDeg) } }) { Text("Finish now") }
                } else {
                    Text("Hold the phone flat, then turn slowly in place for a full circle. Your body blocks the signal, so it is usually strongest when you face the tracker.", fontSize = 12.sp)
                    answer?.let { r ->
                        Text(if (r.confident) "Strongest signal is toward ${Geo.compass(r.bearingDeg)} (about ${r.bearingDeg.toInt()}°). Walk that way a few steps, then check the warmer/colder arrow."
                        else "No clear direction (signal varied only ${"%.1f".format(r.spreadDb)} dB around the circle). Walk a few steps and sweep again, or rely on warmer/colder.", fontSize = 13.sp)
                    }
                    if (answered && answer == null) Text("Not enough readings - turn slower, and make sure the tracker is being heard (it advertises about every 2 seconds).", fontSize = 12.sp)
                    TextButton(onClick = { answered = false; answer = null; finder.let { f -> repeat(0) {} }; sweepAt = System.currentTimeMillis(); sweeping = true; resetFinder(finder) }) { Text(if (answered) "Sweep again" else "Start compass sweep") }
                }
                }
                Text("If you find something you don't own, leave it in place, photograph it, and contact local law enforcement.", fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
    )
}

private fun resetFinder(f: DirectionFinder) = f.reset()
