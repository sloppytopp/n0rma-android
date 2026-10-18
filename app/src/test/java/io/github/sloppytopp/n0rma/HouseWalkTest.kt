package io.github.sloppytopp.n0rma

import io.github.sloppytopp.n0rma.detect.HouseWalk
import io.github.sloppytopp.n0rma.detect.RoomItem
import io.github.sloppytopp.n0rma.detect.RoomScan
import io.github.sloppytopp.n0rma.detect.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HouseWalkTest {
    private fun wifi(key: String, ssid: String, rssi: Double) = RoomItem("wifi:$key", "wifi", ssid, rssi)
    private val kitchen = RoomScan("Kitchen", 1, listOf(
        wifi("hal", "HAL9000", -72.0), wifi("hidden", "", -42.0), wifi("router", "guest", -30.0),
        RoomItem("ble:tag", "tracker", "Tile tracker", -50.0), wifi("mid", "Somebody", -56.0),
    ))
    private val bedroom = RoomScan("Bedroom", 2, listOf(
        wifi("hal", "HAL9000", -66.0), wifi("hidden", "", -44.0), wifi("router", "guest", -35.0),
        RoomItem("ble:tag", "tracker", "Tile tracker", -78.0), wifi("mid", "Somebody", -58.0),
    ))
    private fun find(key: String) = HouseWalk.report(listOf(kitchen, bedroom)) { it.label == "guest" }.first { it.key == key }

    @Test fun neighborIsWeakEverywhere() = assertEquals(Verdict.OUTSIDE, find("wifi:hal").verdict)
    @Test fun strongHiddenSourceIsInsideAndEverywhere() { find("wifi:hidden").let { assertEquals(Verdict.INSIDE, it.verdict); assertNull(it.room) } }
    @Test fun ownNetworkIsMine() = assertEquals(Verdict.MINE, find("wifi:router").verdict)
    @Test fun trackerLoudInOneRoomIsInsideThere() { find("ble:tag").let { assertEquals(Verdict.INSIDE, it.verdict); assertEquals("Kitchen", it.room) } }
    @Test fun moderateSignalIsUnclear() = assertEquals(Verdict.UNCLEAR, find("wifi:mid").verdict)
    @Test fun unnamedBluetoothIsIgnored() {
        val s = RoomScan("A", 1, listOf(RoomItem("ble:x", "ble", "Bluetooth device", -40.0)))
        assertEquals(0, HouseWalk.report(listOf(s)) { false }.size)
    }
}
