package app.ultimate.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A quick look at today's step count, for things that only need to know "has the goal been reached": the launcher's living looks.
 * Reads one number (never asks for permission: if Health Connect or the steps permission isn't there, the answer is simply "no"), and keeps
 * it for a few minutes so a home screen that checks every minute reads Health Connect at most every five.
 */
object ActivityGlance {
    private const val FRESH_MS = 5 * 60_000L
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("activity", Context.MODE_PRIVATE)

    /** Whether the kept count is today's and has reached the steps goal. Instant: reads only what was kept. */
    fun goalReached(c: Context, today: Long = LocalDate.now().toEpochDay()): Boolean {
        val p = prefs(c)
        if (p.getLong("glance.day", -1) != today) return false
        return p.getInt("glance.steps", 0) >= ActivityStore(c).goals().steps
    }

    /** Reads today's steps again unless that was done in the last five minutes (or [force]). Returns whether anything was read. */
    suspend fun refresh(c: Context, force: Boolean = false, now: Long = System.currentTimeMillis()): Boolean = withContext(Dispatchers.IO) {
        val p = prefs(c)
        if (!force && now - p.getLong("glance.at", 0) < FRESH_MS) return@withContext false
        runCatching {
            if (!HealthReader.available(c)) return@runCatching false
            val client = HealthConnectClient.getOrCreate(c)
            if (HealthPermission.getReadPermission(StepsRecord::class) !in client.permissionController.getGrantedPermissions()) return@runCatching false
            val zone = ZoneId.systemDefault(); val day = LocalDate.now(zone)
            val steps = client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(day.atStartOfDay(zone).toInstant(), Instant.ofEpochMilli(now))))[StepsRecord.COUNT_TOTAL] ?: 0L
            p.edit().putLong("glance.day", day.toEpochDay()).putInt("glance.steps", steps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).putLong("glance.at", now).apply()
            true
        }.getOrDefault(false)
    }
}
