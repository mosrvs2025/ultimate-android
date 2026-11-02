package app.ultimate.orb

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Where the orb settles and where its actions bloom. Pure, so the geometry is tested. */
object OrbMath {
    /** The orb rests against the nearer side edge, inside the screen top to bottom. Returns its top-left corner. */
    fun snap(x: Int, y: Int, orb: Int, screenW: Int, screenH: Int, margin: Int, topInset: Int, bottomInset: Int): Pair<Int, Int> {
        val left = x + orb / 2 < screenW / 2
        val sx = if (left) margin else screenW - orb - margin
        val sy = y.coerceIn(topInset + margin, (screenH - bottomInset - orb - margin).coerceAtLeast(topInset + margin))
        return sx to sy
    }

    /**
     * Centers for [count] actions on an arc around the orb at ([cx], [cy]), opening toward the middle of the screen so none
     * fall off the edge it rests on. Near the top or bottom the arc turns down or up. Every center stays [pad] inside the screen.
     */
    fun bloom(cx: Float, cy: Float, count: Int, radius: Float, screenW: Float, screenH: Float, pad: Float): List<Pair<Float, Float>> {
        if (count <= 0) return emptyList()
        // Point the arc's middle at the screen's center, then spread the actions across 160 degrees (narrower for a few).
        val toward = kotlin.math.atan2(screenH / 2 - cy, screenW / 2 - cx)
        val spread = (if (count <= 3) 100.0 else 160.0) * PI / 180
        return (0 until count).map { i ->
            val t = if (count == 1) 0.0 else i.toDouble() / (count - 1) - 0.5
            val a = toward + t * spread
            val px = (cx + radius * cos(a)).toFloat().coerceIn(pad, screenW - pad)
            val py = (cy + radius * sin(a)).toFloat().coerceIn(pad, screenH - pad)
            px to py
        }
    }

    /**
     * How far out to fan [count] actions so neighbours stay at least [gap] apart (centre to centre) along the arc,
     * never closer than [min]. Too tight and the discs touch, and with the gooey effect they melt into one slab.
     */
    fun fanRadius(count: Int, gap: Float, min: Float): Float {
        if (count <= 1) return min
        val spread = (if (count <= 3) 100.0 else 160.0) * PI / 180
        val step = spread / (count - 1)
        return maxOf(min, (gap / (2 * sin(step / 2))).toFloat())
    }

    /**
     * Where a petal is [t] of the way through opening, fanning out like a hand of cards: it leaves the orb along the
     * middle of the fan and sweeps round to its own angle while it travels out to its spot ([px], [py]).
     */
    fun fanAt(t: Float, cx: Float, cy: Float, px: Float, py: Float, toward: Float): Pair<Float, Float> {
        val k = t.coerceIn(0f, 1.2f)
        val a = kotlin.math.atan2(py - cy, px - cx)
        var turn = a - toward
        while (turn > PI) turn -= (2 * PI).toFloat(); while (turn < -PI) turn += (2 * PI).toFloat()
        val sweep = kotlin.math.sqrt(k.coerceIn(0f, 1f))
        val ang = toward + turn * sweep
        val r = hypot(px - cx, py - cy) * k
        return (cx + r * cos(ang)) to (cy + r * sin(ang))
    }

    /** The middle direction of a fan of spots around (cx, cy). */
    fun middle(spots: List<Pair<Float, Float>>, cx: Float, cy: Float): Float {
        val sx = spots.sumOf { cos(kotlin.math.atan2(it.second - cy, it.first - cx).toDouble()) }
        val sy = spots.sumOf { sin(kotlin.math.atan2(it.second - cy, it.first - cx).toDouble()) }
        return kotlin.math.atan2(sy, sx).toFloat()
    }

    /** True when the orb's center is close enough to the dismiss target to drop it there. */
    fun overTarget(cx: Float, cy: Float, tx: Float, ty: Float, reach: Float) = hypot(cx - tx, cy - ty) <= reach

    /** Orders actions by how often they were used, keeping the default order among equals. */
    fun <T> byUse(actions: List<T>, key: (T) -> String, uses: (String) -> Int): List<T> =
        actions.withIndex().sortedWith(compareByDescending<IndexedValue<T>> { uses(key(it.value)) }.thenBy { it.index }).map { it.value }
}
