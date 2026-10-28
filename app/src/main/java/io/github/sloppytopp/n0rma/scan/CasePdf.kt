package io.github.sloppytopp.n0rma.scan

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import io.github.sloppytopp.n0rma.data.CaseInfo
import io.github.sloppytopp.n0rma.detect.PdfLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders a saved case as a PDF. The PDF is a READABLE COPY: the verifiable thing is the .txt report shared with it (PDF text extraction
 * can change whitespace), so the last pages say how to verify and how to timestamp the CHAIN END value.
 */
object CasePdf {
    private const val W = 595; private const val H = 842; private const val M = 40
    const val NOTICE = "This is a documented, tamper-evident record made by the N0RMA app from what this phone's radios heard. " +
        "It is not proof of who placed a device, and it is not a guarantee of any outcome."

    val STEPS = listOf(
        "How to check this record",
        "1. Use the .txt file that was shared with this PDF. The PDF is a readable copy; only the .txt can be verified exactly.",
        "2. In the .txt, every event line carries a chained hash. Editing, deleting or reordering a line breaks the chain.",
        "3. The CHAIN END value (printed on the last page of the report) fixes the report's content.",
        "To fix the time as well: copy the CHAIN END value and send it to yourself, an advocate or a lawyer now (email or text),",
        "or paste it into a public timestamping service in your browser. Anyone can later compare that message's date with the report.",
    )

    fun render(info: CaseInfo, photoFile: (String) -> File, out: File) {
        val doc = PdfDocument()
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var c = page?.canvas
        var y = 0f
        val body = Paint().apply { textSize = 11f; color = Color.BLACK; isAntiAlias = true }
        val small = Paint().apply { textSize = 9f; color = Color.DKGRAY; isAntiAlias = true }
        val mono = Paint().apply { textSize = 8f; typeface = Typeface.MONOSPACE; color = Color.BLACK; isAntiAlias = true }
        val head = Paint().apply { textSize = 16f; typeface = Typeface.DEFAULT_BOLD; color = Color.BLACK; isAntiAlias = true }
        val sub = Paint().apply { textSize = 12f; typeface = Typeface.DEFAULT_BOLD; color = Color.BLACK; isAntiAlias = true }

        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create()); c = page!!.canvas; y = M.toFloat()
        }
        fun need(h: Float) { if (page == null || y + h > H - M) newPage() }
        fun line(t: String, p: Paint, lh: Number) { val h = lh.toFloat(); need(h); c!!.drawText(t, M.toFloat(), y + p.textSize, p); y += h }
        fun para(t: String, p: Paint, chars: Int) { val lh = p.textSize + 4f; PdfLayout.wrap(t, chars).forEach { line(it, p, lh) } }

        newPage()
        line("N0RMA case file", head, 24)
        line(info.title, sub, 18)
        line("Saved " + SimpleDateFormat("yyyy-MM-dd HH:mm z", Locale.US).format(Date(info.createdMs)), small, 14)
        y += 6f; para(NOTICE, body, 90); y += 8f

        if (info.notes.isNotEmpty() || info.photos.isNotEmpty()) {
            line("Added by the person (not verified by the app)", sub, 18)
            info.notes.forEach { n -> line(n.label, sub, 16); para(n.text, body, 90); y += 4f }
            info.photos.forEach { p ->
                val f = photoFile(p.file)
                val bmp = if (f.isFile) BitmapFactory.decodeFile(f.path) else null
                if (bmp != null) {
                    val w = minOf(480f, W - 2f * M); val h = w * bmp.height / bmp.width
                    val hh = minOf(h, 360f); val ww = hh * bmp.width / bmp.height
                    need(hh + 40); c!!.drawBitmap(bmp, null, android.graphics.RectF(M.toFloat(), y, M + ww, y + hh), null); y += hh + 4; bmp.recycle()
                }
                para("Photo ${p.file}  sha256 ${p.sha256}", small, 110)
                if (p.whereWhen.isNotBlank()) para("Found: ${p.whereWhen}", body, 90)
                y += 6f
            }
        }

        newPage()
        line("Report text (the verifiable record)", sub, 18)
        PdfLayout.paginate(PdfLayout.wrap(info.report, 105), 62).forEachIndexed { i, ls ->
            if (i > 0) newPage()
            ls.forEach { line(it, mono, 11f) }
        }

        newPage()
        line(STEPS[0], head, 24)
        STEPS.drop(1).forEach { para(it, body, 95); y += 2f }
        page?.let { doc.finishPage(it) }
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
    }
}
