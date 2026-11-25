package app.ultimate.health

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Your day of movement, worked out from what Health Connect holds (steps by the minute, the hour's distance and calories,
 * exercise sessions). Everything here is plain arithmetic on numbers handed in, so it is tested without a phone.
 *
 * What it adds beyond a total: the three rings against your own goals, the shape of the day beside the shape of your usual
 * day (so "ahead or behind by now" and where you'll probably finish), the bursts of walking the day was made of, and the
 * week at a glance. Nothing is medical, and nothing is guessed when the data isn't there: missing stays missing.
 */
object ActivityMath {
    const val MINUTES = 1_440
    /** A minute counts as "active" from this many steps: a slow stroll is 60 or more a minute, pottering about the house is well under. */
    const val ACTIVE_STEPS_PER_MIN = 40
    /** A minute counts as moving from this many steps, for finding where a walk starts and stops. */
    const val MOVING_STEPS_PER_MIN = 15
    /** A pause this short inside a walk (a crossing, tying a lace) doesn't end it. */
    const val GAP_MIN = 3
    const val MIN_BURST_MIN = 8
    const val MIN_BURST_STEPS = 600
    /** Steps a minute, over the moving minutes, from which a walk is called a run. */
    const val RUN_CADENCE = 140

    const val DEFAULT_STEPS = 10_000
    const val DEFAULT_ACTIVE_MIN = 90
    const val DEFAULT_KCAL = 500

    data class Goals(val steps: Int = DEFAULT_STEPS, val activeMin: Int = DEFAULT_ACTIVE_MIN, val kcal: Int = DEFAULT_KCAL) {
        fun sane() = Goals(steps.coerceIn(1_000, 100_000), activeMin.coerceIn(10, 600), kcal.coerceIn(50, 5_000))
    }

    /** An exercise session, in minutes of the day (end exclusive), with the phone's name for it when it has one. */
    data class Session(val startMin: Int, val endMin: Int, val label: String? = null)

    /** One stretch of walking or running the day was made of. */
    data class Burst(val startMin: Int, val endMin: Int, val steps: Int, val movingMin: Int, val label: String, val metres: Double?) {
        val minutes get() = endMin - startMin
        val cadence get() = if (movingMin == 0) 0 else steps / movingMin
    }

    /** What Health Connect gave for one day. [minuteSteps] has [MINUTES] entries (today and yesterday only); [hourSteps] has 24. */
    class Raw(
        val day: Long, val steps: Long?, val metres: Double?, val activeKcal: Double?, val totalKcal: Double?, val floors: Double?,
        val minuteSteps: IntArray?, val hourSteps: IntArray?, val sessions: List<Session> = emptyList(),
        val updatedAt: Long? = null, val sources: List<String> = emptyList(),
    )

    /** A finished day kept for the usual curve and the week: only the hourly shape and the totals. */
    class Past(val day: Long, val steps: Long?, val metres: Double?, val activeKcal: Double?, val activeMin: Int?, val hourSteps: IntArray?)

    // --- active minutes ---------------------------------------------------------------------------------------------

    /** Minutes with at least [ACTIVE_STEPS_PER_MIN] steps, plus every minute inside an exercise session, each counted once. */
    fun activeMinutes(minuteSteps: IntArray?, sessions: List<Session>): Int? {
        if (minuteSteps == null && sessions.isEmpty()) return null
        val on = BooleanArray(MINUTES)
        minuteSteps?.forEachIndexed { m, s -> if (m < MINUTES && s >= ACTIVE_STEPS_PER_MIN) on[m] = true }
        sessions.forEach { s -> for (m in s.startMin.coerceAtLeast(0) until s.endMin.coerceAtMost(MINUTES)) on[m] = true }
        return on.count { it }
    }

    fun hours(minuteSteps: IntArray): IntArray = IntArray(24) { h -> (h * 60 until minOf(h * 60 + 60, minuteSteps.size)).sumOf { minuteSteps[it] } }

    // --- bursts: the day as a few walks --------------------------------------------------------------------------------

    fun bursts(minuteSteps: IntArray?, sessions: List<Session>, metresPerStep: Double?): List<Burst> {
        if (minuteSteps == null) return emptyList()
        val out = ArrayList<Burst>()
        var m = 0
        while (m < minuteSteps.size) {
            if (minuteSteps[m] < MOVING_STEPS_PER_MIN) { m++; continue }
            val start = m; var last = m; var gap = 0
            while (m < minuteSteps.size && gap <= GAP_MIN) { if (minuteSteps[m] >= MOVING_STEPS_PER_MIN) { last = m; gap = 0 } else gap++; m++ }
            val end = last + 1
            val steps = (start until end).sumOf { minuteSteps[it] }
            val moving = (start until end).count { minuteSteps[it] >= MOVING_STEPS_PER_MIN }
            if (end - start < MIN_BURST_MIN || steps < MIN_BURST_STEPS) continue
            val cadence = steps / moving.coerceAtLeast(1)
            val session = sessions.filter { !it.label.isNullOrBlank() }.maxByOrNull { overlap(it.startMin, it.endMin, start, end) }
                ?.takeIf { overlap(it.startMin, it.endMin, start, end) * 2 >= end - start }
            out += Burst(start, end, steps, moving, session?.label ?: nameOf(start, cadence), metresPerStep?.let { steps * it })
        }
        return out
    }

    private fun overlap(a0: Int, a1: Int, b0: Int, b1: Int) = (minOf(a1, b1) - maxOf(a0, b0)).coerceAtLeast(0)

    private fun nameOf(startMin: Int, cadence: Int): String {
        val h = startMin / 60
        val part = when { h < 5 -> "Night"; h < 11 -> "Morning"; h < 14 -> "Midday"; h < 17 -> "Afternoon"; h < 21 -> "Evening"; else -> "Night" }
        return "$part ${if (cadence >= RUN_CADENCE) "run" else "walk"}"
    }

    /** Metres per step, when the day gives both and the ratio is a believable stride; null otherwise (then walks show no distance). */
    fun stride(steps: Long?, metres: Double?): Double? =
        if (steps == null || metres == null || steps < 200) null else (metres / steps).takeIf { it in 0.4..1.4 }

    // --- your usual day ---------------------------------------------------------------------------------------------------

    /** Steps in each hour of a typical day, as the middle value over [basedOn] earlier days (a long hike doesn't skew it). */
    class Usual(val hours: DoubleArray, val basedOn: Int, val sameWeekday: Boolean) { val total get() = hours.sum() }

    /**
     * The usual shape of the day from up to 8 weeks of earlier days. Prefers the same weekday (Saturdays aren't Mondays) when
     * at least three of those exist, otherwise any recent days if there are at least five. Null when there isn't enough to say.
     */
    fun usual(past: List<Past>, today: Long): Usual? {
        val have = past.filter { it.day < today && it.day >= today - 56 && it.hourSteps != null && (it.steps ?: 0) > 500 }
        val same = have.filter { (today - it.day) % 7 == 0L }
        val use = if (same.size >= 3) same else have.takeIf { it.size >= 5 } ?: return null
        val hours = DoubleArray(24) { h -> median(use.map { it.hourSteps!![h].toDouble() }) }
        return Usual(hours, use.size, use === same)
    }

    private fun median(v: List<Double>): Double { val s = v.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

    /** Where you'd usually be by [nowMin] (minute of the day): whole hours before it plus the part of this one gone. */
    fun expectedBy(usual: Usual, nowMin: Int): Double {
        val h = (nowMin / 60).coerceIn(0, 23)
        return (0 until h).sumOf { usual.hours[it] } + usual.hours[h] * ((nowMin % 60) / 60.0)
    }

    data class Pace(val expected: Double, val ahead: Double, val projected: Int, val goalEtaMin: Int?)

    /**
     * [steps] so far at [nowMin] against [usual]. [projected] is where you'd finish if the rest of the day goes as usual;
     * [goalEtaMin] is the minute of the day you'd probably reach [goal] at that rate (null if already there or not likely today).
     */
    fun pace(steps: Long, usual: Usual?, nowMin: Int, goal: Int): Pace? {
        usual ?: return null
        if (usual.total < 1_500) return null
        val exp = expectedBy(usual, nowMin)
        val left = (usual.total - exp).coerceAtLeast(0.0)
        var eta: Int? = null
        if (steps < goal) {
            var m = nowMin
            while (m < MINUTES) { if (steps + (expectedBy(usual, m) - exp) >= goal) { eta = m; break }; m += 5 }
        }
        return Pace(exp, steps - exp, (steps + left).roundToInt(), eta)
    }

    /** One line on how you're doing against your usual day, or null when that isn't worth saying (early on, or too little history). */
    fun paceWords(p: Pace?, nowMin: Int): String? {
        p ?: return null
        if (nowMin < 8 * 60 || p.expected < 500) return null
        val tol = maxOf(300.0, p.expected * 0.08)
        return when {
            abs(p.ahead) < tol -> "Right on your usual pace for this time of day."
            p.ahead > 0 -> "${"%,d".format(p.ahead.roundToInt())} steps ahead of your usual for this time of day."
            else -> "${"%,d".format((-p.ahead).roundToInt())} steps behind your usual for this time of day."
        }
    }

    /** "6 PM": a minute of the day as a clock time, to the nearest five minutes. */
    fun clock(min: Int): String {
        val m = ((min + 2) / 5 * 5).coerceIn(0, MINUTES - 5)
        val h = m / 60; val mm = m % 60
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$h12${if (mm == 0) "" else ":%02d".format(mm)} ${if (h < 12) "AM" else "PM"}"
    }

    /** The busiest single hour, as its first hour of the day, or null if there are no steps. */
    fun peakHour(hourSteps: IntArray): Int? = hourSteps.indices.maxByOrNull { hourSteps[it] }?.takeIf { hourSteps[it] > 0 }

    fun hourRange(h: Int) = "${clock(h * 60)} – ${clock((h + 1) % 24 * 60)}"

    // --- rings -----------------------------------------------------------------------------------------------------------------

    /** How far one ring is round: 1.0 is the goal; more than 1 means laps. Null when that number isn't known. */
    fun fill(value: Double?, goal: Int): Float? = value?.let { (it / goal).toFloat().coerceAtLeast(0f) }

    // --- the whole day ----------------------------------------------------------------------------------------------------------

    class Day(
        val day: Long, val steps: Long, val metres: Double?, val activeKcal: Double?, val totalKcal: Double?, val floors: Int?, val activeMin: Int?,
        val hourSteps: IntArray, val bursts: List<Burst>, val usual: Usual?, val pace: Pace?, val peak: Int?,
        val updatedAt: Long?, val sources: List<String>,
    ) {
        fun rings(g: Goals) = Triple(fill(steps.toDouble(), g.steps), fill(activeMin?.toDouble(), g.activeMin), fill(activeKcal, g.kcal))
    }

    /** Puts a day together. [nowMin] is the minute of the day it is now (for today) and [live] says whether the day is still going. */
    fun build(raw: Raw, past: List<Past>, goals: Goals, nowMin: Int, live: Boolean): Day? {
        val steps = raw.steps ?: raw.minuteSteps?.sum()?.toLong() ?: raw.hourSteps?.sum()?.toLong() ?: return null
        val hourSteps = raw.hourSteps ?: raw.minuteSteps?.let { hours(it) } ?: IntArray(24)
        val bursts = bursts(raw.minuteSteps, raw.sessions, stride(steps, raw.metres))
        val usual = usual(past, raw.day)
        return Day(
            raw.day, steps, raw.metres, raw.activeKcal, raw.totalKcal, raw.floors?.roundToInt(), activeMinutes(raw.minuteSteps, raw.sessions),
            hourSteps, bursts, usual, if (live) pace(steps, usual, nowMin, goals.steps) else null, peakHour(hourSteps), raw.updatedAt, raw.sources,
        )
    }

    // --- the week -----------------------------------------------------------------------------------------------------------------

    class WeekDay(val day: Long, val steps: Long?, val metres: Double?, val activeKcal: Double?, val activeMin: Int?, val closed: Boolean?)
    class Week(val days: List<WeekDay>, val closed: Int, val streak: Int, val best: WeekDay?, val avgSteps: Long?)

    /** Seven days ending [today]; a day is "closed" when its steps reached the goal. The streak is counted back from yesterday if today isn't done yet. */
    fun week(past: List<Past>, today: Day?, goals: Goals, todayEpoch: Long): Week {
        val days = (todayEpoch - 6..todayEpoch).map { d ->
            if (d == todayEpoch && today != null) WeekDay(d, today.steps, today.metres, today.activeKcal, today.activeMin, today.steps >= goals.steps)
            else past.firstOrNull { it.day == d }?.let { p -> WeekDay(d, p.steps, p.metres, p.activeKcal, p.activeMin, p.steps?.let { it >= goals.steps }) } ?: WeekDay(d, null, null, null, null, null)
        }
        val order = days.reversed().let { if (it.first().closed != true) it.drop(1) else it }
        var streak = 0
        for (d in order) if (d.closed == true) streak++ else break
        val recorded = days.mapNotNull { it.steps }
        return Week(days, days.count { it.closed == true }, streak, days.filter { it.steps != null }.maxByOrNull { it.steps!! }, recorded.takeIf { it.size >= 3 }?.average()?.roundToInt()?.toLong())
    }

    // --- words -----------------------------------------------------------------------------------------------------------------------

    /** The headline over the rings. */
    fun headline(d: Day, g: Goals): String {
        val r = d.steps.toDouble() / g.steps
        return when {
            r >= 2.0 -> "Huge day: ${"%.1f".format(Locale.US, r)}× your step goal."
            r >= 1.0 -> "Step goal done" + if (d.activeMin != null && d.activeMin >= g.activeMin) ", and your active minutes too." else "."
            r >= 0.75 -> "Nearly there: ${"%,d".format(g.steps - d.steps)} steps to go."
            d.steps > 0 -> "${"%,d".format(g.steps - d.steps)} steps to your goal."
            else -> "No steps yet today."
        }
    }

    /** Whether to show miles: the United States, the United Kingdom and a few others do. */
    fun milesFor(country: String?) = country in setOf("US", "GB", "LR", "MM")

    fun distance(metres: Double, miles: Boolean): String =
        if (miles) "%.2f mi".format(Locale.US, metres / 1609.344) else if (metres < 1000) "${metres.roundToInt()} m" else "%.2f km".format(Locale.US, metres / 1000)

    fun minutesWords(min: Int) = if (min < 60) "$min min" else "${min / 60} h ${"%02d".format(min % 60)} min"

    /** Minutes since the newest reading, never negative. Samsung Health passes data on in batches, so this says how fresh the numbers are. */
    fun ageMin(updatedAt: Long, now: Long): Long = ((now - updatedAt) / 60_000).coerceAtLeast(0)
}
