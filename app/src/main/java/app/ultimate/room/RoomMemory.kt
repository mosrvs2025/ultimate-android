package app.ultimate.room

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** A thing found in the room: what the detector called it, where it is, how many times it was seen, and how sure the detector was at best. */
data class RoomObject(val label: String, val at: P3, val seen: Int, val best: Float)

/**
 * Remembers objects across many camera frames. One frame's detection is not trusted: the same label seen again within [merge] metres
 * is the same object (its position becomes the running average), and it only counts once it has been seen [confirmAt] times. Pure, so
 * it is tested.
 */
class ObjectMemory(private val merge: Float = 0.7f, private val confirmAt: Int = 3) {
    private val all = ArrayList<RoomObject>()

    fun see(label: String, at: P3, score: Float) {
        val i = all.indices.filter { all[it].label == label && all[it].at.dist(at) <= merge }.minByOrNull { all[it].at.dist(at) }
        if (i == null) { all += RoomObject(label, at, 1, score); return }
        val o = all[i]; val n = o.seen + 1
        all[i] = o.copy(at = P3((o.at.x * o.seen + at.x) / n, (o.at.y * o.seen + at.y) / n, (o.at.z * o.seen + at.z) / n), seen = n, best = maxOf(o.best, score))
    }

    fun confirmed(): List<RoomObject> = all.filter { it.seen >= confirmAt }.sortedByDescending { it.seen }
    fun clear() = all.clear()
}

/** How a new scan sits relative to a saved room: turn by [yawDeg] about the vertical, then shift. [score] is the share of the new scan's wall cells that land on saved ones. */
data class Alignment(val yawDeg: Float, val dx: Float, val dy: Float, val dz: Float, val score: Float) {
    /** A point of the new session, expressed in the saved room's coordinates. */
    /** The other way: a point of the saved room, expressed in the new session's coordinates (to show remembered things in the camera view). */
    fun invert(p: P3): P3 { val a = Math.toRadians(yawDeg.toDouble()); val c = cos(a).toFloat(); val s = sin(a).toFloat(); val x = p.x - dx; val z = p.z - dz; return P3(x * c + z * s, p.y - dy, -x * s + z * c) }
    fun apply(p: P3): P3 { val a = Math.toRadians(yawDeg.toDouble()); val c = cos(a).toFloat(); val s = sin(a).toFloat(); return P3(p.x * c - p.z * s + dx, p.y + dy, p.x * s + p.z * c + dz) }
}

/**
 * Recognising a room you scanned before, with nothing but its shape, on the phone. Gravity already fixes which way is up, so only a
 * turn about the vertical and a shift are unknown. Both scans are flattened to a top-down plan of their walls and furniture (points
 * above the floor). For each trial turn, every pairing of a new point with a saved point votes for the shift that would put one on the
 * other; the true shift collects far more votes than any other (a generalised Hough transform), which works even when the new scan
 * covers only part of the room. The best few are then checked exactly. Pure, so it is tested.
 */
object Relocalizer {
    const val CELL = 0.1f

    private fun plan(points: List<P3>, floorY: Float): List<Pair<Float, Float>> =
        points.filter { it.y > floorY + 0.3f && it.y < floorY + 2.2f }.map { floor(it.x / CELL).toInt() to floor(it.z / CELL).toInt() }.distinct().map { (it.first + 0.5f) * CELL to (it.second + 0.5f) * CELL }

