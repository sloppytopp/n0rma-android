package io.github.sloppytopp.n0rma.scan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.sloppytopp.n0rma.data.Store
import io.github.sloppytopp.n0rma.detect.Engine
import io.github.sloppytopp.n0rma.detect.Snapshot

/** Process-wide hub: the service feeds the engine, the UI observes [snapshot]. */
object Monitor {
    var onAlert: (String, String) -> Unit = { _, _ -> }
    val engine = Engine(onAlert = { d, t -> onAlert(d, t) }, eventSink = { runCatching { Store.addEvent(it) } }, trailSink = { runCatching { Store.addSight(it) } }, netSink = { runCatching { Store.addKnownNets(it) } },
        isMine = { runCatching { io.github.sloppytopp.n0rma.data.Prefs.isMine(it) }.getOrDefault(false) },
        locProvider = { lastLoc?.takeIf { System.currentTimeMillis() - it.time <= 120_000 }?.let { it.latitude to it.longitude } })   // a stale fix must not read as "the phone did not move"
    var snapshot by mutableStateOf(Snapshot())
        private set

    fun refresh() { engine.ownSsids = io.github.sloppytopp.n0rma.data.Prefs.mySsids; snapshot = engine.snapshot() }

    /**
     * Rebaseline: the user has checked that the Wi-Fi networks around them are theirs or normal. Lock those in, and from now on flag any new
     * Wi-Fi network as a Suspect right away.
     * Deliberately NOT done here: marking trackers or cameras as "mine", or auto-resolving open items. A tracker or camera that is in range at
     * this moment may be exactly the thing being hunted, so those stay flagged until the person decides about each one ("This is mine" /
     * "Mark resolved"). Silencing them for good in one tap would fail open.
     */
    fun rebaseline() {
        val s = engine.snapshot()
        engine.lockKnownNets(s.wifi.map { it.bssid })
        engine.baselineLocked = true
        io.github.sloppytopp.n0rma.data.Prefs.saveBaselineAt(System.currentTimeMillis())
        refresh()
    }

    /** Stop treating new Wi-Fi networks as Suspects. */
    fun resetBaseline() {
        io.github.sloppytopp.n0rma.data.Prefs.saveBaselineAt(0)
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

    /** Address and label of the device the hot/cold Finder is open for ("wifi:<bssid>" or a Bluetooth address), or null. */
    var finding by mutableStateOf<Pair<String, String>?>(null)

    /** Ask the root screen to switch tabs (0 Status .. 5 Settings), e.g. "go set up a survey walk for this". Consumed once, then cleared. */
    var requestTab by mutableStateOf<Int?>(null)

}
