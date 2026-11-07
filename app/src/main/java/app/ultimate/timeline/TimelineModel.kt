package app.ultimate.timeline

import java.util.TimeZone

/** Where a moment came from, and the tool that holds it. */
enum class Source(val label: String, val toolId: String) {
    Notification("Notifications", "inbox"), Clip("Copied", "clips"), Watched("Watched and listened", "footprint"), Focus("Focus", "focus"),
    Photo("Photos", "cleanup"), Screenshot("Screenshots", "cleanup"), Install("Installed apps", "pluck"), Charge("Charging", "checkup"),
}

/** One thing that happened, from any of the app's own records: a notification, something copied, something played, a focus session. */
data class Moment(
    val source: Source, val at: Long, val title: String, val detail: String = "", val app: String = "", val pkg: String? = null,
    val end: Long = at, val focusDrift: Int = 0, val level: Int = -1, val level2: Int = -1,
    /** Unique within its source (a database row, a clip, a gallery item, a package): list keys and actions rely on it. */
    val id: String = "", val key: String = "", val uri: String? = null,
) {
    /** A key no two moments share, even two identical notifications in the same millisecond. */
    val uid: String get() = "${source.name}:" + id.ifBlank { "$at:${title.hashCode()}:${detail.hashCode()}" }
}

/** A finding, and what to show when it is tapped: one kind of record, a search, or only the small hours. */
data class Finding(val text: String, val source: Source? = null, val query: String? = null, val lateNight: Boolean = false)

/** One day's totals for the strip of days. */
data class DayCount(val day: Long, val notifications: Int, val clips: Int, val watchedMs: Long, val focusMs: Long) {
    val total get() = notifications + clips + (if (watchedMs > 0) 1 else 0) + (if (focusMs > 0) 1 else 0)

    /** How much each kind filled the day, on one scale for the stacked bars: a notification is 1, a copy 3, a minute of playing or focus 0.2. */
    val parts: List<Pair<Source, Float>> get() = listOf(Source.Notification to notifications.toFloat(), Source.Clip to clips * 3f, Source.Watched to watchedMs / 300_000f, Source.Focus to focusMs / 300_000f)
    val weight get() = parts.sumOf { it.second.toDouble() }.toFloat()
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
    fun insights(all: List<Moment>, now: Long, zone: TimeZone = TimeZone.getDefault()): List<String> = findings(all, now, zone).map { it.text }

