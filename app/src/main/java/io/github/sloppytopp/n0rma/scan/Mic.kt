package io.github.sloppytopp.n0rma.scan

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.sloppytopp.n0rma.data.Prefs
import io.github.sloppytopp.n0rma.data.Store
import io.github.sloppytopp.n0rma.detect.EventRow
import io.github.sloppytopp.n0rma.detect.Level
import io.github.sloppytopp.n0rma.detect.RfSweep
import io.github.sloppytopp.n0rma.detect.RfSweep.Verdict

/** Drives the hidden-microphone sweep from the UI: talks to the RTL-SDR driver app over localhost, runs on one worker thread. */
object Mic {
    const val DRIVER_PKG = "marto.rtl_tcp_andro"
    const val PORT = 1234
    const val GAIN = 300                       // tenths of dB, fixed on purpose (see RtlTcp.setGain)

    var busy by mutableStateOf(false); private set
    var status by mutableStateOf("Idle"); private set
    var log by mutableStateOf<List<String>>(emptyList()); private set
    var candidates by mutableStateOf<List<Pair<Double, Double>>>(emptyList()); private set
    var am by mutableStateOf(false)
    private var sweeper: MicSweeper? = null

    private fun say(s: String) { status = s }
    private fun line(s: String) { log = (listOf(s) + log).take(40) }

