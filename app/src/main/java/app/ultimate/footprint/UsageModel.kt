package app.ultimate.footprint

import java.util.TimeZone

/** The kinds of moments Android's usage log records that matter here. */
/** Idle: the home screen or system took over, so whichever app was in front is no longer being used. */
enum class Evt { Resumed, Paused, ScreenOn, ScreenOff, Unlocked, Idle }

/** One logged moment: [pkg] is only meaningful for Resumed and Paused. */
data class UsageEvt(val at: Long, val type: Evt, val pkg: String = "")

/** A stretch with the screen off long enough to be sleep, guessed from the log. */
data class Sleep(val from: Long, val to: Long)

class UsageSummary(
    val days: Int,
    val pickups: Int,
    val unlocks: Int,
    val screenMs: Long,
    val opens: Map<String, Int>,
    val foregroundMs: Map<String, Long>,
    /** Minutes by [days ago 0..6][hour 0..23], in milliseconds of foreground use. */
    val heat: Array<LongArray>,
    val unlocksByHour: IntArray,
    val longestSessionMs: Long,
    val sleeps: List<Sleep>,
)

/**
 * Turns Android's usage log into patterns. Events arrive in time order, one by one, so a week of them is never held in memory.
 * Day and hour are the phone's own local time. Pure, so the arithmetic is tested.
 */
class UsageAccumulator(private val now: Long, private val zone: TimeZone = TimeZone.getDefault(), private val days: Int = 7) {
    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
        /** A single stay in one app longer than this is a log gap (a shutdown), not real use. */
        const val MAX_STAY = 6 * HOUR
        const val SLEEP_GAP = 3 * HOUR
    }

    private val opens = HashMap<String, Int>()
    private val fg = HashMap<String, Long>()
    private val heat = Array(days) { LongArray(24) }
    private val unlockHours = IntArray(24)
    private var pickups = 0
    private var unlocks = 0
    private var screenMs = 0L
    private var longest = 0L
    private var current: String? = null
    private var since = 0L
    private var screenOnAt = -1L
    private var lastOff = -1L
    private val sleeps = ArrayList<Sleep>()

    private fun localDay(t: Long) = Math.floorDiv(t + zone.getOffset(t), DAY)
    private fun localHour(t: Long) = (Math.floorMod(t + zone.getOffset(t), DAY) / HOUR).toInt()
    private val today = localDay(now)

    fun add(e: UsageEvt) {
        when (e.type) {
            Evt.Resumed -> {
                if (current != e.pkg) { current?.let { close(e.at) }; opens.merge(e.pkg, 1, Int::plus) }
                current = e.pkg; since = e.at
            }
            Evt.Paused -> if (current == e.pkg) close(e.at)
            Evt.Idle -> close(e.at)
            Evt.ScreenOn -> { if (screenOnAt < 0) { pickups++ ; screenOnAt = e.at }; sleepGapEnds(e.at) }
            Evt.ScreenOff -> {
                close(e.at)
                if (screenOnAt >= 0) { val d = (e.at - screenOnAt).coerceAtLeast(0); screenMs += d; longest = maxOf(longest, d); screenOnAt = -1 }
                lastOff = e.at
            }
            Evt.Unlocked -> { unlocks++; if (inWindow(e.at)) unlockHours[localHour(e.at)]++ }
        }
    }

    private fun inWindow(t: Long) = today - localDay(t) in 0 until days

    private fun close(at: Long) {
        val pkg = current ?: return
        current = null
        val end = at.coerceAtMost(since + MAX_STAY)
        if (end > since) span(since, end, pkg)
    }

    /** Adds a stay to the app's total and to each hour it covers, never into a day outside the window. */
    private fun span(from: Long, to: Long, pkg: String) {
        fg.merge(pkg, to - from, Long::plus)
        var t = from
        while (t < to) {
            val hourEnd = (Math.floorDiv(t + zone.getOffset(t), HOUR) + 1) * HOUR - zone.getOffset(t)
            val end = minOf(to, hourEnd.coerceAtLeast(t + 1))
            val ago = (today - localDay(t)).toInt()
            if (ago in 0 until days) heat[ago][localHour(t)] += end - t
            t = end
        }
    }

    /** A long dark gap that began in the evening or night and ended after is probably sleep. */
    private fun sleepGapEnds(on: Long) {
        if (lastOff < 0) return
        val gap = on - lastOff
        val startHour = localHour(lastOff)
        if (gap >= SLEEP_GAP && (startHour >= 20 || startHour <= 5)) sleeps += Sleep(lastOff, on)
        lastOff = -1
    }

    fun finish(): UsageSummary {
        // A stay still open at the end runs to now, within the same cap.
        close(now)
        if (screenOnAt >= 0) { val d = (now - screenOnAt).coerceAtLeast(0); screenMs += d; longest = maxOf(longest, d) }
        return UsageSummary(days, pickups, unlocks, screenMs, opens, fg, heat, unlockHours, longest, sleeps)
    }
}

/** Plain sentences about the patterns, with the numbers they stand on. */
object Habits {
    private fun hourName(h: Int) = when { h == 0 -> "midnight"; h < 12 -> "${h}am"; h == 12 -> "noon"; else -> "${h - 12}pm" }

    fun busiestHour(s: UsageSummary): Int? {
        val perHour = LongArray(24) { h -> s.heat.sumOf { it[h] } }
        return perHour.withIndex().maxByOrNull { it.value }?.takeIf { it.value > 0 }?.index
    }

    /** Minutes of use between midnight and 5am over the window. */
    fun lateNightMinutes(s: UsageSummary): Long = (0..4).sumOf { h -> s.heat.sumOf { it[h] } } / 60_000

    /** Average local bedtime and wake time as minutes since midnight, from the guessed sleeps; null when there are none. */
    fun typicalSleep(s: UsageSummary, zone: TimeZone = TimeZone.getDefault()): Pair<Int, Int>? {
        if (s.sleeps.isEmpty()) return null
        fun mins(t: Long) = (Math.floorMod(t + zone.getOffset(t), 24 * 3_600_000L) / 60_000).toInt()
        // Bedtimes after midnight are treated as late evening of the day before, so 23:30 and 00:30 average to 00:00, not noon.
        val bed = s.sleeps.map { mins(it.from).let { m -> if (m < 12 * 60) m + 24 * 60 else m } }.average().toInt() % (24 * 60)
        val wake = s.sleeps.map { mins(it.to) }.average().toInt()
        return bed to wake
    }

    fun clock(minutes: Int) = "%d:%02d".format(if (minutes / 60 % 12 == 0) 12 else minutes / 60 % 12, minutes % 60) + if (minutes / 60 % 24 < 12) "am" else "pm"

    fun sentences(s: UsageSummary, label: (String) -> String, zone: TimeZone = TimeZone.getDefault()): List<String> = buildList {
        if (s.unlocks > 0) add("You unlocked your phone about ${(s.unlocks / s.days.toDouble()).let { Math.round(it).toInt() }} times a day.")
        busiestHour(s)?.let { add("Your busiest hour is ${hourName(it)} to ${hourName((it + 1) % 24)}.") }
        s.opens.maxByOrNull { it.value }?.let { add("You opened ${label(it.key)} ${it.value} times this week.") }
        lateNightMinutes(s).takeIf { it >= 10 }?.let { add("You were on your phone for $it minutes between midnight and 5am.") }
        typicalSleep(s, zone)?.let { (bed, wake) -> add("It guesses you go to bed around ${clock(bed)} and wake around ${clock(wake)}, just from when the screen is off.") }
    }
}
