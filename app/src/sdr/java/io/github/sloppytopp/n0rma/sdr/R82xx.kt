/*
 * Rafael Micro R820T/R820T2 tuner driver, a Kotlin port of the register logic of librtlsdr's tuner_r82xx.c
 * (Copyright (C) 2013 Mauro Carvalho Chehab, Copyright (C) 2013 Steve Markgraf, GPL-2.0-or-later).
 *
 * This file is part of the optional N0RMA SDR build and is licensed under the GNU General Public License,
 * version 2 or (at your option) any later version. The rest of N0RMA is MIT licensed; the standard build does not contain this file.
 */
package io.github.sloppytopp.n0rma.sdr

/** I2C access to the tuner through the RTL2832U. [write] returns bytes written, [read] returns the bytes read (raw, not bit-reversed). */
interface I2cBus {
    fun write(addr: Int, data: ByteArray): Int
    fun read(addr: Int, len: Int): ByteArray
}

class R82xx(private val i2c: I2cBus, private val xtal: Int, private val addr: Int = I2C_ADDR) {
    companion object {
        const val I2C_ADDR = 0x34
        const val CHECK_ADDR = 0x00
        const val CHECK_VAL = 0x69
        const val NUM_REGS = 30
        const val REG_SHADOW_START = 5
        const val VER_NUM = 49
        const val MAX_I2C_MSG = 8

        private val INIT_ARRAY = intArrayOf(
            0x83, 0x32, 0x75, 0xc0, 0x40, 0xd6, 0x6c, 0xf5, 0x63, 0x75, 0x68, 0x6c, 0x83, 0x80, 0x00,
            0x0f, 0x00, 0xc0, 0x30, 0x48, 0xcc, 0x60, 0x00, 0x54, 0xae, 0x4a, 0xc0,
        )

        /** start MHz, open_d, rf_mux_ploy, tf_c, xtal_cap20p, xtal_cap10p, xtal_cap0p */
        private val RANGES = arrayOf(
            intArrayOf(0, 0x08, 0x02, 0xdf, 0x02, 0x01, 0x00), intArrayOf(50, 0x08, 0x02, 0xbe, 0x02, 0x01, 0x00),
            intArrayOf(55, 0x08, 0x02, 0x8b, 0x02, 0x01, 0x00), intArrayOf(60, 0x08, 0x02, 0x7b, 0x02, 0x01, 0x00),
            intArrayOf(65, 0x08, 0x02, 0x69, 0x02, 0x01, 0x00), intArrayOf(70, 0x08, 0x02, 0x58, 0x02, 0x01, 0x00),
            intArrayOf(75, 0x00, 0x02, 0x44, 0x02, 0x01, 0x00), intArrayOf(80, 0x00, 0x02, 0x44, 0x02, 0x01, 0x00),
            intArrayOf(90, 0x00, 0x02, 0x34, 0x01, 0x01, 0x00), intArrayOf(100, 0x00, 0x02, 0x34, 0x01, 0x01, 0x00),
            intArrayOf(110, 0x00, 0x02, 0x24, 0x01, 0x01, 0x00), intArrayOf(120, 0x00, 0x02, 0x24, 0x01, 0x01, 0x00),
            intArrayOf(140, 0x00, 0x02, 0x14, 0x01, 0x01, 0x00), intArrayOf(180, 0x00, 0x02, 0x13, 0x00, 0x00, 0x00),
            intArrayOf(220, 0x00, 0x02, 0x13, 0x00, 0x00, 0x00), intArrayOf(250, 0x00, 0x02, 0x11, 0x00, 0x00, 0x00),
            intArrayOf(280, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00), intArrayOf(310, 0x00, 0x41, 0x00, 0x00, 0x00, 0x00),
            intArrayOf(450, 0x00, 0x41, 0x00, 0x00, 0x00, 0x00), intArrayOf(588, 0x00, 0x40, 0x00, 0x00, 0x00, 0x00),
            intArrayOf(650, 0x00, 0x40, 0x00, 0x00, 0x00, 0x00),
        )

        private val VGA_STEPS = intArrayOf(0, 26, 26, 30, 42, 35, 24, 13, 14, 32, 36, 34, 35, 37, 35, 36)
        private val LNA_STEPS = intArrayOf(0, 9, 13, 40, 38, 13, 31, 22, 26, 31, 26, 14, 19, 5, 35, 13)
        private val MIXER_STEPS = intArrayOf(0, 5, 10, 10, 19, 9, 10, 25, 17, 10, 8, 16, 13, 6, 3, -8)
        private val LPF_BW = intArrayOf(1700000, 1600000, 1550000, 1450000, 1200000, 900000, 700000, 550000, 450000, 350000)
        private const val FILT_HP_BW1 = 350000
        private const val FILT_HP_BW2 = 380000

        /** Selectable manual gains in tenths of a dB (what librtlsdr reports for these tuners). */
        val GAINS = intArrayOf(0, 9, 14, 27, 37, 77, 87, 125, 144, 157, 166, 197, 207, 229, 254, 280, 297, 328, 338, 364, 372, 386, 402, 421, 434, 439, 445, 480, 496)

        fun bitrev(b: Int): Int {
            val lut = intArrayOf(0x0, 0x8, 0x4, 0xc, 0x2, 0xa, 0x6, 0xe, 0x1, 0x9, 0x5, 0xd, 0x3, 0xb, 0x7, 0xf)
            return (lut[b and 0xf] shl 4) or lut[(b shr 4) and 0xf]
        }
    }

