package io.github.sloppytopp.n0rma

import io.github.sloppytopp.n0rma.detect.RfSweep
import io.github.sloppytopp.n0rma.detect.RfSweep.Verdict
import io.github.sloppytopp.n0rma.scan.IqSource
import io.github.sloppytopp.n0rma.scan.MicSweeper
import io.github.sloppytopp.n0rma.scan.RtlTcp
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A fake radio world: steady carriers, one optional "bug" that FM-carries the test tone, optional leakage of the tone into everything. */
class SynthSource(
    private val steady: List<Pair<Double, Double>> = emptyList(),      // (Hz, amplitude)
    private val bugHz: Double? = null,
    private val leak: Double = 0.0,                                     // tone leaking into every channel (phone/dongle coupling)
    private val noise: Double = 0.03,
) : IqSource {
    private var tuned = 0.0
    private var rate = RfSweep.RATE
    private var clock = 0L                                              // samples generated at the demod rate
    private var pStart = -1L
    private var pattern = IntArray(0)
    private var winMs = 800
    private val rnd = Random(3)
    private var bugPhase = 0.0
    var gainSet = -1

    override fun setGain(tenthsDb: Int) { gainSet = tenthsDb }
    override fun tune(hz: Double, rate: Int) { tuned = hz; this.rate = rate }
    fun play(p: IntArray, w: Int) { pattern = p; winMs = w; pStart = clock }
    private fun toneOn(i: Long): Boolean {
        if (pStart < 0) return false
        val w = ((i - pStart) * 1000 / RfSweep.DEMOD_RATE / winMs).toInt()
        return i >= pStart && w in pattern.indices && pattern[w] == 1
    }
    override fun close() {}
    override fun read(n: Int): ByteArray {
        val out = ByteArray(n)
        for (s in 0 until n / 2) {
            val t = s.toDouble() / rate
            var re = rnd.nextGaussian() * noise; var im = rnd.nextGaussian() * noise
            for ((f, a) in steady) {
                val off = f - tuned
                // microphonics: when leaking, the tone FM-modulates every real carrier too
                val pm = if (leak > 0 && rate == RfSweep.DEMOD_RATE && toneOn(clock + s)) leak * 8 * sin(2 * PI * RfSweep.TONE_HZ * (clock + s) / rate) else 0.0
                if (kotlin.math.abs(off) < rate / 2.0) { re += a * cos(2 * PI * off * t + pm); im += a * sin(2 * PI * off * t + pm) }
            }
            if (rate == RfSweep.DEMOD_RATE) {
                val i = clock + s
                val audio = if (toneOn(i)) sin(2 * PI * RfSweep.TONE_HZ * i / rate) else 0.0
                if (bugHz != null) {
                    val off = bugHz - tuned
                    if (kotlin.math.abs(off) < 90_000) {
                        bugPhase += 2 * PI * (off + 5000 * audio) / rate
                        re += 0.5 * cos(bugPhase); im += 0.5 * sin(bugPhase)
                    }
                }
                // coupling: the phone's speaker/USB leaks a tone into the dongle's baseband on every channel
                if (leak > 0 && toneOn(i)) { val x = leak * sin(2 * PI * RfSweep.TONE_HZ * i / rate); re += x; im += x }
            }
            out[2 * s] = (127.5 + 127.5 * re).coerceIn(0.0, 255.0).toInt().toByte()
            out[2 * s + 1] = (127.5 + 127.5 * im).coerceIn(0.0, 255.0).toInt().toByte()
        }
        if (rate == RfSweep.DEMOD_RATE) clock += n / 2
        return out
    }
}

class MicSweepTest {
    @Test fun fftFindsTheTone() {
        val n = 1024
        val re = DoubleArray(n) { cos(2 * PI * 100 * it / n) }; val im = DoubleArray(n)
        RfSweep.fft(re, im)
        val mag = DoubleArray(n) { re[it] * re[it] + im[it] * im[it] }
        assertEquals(100, mag.indices.maxBy { mag[it] })
    }

    @Test fun narrowCarrierFoundWideHumpIgnored() {
        val pts = (0 until 200).map { 100e6 + it * 25e3 to -50.0 + (it % 3) * 0.4 }.toMutableList()
        pts[50] = pts[50].first to -30.0
        assertEquals(1, RfSweep.carriers(pts, 10.0).size)
        val hump = pts.mapIndexed { i, p -> if (i in 100..109) p.first to -30.0 else p }
        assertEquals(0, RfSweep.carriers(hump.filterIndexed { i, _ -> i !in 50..50 }, 10.0).size)
    }

    @Test fun steadyAndBaselineDiff() {
        val passes = listOf(mapOf(1e8 to -30.0, 2e8 to -30.0), mapOf(1e8 to -30.0), mapOf(1e8 to -30.0), mapOf(1e8 to -30.0))
        assertEquals(setOf(1e8), RfSweep.steady(passes).keys)
        val cur = mapOf(1e8 + RfSweep.BIN_HZ to -30.0, 433.92e6 to -40.0)
        assertEquals(listOf(433.92e6), RfSweep.newCarriers(cur, listOf(1e8)).map { it.first })
    }

