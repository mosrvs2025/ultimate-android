package app.ultimate.health

import android.content.Context

/**
 * A gentle read on how you're doing today, from signals you already have: last night's sleep, your heart rate
 * variability and resting heart rate against your own recent normal, late-night phone use, how many notifications
 * came in, and the mood you logged. It is an estimate to notice patterns with, not a medical measure, and every
 * factor that went into it is listed. Pure, so it is tested.
 */
object MindCheck {
    data class Factor(val text: String, val good: Boolean)
    /** [load] 0 (easy day) .. 100 (heavy day), and the reasons. */
    data class Read(val load: Int, val factors: List<Factor>) {
        val words get() = when { load < 30 -> "Steady"; load < 55 -> "A bit stretched"; load < 75 -> "Under strain"; else -> "Running on empty" }
    }

    fun read(
        sleepMin: Long?, hrvToday: Double?, hrvNormal: Double?, restingToday: Long?, restingNormal: Double?,
        lateMin: Long?, notificationsToday: Int?, notificationsNormal: Double?, mood: Int?,
    ): Read? {
        var load = 30.0; var n = 0
        val f = ArrayList<Factor>()
        sleepMin?.let { s -> n++
            when { s < 360 -> { load += 18; f += Factor("Short sleep (${HealthInsights.hm(s.toInt())})", false) }
                s >= 450 -> { load -= 8; f += Factor("A full night's sleep (${HealthInsights.hm(s.toInt())})", true) } } }
        if (hrvToday != null && hrvNormal != null && hrvNormal > 0) { n++
            val r = hrvToday / hrvNormal
            if (r < 0.85) { load += 15; f += Factor("Heart rate variability below your normal (%.0f vs %.0f ms), often a sign of stress or tiredness".format(hrvToday, hrvNormal), false) }
            else if (r > 1.1) { load -= 8; f += Factor("Heart rate variability above your normal: well recovered", true) } }
        if (restingToday != null && restingNormal != null) { n++
            val d = restingToday - restingNormal
            if (d >= 5) { load += 10; f += Factor("Resting heart rate ${d.toInt()} above your normal", false) }
            else if (d <= -3) { load -= 4; f += Factor("Resting heart rate at or below your normal", true) } }
        lateMin?.let { l -> n++
            if (l >= 45) { load += 10; f += Factor("${HealthInsights.hm(l.toInt())} on the phone after midnight", false) }
            else if (l < 5) f += Factor("No late-night phone time", true) }
        if (notificationsToday != null && notificationsNormal != null && notificationsNormal > 0) { n++
            if (notificationsToday > notificationsNormal * 1.5 && notificationsToday >= 30) { load += 8; f += Factor("A busy day for notifications ($notificationsToday, normally about ${notificationsNormal.toInt()})", false) } }
        mood?.let { m -> n++
            load += when (m) { 0 -> 20.0; 1 -> 8.0; 2 -> -4.0; else -> -10.0 }
            f += Factor("You said you feel ${MOODS[m.coerceIn(0, 3)].second.lowercase()}", m >= 2) }
        if (n == 0) return null
        return Read(load.toInt().coerceIn(0, 100), f)
    }

    val MOODS = listOf("😞" to "Low", "😐" to "Meh", "🙂" to "Good", "😄" to "Great")
}

/** One mood a day, kept on the phone, so patterns between how you feel and everything else can be found. */
object MoodLog {
    private fun prefs(c: Context) = c.getSharedPreferences("mood_log", Context.MODE_PRIVATE)
    fun set(c: Context, day: Long, mood: Int) = prefs(c).edit().putInt("d$day", mood).apply()
    fun get(c: Context, day: Long): Int? = prefs(c).getInt("d$day", -1).takeIf { it >= 0 }
    fun all(c: Context): Map<Long, Int> = prefs(c).all.mapNotNull { (k, v) -> k.removePrefix("d").toLongOrNull()?.let { d -> (v as? Int)?.let { d to it } } }.toMap()
}
