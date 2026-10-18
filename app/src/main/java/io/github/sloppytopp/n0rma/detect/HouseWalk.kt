package io.github.sloppytopp.n0rma.detect

enum class Verdict { INSIDE, UNCLEAR, OUTSIDE, MINE }

/** [room] is the loudest room when the device is clearly louder in one (by [HouseWalk.ROOM_MARGIN_DB]) or was heard in only one; null = strong in every room. */
data class WalkFinding(val key: String, val kind: String, val label: String, val verdict: Verdict, val room: String?, val maxRssi: Double, val rooms: Int, val nameOnly: Boolean = false)

/**
 * Reads a walk through the house (one sweep per room, no GPS) and says what is probably inside the home and what is a neighbor's.
 * Signal strength through walls is a hint, not proof: a strong source is close, a source that is weak in every room is outside.
 */
object HouseWalk {
    const val INSIDE_DBM = -52.0
    const val OUTSIDE_DBM = -60.0
    const val ROOM_MARGIN_DB = 8.0

    /** [isMine]: this exact device (BSSID / address) was confirmed by the user. [usesMyName]: a Wi-Fi network that only shares one of the user's SSIDs, which anyone can copy, so it is never hidden as "yours". */
    fun report(scans: Collection<RoomScan>, isMine: (RoomItem) -> Boolean, usesMyName: (RoomItem) -> Boolean = { false }): List<WalkFinding> {
        val all = scans.flatMap { s -> s.items.map { s.room to it } }
            .filter { (_, i) -> i.kind != "ble" || i.label != "Bluetooth device" } // phones and watches rotate their address
        return all.groupBy { it.second.key }.map { (key, hits) ->
            val best = hits.maxByOrNull { it.second.rssi }!!
            val item = best.second
            val per = hits.sortedByDescending { it.second.rssi }
            val margin = per.getOrNull(1)?.let { per[0].second.rssi - it.second.rssi }
            val room = if (scans.size == 1 || hits.size == 1 || (margin != null && margin >= ROOM_MARGIN_DB)) best.first else null
            val verdict = when {
                isMine(item) -> Verdict.MINE
                item.rssi >= INSIDE_DBM -> Verdict.INSIDE
                item.rssi <= OUTSIDE_DBM -> Verdict.OUTSIDE
                else -> Verdict.UNCLEAR
            }
            WalkFinding(key, item.kind, item.label, verdict, room, item.rssi, hits.size, nameOnly = verdict != Verdict.MINE && usesMyName(item))
        }.sortedWith(compareBy<WalkFinding> { it.verdict.ordinal }.thenByDescending { it.maxRssi })
    }
}
