package io.github.sloppytopp.n0rma.ui

/**
 * Where a contact sits on the Nearby scopes. Neither radio gives a direction, so the angle is a category, not a compass bearing:
 * Wi-Fi is laid out by band and channel (2.4 / 5 / 6 GHz wedges), Bluetooth by kind (drone / tracker / other). Distance (the radius)
 * is the same signal-strength estimate the Radar tab uses. Kept free of Compose so it can be unit-tested.
 */
object ScopeLayout {
    const val WEDGE_DEG = 120f
    private const val PAD_DEG = 10f   // keep dots off the wedge dividers

    val WIFI_BANDS = listOf("2.4G", "5G", "6G")
    val BLE_KINDS = listOf("DRONE", "TRACKER", "OTHER")
    const val BLE_DRONE = 0
    const val BLE_TRACKER = 1
    const val BLE_OTHER = 2

    /** 0 = 2.4 GHz, 1 = 5 GHz, 2 = 6 GHz, -1 = frequency unknown. */
    fun wifiBand(freqMhz: Int): Int = when (freqMhz) {
        in 2400..2500 -> 0
        in 4900..5895 -> 1
        in 5925..7125 -> 2
        else -> -1
    }

    /** 0..1 position of [freqMhz] along its own band, so neighbouring channels sit next to each other. */
    private fun bandFraction(freqMhz: Int): Float {
        val (lo, hi) = when (wifiBand(freqMhz)) { 0 -> 2412 to 2484; 1 -> 5170 to 5895; else -> 5955 to 7115 }
        return ((freqMhz - lo).toFloat() / (hi - lo)).coerceIn(0f, 1f)
    }

    /** Stable 0..1 value for a key, so a contact keeps its spot between refreshes. */
    fun spread(key: String): Float = ((key.hashCode() ushr 1) % 1000) / 1000f

    /** Clockwise degrees from the top of the scope. */
    fun wifiAngle(freqMhz: Int, key: String): Float {
        val band = wifiBand(freqMhz)
        if (band < 0) return spread(key) * 360f   // no channel reported: spread around the whole scope
        val within = bandFraction(freqMhz) * 0.8f + spread(key) * 0.2f   // channel decides most of it; the key keeps same-channel networks apart
        return band * WEDGE_DEG + PAD_DEG + within * (WEDGE_DEG - 2 * PAD_DEG)
    }

    fun bleAngle(kind: Int, key: String): Float = kind * WEDGE_DEG + PAD_DEG + spread(key) * (WEDGE_DEG - 2 * PAD_DEG)

    /** Same ring layout as the Radar tab: rings at about 5 m and 20 m, edge = far. */
    fun radius(meters: Double): Float = io.github.sloppytopp.n0rma.detect.Geo.radarFraction(meters).toFloat()
}
