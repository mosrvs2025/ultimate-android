package app.ultimate.health

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The deeper reads behind the Health screen: this week against last, goal streaks, sleep stages and a few plain-language
 * things worth doing. Everything is computed from the days Health Connect gave us; nothing is a medical measure and nothing
 * is sent anywhere. Pure, so it is tested.
 */
object HealthDepth {
    enum class Metric(val label: String, val higherIsBetter: Boolean?, val minWeeklyChange: Double) {
        Steps("Steps", true, 0.05), Sleep("Sleep", true, 0.04), RestingHr("Resting heart rate", false, 0.03), Hrv("HRV", true, 0.08), Active("Active calories", true, 0.08),
    }

    /** One metric this week against last: [now] and [before] are daily averages, [pct] the change (0.1 is 10% up). [good] is null when more or less isn't clearly better. */
    data class Delta(val metric: Metric, val now: Double, val before: Double, val pct: Double, val good: Boolean?, val daily: List<Double?>) {
        val up get() = now > before
        val flat get() = abs(pct) < metric.minWeeklyChange
    }

    private fun pick(d: HealthDay, m: Metric): Double? = when (m) {
        Metric.Steps -> d.steps?.toDouble(); Metric.Sleep -> d.sleepMin?.toDouble(); Metric.RestingHr -> d.restingHr?.toDouble()
        Metric.Hrv -> d.hrvMs; Metric.Active -> d.activeKcal
    }

    /** Needs at least three recorded days in each week; a metric without that is left out rather than guessed. [daily] is the last 14 days, null where nothing was recorded. */
    fun weekly(days: List<HealthDay>, today: Long): List<Delta> = Metric.entries.mapNotNull { m ->
        fun avg(from: Long, to: Long) = days.filter { it.day in from..to }.mapNotNull { pick(it, m) }.takeIf { it.size >= 3 }?.average()
        val now = avg(today - 6, today) ?: return@mapNotNull null; val before = avg(today - 13, today - 7) ?: return@mapNotNull null
        val pct = if (before == 0.0) 0.0 else (now - before) / before
        val flat = abs(pct) < m.minWeeklyChange
        val good = if (flat || m.higherIsBetter == null) null else (pct > 0) == m.higherIsBetter
        Delta(m, now, before, pct, good, (today - 13..today).map { d -> days.firstOrNull { it.day == d }?.let { pick(it, m) } })
    }

    /** "+12%", "-4%" or "about the same". */
    fun pctText(d: Delta) = if (d.flat) "about the same" else (if (d.pct > 0) "+" else "-") + "${(abs(d.pct) * 100).roundToInt()}%"

    /** The last [n] days (oldest first) as whether the step [goal] was reached, null where no steps were recorded, plus the current run of days in a row up to today. */
    data class Goal(val days: List<Pair<Long, Boolean?>>, val hit: Int, val streak: Int)

    fun stepGoal(days: List<HealthDay>, today: Long, goal: Long = 8000, n: Int = 7): Goal {
        val list = (today - n + 1..today).map { d -> d to days.firstOrNull { it.day == d }?.steps?.let { it >= goal } }
        var streak = 0
        // Today may not be finished yet: a streak is counted back from yesterday if today hasn't reached the goal.
        val order = list.reversed().let { if (it.firstOrNull()?.second != true) it.drop(1) else it }
        for ((_, hit) in order) if (hit == true) streak++ else break
        return Goal(list, list.count { it.second == true }, streak)
    }

    /** One night split into stages, in minutes; light is what remains of the total. */
    data class Night(val day: Long, val deep: Long, val rem: Long, val light: Long) { val total get() = deep + rem + light }

    fun nights(days: List<HealthDay>, today: Long, n: Int = 7): List<Night> = (today - n + 1..today).mapNotNull { d ->
        days.firstOrNull { it.day == d }?.let { h -> h.sleepMin?.takeIf { it > 0 }?.let { total ->
            val deep = (h.deepMin ?: 0).coerceIn(0, total); val rem = (h.remMin ?: 0).coerceIn(0, total - deep)
            Night(d, deep, rem, total - deep - rem)
        } }
    }

    /** A few plain things worth doing, most useful first. Only said when the numbers clearly support it, and never as medical advice. */
    fun worthDoing(days: List<HealthDay>, today: Long): List<String> = buildList {
        val w = weekly(days, today).associateBy { it.metric }
        val nights = nights(days, today)
        if (nights.size >= 4) {
            val avg = nights.map { it.total }.average()
            if (avg < 6 * 60) add("You've averaged ${HealthInsights.hm(avg.roundToInt())} of sleep over ${nights.size} nights. An earlier night, or a fixed bedtime, is the easiest lever.")
            val short = nights.count { it.total < 6 * 60 }; if (avg >= 6 * 60 && short >= 3) add("$short of your last ${nights.size} nights were under 6 hours, so your average hides some short ones.")
        }
        w[Metric.Steps]?.let { s -> if (!s.flat && s.good == false) add("Steps are down ${(abs(s.pct) * 100).roundToInt()}% on last week (${"%,d".format(s.now.roundToInt())} a day). A 15-minute walk after a meal would close most of that.") }
        w[Metric.RestingHr]?.let { r -> if (r.good == false && r.now - r.before >= 3) add("Resting heart rate is up about ${(r.now - r.before).roundToInt()} bpm on last week. That often follows poor sleep, stress, illness, alcohol or a hard week of training. Worth watching, and worth a doctor if it keeps climbing.") }
        w[Metric.Hrv]?.let { h -> if (h.good == false) add("Heart rate variability is lower than last week, a common sign you're run down. A lighter day or an early night helps.") }
        val g = stepGoal(days, today)
        if (g.days.count { it.second != null } >= 4 && g.hit == 0) add("No day this week reached 8,000 steps. Try a smaller target first: 5,000 on most days.")
        if (g.streak >= 3) add("${g.streak} days in a row at 8,000 steps or more. Keep it going.")
        if (isEmpty() && w.isNotEmpty()) add("Nothing stands out this week. Steady is good.")
    }.take(4)
}
