package io.github.sloppytopp.n0rma.detect

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Notices when scanning stops without the user stopping it (the phone's battery saver or maker "app killer" closing the service). */
object ScanWatch {
    const val BEAT_EVERY_MS = 60_000L

    fun stoppedUnexpectedly(wanted: Boolean, running: Boolean) = wanted && !running

    /** True when it is time to record "still scanning" again (about once a minute, so the check costs almost nothing). */
    fun beatDue(lastBeatMs: Long, nowMs: Long) = lastBeatMs <= 0L || nowMs - lastBeatMs >= BEAT_EVERY_MS

    fun message(lastBeatMs: Long, tz: TimeZone = TimeZone.getDefault()): String {
        val at = if (lastBeatMs > 0) " Last scanning at " + SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = tz }.format(Date(lastBeatMs)) + "." else ""
        return "Scanning stopped unexpectedly.$at Your phone may have closed N0RMA to save battery, so nothing has been watching since. " +
            "Open Status and tap Scan to restart, and in Settings allow unrestricted battery use for N0RMA."
    }
}
