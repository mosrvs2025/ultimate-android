package app.ultimate.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.ultimate.health.ActivityMath.Goals
import app.ultimate.health.ActivityMath.Past
import app.ultimate.health.ActivityMath.Raw
import app.ultimate.health.ActivityMath.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * What the Activity screen keeps on the phone: your three goals, and for each finished day its totals, its steps by the hour
 * and its active minutes. Finished days don't change, so keeping them means opening the screen reads one day, not two months.
 * Today and yesterday are never kept (the watch may still be passing in steps), so they are read fresh every time.
 */
class ActivityStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("activity", Context.MODE_PRIVATE)

    fun goals(): Goals = Goals(prefs.getInt("g.steps", ActivityMath.DEFAULT_STEPS), prefs.getInt("g.active", ActivityMath.DEFAULT_ACTIVE_MIN), prefs.getInt("g.kcal", ActivityMath.DEFAULT_KCAL)).sane()
    fun setGoals(g: Goals) { val s = g.sane(); prefs.edit().putInt("g.steps", s.steps).putInt("g.active", s.activeMin).putInt("g.kcal", s.kcal).apply() }

    fun past(): Map<Long, Past> = runCatching {
        val o = JSONObject(prefs.getString("days", null) ?: return emptyMap())
        o.keys().asSequence().mapNotNull { k -> runCatching { k.toLong() to decode(k.toLong(), o.getJSONObject(k)) }.getOrNull() }.toMap()
    }.getOrDefault(emptyMap())

    /** Keeps [days] (finished days only), forgetting anything older than 70 days. */
    fun put(days: Collection<Past>, today: Long) {
        val all = past().toMutableMap()
        days.forEach { if (it.day < today - 1) all[it.day] = it }
        val o = JSONObject()
        all.filterKeys { it >= today - 70 }.forEach { (d, p) -> o.put(d.toString(), encode(p)) }
        prefs.edit().putString("days", o.toString()).apply()
    }

    private fun encode(p: Past) = JSONObject().apply {
        p.steps?.let { put("s", it) }; p.metres?.let { put("m", it) }; p.activeKcal?.let { put("k", it) }; p.activeMin?.let { put("a", it) }
        p.hourSteps?.let { put("h", JSONArray(it.toList())) }
    }

    private fun decode(day: Long, o: JSONObject) = Past(
        day, if (o.has("s")) o.getLong("s") else null, if (o.has("m")) o.getDouble("m") else null, if (o.has("k")) o.getDouble("k") else null,
        if (o.has("a")) o.getInt("a") else null, o.optJSONArray("h")?.takeIf { it.length() == 24 }?.let { a -> IntArray(24) { a.getInt(it) } },
    )
}

/** Everything a screen needs to draw your activity, or the reason it can't. */
class ActivityLoaded(
    val status: Status, val day: ActivityMath.Day?, val week: ActivityMath.Week?, val goals: Goals, val miles: Boolean,
    /** Whether workouts and total calories are allowed, so the screen can offer them. */
    val canWorkouts: Boolean, val canTotal: Boolean, val readAt: Long,
) {
    enum class Status { Unavailable, NeedsPermission, Ready }
}

