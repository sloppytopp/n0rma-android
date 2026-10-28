package io.github.sloppytopp.n0rma.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import io.github.sloppytopp.n0rma.data.CaseInfo
import io.github.sloppytopp.n0rma.data.CaseStore
import io.github.sloppytopp.n0rma.data.PhotoImport
import io.github.sloppytopp.n0rma.data.Store
import io.github.sloppytopp.n0rma.detect.CaseFile
import io.github.sloppytopp.n0rma.detect.CaseNote
import io.github.sloppytopp.n0rma.detect.CasePhoto
import io.github.sloppytopp.n0rma.detect.Evidence
import io.github.sloppytopp.n0rma.detect.Follow
import io.github.sloppytopp.n0rma.scan.CasePdf
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class Pending(val photo: CasePhoto, val file: File, whereWhen: String = "") { var whereWhen by mutableStateOf(whereWhen) }

private fun store(ctx: Context) = CaseStore(File(ctx.filesDir, "cases"))
private fun chainEnd(report: String) = report.lines().firstOrNull { it.startsWith("CHAIN END (final hash): ") }?.removePrefix("CHAIN END (final hash): ")?.trim() ?: ""
private fun savedAgo(ms: Long): String {
    val d = (System.currentTimeMillis() - ms) / 86_400_000L
    return if (d <= 0) "saved today" else if (d == 1L) "saved yesterday" else "saved $d days ago"
}

