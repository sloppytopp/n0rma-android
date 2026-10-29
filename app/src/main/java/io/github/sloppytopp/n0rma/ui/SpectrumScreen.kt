package io.github.sloppytopp.n0rma.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sloppytopp.n0rma.detect.SpectrumMath
import io.github.sloppytopp.n0rma.scan.SpectrumRun

private fun heat(t: Float): Int {            // black -> blue -> cyan -> yellow -> white
    val x = t.coerceIn(0f, 1f)
    val stops = arrayOf(floatArrayOf(0f, 0f, 0f, 0f), floatArrayOf(0.25f, 0f, 0f, 0.6f), floatArrayOf(0.5f, 0f, 0.7f, 0.9f), floatArrayOf(0.75f, 1f, 0.9f, 0f), floatArrayOf(1f, 1f, 1f, 1f))
    for (i in 1 until stops.size) if (x <= stops[i][0]) {
        val a = stops[i - 1]; val b = stops[i]; val f = (x - a[0]) / (b[0] - a[0])
        fun ch(k: Int) = ((a[k] + (b[k] - a[k]) * f) * 255).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(1) shl 16) or (ch(2) shl 8) or ch(3)
    }
    return 0xFFFFFFFF.toInt()
}

private fun mhz(hz: Double) = "%.3f MHz".format(hz / 1e6)

/** The RTL-SDR spectrum analyzer: live spectrum, waterfall, tap-to-tune, peak list and band names. Needs the SDR build and a dongle. */
@Composable
fun SpectrumScreen() {
    val ctx = LocalContext.current
    val run = SpectrumRun
    var freqText by remember { mutableStateOf("100.000") }
    val d = run.db
    @Suppress("UNUSED_EXPRESSION") run.frame

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Spectrum", color = UiColors.text, fontSize = 20.sp)
        Text("Live signal strength by frequency from an RTL-SDR dongle plugged into this phone. Nothing leaves the phone. It cannot decode what a signal says or tell you what it is; " +
            "band names are the usual allocations only. A dongle covers about 24 MHz to 1.7 GHz, so Wi-Fi and Bluetooth (2.4 / 5 GHz) are not visible here.", color = UiColors.dim, fontSize = 12.sp)
        Button(
            onClick = { if (run.running) run.stop() else run.start(ctx) }, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = UiColors.buttonBg, contentColor = UiColors.buttonFg),
        ) { Text(if (run.running) "Stop" else "Start the spectrum") }
        Text(run.status + if (run.running && d.isNotEmpty()) "  -  ${mhz(run.centerHz)}, 2.4 MHz wide" else "", color = if (run.status == "Running") UiColors.good else UiColors.warn, fontSize = 12.sp)

        if (d.isNotEmpty()) {
            val lo = d.sortedArray()[d.size / 20] - 4f
            val hi = maxOf(d.max() + 4f, lo + 40f)
            val grid = UiColors.faint
            Canvas(Modifier.fillMaxWidth().height(200.dp).pointerInput(Unit) {
                detectTapGestures { o -> run.tune(run.centerHz + (o.x / size.width - 0.5) * SpectrumRun.RATE) }
            }) {
                for (k in 0..4) drawLine(grid.copy(alpha = 0.25f), Offset(0f, size.height * k / 4), Offset(size.width, size.height * k / 4), 1f)
                for (k in 0..4) drawLine(grid.copy(alpha = 0.25f), Offset(size.width * k / 4, 0f), Offset(size.width * k / 4, size.height), 1f)
                val p = Path()
                val step = maxOf(1, d.size / 600)
                var i = 0
                while (i < d.size) {
                    val x = size.width * i / (d.size - 1)
                    val y = size.height * (1f - ((d[i] - lo) / (hi - lo)).coerceIn(0f, 1f))
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                    i += step
                }
                drawPath(p, UiColors.good, style = Stroke(2f))
                drawLine(UiColors.watch, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 1.5f)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("%.2f".format((run.centerHz - SpectrumRun.RATE / 2) / 1e6), color = UiColors.faint, fontSize = 10.sp)
                Text("${"%.3f".format(run.centerHz / 1e6)} MHz   ${lo.toInt()} to ${hi.toInt()} dB", color = UiColors.dim, fontSize = 10.sp)
                Text("%.2f".format((run.centerHz + SpectrumRun.RATE / 2) / 1e6), color = UiColors.faint, fontSize = 10.sp)
            }
            val rows = run.waterfall
            if (rows.isNotEmpty()) {
                val bmp = remember(run.frame) {
                    val b = Bitmap.createBitmap(SpectrumRun.WF_COLS, SpectrumRun.WF_ROWS, Bitmap.Config.ARGB_8888)
                    val px = IntArray(SpectrumRun.WF_COLS * SpectrumRun.WF_ROWS) { 0xFF000000.toInt() }
                    rows.forEachIndexed { r, row ->
                        val y = SpectrumRun.WF_ROWS - rows.size + r                  // newest row at the bottom... drawn reversed below
                        for (c in row.indices) px[y * SpectrumRun.WF_COLS + c] = heat((row[c] - lo) / (hi - lo))
                    }
                    b.setPixels(px, 0, SpectrumRun.WF_COLS, 0, 0, SpectrumRun.WF_COLS, SpectrumRun.WF_ROWS); b
                }
                Canvas(Modifier.fillMaxWidth().height(150.dp).pointerInput(Unit) {
                    detectTapGestures { o -> run.tune(run.centerHz + (o.x / size.width - 0.5) * SpectrumRun.RATE) }
                }) { drawImage(bmp.asImageBitmap(), dstOffset = IntOffset.Zero, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Low) }
            }
            Text("Tap the graph or waterfall to tune to that frequency.", color = UiColors.faint, fontSize = 11.sp)
        }

        Text("Tune", color = UiColors.text, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(value = freqText, onValueChange = { freqText = it }, label = { Text("MHz") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = { freqText.replace(',', '.').toDoubleOrNull()?.let { run.tune(it * 1e6) } }) { Text("Go") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf("FM 100" to 100.0, "Air 125" to 125.0, "Weather 162.55" to 162.55, "433.92" to 433.92, "Pagers 931" to 931.0, "915" to 915.0, "ADS-B 1090" to 1090.0).forEach { (n, f) ->
                TextButton(onClick = { freqText = "%.3f".format(f); run.tune(f * 1e6) }) { Text(n, fontSize = 12.sp) }
            }
        }
        Text("Gain: %.1f dB (fixed, no auto-gain, so levels stay comparable)".format(SpectrumRun.GAINS[run.gainIdx] / 10.0), color = UiColors.dim, fontSize = 12.sp)
        Slider(value = run.gainIdx.toFloat(), onValueChange = { run.setGainIndex(it.toInt()) }, valueRange = 0f..(SpectrumRun.GAINS.size - 1).toFloat(), steps = SpectrumRun.GAINS.size - 2)

        if (run.peaks.isNotEmpty()) {
            Text("Strongest signals in view", color = UiColors.text, fontSize = 14.sp)
            run.peaks.forEach { p ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("${mhz(p.hz)}   ${p.db.toInt()} dB   ${p.label}", color = UiColors.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { freqText = "%.3f".format(p.hz / 1e6); run.tune(p.hz) }) { Text("Tune") }
                }
            }
        }
        Text("Receiving only. Rules about listening to some transmissions differ by country, so check yours.", color = UiColors.faint, fontSize = 11.sp)
    }
}
