package io.github.sloppytopp.n0rma.detect

/** One appear/disappear mark for a logged Wi-Fi source, or a marker for when scanning started or stopped (key "*"). */
data class PresenceEvent(val ts: Long, val key: String, val label: String, val kind: String, val rssi: Int, val freq: Int) {
    companion object { const val SEEN = "seen"; const val NAME = "name"; const val GONE = "gone"; const val START = "start"; const val STOP = "stop" }
}

fun locallyAdministered(bssid: String): Boolean = bssid.take(2).toIntOrNull(16)?.let { it and 2 != 0 } ?: false

/** A Wi-Fi source worth logging over time: it hides its name, or uses a private (locally administered) address. */
fun isLogged(w: WifiRow): Boolean = w.ssid.isBlank() || w.ssid == "(hidden)" || locallyAdministered(w.bssid)

/** Turns a stream of Wi-Fi scans into appear/disappear events. A source is gone once it has missed scans for [goneAfterMs]. */
class PresenceTracker(private val goneAfterMs: Long = 150_000L) {
    private class Live(var last: Long, var rssi: Int, var label: String, var freq: Int)
    private val live = HashMap<String, Live>()

    fun onScan(now: Long, rows: List<WifiRow>): List<PresenceEvent> {
        val out = ArrayList<PresenceEvent>()
        val here = HashSet<String>()
        for (w in rows) {
            if (!isLogged(w)) continue
            val k = "wifi:" + w.bssid
            here += k
            val l = live[k]
            if (l == null) {
                val label = w.ssid.ifBlank { "(hidden)" }
                live[k] = Live(now, w.level, label, w.freq)
                out += PresenceEvent(now, k, label, PresenceEvent.SEEN, w.level, w.freq)
            } else {
                l.last = now; l.rssi = w.level; l.freq = w.freq
                // a source that hid its name now shows one (or changed it): keep the name, it says what the thing is
                if (w.ssid.isNotBlank() && w.ssid != "(hidden)" && w.ssid != l.label) {
                    l.label = w.ssid
                    out += PresenceEvent(now, k, w.ssid, PresenceEvent.NAME, w.level, w.freq)
                }
            }
        }
        val it = live.iterator()
        while (it.hasNext()) {
            val (k, l) = it.next()
            if (k !in here && now - l.last >= goneAfterMs) { out += PresenceEvent(l.last, k, l.label, PresenceEvent.GONE, l.rssi, l.freq); it.remove() }
        }
        return out
    }

    fun reset() = live.clear()
}

data class PresenceSource(val key: String, val label: String, val intervals: List<LongRange>, val hidden: Boolean) {
    val appearances get() = intervals.size
    val totalMs get() = intervals.sumOf { it.last - it.first }
    val lastSeen get() = intervals.maxOfOrNull { it.last } ?: 0L
}

object PresenceSummary {
    /** Per-source presence intervals inside [now - windowMs, now]. [scanningNow]: still-open intervals run to now; otherwise they end at the last logged event. */
    fun build(events: List<PresenceEvent>, now: Long, windowMs: Long, scanningNow: Boolean): List<PresenceSource> {
        val from = now - windowMs
        val sorted = events.sortedBy { it.ts }
        val endOpen = if (scanningNow) now else (sorted.lastOrNull()?.ts ?: now)
        val open = HashMap<String, Long>()
        val labels = HashMap<String, String>()
        val everHidden = HashSet<String>()
        val ivs = HashMap<String, MutableList<LongRange>>()
        fun close(k: String, end: Long) {
            val s = open.remove(k) ?: return
            val a = maxOf(s, from); val b = minOf(end, now)
            if (b >= a && end >= from) ivs.getOrPut(k) { ArrayList() }.add(a..b)
        }
        var lastTs = 0L
        for (e in sorted) {
            when (e.kind) {
                PresenceEvent.SEEN -> {
                    if (e.key !in open) open[e.key] = e.ts
                    labels[e.key] = e.label
                    if (e.label == "(hidden)") everHidden += e.key
                }
                PresenceEvent.NAME -> labels[e.key] = e.label
                PresenceEvent.GONE -> close(e.key, e.ts)
                PresenceEvent.STOP -> open.keys.toList().forEach { close(it, e.ts) }
                // a START while sources are still open means the previous run died without saying so (app killed or reinstalled):
                // end them at the last thing we logged, since nothing is known after that
                PresenceEvent.START -> open.keys.toList().forEach { close(it, lastTs) }
            }
            lastTs = e.ts
        }
        open.keys.toList().forEach { close(it, endOpen) }
        return ivs.map { (k, l) -> PresenceSource(k, labels[k] ?: k, l, hidden = k in everHidden) }
    }

    /** How long N0RMA was actually scanning inside the window, so a quiet log isn't mistaken for a quiet house. */
    fun scanningMs(events: List<PresenceEvent>, now: Long, windowMs: Long, scanningNow: Boolean): Long {
        val from = now - windowMs
        var start: Long? = null
        var total = 0L
        var lastTs = 0L
        for (e in events.sortedBy { it.ts }) {
            if (e.key == "*" && e.kind == PresenceEvent.START) {
                if (start != null) total += maxOf(0L, minOf(lastTs, now) - maxOf(start, from)) // previous run died without a stop marker
                start = e.ts
            } else if (e.key == "*" && e.kind == PresenceEvent.STOP && start != null) { total += maxOf(0L, minOf(e.ts, now) - maxOf(start, from)); start = null }
            lastTs = e.ts
        }
        if (start != null && scanningNow) total += maxOf(0L, now - maxOf(start, from))
        return total
    }
}
