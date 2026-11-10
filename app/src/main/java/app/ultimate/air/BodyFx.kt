package app.ultimate.air

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/** Your whole body as the camera sees it: 33 points (MediaPipe Pose order), each x, y in 0..1 of the picture and how sure it is. */
data class BodyPose(val points: List<Triple<Float, Float, Float>>, val at: Long) {
    fun ok(i: Int) = i in points.indices && points[i].third >= SURE
    /** Point [i] on screen, mirrored when the front camera is used; null when it is not clearly seen. */
    fun at(i: Int, mirror: Boolean): Pair<Float, Float>? = if (ok(i)) (if (mirror) 1f - points[i].first else points[i].first) to points[i].second else null

    companion object {
        const val SURE = 0.5f
        const val NOSE = 0; const val L_EAR = 7; const val R_EAR = 8
        const val L_SHOULDER = 11; const val R_SHOULDER = 12; const val L_ELBOW = 13; const val R_ELBOW = 14; const val L_WRIST = 15; const val R_WRIST = 16
        const val L_INDEX = 19; const val R_INDEX = 20
        const val L_HIP = 23; const val R_HIP = 24; const val L_KNEE = 25; const val R_KNEE = 26; const val L_ANKLE = 27; const val R_ANKLE = 28
        const val L_HEEL = 29; const val R_HEEL = 30; const val L_TOE = 31; const val R_TOE = 32
        /** The lines that make a stick figure. */
        val BONES = listOf(
            L_SHOULDER to R_SHOULDER, L_SHOULDER to L_ELBOW, L_ELBOW to L_WRIST, R_SHOULDER to R_ELBOW, R_ELBOW to R_WRIST,
            L_SHOULDER to L_HIP, R_SHOULDER to R_HIP, L_HIP to R_HIP, L_HIP to L_KNEE, L_KNEE to L_ANKLE, R_HIP to R_KNEE, R_KNEE to R_ANKLE,
            L_ANKLE to L_HEEL, L_HEEL to L_TOE, L_ANKLE to L_TOE, R_ANKLE to R_HEEL, R_HEEL to R_TOE, R_ANKLE to R_TOE,
        )
        val HANDS = listOf(L_WRIST, R_WRIST); val FEET = listOf(L_ANKLE, R_ANKLE)
    }
}

/** Geometry of a pose. Pure, so it is tested. */
object Joints {
    /** The angle at [b] between the lines to [a] and [c], in degrees (180 is straight). */
    fun angle(a: Pair<Float, Float>, b: Pair<Float, Float>, c: Pair<Float, Float>): Float {
        val ux = a.first - b.first; val uy = a.second - b.second; val vx = c.first - b.first; val vy = c.second - b.second
        val d = hypot(ux, uy) * hypot(vx, vy)
        if (d < 1e-6f) return 180f
        return Math.toDegrees(acos(((ux * vx + uy * vy) / d).coerceIn(-1f, 1f)).toDouble()).toFloat()
    }

    /** How far the shoulders are from level, in degrees (0 is level); null when they aren't both seen. */
    fun shoulderTilt(p: BodyPose): Float? {
        if (!p.ok(BodyPose.L_SHOULDER) || !p.ok(BodyPose.R_SHOULDER)) return null
        val l = p.points[BodyPose.L_SHOULDER]; val r = p.points[BodyPose.R_SHOULDER]
        val deg = Math.toDegrees(atan2((l.second - r.second).toDouble(), abs(l.first - r.first).toDouble().coerceAtLeast(1e-6))).toFloat()
        return abs(deg)
    }

    /** The knee angle of whichever leg is seen better (both: the more bent one); null when no whole leg is in view. */
    fun knee(p: BodyPose): Float? = listOf(Triple(BodyPose.L_HIP, BodyPose.L_KNEE, BodyPose.L_ANKLE), Triple(BodyPose.R_HIP, BodyPose.R_KNEE, BodyPose.R_ANKLE))
        .filter { (a, b, c) -> p.ok(a) && p.ok(b) && p.ok(c) }.map { (a, b, c) -> angle(p.at(a, false)!!, p.at(b, false)!!, p.at(c, false)!!) }.minOrNull()

    fun elbow(p: BodyPose): Float? = listOf(Triple(BodyPose.L_SHOULDER, BodyPose.L_ELBOW, BodyPose.L_WRIST), Triple(BodyPose.R_SHOULDER, BodyPose.R_ELBOW, BodyPose.R_WRIST))
        .filter { (a, b, c) -> p.ok(a) && p.ok(b) && p.ok(c) }.map { (a, b, c) -> angle(p.at(a, false)!!, p.at(b, false)!!, p.at(c, false)!!) }.minOrNull()

    /** Shoulder width on screen: the yardstick that sizes stickers to how near you stand. */
    fun scale(p: BodyPose): Float? = if (p.ok(BodyPose.L_SHOULDER) && p.ok(BodyPose.R_SHOULDER))
        hypot(p.points[BodyPose.L_SHOULDER].first - p.points[BodyPose.R_SHOULDER].first, p.points[BodyPose.L_SHOULDER].second - p.points[BodyPose.R_SHOULDER].second) else null
}

/**
 * Counts repetitions from one joint angle: a rep is going below [down] and coming back above [up] (a squat for the knee,
 * a curl or push-up for the elbow). The gap between the two stops wobbling at one level from counting, and a rep must take
 * at least [minMs]. Pure, so it is tested.
 */
class RepCounter(private val down: Float = 100f, private val up: Float = 155f, private val minMs: Long = 400) {
    var count = 0; private set
    var isDown = false; private set
    private var wentDownAt = 0L

