package app.ultimate.health

import android.content.Context
import app.ultimate.air.BodyPose
import app.ultimate.air.Joints
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The exercises the camera can count. [hi] and [lo] are how far through the movement (0 = where it starts, 1 = fully done) you must get for it to
 * count and how far back you must come before the next one can start. [tip] is where to put the phone: the angles are read from a flat picture, so
 * each movement needs to be seen from the right side.
 */
enum class Exercise(val label: String, val emoji: String, val timed: Boolean, val tip: String, val hi: Float = 0.75f, val lo: Float = 0.25f, val partial: String = "Go deeper", val minMs: Long = 450L) {
    SQUAT("Squats", "🏋️", false, "Prop the phone 2–3 m away, side-on or facing you, so your whole body is in the picture."),
    PUSH_UP("Push-ups", "💪", false, "Put the phone on the floor, side-on, so your body runs across the picture.", partial = "Lower your chest"),
    SIT_UP("Sit-ups", "🧘", false, "Phone on the floor, side-on, whole body in the picture.", partial = "Come up higher"),
    LUNGE("Lunges", "🦵", false, "Prop the phone side-on, 2–3 m away, whole body in the picture.", partial = "Drop your back knee lower"),
    JUMPING_JACK("Jumping jacks", "🤸", false, "Prop the phone facing you, 2–3 m away, whole body in the picture.", hi = 0.7f, partial = "Arms all the way up, feet wider", minMs = 220L),
    BICEP_CURL("Bicep curls", "🏋️‍♂️", false, "Prop the phone side-on to your curling arm, upper body in the picture.", hi = 0.8f, lo = 0.2f, partial = "Curl all the way up"),
    SHOULDER_PRESS("Shoulder presses", "🙌", false, "Prop the phone facing you, head and hands in the picture.", hi = 0.9f, lo = 0.3f, partial = "Press all the way up"),
    HIGH_KNEES("High knees", "🏃", false, "Prop the phone facing you or side-on, whole body in the picture."),
    PLANK("Plank", "🧱", true, "Put the phone on the floor, side-on, so your body runs across the picture.");
}

/**
 * Counting repetitions from a pose, one tracker per exercise and set. Each movement is reduced to one number, how far through it you are, from
 * the joint angles or heights that define it; a rep is going past [Exercise.hi] and coming back under [Exercise.lo]. Half-reps are not counted
 * and say so. Nothing is counted while you are out of position or out of view, and a short loss of tracking does not break a rep. Pure, so it
 * is tested against simulated bodies.
 */
class RepTracker(val exercise: Exercise, private val minRepMs: Long = exercise.minMs) {
    var reps = 0; private set
    var holdMs = 0L; private set
    /** How far through the movement you are, 0..1, for a gauge; null when not tracking. */
    var progress: Float? = null; private set
    var cue: String? = null; private set
    /** True while you are seen and in position for this exercise. */
    var tracking = false; private set
    /** How deep each counted rep went, 0..1 (1 is fully done). */
    val peaks = ArrayList<Float>()

    private var smooth: Float? = null; private var active = false; private var activeAt = 0L; private var peak = 0f; private var reach = 0f
    private var lastT = 0L; private var lostSince = 0L
    private var standY = Float.NaN; private var legRef = 0f
    private val legUp = BooleanArray(2); private val legAt = LongArray(2)
    private var plankLostMs = 0L

    fun reset() { reps = 0; holdMs = 0; peaks.clear(); progress = null; cue = null; tracking = false; smooth = null; active = false; peak = 0f; reach = 0f; standY = Float.NaN; legRef = 0f; legUp.fill(false); lastT = 0; lostSince = 0; plankLostMs = 0 }
    val averagePeak: Float? get() = peaks.takeIf { it.isNotEmpty() }?.average()?.toFloat()

