package app.ultimate.air

import kotlin.math.abs
import kotlin.math.hypot

/** One frame from the camera: 21 hand points (0-1 across the image, as the camera sees them), and the sign the model read. */
data class HandPose(val points: List<Pair<Float, Float>>, val sign: String?, val signScore: Float, val at: Long) {
    /** The middle of the palm: wrist and the four knuckles. */
    val palm: Pair<Float, Float> get() = listOf(0, 5, 9, 13, 17).map { points[it] }.let { p -> p.sumOf { it.first.toDouble() }.toFloat() / 5 to p.sumOf { it.second.toDouble() }.toFloat() / 5 }
    /** Knuckle-to-knuckle width: how big the hand looks, so how near it is. */
    val size: Float get() = dist(points[5], points[17])
    /** Thumb tip to index tip, relative to the hand's size: under ~0.3 is a pinch. */
    val pinch: Float get() = dist(points[4], points[8]) / size.coerceAtLeast(1e-4f)

    private fun dist(a: Pair<Float, Float>, b: Pair<Float, Float>) = hypot(a.first - b.first, a.second - b.second)
}

/**
 * Turns a stream of hand poses into gestures, deliberate enough that ordinary hand movement doesn't set them off:
 * - swipe_left / swipe_right / swipe_up / swipe_down: the palm crosses a quarter of the picture in under 0.4 s;
 * - push / pull: the hand grows or shrinks by a third in under 0.5 s (towards or away from the camera);
 * - pinch: thumb and index finger meet;
 * - hold: the palm stays put for 1.2 s;
 * - thumb_up, thumb_down, open_palm, fist, victory, pointing_up, i_love_you: hand signs from the model, held for 0.3 s.
 * [mirrored] is true for the front camera, so "left" is your left. Pure: feed it poses and a clock.
 */
class AirGestures(private val mirrored: Boolean = true, private val refractoryMs: Long = 700) {
    data class Gesture(val kind: String, val label: String)

    private val track = ArrayDeque<HandPose>()
    private var lastFired = Long.MIN_VALUE / 2
    private var pinched = false
    private var heldSign: String? = null
    private var signSince = 0L
    private var signFired = false
    private var holdFired = false

    fun feed(p: HandPose?, now: Long): Gesture? {
        if (p == null || p.points.size < 21) { if (track.isNotEmpty() && now - track.last().at > 300) reset(); return null }
        track.addLast(p)
        while (track.isNotEmpty() && now - track.first().at > 1_400) track.removeFirst()
        if (now - lastFired < refractoryMs) return null

        // Pinch: thumb and index meet after being apart.
        if (!pinched && p.pinch < 0.3f && track.any { it.pinch > 0.55f }) { pinched = true; return fire("pinch", "Pinch", now) }
        if (p.pinch > 0.5f) pinched = false

        // Swipes, from the palm's travel over the last 0.4 s.
        val recent = track.filter { now - it.at <= 400 }
        if (recent.size >= 3) {
            val (x0, y0) = recent.first().palm; val (x1, y1) = p.palm
            val dx = (x1 - x0) * (if (mirrored) -1 else 1); val dy = y1 - y0
            if (abs(dx) > 0.25f && abs(dx) > 2 * abs(dy)) return fire(if (dx < 0) "swipe_left" else "swipe_right", if (dx < 0) "Swipe left" else "Swipe right", now)
            if (abs(dy) > 0.25f && abs(dy) > 2 * abs(dx)) return fire(if (dy < 0) "swipe_up" else "swipe_down", if (dy < 0) "Swipe up" else "Swipe down", now)
        }

        // Push and pull, from how fast the hand grows or shrinks.
        val half = track.filter { now - it.at <= 500 }
        if (half.size >= 3) {
            val g = p.size / half.first().size.coerceAtLeast(1e-4f)
            if (g > 1.35f) return fire("push", "Push", now)
            if (g < 1 / 1.35f) return fire("pull", "Pull", now)
        }

        // Hand signs, held briefly so a passing shape doesn't count.
        val sign = p.sign?.takeIf { p.signScore >= 0.7f && it != "None" }
        if (sign != heldSign) { heldSign = sign; signSince = now; signFired = false }
        if (sign != null && !signFired && now - signSince >= 300) { signFired = true; return fire(signKind(sign), signLabel(sign), now) }

        // Hold: the palm stays within a small circle for 1.2 s.
        val hold = track.filter { now - it.at <= 1_200 }
        if (!holdFired && hold.size >= 8 && now - hold.first().at >= 1_100) {
            val xs = hold.map { it.palm.first }; val ys = hold.map { it.palm.second }
            if ((xs.max() - xs.min()) < 0.04f && (ys.max() - ys.min()) < 0.04f) { holdFired = true; return fire("hold", "Hold", now) }
        }
        if (holdFired && hold.isNotEmpty() && (hold.maxOf { it.palm.first } - hold.minOf { it.palm.first }) > 0.08f) holdFired = false
        return null
    }

    private fun fire(kind: String, label: String, now: Long): Gesture { lastFired = now; track.clear(); return Gesture(kind, label) }
    fun reset() { track.clear(); pinched = false; heldSign = null; signFired = false; holdFired = false }

    companion object {
        fun signKind(s: String) = when (s) {
            "Thumb_Up" -> "thumb_up"; "Thumb_Down" -> "thumb_down"; "Open_Palm" -> "open_palm"; "Closed_Fist" -> "fist"
            "Victory" -> "victory"; "Pointing_Up" -> "pointing_up"; "ILoveYou" -> "i_love_you"; else -> s.lowercase()
        }
        fun signLabel(s: String) = when (s) {
            "Thumb_Up" -> "Thumbs up"; "Thumb_Down" -> "Thumbs down"; "Open_Palm" -> "Open palm"; "Closed_Fist" -> "Fist"
            "Victory" -> "Peace sign"; "Pointing_Up" -> "Pointing up"; "ILoveYou" -> "I love you"; else -> s
        }

        /** Every gesture, for rule triggers and the action picker. */
        val ALL = listOf(
            "swipe_left" to "Swipe left", "swipe_right" to "Swipe right", "swipe_up" to "Swipe up", "swipe_down" to "Swipe down",
            "push" to "Push", "pull" to "Pull", "pinch" to "Pinch", "hold" to "Hold",
            "thumb_up" to "Thumbs up", "thumb_down" to "Thumbs down", "open_palm" to "Open palm", "fist" to "Fist",
            "victory" to "Peace sign", "pointing_up" to "Pointing up", "i_love_you" to "I love you",
        )
    }
}

/** What a gesture does, by default; the person can change each one. */
object AirActions {
    val CHOICES = listOf(
        "none" to "Nothing", "next" to "Next track", "previous" to "Previous track", "playpause" to "Play / pause",
        "volume_up" to "Volume up", "volume_down" to "Volume down", "torch" to "Flashlight on / off", "home" to "Go to Ultimate",
    )
    val DEFAULTS = mapOf(
        "swipe_left" to "previous", "swipe_right" to "next", "thumb_up" to "volume_up", "thumb_down" to "volume_down",
        "open_palm" to "playpause", "pinch" to "torch",
    )
}
