package app.ultimate.wear

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A small pond: the surface of water as a grid of heights, moved by the classic two-buffer wave equation. A finger
 * dropped on it pushes the surface down in a soft bowl; the dent spreads out as rings, bounces off the round edge of the
 * watch, and slowly calms. [shade] turns the surface into light and colour so it looks like lit, moving water.
 *
 * Pure arithmetic over float arrays (no Android), so it is tested on its own and costs a few hundred microseconds a step
 * on a watch at the default 72 × 72.
 */
class Ripple(val n: Int = 72, private val damping: Float = 0.985f) {
    private var cur = FloatArray(n * n)
    private var prev = FloatArray(n * n)
    /** Cells outside the round face never move, so the rings reflect off the bezel the way water meets a glass rim. */
    private val wet = BooleanArray(n * n) { i ->
        val x = i % n - (n - 1) / 2f; val y = i / n - (n - 1) / 2f
        x * x + y * y <= (n / 2f - 1) * (n / 2f - 1)
    }

    fun height(x: Int, y: Int) = cur[y * n + x]

    /**
     * Presses the water at ([fx], [fy]) in 0..1 across the face, [radius] as a share of the face, [depth] how hard.
     * A soft cosine bowl, so the rings come out smooth instead of as a single spiky cell.
     */
    fun drop(fx: Float, fy: Float, radius: Float = 0.06f, depth: Float = 1f) {
        val cx = fx * (n - 1); val cy = fy * (n - 1); val r = max(1.5f, radius * n)
        val x0 = max(1, (cx - r).toInt()); val x1 = min(n - 2, (cx + r).toInt() + 1)
        val y0 = max(1, (cy - r).toInt()); val y1 = min(n - 2, (cy + r).toInt() + 1)
        for (y in y0..y1) for (x in x0..x1) {
            val d = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) / r
            if (d < 1f) { val i = y * n + x; if (wet[i]) cur[i] -= depth * 0.5f * (1f + kotlin.math.cos(Math.PI.toFloat() * d)) }
        }
    }

    /** Moves time on by one tick. */
    fun step() {
        for (y in 1 until n - 1) {
            val row = y * n
            for (x in 1 until n - 1) {
                val i = row + x
                if (!wet[i]) { prev[i] = 0f; continue }
                val v = (cur[i - 1] + cur[i + 1] + cur[i - n] + cur[i + n]) * 0.5f - prev[i]
                prev[i] = v * damping
            }
        }
        val t = cur; cur = prev; prev = t
    }

    /** How much the water is still moving, for tests and for idling the renderer once it is calm. */
    fun energy(): Float { var e = 0f; for (v in cur) e += v * v; return e }

    fun calm() { cur.fill(0f); prev.fill(0f) }

    /**
     * Fills [out] (n × n ARGB) with the lit surface: the slope bends a light from the upper left into highlights and
     * shadows, and deeper water leans from teal towards violet. Off the face it is black.
     */
    fun shade(out: IntArray) {
        for (y in 0 until n) for (x in 0 until n) {
            val i = y * n + x
            if (!wet[i]) { out[i] = 0xFF000000.toInt(); continue }
            val sx = cur[min(n - 1, i + 1)] - cur[max(0, i - 1)]
            val sy = cur[min(n * n - 1, i + n)] - cur[max(0, i - n)]
            val light = (0.5f - (sx + sy) * 1.6f).coerceIn(0f, 1f)
            val depth = (-cur[i] * 0.8f).coerceIn(-1f, 1f) * 0.5f + 0.5f
            // Base water: deep blue-black, tinted teal (0x3DDCD0) in the shallows and violet (0x8E7CFF) in the troughs.
            val tr = 0x3D + (0x8E - 0x3D) * depth; val tg = 0xDC + (0x7C - 0xDC) * depth; val tb = 0xD0 + (0xFF - 0xD0) * depth
            val k = 0.06f + 0.72f * light + 0.10f * (1f - kotlin.math.abs(depth - 0.5f) * 2f).let { 1f - it }
            val spec = ((light - 0.6f) * 4f).coerceIn(0f, 1f) * 200f
            val r = (8 + tr * k + spec).toInt().coerceIn(0, 255)
            val g = (12 + tg * k + spec).toInt().coerceIn(0, 255)
            val b = (20 + tb * k + spec).toInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
