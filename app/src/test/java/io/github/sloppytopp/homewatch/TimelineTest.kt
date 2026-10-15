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

class RebaselineSafetyTest {
    @Test fun emptyKnownListAfterBaselineStillFlagsNewNetworks() {
        val e = io.github.sloppytopp.homewatch.detect.Engine({ 1_700_000_000_000L }, { _, _ -> }).also { it.running = true; it.wifiEnabled = true }
        e.loadKnownNets(emptySet())
        e.baselineLocked = true
        e.onWifiScan(listOf(io.github.sloppytopp.homewatch.detect.WifiObs("aa:bb:cc:00:00:10", "NewNet", -50)))
        assertTrue(e.snapshot().events.any { it.domain == "network" && it.level == Level.WATCH })
    }

    @Test fun floodingNeverWipesTheBaseline() {
        val e = io.github.sloppytopp.homewatch.detect.Engine({ 1_700_000_000_000L }, { _, _ -> }).also { it.running = true; it.wifiEnabled = true }
        e.loadKnownNets(setOf("aa:bb:cc:00:00:01"))
        e.baselineLocked = true
        repeat(60) { chunk ->
            e.onWifiScan((0 until 100).map { io.github.sloppytopp.homewatch.detect.WifiObs("02:00:%02x:%02x:00:00".format(chunk, it), "n$chunk-$it", -90) })
        }
        // the locked network must still be known: seeing it again must not log it as new
        val before = e.snapshot().events.size
        e.onWifiScan(listOf(io.github.sloppytopp.homewatch.detect.WifiObs("aa:bb:cc:00:00:01", "Home", -40)))
        assertEquals(before, e.snapshot().events.size)
    }
}

class RebaselineBoundTest {
    @Test fun knownListStopsGrowingAtTheCapAndWarnsOnce() {
        val e = io.github.sloppytopp.homewatch.detect.Engine({ 1_700_000_000_000L }, { _, _ -> }).also { it.running = true; it.wifiEnabled = true }
        e.loadKnownNets((0 until 5000).map { "02:aa:%02x:%02x:%02x:00".format(it shr 16, (it shr 8) and 255, it and 255) }.toSet())
        e.baselineLocked = true
        val persisted = mutableListOf<String>()
        // many scans of fresh random networks
        repeat(20) { chunk -> e.onWifiScan((0 until 100).map { io.github.sloppytopp.homewatch.detect.WifiObs("0a:bb:%02x:%02x:00:00".format(chunk, it), "x$chunk-$it", -90) }) }
        val flood = e.snapshot().events.filter { it.msg.contains("possible flood") }
        assertEquals(1, flood.size)                                             // one warning, not one event per name
        assertTrue(e.snapshot().events.size < 50)                               // and the event list stays small
        // a close-by rogue network is still named individually, even in the middle of a flood
        e.onWifiScan(listOf(io.github.sloppytopp.homewatch.detect.WifiObs("0a:ee:00:00:00:01", "EvilTwin", -40)))
        assertTrue(e.snapshot().events.any { it.msg.contains("EvilTwin") })
        // the original baseline is intact: an original network seen again is not "new"
        val before = e.snapshot().events.size
        e.onWifiScan(listOf(io.github.sloppytopp.homewatch.detect.WifiObs("02:aa:00:00:07:00", "orig", -50)))
        assertEquals(before, e.snapshot().events.size)
    }
}
