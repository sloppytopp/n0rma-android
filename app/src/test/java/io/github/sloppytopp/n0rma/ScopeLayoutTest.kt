package io.github.sloppytopp.n0rma

import io.github.sloppytopp.n0rma.ui.ScopeLayout
import org.junit.Assert.*
import org.junit.Test

class ScopeLayoutTest {
    @Test fun bandsFromFrequency() {
        assertEquals(0, ScopeLayout.wifiBand(2412))
        assertEquals(0, ScopeLayout.wifiBand(2484))
        assertEquals(1, ScopeLayout.wifiBand(5180))
        assertEquals(1, ScopeLayout.wifiBand(5745))
        assertEquals(2, ScopeLayout.wifiBand(5955))
        assertEquals(2, ScopeLayout.wifiBand(7115))
        assertEquals(-1, ScopeLayout.wifiBand(0))
        assertEquals(-1, ScopeLayout.wifiBand(900))
    }

    @Test fun wifiDotsStayInsideTheirBandWedge() {
        for ((freq, band) in listOf(2412 to 0, 2437 to 0, 2484 to 0, 5180 to 1, 5895 to 1, 5955 to 2, 7115 to 2)) {
            for (key in listOf("aa:bb:cc:00:00:01", "02:11:22:33:44:55", "x", "")) {
                val a = ScopeLayout.wifiAngle(freq, key)
                assertTrue("$freq/$key -> $a", a > band * ScopeLayout.WEDGE_DEG && a < (band + 1) * ScopeLayout.WEDGE_DEG)
            }
        }
    }

    @Test fun unknownFrequencySpreadsAroundTheWholeScope() {
        for (key in listOf("a", "b", "c", "wifi:aa:bb")) {
            val a = ScopeLayout.wifiAngle(0, key)
            assertTrue(a >= 0f && a < 360f)
        }
    }

    @Test fun neighbouringChannelsSitNextToEachOther() {
        // same key, so only the channel differs: channel 1 is left of channel 11
        assertTrue(ScopeLayout.wifiAngle(2412, "k") < ScopeLayout.wifiAngle(2462, "k"))
    }

    @Test fun bleDotsStayInsideTheirKindWedge() {
        for (kind in listOf(ScopeLayout.BLE_DRONE, ScopeLayout.BLE_TRACKER, ScopeLayout.BLE_OTHER)) {
            for (key in listOf("00:00:00:00:00:01", "DE:AD:00:00:00:01", "")) {
                val a = ScopeLayout.bleAngle(kind, key)
                assertTrue(a > kind * ScopeLayout.WEDGE_DEG && a < (kind + 1) * ScopeLayout.WEDGE_DEG)
            }
        }
    }

    @Test fun positionIsStableAndSpreadIsInRange() {
        assertEquals(ScopeLayout.spread("abc"), ScopeLayout.spread("abc"), 0f)
        for (k in listOf("", "a", "negative-hash-key-zzzzzzzz", "￿")) assertTrue(ScopeLayout.spread(k) in 0f..1f)
    }

    @Test fun closerMeansCloserToTheCentre() {
        assertTrue(ScopeLayout.radius(2.0) < ScopeLayout.radius(15.0))
        assertTrue(ScopeLayout.radius(15.0) < ScopeLayout.radius(60.0))
        assertTrue(ScopeLayout.radius(500.0) <= 1f)
    }
}
