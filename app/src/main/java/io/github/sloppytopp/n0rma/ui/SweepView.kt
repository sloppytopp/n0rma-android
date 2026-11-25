package io.github.sloppytopp.n0rma.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sloppytopp.n0rma.detect.SpectrumMath
import io.github.sloppytopp.n0rma.scan.SpectrumRun
import io.github.sloppytopp.n0rma.scan.SweepStore
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Pro build: one wide sweep over the whole tuner range (about 24 MHz to 1.7 GHz) drawn as a single graph with the usual band names, the typical
 * noise level and the strongest signals numbered. Levels are relative dB (not calibrated) and band names are allocations, not identifications.
 */
@Composable
fun SweepSection(onTune: (Double) -> Unit) {
    val ctx = LocalContext.current
    val run = SpectrumRun
    val sw = run.sweep
    val file = remember { File(ctx.filesDir, "sweep_baseline.bin") }
    var msg by remember { mutableStateOf("") }
    var changes by remember { mutableStateOf<Pair<List<SpectrumMath.Change>, List<SpectrumMath.Change>>?>(null) }
    fun stamp(t: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(t))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Wide sweep", color = UiColors.text, fontSize = 16.sp)
        Text("One pass over everything the dongle can hear, 24 MHz to 1.7 GHz, takes about half a minute. Stay still and keep the dongle where you want to check.", color = UiColors.dim, fontSize = 12.sp)
        Button(
            onClick = { if (run.sweeping) run.stop() else run.startSweep(ctx) }, enabled = run.sweeping || !run.running, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = UiColors.buttonBg, contentColor = UiColors.buttonFg),
        ) { Text(if (run.sweeping) "Stop sweep  (${(run.sweepProgress * 100).toInt()}%)" else if (sw == null) "Run a wide sweep" else "Sweep again") }
        if (sw != null) {
            SweepGraph(sw)
            Text("Levels are relative dB, not calibrated. Band names are the usual US allocations only: a signal in a band can be something else.", color = UiColors.faint, fontSize = 11.sp)
            Text("Compare with an earlier sweep", color = UiColors.text, fontSize = 14.sp)
            Text("Sweep a room you trust, save it as the baseline, then sweep again later at the same gain: carriers that appeared or went away are listed. " +
                "A new carrier is a reason to look, not proof of anything.", color = UiColors.dim, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    msg = if (runCatching { SweepStore.save(file, sw) }.isSuccess) "Baseline saved from the sweep at ${stamp(sw.takenAt)}." else "Could not save the baseline."
                    changes = null
                }) { Text("Save as baseline") }
                TextButton(onClick = {
                    val b = SweepStore.load(file)
                    changes = null
                    msg = when {
                        b == null -> "No baseline saved yet. Sweep, then press Save as baseline."
                        Math.abs(b.gainTenths - sw.gainTenths) > SweepStore.MAX_GAIN_DIFF_TENTHS ->
                            "The baseline was taken at gain ${"%.1f".format(b.gainDb())} dB and this sweep at ${"%.1f".format(sw.gainDb())} dB. Use the same gain and sweep again."
                        b.db.size != sw.db.size || b.startHz != sw.startHz || b.binHz != sw.binHz -> "The saved baseline does not match this sweep. Save a new one."
                        else -> { changes = SpectrumMath.compareSweeps(b.db, sw.db, sw.startHz, sw.binHz); "Compared with the baseline from ${stamp(b.takenAt)} (names are usual allocations, not identifications)." }
                    }
                }) { Text("Compare with baseline") }
            }
            if (msg.isNotEmpty()) Text(msg, color = UiColors.dim, fontSize = 12.sp)
            changes?.let { (new, gone) ->
                if (new.isEmpty() && gone.isEmpty()) Text("No carrier appeared or disappeared.", color = UiColors.good, fontSize = 12.sp)
                new.forEach { Text("${"%.3f".format(it.hz / 1e6)} MHz  new, ${"%.0f".format(it.deltaDb)} dB stronger  ${it.label}", color = UiColors.watch, fontSize = 12.sp, maxLines = 2) }
                gone.forEach { Text("${"%.3f".format(it.hz / 1e6)} MHz  gone, ${"%.0f".format(it.deltaDb)} dB weaker  ${it.label}", color = UiColors.dim, fontSize = 12.sp, maxLines = 2) }
            }
            Text("Strongest signals", color = UiColors.text, fontSize = 14.sp)
            if (sw.peaks.isEmpty()) Text("Nothing stands clearly above the noise.", color = UiColors.dim, fontSize = 12.sp)
            sw.peaks.forEachIndexed { i, p ->
                Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("${i + 1}  ${"%.3f".format(p.hz / 1e6)} MHz  ${"%.0f".format(p.db - sw.noise)} dB over noise  ${p.label}", color = UiColors.text, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onTune(p.hz) }) { Text("Tune") }
                }
            }
        }
    }
}

