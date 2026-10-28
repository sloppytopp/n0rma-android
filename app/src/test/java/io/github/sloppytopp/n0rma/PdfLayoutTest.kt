package io.github.sloppytopp.n0rma

import io.github.sloppytopp.n0rma.detect.PdfLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfLayoutTest {
    @Test fun wrapKeepsShortLines() = assertEquals(listOf("abc", "de"), PdfLayout.wrap("abc\nde", 10))
    @Test fun wrapBreaksLongLinesAtMaxChars() {
        val out = PdfLayout.wrap("a".repeat(25), 10)
        assertTrue(out.all { it.length <= 10 }); assertEquals("a".repeat(25), out.joinToString(""))
    }
    @Test fun wrapKeepsBlankLines() = assertEquals(listOf("a", "", "b"), PdfLayout.wrap("a\n\nb", 10))
    @Test fun paginateSplitsExactly() {
        val pages = PdfLayout.paginate((1..101).map { "l$it" }, 50)
        assertEquals(listOf(50, 50, 1), pages.map { it.size }); assertEquals((1..101).map { "l$it" }, pages.flatten())
    }
    @Test fun paginateEmptyGivesOnePage() = assertEquals(1, PdfLayout.paginate(emptyList(), 50).size)
}
