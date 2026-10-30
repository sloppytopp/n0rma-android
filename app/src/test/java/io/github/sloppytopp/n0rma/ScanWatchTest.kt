package io.github.sloppytopp.n0rma

import io.github.sloppytopp.n0rma.detect.ScanWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class ScanWatchTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test fun warnsOnlyWhenScanningWasWantedButIsNotRunning() {
        assertTrue(ScanWatch.stoppedUnexpectedly(wanted = true, running = false))
        assertFalse(ScanWatch.stoppedUnexpectedly(wanted = true, running = true))
        assertFalse(ScanWatch.stoppedUnexpectedly(wanted = false, running = false))
        assertFalse(ScanWatch.stoppedUnexpectedly(wanted = false, running = true))
    }

    @Test fun messageNamesTheLastTimeScanningWasSeen() {
        val m = ScanWatch.message(1_700_000_000_000L, utc)           // 2023-11-14 22:13:20 UTC
        assertTrue(m.contains("22:13"))
        assertTrue(m.contains("stopped unexpectedly", ignoreCase = true))
    }

    @Test fun messageStillMakesSenseWithNoHeartbeatRecorded() {
        val m = ScanWatch.message(0L, utc)
        assertFalse(m.contains("1970"))
        assertTrue(m.contains("stopped unexpectedly", ignoreCase = true))
    }

    @Test fun messageSaysWhatToDoAboutBatterySaving() {
        val m = ScanWatch.message(0L, utc)
        assertTrue(m.contains("battery", ignoreCase = true))
        assertTrue(m.contains("Scan", ignoreCase = false))
    }

    @Test fun heartbeatIsOnlyWrittenAboutOncePerMinute() {
        assertFalse(ScanWatch.beatDue(lastBeatMs = 1_000_000L, nowMs = 1_030_000L))
        assertTrue(ScanWatch.beatDue(lastBeatMs = 1_000_000L, nowMs = 1_060_000L))
        assertTrue(ScanWatch.beatDue(lastBeatMs = 0L, nowMs = 5L))
        assertEquals(60_000L, ScanWatch.BEAT_EVERY_MS)
    }
}