    private val regs = IntArray(NUM_REGS)
    var intFreq = 0; private set
    var hasLock = false; private set
    private var filCalCode = 0
    private var xtalCapSel = 4         // XTAL_HIGH_CAP_0P

    // ---- register access with shadow copy (avoids slow redundant writes) ----
    private fun shadowEqual(reg: Int, v: IntArray): Boolean {
        val r = reg - REG_SHADOW_START
        if (r < 0 || v.size > NUM_REGS - r) return false
        for (i in v.indices) if (regs[r + i] != v[i]) return false
        return true
    }

    private fun shadowStore(reg: Int, v: IntArray) {
        var r = reg - REG_SHADOW_START; var off = 0; var len = v.size
        if (r < 0) { len += r; off -= r; r = 0 }
        if (len <= 0) return
        if (len > NUM_REGS - r) len = NUM_REGS - r
        for (i in 0 until len) regs[r + i] = v[off + i]
    }

    private fun write(reg0: Int, v: IntArray) {
        if (shadowEqual(reg0, v)) return
        shadowStore(reg0, v)
        var reg = reg0; var pos = 0; var left = v.size
        while (left > 0) {
            val size = minOf(left, MAX_I2C_MSG - 1)
            val buf = ByteArray(size + 1); buf[0] = reg.toByte()
            for (i in 0 until size) buf[1 + i] = v[pos + i].toByte()
            val rc = i2c.write(addr, buf)
            check(rc == size + 1) { "i2c write failed reg=${"%02x".format(reg)} rc=$rc" }
            reg += size; left -= size; pos += size
        }
    }

    private fun writeReg(reg: Int, v: Int) = write(reg, intArrayOf(v and 0xff))

    private fun cached(reg: Int): Int {
        val r = reg - REG_SHADOW_START
        check(r in 0 until NUM_REGS) { "bad register" }
        return regs[r]
    }

    private fun writeMask(reg: Int, v: Int, mask: Int) = writeReg(reg, (cached(reg) and mask.inv()) or (v and mask))

    /** Reads [len] status bytes starting at register 0 (bit-reversed by the chip). */
    private fun read(reg: Int, len: Int): IntArray {
        check(i2c.write(addr, byteArrayOf(reg.toByte())) == 1) { "i2c read address failed" }
        val raw = i2c.read(addr, len)
        check(raw.size == len) { "i2c read short" }
        return IntArray(len) { bitrev(raw[it].toInt() and 0xff) }
    }

    // ---- tuning ----
    private fun setMux(freqHz: Long) {
        val mhz = (freqHz / 1_000_000).toInt()
        var i = 0
        while (i < RANGES.size - 1) { if (mhz < RANGES[i + 1][0]) break; i++ }
        val r = RANGES[i]
        writeMask(0x17, r[1], 0x08)
        writeMask(0x1a, r[2], 0xc3)
        writeReg(0x1b, r[3])
        val v = when (xtalCapSel) {
            0, 1 -> r[4] or 0x08
            2 -> r[5] or 0x08
            4 -> r[6] or 0x00
            else -> r[6] or 0x08
        }
        writeMask(0x10, v, 0x0b)
        writeMask(0x08, 0x00, 0x3f)
        writeMask(0x09, 0x00, 0x3f)
    }

    private fun maskReg(reg: Int, v: Int, mask: Int) = (reg and mask.inv()) or (v and mask)

