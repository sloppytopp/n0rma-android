package io.github.sloppytopp.homewatch

import io.github.sloppytopp.homewatch.detect.Level.*
import io.github.sloppytopp.homewatch.ui.defconLevel
import io.github.sloppytopp.homewatch.ui.radarAlpha
import org.junit.Assert.*
import org.junit.Test

class DefconTest {
    @Test fun mapping() {
        assertEquals(0, defconLevel(listOf(OFF, OFF), false))
        assertEquals(5, defconLevel(listOf(OK, OFF, OK), false))
        assertEquals(4, defconLevel(listOf(OK, WATCH), false))
        assertEquals(3, defconLevel(listOf(WATCH, WATCH), false))
        assertEquals(2, defconLevel(listOf(OK, ALERT), false))
        assertEquals(1, defconLevel(listOf(ALERT, OK), true))     // drone alert
        assertEquals(1, defconLevel(listOf(ALERT, ALERT), false)) // two alerts
    }

    @Test fun sweepFade() {
        assertEquals(1f, radarAlpha(1.0, 1.0, false), 1e-6f)            // beam on the blip: full
        assertTrue(radarAlpha(1.0, 1.0 + 0.2, false) < 1f)              // just ahead of the beam: faded
        assertTrue(radarAlpha(1.0, 1.1, false) > 0.15f)
        assertEquals(0.7f, radarAlpha(1.0, 1.0 + 0.2, true).coerceAtMost(0.7f), 1e-6f)
        assertTrue(radarAlpha(3.0, 3.0 + 0.01, true) >= 0.7f)           // alerts never go dark
    }
}

class BearingsTest {
    @Test fun measuredBearingExpires() {
        io.github.sloppytopp.homewatch.ui.Bearings.clear()
        io.github.sloppytopp.homewatch.ui.Bearings.put("wifi:aa", 120.0, now = 1_000)
        assertEquals(120.0, io.github.sloppytopp.homewatch.ui.Bearings.get("wifi:aa", now = 1_000 + 9 * 60_000)!!, 1e-9)
        assertNull(io.github.sloppytopp.homewatch.ui.Bearings.get("wifi:aa", now = 1_000 + 11 * 60_000))
        assertNull(io.github.sloppytopp.homewatch.ui.Bearings.get("nope", now = 1_000))
    }
}