    /** Null when either scan is too small, or nothing lines up well enough ([minScore]) to honestly call it the same room. */
    fun align(fresh: List<P3>, freshFloorY: Float, saved: List<P3>, savedFloorY: Float, minScore: Float = 0.55f, yawStep: Int = 5): Alignment? {
        val a = plan(fresh, freshFloorY); val b = plan(saved, savedFloorY)
        if (a.size < 60 || b.size < 60) return null
        val savedSet = b.map { floor(it.first / CELL).toInt() to floor(it.second / CELL).toInt() }.toHashSet()
        fun sample(l: List<Pair<Float, Float>>, n: Int) = if (l.size <= n) l else l.indices.step((l.size / n).coerceAtLeast(1)).map { l[it] }
        val sa = sample(a, 250); val sb = sample(b, 500)
        var best: Alignment? = null
        for (deg in 0 until 360 step yawStep) {
            val r = Math.toRadians(deg.toDouble()); val c = cos(r).toFloat(); val s = sin(r).toFloat()
            val rot = sa.map { (x, z) -> (x * c - z * s) to (x * s + z * c) }
            val votes = HashMap<Long, Int>()
            for ((ax, az) in rot) for ((bx, bz) in sb) {
                val tx = floor((bx - ax) / CELL + 0.5f).toInt(); val tz = floor((bz - az) / CELL + 0.5f).toInt()
                votes.merge((tx.toLong() shl 32) or (tz.toLong() and 0xFFFFFFFFL), 1, Int::plus)
            }
            // Check the three most-voted shifts exactly, against every point of the new plan.
            val full = a.map { (x, z) -> (x * c - z * s) to (x * s + z * c) }
            for ((k, _) in votes.entries.sortedByDescending { it.value }.take(3)) {
                val tx = (k shr 32).toInt() * CELL; val tz = k.toInt() * CELL
                var hit = 0
                for ((x, z) in full) { val ix = floor((x + tx) / CELL).toInt(); val iz = floor((z + tz) / CELL).toInt()
                    if ((ix to iz) in savedSet || (ix + 1 to iz) in savedSet || (ix - 1 to iz) in savedSet || (ix to iz + 1) in savedSet || (ix to iz - 1) in savedSet) hit++ }
                val score = hit.toFloat() / full.size
                if (best == null || score > best!!.score) best = Alignment(deg.toFloat(), tx, savedFloorY - freshFloorY, tz, score)
            }
        }
        return best?.takeIf { it.score >= minScore }
    }
}

/** Looking at a saved room from outside: orbit round its middle and project to the screen. Pure, so it is tested. */
object Orbit {
    /** Screen position (pixels from the centre, y down) and depth for sorting, for a point seen from [yawDeg] round and [pitchDeg] above, [zoom] pixels per metre. */
    fun project(p: P3, centre: P3, yawDeg: Float, pitchDeg: Float, zoom: Float): Triple<Float, Float, Float> {
        val d = p - centre
        val y = Math.toRadians(yawDeg.toDouble()); val cy = cos(y).toFloat(); val sy = sin(y).toFloat()
        val x1 = d.x * cy - d.z * sy; val z1 = d.x * sy + d.z * cy
        val t = Math.toRadians(pitchDeg.toDouble()); val ct = cos(t).toFloat(); val st = sin(t).toFloat()
        val y2 = d.y * ct - z1 * st; val z2 = d.y * st + z1 * ct
        return Triple(x1 * zoom, -y2 * zoom, z2)
    }
}

/** Turning a camera picture upright and back. [rot] is how far the sensor picture must be turned clockwise (0, 90, 180, 270) to look upright. Pure, so it is tested. */
object Upright {
    /** Which turn makes the picture upright, from where the sensor picture's x axis points on screen ([dx], [dy] in view coordinates, y down). */
    fun rotation(dx: Float, dy: Float): Int = if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) (if (dx >= 0) 0 else 180) else (if (dy >= 0) 90 else 270)

    /** A pixel of the upright picture, back in the sensor picture ([w] by [h] is the sensor picture's size). */
    fun toSensor(ux: Float, uy: Float, rot: Int, w: Int, h: Int): Pair<Float, Float> = when (rot) {
        90 -> uy to (h - 1 - ux); 180 -> (w - 1 - ux) to (h - 1 - uy); 270 -> (w - 1 - uy) to ux; else -> ux to uy
    }
}

/** The household things worth remembering, out of what the detector knows (people and vehicles are left out on purpose). */
object RoomLabels {
    val KEEP = setOf("chair", "couch", "bed", "dining table", "tv", "laptop", "potted plant", "refrigerator", "microwave", "oven", "toaster", "sink", "toilet", "book", "clock", "vase",
        "bottle", "cup", "bowl", "keyboard", "mouse", "remote", "cell phone", "backpack", "handbag", "suitcase", "bench", "teddy bear", "umbrella", "scissors", "hair drier")
    fun emoji(label: String) = when (label) { "chair", "bench" -> "🪑"; "couch" -> "🛋️"; "bed" -> "🛏️"; "dining table" -> "🍽️"; "tv" -> "📺"; "laptop", "keyboard", "mouse" -> "💻"; "potted plant", "vase" -> "🪴"
        "refrigerator" -> "🧊"; "microwave", "oven", "toaster" -> "🍳"; "sink" -> "🚰"; "toilet" -> "🚽"; "book" -> "📚"; "clock" -> "🕒"; "bottle", "cup", "bowl" -> "🥤"; "remote", "cell phone" -> "📱"; "backpack", "handbag", "suitcase" -> "🎒"; else -> "📦" }
}
