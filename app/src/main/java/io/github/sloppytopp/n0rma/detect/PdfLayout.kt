package io.github.sloppytopp.n0rma.detect

/** Pure text layout for the case PDF: hard-wrap long lines, then split into pages. */
object PdfLayout {
    fun wrap(text: String, maxChars: Int): List<String> = text.replace("\r", "").split("\n").flatMap { l ->
        if (l.length <= maxChars) listOf(l) else l.chunked(maxChars)
    }

    fun paginate(lines: List<String>, linesPerPage: Int): List<List<String>> =
        if (lines.isEmpty()) listOf(emptyList()) else lines.chunked(linesPerPage)
}
