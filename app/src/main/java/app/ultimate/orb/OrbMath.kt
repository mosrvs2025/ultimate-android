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

    /**
     * Places [count] actions around the orb at (cx, cy) so that no two are closer than [gap] (centre to centre), none
     * sits closer to the orb than [minR], and all stay inside the screen with [pad] to spare. The fan opens towards the
     * middle of the screen: a half circle when the orb is on an edge, wider in the open, and a second, outer ring when
     * one would be too big. Then any that still crowd each other (in a corner, say) are nudged apart until they fit.
     */
    fun layout(cx: Float, cy: Float, count: Int, gap: Float, minR: Float, w: Float, h: Float, pad: Float): List<Pair<Float, Float>> {
        if (count <= 0) return emptyList()
        val toward = kotlin.math.atan2(h / 2 - cy, w / 2 - cx).toDouble()
        val onEdge = minOf(cx, w - cx, cy, h - cy) < minR * 1.2f
        val spread = (if (count <= 2) 70.0 else if (onEdge) 180.0 else 220.0) * PI / 180
        val maxR = minOf(w, h) * 0.38f
        fun radiusFor(n: Int) = if (n <= 1) minR else maxOf(minR, (gap / (2 * sin(spread / (n - 1) / 2))).toFloat())
        // One ring if it fits, else two: the inner one as full as it can be.
        val rings = mutableListOf<Pair<Int, Float>>()
        var left = count
        while (left > 0) {
            val inner = rings.lastOrNull()?.second?.plus(gap) ?: minR
            var n = left
            while (n > 1 && radiusFor(n).coerceAtLeast(inner) > maxR && rings.size < 2) n--
            rings += n to radiusFor(n).coerceAtLeast(inner)
            left -= n
        }
        val pts = ArrayList<FloatArray>()
        rings.forEachIndexed { ri, (n, r) ->
            for (i in 0 until n) {
                val t = if (n == 1) 0.0 else i.toDouble() / (n - 1) - 0.5
                val a = toward + t * spread * (if (ri == 0) 1.0 else 0.85)
                pts += floatArrayOf((cx + r * cos(a)).toFloat(), (cy + r * sin(a)).toFloat())
            }
        }
        fun clamp(p: FloatArray) { p[0] = p[0].coerceIn(pad, w - pad); p[1] = p[1].coerceIn(pad, h - pad) }
        pts.forEach(::clamp)
        // Nudge apart: each crowded pair moves away from each other, and anything too near the orb moves out.
        repeat(120) {
            var moved = false
            for (i in pts.indices) for (j in i + 1 until pts.size) {
                val a = pts[i]; val b = pts[j]
                var dx = b[0] - a[0]; var dy = b[1] - a[1]
                var d = hypot(dx, dy)
                if (d < gap) {
                    if (d < 0.01f) { dx = cos(i + j.toFloat()); dy = sin(i + j.toFloat()); d = 1f }
                    val push = (gap - d) / 2 + 0.5f
                    a[0] -= dx / d * push; a[1] -= dy / d * push; b[0] += dx / d * push; b[1] += dy / d * push
                    clamp(a); clamp(b); moved = true
                }
            }
            for (p in pts) {
                val d = hypot(p[0] - cx, p[1] - cy)
                if (d < minR * 0.9f) { val k = (minR * 0.9f) / maxOf(d, 0.01f); p[0] = cx + (p[0] - cx) * k; p[1] = cy + (p[1] - cy) * k; clamp(p); moved = true }
            }
            if (!moved) return pts.map { it[0] to it[1] }
        }
        return pts.map { it[0] to it[1] }
    }

    /**
     * The actions as a tidy column of pills beside the orb, curving gently around it like a bracket: [count] rows
     * [row] apart, centred on the orb's height but slid up or down to stay on screen, on the side with more room.
     * Returns each pill's centre. Rows can never overlap, and labels sit inside the pills, so nothing collides.
     */
    fun stack(cx: Float, cy: Float, count: Int, row: Float, pillW: Float, gapFromOrb: Float, curve: Float, w: Float, h: Float, pad: Float): List<Pair<Float, Float>> {
        if (count <= 0) return emptyList()
        val toLeft = cx > w / 2
        val height = row * (count - 1)
        val top = (cy - height / 2).coerceIn(pad + row / 2, maxOf(pad + row / 2, h - pad - row / 2 - height))
        val mid = (count - 1) / 2f
        return (0 until count).map { i ->
            val y = top + i * row
            // Nearest the orb's height sits closest; rows further up or down bow away a little.
            val bow = curve * ((i - mid) / maxOf(mid, 1f)).let { it * it }
            val dx = gapFromOrb + pillW / 2 + bow
            val x = (if (toLeft) cx - dx else cx + dx).coerceIn(pad + pillW / 2, w - pad - pillW / 2)
            x to y
        }
    }

    /** True when the orb's center is close enough to the dismiss target to drop it there. */
    fun overTarget(cx: Float, cy: Float, tx: Float, ty: Float, reach: Float) = hypot(cx - tx, cy - ty) <= reach

    /** Orders actions by how often they were used, keeping the default order among equals. */
    fun <T> byUse(actions: List<T>, key: (T) -> String, uses: (String) -> Int): List<T> =
        actions.withIndex().sortedWith(compareByDescending<IndexedValue<T>> { uses(key(it.value)) }.thenBy { it.index }).map { it.value }
}