    private fun setPll(freqHz: Long) {
        val vcoMin = 1_770_000L; val vcoMax = vcoMin * 2
        val freqKhz = (freqHz + 500) / 1000
        val pllRef = xtal.toLong()
        val voPowerRefDefault = 2

        writeMask(0x1a, 0x00, 0x0c)                      // pll autotune = 128 kHz
        val r = IntArray(7) { regs[0x10 - REG_SHADOW_START + it] }
        r[0] = maskReg(r[0], 0, 0x10)
        r[2] = maskReg(r[2], 0x80, 0xe0)                 // VCO current = 100

        var mixDiv = 2; var divNum = 0
        while (mixDiv <= 64) {
            if (freqKhz * mixDiv >= vcoMin && freqKhz * mixDiv < vcoMax) {
                var divBuf = mixDiv
                while (divBuf > 2) { divBuf = divBuf shr 1; divNum++ }
                break
            }
            mixDiv = mixDiv shl 1
        }
        val data = read(0x00, 5)
        val vcoPowerRef = voPowerRefDefault
        val vcoFineTune = (data[4] and 0x30) shr 4
        if (vcoFineTune > vcoPowerRef) divNum -= 1 else if (vcoFineTune < vcoPowerRef) divNum += 1
        r[0] = maskReg(r[0], (divNum shl 5) and 0xff, 0xe0)

        val vcoFreq = freqHz * mixDiv
        val vcoDiv = (pllRef + 65536L * vcoFreq) / (2L * pllRef)
        val nint = (vcoDiv / 65536).toInt()
        val sdm = (vcoDiv % 65536).toInt()
        require(nint in 13..(128 / vcoPowerRef - 1)) { "No valid PLL values for $freqHz Hz" }
        val ni = (nint - 13) / 4
        val si = nint - 4 * ni - 13
        r[4] = (ni + (si shl 6)) and 0xff
        r[2] = maskReg(r[2], if (sdm == 0) 0x08 else 0x00, 0x08)
        r[5] = sdm and 0xff
        r[6] = sdm shr 8
        write(0x10, r)

        var d: IntArray = IntArray(3)
        for (i in 0 until 2) {
            d = read(0x00, 3)
            if (d[2] and 0x40 != 0) break
            if (i == 0) writeMask(0x12, 0x60, 0xe0)      // didn't lock: raise VCO current
        }
        if (d[2] and 0x40 == 0) { hasLock = false; return }
        hasLock = true
        writeMask(0x1a, 0x08, 0x08)                      // pll autotune = 8 kHz
    }

    /** DVB-T "digital TV" system settings, the only mode N0RMA uses (same values librtlsdr programs at init). */
    private fun sysfreqSel() {
        val mixerTop = 0x24; val lnaTop = 0xe5; val cpCur = 0x38; val divBufCur = 0x30
        val lnaVthL = 0x53; val mixerVthL = 0x75; val airCable1In = 0x00; val lnaDischarge = 14; val filterCur = 0x40
        writeMask(0x1d, lnaTop, 0xc7)
        writeMask(0x1c, mixerTop, 0xf8)
        writeReg(0x0d, lnaVthL)
        writeReg(0x0e, mixerVthL)
        writeMask(0x05, airCable1In, 0x60)
        writeMask(0x06, 0x00, 0x08)
        writeMask(0x11, cpCur, 0x38)
        writeMask(0x17, divBufCur, 0x30)
        writeMask(0x0a, filterCur, 0x60)
        writeMask(0x1d, 0, 0x38)                         // LNA TOP lowest
        writeMask(0x1c, 0, 0x04)                         // normal mode
        writeMask(0x06, 0, 0x40)                         // PRE_DECT off
        writeMask(0x1a, 0x30, 0x30)                      // agc clk 250 Hz
        writeMask(0x1d, 0x18, 0x38)                      // LNA TOP = 3
        writeMask(0x1c, mixerTop, 0x04)
        writeMask(0x1e, lnaDischarge, 0x1f)
        writeMask(0x1a, 0x20, 0x30)                      // agc clk 60 Hz
    }

