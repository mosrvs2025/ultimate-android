package app.ultimate.air

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/** Your whole body as the camera sees it: 33 points (MediaPipe Pose order), each x, y in 0..1 of the picture and how sure it is. */
data class BodyPose(val points: List<Triple<Float, Float, Float>>, val at: Long) {
    /** Legs and feet are harder to see, most of all from further back, so they are trusted at a lower score than the rest. */
    fun ok(i: Int) = i in points.indices && points[i].third >= (if (i >= L_HIP) SURE_LOW else SURE)
    /** Point [i] on screen, mirrored when the front camera is used; null when it is not clearly seen. */
    fun at(i: Int, mirror: Boolean): Pair<Float, Float>? = if (ok(i)) (if (mirror) 1f - points[i].first else points[i].first) to points[i].second else null

    companion object {
        const val SURE = 0.5f; const val SURE_LOW = 0.25f
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
class RepCounter(private val down: Float = 105f, private val up: Float = 150f, private val minMs: Long = 300) {
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
    Kicks("👟 Spark feet", "feet"), Blast("💥 Energy blast", "both hands"), Outfit("👕 Outfits", "whole body"),
    Costume("👑 Costume", "head, hands, feet"), Coach("📐 Coach", "angles and reps"),
}

/** The 21 points of a hand joined into fingers (MediaPipe order), for drawing a hand on top of a body. */
val HAND_BONES = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4, 0 to 5, 5 to 6, 6 to 7, 7 to 8, 5 to 9, 9 to 10, 10 to 11, 11 to 12, 9 to 13, 13 to 14, 14 to 15, 15 to 16, 13 to 17, 17 to 18, 18 to 19, 19 to 20, 0 to 17)

/** A ball of energy let go from between your hands: where it started, which way it flies, and how charged it was (0..1). */
data class BlastShot(val x: Float, val y: Float, val dx: Float, val dy: Float, val power: Float)

/**
 * Whole-body gestures you can do from across the room, so the phone can be worked without touching it:
 *  - **Swipe**: raise a hand above your shoulder and sweep it sideways. Next or previous.
 *  - **Menu**: hold both hands above your head for a moment. Switches what the swipe changes.
 *  - **Blast**: bring both hands together in front of you to charge (the longer, the bigger), then throw them apart to fire.
 * Distances are measured in shoulder widths, so it works the same near or far. Each gesture has a pause after it so one
 * movement is one command. Pure, so it is tested.
 */
class BodyGestures(private val swipeWidths: Float = 1.1f, private val swipeMs: Long = 450, private val menuHoldMs: Long = 600, private val chargeMs: Long = 1200) {
    enum class Kind { SwipeLeft, SwipeRight, Menu }
    sealed interface Event { data class Did(val kind: Kind) : Event; data class Fired(val shot: BlastShot) : Event }

    /** How charged the blast is right now (0 when your hands aren't together), and where it sits. */
    var charge = 0f; private set
    var chargeAt: Pair<Float, Float>? = null; private set

    private val track = HashMap<Int, ArrayDeque<Triple<Float, Float, Long>>>()
    private var quietUntil = 0L
    private var bothUpSince = -1L; private var menuArmed = true
    private var togetherSince = -1L

    fun feed(p: BodyPose, mirror: Boolean, t: Long): List<Event> {
        val out = ArrayList<Event>()
        val unit = Joints.scale(p) ?: run { reset(); return out }
        val lw = p.at(BodyPose.L_WRIST, mirror); val rw = p.at(BodyPose.R_WRIST, mirror)
        val ls = p.at(BodyPose.L_SHOULDER, mirror)!!; val rs = p.at(BodyPose.R_SHOULDER, mirror)!!
        val headY = p.at(BodyPose.NOSE, mirror)?.second ?: (minOf(ls.second, rs.second) - unit * 0.6f)

        // Blast: hands together below the head charge it; throwing them apart lets it go.
        val together = lw != null && rw != null && hypot(lw.first - rw.first, lw.second - rw.second) < unit * 0.6f && minOf(lw.second, rw.second) > headY
        if (together) {
            if (togetherSince < 0) togetherSince = t
            charge = ((t - togetherSince).toFloat() / chargeMs).coerceIn(0f, 1f)
            chargeAt = (lw!!.first + rw!!.first) / 2 to (lw.second + rw.second) / 2
        } else {
            val at = chargeAt
            if (charge >= 0.25f && at != null && lw != null && rw != null && hypot(lw.first - rw.first, lw.second - rw.second) > unit * 1.1f) {
                val cx = (ls.first + rs.first) / 2; val cy = (ls.second + rs.second) / 2
                var dx = at.first - cx; var dy = at.second - cy; val d = hypot(dx, dy)
                if (d < unit * 0.25f) { dx = 0f; dy = -1f } else { dx /= d; dy /= d }
                out += Event.Fired(BlastShot(at.first, at.second, dx, dy, charge)); quietUntil = t + 600
            }
            togetherSince = -1; charge = 0f; chargeAt = null
        }

        // Menu: both hands held above the head. You have to bring them down before it can fire again.
        val bothUp = lw != null && rw != null && lw.second < headY && rw.second < headY
        if (bothUp) {
            if (bothUpSince < 0) bothUpSince = t
            if (menuArmed && t - bothUpSince >= menuHoldMs && t >= quietUntil) { out += Event.Did(Kind.Menu); menuArmed = false; quietUntil = t + 700 }
        } else { bothUpSince = -1; menuArmed = true }

        // Swipe: one raised hand moving sideways fast. Not while charging or holding the menu pose.
        for ((key, w, sh) in listOf(Triple(BodyPose.L_WRIST, lw, ls), Triple(BodyPose.R_WRIST, rw, rs))) {
            val q = track.getOrPut(key) { ArrayDeque() }
            if (w == null || w.second > sh.second || together || bothUp) { q.clear(); continue }
            q.addLast(Triple(w.first, w.second, t)); while (q.isNotEmpty() && t - q.first().third > swipeMs) q.removeFirst()
            val first = q.first(); val dx = w.first - first.first; val dy = w.second - first.second
            if (t >= quietUntil && abs(dx) > unit * swipeWidths && abs(dx) > abs(dy) * 1.5f) {
                out += Event.Did(if (dx > 0) Kind.SwipeRight else Kind.SwipeLeft); quietUntil = t + 800; track.values.forEach { it.clear() }
            }
        }
        return out
    }