    /** The same findings, each with the filter that shows the records behind it. */
    fun findings(all: List<Moment>, now: Long, zone: TimeZone = TimeZone.getDefault()): List<Finding> = buildList {
        fun add(text: String, source: Source? = null, query: String? = null, late: Boolean = false) { add(Finding(text, source, query, late)) }
        val notes = all.filter { it.source == Source.Notification }
        val focus = all.filter { it.source == Source.Focus && it.end > it.at }
        val watched = all.filter { it.source == Source.Watched }
        val clips = all.filter { it.source == Source.Clip }

        // Who interrupts you while you are trying to focus.
        if (focus.isNotEmpty()) {
            val during = notes.filter { n -> focus.any { n.at in it.at..it.end } }
            if (during.size >= 3) {
                val top = during.groupingBy { it.app }.eachCount().maxByOrNull { it.value }!!
                add(source = Source.Notification, query = top.key, text = "${top.key} pinged you ${top.value} time${if (top.value == 1) "" else "s"} while you were focusing. That's ${during.size} interruptions across ${focus.size} session${if (focus.size == 1) "" else "s"}.")
            }
        }

        val days = days(all, 30, now, zone).filter { it.total > 0 }
        // Do focus days come with fewer notifications?
        val focusDays = days.filter { it.focusMs > 0 }; val otherDays = days.filter { it.focusMs == 0L }
        if (focusDays.size >= 2 && otherDays.size >= 2) {
            val a = focusDays.map { it.notifications }.average(); val b = otherDays.map { it.notifications }.average()
            if (b > 0 && kotlin.math.abs(a - b) / b >= 0.15) add(source = Source.Focus, text = "On days you used Focus you got ${pct(a, b)}% ${if (a < b) "fewer" else "more"} notifications (${Math.round(a)} against ${Math.round(b)}).")
        }

        // Does a busy phone mean more watching?
        val withBoth = days.filter { it.notifications > 0 }
        if (withBoth.size >= 6 && watched.isNotEmpty()) {
            val sorted = withBoth.sortedBy { it.notifications }; val half = sorted.size / 2
            val quiet = sorted.take(half).map { it.watchedMs }.average(); val busy = sorted.takeLast(half).map { it.watchedMs }.average()
            if (quiet > 0 && busy / quiet >= 1.4) add(source = Source.Watched, text = "You watch and listen ${String.format(java.util.Locale.US, "%.1f", busy / quiet)}× as much on your busiest notification days.")
            else if (busy > 0 && quiet / busy >= 1.4) add(source = Source.Watched, text = "Busy notification days leave less time for watching: ${mins(busy.toLong())} against ${mins(quiet.toLong())} on quieter days.")
        }

        // What you copy straight out of notifications (often codes and addresses).
        val copiedFrom = clips.mapNotNull { c -> notes.lastOrNull { it.at in (c.at - 120_000)..c.at }?.app }
        if (copiedFrom.size >= 3) {
            val top = copiedFrom.groupingBy { it }.eachCount().maxByOrNull { it.value }!!
            add(source = Source.Clip, text = "${copiedFrom.size} things you copied came right after a notification, most often from ${top.key} (${top.value}).")
        }

        // Late nights, from any record.
        val late = all.filter { hourOf(it.at, zone) in 0..3 }.map { dayOf(it.at, zone) }.toSet()
        if (late.size >= 3) {
            // Name only what you were doing (watching or listening): a photo's folder or a charger is not an activity.
            val whatKeepsYou = all.filter { hourOf(it.at, zone) in 0..3 && it.source == Source.Watched && it.app.isNotBlank() }.groupingBy { it.app }.eachCount().maxByOrNull { it.value }
            // Count against every day the records cover, so it can never read "21 of the last 13".
            val covered = (all.map { dayOf(it.at, zone) }.toSet() + late).size
            add(late = true, text = "You were up past midnight on ${late.size} of the last $covered days" + (whatKeepsYou?.let { ", most often with ${it.key}" } ?: "") + ".")
        }

        // Screenshots: how many, and when you take them.
        val shots = all.filter { it.source == Source.Screenshot }
        if (shots.size >= 5) {
            val h = shots.groupingBy { hourOf(it.at, zone) }.eachCount().maxByOrNull { it.value }!!.key
            add(source = Source.Screenshot, text = "You took ${shots.size} screenshots, most around ${if (h % 12 == 0) 12 else h % 12}${if (h < 12) "am" else "pm"}.")
        }
        // Photos: the day of the week the camera comes out.
        val photos = all.filter { it.source == Source.Photo }
        if (photos.size >= 10) {
            val byDay = photos.groupingBy { java.util.Calendar.getInstance(zone).apply { timeInMillis = it.at }.get(java.util.Calendar.DAY_OF_WEEK) }.eachCount().maxByOrNull { it.value }!!
            add(source = Source.Photo, text = "You took ${photos.size} photos; your camera comes out most on ${java.text.DateFormatSymbols.getInstance().weekdays[byDay.key]}s (${byDay.value}).")
        }
        // New apps.
        val installs = all.filter { it.source == Source.Install }
        if (installs.size >= 3) add(source = Source.Install, text = "You installed ${installs.size} apps, most recently ${installs.maxByOrNull { it.at }!!.title}.")
        // Charging habits.
        val charges = all.filter { it.source == Source.Charge && it.end > it.at }
        if (charges.size >= 4) {
            val overnight = charges.filter { c -> (0..5).any { k -> val t = c.at + k * 3_600_000L; t <= c.end && hourOf(t, zone) == 3 } || (hourOf(c.at, zone) <= 3 && hourOf(c.end, zone) >= 4 && c.end - c.at > 3_600_000) }
                .map { dayOf(it.end, zone) }.toSet()
            val nights = days.size.coerceAtLeast(1)
            if (overnight.isNotEmpty()) add(source = Source.Charge, text = "You charged overnight on ${overnight.size} of the last $nights nights.")
            val ins = charges.mapNotNull { it.level.takeIf { l -> l in 0..100 } }.sorted()
            if (ins.size >= 4) add(source = Source.Charge, text = "You usually plug in at about ${ins[ins.size / 2]}%.")
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
