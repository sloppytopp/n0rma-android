package io.github.sloppytopp.n0rma.scan

import android.content.Context

/** What the optional SDR build provides for talking to an RTL-SDR dongle over USB. The standard build has no driver ([SdrBackend.driver] is null). */
interface SdrDriver {
    fun deviceAttached(ctx: Context): Boolean
    fun hasPermission(ctx: Context): Boolean
    /** Shows Android's own USB permission prompt for the dongle; [done] gets the answer. */
    fun requestPermission(ctx: Context, done: (Boolean) -> Unit)
    /** Opens the dongle and returns a sample source. Throws with a plain-language message if it cannot. */
    fun open(ctx: Context): IqSource
}

object SdrBackend {
    /** Loaded by name so the standard build, which does not contain the (GPL) driver, compiles and runs without it. */
    val driver: SdrDriver? by lazy {
        runCatching { Class.forName("io.github.sloppytopp.n0rma.sdr.UsbRtlDriver").getDeclaredConstructor().newInstance() as SdrDriver }.getOrNull()
    }
}
