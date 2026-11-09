package app.ultimate.health

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord

/**
 * What to show when today's number isn't there. Watches often sync resting heart rate and sleep a day late, and some
 * metrics (HRV especially) are never shared with Health Connect, so a dash with no reason looks like a bug. This picks the
 * newest value from the last few days and, if there is none, says whether the cause is a permission or simply no data.
 * Pure, so it is tested.
 */
object HealthFill {
    /** A value and how many days old it is (0 is today). */
    data class Latest<T>(val value: T, val daysAgo: Int)

    fun <T> latest(days: List<HealthDay>, today: Long, maxBack: Int = 7, pick: (HealthDay) -> T?): Latest<T>? =
        days.filter { it.day in (today - maxBack)..today }.sortedByDescending { it.day }.firstNotNullOfOrNull { d -> pick(d)?.let { Latest(it, (today - d.day).toInt()) } }

    fun ago(daysAgo: Int) = when (daysAgo) { 0 -> "today"; 1 -> "yesterday"; else -> "$daysAgo days ago" }

    enum class Metric(val label: String, val permission: String) {
        Steps("steps", HealthPermission.getReadPermission(StepsRecord::class)),
        Sleep("sleep", HealthPermission.getReadPermission(SleepSessionRecord::class)),
        RestingHr("resting heart rate", HealthPermission.getReadPermission(RestingHeartRateRecord::class)),
        Hrv("heart rate variability", HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class)),
    }

    /** The one-line reason a metric is empty. [allowed] is what Health Connect has granted; null means Health Connect itself is unavailable. */
    fun whyEmpty(m: Metric, allowed: Set<String>?, maxBack: Int = 7): String = when {
        allowed == null -> "Health Connect isn't available on this phone"
        m.permission !in allowed -> "Not allowed yet: tap to allow ${m.label}"
        m == Metric.Hrv -> "Nothing in Health Connect for $maxBack days. Samsung Health often doesn't share HRV"
        else -> "No ${m.label} in Health Connect for $maxBack days. Check that your watch is syncing to Samsung Health"
    }
}
