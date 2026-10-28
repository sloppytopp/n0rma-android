package io.github.sloppytopp.n0rma

import io.github.sloppytopp.n0rma.data.CaseStore
import io.github.sloppytopp.n0rma.detect.CaseNote
import io.github.sloppytopp.n0rma.detect.CasePhoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaseStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val notes = listOf(CaseNote("Found", "line one\nline two | with pipe\n\nand = signs"))
    private val photos = listOf(CasePhoto("p1.jpg", "ab".repeat(32), 1_700_000_000_000L, "Driveway\nTuesday | evening"))

    @Test fun createThenLoadRoundTrip() {
        val s = CaseStore(tmp.newFolder("cases"))
        val c = s.create(1000L, "Car tracker", "REPORT TEXT\nline2", notes, photos)
        val back = s.load(c.id)!!
        assertEquals("Car tracker", back.title); assertEquals(1000L, back.createdMs)
        assertEquals("REPORT TEXT\nline2", back.report)
        assertEquals(notes, back.notes); assertEquals(photos, back.photos)
    }
    @Test fun listIsNewestFirst() {
        val s = CaseStore(tmp.newFolder("cases"))
        s.create(1000L, "old", "r", emptyList(), emptyList()); s.create(3000L, "new", "r", emptyList(), emptyList()); s.create(2000L, "mid", "r", emptyList(), emptyList())
        assertEquals(listOf("new", "mid", "old"), s.list().map { it.title })
    }
    @Test fun deleteRemovesAllFiles() {
        val root = tmp.newFolder("cases"); val s = CaseStore(root)
        val c = s.create(1000L, "t", "r", notes, photos)
        s.photoFile(c.id, "p1.jpg").writeText("x")
        s.delete(c.id)
        assertFalse(root.resolve(c.id).exists()); assertNull(s.load(c.id))
    }
    @Test fun deleteAllLeavesRootEmpty() {
        val root = tmp.newFolder("cases"); val s = CaseStore(root)
        s.create(1000L, "a", "r", emptyList(), emptyList()); s.create(2000L, "b", "r", emptyList(), emptyList())
        s.deleteAll()
        assertTrue(s.list().isEmpty())
    }
    @Test fun unknownIdLoadsNull() = assertNull(CaseStore(tmp.newFolder("cases")).load("case-999"))
    @Test fun titleWithSlashCannotEscapeRoot() {
        val root = tmp.newFolder("cases"); val s = CaseStore(root)
        val c = s.create(1000L, "../../evil/x", "r", emptyList(), emptyList())
        assertTrue(c.id.matches(Regex("case-\\d+")))
        assertEquals("../../evil/x", s.load(c.id)!!.title)
        assertEquals(1, root.listFiles()!!.size)
    }
    @Test fun badIdIsRejected() {
        val s = CaseStore(tmp.newFolder("cases"))
        assertNull(s.load("../x")); s.delete("../x")
    }
}