    @Test fun baselineRoundTrip() {
        val b = RfSweep.Baseline(123L, 300, listOf(1e8, 2.5e8))
        assertEquals(b, RfSweep.Baseline.decode(b.encode()))
        assertNull(RfSweep.Baseline.decode("junk"))
        assertNull(RfSweep.Baseline.decode(null))
    }

    @Test fun controlAvoidsBusyFrequencies() {
        val c = RfSweep.pickControl(433.92e6, listOf(434.17e6))!!
        assertTrue(Math.abs(c - 434.17e6) >= 100e3)
    }

    @Test fun sweepFindsAnUnexpectedCarrierAndBaselineAbsorbsKnownOnes() {
        val band = listOf(RfSweep.Band("t", 430e6, 437e6))
        val known = 431.5e6; val bug = 433.92e6
        val base = MicSweeper(SynthSource(steady = listOf(known to 0.2)), 300).passes(3, band, RfSweep.BASE_DB)
        assertTrue(base.keys.any { Math.abs(it - known) < 50e3 })
        val sw = MicSweeper(SynthSource(steady = listOf(known to 0.2, bug to 0.2)), 300)
        val found = RfSweep.newCarriers(sw.passes(3, band, RfSweep.FLAG_DB), base.keys)
        assertEquals(1, found.size)
        assertEquals(bug, found[0].first, 50e3)
        assertEquals(300, (sw.let { SynthSource().also { s -> MicSweeper(s, 300) } }).gainSet)   // fixed manual gain is always set
    }

    @Test fun quietHouseFlagsNothing() {
        val band = listOf(RfSweep.Band("t", 430e6, 437e6))
        val base = MicSweeper(SynthSource(), 300).passes(3, band, RfSweep.BASE_DB)
        val now = MicSweeper(SynthSource(), 300).passes(3, band, RfSweep.FLAG_DB)
        assertTrue(RfSweep.newCarriers(now, base.keys).isEmpty())
    }

    private fun run(src: SynthSource, target: Double, baseline: List<Double> = emptyList()): MicSweeper.ToneResult =
        MicSweeper(src, 300).toneTest(target, baseline, false, { p, w -> src.play(p, w) }, rnd = Random(11))

    @Test fun toneComesBackFromARealBug() {
        val r = run(SynthSource(bugHz = 433.92e6, steady = listOf(435.0e6 to 0.5)), 433.92e6, listOf(435.0e6))
        assertEquals(Verdict.CONFIRMED, r.verdict)
        assertEquals(2, r.controls.size)
        assertTrue(r.target.p <= 0.01)
    }

    @Test fun plainCarrierIsNotConfirmed() {
        val src = SynthSource(steady = listOf(433.92e6 to 0.5))
        val r = run(src, 433.92e6)
        assertNotEquals(Verdict.CONFIRMED, r.verdict)
    }

    @Test fun couplingIsNeverAnAlert() {
        val src = SynthSource(bugHz = null, leak = 0.4, steady = listOf(433.92e6 to 0.5, 435.0e6 to 0.5))
        val r = run(src, 433.92e6, listOf(435.0e6))
        assertEquals(Verdict.COUPLING, r.verdict)
    }

    @Test fun noControlMeansNoAlert() {
        val t = RfSweep.ToneScore(0.95, 12.0, 0.002, 100)
        val quiet = RfSweep.ToneScore(0.1, 0.5, 0.6, 0)
        assertEquals(Verdict.POSSIBLE, RfSweep.verdict(t, emptyList()))
        assertEquals(Verdict.COUPLING, RfSweep.verdict(t, listOf(quiet, RfSweep.ToneScore(0.9, 10.0, 0.004, 100))))
        assertEquals(Verdict.CONFIRMED, RfSweep.verdict(t, listOf(quiet, quiet)))
    }

    @Test fun rtlTcpSpeaksTheProtocol() {
        val srv = ServerSocket(0)
        val got = java.util.concurrent.LinkedBlockingQueue<List<Int>>()
        Thread {
            srv.accept().use { s ->
                s.getOutputStream().write(byteArrayOf('R'.code.toByte(), 'T'.code.toByte(), 'L'.code.toByte(), '0'.code.toByte(), 0, 0, 0, 5, 0, 0, 0, 29))
                s.getOutputStream().flush()
                val i = s.getInputStream()
                repeat(5) { val b = ByteArray(5); var o = 0; while (o < 5) o += i.read(b, o, 5 - o); got.add(b.map { it.toInt() and 0xFF }) }
                s.getOutputStream().write(ByteArray(100) { it.toByte() }); s.getOutputStream().flush()
            }
        }.start()
        RtlTcp("127.0.0.1", srv.localPort).use { c ->
            assertEquals(5, c.tunerType)
            c.tune(433_920_000.0, 2_400_000); c.setGain(300)
            assertEquals(100, c.read(100).size)
        }
        val cmds = generateSequence { got.poll(2, java.util.concurrent.TimeUnit.SECONDS) }.take(5).toList()
        assertEquals(listOf(0x02, 0x01, 0x03, 0x08, 0x04), cmds.map { it[0] })
        assertEquals(433_920_000L, cmds[1].drop(1).fold(0L) { a, b -> a * 256 + b })   // frequency, big-endian
        assertEquals(1, cmds[2][4]); assertEquals(0, cmds[3][4])                      // manual gain on, AGC off
        srv.close()
    }
}
