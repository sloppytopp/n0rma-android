/*
 * Android USB glue for the RTL2832U driver. Part of the optional N0RMA SDR build, licensed GPL-2.0-or-later (see Rtl2832.kt).
 */
package io.github.sloppytopp.n0rma.sdr

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.os.Build
import io.github.sloppytopp.n0rma.scan.IqSource
import io.github.sloppytopp.n0rma.scan.SdrDriver

class UsbRtlDriver : SdrDriver {
    companion object {
        private const val ACTION = "io.github.sloppytopp.n0rma.USB_PERMISSION"
        private val IDS = setOf(0x0bda to 0x2838, 0x0bda to 0x2832)
    }

    private fun find(ctx: Context): UsbDevice? =
        (ctx.getSystemService(Context.USB_SERVICE) as UsbManager).deviceList.values.firstOrNull { (it.vendorId to it.productId) in IDS }

    override fun deviceAttached(ctx: Context) = find(ctx) != null

    override fun hasPermission(ctx: Context): Boolean {
        val d = find(ctx) ?: return false
        return (ctx.getSystemService(Context.USB_SERVICE) as UsbManager).hasPermission(d)
    }

    override fun requestPermission(ctx: Context, done: (Boolean) -> Unit) {
        val d = find(ctx) ?: return done(false)
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        if (um.hasPermission(d)) return done(true)
        val app = ctx.applicationContext
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                runCatching { app.unregisterReceiver(this) }
                done(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        val flt = IntentFilter(ACTION)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(rx, flt, Context.RECEIVER_NOT_EXPORTED) else app.registerReceiver(rx, flt)
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        um.requestPermission(d, PendingIntent.getBroadcast(app, 0, Intent(ACTION).setPackage(app.packageName), flags))
    }

    override fun open(ctx: Context): IqSource {
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val dev = find(ctx) ?: throw IllegalStateException("No RTL-SDR dongle is plugged in")
        if (!um.hasPermission(dev)) throw IllegalStateException("USB permission for the dongle was not granted")
        val conn = um.openDevice(dev) ?: throw IllegalStateException("Android would not open the dongle")
        val iface = dev.getInterface(0)
        if (!conn.claimInterface(iface, true)) { conn.close(); throw IllegalStateException("Another app is using the dongle") }
        val ep = (0 until iface.endpointCount).map { iface.getEndpoint(it) }
            .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN }
            ?: run { conn.close(); throw IllegalStateException("The dongle has no data endpoint") }
        val chip = Rtl2832(AndroidBus(conn, ep))
        try { chip.open() } catch (t: Throwable) { runCatching { conn.releaseInterface(iface) }; conn.close(); throw t }
        return RtlUsbSource(chip, conn, iface)
    }
}

private class AndroidBus(private val c: UsbDeviceConnection, private val ep: UsbEndpoint) : UsbBus {
    private val IN = 0xC0; private val OUT = 0x40; private val TIMEOUT = 300
    override fun controlIn(value: Int, index: Int, len: Int): ByteArray? {
        val b = ByteArray(len)
        val n = c.controlTransfer(IN, 0, value, index, b, len, TIMEOUT)
        return if (n < 0) null else b.copyOf(n)
    }
    override fun controlOut(value: Int, index: Int, data: ByteArray) = c.controlTransfer(OUT, 0, value, index, data, data.size, TIMEOUT)
    override fun bulkIn(buf: ByteArray, len: Int, timeoutMs: Int) = c.bulkTransfer(ep, buf, len, timeoutMs)
}

class RtlUsbSource(private val chip: Rtl2832, private val conn: UsbDeviceConnection, private val iface: android.hardware.usb.UsbInterface) : IqSource {
    private var started = false
    val tuner get() = chip.tunerName
    val sampleRate get() = chip.rate
    val centerHz get() = chip.centerHz
    override fun tune(hz: Double, rate: Int) {
        if (rate != chip.rate) chip.setSampleRate(rate)
        chip.setCenterFreq(hz.toLong())
        if (!started) { chip.resetBuffer(); started = true }
    }
    override fun setGain(tenthsDb: Int) = chip.setGain(tenthsDb)
    override fun read(n: Int): ByteArray = chip.read(n)
    override fun close() {
        runCatching { chip.close() }
        runCatching { conn.releaseInterface(iface) }
        runCatching { conn.close() }
    }
}
