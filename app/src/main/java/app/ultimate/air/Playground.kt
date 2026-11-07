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
