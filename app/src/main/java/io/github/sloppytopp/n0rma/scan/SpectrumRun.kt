package io.github.sloppytopp.n0rma.scan

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.sloppytopp.n0rma.detect.SpectrumMath

/** Runs the live spectrum: opens the dongle through the built-in USB driver, reads samples on one worker thread and publishes dB frames. */
object SpectrumRun {
    const val NFFT = 1024
    const val RATE = 2_400_000
    private const val FRAMES = 24
    const val WF_COLS = 512
    const val WF_ROWS = 90
    val GAINS = intArrayOf(0, 9, 14, 27, 37, 77, 87, 125, 144, 157, 166, 197, 207, 229, 254, 280, 297, 328, 338, 364, 372, 386, 402, 421, 434, 439, 445, 480, 496)
    const val SWEEP_GROUP = 16                       // 37.5 kHz per sweep point: fine enough for carriers, small enough to save and compare
    const val MIN_HZ = 24_000_000.0
    const val MAX_HZ = 1_766_000_000.0

    var running by mutableStateOf(false); private set
    var status by mutableStateOf("Not running"); private set
    var centerHz by mutableStateOf(100_000_000.0); private set
    var gainIdx by mutableStateOf(17); private set                // 32.8 dB
    var db by mutableStateOf(FloatArray(0)); private set
    var peaks by mutableStateOf<List<SpectrumMath.Peak>>(emptyList()); private set
    var waterfall by mutableStateOf<List<FloatArray>>(emptyList()); private set
    var frame by mutableIntStateOf(0); private set

    /** The result of one wide sweep: [db] is lowest frequency first, one value per [binHz] starting at [startHz]. Relative dB, not calibrated. */
    class Sweep(val startHz: Double, val binHz: Double, val db: FloatArray, val gainTenths: Int, val takenAt: Long) {
        val noise = SpectrumMath.noiseFloor(db)
        val peaks = SpectrumMath.sweepPeaks(db, startHz, binHz)
    }
    var sweep by mutableStateOf<Sweep?>(null); private set
    var sweepProgress by mutableStateOf(0f); private set
    var sweeping by mutableStateOf(false); private set

    @Volatile private var wantHz = centerHz
    @Volatile private var wantGain = GAINS[gainIdx]
    @Volatile private var stop = false

    fun tune(hz: Double) { val c = hz.coerceIn(MIN_HZ, MAX_HZ); centerHz = c; wantHz = c }
    fun setGainIndex(i: Int) { gainIdx = i.coerceIn(0, GAINS.size - 1); wantGain = GAINS[gainIdx] }
    fun stop() { stop = true }

    /** One pass over the whole tuner range in 1.8 MHz steps (about half a minute). Pro build only; the screen decides who may call it. */
    fun startSweep(ctx: Context) = start(ctx, wide = true)

    fun start(ctx: Context, wide: Boolean = false) {
        if (running) return
        val drv = SdrBackend.driver ?: run { status = "This build has no RTL-SDR driver. Use the SDR or Pro build."; return }
        if (!drv.deviceAttached(ctx)) { status = "No RTL-SDR dongle found. Plug it into the phone's USB port (a USB-C adapter may be needed)."; return }
        running = true; stop = false; status = "Asking Android for USB permission..."
        val app = ctx.applicationContext
        drv.requestPermission(app) { granted ->
            if (!granted) { status = "USB permission for the dongle was refused."; running = false } else if (wide) workSweep(drv, app) else work(drv, app)
        }
    }

    private fun work(drv: SdrDriver, ctx: Context) {
        Thread {
            var src: IqSource? = null
            try {
                status = "Opening the dongle..."
                src = drv.open(ctx)
                var hz = wantHz; var gain = wantGain
                src.tune(hz, RATE); src.setGain(gain)
                status = "Running"
                val rows = ArrayList<FloatArray>()
                while (!stop) {
                    if (wantHz != hz) { hz = wantHz; src.tune(hz, RATE) }
                    if (wantGain != gain) { gain = wantGain; src.setGain(gain) }
                    val iq = src.read(2 * NFFT * FRAMES)
                    val d = SpectrumMath.psdDb(iq, NFFT, FRAMES)
                    val row = FloatArray(WF_COLS) { c -> val a = c * NFFT / WF_COLS; var m = d[a]; for (k in 1 until NFFT / WF_COLS) if (d[a + k] > m) m = d[a + k]; m }
                    rows += row; if (rows.size > WF_ROWS) rows.removeAt(0)
                    waterfall = ArrayList(rows)
                    db = d
                    peaks = SpectrumMath.peaks(d, hz, RATE)
                    frame++
                }
                status = "Stopped"
            } catch (t: Throwable) {
                status = t.message ?: "Dongle error"
            } finally {
                runCatching { src?.close() }
                running = false
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun workSweep(drv: SdrDriver, ctx: Context) {
        sweeping = true; sweepProgress = 0f
        Thread {
            var src: IqSource? = null
            try {
                status = "Opening the dongle..."
                src = drv.open(ctx)
                val gain = wantGain
                src.setGain(gain)
                val group = SWEEP_GROUP
                val stepHz = RATE * SpectrumMath.SWEEP_KEEP
                val binHz = RATE.toDouble() / NFFT * group
                val first = MIN_HZ + stepHz / 2
                val steps = Math.ceil((MAX_HZ - MIN_HZ) / stepHz).toInt()
                val out = ArrayList<FloatArray>(steps)
                for (k in 0 until steps) {
                    if (stop) break
                    status = "Sweeping... ${"%.0f".format((first + k * stepHz) / 1e6)} MHz"
                    src.tune(first + k * stepHz, RATE)
                    src.read(2 * NFFT * 2)                                   // discard what was captured while the tuner was still settling
                    out += SpectrumMath.sweepStep(SpectrumMath.psdDb(src.read(2 * NFFT * 8), NFFT, 8), group)
                    sweepProgress = (k + 1f) / steps
                }
                if (out.size == steps) {
                    sweep = Sweep(MIN_HZ, binHz, FloatArray(out.sumOf { it.size }).also { a -> var o = 0; for (r in out) { r.copyInto(a, o); o += r.size } }, gain, System.currentTimeMillis())
                    status = "Sweep done"
                } else status = "Sweep stopped"
            } catch (t: Throwable) {
                status = t.message ?: "Dongle error"
            } finally {
                runCatching { src?.close() }
                sweeping = false; running = false
            }
        }.also { it.isDaemon = true }.start()
    }
}
