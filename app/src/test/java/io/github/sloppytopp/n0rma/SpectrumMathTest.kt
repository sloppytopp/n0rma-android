package io.github.sloppytopp.n0rma

import io.github.sloppytopp.n0rma.detect.SpectrumMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class SpectrumMathTest {
    private fun tone(n: Int, cyclesPerSample: Double, amp: Double = 0.5): ByteArray {
        val b = ByteArray(2 * n)
        for (i in 0 until n) {
            b[2 * i] = (127.5 + 127.5 * amp * cos(2 * PI * cyclesPerSample * i)).toInt().coerceIn(0, 255).toByte()
            b[2 * i + 1] = (127.5 + 127.5 * amp * sin(2 * PI * cyclesPerSample * i)).toInt().coerceIn(0, 255).toByte()
        }
        return b
    }

    @Test fun toneAtPlus300kHzLandsInTheRightBin() {
        val nfft = 1024; val rate = 2_400_000
        val db = SpectrumMath.psdDb(tone(nfft * 8, 300_000.0 / rate), nfft, 8)
        val peak = db.indices.maxByOrNull { db[it] }!!
        assertTrue(Math.abs(SpectrumMath.binAt(100_300_000.0, nfft, 100_000_000.0, rate) - peak) <= 1)
        assertTrue(peak > nfft / 2)                                      // positive offset is right of centre
    }

    @Test fun toneAtMinus600kHzIsLeftOfCentre() {
        val nfft = 1024; val rate = 2_400_000
        val db = SpectrumMath.psdDb(tone(nfft * 8, -600_000.0 / rate), nfft, 8)
        val peak = db.indices.maxByOrNull { db[it] }!!
        assertTrue(peak < nfft / 2)
        assertTrue(Math.abs(SpectrumMath.binAt(99_400_000.0, nfft, 100_000_000.0, rate) - peak) <= 1)
    }

    @Test fun fullScaleToneReadsNearZeroDb() {
        val db = SpectrumMath.psdDb(tone(1024 * 4, 0.1, amp = 1.0), 1024, 4)
        assertTrue(db.max() in -6f..1f)
    }

    @Test fun silenceIsFarBelowATone() {
        val rnd = java.util.Random(1)                                       // 1-LSB noise, no DC offset: what an idle ADC looks like
        val quiet = SpectrumMath.psdDb(ByteArray(2 * 1024 * 4) { (127 + rnd.nextInt(2)).toByte() }, 1024, 4).max()
        assertTrue(quiet < -45f)
    }

    @Test fun shortInputGivesAFloorNotACrash() = assertEquals(256, SpectrumMath.psdDb(ByteArray(10), 256, 4).size)

    @Test fun peakListFindsTwoTonesWithBandNames() {
        val nfft = 2048; val rate = 2_400_000
        val a = tone(nfft * 8, 400_000.0 / rate, 0.4); val b = tone(nfft * 8, -500_000.0 / rate, 0.3)
        val mix = ByteArray(a.size) { (((a[it].toInt() and 0xff) + (b[it].toInt() and 0xff) - 127)).coerceIn(0, 255).toByte() }
        val peaks = SpectrumMath.peaks(SpectrumMath.psdDb(mix, nfft, 8), 100_000_000.0, rate, count = 4)
        assertTrue(peaks.size >= 2)
        assertEquals(100_400_000.0, peaks[0].hz, 5_000.0)
        assertEquals("FM broadcast", peaks[0].label)
    }

    @Test fun bandLabelsAreAllocationsNotIdentifications() {
        assertEquals("NOAA weather radio", SpectrumMath.bandLabel(162_550_000.0))
        assertEquals("FM broadcast", SpectrumMath.bandLabel(99_500_000.0))
        assertTrue(SpectrumMath.bandLabel(1_000_000_000.0).isNotEmpty())
        assertEquals("no common allocation listed", SpectrumMath.bandLabel(7_000_000.0))
    }

    @Test fun fftRejectsNonPowerOfTwo() { try { SpectrumMath.fft(FloatArray(100), FloatArray(100)); org.junit.Assert.fail() } catch (e: IllegalArgumentException) {} }

    @Test fun sweepStepKeepsTheCentreAndRemovesTheDcSpike() {
        val psd = FloatArray(1024) { -30f }
        psd[512] = 20f                                                   // tuner DC spike
        psd[300] = -5f                                                   // a real signal inside the kept part (bins 128..895)
        psd[50] = 40f                                                    // edge roll-off, outside the kept part
        val out = SpectrumMath.sweepStep(psd, 4)
        assertEquals(192, out.size)
        assertEquals(-5f, out.max(), 0f)
        assertEquals(-5f, out[(300 - 128) / 4], 0f)
    }

    @Test fun sweepPeaksAreNumberedStrongestFirstAndLabelled() {
        val db = FloatArray(2000) { -30f }
        db[500] = 8f; db[1200] = 2f; db[1203] = 1f                       // the second pair is one signal, not two
        val peaks = SpectrumMath.sweepPeaks(db, 24e6, 9375.0)
        assertEquals(2, peaks.size)
        assertEquals(24e6 + 500 * 9375.0, peaks[0].hz, 1.0)
        assertEquals(8f, peaks[0].db, 0f)
        assertEquals(-30f, SpectrumMath.noiseFloor(db), 0f)
    }

    @Test fun compareSweepsReportsNewAndGoneCarriersOnce() {
        val base = FloatArray(1000) { -30f }; val now = base.copyOf()
        base[200] = -5f                                                  // was there, now gone
        now[600] = 0f; now[602] = -3f                                    // new (one carrier, two points)
        now[800] = -25f                                                  // only 5 dB up: below the 8 dB rise
        val (new, gone) = SpectrumMath.compareSweeps(base, now, 100e6, 37_500.0)
        assertEquals(1, new.size); assertEquals(100e6 + 600 * 37_500.0, new[0].hz, 1.0); assertEquals(30f, new[0].deltaDb, 0f)
        assertEquals(1, gone.size); assertEquals(100e6 + 200 * 37_500.0, gone[0].hz, 1.0); assertEquals(25f, gone[0].deltaDb, 0f)
    }

    @Test fun baselineFileRoundTripsAndRejectsGarbage() {
        val f = java.io.File.createTempFile("sweep", ".bin")
        val s = io.github.sloppytopp.n0rma.scan.SpectrumRun.Sweep(24e6, 37_500.0, FloatArray(50) { it - 30f }, 328, 1234L)
        io.github.sloppytopp.n0rma.scan.SweepStore.save(f, s)
        val r = io.github.sloppytopp.n0rma.scan.SweepStore.load(f)!!
        assertEquals(328, r.gainTenths); assertEquals(1234L, r.takenAt); assertEquals(50, r.db.size); assertEquals(-5f, r.db[25], 0f)
        f.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        assertTrue(io.github.sloppytopp.n0rma.scan.SweepStore.load(f) == null)
        f.delete()
    }
}