    /** Feeds one picture of the body ([p] null when nobody is seen). True when this picture completed a repetition. */
    fun feed(p: BodyPose?, t: Long): Boolean {
        val dt = if (lastT == 0L) 0L else (t - lastT).coerceIn(0, 400); lastT = t
        return when (exercise) { Exercise.HIGH_KNEES -> knees(p, t); Exercise.PLANK -> plank(p, dt); else -> movement(p, t) }
    }

    private fun pt(p: BodyPose, i: Int) = p.at(i, false)
    private fun mid(a: Pair<Float, Float>?, b: Pair<Float, Float>?) = if (a != null && b != null) (a.first + b.first) / 2 to (a.second + b.second) / 2 else a ?: b
    private fun dist(a: Pair<Float, Float>, b: Pair<Float, Float>) = hypot(a.first - b.first, a.second - b.second)
    private fun clamp(v: Float) = v.coerceIn(0f, 1f)

    private fun movement(p: BodyPose?, t: Long): Boolean {
        val (raw, say) = if (p == null) null to "Step back so your whole body is in view" else signal(p)
        if (raw == null) {
            tracking = false; progress = null; cue = say
            if (lostSince == 0L) lostSince = t
            if (t - lostSince > 1200) { active = false; peak = 0f; reach = 0f; smooth = null }
            return false
        }
        lostSince = 0; tracking = true
        val s = smooth?.let { it * 0.4f + raw * 0.6f } ?: raw; smooth = s; progress = s
        if (say != null) cue = say else if (cue != null && cue!!.startsWith("Step") || cue == "Get into position") cue = null
        if (s > exercise.lo) reach = max(reach, s)
        if (!active && s >= exercise.hi) { active = true; activeAt = t; peak = s; cue = null }
        else if (active) {
            peak = max(peak, s)
            if (s <= exercise.lo) { active = false; reach = 0f; if (t - activeAt >= minRepMs) { reps++; peaks += peak; cue = null; return true } }
        } else if (s <= exercise.lo) { if (reach in 0.4f..exercise.hi) cue = exercise.partial; reach = 0f }
        return false
    }

