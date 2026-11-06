package app.ultimate.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import kotlin.reflect.KClass

/** One day of health, from Health Connect. Null means nothing was recorded that day, not zero. */
data class HealthDay(
    val day: Long,
    val steps: Long? = null, val distanceM: Double? = null, val activeKcal: Double? = null, val floors: Double? = null,
    val sleepMin: Long? = null, val deepMin: Long? = null, val remMin: Long? = null,
    val restingHr: Long? = null, val avgHr: Long? = null, val maxHr: Long? = null, val hrvMs: Double? = null,
    val spo2: Double? = null, val breathsPerMin: Double? = null,
    val weightKg: Double? = null, val bodyFatPct: Double? = null,
    val workouts: Int = 0, val workoutMin: Long = 0,
)

/** The kinds of health data, each asked for on its own, so you choose exactly what Ultimate reads. */
enum class HealthKind(val title: String, val what: String, val records: List<KClass<out androidx.health.connect.client.records.Record>>) {
    Activity("Activity", "Steps, distance, active calories and floors", listOf(StepsRecord::class, DistanceRecord::class, ActiveCaloriesBurnedRecord::class, FloorsClimbedRecord::class)),
    Heart("Heart", "Heart rate, resting heart rate and heart rate variability", listOf(HeartRateRecord::class, RestingHeartRateRecord::class, HeartRateVariabilityRmssdRecord::class)),
    Sleep("Sleep", "Sleep sessions and their stages", listOf(SleepSessionRecord::class)),
    Workouts("Workouts", "Exercise sessions", listOf(ExerciseSessionRecord::class)),
    Vitals("Vitals", "Blood oxygen and breathing rate", listOf(OxygenSaturationRecord::class, RespiratoryRateRecord::class)),
    Body("Body", "Weight and body fat", listOf(WeightRecord::class, BodyFatRecord::class));

    val permissions: Set<String> get() = records.map { HealthPermission.getReadPermission(it) }.toSet()
}

/** Reads Health Connect. Read-only, on the phone, only the kinds you allow. */
object HealthReader {
    fun available(c: Context) = HealthConnectClient.getSdkStatus(c) == HealthConnectClient.SDK_AVAILABLE

    suspend fun granted(c: Context): Set<HealthKind> = runCatching {
        val have = HealthConnectClient.getOrCreate(c).permissionController.getGrantedPermissions()
        HealthKind.entries.filter { have.containsAll(it.permissions) }.toSet()
    }.getOrDefault(emptySet())

