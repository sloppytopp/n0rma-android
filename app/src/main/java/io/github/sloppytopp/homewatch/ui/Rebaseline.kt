package io.github.sloppytopp.homewatch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sloppytopp.homewatch.data.Prefs
import io.github.sloppytopp.homewatch.detect.Snapshot
import io.github.sloppytopp.homewatch.scan.Monitor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Everything is all clear and these are my devices: lock it in." After this, anything new that shows up is flagged right away.
 * One button, one confirmation, plain words.
 */
@Composable
fun RebaselineCard(s: Snapshot) {
    var confirm by remember { mutableStateOf(false) }
    val at = Prefs.baselineAt
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(UiColors.card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Rebaseline", color = UiColors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(
            if (at == 0L) "When everything looks normal and the Wi-Fi networks around you are yours, tap Rebaseline. N0RMA locks them in, and any new network after that is flagged right away. Trackers and cameras stay watched either way."
            else "Locked in ${SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(Date(at))}. Any new Wi-Fi network is flagged right away. Tap Rebaseline again after you add or move your own devices.",
            color = UiColors.dim, fontSize = 14.sp,
        )
        Button(
            onClick = { confirm = true }, enabled = s.running, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = UiColors.buttonBg, contentColor = UiColors.buttonFg),
        ) { Text(if (s.running) "Rebaseline" else "Start a scan first", fontSize = 16.sp) }
        if (at != 0L) TextButton(onClick = { Monitor.resetBaseline() }) { Text("Reset baseline", fontSize = 14.sp) }
    }
    if (confirm) AlertDialog(
        containerColor = UiColors.dialogBg, titleContentColor = UiColors.text, textContentColor = UiColors.text,
        onDismissRequest = { confirm = false },
        title = { Text("Lock in what is here now?") },
        text = { Text("Only do this if the Wi-Fi networks N0RMA shows around you are yours or normal. It marks the networks detected right now as safe; any new one will be flagged. Trackers, cameras and open items are not changed: decide about each one yourself.") },
        confirmButton = { TextButton(onClick = { Monitor.rebaseline(); confirm = false }) { Text("Yes, rebaseline") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}
