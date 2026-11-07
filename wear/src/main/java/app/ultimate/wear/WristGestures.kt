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
    /** When the wrist last turned fast: a flick's own jolt and rebound must not count as a shake. */
    private var lastTurn = Long.MIN_VALUE / 2

    /**
     * Which axis direction means which gesture ("x+" to "flick_down", ...), as calibrated on the phone from this wearer's
     * own flicks; null keeps the built-in guess. [twistAxis] is the axis turned about in a twist.
     */
    var directions: Map<String, String>? = null
    var twistAxis: Char = 'y'

    /** Applies a phone calibration ("config:rate=4.2;twist=y;x-=flick_up;..."); returns false if it can't be read. */
    fun configure(text: String): Boolean {
        val body = text.removePrefix("config:").takeIf { it != text } ?: return false
        val map = HashMap<String, String>(); var rate: Float? = null; var twist: Char? = null
        body.split(';').forEach { part ->
            val (k, v) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: return@forEach
            when {
                k == "rate" -> rate = v.toFloatOrNull()
                k == "twist" -> twist = v.firstOrNull()?.takeIf { it in "xyz" }
                k.length == 2 && k[0] in "xyz" && k[1] in "+-" && v in LABELS -> map[k] = v
            }
        }
        rate?.takeIf { it in 1.5f..12f }?.let { flickRate = it }
        twist?.let { twistAxis = it }
        directions = map.takeIf { it.isNotEmpty() }
        return true
    }

    /** Feeds one gyroscope reading; returns a gesture name when one completes. */
    fun gyro(x: Float, y: Float, z: Float, now: Long): String? {
        val ax = abs(x); val ay = abs(y); val az = abs(z)
        val peak = maxOf(ax, ay, az)
        // Re-arm once the wrist has settled, so one movement is one gesture.
        if (peak < flickRate * 0.35f) armed = true
        if (peak > flickRate * 0.5f) lastTurn = now
        if (twistSign != 0 && now - twistAt > 500) twistSign = 0
        if (!armed || peak < flickRate || now - lastFired < refractoryMs) return null
        val axis = when (peak) { ax -> 'x'; ay -> 'y'; else -> 'z' }
        val v = when (axis) { 'x' -> x; 'y' -> y; else -> z }
        val others = when (axis) { 'x' -> maxOf(ay, az); 'y' -> maxOf(ax, az); else -> maxOf(ax, ay) }
        if (peak < dominance * others) return null
        val sign = if (v > 0) 1 else -1
        if (axis == twistAxis) {
            // A twist is a turn about the forearm and straight back: the first half waits for the second.
            armed = false
            return if (twistSign == -sign && now - twistAt <= 500) { twistSign = 0; fire(now, "twist") } else { twistSign = sign; twistAt = now; null }
        }
        val key = "$axis${if (sign > 0) "+" else "-"}"
        val kind = directions?.get(key) ?: when (key) {
            // The built-in guess, used until the phone calibrates this watch.
            "z+" -> if (rightWrist) "flick_right" else "flick_left"
            "z-" -> if (rightWrist) "flick_left" else "flick_right"
            "x+" -> "flick_up"
            "x-" -> "flick_down"
            else -> null
        } ?: return null
        // The wrist stopping swings back the other way: never count that as the opposite flick.
        val opposite = mapOf("flick_left" to "flick_right", "flick_right" to "flick_left", "flick_up" to "flick_down", "flick_down" to "flick_up")
        if (opposite[lastKind] == kind && now - lastFired < 900) return null
        return fire(now, kind)
    }

    /** Feeds one linear-acceleration reading; returns "shake" when three hard jolts in alternating directions land within 800 ms. */
    fun accel(x: Float, y: Float, z: Float, now: Long): String? {
        val m = sqrt(x * x + y * y + z * z)
        while (jolts.isNotEmpty() && now - jolts.first().first > 800) jolts.removeFirst()
        // Harder than a flick's own jolt, and not while the wrist is turning, so flicks stop reading as shakes.
        if (m < SHAKE_JOLT || now - lastFired < refractoryMs || now - lastTurn < 400) return null
        // The sign of the strongest axis tells a jolt one way from the next one back.
        val sign = when (maxOf(abs(x), abs(y), abs(z))) { abs(x) -> if (x > 0) 1 else -1; abs(y) -> if (y > 0) 2 else -2; else -> if (z > 0) 3 else -3 }
        if (jolts.lastOrNull()?.second == sign) return null
        jolts.addLast(now to sign)
        if (jolts.size >= 3) { jolts.clear(); return fire(now, "shake") }
        return null
    }

    private var lastKind: String? = null
    private fun fire(now: Long, kind: String): String { lastFired = now; lastKind = kind; armed = false; return kind }

    companion object {
        /** m/s² a shake's jolts must reach. */
        const val SHAKE_JOLT = 18f
        val LABELS = mapOf(
            "flick_left" to "Flick left", "flick_right" to "Flick right", "flick_up" to "Flick up", "flick_down" to "Flick down",
            "twist" to "Twist", "shake" to "Shake",
        )
    }
}
