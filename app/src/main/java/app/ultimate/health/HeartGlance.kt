package app.ultimate.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/** What a small heart card needs: the newest reading, and the newest resting rate. Times are epoch milliseconds. */
data class HeartBrief(val bpm: Int?, val at: Long, val restingBpm: Int?, val restingAt: Long, val lowBpm: Int? = null, val highBpm: Int? = null)

/** Words for a [HeartBrief]. Plain arithmetic, so it is tested without a phone. */
object HeartWords {
    const val FRESH_MS = 6 * 3_600_000L

    /** "just now", "12 min ago", "3 h ago", "yesterday", "3 days ago". */
    fun ago(thenMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val min = ((nowMs - thenMs) / 60_000L).coerceAtLeast(0)
        return when {
            min < 2 -> "just now"
            min < 60 -> "$min min ago"
            min < 24 * 60 -> "${min / 60} h ago"
            else -> {
                val days = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().toEpochDay() - Instant.ofEpochMilli(thenMs).atZone(zone).toLocalDate().toEpochDay()
                if (days <= 1L) "yesterday" else "$days days ago"
            }
        }
    }

    /** The big line: the newest reading while it is fresh, else the resting rate, else whatever reading there is. Null when there is nothing. */
    fun headline(b: HeartBrief, nowMs: Long): String? = when {
        b.bpm != null && nowMs - b.at <= FRESH_MS -> "${b.bpm} bpm"
        b.restingBpm != null -> "${b.restingBpm} resting"
        b.bpm != null -> "${b.bpm} bpm"
        else -> null
    }

    /** The small line under it: how old the reading is, the resting rate if it isn't the headline, and the day's range. */
    fun detail(b: HeartBrief, nowMs: Long): String {
        val freshReading = b.bpm != null && nowMs - b.at <= FRESH_MS
        val parts = ArrayList<String>()
        when {
            freshReading -> parts += ago(b.at, nowMs)
            b.restingBpm != null -> parts += "resting rate from ${ago(b.restingAt, nowMs)}".replace("from just now", "from today")
            b.bpm != null -> parts += "last reading ${ago(b.at, nowMs)}"
        }
        if (freshReading && b.restingBpm != null) parts += "resting ${b.restingBpm}"
        if (b.lowBpm != null && b.highBpm != null && b.lowBpm != b.highBpm) parts += "${b.lowBpm}–${b.highBpm} today"
        return parts.joinToString(" · ")
    }
}

/**
 * Reads the newest heart rate and resting rate. Never asks for permission: with neither allowed the answer is [Result.NeedsPermission], and with
 * Health Connect missing, [Result.Unavailable]. Kept for two minutes so a home screen that asks every minute reads at most every other one.
 */
object HeartGlance {
    sealed interface Result { data class Ready(val brief: HeartBrief) : Result; data object NeedsPermission : Result; data object Unavailable : Result; data object Empty : Result }

    private val heartPermission get() = HealthPermission.getReadPermission(HeartRateRecord::class)
    private val restingPermission get() = HealthPermission.getReadPermission(RestingHeartRateRecord::class)
    @Volatile private var last: Pair<Long, Result>? = null

    suspend fun read(c: Context, force: Boolean = false, now: Long = System.currentTimeMillis()): Result = withContext(Dispatchers.IO) {
        last?.let { (at, r) -> if (!force && now - at < 2 * 60_000L) return@withContext r }
        val r = runCatching {
            if (!HealthReader.available(c)) return@runCatching Result.Unavailable
            val client = HealthConnectClient.getOrCreate(c)
            val allowed = client.permissionController.getGrantedPermissions()
            val canHeart = heartPermission in allowed; val canResting = restingPermission in allowed
            if (!canHeart && !canResting) return@runCatching Result.NeedsPermission
            val nowI = Instant.ofEpochMilli(now)
            val dayStart = nowI.atZone(ZoneId.systemDefault()).toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant()
            val samples = if (canHeart) runCatching {
                client.readRecords(ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.between(nowI.minusSeconds(3 * 86_400L), nowI), pageSize = 5000)).records.flatMap { it.samples }
            }.getOrDefault(emptyList()) else emptyList()
            val newest = samples.maxByOrNull { it.time }
            val today = samples.filter { it.time >= dayStart }.map { it.beatsPerMinute.toInt() }
            val resting = if (canResting) runCatching {
                client.readRecords(ReadRecordsRequest(RestingHeartRateRecord::class, TimeRangeFilter.between(nowI.minusSeconds(4 * 86_400L), nowI), pageSize = 100)).records.maxByOrNull { it.time }
            }.getOrNull() else null
            if (newest == null && resting == null) Result.Empty
            else Result.Ready(HeartBrief(newest?.beatsPerMinute?.toInt(), newest?.time?.toEpochMilli() ?: 0, resting?.beatsPerMinute?.toInt(), resting?.time?.toEpochMilli() ?: 0, today.minOrNull(), today.maxOrNull()))
        }.getOrDefault(Result.Unavailable)
        last = now to r
        r
    }
}
