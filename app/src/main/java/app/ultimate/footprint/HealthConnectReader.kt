package app.ultimate.footprint

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.ZoneId

/** Reads what other apps wrote into Health Connect. Read-only, last 30 days, and nothing leaves the phone. */
internal object HealthConnectReader {
    val permissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
    )

    fun status(c: Context): Int = HealthConnectClient.getSdkStatus(c)
    fun available(c: Context) = status(c) == HealthConnectClient.SDK_AVAILABLE

    suspend fun granted(c: Context): Boolean = runCatching { HealthConnectClient.getOrCreate(c).permissionController.getGrantedPermissions().containsAll(permissions) }.getOrDefault(false)

    suspend fun days(c: Context, count: Int = 30): List<HealthDay> {
        val client = HealthConnectClient.getOrCreate(c); val zone = ZoneId.systemDefault()
        val from = Instant.now().atZone(zone).toLocalDate().minusDays(count - 1L).atStartOfDay(zone).toInstant()
        val range = TimeRangeFilter.between(from, Instant.now())
        fun day(i: Instant) = i.atZone(zone).toLocalDate().toEpochDay()
        val steps = HashMap<Long, Long>(); val sleep = HashMap<Long, Long>(); val bpm = HashMap<Long, MutableList<Long>>()
        runCatching { client.readRecords(ReadRecordsRequest(StepsRecord::class, range, pageSize = 5000)).records.forEach { steps.merge(day(it.startTime), it.count, Long::plus) } }
        runCatching { client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, range, pageSize = 1000)).records.forEach { sleep.merge(day(it.endTime), it.endTime.toEpochMilli() - it.startTime.toEpochMilli(), Long::plus) } }
        runCatching { client.readRecords(ReadRecordsRequest(HeartRateRecord::class, range, pageSize = 5000)).records.forEach { r -> r.samples.forEach { bpm.getOrPut(day(it.time)) { ArrayList() }.add(it.beatsPerMinute) } } }
        val keys = (steps.keys + sleep.keys + bpm.keys).sorted()
        return keys.map { d -> val b = bpm[d]; HealthDay(d, steps[d] ?: 0, sleep[d] ?: 0, b?.average()?.toInt(), b?.minOrNull()?.toInt(), b?.maxOrNull()?.toInt()) }
    }
}