    fun feed(angle: Float?, t: Long): Boolean {
        if (angle == null) return false
        if (!isDown && angle < down) { isDown = true; wentDownAt = t }
        else if (isDown && angle > up) { isDown = false; if (t - wentDownAt >= minMs) { count++; return true } }
        return false
    }
    fun reset() { count = 0; isDown = false }
}

/** Fading light trails that follow moving points (hands, feet). Pure, so it is tested. */
class Trails(private val maxAgeMs: Long = 900, private val maxPoints: Int = 60) {
    private val lines = HashMap<Int, ArrayDeque<Triple<Float, Float, Long>>>()

    fun add(key: Int, x: Float, y: Float, t: Long) {
        val q = lines.getOrPut(key) { ArrayDeque() }
        q.addLast(Triple(x, y, t)); while (q.size > maxPoints) q.removeFirst()
    }

    /** Each trail's points oldest first, with how fresh each is (1 new, 0 about to vanish). A jump across the screen starts a new trail. */
    fun lines(now: Long): Map<Int, List<Triple<Float, Float, Float>>> {
        lines.values.forEach { q -> while (q.isNotEmpty() && now - q.first().third > maxAgeMs) q.removeFirst() }
        return lines.filterValues { it.size >= 2 }.mapValues { (_, q) -> q.map { Triple(it.first, it.second, 1f - (now - it.third).toFloat() / maxAgeMs) } }
    }
    fun clear() = lines.clear()
}

/** Sparks: little particles thrown from a point that fall and fade. The same seed gives the same sparks. Pure, so it is tested. */
class Sparks(seed: Long = 7, private val max: Int = 400) {
    class P(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val hue: Float)
    private val r = java.util.Random(seed)
    val all = ArrayList<P>()

    /** Throws [n] sparks from ([x], [y]); [speed] is how fast the source itself moves, so fast hands throw them further. */
    fun emit(x: Float, y: Float, n: Int, speed: Float = 0f, hue: Float = 30f) {
        repeat(n) {
            if (all.size >= max) all.removeAt(0)
            val a = r.nextFloat() * 6.2832f; val v = 0.05f + r.nextFloat() * (0.25f + speed)
            all += P(x, y, kotlin.math.cos(a) * v, kotlin.math.sin(a) * v - 0.15f, 1f, hue + r.nextFloat() * 30f)
        }
    }

    /** Moves everything on by [dt] seconds: gravity pulls down, life runs out in about a second. */
    fun step(dt: Float) {
        val it = all.iterator()
        while (it.hasNext()) { val p = it.next(); p.vy += 0.9f * dt; p.x += p.vx * dt; p.y += p.vy * dt; p.life -= dt * 1.1f; if (p.life <= 0f || p.y > 1.2f) it.remove() }
    }
}

/** Looks for the whole picture: each is a 4x5 colour matrix (red, green, blue, alpha rows), the form Android's ColorMatrix takes. */
object CameraFilters {
    private fun sat(s: Float): FloatArray {
        val r = 0.213f * (1 - s); val g = 0.715f * (1 - s); val b = 0.072f * (1 - s)
        return floatArrayOf(r + s, g, b, 0f, 0f, r, g + s, b, 0f, 0f, r, g, b + s, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
    }
    private fun contrast(m: FloatArray, c: Float): FloatArray { val o = m.copyOf(); val t = 127.5f * (1 - c); for (row in 0..2) { for (k in 0..3) o[row * 5 + k] *= c; o[row * 5 + 4] = o[row * 5 + 4] * c + t }; return o }
    private fun tint(m: FloatArray, r: Float, g: Float, b: Float): FloatArray { val o = m.copyOf(); for (k in 0..4) { o[k] *= r; o[5 + k] *= g; o[10 + k] *= b }; return o }

    val IDENTITY = sat(1f)
    val all: List<Pair<String, FloatArray>> = listOf(
        "None" to IDENTITY,
        "Vivid" to contrast(sat(1.6f), 1.1f),
        "Mono" to sat(0f),
        "Noir" to contrast(sat(0f), 1.6f),
        "Sepia" to floatArrayOf(0.393f, 0.769f, 0.189f, 0f, 0f, 0.349f, 0.686f, 0.168f, 0f, 0f, 0.272f, 0.534f, 0.131f, 0f, 0f, 0f, 0f, 0f, 1f, 0f),
        "Warm" to tint(sat(1.15f), 1.12f, 1.0f, 0.86f),
        "Cool" to tint(sat(1.1f), 0.88f, 1.0f, 1.15f),
        "Night" to tint(contrast(sat(0f), 1.3f), 0.25f, 1.25f, 0.3f),
        "Heat" to floatArrayOf(1.6f, 0.6f, 0f, 0f, -40f, 0f, 0.9f, 0f, 0f, -20f, -0.6f, -0.3f, 1.2f, 0f, 60f, 0f, 0f, 0f, 1f, 0f),
        "Negative" to floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f),
    )

    /** What a filter does to one colour (0..255 each), for checking a look without a screen. */
    fun apply(m: FloatArray, r: Float, g: Float, b: Float): Triple<Float, Float, Float> = Triple(
        (m[0] * r + m[1] * g + m[2] * b + m[4]).coerceIn(0f, 255f), (m[5] * r + m[6] * g + m[7] * b + m[9]).coerceIn(0f, 255f), (m[10] * r + m[11] * g + m[12] * b + m[14]).coerceIn(0f, 255f))
}

/** The body effects on offer. [parts] says what each one decorates, so the picker can say "hands", "feet", "whole body". */
enum class BodyEffect(val label: String, val parts: String) {
    Neon("⚡ Neon body", "whole body"), Trails("✨ Light trails", "hands and feet"), Fire("🔥 Fire hands", "hands"),
    Kicks("👟 Spark feet", "feet"), Costume("👑 Costume", "head, hands, feet"), Coach("📐 Coach", "angles and reps"),
}