    private fun setTvStandard() {
        val ifKhz = 3570; val filtCalLo = 56000
        val filtGain = 0x10; val imgR = 0x00; val filtQ = 0x10; val hpCor = 0x6b
        val extEnable = 0x60; val loopThrough = 0x01; val ltAtt = 0x00; val fltExtWidest = 0x00; val polyfilCur = 0x60
        for (i in INIT_ARRAY.indices) regs[i] = INIT_ARRAY[i]
        for (i in INIT_ARRAY.size until NUM_REGS) regs[i] = 0
        writeMask(0x0c, 0x00, 0x0f)
        writeMask(0x13, VER_NUM, 0x3f)
        writeMask(0x1d, 0x00, 0x38)
        intFreq = ifKhz * 1000

        for (i in 0 until 2) {                           // IF filter calibration
            writeMask(0x0b, hpCor, 0x60)
            writeMask(0x0f, 0x04, 0x04)                  // cali clk on
            writeMask(0x10, 0x00, 0x03)                  // xtal cap 0 pF for PLL
            setPll(filtCalLo * 1000L)
            if (!hasLock) return
            writeMask(0x0b, 0x10, 0x10)                  // start trigger
            Thread.sleep(2)
            writeMask(0x0b, 0x00, 0x10)                  // stop trigger
            writeMask(0x0f, 0x00, 0x04)                  // cali clk off
            val d = read(0x00, 5)
            filCalCode = d[4] and 0x0f
            if (filCalCode != 0 && filCalCode != 0x0f) break
        }
        if (filCalCode == 0x0f) filCalCode = 0
        writeMask(0x0a, filtQ or filCalCode, 0x1f)
        writeMask(0x0b, hpCor, 0xef)
        writeMask(0x07, imgR, 0x80)
        writeMask(0x06, filtGain, 0x30)
        writeMask(0x1e, extEnable, 0x60)
        writeMask(0x05, loopThrough, 0x80)
        writeMask(0x1f, ltAtt, 0x80)
        writeMask(0x0f, fltExtWidest, 0x80)
        writeMask(0x19, polyfilCur, 0x60)
    }

    fun init() {
        xtalCapSel = 4
        for (i in 0 until NUM_REGS) regs[i] = 0
        write(0x05, INIT_ARRAY)
        setTvStandard()
        sysfreqSel()
    }

    /** Sets the IF filter for [bwHz]; returns the intermediate frequency in Hz the RTL2832U must be told about. */
    fun setBandwidth(bwHz: Int): Int {
        var bw = bwHz; val reg0a: Int; var reg0b: Int
        if (bw > 7_000_000) { reg0a = 0x10; reg0b = 0x0b; intFreq = 4_570_000 }
        else if (bw > 6_000_000) { reg0a = 0x10; reg0b = 0x2a; intFreq = 4_570_000 }
        else if (bw > LPF_BW[0] + FILT_HP_BW1 + FILT_HP_BW2) { reg0a = 0x10; reg0b = 0x6b; intFreq = 3_570_000 }
        else {
            reg0a = 0x00; reg0b = 0x80; intFreq = 2_300_000
            var realBw = 0
            if (bw > LPF_BW[0] + FILT_HP_BW1) { bw -= FILT_HP_BW2; intFreq += FILT_HP_BW2; realBw += FILT_HP_BW2 } else reg0b = reg0b or 0x20
            if (bw > LPF_BW[0]) { bw -= FILT_HP_BW1; intFreq += FILT_HP_BW1; realBw += FILT_HP_BW1 } else reg0b = reg0b or 0x40
            var i = 0
            while (i < LPF_BW.size) { if (bw > LPF_BW[i]) break; i++ }
            i -= 1
            if (i < 0) i = 0
            reg0b = reg0b or (15 - i)
            realBw += LPF_BW[i]
            intFreq -= realBw / 2
        }
        writeMask(0x0a, reg0a, 0x10)
        writeMask(0x0b, reg0b, 0xef)
        return intFreq
    }

    fun setFreq(freqHz: Long) {
        val lo = freqHz + intFreq
        setMux(lo)
        setPll(lo)
        check(hasLock) { "PLL not locked at $freqHz Hz" }
    }

    /** Manual gain: [gainTenthsDb] picks the LNA and mixer steps (VGA fixed), as librtlsdr does. */
    fun setGainManual(gainTenthsDb: Int) {
        writeMask(0x05, 0x10, 0x10)                      // LNA auto off
        writeMask(0x07, 0, 0x10)                         // mixer auto off
        read(0x00, 4)
        writeMask(0x0c, 0x08, 0x9f)                      // fixed VGA gain
        var total = 0; var mix = 0; var lna = 0
        for (i in 0 until 15) {
            if (total >= gainTenthsDb) break
            total += LNA_STEPS[++lna]
            if (total >= gainTenthsDb) break
            total += MIXER_STEPS[++mix]
        }
        writeMask(0x05, lna, 0x0f)
        writeMask(0x07, mix, 0x0f)
    }

    fun standby() {
        writeReg(0x06, 0xb1); writeReg(0x05, 0xa0); writeReg(0x07, 0x3a); writeReg(0x08, 0x40); writeReg(0x09, 0xc0)
        writeReg(0x0a, 0x36); writeReg(0x0c, 0x35); writeReg(0x0f, 0x68); writeReg(0x11, 0x03); writeReg(0x17, 0xf4); writeReg(0x19, 0x0c)
    }
}
