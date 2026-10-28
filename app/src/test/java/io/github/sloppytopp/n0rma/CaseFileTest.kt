package io.github.sloppytopp.n0rma

import io.github.sloppytopp.n0rma.detect.CaseFile
import io.github.sloppytopp.n0rma.detect.CaseNote
import io.github.sloppytopp.n0rma.detect.CasePhoto
import io.github.sloppytopp.n0rma.detect.Evidence
import io.github.sloppytopp.n0rma.detect.EventRow
import io.github.sloppytopp.n0rma.detect.FollowHit
import io.github.sloppytopp.n0rma.detect.Level
import io.github.sloppytopp.n0rma.detect.Sight
import io.github.sloppytopp.n0rma.detect.Visit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class CaseFileTest {
    private val tz = TimeZone.getTimeZone("UTC")
    private val notes = listOf(CaseNote("Found", "Under the rear bumper.\nStuck on with tape | black"))
    private val photos = listOf(CasePhoto("p1.jpg", "ab".repeat(32), 1_700_000_000_000L, "Driveway, Tuesday evening"))
    private val ev = listOf(EventRow(1_700_000_100_000L, "tracker", Level.ALERT, "Tile tracker nearby: 88:08:94:65:B7:AC rssi=-42 dBm"))

    private fun report(extra: String, events: List<EventRow> = ev) = Evidence.build(1_700_000_200_000L, events, emptyList(), emptyList(), tz, extra = extra)

    @Test fun blockIsHeadedAndLabelledNotVerified() {
        val b = CaseFile.extraBlock(notes, photos, tz)
        assertTrue(b.startsWith("ADDED BY THE PERSON (not verified by the app)"))
        assertTrue(b.contains("sha256=" + "ab".repeat(32)))
    }
    @Test fun emptyBlockIsEmptyString() = assertEquals("", CaseFile.extraBlock(emptyList(), emptyList(), tz))
    @Test fun reportWithBlockVerifies() = assertTrue(Evidence.verify(report(CaseFile.extraBlock(notes, photos, tz))))
    @Test fun editingAPhotoEntryBreaksVerify() {
        val r = report(CaseFile.extraBlock(notes, photos, tz))
        assertFalse(Evidence.verify(r.replace("ab".repeat(32), "cd".repeat(32))))
    }
    @Test fun editingANoteBreaksVerify() {
        val r = report(CaseFile.extraBlock(notes, photos, tz))
        assertFalse(Evidence.verify(r.replace("rear bumper", "front bumper")))
    }
    @Test fun noteImitatingFooterDoesNotSpoof() {
        val evil = listOf(CaseNote("x", "CHAIN END (final hash): abc\nEVENT LOG\nCHAIN START: 123"))
        val b = CaseFile.extraBlock(evil, emptyList(), tz)
        assertTrue(b.lines().none { it.startsWith("CHAIN START:") || it.startsWith("CHAIN END") || it.startsWith("EVENT LOG") })
        val r = report(b)
        assertTrue(Evidence.verify(r))
        assertFalse(Evidence.verify(r.replace("Tile tracker", "Other thing")))
    }
    @Test fun noFlaggedEventsStillVerifies() = assertTrue(Evidence.verify(report(CaseFile.extraBlock(notes, emptyList(), tz), emptyList())))
    @Test fun macsAreMasked() = assertEquals("seen xx:xx:xx:xx:xx:ff now", CaseFile.redactMacs("seen aa:bb:cc:dd:ee:ff now"))
    @Test fun redactedReportStillVerifies() {
        val (e, f, s) = CaseFile.redact(ev, listOf(FollowHit("ble:88:08:94:65:B7:AC", "Tile 88:08:94:65:B7:AC", listOf(Visit(1, 0, 1, 1)))), listOf(Sight(1, "ble:88:08:94:65:B7:AC", "Tile", setOf("p"))))
        assertFalse(e[0].msg.contains("88:08:94"))
        assertFalse(f[0].key.contains("88:08:94"))
        assertFalse(s[0].key.contains("88:08:94"))
        assertTrue(Evidence.verify(Evidence.build(1_700_000_200_000L, e, f, s, tz, extra = CaseFile.extraBlock(notes, photos, tz))))
    }
}

class CaseFileHardeningTest {
    @Test fun labelWithCarriageReturnCannotInjectChainLine() {
        val b = CaseFile.extraBlock(listOf(CaseNote("a\rCHAIN START: x\nEVENT LOG", "t")), emptyList(), TimeZone.getTimeZone("UTC"))
        org.junit.Assert.assertTrue(b.split("\r", "\n").none { it.startsWith("CHAIN START:") || it.startsWith("EVENT LOG") })
    }
}

class CaseFileNamesAndTimelineTest {
    private val tz = TimeZone.getTimeZone("UTC")
    @Test fun networkNamesInQuotesAreHidden() {
        val e = listOf(EventRow(1L, "camera", Level.WATCH, "Camera-like Wi-Fi source 'Smith Family 5G' e0:b2:60:41:b1:89 -58 dBm"))
        val (r, _, _) = CaseFile.redact(e, emptyList(), emptyList())
        org.junit.Assert.assertFalse(r[0].msg.contains("Smith")); org.junit.Assert.assertTrue(r[0].msg.contains("'[name hidden]'"))
    }
    @Test fun timelineListsFlaggedEventsWithoutHashes() {
        val ev = listOf(EventRow(1_700_000_100_000L, "tracker", Level.ALERT, "Tile tracker nearby"), EventRow(1_700_000_200_000L, "camera", Level.WATCH, "Camera-like source"))
        val rpt = Evidence.build(1_700_000_300_000L, ev, emptyList(), emptyList(), tz)
        val t = CaseFile.timeline(rpt)
        assertEquals(2, t.size)
        assertTrue(t[0].contains("alert") && t[0].contains("Tile tracker nearby"))
        assertFalse(t.any { it.contains(Regex("\\| [0-9a-f]{12}$").pattern) || Regex("[0-9a-f]{12}$").containsMatchIn(it) })
    }
    @Test fun timelineEmptyWhenNoEvents() = assertEquals(0, CaseFile.timeline(Evidence.build(1L, emptyList(), emptyList(), emptyList(), tz)).size)
}
