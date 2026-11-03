package app.ultimate.echo

/**
 * Gestures read from the measured distance track: deliberate movements a person makes on purpose, so a rule can act
 * on them without waving at the phone by accident setting things off.
 * - push: the hand moves toward the phone at least [travelM] within [windowMs];
 * - pull: the same, away from the phone;
 * - hover: the hand stays within [holdSpanM] for [holdMs], reported once per hold.
 * Pure: feed it readings and a clock.
 */
class EchoGestures(
    private val travelM: Double = 0.06, private val windowMs: Long = 500,
    private val holdSpanM: Double = 0.03, private val holdMs: Long = 1_200, private val refractoryMs: Long = 900,
) {
    data class Gesture(val kind: String, val atM: Double)

    private val track = ArrayDeque<Pair<Long, Double>>()
    private var lastFired = Long.MIN_VALUE / 2
    private var hovered = false

    /** [rangeM] is NaN when nothing is detected. Returns a gesture when one completes. */
    fun feed(rangeM: Double, now: Long): Gesture? {
        if (rangeM.isNaN()) {
            // A short dropout is normal; a long one ends the hold.
            if (track.isNotEmpty() && now - track.last().first > 250) { track.clear(); hovered = false }
            return null
        }
        track.addLast(now to rangeM)
        while (track.isNotEmpty() && now - track.first().first > holdMs + 200) track.removeFirst()
        if (now - lastFired < refractoryMs) return null
        val recent = track.filter { now - it.first <= windowMs }
        if (recent.size >= 3) {
            val first = recent.first().second; val last = recent.last().second
            if (first - last >= travelM) return fire(Gesture("push", last), now)
            if (last - first >= travelM) return fire(Gesture("pull", last), now)
        }
        val hold = track.filter { now - it.first <= holdMs }
        if (!hovered && hold.size >= 8 && now - hold.first().first >= holdMs - 100) {
            val lo = hold.minOf { it.second }; val hi = hold.maxOf { it.second }
            if (hi - lo <= holdSpanM) { hovered = true; return fire(Gesture("hover", (lo + hi) / 2), now) }
        }
        // Moving more than the hold span re-arms hover for the next time the hand settles.
        if (hovered && hold.isNotEmpty() && (hold.maxOf { it.second } - hold.minOf { it.second }) > holdSpanM * 2) hovered = false
        return null
    }

    private fun fire(g: Gesture, now: Long): Gesture { lastFired = now; track.clear(); return g }
}