    /** Ask the driver app to start its rtl_tcp server (it shows the USB permission prompt for the dongle). */
    fun launchDriver(ctx: Context) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("iqsrc://-a 127.0.0.1 -p $PORT")).setPackage(DRIVER_PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)   // explicit target: no other app can answer this intent
        try { ctx.startActivity(i) } catch (e: ActivityNotFoundException) {
            say("The RTL-SDR driver app is not installed")
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/$DRIVER_PKG/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    fun baseline(): RfSweep.Baseline? = RfSweep.Baseline.decode(Prefs.micBaseline)

    fun cancel() { sweeper?.cancelled = true }

    private fun work(name: String, body: (MicSweeper) -> Unit) {
        if (busy) return
        busy = true; say("Connecting to the driver app...")
        Thread {
            var src: RtlTcp? = null
            try {
                src = RtlTcp("127.0.0.1", PORT)
                val sw = MicSweeper(src, GAIN).also { sweeper = it }
                body(sw)
            } catch (e: java.net.ConnectException) {
                say("Can't reach the dongle. Plug it in, open the RTL-SDR driver app and press Start, then try again.")
            } catch (e: Exception) {
                say("$name failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                src?.close(); sweeper = null; busy = false
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun event(level: Level, msg: String) { runCatching { Store.addEvent(EventRow(System.currentTimeMillis(), "rf", level, msg)) } }

    /** Record what's normal: run in the quiet house with your own gear on. Uses the lower bar so a faint legit carrier can't come back as "new". */
    fun recordBaseline(passes: Int = 5) = work("Baseline") { sw ->
        val c = sw.passes(passes, RfSweep.BANDS, RfSweep.BASE_DB) { say(it) }
        if (sw.cancelled) { say("Cancelled"); return@work }
        Prefs.saveMicBaseline(RfSweep.Baseline(System.currentTimeMillis(), GAIN, c.keys.sorted()).encode())
        say("Baseline saved: ${c.size} steady carrier(s) are now treated as normal")
        line("Baseline: ${c.size} steady carriers recorded")
    }

    fun scan(passes: Int = 5) = work("Scan") { sw ->
        val b = baseline() ?: run { say("Record a baseline first (quiet house, your own gear on)"); return@work }
        if (b.gainTenthsDb != GAIN) { say("Baseline was recorded at a different gain; record it again"); return@work }
        val cur = sw.passes(passes, RfSweep.BANDS, RfSweep.FLAG_DB) { say(it) }
        if (sw.cancelled) { say("Cancelled"); return@work }
        val found = RfSweep.newCarriers(cur, b.carriers)
        candidates = found
        if (found.isEmpty()) { say("Nothing new compared with the baseline"); line("Scan: nothing new") }
        else {
            say("${found.size} new steady carrier(s): each needs a tone test before it means anything")
            found.forEach { (f, d) ->
                val m = "Mic sweep: new steady carrier ${"%.3f".format(f / 1e6)} MHz (${d.toInt()} dB) in ${RfSweep.bandOf(f)}; not yet tone-tested"
                line(m); event(Level.WATCH, m)
            }
        }
    }

    /** Many quick passes; reports carriers that appear in some but not most of them (bursty / keyed / duty-cycled transmitters). */
    fun burstScan(passes: Int = 12) = work("Burst scan") { sw ->
        val b = baseline() ?: run { say("Record a baseline first (quiet house, your own gear on)"); return@work }
        if (b.gainTenthsDb != GAIN) { say("Baseline was recorded at a different gain; record it again"); return@work }
        val all = sw.rawPasses(passes, RfSweep.BANDS, RfSweep.FLAG_DB) { say(it) }
        if (sw.cancelled) { say("Cancelled"); return@work }
        val found = RfSweep.intermittent(all, b.carriers)
        candidates = found.map { it.hz to it.db }
        if (found.isEmpty()) { say("No in-and-out carriers found"); line("Burst scan: nothing intermittent") }
        else {
            say("${found.size} intermittent carrier(s): use Watch to see its on/off pattern")
            found.forEach {
                val m = "Intermittent carrier ${"%.3f".format(it.hz / 1e6)} MHz (${it.db.toInt()} dB) in ${it.hits} of ${it.passes} passes, ${RfSweep.bandOf(it.hz)}; not tone-tested"
                line(m); event(Level.WATCH, m)
            }
        }
    }

    /** Park on one frequency and log its on/off pattern. Describes the signal; it never says what the signal is. */
    fun watch(mhz: Double, seconds: Int = 120) = work("Watch") { sw ->
        val r = sw.watch(mhz * 1e6, seconds) { say(it) }
        if (sw.cancelled) { say("Cancelled"); return@work }
        val p = r.profile
        val head = "${"%.3f".format(mhz)} MHz"
        val m = when {
            p.transitions == 0 && p.onShare == 0.0 -> "$head: silent for ${seconds}s"
            p.transitions == 0 -> "$head: steady the whole ${seconds}s (${r.peakDb.toInt()} dB, floor ${r.floorDb.toInt()} dB)"
            else -> "$head: on ${(p.onShare * 100).toInt()}% of ${seconds}s, ${p.transitions} switches, typical burst ${p.medianOnMs} ms, gap ${p.medianOffMs} ms" +
                (p.periodMs?.let { ", repeats about every ${"%.1f".format(it / 1000.0)} s" } ?: "") + " (${r.peakDb.toInt()} dB)"
        }
        say(m); line(m)
        if (p.transitions > 0) event(Level.WATCH, "Mic sweep: $m")
    }

    fun toneTest(ctx: Context, mhz: Double) = work("Tone test") { sw ->
        val out = ToneOut(ctx.applicationContext)
        try {
            val r = sw.toneTest(mhz * 1e6, baseline()?.carriers ?: emptyList(), am, { p, w -> out.start(p, w) }) { say(it) }
            val t = r.target
            val head = "${"%.3f".format(mhz)} MHz"
            val detail = "tone score ${"%.2f".format(t.corr)}, ${"%.1f".format(t.onOffDb)} dB on-vs-off, chance ${"%.3f".format(t.p)}" +
                r.controls.joinToString("") { (f, s) -> "; control ${"%.3f".format(f / 1e6)} MHz ${"%.2f".format(s.corr)}" }
            when (r.verdict) {
                Verdict.CONFIRMED -> { val m = "MIC SWEEP: $head carried the test tone back from this room, and the control frequencies did not ($detail)"; say(m); line(m); event(Level.ALERT, m) }
                Verdict.COUPLING -> { val m = "$head: inconclusive. The control frequencies also showed the tone, so the phone/dongle is leaking. Move the dongle away from the phone on a USB cable and retry ($detail)"; say(m); line(m); event(Level.WATCH, m) }
                Verdict.POSSIBLE -> { val m = "$head: possible, not confirmed; no control could be measured or the match is weak ($detail). Repeat closer to the source"; say(m); line(m); event(Level.WATCH, m) }
                Verdict.NONE -> { val m = "$head: no tone came back; not carrying audio from this room ($detail)"; say(m); line(m) }
            }
        } finally { out.finish() }
    }
}
