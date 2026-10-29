package app.ultimate.screentime

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.Calendar

/** A moment an app came to the front or left it. */
data class UseEvent(val pkg: String, val at: Long, val resumed: Boolean)

/** Pure arithmetic over usage events, so the numbers on screen are tested. */
object UsageMath {
    /**
     * Foreground milliseconds per app between [from] and [to]. An app still open at [to] counts up to [to]; one that was open
     * before [from] (its first event is a pause) counts from [from].
     */
    fun totals(events: List<UseEvent>, from: Long, to: Long): Map<String, Long> {
        val out = HashMap<String, Long>()
        val openSince = HashMap<String, Long>()
        val seen = HashSet<String>()
        for (e in events.sortedBy { it.at }) {
            if (e.at > to) break
            if (e.resumed) { if (e.pkg !in openSince) openSince[e.pkg] = maxOf(e.at, from) }
            else {
                val start = openSince.remove(e.pkg) ?: (if (e.pkg !in seen) from else null)
                if (start != null && e.at > start) out[e.pkg] = (out[e.pkg] ?: 0) + (e.at - maxOf(start, from))
            }
            seen += e.pkg
        }
        openSince.forEach { (pkg, start) -> if (to > start) out[pkg] = (out[pkg] ?: 0) + (to - start) }
        return out.filterValues { it > 0 }
    }

    fun startOfDay(at: Long): Long = Calendar.getInstance().apply {
        timeInMillis = at; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun hm(ms: Long): String { val m = ms / 60_000; return if (m < 60) "${m}m" else "${m / 60}h ${m % 60}m" }
}

/** Reads the phone's own usage history (needs usage access) and keeps the user's daily limits. */
class ScreenTime(private val context: Context) {
    private val prefs = context.getSharedPreferences("screentime", Context.MODE_PRIVATE)

    fun events(from: Long, to: Long): List<UseEvent> {
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val list = ArrayList<UseEvent>()
        val q = usm.queryEvents(from, to)
        val e = UsageEvents.Event()
        while (q.hasNextEvent()) {
            q.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> list += UseEvent(e.packageName, e.timeStamp, true)
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> list += UseEvent(e.packageName, e.timeStamp, false)
            }
        }
        return list
    }

    /** Per-app totals for each of the last [days] days, oldest first; today runs to now. */
    fun days(days: Int = 7, now: Long = System.currentTimeMillis()): List<Pair<Long, Map<String, Long>>> {
        val today = UsageMath.startOfDay(now)
        val from = today - (days - 1) * 86_400_000L
        val events = events(from - 3_600_000L, now)
        return (0 until days).map { d ->
            val start = from + d * 86_400_000L
            start to UsageMath.totals(events, start, minOf(start + 86_400_000L, now))
        }
    }

    fun limit(pkg: String): Long = prefs.getLong("limit.$pkg", 0)
    fun setLimit(pkg: String, ms: Long) = prefs.edit().apply { if (ms > 0) putLong("limit.$pkg", ms) else remove("limit.$pkg") }.apply()
    fun limits(): Map<String, Long> = prefs.all.filterKeys { it.startsWith("limit.") }.mapKeys { it.key.removePrefix("limit.") }.mapValues { (it.value as? Long) ?: 0L }

    /** Whether today's nudge for [pkg] was already shown, so it comes once a day, not every check. */
    fun nudged(pkg: String, day: Long) = prefs.getLong("nudged.$pkg", 0) == day
    fun markNudged(pkg: String, day: Long) = prefs.edit().putLong("nudged.$pkg", day).apply()
}