    /** The movement's progress (null when not seen or not in position) and any advice. */
    private fun signal(p: BodyPose): Pair<Float?, String?> = when (exercise) {
        Exercise.SQUAT -> squat(p)
        Exercise.PUSH_UP -> {
            val e = Joints.elbow(p); val sh = mid(pt(p, BodyPose.L_SHOULDER), pt(p, BodyPose.R_SHOULDER)); val an = mid(pt(p, BodyPose.L_ANKLE), pt(p, BodyPose.R_ANKLE))
            if (e == null || sh == null || an == null) null to "Get into position: the phone side-on, your whole body in view"
            else if (abs(sh.second - an.second) > 0.6f * abs(sh.first - an.first)) null to "Get into a push-up position, side-on to the phone"
            else clamp((165f - e) / 75f) to null
        }
        Exercise.SIT_UP -> {
            val a = listOf(Triple(BodyPose.L_SHOULDER, BodyPose.L_HIP, BodyPose.L_KNEE), Triple(BodyPose.R_SHOULDER, BodyPose.R_HIP, BodyPose.R_KNEE)).filter { (x, y, z) -> p.ok(x) && p.ok(y) && p.ok(z) }
                .map { (x, y, z) -> Joints.angle(pt(p, x)!!, pt(p, y)!!, pt(p, z)!!) }.minOrNull()
            if (a == null) null to "Get your shoulders, hips and knees in view" else clamp((150f - a) / 75f) to null
        }
        Exercise.LUNGE -> {
            val la = listOf(BodyPose.L_HIP to BodyPose.L_KNEE to BodyPose.L_ANKLE, BodyPose.R_HIP to BodyPose.R_KNEE to BodyPose.R_ANKLE)
            val ks = la.filter { (h, a) -> p.ok(h.first) && p.ok(h.second) && p.ok(a) }.map { (h, a) -> Joints.angle(pt(p, h.first)!!, pt(p, h.second)!!, pt(p, a)!!) }
            val aL = pt(p, BodyPose.L_ANKLE); val aR = pt(p, BodyPose.R_ANKLE); val hip = mid(pt(p, BodyPose.L_HIP), pt(p, BodyPose.R_HIP))
            if (ks.size < 2 || aL == null || aR == null || hip == null) null to "Get both legs in view, side-on"
            else { val leg = max(dist(hip, aL), dist(hip, aR)); val stance = abs(aL.first - aR.first) / leg.coerceAtLeast(0.05f)
                val base = clamp((170f - ks.min()) / 75f)
                (if (stance < 0.3f) min(base, 0.2f) else base) to (if (stance < 0.3f && base > 0.4f) "Step further apart" else null) }
        }
        Exercise.JUMPING_JACK -> {
            val sl = pt(p, BodyPose.L_SHOULDER); val sr = pt(p, BodyPose.R_SHOULDER); val wl = pt(p, BodyPose.L_WRIST); val wr = pt(p, BodyPose.R_WRIST); val al = pt(p, BodyPose.L_ANKLE); val ar = pt(p, BodyPose.R_ANKLE); val hip = mid(pt(p, BodyPose.L_HIP), pt(p, BodyPose.R_HIP))
            if (sl == null || sr == null || wl == null || wr == null || al == null || ar == null || hip == null) null to "Face the phone with your whole body in view"
            else { val sm = mid(sl, sr)!!; val torso = dist(sm, hip).coerceAtLeast(0.05f); val sw = dist(sl, sr).coerceAtLeast(0.03f)
                val arms = clamp((sm.second - (wl.second + wr.second) / 2) / (0.6f * torso) + 0.15f)
                val legs = clamp((abs(al.first - ar.first) / sw - 1.0f) / 0.9f)
                min(arms, legs) to null }
        }
        Exercise.BICEP_CURL -> {
            val arm = listOf(Triple(BodyPose.L_SHOULDER, BodyPose.L_ELBOW, BodyPose.L_WRIST), Triple(BodyPose.R_SHOULDER, BodyPose.R_ELBOW, BodyPose.R_WRIST)).filter { (a, b, c) -> p.ok(a) && p.ok(b) && p.ok(c) }
                .map { (a, b, c) -> Joints.angle(pt(p, a)!!, pt(p, b)!!, pt(p, c)!!) to (pt(p, b)!!.first - pt(p, a)!!.first) / max(0.02f, abs(pt(p, b)!!.second - pt(p, a)!!.second)) }.minByOrNull { it.first }
            if (arm == null) null to "Get your arm in view" else clamp((160f - arm.first) / 105f) to (if (abs(arm.second) > 0.8f) "Keep your elbow by your side" else null)
        }
        Exercise.SHOULDER_PRESS -> {
            val nose = pt(p, BodyPose.NOSE); val sm = mid(pt(p, BodyPose.L_SHOULDER), pt(p, BodyPose.R_SHOULDER)); val w = mid(pt(p, BodyPose.L_WRIST), pt(p, BodyPose.R_WRIST))
            if (nose == null || sm == null || w == null) null to "Face the phone with your head and hands in view"
            else clamp((sm.second - w.second) / (sm.second - nose.second).coerceAtLeast(0.05f) / 1.5f) to null
        }
        else -> null to null
    }

