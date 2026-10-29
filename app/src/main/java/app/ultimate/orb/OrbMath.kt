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

    /** True when the orb's center is close enough to the dismiss target to drop it there. */
    fun overTarget(cx: Float, cy: Float, tx: Float, ty: Float, reach: Float) = hypot(cx - tx, cy - ty) <= reach

    /** Orders actions by how often they were used, keeping the default order among equals. */
    fun <T> byUse(actions: List<T>, key: (T) -> String, uses: (String) -> Int): List<T> =
        actions.withIndex().sortedWith(compareByDescending<IndexedValue<T>> { uses(key(it.value)) }.thenBy { it.index }).map { it.value }
}
