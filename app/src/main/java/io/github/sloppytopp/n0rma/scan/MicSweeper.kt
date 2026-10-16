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
