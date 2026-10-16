package io.github.sloppytopp.n0rma.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sloppytopp.n0rma.scan.Mic
import java.text.DateFormat
import java.util.Date

/** Hidden-microphone sweep with an RTL-SDR dongle. Honest by design: a sweep hit is only a WATCH; only a tone that comes back, with clean controls, is an ALERT. */
@Composable
fun MicScreen() {
    val ctx = LocalContext.current
    var mhz by remember { mutableStateOf("") }
    val base = Mic.baseline()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Hidden-mic sweep", color = UiColors.text, fontSize = 20.sp)
        Text("A microphone is silent; this hunts for the radio that would carry its audio. Needs an RTL-SDR dongle (RTL-SDR Blog V3 recommended), a USB-OTG " +
            "adapter and the free \"RTL-SDR driver\" app (F-Droid). A sweep hit alone is only a Suspect. An Alert needs a tone played in this room to come back " +
            "on that frequency while control frequencies stay silent.", color = UiColors.dim, fontSize = 12.sp)

        Text("1. Connect", color = UiColors.text, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
        Text("Plug in the dongle, then start the driver app. If Android asks to let it use the USB device, allow it. Keep the dongle on a cable, away from the phone's speaker.", color = UiColors.faint, fontSize = 11.sp)
        TextButton(onClick = { Mic.launchDriver(ctx) }, enabled = !Mic.busy) { Text("Open the RTL-SDR driver app") }

        Text("2. Baseline (quiet house, your own gear on)", color = UiColors.text, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
        Text(if (base == null) "No baseline yet." else "Recorded ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(base.ts))}: ${base.carriers.size} steady carriers treated as normal.",
            color = UiColors.faint, fontSize = 11.sp)
        TextButton(onClick = { Mic.recordBaseline() }, enabled = !Mic.busy) { Text("Record baseline (about 15 min)") }

        Text("3. Scan", color = UiColors.text, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
        TextButton(onClick = { Mic.scan() }, enabled = !Mic.busy && base != null) { Text("Scan for new carriers (about 15 min)") }

        Text("4. Tone test a frequency", color = UiColors.text, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
        Text("Stay in the room, nobody talking, phone speaker facing the suspect spot. The phone plays tones at full volume for about 25 seconds, then measures a control frequency or two.", color = UiColors.faint, fontSize = 11.sp)
        Mic.candidates.forEach { (f, d) ->
            TextButton(onClick = { Mic.toneTest(ctx, f / 1e6) }, enabled = !Mic.busy) { Text("${"%.3f".format(f / 1e6)} MHz  (${d.toInt()} dB)   ›  test") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(mhz, { mhz = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("MHz") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
            TextButton(onClick = { mhz.toDoubleOrNull()?.let { Mic.toneTest(ctx, it) } }, enabled = !Mic.busy && mhz.toDoubleOrNull() != null) { Text("Test") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("AM instead of FM (rare)", color = UiColors.dim, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Switch(checked = Mic.am, onCheckedChange = { Mic.am = it })
        }

        Text(Mic.status, color = UiColors.text, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        if (Mic.busy) TextButton(onClick = { Mic.cancel() }) { Text("Cancel") }
        Mic.log.forEach { Text("• $it", color = UiColors.dim, fontSize = 11.sp) }

        Text("What this cannot see", color = UiColors.text, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
        Text("Recorders that never transmit; wired mics; GSM/LTE, DECT, Wi-Fi/Bluetooth bugs (use the other tabs for those); digital or burst-only transmitters; " +
            "anything above about 1.3 GHz or inside a gap the dongle cannot tune. A quiet result does not prove the room is clear. A confirmed tone can also be a baby monitor or " +
            "wireless mic you own: it means that frequency carries this room's audio, not who put it there. The driver app has no login, so another app on this phone could feed it fake radio data; " +
            "only trust results on a phone you control.", color = UiColors.faint, fontSize = 11.sp)
    }
}
