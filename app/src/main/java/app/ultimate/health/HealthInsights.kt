package app.ultimate.health

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One day of phone use, to set beside health: total screen time and the part between midnight and 5 am. */
data class PhoneDay(val day: Long, val screenMin: Long, val lateMin: Long)

/** A pattern across days, in words, with how strong it is and how many days it rests on. */
data class Insight(val title: String, val text: String, val strength: Double, val days: Int)

/**
 * Patterns between health and phone use, found day by day. Each needs at least [MIN_DAYS] days with both sides recorded
 * and a correlation of at least [MIN_R]; weaker ones aren't shown. A pattern isn't a cause, and each says so.
 */
object HealthInsights {
    const val MIN_DAYS = 10
    const val MIN_R = 0.4

    /** Pearson correlation of paired values; null with too few pairs or no variation. */
    fun correlation(pairs: List<Pair<Double, Double>>): Double? {
        if (pairs.size < 3) return null
        val mx = pairs.map { it.first }.average(); val my = pairs.map { it.second }.average()
        var sxy = 0.0; var sx = 0.0; var sy = 0.0
        pairs.forEach { (x, y) -> sxy += (x - mx) * (y - my); sx += (x - mx) * (x - mx); sy += (y - my) * (y - my) }
        if (sx == 0.0 || sy == 0.0) return null
        return sxy / sqrt(sx * sy)
    }

    /** The difference in [y] between the days in the top and bottom third of [x]: an effect size people can read. */
    private fun split(pairs: List<Pair<Double, Double>>): Pair<Double, Double> {
        val s = pairs.sortedBy { it.first }; val third = maxOf(1, s.size / 3)
        return s.take(third).map { it.second }.average() to s.takeLast(third).map { it.second }.average()
    }

    fun find(health: List<HealthDay>, phone: List<PhoneDay>): List<Insight> {
        val h = health.associateBy { it.day }; val p = phone.associateBy { it.day }
        val out = ArrayList<Insight>()
        fun consider(title: String, pairs: List<Pair<Double, Double>>, words: (r: Double, low: Double, high: Double) -> String) {
            if (pairs.size < MIN_DAYS) return
            val r = correlation(pairs) ?: return
            if (abs(r) < MIN_R) return
            val (low, high) = split(pairs)
            out += Insight(title, words(r, low, high) + " (a pattern over ${pairs.size} days, not proof of cause)", abs(r), pairs.size)
        }
        // Late-night phone use against that night's sleep: the night before the day you wake up.
        consider("Late-night phone and sleep", p.values.mapNotNull { pd -> h[pd.day + 1]?.sleepMin?.let { pd.lateMin.toDouble() to it.toDouble() } }) { r, low, high ->
            val diff = (low - high).roundToInt()
            if (r < 0) "On nights with the most phone use after midnight you slept about ${hm(abs(diff))} less than on the nights with the least"
            else "Nights with more phone use after midnight came with about ${hm(abs(diff))} more sleep"
        }
        consider("Screen time and steps", p.values.mapNotNull { pd -> h[pd.day]?.steps?.let { pd.screenMin.toDouble() to it.toDouble() } }) { r, low, high ->
            val diff = abs(low - high).roundToInt()
            if (r < 0) "Your busiest phone days had about ${"%,d".format(diff)} fewer steps than your lightest ones" else "Your busiest phone days also had about ${"%,d".format(diff)} more steps"
        }
        consider("Sleep and the next day's activity", h.values.mapNotNull { d -> d.sleepMin?.let { s -> h[d.day]?.steps?.let { s.toDouble() to it.toDouble() } } }) { r, low, high ->
            val diff = abs(low - high).roundToInt()
            if (r > 0) "After your best-rested nights you walked about ${"%,d".format(diff)} more steps" else "After your longest nights you walked about ${"%,d".format(diff)} fewer steps"
        }
        consider("Sleep and resting heart rate", h.values.mapNotNull { d -> d.sleepMin?.let { s -> d.restingHr?.let { s.toDouble() to it.toDouble() } } }) { r, low, high ->
            val diff = abs(low - high).roundToInt()
            if (r < 0) "Your resting heart rate was about $diff bpm lower after longer nights" else "Your resting heart rate was about $diff bpm higher after longer nights"
        }
        consider("Screen time and heart rate variability", p.values.mapNotNull { pd -> h[pd.day]?.hrvMs?.let { pd.screenMin.toDouble() to it } }) { r, low, high ->
            val diff = abs(low - high).roundToInt()
            if (r < 0) "Heart rate variability (a sign of recovery) was about $diff ms lower on your heaviest phone days" else "Heart rate variability was about $diff ms higher on your heaviest phone days"
        }
        return out.sortedByDescending { it.strength }
    }

    /** Plain trends: this week against the week before, for what changed noticeably. */
    fun trends(health: List<HealthDay>, today: Long): List<String> {
        fun avg(sel: (HealthDay) -> Double?, from: Long, to: Long) = health.filter { it.day in from..to }.mapNotNull(sel).takeIf { it.size >= 3 }?.average()
        val out = ArrayList<String>()
        fun compare(name: String, sel: (HealthDay) -> Double?, fmt: (Double) -> String, minChange: Double) {
            val now = avg(sel, today - 6, today) ?: return; val before = avg(sel, today - 13, today - 7) ?: return
            if (abs(now - before) < minChange) return
            out += "$name: ${fmt(now)} a day this week, ${if (now > before) "up" else "down"} from ${fmt(before)} the week before"
        }
        compare("Steps", { it.steps?.toDouble() }, { "%,d".format(it.roundToInt()) }, 1000.0)
        compare("Sleep", { it.sleepMin?.toDouble() }, { hm(it.roundToInt()) }, 25.0)
        compare("Resting heart rate", { it.restingHr?.toDouble() }, { "${it.roundToInt()} bpm" }, 3.0)
        return out
    }

    fun hm(min: Int): String = if (min < 60) "${min}m" else "${min / 60}h ${min % 60}m"
}