private fun SpectrumRun.Sweep.gainDb() = gainTenths / 10.0

@Composable
private fun SweepGraph(sw: SpectrumRun.Sweep) {
    val tm = rememberTextMeasurer()
    val small = TextStyle(fontSize = 9.sp, color = UiColors.dim)
    val bandStyle = TextStyle(fontSize = 9.sp, color = UiColors.good)
    val peakStyle = TextStyle(fontSize = 10.sp, color = UiColors.watch)
    Canvas(Modifier.fillMaxWidth().height(240.dp)) {
        val bottom = 18.dp.toPx()
        val w = size.width; val h = size.height - bottom
        val spanHz = sw.db.size * sw.binHz
        val d = sw.db
        val lo = sw.noise - 6f
        val hi = maxOf(d.max() + 5f, sw.noise + 30f)
        fun xOf(hz: Double) = (((hz - sw.startHz) / spanHz) * w).toFloat()
        fun yOf(v: Float) = h * (1f - ((v - lo) / (hi - lo)).coerceIn(0f, 1f))

        // band shading and names (widest first so the narrow ones are not hidden; a name is skipped if it would overlap the previous one)
        var lastEnd = -1f
        SpectrumMath.bands.filter { it.second - it.first >= 20.0 }.sortedBy { it.first }.forEach { (a, b, name) ->
            val x0 = xOf(a * 1e6).coerceAtLeast(0f); val x1 = xOf(b * 1e6).coerceAtMost(w)
            if (x1 <= x0) return@forEach
            drawRect(UiColors.good.copy(alpha = 0.07f), Offset(x0, 0f), androidx.compose.ui.geometry.Size(x1 - x0, h))
            val t = tm.measure(name, bandStyle, maxLines = 1)
            if (t.size.width <= x1 - x0 && x0 >= lastEnd) { drawText(t, topLeft = Offset(x0 + 2f, 2f)); lastEnd = x0 + t.size.width + 6f }
        }
        // grid: every 100 MHz, labelled every 200
        var f = 100
        while (f * 1e6 < sw.startHz + spanHz) {
            val x = xOf(f * 1e6)
            drawLine(UiColors.faint.copy(alpha = 0.25f), Offset(x, 0f), Offset(x, h), 1f)
            if (f % 200 == 0) { val t = tm.measure(if (f < 1000) "$f MHz" else "%.1f GHz".format(f / 1000.0), small); drawText(t, topLeft = Offset((x - t.size.width / 2f).coerceIn(0f, w - t.size.width), h + 3f)) }
            f += 100
        }
        // curve: strongest value of every pixel column, so a narrow signal never disappears when the 180k points are squeezed onto the screen
        val cols = w.toInt().coerceAtLeast(1)
        val path = Path(); var started = false
        val fill = Path()
        for (c in 0 until cols) {
            val i0 = (c.toLong() * d.size / cols).toInt(); val i1 = ((c + 1L) * d.size / cols).toInt().coerceAtLeast(i0 + 1).coerceAtMost(d.size)
            var m = d[i0]; for (i in i0 + 1 until i1) if (d[i] > m) m = d[i]
            val y = yOf(m)
            if (!started) { path.moveTo(c.toFloat(), y); fill.moveTo(c.toFloat(), h); fill.lineTo(c.toFloat(), y); started = true } else { path.lineTo(c.toFloat(), y); fill.lineTo(c.toFloat(), y) }
        }
        fill.lineTo(cols - 1f, h); fill.close()
        drawPath(fill, UiColors.good.copy(alpha = 0.18f))
        drawPath(path, UiColors.good, style = Stroke(1.5f))
        // typical noise level
        val ny = yOf(sw.noise)
        drawLine(UiColors.dim, Offset(0f, ny), Offset(w, ny), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        val nt = tm.measure("typical noise level", small); drawText(nt, topLeft = Offset(w - nt.size.width - 2f, ny - nt.size.height - 1f))
        // numbered peaks
        sw.peaks.forEachIndexed { i, p ->
            val x = xOf(p.hz); val y = yOf(p.db)
            drawCircle(UiColors.watch, 4f, Offset(x, y))
            val t = tm.measure("${i + 1}", peakStyle); drawText(t, topLeft = Offset((x - t.size.width / 2f).coerceIn(0f, w - t.size.width), (y - t.size.height - 4f).coerceAtLeast(0f)))
        }
        drawText(tm.measure("relative dB", small), topLeft = Offset(2f, h - 12.dp.toPx()))
    }
}
