package io.github.sloppytopp.homewatch.scan

import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** A source of unsigned 8-bit interleaved IQ samples. The real one is an rtl_tcp server; tests use a synthetic one. */
interface IqSource : AutoCloseable {
    fun tune(hz: Double, rate: Int)
    fun setGain(tenthsDb: Int)
    /** Exactly [n] bytes (blocks). */
    fun read(n: Int): ByteArray
}

/**
 * rtl_tcp client (the protocol spoken by Martin Marinov's "RTL-SDR driver" app, which owns the USB dongle, so this app needs no USB code).
 * SECURITY: rtl_tcp has no authentication, so any app on this phone could listen on that port and feed fake samples. The host is fixed to
 * loopback, the "RTL0" header is checked and every read is bounded, but a hostile local app could still spoof results (e.g. a false "all clear").
 * Treat sweep results as advisory on a phone you do not trust. Header: "RTL0", tuner type, gain count (12 bytes). Commands: 1 byte id + big-endian uint32. Data: raw IQ bytes.
 */
class RtlTcp(host: String = "127.0.0.1", port: Int = 1234, timeoutMs: Int = 4000) : IqSource {
    private val sock = Socket().also { it.connect(InetSocketAddress(host, port), timeoutMs); it.soTimeout = 8000; it.receiveBufferSize = 1 shl 20 }
    private val inp = DataInputStream(sock.getInputStream().buffered(1 shl 16))
    private val out: OutputStream = sock.getOutputStream()
    val tunerType: Int
    init {
        val h = ByteArray(12); inp.readFully(h)
        require(String(h, 0, 4, Charsets.US_ASCII) == "RTL0") { "not an rtl_tcp server" }
        tunerType = ((h[4].toInt() and 0xFF) shl 24) or ((h[5].toInt() and 0xFF) shl 16) or ((h[6].toInt() and 0xFF) shl 8) or (h[7].toInt() and 0xFF)
    }
    private fun cmd(id: Int, v: Long) {
        val b = byteArrayOf(id.toByte(), (v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        out.write(b); out.flush()
    }
    override fun tune(hz: Double, rate: Int) {
        cmd(0x02, rate.toLong()); cmd(0x01, hz.toLong())
    }
    /** Fixed manual gain, AGC off: automatic gain moves the noise floor between passes and makes carriers appear and vanish. */
    override fun setGain(tenthsDb: Int) { cmd(0x03, 1); cmd(0x08, 0); cmd(0x04, tenthsDb.toLong()) }
    override fun read(n: Int): ByteArray = ByteArray(n).also { inp.readFully(it) }
    override fun close() { runCatching { sock.close() } }
}
