package io.github.sloppytopp.n0rma.detect

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class CaseNote(val label: String, val text: String)
/** [importedMs] is the app's clock when the photo was added; photo EXIF dates are never trusted or printed. */
data class CasePhoto(val file: String, val sha256: String, val importedMs: Long, val whereWhen: String)

/**
 * Pro "case file" helpers. Things the PERSON supplies (notes, photos) go into the evidence report's `extra` block, so they are covered by the
 * hash chain but labelled as not verified by the app. Every line is indented so no note can imitate a CHAIN / EVENT LOG line.
 */
object CaseFile {
    const val HEADING = "ADDED BY THE PERSON (not verified by the app)"

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun indent(s: String) = s.replace("\r", "").lines().joinToString("\n") { "    $it" }

    fun extraBlock(notes: List<CaseNote>, photos: List<CasePhoto>, tz: TimeZone = TimeZone.getDefault()): String {
        if (notes.isEmpty() && photos.isEmpty()) return ""
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).apply { timeZone = tz }
        return buildString {
            appendLine(HEADING)
            appendLine("- These entries were typed or added by the person using the app. The app did not check them. Photo import times come from this phone's clock.")
            notes.forEach { n -> appendLine("- Note: ${n.label.replace(Regex("[\r\n]"), " ")}"); appendLine(indent(n.text)) }
            photos.forEach { p ->
                appendLine("- Photo ${p.file}: sha256=${p.sha256} (location data removed) imported ${fmt.format(Date(p.importedMs))}")
                if (p.whereWhen.isNotBlank()) { appendLine("  Where and when found, in the person's words:"); appendLine(indent(p.whereWhen)) }
            }
            appendLine()
        }
    }

    private val MAC = Regex("([0-9A-Fa-f]{2}:){5}([0-9A-Fa-f]{2})")

    /** Hides all but the last byte of every MAC address. */
    fun redactMacs(s: String): String = MAC.replace(s) { "xx:xx:xx:xx:xx:" + it.groupValues[2].lowercase() }

    /** Redaction must happen BEFORE Evidence.build: changing a built report would break its hash chain. */
    fun redact(events: List<EventRow>, follow: List<FollowHit>, sights: List<Sight>): Triple<List<EventRow>, List<FollowHit>, List<Sight>> = Triple(
        events.map { it.copy(msg = redactMacs(it.msg)) },
        follow.map { it.copy(key = redactMacs(it.key), label = redactMacs(it.label)) },
        sights.map { it.copy(key = redactMacs(it.key), label = redactMacs(it.label)) },
    )
}
