package io.github.sloppytopp.n0rma.detect

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/** Signal-strength-by-frequency maths for the spectrum screen. It cannot decode anything or say what a signal IS. */
object SpectrumMath {
    /** In-place radix-2 FFT; [re] and [im] must have a power-of-two length. */
    fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        require(n > 1 && n and (n - 1) == 0) { "length must be a power of two" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val tr = re[i]; re[i] = re[j]; re[j] = tr; val ti = im[i]; im[i] = im[j]; im[j] = ti }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci; val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi; re[a] += xr; im[a] += xi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * Average power spectrum in dB (relative to a full-scale tone) of unsigned 8-bit interleaved IQ. Index 0 is the lowest frequency
     * (centre - rate/2) and the last is the highest, so it can be drawn left to right. Uses up to [frames] non-overlapping Hann-windowed blocks.
     */
    fun psdDb(iq: ByteArray, nfft: Int, frames: Int): FloatArray {
        val win = FloatArray(nfft) { (0.5 - 0.5 * cos(2.0 * PI * it / (nfft - 1))).toFloat() }
        val sumW = win.sum()
        val acc = FloatArray(nfft)
        val re = FloatArray(nfft); val im = FloatArray(nfft)
        val blocks = minOf(frames, iq.size / (2 * nfft)).coerceAtLeast(0)
        for (f in 0 until blocks) {
            val base = f * 2 * nfft
            for (i in 0 until nfft) {
                re[i] = ((iq[base + 2 * i].toInt() and 0xff) - 127.5f) / 127.5f * win[i]
                im[i] = ((iq[base + 2 * i + 1].toInt() and 0xff) - 127.5f) / 127.5f * win[i]
            }
            fft(re, im)
            for (i in 0 until nfft) acc[i] += re[i] * re[i] + im[i] * im[i]
        }
        val out = FloatArray(nfft)
        for (i in 0 until nfft) {
            val p = if (blocks > 0) acc[i] / blocks / (sumW * sumW) else 0f
            out[(i + nfft / 2) % nfft] = (10.0 * log10(p.toDouble() + 1e-12)).toFloat()      // fftshift: negative frequencies first
        }
        return out
    }

    class Peak(val hz: Double, val db: Float, val label: String)

    /** Strongest local maxima at least [minAboveFloor] dB above the median, at least [minSepBins] apart. */
    fun peaks(db: FloatArray, centerHz: Double, rate: Int, count: Int = 6, minAboveFloor: Float = 8f, minSepBins: Int = 6): List<Peak> {
        if (db.size < 8) return emptyList()
        val floor = db.sortedArray()[db.size / 2]
        val cand = (2 until db.size - 2).filter { i -> db[i] >= db[i - 1] && db[i] > db[i + 1] && db[i] > db[i - 2] && db[i] > db[i + 2] && db[i] - floor >= minAboveFloor }
            .sortedByDescending { db[it] }
        val picked = ArrayList<Int>()
        for (i in cand) { if (picked.none { Math.abs(it - i) < minSepBins }) picked += i; if (picked.size >= count) break }
        return picked.map { i -> val hz = hzAt(i, db.size, centerHz, rate); Peak(hz, db[i], bandLabel(hz)) }
    }

    fun hzAt(bin: Int, nfft: Int, centerHz: Double, rate: Int) = centerHz + (bin - nfft / 2).toDouble() / nfft * rate
    fun binAt(hz: Double, nfft: Int, centerHz: Double, rate: Int) = (nfft / 2 + ((hz - centerHz) / rate * nfft)).toInt().coerceIn(0, nfft - 1)

    /** Usual US allocations, NOT an identification: a signal in a band might be something else entirely. MHz ranges. */
    private val BANDS = listOf(
        24.0 to 30.0 to "CB / 10 m amateur", 30.0 to 50.0 to "VHF low (public safety, amateur 6 m starts at 50)", 50.0 to 54.0 to "6 m amateur",
        54.0 to 88.0 to "TV channels 2-6", 88.0 to 108.0 to "FM broadcast", 108.0 to 118.0 to "Aircraft navigation", 118.0 to 137.0 to "Aircraft voice (AM)",
        137.0 to 138.0 to "Weather satellites", 144.0 to 148.0 to "2 m amateur", 150.0 to 162.0 to "VHF business / marine / public safety",
        162.4 to 162.6 to "NOAA weather radio", 174.0 to 216.0 to "TV channels 7-13", 315.0 to 316.0 to "Garage / keyfob remotes (315)",
        420.0 to 450.0 to "70 cm amateur", 433.05 to 434.79 to "ISM 433 (sensors, remotes)", 450.0 to 470.0 to "UHF business / GMRS / FRS",
        470.0 to 698.0 to "UHF TV", 698.0 to 806.0 to "LTE 700", 806.0 to 869.0 to "800 MHz public safety / trunking", 824.0 to 894.0 to "Cellular 850",
        902.0 to 928.0 to "ISM 915 (sensors, smart meters)", 929.0 to 932.0 to "Pagers", 960.0 to 1215.0 to "Aviation (DME, ADS-B at 1090)",
        1217.0 to 1350.0 to "Radar / navigation", 1525.0 to 1660.0 to "Satellite phone / GPS L1 nearby", 1710.0 to 1755.0 to "AWS cellular (edge)",
    ).map { Triple(it.first.first, it.first.second, it.second) }

    fun bandLabel(hz: Double): String {
        val mhz = hz / 1e6
        return BANDS.filter { mhz >= it.first && mhz < it.second }.minByOrNull { it.second - it.first }?.third ?: "no common allocation listed"
    }
}
