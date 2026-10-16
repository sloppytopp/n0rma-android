package io.github.sloppytopp.n0rma.detect

enum class TimelineFilter(val label: String) { ALL("All"), INFO("Info"), SUSPECT("Suspect"), ALERT("Alert") }

/** The calm home screen's event list: plain words (Info / Suspect / Alert), newest first, with user-set "resolved" marks. */
object Timeline {
    fun key(e: EventRow) = "${e.ts}|${e.domain}"

    fun word(l: Level) = when (l) { Level.ALERT -> "Alert"; Level.WATCH -> "Suspect"; else -> "Info" }

    fun matches(e: EventRow, f: TimelineFilter) = when (f) {
        TimelineFilter.ALL -> true
        TimelineFilter.INFO -> e.level == Level.OK || e.level == Level.OFF
        TimelineFilter.SUSPECT -> e.level == Level.WATCH
        TimelineFilter.ALERT -> e.level == Level.ALERT
    }

    fun filter(events: List<EventRow>, f: TimelineFilter): List<EventRow> = events.filter { matches(it, f) }.sortedByDescending { it.ts }

    /** Suspect/alert events the user has not marked as resolved. */
    fun unresolved(events: List<EventRow>, resolved: Set<String>): Int =
        events.count { (it.level == Level.WATCH || it.level == Level.ALERT) && key(it) !in resolved }
}