    /** The last [count] days, oldest first, for the kinds in [kinds]. Each part fails on its own, so one missing type doesn't hide the rest. */
    suspend fun days(c: Context, kinds: Set<HealthKind>, count: Int = 60): List<HealthDay> {
        val client = HealthConnectClient.getOrCreate(c); val zone = ZoneId.systemDefault()
        val start = LocalDate.now(zone).minusDays(count - 1L)
        val from = start.atStartOfDay(zone).toInstant(); val now = Instant.now()
        val range = TimeRangeFilter.between(from, now)
        fun dayOf(i: Instant) = i.atZone(zone).toLocalDate().toEpochDay()
        val days = HashMap<Long, HealthDay>()
        fun put(d: Long, f: (HealthDay) -> HealthDay) { days[d] = f(days[d] ?: HealthDay(d)) }

        if (HealthKind.Activity in kinds) runCatching {
            // Health Connect's own daily totals merge overlapping sources (phone and watch both counting steps) properly.
            val r = client.aggregateGroupByPeriod(AggregateGroupByPeriodRequest(
                setOf(StepsRecord.COUNT_TOTAL, DistanceRecord.DISTANCE_TOTAL, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL, FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL),
                TimeRangeFilter.between(start.atStartOfDay(), now.atZone(zone).toLocalDateTime()), Period.ofDays(1)))
            r.forEach { p ->
                val d = p.startTime.toLocalDate().toEpochDay()
                put(d) { it.copy(steps = p.result[StepsRecord.COUNT_TOTAL], distanceM = p.result[DistanceRecord.DISTANCE_TOTAL]?.inMeters,
                    activeKcal = p.result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories, floors = p.result[FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL]) }
            }
        }
        if (HealthKind.Heart in kinds) {
            runCatching {
                val byDay = HashMap<Long, MutableList<Long>>()
                client.readRecords(ReadRecordsRequest(HeartRateRecord::class, range, pageSize = 5000)).records.forEach { r -> r.samples.forEach { byDay.getOrPut(dayOf(it.time)) { ArrayList() }.add(it.beatsPerMinute) } }
                byDay.forEach { (d, v) -> put(d) { it.copy(avgHr = v.average().toLong(), maxHr = v.max()) } }
            }
            runCatching { client.readRecords(ReadRecordsRequest(RestingHeartRateRecord::class, range, pageSize = 1000)).records.groupBy { dayOf(it.time) }.forEach { (d, v) -> put(d) { it.copy(restingHr = v.minOf { r -> r.beatsPerMinute }) } } }
            runCatching { client.readRecords(ReadRecordsRequest(HeartRateVariabilityRmssdRecord::class, range, pageSize = 2000)).records.groupBy { dayOf(it.time) }.forEach { (d, v) -> put(d) { it.copy(hrvMs = v.map { r -> r.heartRateVariabilityMillis }.average()) } } }
        }
        if (HealthKind.Sleep in kinds) runCatching {
            client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, range, pageSize = 500)).records.forEach { s ->
                // A night counts for the day you wake up.
                val d = dayOf(s.endTime)
                fun min(a: Instant, b: Instant) = (b.toEpochMilli() - a.toEpochMilli()) / 60_000
                val total = min(s.startTime, s.endTime)
                val deep = s.stages.filter { it.stage == SleepSessionRecord.STAGE_TYPE_DEEP }.sumOf { min(it.startTime, it.endTime) }
                val rem = s.stages.filter { it.stage == SleepSessionRecord.STAGE_TYPE_REM }.sumOf { min(it.startTime, it.endTime) }
                put(d) { it.copy(sleepMin = (it.sleepMin ?: 0) + total, deepMin = (it.deepMin ?: 0) + deep, remMin = (it.remMin ?: 0) + rem) }
            }
        }
        if (HealthKind.Workouts in kinds) runCatching {
            client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, range, pageSize = 500)).records.forEach { w ->
                put(dayOf(w.startTime)) { it.copy(workouts = it.workouts + 1, workoutMin = it.workoutMin + (w.endTime.toEpochMilli() - w.startTime.toEpochMilli()) / 60_000) }
            }
        }
        if (HealthKind.Vitals in kinds) {
            runCatching { client.readRecords(ReadRecordsRequest(OxygenSaturationRecord::class, range, pageSize = 2000)).records.groupBy { dayOf(it.time) }.forEach { (d, v) -> put(d) { it.copy(spo2 = v.map { r -> r.percentage.value }.average()) } } }
            runCatching { client.readRecords(ReadRecordsRequest(RespiratoryRateRecord::class, range, pageSize = 2000)).records.groupBy { dayOf(it.time) }.forEach { (d, v) -> put(d) { it.copy(breathsPerMin = v.map { r -> r.rate }.average()) } } }
        }
        if (HealthKind.Body in kinds) {
            runCatching { client.readRecords(ReadRecordsRequest(WeightRecord::class, range, pageSize = 500)).records.groupBy { dayOf(it.time) }.forEach { (d, v) -> put(d) { it.copy(weightKg = v.last().weight.inKilograms) } } }
            runCatching { client.readRecords(ReadRecordsRequest(BodyFatRecord::class, range, pageSize = 500)).records.groupBy { dayOf(it.time) }.forEach { (d, v) -> put(d) { it.copy(bodyFatPct = v.last().percentage.value) } } }
        }
        return days.values.sortedBy { it.day }
    }
}