/** Reads Health Connect for the Activity screen. Read-only, on the phone. Each part fails alone, so a missing permission hides only its own number. */
object ActivityRepo {
    private val stepsPermission = HealthPermission.getReadPermission(StepsRecord::class)
    private val workoutsPermission = HealthPermission.getReadPermission(ExerciseSessionRecord::class)
    private val totalPermission = HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class)

    /** What to ask for to get everything: steps, distance, active and total calories, floors, and workouts. */
    val permissions: Set<String> get() = HealthKind.Activity.permissions + HealthKind.Energy.permissions + HealthKind.Workouts.permissions

    @Volatile private var last: ActivityLoaded? = null
    @Volatile private var historyAt = 0L
    /** The most recent result, for drawing something at once while a fresh read runs. */
    fun latest(): ActivityLoaded? = last

    /**
     * [history] false is the quick read: today only (what Home needs). true also fills in the usual day and the week from the
     * last eight weeks, keeping finished days on the phone so it is slow only once. The weeks behind today are re-read at most every
     * half hour unless [force] says otherwise (the Refresh button).
     */
    suspend fun load(c: Context, history: Boolean, force: Boolean = false): ActivityLoaded = withContext(Dispatchers.IO) {
        val store = ActivityStore(c)
        val goals = store.goals()
        val miles = ActivityMath.milesFor(Locale.getDefault().country)
        val now = System.currentTimeMillis()
        if (!HealthReader.available(c)) return@withContext ActivityLoaded(ActivityLoaded.Status.Unavailable, null, null, goals, miles, false, false, now).also { last = it }
        val allowed = HealthReader.allowed(c)
        if (stepsPermission !in allowed) return@withContext ActivityLoaded(ActivityLoaded.Status.NeedsPermission, null, null, goals, miles, workoutsPermission in allowed, totalPermission in allowed, now).also { last = it }

        val client = HealthConnectClient.getOrCreate(c)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val nowInstant = Instant.ofEpochMilli(now)
        val nowMin = nowInstant.atZone(zone).let { it.hour * 60 + it.minute }

        val known = store.past().toMutableMap()
        val fresh = HashMap<Long, Past>()
        if (history && (force || known.isEmpty() || now - historyAt > 30 * 60_000L)) { readHistory(c, client, zone, today, nowInstant, allowed, known, fresh); historyAt = now }
        val past = (known + fresh).values.toList()
        store.put(fresh.values, today.toEpochDay())

        val raw = readDay(client, zone, today, nowInstant, allowed)
        val built = ActivityMath.build(raw, past, goals, nowMin, live = true)
        val week = ActivityMath.week(past, built, goals, today.toEpochDay())
        ActivityLoaded(ActivityLoaded.Status.Ready, built, week, goals, miles, workoutsPermission in allowed, totalPermission in allowed, now).also { last = it }
    }

    /** One whole day: the totals, steps by the minute, the day's workouts, and when and from where the newest steps came. */
    private suspend fun readDay(client: HealthConnectClient, zone: ZoneId, day: LocalDate, now: Instant, allowed: Set<String>): Raw {
        val start = day.atStartOfDay(zone).toInstant()
        val end = minOf(day.plusDays(1).atStartOfDay(zone).toInstant(), now)
        val range = TimeRangeFilter.between(start, end)
        var steps: Long? = null; var metres: Double? = null; var active: Double? = null; var floors: Double? = null
        var sources: List<String> = emptyList()
        runCatching {
            // Health Connect's own total merges what the phone and the watch both counted, so nothing is counted twice.
            val metrics = buildSet {
                add(StepsRecord.COUNT_TOTAL)
                if (HealthPermission.getReadPermission(DistanceRecord::class) in allowed) add(DistanceRecord.DISTANCE_TOTAL)
                if (HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class) in allowed) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
                if (HealthPermission.getReadPermission(FloorsClimbedRecord::class) in allowed) add(FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL)
            }
            val r = client.aggregate(AggregateRequest(metrics, range))
            steps = r[StepsRecord.COUNT_TOTAL]; metres = r[DistanceRecord.DISTANCE_TOTAL]?.inMeters
            active = r[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories; floors = r[FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL]
            sources = r.dataOrigins.map { it.packageName }
        }
        val total = if (totalPermission in allowed) runCatching { client.aggregate(AggregateRequest(setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL), range))[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories }.getOrNull() else null
        val minutes = minuteSteps(client, zone, day, end)
        val sessions = if (workoutsPermission in allowed) sessions(client, start, end) else emptyList()
        val updated = runCatching {
            client.readRecords(ReadRecordsRequest(StepsRecord::class, range, ascendingOrder = false, pageSize = 1)).records.firstOrNull()?.endTime?.toEpochMilli()
        }.getOrNull()
        return Raw(day.toEpochDay(), steps, metres, active, total, floors, minutes, minutes?.let { ActivityMath.hours(it) }, sessions, updated, sources)
    }

    /** Steps in each minute of [day] up to [until], with Health Connect merging sources; null if it can't be read at all. */
    private suspend fun minuteSteps(client: HealthConnectClient, zone: ZoneId, day: LocalDate, until: Instant): IntArray? = runCatching {
        val out = IntArray(ActivityMath.MINUTES)
        val start = day.atStartOfDay(zone).toInstant()
        var from = start
        while (from < until) {
            val to = minOf(from.plus(Duration.ofHours(6)), until)
            client.aggregateGroupByDuration(AggregateGroupByDurationRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(from, to), Duration.ofMinutes(1))).forEach { b ->
                val n = b.result[StepsRecord.COUNT_TOTAL] ?: return@forEach
                val z = b.startTime.atZone(zone)
                val m = z.hour * 60 + z.minute
                if (m in 0 until ActivityMath.MINUTES) out[m] += n.toInt()
            }
            from = to
        }
        out
    }.getOrNull()

    private suspend fun sessions(client: HealthConnectClient, start: Instant, end: Instant): List<Session> = runCatching {
        client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, TimeRangeFilter.between(start, end), pageSize = 200)).records.map { s ->
            Session(
                Duration.between(start, s.startTime).toMinutes().toInt().coerceIn(0, ActivityMath.MINUTES),
                Duration.between(start, s.endTime).toMinutes().toInt().coerceIn(0, ActivityMath.MINUTES),
                s.title?.takeIf { it.isNotBlank() } ?: typeName(s.exerciseType),
            )
        }
    }.getOrDefault(emptyList())

    private fun typeName(type: Int): String? = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Walking"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "Running"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "Hiking"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "Cycling"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "Swimming"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "Yoga"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "Strength training"
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "Elliptical"
        else -> null
    }

    /**
     * Fills in finished days of the last eight weeks that aren't kept yet: their totals, steps by the hour (for the usual day) and
     * active minutes. Yesterday is read every time because the watch may still be catching up on it.
     */
    private suspend fun readHistory(
        c: Context, client: HealthConnectClient, zone: ZoneId, today: LocalDate, now: Instant, allowed: Set<String>,
        known: MutableMap<Long, Past>, fresh: MutableMap<Long, Past>,
    ) {
        val t = today.toEpochDay()
        val first = t - 56
        // Daily totals come from the same call the Health screen uses.
        val totals = runCatching { HealthReader.days(c, setOf(HealthKind.Activity), 57) }.getOrDefault(emptyList()).associateBy { it.day }
        val missing = (first until t).filter { d -> d >= t - 1 || known[d] == null }
        if (missing.isNotEmpty()) {
            val hours = hourlySteps(client, zone, LocalDate.ofEpochDay(missing.first()), LocalDate.ofEpochDay(missing.last()), now)
            for (d in missing) {
                val day = totals[d]
                val h = hours[d]
                // Active minutes need steps by the minute: only the last week is worth that, older days keep the totals.
                val activeMin = if (d >= t - 7) {
                    val date = LocalDate.ofEpochDay(d)
                    val s = date.atStartOfDay(zone).toInstant()
                    val sess = if (workoutsPermission in allowed) sessions(client, s, date.plusDays(1).atStartOfDay(zone).toInstant()) else emptyList()
                    ActivityMath.activeMinutes(minuteSteps(client, zone, date, date.plusDays(1).atStartOfDay(zone).toInstant()), sess)
                } else known[d]?.activeMin
                // A day with nothing recorded is kept as an empty day, so it is not read again every time.
                val p = Past(d, day?.steps ?: h?.sum()?.toLong(), day?.distanceM, day?.activeKcal, activeMin, h ?: IntArray(24))
                if (d < t - 1) fresh[d] = p else known[d] = p
            }
        }
    }

    /** Steps in each hour for every day from [from] to [to], a week of days at a time. */
    private suspend fun hourlySteps(client: HealthConnectClient, zone: ZoneId, from: LocalDate, to: LocalDate, now: Instant): Map<Long, IntArray> {
        val out = HashMap<Long, IntArray>()
        var d = from
        while (!d.isAfter(to)) {
            val chunkEnd = minOf(d.plusDays(7), to.plusDays(1))
            val start = d.atStartOfDay(zone).toInstant(); val end = minOf(chunkEnd.atStartOfDay(zone).toInstant(), now)
            if (start < end) runCatching {
                client.aggregateGroupByDuration(AggregateGroupByDurationRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(start, end), Duration.ofHours(1))).forEach { b ->
                    val n = b.result[StepsRecord.COUNT_TOTAL] ?: return@forEach
                    val z = b.startTime.atZone(zone)
                    out.getOrPut(z.toLocalDate().toEpochDay()) { IntArray(24) }[z.hour] += n.toInt()
                }
            }
            d = chunkEnd
        }
        return out
    }
}
