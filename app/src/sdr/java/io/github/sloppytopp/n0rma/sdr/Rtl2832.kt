/*
 * RTL2832U USB demodulator driver, a Kotlin port of the register sequences of librtlsdr (librtlsdr.c,
 * Copyright (C) 2012 Steve Markgraf, Dimitri Stolnikov, Hoernchen, Kyle Keen and others, GPL-2.0-or-later).
 *
 * This file is part of the optional N0RMA SDR build and is licensed under the GNU General Public License,
 * version 2 or (at your option) any later version. The rest of N0RMA is MIT licensed; the standard build does not contain this file.
 */
package io.github.sloppytopp.n0rma.sdr

/** The three USB operations the RTL2832U needs, so the register logic can be tested without a phone. */
interface UsbBus {
    /** Vendor control read, request 0. Returns the bytes read or null on failure. */
    fun controlIn(value: Int, index: Int, len: Int): ByteArray?
    /** Vendor control write, request 0. Returns bytes written or a negative number on failure. */
    fun controlOut(value: Int, index: Int, data: ByteArray): Int
    /** Bulk read from the IQ endpoint. Returns bytes read or a negative number on failure/timeout. */
    fun bulkIn(buf: ByteArray, len: Int, timeoutMs: Int): Int
}

class Rtl2832(private val bus: UsbBus) : AutoCloseable {
    companion object {
        const val XTAL = 28_800_000
        const val IF_FREQ = 3_570_000
        private const val DEMODB = 0; private const val USBB = 1; private const val SYSB = 2; private const val IICB = 6
        private const val USB_SYSCTL = 0x2000; private const val USB_EPA_MAXPKT = 0x2158; private const val USB_EPA_CTL = 0x2148
        private const val DEMOD_CTL = 0x3000; private const val DEMOD_CTL_1 = 0x300b
        private val FIR = intArrayOf(-54, -36, -41, -40, -32, -14, 14, 53, 101, 156, 215, 273, 327, 372, 404, 421)
        const val MIN_RATE = 900_001; const val MAX_RATE = 3_200_000
    }

    var centerHz = 0L; private set
    var rate = 0; private set
    private lateinit var tuner: R82xx
    var tunerName = "unknown"; private set

    // ---- raw registers ----
    private fun writeReg(block: Int, addr: Int, v: Int, len: Int): Int {
        val data = if (len == 1) byteArrayOf((v and 0xff).toByte()) else byteArrayOf((v shr 8).toByte(), (v and 0xff).toByte())
        return bus.controlOut(addr, (block shl 8) or 0x10, data)
    }

    private fun demodRead(page: Int, addr: Int, len: Int): Int {
        val d = bus.controlIn((addr shl 8) or 0x20, page, len) ?: return -1
        return if (d.size >= 2) ((d[1].toInt() and 0xff) shl 8) or (d[0].toInt() and 0xff) else (d.firstOrNull()?.toInt()?.and(0xff) ?: 0)
    }

    private fun demodWrite(page: Int, addr: Int, v: Int, len: Int) {
        val data = if (len == 1) byteArrayOf((v and 0xff).toByte()) else byteArrayOf((v shr 8).toByte(), (v and 0xff).toByte())
        val r = bus.controlOut((addr shl 8) or 0x20, 0x10 or page, data)
        check(r == len) { "demod write failed page=$page addr=${"%02x".format(addr)} rc=$r" }
        demodRead(0x0a, 0x01, 1)                        // the chip needs this dummy read after every demod write
    }

    private val i2c = object : I2cBus {
        override fun write(addr: Int, data: ByteArray) = bus.controlOut(addr, (IICB shl 8) or 0x10, data)
        override fun read(addr: Int, len: Int) = bus.controlIn(addr, IICB shl 8, len) ?: ByteArray(0)
    }

    private fun repeater(on: Boolean) = demodWrite(1, 0x01, if (on) 0x18 else 0x10, 1)

    private fun setFir() {
        val fir = IntArray(20)
        for (i in 0 until 8) fir[i] = FIR[i] and 0xff
        var i = 0
        while (i < 8) {
            val v0 = FIR[8 + i]; val v1 = FIR[8 + i + 1]
            fir[8 + i * 3 / 2] = (v0 shr 4) and 0xff
            fir[8 + i * 3 / 2 + 1] = ((v0 shl 4) or ((v1 shr 8) and 0x0f)) and 0xff
            fir[8 + i * 3 / 2 + 2] = v1 and 0xff
            i += 2
        }
        for (k in fir.indices) demodWrite(1, 0x1c + k, fir[k], 1)
    }

