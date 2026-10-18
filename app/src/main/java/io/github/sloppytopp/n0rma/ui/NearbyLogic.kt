package io.github.sloppytopp.n0rma.ui

import io.github.sloppytopp.n0rma.detect.WifiRow
import kotlin.math.abs
import kotlin.math.min

/** Wi-Fi channel load for the spectrum strip. Pure, so it can be unit-tested without a phone. */
object Spectrum {
    /** [section] 0 = 2.4 GHz, 1 = 5 GHz, 2 = 6 GHz. [load] 0..1 is how crowded the channel is; [count] = networks whose own channel this is. */
    data class Bin(val section: Int, val ch: Int, val load: Float, val count: Int) { val key get() = "$section:$ch" }

    val CH_5G = listOf(36, 40, 44, 48, 52, 56, 60, 64, 100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 144, 149, 153, 157, 161, 165)

    /** Channel number for a frequency in MHz, or 0 when it is not a Wi-Fi channel we know. */
    fun channel(freqMhz: Int): Int = when (freqMhz) {
        2484 -> 14
        in 2412..2472 -> (freqMhz - 2407) / 5
        in 5170..5895 -> (freqMhz - 5000) / 5
        in 5955..7115 -> (freqMhz - 5950) / 5
        else -> 0
    }

    private fun strength(dbm: Int) = ((dbm + 95) / 55f).coerceIn(0f, 1f)

    /** A 20 MHz 2.4 GHz network smears over neighbouring channels; channels 5 apart no longer overlap. */
    private fun overlap(d: Int) = when (d) { 0 -> 1f; 1 -> 0.8f; 2 -> 0.6f; 3 -> 0.4f; 4 -> 0.2f; else -> 0f }

    fun bins(wifi: List<WifiRow>): List<Bin> {
        val out = ArrayList<Bin>()
        val on24 = wifi.filter { ScopeLayout.wifiBand(it.freq) == 0 }.map { channel(it.freq) to strength(it.level) }
        for (c in 1..13) out += Bin(0, c, min(1f, on24.sumOf { (n, s) -> (overlap(abs(c - n)) * s).toDouble() }.toFloat() / 1.2f), on24.count { it.first == c })
        val on5 = wifi.filter { ScopeLayout.wifiBand(it.freq) == 1 }.map { channel(it.freq) to strength(it.level) }
        for (c in CH_5G) out += Bin(1, c, min(1f, on5.filter { it.first == c }.sumOf { it.second.toDouble() }.toFloat() / 1.2f), on5.count { it.first == c })
        val on6 = wifi.filter { ScopeLayout.wifiBand(it.freq) == 2 }.map { channel(it.freq) to strength(it.level) }
        for (c in on6.map { it.first }.distinct().sorted()) out += Bin(2, c, min(1f, on6.filter { it.first == c }.sumOf { it.second.toDouble() }.toFloat() / 1.2f), on6.count { it.first == c })
        return out
    }

    /** One plain sentence about the most crowded channel, or null when nothing was heard. */
    fun busiest(bins: List<Bin>): String? {
        val b = bins.filter { it.count > 0 }.maxByOrNull { it.load } ?: return null
        val band = listOf("2.4 GHz", "5 GHz", "6 GHz")[b.section]
        return "Busiest: $band channel ${b.ch} (${b.count} network${if (b.count == 1) "" else "s"})"
    }
}

/** Scrolling history of Bluetooth signal strengths, one column per tick, oldest on the left. */
class WaterfallBuffer(val cols: Int = 60, val rows: Int = 14) {
    private val data = ArrayList<IntArray>()

    /** 0 = weakest row (-100 dBm), rows-1 = strongest (-35 dBm and up). */
    fun row(rssi: Int) = ((rssi + 100) / 5).coerceIn(0, rows - 1)

    fun push(rssis: List<Int>) {
        val col = IntArray(rows)
        for (r in rssis) col[row(r)]++
        data += col
        while (data.size > cols) data.removeAt(0)
    }

    /** Count of devices in that cell; [col] 0 is the oldest column still held (columns not yet filled read as 0). */
    fun cell(col: Int, row: Int): Int {
        val i = col - (cols - data.size)
        return if (i < 0 || i >= data.size) 0 else data[i][row]
    }
}

/** Remembers what was already on a radar so a device that turns up later can be called out. The first look once the radio has reported is the baseline and flags nothing. */
class ArrivalTracker {
    private val seen = HashSet<String>()
    private val first = HashMap<String, Long>()
    private var primed = false

    /** [ready] = this radio has actually reported (default: it heard something). Until then nothing is baselined, so nothing is flagged. */
    fun update(keys: Collection<String>, now: Long, ready: Boolean = keys.isNotEmpty()) {
        if (!primed) { if (!ready) return; seen.addAll(keys); primed = true; return }
        for (k in keys) if (seen.add(k)) first[k] = now
    }

    fun ageMs(key: String, now: Long): Long? = first[key]?.let { now - it }
    fun justArrived(key: String, now: Long) = ageMs(key, now)?.let { it <= 4_000 } == true
    fun isNew(key: String, now: Long) = ageMs(key, now)?.let { it <= 15_000 } == true
}

/** What a sweep did and did not look at, in words. */
class Receipt(val at: Long, val checked: List<String>, val notChecked: List<String>, val flagged: Int)

object SweepReceipt {
    private fun n(count: Int, one: String, many: String = one + "s") = "$count ${if (count == 1) one else many}"

    fun build(
        at: Long, wifiScanned: Boolean, wifiCount: Int, wifiSuspicious: Int, bleSignals: Long, trackers: Int, drones: Int, flagged: Int, rfOn: Boolean,
    ): Receipt {
        val checked = ArrayList<String>()
        val not = ArrayList<String>()
        if (wifiScanned) checked += "Wi-Fi: ${n(wifiCount, "network")} heard" + if (wifiSuspicious > 0) ", $wifiSuspicious look like cameras or drones" else ", none look like cameras or drones"
        else not += "Wi-Fi: no fresh Wi-Fi scan (Android limits them) - sweep again"
        checked += "Bluetooth: ${bleSignals.coerceAtLeast(0)} signals heard"
        checked += "Trackers: " + if (trackers == 0) "none heard" else "${n(trackers, "tracker signal")} in range"
        checked += "Drones: " + if (drones == 0) "no Remote ID broadcasts" else n(drones, "Remote ID claim")
        not += "Cellular and GPS trackers"
        not += "Cameras that only record to an SD card"
        not += "Drones that do not broadcast Remote ID"
        if (!rfOn) not += "Hidden microphones (needs the optional RTL-SDR build)"
        return Receipt(at, checked, not, flagged)
    }
}
