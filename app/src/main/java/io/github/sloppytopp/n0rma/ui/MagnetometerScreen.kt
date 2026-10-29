package io.github.sloppytopp.n0rma.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.sloppytopp.n0rma.detect.MagLevel
import io.github.sloppytopp.n0rma.detect.MagMeter

/** Close-range magnetic locator: set a baseline away from the thing, then move the phone slowly toward it. Nothing is saved or sent. */
@Composable
fun MagnetometerSweep(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val meter = remember { MagMeter() }
    var tick by remember { mutableIntStateOf(0) }
    val sensor = remember { (ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { meter.update(e.values[0], e.values[1], e.values[2]); tick++ }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(l) }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(UiColors.dialogBg).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Magnetic locator", color = UiColors.text, fontSize = 20.sp)
            if (sensor == null) {
                Text("This phone has no magnetic-field sensor, so this tool can't run.", color = UiColors.warn, fontSize = 13.sp)
            } else {
                @Suppress("UNUSED_EXPRESSION") tick
                Text("1. Hold the phone a metre or more away from the thing you want to check, then tap Set baseline. " +
                    "2. Move the phone slowly toward it, within a few centimetres. 3. The bar rises where the magnetic field changes: electronics, speakers, magnets and steel all do it.",
                    color = UiColors.dim, fontSize = 12.sp)
                val color = when (meter.level) { MagLevel.STRONG -> UiColors.alert; MagLevel.NOTICEABLE -> UiColors.watch; else -> UiColors.good }
                Text("%.0f µT".format(meter.current), color = UiColors.text, fontSize = 28.sp)
                if (meter.baseline != null) Text("+%.0f µT from baseline".format(meter.delta), color = UiColors.dim, fontSize = 14.sp)
                Box(Modifier.fillMaxWidth().height(24.dp).background(UiColors.faint.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(meter.fill).background(color))
                }
                Text(when (meter.level) {
                    MagLevel.NO_BASELINE -> "Set a baseline first."
                    MagLevel.QUIET -> "Nothing unusual here."
                    MagLevel.NOTICEABLE -> "The field changes here: something magnetic or electronic is close."
                    MagLevel.STRONG -> "Strong change: the source is very close. Look at it by eye, then move away slowly to confirm it fades."
                } + if (meter.peak > 0f) "  Peak so far: +%.0f µT.".format(meter.peak) else "", color = UiColors.text, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { meter.setBaseline(); tick++ }, colors = ButtonDefaults.buttonColors(containerColor = UiColors.buttonBg, contentColor = UiColors.buttonFg)) { Text("Set baseline") }
                    TextButton(onClick = { meter.clearBaseline(); tick++ }) { Text("Reset") }
                }
            }
            Text("This is a locator, not a safety meter. It only works within a few centimetres, every phone's sensor drifts, and phone cases, magnets and metal furniture move it too. " +
                "A quiet reading does not mean nothing is there. If you find something you don't own: leave it, photograph it, and contact local law enforcement.",
                color = UiColors.faint, fontSize = 11.sp)
            TextButton(onClick = onClose) { Text("Close") }
        }
    }
}
