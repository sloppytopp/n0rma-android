package io.github.sloppytopp.n0rma.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sloppytopp.n0rma.data.Prefs

/** Plain-language steps per phone maker (written steps, not deep links: those break between models), plus the opt-in "resume after restart". */
private val STEPS = listOf(
    Triple("samsung", "Samsung", "Settings > Battery > Background usage limits > Never sleeping apps > add N0RMA. Also turn off \"Put unused apps to sleep\"."),
    Triple("xiaomi", "Xiaomi / Redmi / Poco", "App info > Autostart: on. App info > Battery saver: No restrictions."),
    Triple("oneplus", "OnePlus / OPPO / realme", "App info > Battery usage > Allow background activity. Also turn on Allow auto-launch."),
    Triple("huawei", "Huawei / Honor", "Settings > Battery > App launch > N0RMA > Manage manually, then turn on all three switches."),
    Triple("google", "Pixel and most others", "Settings > Apps > N0RMA > Battery > Unrestricted."),
)

@Composable
fun KeepRunningCard() {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val maker = Build.MANUFACTURER.lowercase()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Keep N0RMA running", color = UiColors.text, fontSize = 16.sp)
        Text("Some phones close background apps to save battery. If that happens N0RMA stops watching and tells you when you next open it.", color = UiColors.dim, fontSize = 12.sp)
        TextButton(onClick = { open = !open }) { Text(if (open) "Hide the steps for my phone" else "Show the steps for my phone") }
        if (open) {
            STEPS.forEach { (key, name, steps) ->
                val mine = maker.contains(key) || (key == "oneplus" && (maker.contains("oppo") || maker.contains("realme"))) || (key == "xiaomi" && (maker.contains("redmi") || maker.contains("poco"))) ||
                    (key == "huawei" && maker.contains("honor"))
                Text(name + if (mine) "  (your phone)" else "", color = if (mine) UiColors.good else UiColors.text, fontSize = 13.sp)
                Text(steps, color = UiColors.dim, fontSize = 12.sp)
            }
            Text("Menu names change between phones and Android versions, so treat these as a guide.", color = UiColors.faint, fontSize = 11.sp)
            TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Text("Open the battery settings list") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Switch(checked = Prefs.resumeAfterBoot, onCheckedChange = { Prefs.saveResumeAfterBoot(it) })
            Text("  Resume scanning after the phone restarts", color = UiColors.text, fontSize = 13.sp)
        }
        Text("Off by default. If you turn it on and scanning was on before a restart, N0RMA starts scanning again by itself (Android may ask you to open the app instead).", color = UiColors.faint, fontSize = 11.sp)
    }
}