    fun reset() { track.clear(); bothUpSince = -1; togetherSince = -1; charge = 0f; chargeAt = null }
}

/** Energy balls in flight: they fly straight, grow a little and fade. Pure, so it is tested. */
class Blasts {
    class B(var x: Float, var y: Float, val dx: Float, val dy: Float, val power: Float, var life: Float)
    val all = ArrayList<B>()
    fun fire(s: BlastShot) { all += B(s.x, s.y, s.dx, s.dy, s.power, 1f); if (all.size > 12) all.removeAt(0) }
    fun step(dt: Float) { val it = all.iterator(); while (it.hasNext()) { val b = it.next(); val v = 0.9f + b.power; b.x += b.dx * v * dt; b.y += b.dy * v * dt; b.life -= dt * 0.8f; if (b.life <= 0f || b.x < -0.3f || b.x > 1.3f || b.y < -0.3f || b.y > 1.3f) it.remove() } }
}

/** Clothes drawn on your body. Colours are ARGB; [sleeves] 0 none, 1 short, 2 long; [legs] 0 none, 1 shorts, 2 trousers. */
data class Outfit(val label: String, val top: Long?, val sleeves: Int, val bottom: Long?, val legs: Int, val cape: Long? = null, val trim: Long? = null)

object Outfits {
    val all = listOf(
        Outfit("👕 Tee and jeans", 0xFFE53935, 1, 0xFF1E3A8A, 2),
        Outfit("🧥 Hoodie and joggers", 0xFF455A64, 2, 0xFF263238, 2, trim = 0xFFECEFF1),
        Outfit("🩳 Summer", 0xFFFFC107, 1, 0xFF00ACC1, 1),
        Outfit("🤵 Suit", 0xFF212121, 2, 0xFF212121, 2, trim = 0xFFFFFFFF),
        Outfit("🦸 Hero", 0xFF1565C0, 2, 0xFFC62828, 2, cape = 0xFFC62828, trim = 0xFFFFD600),
        Outfit("🥷 Ninja", 0xFF111111, 2, 0xFF111111, 2, trim = 0xFFB71C1C),
        Outfit("🤖 Armour", 0xFF90A4AE, 2, 0xFF607D8B, 2, trim = 0xFF00E5FF),
        Outfit("🥋 Gi", 0xFFFAFAFA, 2, 0xFFFAFAFA, 2, trim = 0xFF111111),
    )

    /** The torso as a four-corner shape (shoulders then hips), a little wider than the joints so it reads as cloth; null if not all seen. */
    fun torso(p: BodyPose, mirror: Boolean, widen: Float = 0.18f): List<Pair<Float, Float>>? {
        val ls = p.at(BodyPose.L_SHOULDER, mirror) ?: return null; val rs = p.at(BodyPose.R_SHOULDER, mirror) ?: return null
        val lh = p.at(BodyPose.L_HIP, mirror) ?: return null; val rh = p.at(BodyPose.R_HIP, mirror) ?: return null
        fun out(a: Pair<Float, Float>, b: Pair<Float, Float>) = (a.first + (a.first - b.first) * widen) to (a.second + (a.second - b.second) * widen * 0.2f)
        return listOf(out(ls, rs), out(rs, ls), out(rh, lh), out(lh, rh))
    }
}
