package io.github.sloppytopp.homewatch.scan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.sloppytopp.homewatch.data.Store
import io.github.sloppytopp.homewatch.detect.Engine
import io.github.sloppytopp.homewatch.detect.Snapshot

/** Process-wide hub: the service feeds the engine, the UI observes [snapshot]. */
object Monitor {
    var onAlert: (String, String) -> Unit = { _, _ -> }
    val engine = Engine(onAlert = { d, t -> onAlert(d, t) }, eventSink = { runCatching { Store.addEvent(it) } }, trailSink = { runCatching { Store.addSight(it) } }, netSink = { runCatching { Store.addKnownNets(it) } },
        isMine = { runCatching { io.github.sloppytopp.homewatch.data.Prefs.isMine(it) }.getOrDefault(false) },
        locProvider = { lastLoc?.takeIf { System.currentTimeMillis() - it.time <= 120_000 }?.let { it.latitude to it.longitude } })   // a stale fix must not read as "the phone did not move"
    var snapshot by mutableStateOf(Snapshot())
        private set

    fun refresh() { snapshot = engine.snapshot() }

    /**
     * Rebaseline: the user has checked that everything around them is theirs. Lock in what is here right now (Wi-Fi networks, trackers,
     * camera-like sources) as safe, clear the open items, and from now on flag any new Wi-Fi network as a Suspect right away.
     */
    fun rebaseline() {
        val now = System.currentTimeMillis()
        val s = engine.snapshot()
        engine.lockKnownNets(s.wifi.map { it.bssid })
        s.trackers.forEach { io.github.sloppytopp.homewatch.data.Prefs.markMine(it.addr) }
        engine.camKeysNow().forEach { io.github.sloppytopp.homewatch.data.Prefs.markMine(it) }
        val open = (runCatching { Store.events(now - 72 * 3_600_000L, 300) }.getOrDefault(emptyList()) + s.events)
            .filter { it.level == io.github.sloppytopp.homewatch.detect.Level.WATCH || it.level == io.github.sloppytopp.homewatch.detect.Level.ALERT }
        io.github.sloppytopp.homewatch.data.Prefs.resolveMany(open.map { io.github.sloppytopp.homewatch.detect.Timeline.key(it) })
        engine.baselineLocked = true
        io.github.sloppytopp.homewatch.data.Prefs.saveBaselineAt(now)
        refresh()
    }

    /** Stop treating new Wi-Fi networks as Suspects. Devices already marked as yours stay marked. */
    fun resetBaseline() {
        io.github.sloppytopp.homewatch.data.Prefs.saveBaselineAt(0)
        engine.baselineLocked = false
        refresh()
    }

    private var inspector: BleScanner? = null
    var inspecting by mutableStateOf(false); private set

    /** Unfiltered Bluetooth scan (shows every device). Only while the Nearby screen asks for it; Android pauses it with the screen off. */
    fun setInspect(ctx: android.content.Context, on: Boolean) {
        if (on == inspecting) return
        if (on) { inspector = BleScanner(ctx.applicationContext, inspect = true); if (inspector!!.start()) inspecting = true else inspector = null }
        else { inspector?.stop(); inspector = null; inspecting = false; engine.clearInspect() }
    }

    var lastSweep by mutableStateOf<String?>(null)

    /** Ask for Wi-Fi scans as often as Android allows (room sweeps, hunts). */
    var fastWifi by mutableStateOf(false)

    /** Survey walk: log every sighting together with the phone's GPS position. */
    var surveying by mutableStateOf(false)
    var surveyCount by mutableStateOf(0)
    var surveyNote by mutableStateOf<String?>(null)
    var lastLoc by mutableStateOf<android.location.Location?>(null)

    /** True while the PIN screen is showing. */
    var locked by mutableStateOf(false)

    /** Which alert the user is currently hunting ("tracker" | "drone" | "camera"), or null. */
    var hunt by mutableStateOf<String?>(null)

    /** Ask the root screen to switch tabs (0 Status .. 5 Settings), e.g. "go set up a survey walk for this". Consumed once, then cleared. */
    var requestTab by mutableStateOf<Int?>(null)

}
