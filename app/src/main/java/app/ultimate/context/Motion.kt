package app.ultimate.context

import kotlin.math.abs
import kotlin.math.sqrt

/** How the phone is being carried right now. */
enum class Motion { Still, Held, Walking }

/**
 * Tells "on a table", "in a hand" and "walking" apart from a couple of seconds of acceleration size (gravity included).
 * Walking is a steady bounce of 1.2 to 3.5 steps a second that is strong enough to feel; a hand alone makes slow, irregular
 * drift; a table makes almost nothing. No learning and no sensor other than the accelerometer. Pure, so it is tested.
 */
object MotionClassifier {
    const val STILL_STD = 0.12f
    const val WALK_STD = 0.9f
    const val MIN_HZ = 1.2f
    const val MAX_HZ = 3.5f

    /** [magnitudes] are acceleration sizes in m/s^2 sampled evenly at [rateHz]. Needs about two seconds to say anything but Still. */
    fun classify(magnitudes: FloatArray, rateHz: Float): Motion {
        if (magnitudes.size < 8 || rateHz <= 0f) return Motion.Still
        val mean = magnitudes.average().toFloat()
        val x = FloatArray(magnitudes.size) { magnitudes[it] - mean }
        val std = sqrt(x.sumOf { (it * it).toDouble() } / x.size).toFloat()
        if (std < STILL_STD) return Motion.Still
        if (std < WALK_STD) return Motion.Held
        // Smooth lightly so sensor noise doesn't add zero crossings, then count upward crossings: one per step.
        val s = FloatArray(x.size) { i -> (x[maxOf(0, i - 1)] + x[i] + x[minOf(x.size - 1, i + 1)]) / 3f }
        var ups = 0
        for (i in 1 until s.size) if (s[i - 1] < 0f && s[i] >= 0f) ups++
        val hz = ups / (s.size / rateHz)
        return if (hz in MIN_HZ..MAX_HZ) Motion.Walking else Motion.Held
    }
}

/** What the interface should do about it. [tileMinDp] is the least height a favourite tile gets; 0 leaves tiles as they are. */
data class Morph(val glance: Boolean, val tileMinDp: Int, val note: String?)

/**
 * The one place that decides how the interface bends to the body. Today it is deliberately small: while you walk, the
 * tiles you tap get taller, which is easier to hit with a moving thumb, and nothing is removed, reordered or hidden (so
 * nothing moves under a finger). More ways to bend belong here, each one tested.
 */
object UiMorph {
    val NORMAL = Morph(false, 0, null)
    val GLANCE = Morph(true, 104, "You're on the move: bigger tiles")

    fun forMotion(m: Motion): Morph = if (m == Motion.Walking) GLANCE else NORMAL
}
