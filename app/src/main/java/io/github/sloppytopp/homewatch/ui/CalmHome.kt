package io.github.sloppytopp.homewatch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sloppytopp.homewatch.data.Prefs
import io.github.sloppytopp.homewatch.data.Store
import io.github.sloppytopp.homewatch.detect.Level as DLevel
import io.github.sloppytopp.homewatch.detect.Snapshot
import io.github.sloppytopp.homewatch.detect.Timeline
import io.github.sloppytopp.homewatch.detect.TimelineFilter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The default home screen: calm, plain words. One big Scan button, three cards, a filterable event timeline with a
 * "Resolved" mark per event, quick actions and the evidence export. The detectors and scanning logic are unchanged.
 */
@Composable
fun CalmHome(s: Snapshot, onToggleScan: () -> Unit, goTab: (Int) -> Unit) {
    val ctx = LocalContext.current
    val now = System.currentTimeMillis()
    var filter by remember { mutableStateOf(TimelineFilter.ALL) }
    var showChecklist by remember { mutableStateOf(false) }
    // The live snapshot's events change as scanning runs; the stored ones cover the last 3 days
    val stored = remember(s.events.size) { Store.events(now - 72 * 3_600_000L, 300) }
    val events = remember(stored, s.events) { (stored + s.events).distinctBy { Timeline.key(it) } }
    val unresolved = Timeline.unresolved(events, Prefs.resolved)

    val worst = listOf(s.drone.level, s.tracker.level, s.camera.level, s.spam.level).maxByOrNull { it.ordinal } ?: DLevel.OFF
    val (head, headLevel) = when {
        !s.running -> "Not scanning" to Level.OFF
        worst == DLevel.ALERT -> "Alert: look at this now" to Level.ALERT
        worst == DLevel.WATCH -> "Something to check" to Level.WATCH
        else -> "All clear" to Level.OK
    }

    if (!Prefs.welcomed) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(UiColors.card).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("N0RMA scans for hidden trackers around you.", color = UiColors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("Nothing is sent to the internet. Tap Scan to begin.", color = UiColors.dim, fontSize = 15.sp)
            TextButton(onClick = { Prefs.saveWelcomed() }) { Text("Got it", fontSize = 15.sp) }
        }
    }

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(headLevel.containerC()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${headLevel.icon}  $head", color = headLevel.accentC(), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Text(
            if (!s.running) "Tap Scan to check what's around you." else
                if (unresolved == 0) "Nothing needs your attention." else "$unresolved ${if (unresolved == 1) "item" else "items"} not yet resolved.",
            color = UiColors.dim, fontSize = 15.sp,
        )
    }

    Button(
        onClick = onToggleScan, modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = UiColors.good, contentColor = androidx.compose.ui.graphics.Color(0xFF0B1A10)),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
    ) { Text(if (io.github.sloppytopp.homewatch.scan.Demo.active) "Stop sample data" else if (s.running) "Stop scanning" else "Scan", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
    if (!s.running && !io.github.sloppytopp.homewatch.scan.Demo.active)
        TextButton(onClick = { io.github.sloppytopp.homewatch.scan.Demo.start() }) { Text("Try it with sample data (nothing real is scanned)", fontSize = 13.sp) }
    s.error?.let { Text(it, color = UiColors.warn, fontSize = 14.sp) }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        InfoCard("Active scans", if (s.running) "On" else "Off", if (s.running) "Bluetooth, Wi-Fi" else "Not running", Modifier.weight(1f))
        InfoCard("Unresolved", "$unresolved", if (unresolved == 0) "None" else "Review below", Modifier.weight(1f), if (unresolved > 0) Level.WATCH else Level.OK)
        InfoCard("Evidence", "Report", "Tap export", Modifier.weight(1f), onClick = { shareEvidenceReport(ctx) })
    }

    Text("Events", color = UiColors.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TimelineFilter.values().forEach { f ->
            val on = f == filter
            Box(Modifier.clip(RoundedCornerShape(20.dp)).background(if (on) UiColors.good else UiColors.card).clickable { filter = f }
                .padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text(f.label, color = if (on) androidx.compose.ui.graphics.Color(0xFF0B1A10) else UiColors.text, fontSize = 14.sp)
            }
        }
    }
    val shown = Timeline.filter(events, filter)
    if (shown.isEmpty()) Text("Nothing here.", color = UiColors.dim, fontSize = 15.sp)
    val fmt = SimpleDateFormat("EEE h:mm a", Locale.getDefault())
    shown.take(30).forEach { e ->
        val key = Timeline.key(e)
        val done = key in Prefs.resolved
        val lvl = Level.valueOf(e.level.name)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(UiColors.card).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${Timeline.word(e.level)}  ·  ${fmt.format(Date(e.ts))}", color = if (done) UiColors.faint else lvl.accentC(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(e.msg, color = if (done) UiColors.faint else UiColors.text, fontSize = 15.sp, textDecoration = if (done) TextDecoration.LineThrough else null)
            if (e.level == DLevel.WATCH || e.level == DLevel.ALERT)
                TextButton(onClick = { Prefs.toggleResolved(key) }) { Text(if (done) "Unresolve" else "Mark resolved (my device / false alarm)", fontSize = 14.sp) }
        }
    }

    Text("Quick actions", color = UiColors.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
    ActionRow("Export evidence") { shareEvidenceReport(ctx) }
    ActionRow("Safety checklist") { showChecklist = !showChecklist }
    if (showChecklist) DigitalSafetyCard()
    ActionRow("Settings") { goTab(5) }

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(UiColors.card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Evidence report", color = UiColors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text("Makes a plain-text report of everything flagged in the last 30 days. Each entry is linked to the one before it by a hash, so any later edit, deletion or reordering breaks the chain and can be detected. " +
            "Save the last line (the chain end) somewhere separate. Nothing is sent anywhere until you pick a destination.", color = UiColors.dim, fontSize = 14.sp)
        Button(onClick = { shareEvidenceReport(ctx) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = UiColors.buttonBg, contentColor = UiColors.buttonFg)) { Text("Export evidence report", fontSize = 16.sp) }
    }
    Text("Detect-only. A quiet screen is not a guarantee of safety. Nothing leaves this phone.", color = UiColors.faint, fontSize = 13.sp)
}

@Composable
private fun InfoCard(title: String, big: String, small: String, modifier: Modifier, level: Level = Level.OFF, onClick: (() -> Unit)? = null) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(UiColors.card).let { if (onClick != null) it.clickable { onClick() } else it }.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, color = UiColors.dim, fontSize = 12.sp)
        Text(big, color = if (level == Level.OFF) UiColors.text else level.accentC(), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(small, color = UiColors.faint, fontSize = 12.sp)
    }
}

@Composable
private fun ActionRow(label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(UiColors.card).clickable { onClick() }.padding(16.dp)) {
        Text(label, color = UiColors.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text("›", color = UiColors.dim, fontSize = 18.sp)
    }
}
