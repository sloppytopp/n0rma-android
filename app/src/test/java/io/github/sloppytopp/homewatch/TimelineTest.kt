package io.github.sloppytopp.homewatch

import io.github.sloppytopp.homewatch.detect.EventRow
import io.github.sloppytopp.homewatch.detect.Level
import io.github.sloppytopp.homewatch.detect.Timeline
import io.github.sloppytopp.homewatch.detect.TimelineFilter
import org.junit.Assert.*
import org.junit.Test

class TimelineTest {
    private val ev = listOf(
        EventRow(1000, "tracker", Level.WATCH, "Find My tracker nearby"),
        EventRow(3000, "drone", Level.ALERT, "Drone signal"),
        EventRow(2000, "network", Level.OK, "New Wi-Fi network appeared"),
        EventRow(4000, "camera", Level.WATCH, "Camera-like Wi-Fi"),
    )

    @Test fun plainWords() {
        assertEquals("Suspect", Timeline.word(Level.WATCH))
        assertEquals("Alert", Timeline.word(Level.ALERT))
        assertEquals("Info", Timeline.word(Level.OK))
        assertEquals("Info", Timeline.word(Level.OFF))
    }

    @Test fun filtersAndSortsNewestFirst() {
        assertEquals(listOf(4000L, 3000L, 2000L, 1000L), Timeline.filter(ev, TimelineFilter.ALL).map { it.ts })
        assertEquals(listOf(4000L, 1000L), Timeline.filter(ev, TimelineFilter.SUSPECT).map { it.ts })
        assertEquals(listOf(3000L), Timeline.filter(ev, TimelineFilter.ALERT).map { it.ts })
        assertEquals(listOf(2000L), Timeline.filter(ev, TimelineFilter.INFO).map { it.ts })
    }

    @Test fun resolvedEventsLeaveTheUnresolvedCount() {
        assertEquals(3, Timeline.unresolved(ev, emptySet()))          // info events never count
        assertEquals(2, Timeline.unresolved(ev, setOf(Timeline.key(ev[0]))))
        assertEquals(0, Timeline.unresolved(ev, ev.map { Timeline.key(it) }.toSet()))
    }

    @Test fun keysAreStablePerEvent() {
        assertEquals("1000|tracker", Timeline.key(ev[0]))
        assertNotEquals(Timeline.key(ev[0]), Timeline.key(ev[1]))
    }
}

class RebaselineTest {
    private fun scan(locked: Boolean): List<EventRow> {
        val e = io.github.sloppytopp.homewatch.detect.Engine({ 1_700_000_000_000L }, { _, _ -> }).also { it.running = true; it.wifiEnabled = true }
        e.loadKnownNets(setOf("aa:bb:cc:00:00:09"))
        e.baselineLocked = locked
        e.onWifiScan(listOf(
            io.github.sloppytopp.homewatch.detect.WifiObs("aa:bb:cc:00:00:09", "Home", -50),
            io.github.sloppytopp.homewatch.detect.WifiObs("aa:bb:cc:00:00:10", "NewNet", -50)))
        return e.snapshot().events.filter { it.domain == "network" }
    }

    @Test fun newNetworkBeforeBaselineIsInfo() {
        val ev = scan(false)
        assertEquals(1, ev.size); assertEquals(Level.OK, ev[0].level)
    }

    @Test fun newNetworkAfterBaselineIsSuspect() {
        val ev = scan(true)
        assertEquals(1, ev.size); assertEquals(Level.WATCH, ev[0].level)
        assertTrue(ev[0].msg.contains("since your baseline"))
    }

    @Test fun lockedNetworksDoNotFlag() {
        val e = io.github.sloppytopp.homewatch.detect.Engine({ 1_700_000_000_000L }, { _, _ -> }).also { it.running = true; it.wifiEnabled = true }
        e.loadKnownNets(setOf("aa:bb:cc:00:00:09"))
        e.lockKnownNets(listOf("aa:bb:cc:00:00:10"))
        e.baselineLocked = true
        e.onWifiScan(listOf(io.github.sloppytopp.homewatch.detect.WifiObs("aa:bb:cc:00:00:10", "NewNet", -50)))
        assertTrue(e.snapshot().events.none { it.domain == "network" })
    }
}
