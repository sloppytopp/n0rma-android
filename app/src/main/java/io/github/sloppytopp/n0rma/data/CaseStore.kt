package io.github.sloppytopp.n0rma.data

import io.github.sloppytopp.n0rma.detect.CaseNote
import io.github.sloppytopp.n0rma.detect.CasePhoto
import java.io.File

data class CaseInfo(val id: String, val title: String, val createdMs: Long, val report: String, val notes: List<CaseNote>, val photos: List<CasePhoto>)

/**
 * Pro saved cases: one directory per case (`case-<createdMs>`) under [root], holding report.txt, meta.txt and photos/.
 * Plain files on purpose: the 30-day database prune and "Delete all history" never touch them; only [delete] / [deleteAll] do.
 */
class CaseStore(private val root: File) {
    private val idOk = Regex("case-\\d+")

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
    private fun unesc(s: String): String {
        val b = StringBuilder(); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) { b.append(when (s[i + 1]) { 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; else -> s[i + 1] }); i += 2 } else { b.append(c); i++ }
        }
        return b.toString()
    }

    private fun dir(id: String): File? = if (idOk.matches(id)) File(root, id) else null

    fun photoFile(id: String, name: String): File {
        val d = dir(id) ?: throw IllegalArgumentException("bad case id")
        return File(File(d, "photos"), File(name).name)       // File(name).name drops any path part
    }

    fun create(nowMs: Long, title: String, report: String, notes: List<CaseNote>, photos: List<CasePhoto>): CaseInfo {
        var ms = nowMs
        while (File(root, "case-$ms").exists()) ms++
        val id = "case-$ms"
        val d = File(root, id); File(d, "photos").mkdirs()
        File(d, "report.txt").writeText(report)
        File(d, "meta.txt").writeText(buildString {
            appendLine("title\t${esc(title)}"); appendLine("created\t$ms")
            notes.forEach { appendLine("note\t${esc(it.label)}\t${esc(it.text)}") }
            photos.forEach { appendLine("photo\t${esc(it.file)}\t${it.sha256}\t${it.importedMs}\t${esc(it.whereWhen)}") }
        })
        return CaseInfo(id, title, ms, report, notes, photos)
    }

    /** Like [create] but also copies the photo files in; if anything fails, the half-made case is removed and the error is rethrown. */
    fun createWithPhotos(nowMs: Long, title: String, report: String, notes: List<CaseNote>, photos: List<CasePhoto>, sources: Map<String, File>): CaseInfo {
        val info = create(nowMs, title, report, notes, photos)
        try {
            photos.forEach { p -> (sources[p.file] ?: throw java.io.IOException("missing photo ${p.file}")).copyTo(photoFile(info.id, p.file), overwrite = true) }
        } catch (t: Throwable) { delete(info.id); throw if (t is java.io.IOException) t else java.io.IOException(t) }
        return info
    }

    fun load(id: String): CaseInfo? {
        val d = dir(id) ?: return null
        val meta = File(d, "meta.txt"); val rep = File(d, "report.txt")
        if (!meta.isFile || !rep.isFile) return null
        var title = ""; var created = 0L
        val notes = ArrayList<CaseNote>(); val photos = ArrayList<CasePhoto>()
        meta.readLines().forEach { l ->
            val p = l.split("\t")
            when (p[0]) {
                "title" -> title = unesc(p.getOrElse(1) { "" })
                "created" -> created = p.getOrElse(1) { "0" }.toLongOrNull() ?: 0L
                "note" -> if (p.size >= 3) notes += CaseNote(unesc(p[1]), unesc(p[2]))
                "photo" -> if (p.size >= 5) photos += CasePhoto(unesc(p[1]), p[2], p[3].toLongOrNull() ?: 0L, unesc(p[4]))
            }
        }
        return CaseInfo(id, title, created, rep.readText(), notes, photos)
    }

    fun list(): List<CaseInfo> = (root.listFiles() ?: emptyArray()).filter { it.isDirectory && idOk.matches(it.name) }
        .mapNotNull { load(it.name) }.sortedByDescending { it.createdMs }

    fun delete(id: String) { dir(id)?.deleteRecursively() }
    fun deleteAll() { (root.listFiles() ?: emptyArray()).forEach { if (idOk.matches(it.name)) it.deleteRecursively() } }
}
