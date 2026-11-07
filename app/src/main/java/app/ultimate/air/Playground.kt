package app.ultimate.air

import kotlin.math.hypot

/**
 * The desk piano: a row of keys lies along the desk, and a fingertip that comes down through the desk line plays the
 * key under it. Each of the five fingertips is tracked on its own, and a finger has to lift clearly above the line
 * before it can play again, so resting on a key doesn't repeat it. The camera sees the desk from above (picture
 * coordinates, 0..1, y down). Pure, so it is tested.
 */
class DeskPiano(val keys: Int = 8, private val left: Float = 0.06f, private val right: Float = 0.94f, private val lift: Float = 0.035f) {
    /** Where the desk is in the picture (y), once set. */
    var line: Float? = null; private set
    private val armed = BooleanArray(5)

    /** Sets the desk from fingertips resting on it: the line sits just above where they rest. */
    fun setDesk(tips: List<Pair<Float, Float>>) { line = tips.map { it.second }.sorted().let { it[it.size / 2] } - 0.01f; armed.fill(false) }

    /** Which key is under picture position [x], or null off the keyboard. */
    fun keyAt(x: Float): Int? = ((x - left) / (right - left) * keys).toInt().takeIf { x in left..right }?.coerceIn(0, keys - 1)

    /** Feeds the five fingertips (thumb first, null when not seen); returns the keys struck now. */
    fun feed(tips: List<Pair<Float, Float>?>): List<Int> {
        val l = line ?: return emptyList()
        val out = ArrayList<Int>()
        tips.forEachIndexed { i, t ->
            if (i >= 5) return@forEachIndexed
            if (t == null) { armed[i] = false; return@forEachIndexed }
            if (t.second < l - lift) armed[i] = true
            else if (armed[i] && t.second >= l) { armed[i] = false; keyAt(t.first)?.let(out::add) }
        }
        return out
    }

    companion object {
        /** The fingertips in a hand's 21 points: thumb, index, middle, ring, little. */
        val TIPS = listOf(4, 8, 12, 16, 20)
        /** C major from middle C up an octave, in hertz, and the names. */
        val NOTES = listOf(261.63, 293.66, 329.63, 349.23, 392.00, 440.00, 493.88, 523.25)
        val NAMES = listOf("C", "D", "E", "F", "G", "A", "B", "C")
    }
}

/**
 * Drawing in the air: pinch to put the pen down, move to draw, open to lift it. Strokes are kept in picture
 * coordinates, so they stay where you drew them. Pure, so it is tested.
 */
class AirCanvas {
    data class Stroke(val color: Long, val points: MutableList<Pair<Float, Float>> = ArrayList())
    val strokes = ArrayList<Stroke>()
    var drawing = false; private set

    /** Feeds the pen tip and the pinch gap (thumb to index over hand size); closed below 0.3, open above 0.45. */
    fun feed(x: Float, y: Float, gap: Float, color: Long) {
        if (!drawing && gap < 0.3f) { drawing = true; strokes += Stroke(color) }
        if (drawing && gap > 0.45f) drawing = false
        if (drawing) {
            val pts = strokes.last().points
            // Skip tiny moves, so a still pinch doesn't pile up points.
            if (pts.isEmpty() || hypot(pts.last().first - x, pts.last().second - y) > 0.004f) pts += x to y
        }
    }
    fun lost() { drawing = false }
    fun undo() { strokes.removeLastOrNull(); drawing = false }
    fun clear() { strokes.clear(); drawing = false }
}

/**
 * Drawing on your face: points are kept relative to the face (from the nose, in face widths, turned with the head),
 * so a drawing stays on your cheek as you move, tilt and come closer. Pure, so it is tested.
 */
object FaceFrame {
    /** The face's origin (nose tip), size (cheek to cheek) and tilt, from the face mesh's points. */
    data class Frame(val ox: Float, val oy: Float, val scale: Float, val cos: Float, val sin: Float)

    fun of(points: List<Pair<Float, Float>>): Frame? {
        if (points.size < 455) return null
        val (nx, ny) = points[1]; val (lx, ly) = points[234]; val (rx, ry) = points[454]
        val w = hypot(rx - lx, ry - ly).coerceAtLeast(1e-4f)
        return Frame(nx, ny, w, (rx - lx) / w, (ry - ly) / w)
    }
    /** A picture point into face coordinates. */
    fun toFace(f: Frame, x: Float, y: Float): Pair<Float, Float> {
        val dx = (x - f.ox) / f.scale; val dy = (y - f.oy) / f.scale
        return (dx * f.cos + dy * f.sin) to (-dx * f.sin + dy * f.cos)
    }
    /** A face point back into the picture. */
    fun toPicture(f: Frame, u: Float, v: Float): Pair<Float, Float> =
        (f.ox + (u * f.cos - v * f.sin) * f.scale) to (f.oy + (u * f.sin + v * f.cos) * f.scale)
}

