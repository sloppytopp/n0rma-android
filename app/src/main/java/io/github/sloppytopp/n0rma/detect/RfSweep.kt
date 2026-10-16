package io.github.sloppytopp.n0rma.detect

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Hidden-microphone transmitter hunt with an RTL-SDR (pure maths, no Android, no hardware).
 *
 * A microphone is silent; this looks for the radio that would carry its audio.
 *  1. Baseline: sweep the quiet house and remember every steady narrowband carrier (broadcast stations, your own gear).
 *  2. Scan: sweep again. A steady narrowband carrier that is not in the baseline is a WATCH, never an alert by itself.
 *  3. Tone test: play a random on/off tone pattern in the room and demodulate the suspect frequency. The result is an ALERT only if
 *     the pattern comes back far above chance (permutation test) AND a control frequency next to it does not also "hear" it
 *     (otherwise the phone's own speaker/USB noise is leaking into the dongle and the test proves nothing).
 */
object RfSweep {
    const val RATE = 2_400_000          // sweep sample rate
    const val FFT_N = 1024
    const val BIN_HZ = 25_000.0         // report bins
    const val STEP_HZ = 800_000.0       // hop; windows overlap so the dongle's centre spike is always covered by a neighbour
    const val USABLE_HZ = 960_000.0     // keep +-this around each window centre (filter roll-off beyond it)
    const val DC_SKIP_HZ = 60_000.0     // drop the centre spike
    const val FLAG_DB = 10.0            // a NEW carrier must beat the floor by this much...
    const val BASE_DB = 6.0             // ...but baseline carriers are recorded at this lower bar, so a weak carrier that was
                                        // already there cannot flicker back as "new"
    const val PERSIST = 0.7             // share of passes a carrier must appear in to count as steady
    const val MATCH_HZ = 2 * BIN_HZ     // drift allowed when matching against the baseline
    const val MAX_WIDTH_BINS = 3        // wider than this is TV/data/noise, not an analog audio carrier
    const val TONE_HZ = 1000.0
    const val AUDIO_RATE = 24_000
    const val DEMOD_RATE = 240_000

    data class Band(val label: String, val lo: Double, val hi: Double)
    val BANDS = listOf(
        Band("VHF low/FM broadcast 30-108 MHz", 30e6, 108e6),
        Band("VHF 108-175 MHz (air, handheld, bugs)", 108e6, 175e6),
        Band("VHF high 175-300 MHz (wireless mics, TV)", 175e6, 300e6),
        Band("UHF 300-520 MHz (bugs, ISM 315/433)", 300e6, 520e6),
        Band("UHF wireless mics 520-700 MHz", 520e6, 700e6),
        Band("800-960 MHz (ISM 868/915, wireless mics)", 800e6, 960e6),
        Band("1.2 GHz analog video/audio TX", 1.08e9, 1.32e9),
    )
    fun bandOf(hz: Double) = BANDS.firstOrNull { hz in it.lo..it.hi }?.label ?: "outside swept ranges"

    // ---- FFT -------------------------------------------------------------------------------------------------------------

    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val a = re[i]; re[i] = re[j]; re[j] = a; val b = im[i]; im[i] = im[j]; im[j] = b }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    private val hann = DoubleArray(FFT_N) { 0.5 - 0.5 * cos(2 * PI * it / (FFT_N - 1)) }

    /** Unsigned 8-bit interleaved IQ -> averaged power spectrum in dB, centre-shifted (index FFT_N/2 = window centre). */
    fun spectrumDb(iq: ByteArray): DoubleArray {
        val frames = iq.size / 2 / FFT_N
        require(frames >= 1) { "need at least $FFT_N IQ samples" }
        val acc = DoubleArray(FFT_N)
        val re = DoubleArray(FFT_N); val im = DoubleArray(FFT_N)
        for (f in 0 until frames) {
            for (i in 0 until FFT_N) {
                val o = (f * FFT_N + i) * 2
                re[i] = ((iq[o].toInt() and 0xFF) - 127.5) / 127.5 * hann[i]
                im[i] = ((iq[o + 1].toInt() and 0xFF) - 127.5) / 127.5 * hann[i]
            }
            fft(re, im)
            for (i in 0 until FFT_N) acc[i] += re[i] * re[i] + im[i] * im[i]
        }
        val out = DoubleArray(FFT_N)
        for (i in 0 until FFT_N) out[(i + FFT_N / 2) % FFT_N] = 10 * log10(acc[i] / frames / FFT_N + 1e-12)
        return out
    }

    /** One hop's spectrum -> (freq Hz, dB) points on the report grid, usable part only, centre spike dropped. Max per report bin. */
    fun hopPoints(centerHz: Double, db: DoubleArray): List<Pair<Double, Double>> {
        val binHz = RATE.toDouble() / FFT_N
        val best = HashMap<Long, Double>()
        for (i in 0 until FFT_N) {
            val off = (i - FFT_N / 2) * binHz
            if (abs(off) > USABLE_HZ || abs(off) < DC_SKIP_HZ) continue
            val key = Math.round((centerHz + off) / BIN_HZ)
            best[key] = max(best[key] ?: -999.0, db[i])
        }
        return best.map { it.key * BIN_HZ to it.value }
    }

    fun hopCentres(lo: Double, hi: Double): List<Double> {
        val out = ArrayList<Double>()
        var c = lo + USABLE_HZ - STEP_HZ / 2
        while (c - USABLE_HZ < hi) { out.add(c); c += STEP_HZ }
        return out
    }

    // ---- carriers, steadiness, baseline diff ------------------------------------------------------------------------------

    /** Narrowband carriers in one pass: local peaks at least [overDb] above the median floor, contiguous run <= [MAX_WIDTH_BINS]. */
    fun carriers(points: List<Pair<Double, Double>>, overDb: Double): Map<Double, Double> {
        if (points.size < 8) return emptyMap()
        val pts = points.sortedBy { it.first }
        val sortedDb = pts.map { it.second }.sorted()
        val floor = (sortedDb[(sortedDb.size - 1) / 2] + sortedDb[sortedDb.size / 2]) / 2
        val thr = floor + overDb
        val found = HashMap<Double, Double>()
        for (i in pts.indices) {
            val d = pts[i].second
            if (d < thr) continue
            if (d < max(pts.getOrNull(i - 1)?.second ?: -999.0, pts.getOrNull(i + 1)?.second ?: -999.0)) continue
            var a = i; var b = i
            while (a > 0 && pts[a - 1].second >= thr) a--
            while (b < pts.size - 1 && pts[b + 1].second >= thr) b++
            if (b - a + 1 > MAX_WIDTH_BINS) continue
            found[Math.round(pts[i].first / BIN_HZ) * BIN_HZ] = d
        }
        return found
    }

    /** Carriers present in at least [PERSIST] of the passes -> mean dB. */
    fun steady(passes: List<Map<Double, Double>>): Map<Double, Double> {
        if (passes.isEmpty()) return emptyMap()
        val seen = HashMap<Double, MutableList<Double>>()
        passes.forEach { p -> p.forEach { (f, d) -> seen.getOrPut(f) { ArrayList() }.add(d) } }
        return seen.filter { it.value.size.toDouble() / passes.size >= PERSIST }.mapValues { it.value.average() }
    }

    /** Steady carriers with no baseline carrier within drift: the unexplained ones, strongest first. */
    fun newCarriers(current: Map<Double, Double>, baseline: Collection<Double>): List<Pair<Double, Double>> {
        val base = baseline.sorted()
        return current.filter { (f, _) -> base.none { abs(f - it) <= MATCH_HZ } }.toList().sortedByDescending { it.second }
    }

    /** A nearby frequency with nothing on it (no baseline carrier, no current carrier within 100 kHz) for the control measurement. */
    fun pickControl(target: Double, baseline: Collection<Double>, current: Collection<Double> = emptyList()): Double? {
        val busy = baseline + current
        for (off in listOf(250e3, -250e3, 500e3, -500e3, 1e6, -1e6, 2e6, -2e6)) {
            val f = target + off
            if (f > 24e6 && busy.none { abs(it - f) < 100e3 }) return f
        }
        return null
    }

    // ---- demodulation -----------------------------------------------------------------------------------------------------

    /** FM discriminator over IQ at [DEMOD_RATE], low-passed and decimated to [AUDIO_RATE] (box filter). Output scaled roughly to +-1. */
    fun demodFm(iq: ByteArray): DoubleArray {
        val n = iq.size / 2
        val dec = DEMOD_RATE / AUDIO_RATE
        val out = DoubleArray(n / dec)
        var pr = 0.0; var pi = 0.0
        var acc = 0.0; var c = 0; var o = 0
        for (i in 0 until n) {
            val r = ((iq[2 * i].toInt() and 0xFF) - 127.5) / 127.5
            val q = ((iq[2 * i + 1].toInt() and 0xFF) - 127.5) / 127.5
            val d = atan2(q * pr - r * pi, r * pr + q * pi)    // arg(z[n] * conj(z[n-1]))
            pr = r; pi = q
            acc += d; c++
            if (c == dec) { if (o < out.size) out[o++] = acc / dec / PI; acc = 0.0; c = 0 }
        }
        return out
    }

    fun demodAm(iq: ByteArray): DoubleArray {
        val n = iq.size / 2
        val dec = DEMOD_RATE / AUDIO_RATE
        val out = DoubleArray(n / dec)
        var acc = 0.0; var c = 0; var o = 0
        for (i in 0 until n) {
            val r = ((iq[2 * i].toInt() and 0xFF) - 127.5) / 127.5
            val q = ((iq[2 * i + 1].toInt() and 0xFF) - 127.5) / 127.5
            acc += sqrt(r * r + q * q); c++
            if (c == dec) { if (o < out.size) out[o++] = acc / dec; acc = 0.0; c = 0 }
        }
        val mean = out.average()
        return DoubleArray(out.size) { out[it] - mean }
    }

    // ---- tone (feedback) test ----------------------------------------------------------------------------------------------

    fun goertzel(x: DoubleArray, from: Int, to: Int, rate: Int, freq: Double): Double {
        val k = 2 * cos(2 * PI * freq / rate)
        var s1 = 0.0; var s2 = 0.0
        for (i in from until to) { val s0 = x[i] + k * s1 - s2; s2 = s1; s1 = s0 }
        return s1 * s1 + s2 * s2 - k * s1 * s2
    }

    /** Random on/off pattern with a guaranteed mix (never all-on / all-off, at least [min] of each). */
    fun randomPattern(windows: Int, rnd: java.util.Random): IntArray {
        while (true) {
            val p = IntArray(windows) { rnd.nextInt(2) }
            val on = p.sum()
            if (on >= windows / 4 && windows - on >= windows / 4) return p
        }
    }

    data class ToneScore(val corr: Double, val onOffDb: Double, val p: Double, val lagMs: Int)

    /**
     * How well does the demodulated audio follow the on/off tone pattern?
     * Playback starts an unknown (small) time after capture, so the best of several lags is used; the shuffled-pattern null
     * takes the best lag too, so searching for the lag cannot inflate the score. p = share of shuffles scoring at least as well.
     */
    fun toneScore(audio: DoubleArray, pattern: IntArray, winMs: Int, rate: Int = AUDIO_RATE, perms: Int = 400, seed: Long = 7): ToneScore {
        val win = rate * winMs / 1000
        val lagsMs = (0..400 step 50).toList()
        val logs = lagsMs.map { lag ->
            val off = rate * lag / 1000
            DoubleArray(pattern.size) { w ->
                val a = off + w * win
                val b = a + win
                if (b <= audio.size) log10(goertzel(audio, a, b, rate, TONE_HZ) + 1e-12) else Double.NaN
            }
        }
        fun corr(l: DoubleArray, pat: IntArray): Double {
            val idx = l.indices.filter { !l[it].isNaN() }
            if (idx.size < 6) return 0.0
            val mp = idx.map { l[it] }.average(); val mt = idx.map { pat[it].toDouble() }.average()
            var num = 0.0; var dp = 0.0; var dt = 0.0
            for (i in idx) { val a = l[i] - mp; val b = pat[i] - mt; num += a * b; dp += a * a; dt += b * b }
            val den = sqrt(dp * dt)
            return if (den < 1e-12) 0.0 else num / den
        }
        fun best(pat: IntArray) = logs.indices.maxOf { corr(logs[it], pat) }
        val real = best(pattern)
        val bestLag = logs.indices.maxBy { corr(logs[it], pattern) }
        val rnd = java.util.Random(seed)
        var ge = 0
        repeat(perms) {
            val sh = pattern.copyOf().toMutableList().also { it.shuffle(rnd) }.toIntArray()
            if (best(sh) >= real) ge++
        }
        val l = logs[bestLag]
        val on = pattern.indices.filter { pattern[it] == 1 && !l[it].isNaN() }.map { Math.pow(10.0, l[it]) }
        val off = pattern.indices.filter { pattern[it] == 0 && !l[it].isNaN() }.map { Math.pow(10.0, l[it]) }
        val db = if (on.isEmpty() || off.isEmpty()) 0.0 else 10 * log10((on.average() + 1e-12) / (off.average() + 1e-12))
        return ToneScore(real, db, (ge + 1).toDouble() / (perms + 1), lagsMs[bestLag])
    }

    enum class Verdict { CONFIRMED, COUPLING, POSSIBLE, NONE }

    const val MIN_CORR = 0.8
    const val MIN_DB = 6.0
    const val MAX_P = 0.01

    fun hit(s: ToneScore) = s.corr >= MIN_CORR && s.onOffDb >= MIN_DB && s.p <= MAX_P

    /**
     * Any control that "hears" the tone too means the leak is in the phone/dongle chain (additive hum on an empty channel, or
     * microphonics/FM on a real carrier), so nothing can be concluded. No controls measured -> never an alert.
     */
    fun verdict(target: ToneScore, controls: List<ToneScore>): Verdict = when {
        controls.isEmpty() -> Verdict.POSSIBLE
        controls.any { it.corr >= 0.5 || it.p <= 0.05 } -> if (hit(target)) Verdict.COUPLING else Verdict.NONE
        hit(target) -> Verdict.CONFIRMED
        target.corr >= 0.5 && target.p <= 0.05 -> Verdict.POSSIBLE
        else -> Verdict.NONE
    }

    /** A real, steady baseline carrier near the target to use as a second control (catches microphonic FM that an empty channel cannot show). */
    fun pickCarrierControl(target: Double, baseline: Collection<Double>): Double? =
        baseline.filter { abs(it - target) in 300e3..20e6 }.minByOrNull { abs(it - target) }

    // ---- baseline storage format --------------------------------------------------------------------------------------------

    data class Baseline(val ts: Long, val gainTenthsDb: Int, val carriers: List<Double>) {
        fun encode() = "$ts;$gainTenthsDb;" + carriers.joinToString(",") { it.toLong().toString() }
        companion object {
            fun decode(s: String?): Baseline? {
                val p = s?.split(";") ?: return null
                if (p.size < 3) return null
                return runCatching { Baseline(p[0].toLong(), p[1].toInt(), p[2].split(",").filter { it.isNotBlank() }.map { it.toDouble() }) }.getOrNull()
            }
        }
    }
}
