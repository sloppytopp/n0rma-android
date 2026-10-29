package io.github.sloppytopp.n0rma.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A pretend RTL2832U + R820T on the USB bus: answers the tuner probe and PLL-lock reads, records every write. */
private class FakeBus(private val tunerId: Int = 0x69, private val pllLock: Boolean = true) : UsbBus {
    class Write(val value: Int, val index: Int, val data: ByteArray)
    val writes = ArrayList<Write>()
    private var ptr = 0

    override fun controlIn(value: Int, index: Int, len: Int): ByteArray? {
        if (index == (6 shl 8)) {                          // I2C read from the tuner
            val status = intArrayOf(0, 0, if (pllLock) 0x4a else 0x0a, 0, 0x31)     // decoded status; chip sends bit-reversed
            return if (len == 1 && ptr == 0) byteArrayOf(tunerId.toByte())
            else ByteArray(len) { R82xx.bitrev(status.getOrElse(ptr + it) { 0 }).toByte() }
        }
        return ByteArray(len)
    }

    override fun controlOut(value: Int, index: Int, data: ByteArray): Int {
        writes += Write(value, index, data)
        if (index == ((6 shl 8) or 0x10) && data.size == 1) ptr = data[0].toInt() and 0xff
        return data.size
    }

    override fun bulkIn(buf: ByteArray, len: Int, timeoutMs: Int): Int { for (i in 0 until len) buf[i] = (i and 0xff).toByte(); return len }

    fun demod(page: Int, addr: Int) = writes.filter { it.value == ((addr shl 8) or 0x20) && it.index == (0x10 or page) }
    fun i2cWrites() = writes.filter { it.index == ((6 shl 8) or 0x10) && it.value == R82xx.I2C_ADDR }.map { it.data }
}

class Rtl2832Test {
    @Test fun firstTransferIsTheUsbSysctlDummyWrite() {
        val bus = FakeBus(); Rtl2832(bus).open()
        val w = bus.writes.first()
        assertEquals(0x2000, w.value); assertEquals((1 shl 8) or 0x10, w.index); assertEquals(0x09, w.data[0].toInt())
    }

    @Test fun openFindsTheTunerAndSetsTheIfAndInversion() {
        val bus = FakeBus(); val chip = Rtl2832(bus); chip.open()
        assertEquals("R820T/R820T2", chip.tunerName)
        // 3.57 MHz IF, negated, in the three DDC registers
        assertEquals(listOf(56, 17, 18), listOf(0x19, 0x1a, 0x1b).map { bus.demod(1, it).last().data[0].toInt() and 0xff })
        assertEquals(0x01, bus.demod(1, 0x15).last().data[0].toInt())                // spectrum inversion on
        assertEquals(0x4d, bus.demod(0, 0x08).last().data[0].toInt())                // in-phase ADC only
    }

    @Test fun openFailsPlainlyWhenTheTunerIsNotAnR820T() {
        try { Rtl2832(FakeBus(tunerId = 0x00)).open(); fail("should throw") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("No supported tuner")) }
    }

    @Test fun sampleRateWritesTheResamplerRatio() {
        val bus = FakeBus(); val chip = Rtl2832(bus); chip.open(); chip.setSampleRate(2_400_000)
        assertEquals(2_400_000, chip.rate)
        assertEquals(listOf(0x03, 0x00), bus.demod(1, 0x9f).last().data.map { it.toInt() and 0xff })
        assertEquals(listOf(0x00, 0x00), bus.demod(1, 0xa1).last().data.map { it.toInt() and 0xff })
    }

    @Test fun rejectsRatesTheChipCannotDo() {
        val chip = Rtl2832(FakeBus()).also { it.open() }
        for (bad in listOf(100_000, 500_000, 3_300_000)) try { chip.setSampleRate(bad); fail("accepted $bad") } catch (e: IllegalArgumentException) {}
    }

    private fun pllWrite(bus: FakeBus): IntArray = bus.i2cWrites().last { it.size == 8 && it[0].toInt() == 0x10 }.map { it.toInt() and 0xff }.toIntArray()

    @Test fun tuning100MHzProgramsTheExpectedPll() {
        val bus = FakeBus(); val chip = Rtl2832(bus); chip.open(); chip.setSampleRate(2_400_000); chip.setCenterFreq(100_000_000)
        val w = pllWrite(bus)                                  // [0x10, r10..r16]
        assertEquals(0xca, w[1 + 4]); assertEquals(0x5b, w[1 + 5]); assertEquals(0x90, w[1 + 6])
        assertEquals(3, (w[1] and 0xe0) shr 5)                 // divider exponent after the VCO fine-tune correction
    }

    @Test fun tuning433MHzProgramsTheExpectedPll() {
        val bus = FakeBus(); val chip = Rtl2832(bus); chip.open(); chip.setSampleRate(2_400_000); chip.setCenterFreq(433_920_000)
        val w = pllWrite(bus)
        assertEquals(0xcb, w[1 + 4]); assertEquals(0xcd, w[1 + 5]); assertEquals(0x84, w[1 + 6])
    }

    @Test fun tuningFailsWhenThePllNeverLocks() {
        val chip = Rtl2832(FakeBus(pllLock = false))
        try { chip.open(); chip.setSampleRate(2_400_000); chip.setCenterFreq(100_000_000); fail("should throw") } catch (e: IllegalStateException) {}
    }

    @Test fun gainStepsFollowTheLnaAndMixerTables() {
        val bus = FakeBus(); val chip = Rtl2832(bus); chip.open(); chip.setGain(0)
        val atZero = bus.i2cWrites().toList().size
        chip.setGain(496)
        assertTrue(bus.i2cWrites().size > atZero)              // a higher gain writes the LNA/mixer registers
    }

    @Test fun readReturnsExactlyTheRequestedBytes() {
        val chip = Rtl2832(FakeBus()).also { it.open() }
        assertEquals(40_000, chip.read(40_000).size)
    }

    @Test fun i2cWritesNeverExceedTheEightByteLimit() {
        val bus = FakeBus(); Rtl2832(bus).also { it.open(); it.setSampleRate(2_400_000); it.setCenterFreq(162_550_000) }
        assertTrue(bus.i2cWrites().all { it.size <= 8 })
    }
}
