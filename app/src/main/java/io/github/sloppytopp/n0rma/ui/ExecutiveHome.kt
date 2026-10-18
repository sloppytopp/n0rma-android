package io.github.sloppytopp.n0rma.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sloppytopp.n0rma.data.Prefs
import io.github.sloppytopp.n0rma.data.Store
import io.github.sloppytopp.n0rma.detect.Level as DLevel
import io.github.sloppytopp.n0rma.detect.Snapshot
import io.github.sloppytopp.n0rma.detect.Timeline
import io.github.sloppytopp.n0rma.detect.TimelineFilter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Streamlined look: one headline, one button, the three latest items that need a decision, two links. No cards, no filters. */
@Composable
fun ExecutiveHome(s: Snapshot, onToggleScan: () -> Unit, goTab: (Int) -> Unit) {
    val ctx = LocalContext.current
    val now = System.currentTimeMillis()
    val stored = remember(s.events.size) { Store.events(now - 72 * 3_600_000L, 300) }
    val events = remember(stored, s.events) { (stored + s.events).distinctBy { Timeline.key(it) } }
    val unresolved = Timeline.unresolved(events, Prefs.resolved)
    val worst = listOf(s.drone.level, s.tracker.level, s.camera.level, s.spam.level).maxByOrNull { it.ordinal } ?: DLevel.OFF
    val (head, lvl) = when {
        !s.running -> "Not scanning" to Level.OFF
        worst == DLevel.ALERT -> "Alert" to Level.ALERT
        unresolved > 0 || worst == DLevel.WATCH -> "Review needed" to Level.WATCH
        else -> "All clear" to Level.OK
    }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(head, color = lvl.accentC(), fontSize = 36.sp, fontWeight = FontWeight.Light)
        Text(
            when {
                !s.running -> "Tap Scan to check your surroundings."
                unresolved == 0 -> "No open items."
                else -> "$unresolved open ${if (unresolved == 1) "item" else "items"}."
            }, color = UiColors.dim, fontSize = 16.sp,
        )
        Button(
            onClick = onToggleScan, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = UiColors.good, contentColor = UiColors.onGood),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp),
        ) { Text(if (io.github.sloppytopp.n0rma.scan.Demo.active) "Stop sample data" else if (s.running) "Stop" else "Scan", fontSize = 16.sp, fontWeight = FontWeight.Medium) }
        s.error?.let { Text(it, color = UiColors.warn, fontSize = 14.sp) }

        RadarPair(s, maxCards = 3)

        Rule()
        Text("Needs a decision", color = UiColors.faint, fontSize = 12.sp, letterSpacing = 1.sp)
        val fmt = SimpleDateFormat("EEE h:mm a", Locale.getDefault())
        val open = Timeline.filter(events, TimelineFilter.ALL).filter { (it.level == DLevel.WATCH || it.level == DLevel.ALERT) && Timeline.key(it) !in Prefs.resolved }.take(3)
        if (open.isEmpty()) Text("Nothing open.", color = UiColors.dim, fontSize = 15.sp)
        open.forEach { e ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${Timeline.word(e.level)}  ·  ${fmt.format(Date(e.ts))}", color = Level.valueOf(e.level.name).accentC(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(e.msg, color = UiColors.text, fontSize = 15.sp)
                TextButton(onClick = { Prefs.toggleResolved(Timeline.key(e)) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("Mark resolved", fontSize = 14.sp) }
            }
        }

        Rule()
        RebaselineCard(s)
        Rule()
        Link("Export evidence report") { shareEvidenceReport(ctx) }
        Link("Settings and themes") { goTab(5) }
        Text("Detect-only. A quiet screen is not a guarantee of safety. Nothing leaves this phone.", color = UiColors.faint, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun Rule() = Box(Modifier.fillMaxWidth().height(1.dp).background(UiColors.ring))

@Composable
private fun Link(label: String, onClick: () -> Unit) {
    Text(label, color = UiColors.good, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 10.dp))
}
