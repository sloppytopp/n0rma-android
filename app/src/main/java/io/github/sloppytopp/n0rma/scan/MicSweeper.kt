package io.github.sloppytopp.n0rma.scan

import io.github.sloppytopp.n0rma.detect.RfSweep
import io.github.sloppytopp.n0rma.detect.RfSweep.Verdict

/** Orchestrates sweeps and the tone test over an [IqSource]. No Android types, so it runs in JVM tests against a synthetic source. */
class MicSweeper(private val src: IqSource, val gainTenthsDb: Int = 300, private val frames: Int = 48) {
    @Volatile var cancelled = false

    init { src.setGain(gainTenthsDb) }

    /** One full pass over [bands] -> narrowband carriers at [overDb] above floor. */
    fun pass(bands: List<RfSweep.Band>, overDb: Double, progress: (String) -> Unit = {}): Map<Double, Double> {
        val pts = HashMap<Double, Double>()
        for (b in bands) {
            val hops = RfSweep.hopCentres(b.lo, b.hi)
            hops.forEachIndexed { i, c ->
                if (cancelled) return emptyMap()
                if (i % 20 == 0) progress("${b.label}  ${i * 100 / hops.size}%")
                src.tune(c, RfSweep.RATE)
                src.read(1 shl 18)                                   // discard: stale samples from the previous tuning
                val db = RfSweep.spectrumDb(src.read(frames * RfSweep.FFT_N * 2))
                RfSweep.hopPoints(c, db).forEach { (f, d) -> pts[f] = maxOf(pts[f] ?: -999.0, d) }
            }
        }
        return RfSweep.carriers(pts.toList(), overDb)
    }

    /** [passes] passes -> steady carriers. [overDb] is the bar a carrier must clear. */
    fun passes(n: Int, bands: List<RfSweep.Band>, overDb: Double, progress: (String) -> Unit = {}): Map<Double, Double> {
        val all = ArrayList<Map<Double, Double>>()
        for (k in 1..n) { progress("pass $k of $n"); all.add(pass(bands, overDb) { progress("pass $k/$n: $it") }); if (cancelled) break }
        return RfSweep.steady(all)
    }

    /** [n] quick passes kept separately (not collapsed to steady ones) so carriers that come and go can be told apart from steady ones. */
    fun rawPasses(n: Int, bands: List<RfSweep.Band>, overDb: Double, progress: (String) -> Unit = {}): List<Map<Double, Double>> {
        val all = ArrayList<Map<Double, Double>>()
        for (k in 1..n) { all.add(pass(bands, overDb) { progress("pass $k/$n: $it") }); if (cancelled) break }
        return all
    }

    class WatchResult(val profile: RfSweep.BurstProfile, val peakDb: Double, val floorDb: Double)

    /**
     * Sit on one frequency for [seconds] and record when it is on. The target is parked [OFFSET_HZ] off the window centre so the dongle's
     * centre spike never sits on it. Each [STEP_MS] slice is on when the target bin beats the local floor by [overDb].
     */
    fun watch(hz: Double, seconds: Int, overDb: Double = RfSweep.FLAG_DB, progress: (String) -> Unit = {}): WatchResult {
        src.tune(hz + OFFSET_HZ, RfSweep.RATE)
        src.read(1 shl 18)
        val binHz = RfSweep.RATE.toDouble() / RfSweep.FFT_N
        val sig = RfSweep.FFT_N / 2 - Math.round(OFFSET_HZ / binHz).toInt()
        val bytes = RfSweep.RATE * 2 * STEP_MS / 1000
        val sigDb = ArrayList<Double>(); val floors = ArrayList<Double>()
        val slices = seconds * 1000 / STEP_MS
        for (i in 0 until slices) {
            if (cancelled) break
            if (i % 20 == 0) progress("Watching ${"%.3f".format(hz / 1e6)} MHz  ${i * 100 / slices}%")
            val db = RfSweep.spectrumDb(src.read(bytes))
            sigDb.add((sig - 1..sig + 1).maxOf { db[it] })
            val ring = (sig - 120..sig + 120).filter { Math.abs(it - sig) > 20 }.map { db[it] }.sorted()   // floor from bins either side, away from the signal
            floors.add(ring[ring.size / 2])
        }
        if (sigDb.isEmpty()) return WatchResult(RfSweep.burstProfile(BooleanArray(0), STEP_MS), -999.0, -999.0)
        val floor = floors.sorted()[floors.size / 2]
        val on = BooleanArray(sigDb.size) { sigDb[it] - floors[it] >= overDb }
        return WatchResult(RfSweep.burstProfile(on, STEP_MS), sigDb.max(), floor)
    }

    companion object {
        const val OFFSET_HZ = 300_000.0
        const val STEP_MS = 100
    }

    class ToneResult(val target: RfSweep.ToneScore, val controls: List<Pair<Double, RfSweep.ToneScore>>, val verdict: Verdict)

    /** Capture one demodulated measurement while [play] starts the pattern (it must return immediately and play asynchronously). */
    private fun measure(hz: Double, am: Boolean, pattern: IntArray, winMs: Int, play: (IntArray, Int) -> Unit): RfSweep.ToneScore {
        src.tune(hz, RfSweep.DEMOD_RATE)
        src.read(300_000)
        val chunk = RfSweep.DEMOD_RATE * 2 / 10                       // 100 ms of IQ
        val lead = 5                                                  // chunks before playback starts
        val total = lead + (pattern.size * winMs + 800) / 100
        val buf = java.io.ByteArrayOutputStream()
        for (c in 0 until total) {
            if (c == lead) play(pattern, winMs)
            buf.write(src.read(chunk))
            if (cancelled) break
        }
        val iq = buf.toByteArray().copyOfRange(lead * chunk, buf.size())
        val audio = if (am) RfSweep.demodAm(iq) else RfSweep.demodFm(iq)
        return RfSweep.toneScore(audio, pattern, winMs)
    }

    /**
     * Tone test with a control. A fresh random pattern is used for every measurement. The control frequency (empty, next to the target)
     * is measured with a fresh pattern of the same length, as is a nearby real carrier from the baseline; if either also hears the tone, the result is "coupling" (phone/dongle leakage), not an alert.
     */
    fun toneTest(hz: Double, baseline: Collection<Double>, am: Boolean, play: (IntArray, Int) -> Unit, windows: Int = 16, winMs: Int = 800,
                 rnd: java.util.Random = java.util.Random(), progress: (String) -> Unit = {}): ToneResult {
        progress("Measuring ${"%.3f".format(hz / 1e6)} MHz...")
        val t = measure(hz, am, RfSweep.randomPattern(windows, rnd), winMs, play)
        val controls = ArrayList<Pair<Double, RfSweep.ToneScore>>()
        for (f in listOfNotNull(RfSweep.pickControl(hz, baseline), RfSweep.pickCarrierControl(hz, baseline))) {
            if (cancelled) break
            progress("Measuring a control frequency (${"%.3f".format(f / 1e6)} MHz)...")
            controls.add(f to measure(f, am, RfSweep.randomPattern(windows, rnd), winMs, play))
        }
        return ToneResult(t, controls, RfSweep.verdict(t, controls.map { it.second }))
    }
}
