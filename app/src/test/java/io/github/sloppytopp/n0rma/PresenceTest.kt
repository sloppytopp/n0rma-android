package io.github.sloppytopp.n0rma

import io.github.sloppytopp.n0rma.detect.PresenceEvent
import io.github.sloppytopp.n0rma.detect.PresenceSummary
import io.github.sloppytopp.n0rma.detect.PresenceTracker
import io.github.sloppytopp.n0rma.detect.WifiRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceTest {
    private fun hidden(bssid: String, level: Int = -50) = WifiRow("", bssid, level, "", "other")
    private val named = WifiRow("Cafe", "00:11:22:33:44:55", -60, "", "other")
    private val s = 1000L

    @Test fun appearsOnceThenGoneAfterMissedScans() {
        val t = PresenceTracker()
        val a = hidden("d6:e2:2f:b6:20:10")
        assertEquals(listOf("seen"), t.onScan(0, listOf(a)).map { it.kind })
        assertTrue(t.onScan(30 * s, listOf(a)).isEmpty())
        assertTrue(t.onScan(60 * s, listOf(a)).isEmpty())
        assertTrue(t.onScan(120 * s, emptyList()).isEmpty())          // missed, not yet gone
        val gone = t.onScan(220 * s, emptyList())
        assertEquals(listOf("gone"), gone.map { it.kind })
        assertEquals(60 * s, gone[0].ts)                              // timestamped at the last sighting
    }

    @Test fun comesBackAsASecondAppearance() {
        val t = PresenceTracker()
        val a = hidden("d6:e2:2f:b6:20:10")
        t.onScan(0, listOf(a)); t.onScan(200 * s, emptyList())
        assertEquals(listOf("seen"), t.onScan(400 * s, listOf(a)).map { it.kind })
    }

    @Test fun ordinaryNamedNetworkIsNotLogged() = assertTrue(PresenceTracker().onScan(0, listOf(named)).isEmpty())

    @Test fun privateAddressWithANameIsLogged() =
        assertEquals(1, PresenceTracker().onScan(0, listOf(WifiRow("Guest", "1a:df:3e:21:36:d6", -40, "", "other"))).size)

    @Test fun rotatingAddressesAreSeparateSources() {
        val t = PresenceTracker()
        val ev = listOf("d6:aa:00:00:00:01", "d6:aa:00:00:00:02", "d6:aa:00:00:00:03").flatMap { t.onScan(0, listOf(hidden(it))) }
        val src = PresenceSummary.build(ev, 10 * s, 3_600_000, true)
        assertEquals(3, src.size)
    }

    @Test fun summaryClipsToWindowAndStopClosesOpenIntervals() {
        val ev = listOf(
            PresenceEvent(0, "*", "start", "start", 0, 0),
            PresenceEvent(100, "wifi:x", "(hidden)", "seen", -50, 0),
            PresenceEvent(300, "*", "stop", "stop", 0, 0),
            PresenceEvent(1000, "*", "start", "start", 0, 0),
        )
        val src = PresenceSummary.build(ev, 2000, 5000, true).single()
        assertEquals(1, src.appearances); assertEquals(200L, src.totalMs); assertTrue(src.hidden)
        assertEquals(300L + 1000L, PresenceSummary.scanningMs(ev, 2000, 5000, true))
    }
}
