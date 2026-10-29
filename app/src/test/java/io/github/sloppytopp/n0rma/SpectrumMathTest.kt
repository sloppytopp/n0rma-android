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
}