    private fun initBaseband() {
        writeReg(USBB, USB_SYSCTL, 0x09, 1)
        writeReg(USBB, USB_EPA_MAXPKT, 0x0002, 2)
        writeReg(USBB, USB_EPA_CTL, 0x1002, 2)
        writeReg(SYSB, DEMOD_CTL_1, 0x22, 1)            // power on demod
        writeReg(SYSB, DEMOD_CTL, 0xe8, 1)
        demodWrite(1, 0x01, 0x14, 1); demodWrite(1, 0x01, 0x10, 1)        // soft reset
        demodWrite(1, 0x15, 0x00, 1)                    // no spectrum inversion, no adjacent channel rejection
        demodWrite(1, 0x16, 0x0000, 2)
        for (i in 0 until 6) demodWrite(1, 0x16 + i, 0x00, 1)             // clear DDC shift and IF registers
        setFir()
        demodWrite(0, 0x19, 0x05, 1)                    // SDR mode, DAGC off
        demodWrite(1, 0x93, 0xf0, 1); demodWrite(1, 0x94, 0x0f, 1)
        demodWrite(1, 0x11, 0x00, 1)                    // AGC off
        demodWrite(1, 0x04, 0x00, 1)                    // RF and IF AGC loop off
        demodWrite(0, 0x61, 0x60, 1)                    // PID filter off
        demodWrite(0, 0x06, 0x80, 1)
        demodWrite(1, 0xb1, 0x1b, 1)                    // zero-IF, DC cancel, IQ comp
        demodWrite(0, 0x0d, 0x83, 1)                    // no 4.096 MHz clock output
    }

    private fun setIfFreq(freqHz: Int) {
        val ifFreq = -((freqHz.toDouble() * 4194304.0 / XTAL).toInt())
        demodWrite(1, 0x19, (ifFreq shr 16) and 0x3f, 1)
        demodWrite(1, 0x1a, (ifFreq shr 8) and 0xff, 1)
        demodWrite(1, 0x1b, ifFreq and 0xff, 1)
    }

    private fun i2cReadReg(addr: Int, reg: Int): Int {
        i2c.write(addr, byteArrayOf(reg.toByte()))
        val d = i2c.read(addr, 1)
        return if (d.isEmpty()) -1 else d[0].toInt() and 0xff
    }

    /** Brings the dongle up and finds the R820T/R820T2 tuner (RTL-SDR Blog V3). Throws if it is another tuner. */
    fun open() {
        if (writeReg(USBB, USB_SYSCTL, 0x09, 1) < 0) throw IllegalStateException("The dongle did not answer on USB")
        initBaseband()
        repeater(true)
        val chk = i2cReadReg(R82xx.I2C_ADDR, R82xx.CHECK_ADDR)
        if (chk != R82xx.CHECK_VAL) {
            repeater(false)
            throw IllegalStateException(if (i2cReadReg(0x74, R82xx.CHECK_ADDR) == R82xx.CHECK_VAL)
                "This looks like an RTL-SDR Blog V4 (R828D tuner), which N0RMA does not drive yet" else "No supported tuner found (this driver handles the R820T/R820T2)")
        }
        tunerName = "R820T/R820T2"
        demodWrite(1, 0xb1, 0x1a, 1)                    // disable zero-IF mode
        demodWrite(0, 0x08, 0x4d, 1)                    // only the in-phase ADC input
        setIfFreq(IF_FREQ)
        demodWrite(1, 0x15, 0x01, 1)                    // spectrum inversion on
        tuner = R82xx(i2c, XTAL)
        tuner.init()
        repeater(false)
    }

    fun setSampleRate(hz: Int) {
        require(hz in MIN_RATE..MAX_RATE) { "sample rate $hz not supported" }
        var ratio = (XTAL.toDouble() * 4194304.0 / hz).toLong()
        ratio = ratio and 0x0ffffffcL
        val real = ratio or ((ratio and 0x08000000L) shl 1)
        rate = (XTAL.toDouble() * 4194304.0 / real).toInt()
        repeater(true)
        val ifHz = tuner.setBandwidth(rate)
        setIfFreq(ifHz)
        if (centerHz != 0L) tuner.setFreq(centerHz)
        repeater(false)
        demodWrite(1, 0x9f, (ratio shr 16).toInt(), 2)
        demodWrite(1, 0xa1, (ratio and 0xffff).toInt(), 2)
        demodWrite(1, 0x3f, 0, 1); demodWrite(1, 0x3e, 0, 1)             // no sample clock correction
        demodWrite(1, 0x01, 0x14, 1); demodWrite(1, 0x01, 0x10, 1)       // soft reset
    }

    fun setCenterFreq(hz: Long) {
        repeater(true)
        try { tuner.setFreq(hz) } finally { repeater(false) }
        centerHz = hz
    }

    /** Fixed manual gain in tenths of a dB (AGC off). */
    fun setGain(tenthsDb: Int) {
        repeater(true)
        try { tuner.setGainManual(tenthsDb) } finally { repeater(false) }
    }

    fun resetBuffer() { writeReg(USBB, USB_EPA_CTL, 0x1002, 2); writeReg(USBB, USB_EPA_CTL, 0x0000, 2) }

    /** Reads exactly [n] bytes of unsigned 8-bit interleaved IQ. */
    fun read(n: Int): ByteArray {
        val out = ByteArray(n); var pos = 0
        val chunk = ByteArray(16384)
        while (pos < n) {
            val want = minOf(chunk.size, n - pos)
            val got = bus.bulkIn(chunk, want, 3000)
            if (got <= 0) throw java.io.IOException("USB read failed ($got)")
            System.arraycopy(chunk, 0, out, pos, got); pos += got
        }
        return out
    }

    override fun close() {
        runCatching { repeater(true); tuner.standby(); repeater(false) }
        runCatching { writeReg(SYSB, DEMOD_CTL, 0x20, 1) }       // power off demod and ADCs
    }
}
