package app.ultimate.air

import kotlin.math.hypot

/**
 * Shapes read finger by finger: each finger is out or curled, and the five together name a shape. This reads shapes
 * the built-in recognizer doesn't know (the middle finger, rock on, call me, three, four), and any you make up later.
 * A shape counts once it has been held for [holdMs], so passing through it on the way to another doesn't. Pure, so
 * it is tested.
 */
class FingerShapes(private val holdMs: Long = 500) {
    private var shape: String? = null
    private var since = 0L
    private var fired = false

    /** Feeds one hand (21 points, 0..1); returns the shape's kind once it has been held long enough, once per hold. */
    fun feed(points: List<Pair<Float, Float>>, now: Long): String? {
        val s = SHAPES[mask(points)]
        if (s != shape) { shape = s; since = now; fired = false; return null }
        if (s == null || fired || now - since < holdMs) return null
        fired = true; return s
    }

    companion object {
        /** Thumb, index, middle, ring, pinky: true when out. */
        fun mask(p: List<Pair<Float, Float>>): List<Boolean> {
            fun d(a: Int, b: Int) = hypot(p[a].first - p[b].first, p[a].second - p[b].second)
            // A finger is out when its tip is clearly farther from the wrist than its middle joint.
            fun out(tip: Int, pip: Int) = d(0, tip) > d(0, pip) * 1.15f
            // The thumb is out when its tip is well away from the base of the little finger, compared with its own joint.
            val thumb = d(4, 17) > d(3, 17) * 1.1f && d(4, 5) > d(5, 9) * 0.9f
            return listOf(thumb, out(8, 6), out(12, 10), out(16, 14), out(20, 18))
        }

        private fun m(vararg f: Int) = f.map { it == 1 }
        val SHAPES = mapOf(
            m(0, 0, 1, 0, 0) to "middle_finger",
            m(1, 1, 0, 0, 1) to "rock_on", m(0, 1, 0, 0, 1) to "rock_on",
            m(1, 0, 0, 0, 1) to "call_me",
            m(0, 1, 1, 1, 0) to "three",
            m(0, 1, 1, 1, 1) to "four",
        )
        val LABELS = mapOf("middle_finger" to "Middle finger", "rock_on" to "Rock on", "call_me" to "Call me", "three" to "Three fingers", "four" to "Four fingers")
    }
}