/** Pro: build a case file (report + your notes and photos), save it, and share it as a .txt (verifiable) plus a readable .pdf. */
@Composable
fun CaseScreen() {
    val ctx = LocalContext.current
    var v by remember { mutableIntStateOf(0) }
    var title by remember { mutableStateOf("Case " + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())) }
    var note by remember { mutableStateOf("") }
    var redact by remember { mutableStateOf(true) }
    var msg by remember { mutableStateOf<String?>(null) }
    var stamp by remember { mutableStateOf<CaseInfo?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    val pending = remember { mutableStateListOf<Pending>() }
    val cases = remember(v) { store(ctx).list() }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            val name = "photo-${System.currentTimeMillis()}.jpg"
            val f = File(File(ctx.cacheDir, "case_pending"), name)
            val p = PhotoImport.strip(ctx, uri, f)
            if (p == null) msg = "That photo could not be read, so it was skipped." else pending += Pending(p, f)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Case file (Pro)", color = UiColors.text, fontSize = 16.sp)
        Text("Packages the flagged events with your own notes and photos into one saved case: a .txt record that can be verified, and a readable PDF. " +
            "It is a documented, tamper-evident record, not proof of who placed a device and not a guarantee of any result. Detection stays free.", color = UiColors.dim, fontSize = 12.sp)
        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Case name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Your notes: what you found, where, when") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
        pending.forEach { p ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(value = p.whereWhen, onValueChange = { p.whereWhen = it }, label = { Text("Photo: where and when found") }, modifier = Modifier.weight(1f))
                TextButton(onClick = { p.file.delete(); pending.remove(p) }) { Text("Remove") }
            }
        }
        TextButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Add a photo (location data is removed)") }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Switch(checked = redact, onCheckedChange = { redact = it })
            Text("  Hide device addresses (MAC) in the report", color = UiColors.text, fontSize = 13.sp)
        }
        Button(
            onClick = {
                val now = System.currentTimeMillis()
                var (ev, fo, sg) = Triple(Store.events(now - 30 * 86_400_000L, 1500), Follow.analyze(Store.trail()), Store.trail())
                if (redact) CaseFile.redact(ev, fo, sg).let { ev = it.first; fo = it.second; sg = it.third }
                val notes = if (note.isBlank()) emptyList() else listOf(CaseNote("Notes", note.trim()))
                val photos = pending.map { it.photo.copy(whereWhen = it.whereWhen.trim()) }
                val report = Evidence.build(now, ev, fo, sg, title = "N0RMA CASE FILE", extra = CaseFile.extraBlock(notes, photos))
                val s = store(ctx)
                val info = s.create(now, title.trim().ifEmpty { "Case" }, report, notes, photos)
                pending.forEach { p -> p.file.copyTo(s.photoFile(info.id, p.photo.file), overwrite = true); p.file.delete() }
                pending.clear(); note = ""; v++
                msg = "Saved. It stays on this phone until you delete it."
            }, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = UiColors.buttonBg, contentColor = UiColors.buttonFg),
        ) { Text("Save as a case") }
        msg?.let { Text(it, color = UiColors.warn, fontSize = 12.sp) }

        Text("Saved cases (${cases.size})", color = UiColors.text, fontSize = 14.sp)
        cases.forEach { c ->
            Text("${c.title} - ${savedAgo(c.createdMs)}, ${c.photos.size} photo${if (c.photos.size == 1) "" else "s"}", color = UiColors.text, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { msg = shareCase(ctx, c) }) { Text("Share") }
                TextButton(onClick = { stamp = c }) { Text("Timestamp") }
                TextButton(onClick = { store(ctx).delete(c.id); v++ }) { Text("Delete", color = UiColors.warn) }
            }
        }
        if (cases.isNotEmpty()) TextButton(onClick = { confirmAll = true }) { Text("Delete all cases", color = UiColors.warn) }
        Text("Saved cases and photos live on this phone, are NOT removed by \"Delete all history\", and are only erased by the Delete buttons here or by uninstalling. " +
            "Use the PIN lock (Settings) if someone else may pick up your phone.", color = UiColors.faint, fontSize = 11.sp)
    }

    stamp?.let { c ->
        androidx.compose.material3.AlertDialog(
            containerColor = UiColors.dialogBg, titleContentColor = UiColors.text, textContentColor = UiColors.text,
            onDismissRequest = { stamp = null }, title = { Text("Timestamp this case") },
            text = { Text("CHAIN END:\n${chainEnd(c.report)}\n\n" + CasePdf.STEPS.drop(3).joinToString("\n"), fontSize = 13.sp) },
            confirmButton = { TextButton(onClick = {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("CHAIN END", chainEnd(c.report)))
                msg = "Copied."; stamp = null
            }) { Text("Copy") } },
            dismissButton = { TextButton(onClick = {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "N0RMA CHAIN END ${chainEnd(c.report)}"), "Send hash"))
                stamp = null
            }) { Text("Send") } },
        )
    }
    if (confirmAll) androidx.compose.material3.AlertDialog(
        containerColor = UiColors.dialogBg, titleContentColor = UiColors.text, textContentColor = UiColors.text,
        onDismissRequest = { confirmAll = false }, title = { Text("Delete all cases?") },
        text = { Text("This permanently erases every saved case and its photos from this phone.") },
        confirmButton = { TextButton(onClick = { store(ctx).deleteAll(); confirmAll = false; v++ }) { Text("Delete", color = UiColors.warn) } },
        dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel") } },
    )
}

/** Writes the .txt and .pdf to the cache share folder and opens the share sheet with both. Returns a status message. */
private fun shareCase(ctx: Context, c: CaseInfo): String = try {
    val dir = File(ctx.cacheDir, "case_share"); dir.deleteRecursively(); dir.mkdirs()
    val base = "n0rma-case-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(c.createdMs))
    val txt = File(dir, "$base.txt").also { it.writeText(c.report) }
    val pdf = File(dir, "$base.pdf")
    CasePdf.render(c, { name -> store(ctx).photoFile(c.id, name) }, pdf)
    val uris = arrayListOf(txt, pdf).map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", it) }
    val send = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)).putExtra(Intent.EXTRA_SUBJECT, "N0RMA case file")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, "Share case file"))
    "Share the .txt and .pdf together: only the .txt can be verified exactly."
} catch (t: Throwable) { "Couldn't prepare the files to share." }