    /**
     * Squats seen from the side bend the knee a lot on screen; seen from the front the thigh points at the camera and it bends little, but the hips
     * drop. So the depth is the larger of the two, but a dropping hip only counts when the knee really is bending (walking towards the phone drops
     * the hip in the picture too).
     */
    private fun squat(p: BodyPose): Pair<Float?, String?> {
        val k = Joints.knee(p); val hip = mid(pt(p, BodyPose.L_HIP), pt(p, BodyPose.R_HIP)); val an = mid(pt(p, BodyPose.L_ANKLE), pt(p, BodyPose.R_ANKLE))
        if (k == null || hip == null) return null to "Step back so your whole body is in view"
        val flex = clamp((170f - k) / 80f)
        var drop = 0f
        if (an != null) {
            legRef = max(legRef * 0.9995f, dist(hip, an)); standY = if (standY.isNaN()) hip.second else min(hip.second, standY + 0.00015f)
            drop = clamp((hip.second - standY) / (0.4f * legRef.coerceAtLeast(0.1f))); if (k >= 160f) drop = 0f
        }
        return max(flex, drop) to null
    }

    private fun knees(p: BodyPose?, t: Long): Boolean {
        if (p == null) { tracking = false; progress = null; cue = "Step back so your whole body is in view"; return false }
        val sm = mid(pt(p, BodyPose.L_SHOULDER), pt(p, BodyPose.R_SHOULDER)); val hipM = mid(pt(p, BodyPose.L_HIP), pt(p, BodyPose.R_HIP))
        if (sm == null || hipM == null) { tracking = false; progress = null; cue = "Get your whole body in view"; return false }
        tracking = true; cue = null
        val torso = dist(sm, hipM).coerceAtLeast(0.05f); var counted = false; var best = 0f
        for ((n, ids) in listOf(BodyPose.L_HIP to BodyPose.L_KNEE, BodyPose.R_HIP to BodyPose.R_KNEE).withIndex()) {
            val h = pt(p, ids.first) ?: continue; val k = pt(p, ids.second) ?: continue
            val lift = (h.second - k.second) / torso      // positive: knee above the hip
            best = max(best, clamp((lift + 0.4f) / 0.5f))
            if (!legUp[n] && lift >= -0.05f && t - legAt[n] >= 250) { legUp[n] = true; legAt[n] = t; reps++; peaks += clamp((lift + 0.4f) / 0.5f); counted = true }
            else if (legUp[n] && lift <= -0.35f) legUp[n] = false
        }
        progress = best
        return counted
    }

    /** A plank is held, not counted: time accumulates while the body is level and in a straight line, and the advice says which way the hips are off. */
    private fun plank(p: BodyPose?, dt: Long): Boolean {
        val sh = p?.let { mid(pt(it, BodyPose.L_SHOULDER), pt(it, BodyPose.R_SHOULDER)) }; val hip = p?.let { mid(pt(it, BodyPose.L_HIP), pt(it, BodyPose.R_HIP)) }; val an = p?.let { mid(pt(it, BodyPose.L_ANKLE), pt(it, BodyPose.R_ANKLE)) }
        if (sh == null || hip == null || an == null) { plankLostMs += dt; if (plankLostMs > 1500) { tracking = false; progress = null }; cue = "Get your whole body in view, side-on"; return false }
        val level = abs(sh.second - an.second) <= 0.4f * abs(sh.first - an.first)
        if (!level) { plankLostMs += dt; if (plankLostMs > 1500) { tracking = false; progress = null }; cue = "Get into a plank, side-on to the phone"; return false }
        plankLostMs = 0; tracking = true
        val a = Joints.angle(sh, hip, an); progress = clamp((a - 140f) / 35f)
        if (a >= 160f) { holdMs += dt; cue = null }
        else { val lineY = sh.second + (an.second - sh.second) * ((hip.first - sh.first) / (an.first - sh.first).let { if (abs(it) < 1e-4f) 1e-4f else it }); cue = if (hip.second > lineY) "Lift your hips" else "Lower your hips" }
        return false
    }
}

/** One set that was done: [reps], or [seconds] for something held. [quality] is the average depth of the counted reps (1 is full), only when the camera counted them. */
data class WorkoutSet(val id: String, val exercise: String, val at: Long, val day: Long, val reps: Int, val seconds: Int, val quality: Float?, val camera: Boolean) {
    val kind: Exercise? get() = runCatching { Exercise.valueOf(exercise) }.getOrNull()
}

