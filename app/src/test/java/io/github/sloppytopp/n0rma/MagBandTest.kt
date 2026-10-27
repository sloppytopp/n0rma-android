package io.github.sloppytopp.n0rma

import io.github.sloppytopp.n0rma.detect.MagLevel
import io.github.sloppytopp.n0rma.detect.MagMeter
import io.github.sloppytopp.n0rma.detect.Survey
import io.github.sloppytopp.n0rma.detect.SurveyPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class MagBandTest {
    private fun pt(key: String, freq: Int, rssi: Int = -60, kind: String = "wifi") = SurveyPoint(0, kind, key, key, rssi, 0.0, 0.0, 1f, freq = freq)

    @Test fun bandsFromFrequency() {
        assertEquals("2.4", Survey.band(2437)); assertEquals("5", Survey.band(5180)); assertEquals("6", Survey.band(5955))
        assertEquals("", Survey.band(0)); assertEquals("", Survey.band(900))
    }
    @Test fun filterKeepsOneBandOnly() {
        val ps = listOf(pt("a", 2412), pt("b", 5180), pt("t", 0, kind = "tracker"))
        assertEquals(listOf("a"), Survey.onBand(ps, "2.4").map { it.key })
        assertEquals(3, Survey.onBand(ps, "all").size)
    }
    @Test fun statsCountDistinctNetworksAndStrongest() {
        val s = Survey.bandStats(listOf(pt("a", 2412, -70), pt("a", 2412, -50), pt("b", 2437, -80), pt("c", 5180, -60), pt("t", 0, kind = "tracker")))
        assertEquals(listOf("2.4", "5"), s.map { it.band })
        assertEquals(2, s[0].networks); assertEquals(-50, s[0].strongest); assertEquals(1, s[1].networks)
    }
    @Test fun noBaselineMeansNoLevel() { val m = MagMeter(); m.update(30f, 0f, 0f); assertEquals(MagLevel.NO_BASELINE, m.level); assertEquals(0f, m.delta, 0f) }
    @Test fun readingAboveBaselineRaisesLevel() {
        val m = MagMeter(alpha = 1f); m.update(40f, 0f, 0f); m.setBaseline()
        m.update(42f, 0f, 0f); assertEquals(MagLevel.QUIET, m.level)
        m.update(55f, 0f, 0f); assertEquals(MagLevel.NOTICEABLE, m.level)
        m.update(100f, 0f, 0f); assertEquals(MagLevel.STRONG, m.level); assertEquals(60f, m.peak, 0.01f)
    }
    @Test fun dropBelowBaselineIsZeroNotNegative() { val m = MagMeter(alpha = 1f); m.update(50f, 0f, 0f); m.setBaseline(); m.update(30f, 0f, 0f); assertEquals(0f, m.delta, 0f) }
    @Test fun fillIsClamped() { val m = MagMeter(alpha = 1f); m.update(10f, 0f, 0f); m.setBaseline(); m.update(500f, 0f, 0f); assertEquals(1f, m.fill, 0f) }
}