/**
 * Moving through a list with your face alone, for hands-free calling: raise your eyebrows (or look up) to go up,
 * lower them (or look down) to go down, hold your mouth open to choose, and close both eyes for a moment to go back.
 * Each move is measured against your own resting face, which it keeps learning while you're still, and holding a
 * move repeats it slowly so long lists are quick. Pure, so it is tested.
 */
class FaceNavigator(private val margin: Float = 0.3f, private val selectMs: Long = 700, private val backMs: Long = 900,
                    private val repeatMs: Long = 700, private val gapMs: Long = 350) {
    enum class Move { Up, Down, Select, Back }
    private val rest = HashMap<String, Float>()
    private var active: String? = null
    private var since = 0L
    private var lastMove = Long.MIN_VALUE / 2
    private var done = false
    /** What's being held right now and how far along a hold-to-choose is (0..1), for the screen to show. */
    var holding: Pair<String, Float>? = null; private set

    fun feed(scores: Map<String, Float>, now: Long): Move? {
        fun s(n: String) = scores[n] ?: 0f
        val raw = mapOf(
            "up" to maxOf((s("browInnerUp") + (s("browOuterUpLeft") + s("browOuterUpRight")) / 2) / 2, (s("eyeLookUpLeft") + s("eyeLookUpRight")) / 2),
            "down" to maxOf((s("browDownLeft") + s("browDownRight")) / 2, (s("eyeLookDownLeft") + s("eyeLookDownRight")) / 2),
            // Mouth open or tongue out: either chooses.
            "select" to maxOf(s("jawOpen"), s("tongueOut")),
            "back" to minOf(s("eyeBlinkLeft"), s("eyeBlinkRight")),
        )
        // Above your resting level by the margin counts; eyes closed is judged on the raw score (they rest open).
        val on = raw.filter { (k, v) -> if (k == "back") v > 0.6f else v - (rest[k] ?: v) > margin }.maxByOrNull { (k, v) -> v - (rest[k] ?: 0f) }?.key
        raw.forEach { (k, v) -> if (k != on) rest[k] = rest[k]?.let { it + 0.03f * (v - it) } ?: v }
        if (on != active) { active = on; since = now; done = false }
        val held = now - since
        holding = when (on) { "select" -> on to (held.toFloat() / selectMs).coerceAtMost(1f); "back" -> on to (held.toFloat() / backMs).coerceAtMost(1f); null -> null; else -> on to 1f }
        if (on == null || now - lastMove < gapMs) return null
        val move = when (on) {
            "up", "down" -> if (!done || held >= repeatMs && now - lastMove >= repeatMs) (if (on == "up") Move.Up else Move.Down) else null
            "select" -> if (!done && held >= selectMs) Move.Select else null
            else -> if (!done && held >= backMs) Move.Back else null
        } ?: return null
        done = true; lastMove = now
        return move
    }
}

/**
 * Sees a tongue sticking out. The face tracker has no tongue, so this looks at the picture itself: the strip just
 * below the lower lip turns pink-red when the tongue is out, compared with your own skin (cheeks and chin) in the same
 * light. It learns your resting difference in the first moments, so lipstick, beards and lighting don't matter much.
 * [feed] takes a colour sampler over the picture (0..1 coordinates) and the face mesh points; returns 0..1.
 * Pure, so it is tested.
 */
class TongueDetector(private val span: Float = 0.06f) {
    private var base: Float? = null

    fun feed(points: List<Pair<Float, Float>>, rgb: (Float, Float) -> Int): Float {
        if (points.size < 400) return 0f
        fun red(x: Float, y: Float): Float { val c = rgb(x, y); val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255; return r / (r + g + b + 1f) }
        fun lerp(a: Pair<Float, Float>, b: Pair<Float, Float>, t: Float) = (a.first + (b.first - a.first) * t) to (a.second + (b.second - a.second) * t)
        val lip = points[17]; val chin = points[152]
        val w = (points[291].first - points[61].first) * 0.25f
        // Where a tongue shows: the upper part of the strip between the lower lip and the chin, across the mouth's middle.
        val region = listOf(0.2f, 0.4f).flatMap { t -> val c = lerp(lip, chin, t); listOf(-w, 0f, w).map { dx -> red(c.first + dx, c.second) } }.average().toFloat()
        val skin = listOf(points[50], points[280], lerp(lip, chin, 0.85f)).map { red(it.first, it.second) }.average().toFloat()
        val d = region - skin
        val b = base ?: d.also { base = it }
        val level = ((d - b) / span).coerceIn(0f, 1f)
        // Learn the resting difference only while the tongue is clearly in.
        if (level < 0.3f) base = b + 0.03f * (d - b)
        return level
    }
}
