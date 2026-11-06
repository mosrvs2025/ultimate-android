package app.ultimate.wear

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Wrist gestures from the watch's gyroscope (rad/s) and linear accelerometer (m/s², gravity removed).
 *
 * Watch axes, screen up: X runs from 9 to 3 o'clock, Y from 6 to 12 o'clock (along the forearm), Z out of the screen.
 * - flick left / right: a quick turn about Z, the wrist swinging sideways;
 * - flick up / down: a quick turn about X, the hand tipping up or down;
 * - twist: a quick turn about Y (the forearm) and back again, like turning a key;
 * - shake: three or more hard back-and-forth jolts within a moment.
 *
 * A gesture counts when one axis clearly leads (at least [dominance] times the others), so ordinary arm movement,
 * which turns about several axes at once, rarely fires. After a gesture there is a short pause ([refractoryMs]).
 * [rightWrist] mirrors left and right, since the same motion reads the other way on the other arm.
 */
class WristGestures(
    var rightWrist: Boolean = false,
    /** Turn rate, in rad/s, that a flick must reach; lower is more sensitive. */
    var flickRate: Float = 4.5f,
    private val dominance: Float = 1.6f,
    private val refractoryMs: Long = 700,
) {
    private var lastFired = Long.MIN_VALUE / 2
    private var armed = true
    private var twistSign = 0
    private var twistAt = 0L
    private val jolts = ArrayDeque<Pair<Long, Int>>()

    /** Feeds one gyroscope reading; returns a gesture name when one completes. */
    fun gyro(x: Float, y: Float, z: Float, now: Long): String? {
        val ax = abs(x); val ay = abs(y); val az = abs(z)
        val peak = maxOf(ax, ay, az)
        // Re-arm once the wrist has settled, so one movement is one gesture.
        if (peak < flickRate * 0.35f) armed = true
        if (twistSign != 0 && now - twistAt > 500) twistSign = 0
        if (!armed || peak < flickRate || now - lastFired < refractoryMs) return null
        return when {
            ay >= peak && ay >= dominance * maxOf(ax, az) -> {
                // A twist is a turn about the forearm and straight back: the first half waits for the second.
                val sign = if (y > 0) 1 else -1
                armed = false
                if (twistSign == -sign && now - twistAt <= 500) { twistSign = 0; fire(now, "twist") } else { twistSign = sign; twistAt = now; null }
            }
            az >= peak && az >= dominance * maxOf(ax, ay) -> {
                val left = (z > 0) != rightWrist
                fire(now, if (left) "flick_left" else "flick_right")
            }
            ax >= peak && ax >= dominance * maxOf(ay, az) -> fire(now, if (x > 0) "flick_up" else "flick_down")
            else -> null
        }
    }

    /** Feeds one linear-acceleration reading; returns "shake" when three hard jolts in alternating directions land within 800 ms. */
    fun accel(x: Float, y: Float, z: Float, now: Long): String? {
        val m = sqrt(x * x + y * y + z * z)
        while (jolts.isNotEmpty() && now - jolts.first().first > 800) jolts.removeFirst()
        if (m < 14f || now - lastFired < refractoryMs) return null
        // The sign of the strongest axis tells a jolt one way from the next one back.
        val sign = when (maxOf(abs(x), abs(y), abs(z))) { abs(x) -> if (x > 0) 1 else -1; abs(y) -> if (y > 0) 2 else -2; else -> if (z > 0) 3 else -3 }
        if (jolts.lastOrNull()?.second == sign) return null
        jolts.addLast(now to sign)
        if (jolts.size >= 3) { jolts.clear(); return fire(now, "shake") }
        return null
    }

    private fun fire(now: Long, kind: String): String { lastFired = now; armed = false; return kind }

    companion object {
        val LABELS = mapOf(
            "flick_left" to "Flick left", "flick_right" to "Flick right", "flick_up" to "Flick up", "flick_down" to "Flick down",
            "twist" to "Twist", "shake" to "Shake",
        )
    }
}
