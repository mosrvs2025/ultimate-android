package app.ultimate.air

import kotlin.math.hypot

/**
 * The desk piano: a row of keys lies along the desk, and a fingertip that comes down through the desk line plays the
 * key under it. Each of the five fingertips is tracked on its own, and a finger has to lift clearly above the line
 * before it can play again, so resting on a key doesn't repeat it. The camera sees the desk from above (picture
 * coordinates, 0..1, y down). Pure, so it is tested.
 */
class DeskPiano(val keys: Int = 8, private val left: Float = 0.06f, private val right: Float = 0.94f, private val lift: Float = 0.035f,
                private val minSpeed: Float = 0.004f) {
    /** Where the desk is in the picture (y), once set; in pad mode, the lowest finger's rest. */
    var line: Float? = null; private set
    /** Pad mode: each finger plays its own note, on a pad where that finger rests (x, y), thumb first. */
    var pads: List<Pair<Float, Float>>? = null; private set
    private val armed = BooleanArray(5)
    private val lastY = FloatArray(5) { Float.NaN }

    /** Sets the desk from fingertips resting on it. In [padMode], each finger gets a pad right where it rests. */
    fun setDesk(tips: List<Pair<Float, Float>>, padMode: Boolean = false) {
        line = tips.map { it.second }.sorted().let { it[it.size / 2] } - 0.01f
        pads = if (padMode) tips.take(5) else null
        armed.fill(false); lastY.fill(Float.NaN)
    }

    /** Which key is under picture position [x], or null off the keyboard. */
    fun keyAt(x: Float): Int? = ((x - left) / (right - left) * keys).toInt().takeIf { x in left..right }?.coerceIn(0, keys - 1)

    /**
     * Feeds the five fingertips (thumb first, null when not seen); returns the keys struck now. A strike is a finger
     * that lifted clearly, then came down to the desk while moving down (a tap), not one that drifted there.
     */
    fun feed(tips: List<Pair<Float, Float>?>): List<Int> {
        val l = line ?: return emptyList()
        val out = ArrayList<Int>()
        tips.forEachIndexed { i, t ->
            if (i >= 5) return@forEachIndexed
            if (t == null) { armed[i] = false; lastY[i] = Float.NaN; return@forEachIndexed }
            val desk = pads?.getOrNull(i)?.second ?: l
            val speed = if (lastY[i].isNaN()) 0f else t.second - lastY[i]
            lastY[i] = t.second
            // The thumb moves less up and down than the fingers (it swings sideways), so it gets half the lift and speed to count.
            val k = if (i == 0) 0.5f else 1f
            if (t.second < desk - lift * k) armed[i] = true
            else if (armed[i] && t.second >= desk - lift * 0.3f * k && speed >= minSpeed * k) {
                armed[i] = false
                (if (pads != null) i else keyAt(t.first))?.let(out::add)
            }
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
class FaceNavigator(private val margin: Float = 0.18f, private val nodMargin: Float = 0.06f, private val selectMs: Long = 700, private val backMs: Long = 900,
                    private val repeatMs: Long = 700, private val gapMs: Long = 350) {
    enum class Move { Up, Down, Select, Back }
    private val rest = HashMap<String, Float>()
    private var active: String? = null
    private var since = 0L
    private var lastMove = Long.MIN_VALUE / 2
    private var done = false
    /** What's being held right now and how far along a hold-to-choose is (0..1), for the screen to show. */
    var holding: Pair<String, Float>? = null; private set
    /** How close each move is to counting (0..1): "up", "down", "select", "back", for live meters. */
    var levels: Map<String, Float> = emptyMap(); private set

    /**
     * [pitch] is optional: how far the nose sits between the eyes and the chin (from the face mesh). Tilting the head
     * back or forward moves it, so nodding scrolls too.
     */
    fun feed(scores: Map<String, Float>, now: Long, pitch: Float? = null): Move? {
        fun s(n: String) = scores[n] ?: 0f
        val raw = HashMap<String, Float>().apply {
            put("brow_up", maxOf((s("browInnerUp") + (s("browOuterUpLeft") + s("browOuterUpRight")) / 2) / 2, (s("eyeLookUpLeft") + s("eyeLookUpRight")) / 2))
            put("brow_down", maxOf((s("browDownLeft") + s("browDownRight")) / 2, (s("eyeLookDownLeft") + s("eyeLookDownRight")) / 2))
            // Mouth open or tongue out: either chooses.
            put("select", maxOf(s("jawOpen"), s("tongueOut")))
            put("back", minOf(s("eyeBlinkLeft"), s("eyeBlinkRight")))
            if (pitch != null) { put("nod_up", -pitch); put("nod_down", pitch) }
        }
        // How far above your resting level each one is, as a share of what counts.
        fun over(k: String, v: Float) = if (k == "back") ((v - 0.3f) / 0.3f) else (v - (rest[k] ?: v)) / (if (k.startsWith("nod")) nodMargin else margin)
        val share = raw.mapValues { (k, v) -> over(k, v) }
        val chan = mapOf("up" to listOf("brow_up", "nod_up"), "down" to listOf("brow_down", "nod_down"), "select" to listOf("select"), "back" to listOf("back"))
        val lv = chan.mapValues { (_, ks) -> ks.maxOf { share[it] ?: 0f }.coerceIn(0f, 1f) }
        levels = lv
        val on = lv.filter { it.value >= 1f }.maxByOrNull { it.value }?.key
        // Keep learning the resting face from channels that aren't being used right now.
        raw.forEach { (k, v) -> if (chan[on]?.contains(k) != true) rest[k] = rest[k]?.let { it + 0.03f * (v - it) } ?: v }
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

    companion object {
        /** Where the nose tip sits between the eyes and the chin (0..1); tilting the head back makes it smaller. */
        fun pitch(points: List<Pair<Float, Float>>): Float? {
            if (points.size < 400) return null
            val eyes = (points[33].second + points[263].second) / 2; val chin = points[152].second
            return ((points[1].second - eyes) / (chin - eyes).coerceAtLeast(1e-3f))
        }
    }
}

/**
 * Sees a tongue sticking out. The face tracker has no tongue, so this looks at the picture itself: the strip just
 * below the lower lip turns pink-red when the tongue is out, compared with your own skin (cheeks and chin) in the same
 * light. It learns your resting difference in the first moments, so lipstick, beards and lighting don't matter much.
 * [feed] takes a colour sampler over the picture (0..1 coordinates) and the face mesh points; returns 0..1.
 * Pure, so it is tested.
 */
class TongueDetector(private val span: Float = 0.06f, private val openAt: Float = 0.05f) {
    private var base: Float? = null
    private var smooth = 0f

    /** How far the mouth is open, as a fraction of the face's height (inner lips, then forehead to chin). 0 when the mesh isn't whole. */
    fun mouthOpen(points: List<Pair<Float, Float>>): Float {
        if (points.size < 400) return 0f
        val h = hypot(points[10].first - points[152].first, points[10].second - points[152].second).coerceAtLeast(1e-3f)
        return hypot(points[13].first - points[14].first, points[13].second - points[14].second) / h
    }

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
        // A tongue needs an open mouth. With the mouth shut the strip is only skin, so that is when the resting difference is
        // learned (quickly), and nothing is reported. Learning it from whatever the first frame happened to show, and never again
        // while the reading was high, left some faces stuck on "tongue out" for good.
        if (mouthOpen(points) < openAt) {
            base = base?.let { it + 0.15f * (d - it) } ?: d
            smooth *= 0.5f
            return 0f
        }
        val b = base ?: return 0f
        val raw = ((d - b) / span).coerceIn(0f, 1f)
        // Smoothed, so one odd frame can't flash it.
        smooth += 0.4f * (raw - smooth)
        return if (smooth < 0.35f) 0f else smooth
    }
}
