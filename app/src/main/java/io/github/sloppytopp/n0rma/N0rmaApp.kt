package io.github.sloppytopp.n0rma

import android.app.Application
import io.github.sloppytopp.n0rma.data.Prefs
import io.github.sloppytopp.n0rma.data.Store
import io.github.sloppytopp.n0rma.detect.WifiClassifier

class N0rmaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Store.init(this)
        runCatching { io.github.sloppytopp.n0rma.scan.Monitor.engine.loadTrail(Store.trail()) }
        runCatching { io.github.sloppytopp.n0rma.scan.Monitor.engine.loadKnownNets(Store.knownNets()) }
        io.github.sloppytopp.n0rma.scan.Monitor.engine.baselineLocked = Prefs.baselineAt > 0
        runCatching { assets.open("stalkerware_packages.csv").bufferedReader().useLines { io.github.sloppytopp.n0rma.detect.Stalkerware.loadCsv(it) } }
        runCatching { assets.open("oui_known.csv").bufferedReader().useLines { WifiClassifier.loadKnown(it) } }
        runCatching { assets.open("oui_watch.csv").bufferedReader().useLines { WifiClassifier.loadCsv(it) } }
    }
}
