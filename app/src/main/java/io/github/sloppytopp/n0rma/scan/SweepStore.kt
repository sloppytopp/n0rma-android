package io.github.sloppytopp.n0rma.scan

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** The one saved "baseline" wide sweep (Pro build) that later sweeps are compared with. Stays in the app's private storage; nothing is sent anywhere. */
object SweepStore {
    private const val MAGIC = 0x4E30534D       // "N0SM"
    /** Sweeps taken at gains further apart than this (dB) are not comparable. */
    const val MAX_GAIN_DIFF_TENTHS = 10

    fun save(f: File, s: SpectrumRun.Sweep) {
        DataOutputStream(f.outputStream().buffered()).use { o ->
            o.writeInt(MAGIC); o.writeLong(s.takenAt); o.writeInt(s.gainTenths); o.writeDouble(s.startHz); o.writeDouble(s.binHz)
            o.writeInt(s.db.size); for (v in s.db) o.writeFloat(v)
        }
    }

    /** Null when there is no baseline or the file is not one of ours / is damaged. */
    fun load(f: File): SpectrumRun.Sweep? {
        if (!f.isFile || f.length() > 8_000_000) return null
        return runCatching {
            DataInputStream(f.inputStream().buffered()).use { i ->
                if (i.readInt() != MAGIC) return null
                val t = i.readLong(); val g = i.readInt(); val start = i.readDouble(); val bin = i.readDouble(); val n = i.readInt()
                if (n !in 1..2_000_000 || bin <= 0.0) return null
                SpectrumRun.Sweep(start, bin, FloatArray(n) { i.readFloat() }, g, t)
            }
        }.getOrNull()
    }
}
