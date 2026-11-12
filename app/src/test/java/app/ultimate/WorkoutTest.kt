package app.ultimate

import androidx.test.core.app.ApplicationProvider
import app.ultimate.air.BodyPose
import app.ultimate.health.Exercise
import app.ultimate.health.RepTracker
import app.ultimate.health.WorkoutLog
import app.ultimate.health.WorkoutSet
import app.ultimate.health.WorkoutStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

typealias Pt = Pair<Float, Float>

/** Simulated bodies: stick figures drawn in the camera's picture, moved through each exercise, with a little jitter on every joint like a real tracker. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UltimateApp::class)
class WorkoutTest {
    private val rnd = Random(5)
    private fun body(vararg j: Pair<Int, Pt>, jitter: Float = 0.004f): BodyPose {
        val pts = MutableList(33) { Triple(0f, 0f, 0f) }
        for ((i, p) in j) pts[i] = Triple(p.first + rnd.nextGaussian().toFloat() * jitter, p.second + rnd.nextGaussian().toFloat() * jitter, 0.9f)
        return BodyPose(pts, 0)
    }
    private val T = 33L   // ~30 pictures a second

    /** Runs [seconds] of a movement: [f] gets 0..1 through each cycle of [period] seconds (a smooth down-and-up) and returns the body. Returns the tracker. */
    private fun run(ex: Exercise, cycles: Int, period: Float = 2.2f, lead: Float = 1f, f: (Float) -> BodyPose?): RepTracker {
        val tr = RepTracker(ex); var t = 1000L
        repeat((lead * 30).toInt()) { tr.feed(f(0f), t); t += T }
        for (c in 0 until cycles) for (k in 0 until (period * 30).toInt()) { val ph = k / (period * 30f); tr.feed(f((1 - cos(2 * PI * ph).toFloat()) / 2), t); t += T }
        repeat(30) { tr.feed(f(0f), t); t += T }
        return tr
    }

    // ---- figures. All in picture coordinates: x right, y down.
    private fun knee(a: Pt, h: Pt, thigh: Float, forward: Float = 1f): Pt {
        val dx = h.first - a.first; val dy = h.second - a.second; val d = hypot(dx, dy).coerceAtMost(2 * thigh - 1e-4f); val hh = sqrt(thigh * thigh - d * d / 4)
        val mx = (a.first + h.first) / 2; val my = (a.second + h.second) / 2; return (mx + forward * hh * dy / d) to (my - forward * hh * dx / d)
    }
    private fun sideSquat(depth: Float): BodyPose {
        // The hip is `d` from the ankle (0.5 standing straight, 0.35 at a 90 degree knee) and moves back as it goes down.
        val a = 0.5f to 0.9f; val d = 0.498f - 0.144f * depth; val dx = 0.08f * depth; val hip = (0.5f - dx) to (0.9f - sqrt(d * d - dx * dx))
        val k = knee(a, hip, 0.25f); val sh = (hip.first + 0.08f * depth + 0.02f) to (hip.second - 0.3f)
        return body(BodyPose.L_ANKLE to a, BodyPose.R_ANKLE to a, BodyPose.L_KNEE to k, BodyPose.R_KNEE to k, BodyPose.L_HIP to hip, BodyPose.R_HIP to hip, BodyPose.L_SHOULDER to sh, BodyPose.R_SHOULDER to sh)
    }
    private fun pushUp(depth: Float): BodyPose {
        val d = 0.3f * (1 - 0.65f * depth); val sh = 0.7f to (0.85f - d); val w = 0.7f to 0.85f; val e = (0.7f - sqrt(0.15f * 0.15f - d * d / 4)) to (0.85f - d / 2)
        val an = 0.05f to 0.85f; val hip = 0.38f to (sh.second + 0.5f * (an.second - sh.second) + 0.01f)
        return body(BodyPose.L_SHOULDER to sh, BodyPose.R_SHOULDER to sh, BodyPose.L_ELBOW to e, BodyPose.R_ELBOW to e, BodyPose.L_WRIST to w, BodyPose.R_WRIST to w, BodyPose.L_HIP to hip, BodyPose.R_HIP to hip, BodyPose.L_ANKLE to an, BodyPose.R_ANKLE to an)
    }
    private fun standing() = body(BodyPose.L_SHOULDER to (0.45f to 0.3f), BodyPose.R_SHOULDER to (0.55f to 0.3f), BodyPose.L_ELBOW to (0.43f to 0.45f), BodyPose.R_ELBOW to (0.57f to 0.45f), BodyPose.L_WRIST to (0.43f to 0.6f), BodyPose.R_WRIST to (0.57f to 0.6f),
        BodyPose.L_HIP to (0.47f to 0.55f), BodyPose.R_HIP to (0.53f to 0.55f), BodyPose.L_KNEE to (0.47f to 0.72f), BodyPose.R_KNEE to (0.53f to 0.72f), BodyPose.L_ANKLE to (0.47f to 0.9f), BodyPose.R_ANKLE to (0.53f to 0.9f))
    private fun sitUp(up: Float): BodyPose {
        val hip = 0.5f to 0.8f; val kn = 0.7f to 0.65f; val b = (PI / 2 * (1 - up)).toFloat(); val sh = (hip.first - 0.3f * sin(b)) to (hip.second - 0.3f * cos(b))
        return body(BodyPose.L_SHOULDER to sh, BodyPose.R_SHOULDER to sh, BodyPose.L_HIP to hip, BodyPose.R_HIP to hip, BodyPose.L_KNEE to kn, BodyPose.R_KNEE to kn)
    }
    private fun lunge(depth: Float, stance: Float): BodyPose {
        val aL = (0.5f - stance / 2) to 0.9f; val aR = (0.5f + stance / 2) to 0.9f; val hip = 0.5f to (0.9f - (0.53f - 0.163f * depth))
        val kL = knee(aL, hip, 0.28f); val kR = knee(aR, hip, 0.28f)
        return body(BodyPose.L_ANKLE to aL, BodyPose.R_ANKLE to aR, BodyPose.L_KNEE to kL, BodyPose.R_KNEE to kR, BodyPose.L_HIP to hip, BodyPose.R_HIP to hip, BodyPose.L_SHOULDER to (0.5f to hip.second - 0.3f), BodyPose.R_SHOULDER to (0.5f to hip.second - 0.3f))
    }
    private fun jack(open: Float): BodyPose {
        fun l(a: Float, b: Float) = a + (b - a) * open
        return body(BodyPose.L_SHOULDER to (0.45f to 0.4f), BodyPose.R_SHOULDER to (0.55f to 0.4f), BodyPose.L_HIP to (0.46f to 0.62f), BodyPose.R_HIP to (0.54f to 0.62f),
            BodyPose.L_WRIST to (l(0.44f, 0.35f) to l(0.7f, 0.15f)), BodyPose.R_WRIST to (l(0.56f, 0.65f) to l(0.7f, 0.15f)), BodyPose.L_ANKLE to (l(0.47f, 0.35f) to 0.9f), BodyPose.R_ANKLE to (l(0.53f, 0.65f) to 0.9f))
    }
    private fun curl(up: Float, swing: Float = 0f): BodyPose {
        val s = 0.5f to 0.4f; val e = (0.5f + swing) to 0.62f; val ang = (PI - Math.toRadians(170.0 - 125.0 * up)).toFloat()
        return body(BodyPose.L_SHOULDER to s, BodyPose.L_ELBOW to e, BodyPose.L_WRIST to ((e.first + 0.22f * sin(ang)) to (e.second + 0.22f * cos(ang))))
    }
    private fun press(up: Float): BodyPose { val y = 0.4f - 0.38f * up; return body(BodyPose.NOSE to (0.5f to 0.25f), BodyPose.L_SHOULDER to (0.4f to 0.4f), BodyPose.R_SHOULDER to (0.6f to 0.4f), BodyPose.L_WRIST to (0.38f to y), BodyPose.R_WRIST to (0.62f to y)) }
    private fun knees(l: Float, r: Float) = body(BodyPose.L_SHOULDER to (0.45f to 0.4f), BodyPose.R_SHOULDER to (0.55f to 0.4f), BodyPose.L_HIP to (0.46f to 0.62f), BodyPose.R_HIP to (0.54f to 0.62f), BodyPose.L_KNEE to (0.46f to 0.78f - 0.18f * l), BodyPose.R_KNEE to (0.54f to 0.78f - 0.18f * r))
    private fun plank(hipY: Float) = body(BodyPose.L_SHOULDER to (0.7f to 0.6f), BodyPose.R_SHOULDER to (0.7f to 0.6f), BodyPose.L_HIP to (0.4f to hipY), BodyPose.R_HIP to (0.4f to hipY), BodyPose.L_ANKLE to (0.1f to 0.8f), BodyPose.R_ANKLE to (0.1f to 0.8f))

    @Test fun squatsAreCountedFromTheSide() {
        val t = run(Exercise.SQUAT, 10) { sideSquat(it) }
        assertEquals(10, t.reps); assertTrue("deep: ${t.averagePeak}", t.averagePeak!! > 0.8f); assertNull(t.cue)
    }
    @Test fun halfSquatsAreNotCountedAndSayWhy() {
        val t = run(Exercise.SQUAT, 5) { sideSquat(0.45f * it) }
        assertEquals(0, t.reps); assertEquals("Go deeper", t.cue)
    }
    @Test fun standingStillOrWalkingTowardsThePhoneCountsNothing() {
        assertEquals(0, run(Exercise.SQUAT, 8, period = 2f) { standing() }.reps)
        // Walking closer: the hips slide down the picture a little, but the legs stay straight.
        assertEquals(0, run(Exercise.SQUAT, 3, period = 6f) { standing().let { b -> BodyPose(b.points.map { Triple(it.first, it.second + 0.1f * 0.3f * it.third, it.third) }, 0) } }.reps)
    }
    @Test fun aRepIsNotLostWhenTrackingFlickersButNothingCountsWhileYouAreOutOfView() {
        val tr = RepTracker(Exercise.SQUAT); var t = 1000L; var n = 0
        repeat(30) { tr.feed(sideSquat(0f), t); t += T }
        for (k in 0 until 66) { val ph = k / 66f; val d = (1 - cos(2 * PI * ph).toFloat()) / 2; if (k in 30..40) tr.feed(null, t) else if (tr.feed(sideSquat(d), t)) n++; t += T }
        repeat(30) { if (tr.feed(sideSquat(0f), t)) n++; t += T }
        assertEquals("a third of a second lost mid-rep", 1, n)
        val away = RepTracker(Exercise.SQUAT); t = 1000L; repeat(20) { away.feed(sideSquat(0f), t); t += T }
        repeat(20) { away.feed(sideSquat(1f), t); t += T }; repeat(60) { away.feed(null, t); t += T }; repeat(20) { away.feed(sideSquat(0f), t); t += T }
        assertEquals("walked out of the picture at the bottom: no rep", 0, away.reps)
    }
    @Test fun pushUpsAreCountedWhenYouAreInPosition() {
        val t = run(Exercise.PUSH_UP, 8, period = 1.8f) { pushUp(it) }
        assertEquals(8, t.reps)
        val standingUp = run(Exercise.PUSH_UP, 4) { standing() }
        assertEquals(0, standingUp.reps); assertTrue(standingUp.cue!!.contains("push-up")); assertFalse(standingUp.tracking)
    }
    @Test fun sitUpsLungesAndTheDifferenceBetweenALungeAndASquat() {
        assertEquals(6, run(Exercise.SIT_UP, 6) { sitUp(it) }.reps)
        assertEquals(5, run(Exercise.LUNGE, 5) { lunge(it, 0.3f) }.reps)
        val narrow = run(Exercise.LUNGE, 5) { lunge(it, 0.04f) }
        assertEquals("a squat is not a lunge", 0, narrow.reps)
    }
    @Test fun jumpingJacksCurlsPressesAndHighKnees() {
        assertEquals(12, run(Exercise.JUMPING_JACK, 12, period = 1.0f) { jack(it) }.reps)
        assertEquals(8, run(Exercise.BICEP_CURL, 8) { curl(it) }.reps)
        val swing = RepTracker(Exercise.BICEP_CURL); repeat(5) { swing.feed(curl(0.5f, 0.25f), 1000L + it * T) }
        assertEquals("an elbow swung away from the body is called out", "Keep your elbow by your side", swing.cue)
        val tucked = RepTracker(Exercise.BICEP_CURL); repeat(5) { tucked.feed(curl(0.5f), 1000L + it * T) }; assertNull(tucked.cue)
        assertEquals(6, run(Exercise.SHOULDER_PRESS, 6) { press(it) }.reps)
        // High knees: left, right, left, right ... each lift counts once.
        val tr = RepTracker(Exercise.HIGH_KNEES); var t = 1000L
        repeat(20) { tr.feed(knees(0f, 0f), t); t += T }
        repeat(10) { n -> repeat(8) { k -> val u = sin(PI * k / 7).toFloat(); tr.feed(if (n % 2 == 0) knees(u, 0f) else knees(0f, u), t); t += T }; repeat(4) { tr.feed(knees(0f, 0f), t); t += T } }
        assertEquals(10, tr.reps)
    }
    @Test fun aPlankIsTimedAndSaysWhichWayTheHipsAreOff() {
        val tr = RepTracker(Exercise.PLANK); var t = 1000L
        repeat(30 * 20) { tr.feed(plank(0.7f), t); t += T }
        assertEquals("20 s held", 20000f, tr.holdMs.toFloat(), 800f); assertNull(tr.cue)
        val before = tr.holdMs; repeat(30 * 5) { tr.feed(plank(0.8f), t); t += T }
        assertTrue("sagging time is not held time: ${tr.holdMs - before}", tr.holdMs - before < 600); assertEquals("Lift your hips", tr.cue)
        repeat(30 * 3) { tr.feed(plank(0.58f), t); t += T }; assertEquals("Lower your hips", tr.cue)
    }

    @Test fun theLogTotalsStreaksAndBests() {
        fun set(id: String, ex: Exercise, day: Long, reps: Int = 0, sec: Int = 0) = WorkoutSet(id, ex.name, day * 86_400_000L, day, reps, sec, null, true)
        val l = listOf(set("a", Exercise.SQUAT, 100, 20), set("b", Exercise.SQUAT, 100, 25), set("c", Exercise.PUSH_UP, 99, 12), set("d", Exercise.PLANK, 98, sec = 75), set("e", Exercise.SQUAT, 90, 40))
        val w = WorkoutLog.totals(l, 94, 100); assertEquals(Exercise.SQUAT, w.first().exercise); assertEquals(45, w.first().reps); assertEquals(2, w.first().sets); assertEquals(75, w.first { it.exercise == Exercise.PLANK }.seconds)
        assertEquals(3, WorkoutLog.streak(l, 100)); assertEquals("today not done yet: yesterday still counts", 3, WorkoutLog.streak(l, 101)); assertEquals(0, WorkoutLog.streak(l, 103))
        assertEquals(40, WorkoutLog.best(l, Exercise.SQUAT)); assertEquals("only the older set counts as the earlier best", 40, WorkoutLog.best(l, Exercise.SQUAT, beforeAt = 95 * 86_400_000L)); assertEquals(0, WorkoutLog.best(l, Exercise.SQUAT, beforeAt = 80 * 86_400_000L)); assertEquals(75, WorkoutLog.best(l, Exercise.PLANK))
        val s = WorkoutLog.summary(l, 100)!!; assertTrue(s, s.contains("45 squats") && s.contains("1:15 plank") && s.contains("3 days in a row"))
        assertNull("nothing in the last week", WorkoutLog.summary(listOf(set("x", Exercise.SQUAT, 10, 5)), 100)); assertEquals("1:05", WorkoutLog.mmss(65))
    }

    @Test fun setsAreKeptOnThePhone() {
        val store = WorkoutStore(ApplicationProvider.getApplicationContext()); store.clear()
        assertTrue(store.all().isEmpty())
        store.add(WorkoutSet("1", "SQUAT", 5L, 1L, 12, 0, 0.9f, true)); store.add(WorkoutSet("2", "PLANK", 6L, 1L, 0, 45, null, false))
        val a = store.all(); assertEquals(2, a.size); assertEquals(0.9f, a[0].quality!!, 1e-6f); assertNull(a[1].quality); assertEquals(Exercise.PLANK, a[1].kind); assertFalse(a[1].camera)
        assertEquals(1, store.remove("1").size); store.clear(); assertTrue(store.all().isEmpty())
    }
}