/** Totals and plain words from the logged sets. Pure, so it is tested. */
object WorkoutLog {
    data class Total(val exercise: Exercise, val sets: Int, val reps: Int, val seconds: Int)

    fun totals(sets: List<WorkoutSet>, fromDay: Long, toDay: Long): List<Total> = sets.filter { it.day in fromDay..toDay }.groupBy { it.kind }.mapNotNull { (k, v) -> k?.let { Total(it, v.size, v.sumOf { s -> s.reps }, v.sumOf { s -> s.seconds }) } }.sortedWith(compareByDescending<Total> { it.sets }.thenByDescending { it.reps + it.seconds })

    /** The best single set of an exercise before [beforeAt] (most reps, or longest hold). */
    fun best(sets: List<WorkoutSet>, ex: Exercise, beforeAt: Long = Long.MAX_VALUE): Int = sets.filter { it.exercise == ex.name && it.at < beforeAt }.maxOfOrNull { if (ex.timed) it.seconds else it.reps } ?: 0

    /** Days in a row, up to today (or yesterday: today may not have happened yet), with at least one set. */
    fun streak(sets: List<WorkoutSet>, today: Long): Int {
        val days = sets.map { it.day }.toSet(); var d = if (today in days) today else today - 1; var n = 0
        while (d in days) { n++; d-- }
        return n
    }

    fun summary(sets: List<WorkoutSet>, today: Long): String? {
        val week = totals(sets, today - 6, today); if (week.isEmpty()) return null
        val days = sets.filter { it.day in today - 6..today }.map { it.day }.toSet().size
        val parts = week.take(3).joinToString(", ") { t -> if (t.exercise.timed) "${t.seconds / 60}:%02d plank".format(t.seconds % 60) else "${t.reps} ${t.exercise.label.lowercase()}" }
        val st = streak(sets, today)
        return "This week: $parts over $days day${if (days == 1) "" else "s"}" + if (st >= 2) " · $st days in a row" else ""
    }

    fun mmss(seconds: Int) = "%d:%02d".format(seconds / 60, seconds % 60)
}

/** Workouts kept on the phone and nowhere else. */
class WorkoutStore(context: Context) {
    private val prefs = context.getSharedPreferences("workouts", Context.MODE_PRIVATE)
    fun all(): List<WorkoutSet> = runCatching {
        val a = JSONArray(prefs.getString("sets", "[]")); (0 until a.length()).map { i -> a.getJSONObject(i).let { WorkoutSet(it.getString("id"), it.getString("ex"), it.getLong("at"), it.getLong("day"), it.getInt("reps"), it.getInt("sec"), if (it.has("q")) it.getDouble("q").toFloat() else null, it.getBoolean("cam")) } }
    }.getOrDefault(emptyList())
    fun add(s: WorkoutSet): List<WorkoutSet> = (all() + s).takeLast(5000).also { save(it) }
    fun remove(id: String): List<WorkoutSet> = all().filterNot { it.id == id }.also { save(it) }
    fun clear() { prefs.edit().clear().apply() }
    private fun save(l: List<WorkoutSet>) { prefs.edit().putString("sets", JSONArray().also { a -> l.forEach { s -> a.put(JSONObject().put("id", s.id).put("ex", s.exercise).put("at", s.at).put("day", s.day).put("reps", s.reps).put("sec", s.seconds).put("cam", s.camera).also { o -> s.quality?.let { o.put("q", it.toDouble()) } }) } }.toString()).apply() }
}

/** Set by the build that carries body tracking, so the Health screen can offer counting from the camera. Null in builds without it (hand-logging still works). */
object WorkoutCameraHook {
    @Volatile var content: (@androidx.compose.runtime.Composable (store: WorkoutStore, onExit: () -> Unit) -> Unit)? = null
}
