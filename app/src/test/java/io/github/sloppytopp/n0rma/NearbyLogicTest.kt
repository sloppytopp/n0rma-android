package io.github.sloppytopp.n0rma

import io.github.sloppytopp.n0rma.detect.WifiRow
import io.github.sloppytopp.n0rma.ui.ArrivalTracker
import io.github.sloppytopp.n0rma.ui.Spectrum
import io.github.sloppytopp.n0rma.ui.SweepReceipt
import io.github.sloppytopp.n0rma.ui.WaterfallBuffer
import org.junit.Assert.*
import org.junit.Test

class NearbyLogicTest {
    private fun w(ssid: String, level: Int, freq: Int) = WifiRow(ssid, "aa:$ssid", level, "", "", "", freq)

    @Test fun channelNumbers() {
        assertEquals(1, Spectrum.channel(2412))
        assertEquals(6, Spectrum.channel(2437))
        assertEquals(13, Spectrum.channel(2472))
        assertEquals(36, Spectrum.channel(5180))
        assertEquals(149, Spectrum.channel(5745))
        assertEquals(1, Spectrum.channel(5955))
        assertEquals(0, Spectrum.channel(0))
    }

    @Test fun emptyAirIsEmptySpectrum() {
        val bins = Spectrum.bins(emptyList())
        assertEquals(13 + Spectrum.CH_5G.size, bins.size)   // no 6 GHz bins until something is heard there
        assertTrue(bins.all { it.load == 0f && it.count == 0 })
        assertEquals(null, Spectrum.busiest(bins))
    }

    @Test fun networkSmearsOverNeighbouringChannelsButNotFarOnes() {
        val bins = Spectrum.bins(listOf(w("a", -50, 2437))).filter { it.section == 0 }.associateBy { it.ch }
        assertEquals(1, bins[6]!!.count)
        assertTrue(bins[6]!!.load > bins[7]!!.load)
        assertTrue(bins[7]!!.load > bins[9]!!.load)
        assertEquals(0f, bins[11]!!.load, 0f)   // 5 channels away: no overlap
        assertEquals(0, bins[7]!!.count)
    }

    @Test fun louderAndMoreNetworksMeanMoreLoad() {
        val quiet = Spectrum.bins(listOf(w("a", -85, 5180))).first { it.ch == 36 }.load
        val loud = Spectrum.bins(listOf(w("a", -45, 5180))).first { it.ch == 36 }.load
        val two = Spectrum.bins(listOf(w("a", -70, 5180), w("b", -70, 5180))).first { it.ch == 36 }
        assertTrue(loud > quiet)
        assertEquals(2, two.count)
        assertTrue(two.load <= 1f)
    }

    @Test fun sixGhzBinsAppearOnlyWhenHeard() {
        val bins = Spectrum.bins(listOf(w("a", -70, 6055)))
        val six = bins.filter { it.section == 2 }
        assertEquals(1, six.size)
        assertEquals(21, six[0].ch)
        assertEquals("Busiest: 6 GHz channel 21 (1 network)", Spectrum.busiest(bins))
    }

    @Test fun busiestPicksTheMostCrowdedChannel() {
        val bins = Spectrum.bins(listOf(w("a", -50, 2412), w("b", -50, 2437), w("c", -55, 2437), w("d", -60, 2437)))
        assertEquals("Busiest: 2.4 GHz channel 6 (3 networks)", Spectrum.busiest(bins))
    }

    @Test fun waterfallScrollsAndBinsByStrength() {
        val b = WaterfallBuffer(cols = 3, rows = 14)
        assertEquals(0, b.row(-100)); assertEquals(13, b.row(-20)); assertEquals(0, b.row(-130))
        b.push(listOf(-100, -100, -60))
        assertEquals(2, b.cell(2, 0))                    // newest column is on the right
        assertEquals(1, b.cell(2, b.row(-60)))
        assertEquals(0, b.cell(0, 0))                    // not filled yet
        b.push(emptyList()); b.push(emptyList()); b.push(listOf(-40))
        assertEquals(0, b.cell(1, 0))
        assertEquals(1, b.cell(2, b.row(-40)))
        assertEquals(0, b.cell(0, 0))                    // the first column has scrolled off
    }

    @Test fun firstLookIsBaselineThenNewDevicesAreFlagged() {
        val t = ArrivalTracker()
        t.update(emptyList(), 0)                         // nothing yet: still waiting for a baseline
        t.update(listOf("a", "b"), 1_000)                // baseline: nothing is "new"
        assertFalse(t.isNew("a", 1_500))
        t.update(listOf("a", "b", "c"), 10_000)
        assertTrue(t.justArrived("c", 12_000))
        assertTrue(t.isNew("c", 20_000))
        assertFalse(t.justArrived("c", 15_000))          // older than 4 s
        assertFalse(t.isNew("c", 30_000))                // older than 15 s
        assertFalse(t.isNew("a", 10_500))
        t.update(listOf("a", "b", "c"), 40_000)          // seen again later: not re-flagged
        assertFalse(t.isNew("c", 40_100))
    }

    @Test fun anEmptyButReportingRadioStillBaselines() {
        val t = ArrivalTracker()
        t.update(emptyList(), 0, ready = true)           // e.g. Bluetooth scanning is running and nothing is in range yet
        t.update(listOf("tracker"), 5_000, ready = true)
        assertTrue(t.justArrived("tracker", 5_500))      // a tracker turning up in an empty room is exactly what to call out
    }

    @Test fun receiptSaysWhatWasAndWasNotChecked() {
        val r = SweepReceipt.build(0, true, 12, 1, 340, 0, 1, 1, false)
        assertEquals("Wi-Fi: 12 networks heard, 1 look like cameras or drones", r.checked[0])
        assertEquals("Bluetooth: 340 signals heard", r.checked[1])
        assertEquals("Trackers: none heard", r.checked[2])
        assertEquals("Drones: 1 Remote ID claim", r.checked[3])
        assertTrue(r.notChecked.any { it.startsWith("Cellular") })
        assertTrue(r.notChecked.any { it.startsWith("Hidden microphones") })
    }

    @Test fun receiptAdmitsAMissedWifiScan() {
        val r = SweepReceipt.build(0, false, 0, 0, -5, 2, 0, 0, true)
        assertTrue(r.checked.none { it.startsWith("Wi-Fi") })
        assertTrue(r.notChecked[0].startsWith("Wi-Fi: no fresh Wi-Fi scan"))
        assertEquals("Bluetooth: 0 signals heard", r.checked[0])
        assertEquals("Trackers: 2 tracker signals in range", r.checked[1])
        assertTrue(r.notChecked.none { it.startsWith("Hidden microphones") })
    }
}
