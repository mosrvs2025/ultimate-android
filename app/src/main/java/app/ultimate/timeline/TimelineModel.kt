package app.ultimate.timeline

import java.util.TimeZone

/** Where a moment came from, and the tool that holds it. */
enum class Source(val label: String, val toolId: String) {
    Notification("Notifications", "inbox"), Clip("Copied", "clips"), Watched("Watched and listened", "footprint"), Focus("Focus", "focus"),
}

/** One thing that happened, from any of the app's own records: a notification, something copied, something played, a focus session. */
data class Moment(
    val source: Source, val at: Long, val title: String, val detail: String = "", val app: String = "", val pkg: String? = null,
    val end: Long = at, val focusDrift: Int = 0,
)

/** One day's totals for the strip of days. */
data class DayCount(val day: Long, val notifications: Int, val clips: Int, val watchedMs: Long, val focusMs: Long) {
    val total get() = notifications + clips + (if (watchedMs > 0) 1 else 0) + (if (focusMs > 0) 1 else 0)
}

/**
 * Pulls the app's separate records together so they can be searched in one place, looked at day by day, and read
 * across each other for things none of them shows alone. Pure: the reading happens elsewhere.
 */
object TimelineMath {
    private const val DAY = 86_400_000L

    fun dayOf(at: Long, zone: TimeZone = TimeZone.getDefault()) = Math.floorDiv(at + zone.getOffset(at), DAY)
    fun startOf(day: Long, zone: TimeZone = TimeZone.getDefault()): Long { val guess = day * DAY; return guess - zone.getOffset(guess) }
    private fun hourOf(at: Long, zone: TimeZone) = (Math.floorMod(at + zone.getOffset(at), DAY) / 3_600_000L).toInt()

    /** Every word typed must appear somewhere in the moment: its title, its text or its app. Newest first. */
    fun search(all: List<Moment>, query: String): List<Moment> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return all.filter { m -> val hay = "${m.title} ${m.detail} ${m.app} ${m.source.label}".lowercase(); words.all { it in hay } }.sortedByDescending { it.at }
    }

    fun onDay(all: List<Moment>, day: Long, zone: TimeZone = TimeZone.getDefault()) = all.filter { dayOf(it.at, zone) == day }.sortedBy { it.at }

    /** Totals for each of the last [days] days, oldest first. */
    fun days(all: List<Moment>, days: Int, now: Long, zone: TimeZone = TimeZone.getDefault()): List<DayCount> {
        val today = dayOf(now, zone)
        val by = all.groupBy { dayOf(it.at, zone) }
        return (days - 1 downTo 0).map { ago ->
            val d = today - ago; val list = by[d].orEmpty()
            DayCount(d, list.count { it.source == Source.Notification }, list.count { it.source == Source.Clip },
                list.filter { it.source == Source.Watched }.sumOf { it.end - it.at }, list.filter { it.source == Source.Focus }.sumOf { it.end - it.at })
        }
    }

    /** How many of each kind fell in each hour of [list], for the day's stacked bars. */
    fun hours(list: List<Moment>, zone: TimeZone = TimeZone.getDefault()): Array<IntArray> {
        val out = Array(24) { IntArray(Source.entries.size) }
        list.forEach { out[hourOf(it.at, zone)][it.source.ordinal]++ }
        return out
    }

    private fun pct(a: Double, b: Double) = Math.round(kotlin.math.abs(a - b) / b * 100).toInt()
    private fun mins(ms: Long) = if (ms >= 3_600_000) "${ms / 3_600_000}h ${ms / 60_000 % 60}m" else "${ms / 60_000} min"

    /**
     * Findings that only show when the records are read together, most telling first. Each needs enough data behind it
     * to be worth saying, so a new phone says little and a month says a lot.
     */
    fun insights(all: List<Moment>, now: Long, zone: TimeZone = TimeZone.getDefault()): List<String> = buildList {
        val notes = all.filter { it.source == Source.Notification }
        val focus = all.filter { it.source == Source.Focus && it.end > it.at }
        val watched = all.filter { it.source == Source.Watched }
        val clips = all.filter { it.source == Source.Clip }

        // Who interrupts you while you are trying to focus.
        if (focus.isNotEmpty()) {
            val during = notes.filter { n -> focus.any { n.at in it.at..it.end } }
            if (during.size >= 3) {
                val top = during.groupingBy { it.app }.eachCount().maxByOrNull { it.value }!!
                add("${top.key} pinged you ${top.value} time${if (top.value == 1) "" else "s"} while you were focusing. That's ${during.size} interruptions across ${focus.size} session${if (focus.size == 1) "" else "s"}.")
            }
        }

        val days = days(all, 30, now, zone).filter { it.total > 0 }
        // Do focus days come with fewer notifications?
        val focusDays = days.filter { it.focusMs > 0 }; val otherDays = days.filter { it.focusMs == 0L }
        if (focusDays.size >= 2 && otherDays.size >= 2) {
            val a = focusDays.map { it.notifications }.average(); val b = otherDays.map { it.notifications }.average()
            if (b > 0 && kotlin.math.abs(a - b) / b >= 0.15) add("On days you used Focus you got ${pct(a, b)}% ${if (a < b) "fewer" else "more"} notifications (${Math.round(a)} against ${Math.round(b)}).")
        }

        // Does a busy phone mean more watching?
        val withBoth = days.filter { it.notifications > 0 }
        if (withBoth.size >= 6 && watched.isNotEmpty()) {
            val sorted = withBoth.sortedBy { it.notifications }; val half = sorted.size / 2
            val quiet = sorted.take(half).map { it.watchedMs }.average(); val busy = sorted.takeLast(half).map { it.watchedMs }.average()
            if (quiet > 0 && busy / quiet >= 1.4) add("You watch and listen ${String.format(java.util.Locale.US, "%.1f", busy / quiet)}× as much on your busiest notification days.")
            else if (busy > 0 && quiet / busy >= 1.4) add("Busy notification days leave less time for watching: ${mins(busy.toLong())} against ${mins(quiet.toLong())} on quieter days.")
        }

        // What you copy straight out of notifications (often codes and addresses).
        val copiedFrom = clips.mapNotNull { c -> notes.lastOrNull { it.at in (c.at - 120_000)..c.at }?.app }
        if (copiedFrom.size >= 3) {
            val top = copiedFrom.groupingBy { it }.eachCount().maxByOrNull { it.value }!!
            add("${copiedFrom.size} things you copied came right after a notification, most often from ${top.key} (${top.value}).")
        }

        // Late nights, from any record.
        val late = all.filter { hourOf(it.at, zone) in 0..3 }.map { dayOf(it.at, zone) }.toSet()
        if (late.size >= 3) {
            val whatKeepsYou = all.filter { hourOf(it.at, zone) in 0..3 && it.source != Source.Notification }.groupingBy { it.app.ifBlank { it.source.label } }.eachCount().maxByOrNull { it.value }
            add("You were up past midnight on ${late.size} of the last ${days.size} days" + (whatKeepsYou?.let { ", most often with ${it.key}" } ?: "") + ".")
        }

        // The busiest day of the week for everything together.
        if (days.size >= 10) {
            val byWeekday = all.groupingBy { java.util.Calendar.getInstance(zone).apply { timeInMillis = it.at }.get(java.util.Calendar.DAY_OF_WEEK) }.eachCount()
            byWeekday.maxByOrNull { it.value }?.let { (d, _) ->
                val name = java.text.DateFormatSymbols.getInstance().weekdays[d]
                add("$name is your busiest day across everything the app keeps.")
            }
        }
    }
}
