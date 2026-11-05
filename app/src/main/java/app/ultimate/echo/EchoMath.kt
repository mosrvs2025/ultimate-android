package app.ultimate.echo

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Echo turns the speaker and microphone into a short-range sonar. The speaker plays a steady tone too high for most
 * people to hear; the microphone hears it, along with its reflections. A still room sends the tone back unchanged. A
 * hand moving toward the phone shifts the reflection slightly up in pitch (Doppler), moving away shifts it down, so
 * energy appears just above or below the tone. This file only does the arithmetic; [EchoEngine] does the audio.
 */
object EchoMath {
    const val RATE = 48_000
    /** 18 750 Hz fits exactly 1 875 times in 4 800 samples, so the tone buffer loops with no click. */
    const val PILOT = 18_750.0
    const val FRAME = 2_048

    /** Power of one frequency in [x] (Goertzel), the cheap way to look at a few frequencies instead of a whole FFT. */
    fun power(x: FloatArray, freq: Double, rate: Int = RATE): Double {
        val w = 2 * PI * freq / rate; val c = 2 * cos(w)
        var s1 = 0.0; var s2 = 0.0
        for (v in x) { val s0 = v + c * s1 - s2; s2 = s1; s1 = s0 }
        return (s1 * s1 + s2 * s2 - c * s1 * s2) / (x.size.toDouble() * x.size)
    }

    /** Offsets from the tone where moving reflections land: a hand at walking pace shifts it by roughly 20 to 200 Hz. */
    private val OFFSETS = listOf(47.0, 70.0, 94.0, 117.0, 141.0, 164.0, 188.0)

    /** Energy just above and just below the tone, each relative to the tone itself, so the speaker volume cancels out. */
    data class Bands(val pilot: Double, val above: Double, val below: Double, val noise: Double = 0.0)

    fun bands(x: FloatArray, pilot: Double = PILOT): Bands {
        val p = power(x, pilot)
        val safe = p.coerceAtLeast(1e-12)
        return Bands(p, OFFSETS.sumOf { power(x, pilot + it) } / safe, OFFSETS.sumOf { power(x, pilot - it) } / safe, (power(x, pilot + 700) + power(x, pilot - 700)) / 2)
    }

    /** One period-exact buffer of the tone, ready to loop. */
    fun tone(amplitude: Float = 0.35f, samples: Int = 4_800, freq: Double = PILOT): ShortArray =
        ShortArray(samples) { i -> (sin(2 * PI * freq * i / RATE) * amplitude * Short.MAX_VALUE).toInt().toShort() }

    /** A Hann window, which keeps the strong tone from smearing into the side bands where motion shows up. */
    fun window(n: Int = FRAME) = FloatArray(n) { i -> (0.5 - 0.5 * cos(2 * PI * i / (n - 1))).toFloat() }

    fun rms(x: FloatArray): Double = sqrt(x.sumOf { (it * it).toDouble() } / x.size.coerceAtLeast(1))
}

/** What Echo makes of one moment: how much is moving, and which way. */
data class EchoReading(val motion: Float, val direction: Direction, val heard: Boolean) {
    enum class Direction { Still, Approach, Recede }
}

/**
 * Turns frames into readings and, now and then, into an event. It learns the quiet level of the room for the first
 * second and keeps adjusting it while nothing moves, so a fan or a different table does not read as motion.
 * Stateful but pure: no audio, no clock but the one passed in.
 */
class EchoDetector(private val threshold: Float = 3.5f, private val refractoryMs: Long = 700) {
    private var baseline = -1.0
    private var frames = 0
    private var lastEventAt = Long.MIN_VALUE / 2
    /** The usual level of sound well away from the tone, relative to it: what "quiet" is in this room. */
    private var floorBase = -1.0

    /** Returns the reading, and an event kind ("approach" or "recede") when one should be reported. */
    fun feed(b: EchoMath.Bands, now: Long): Pair<EchoReading, String?> {
        frames++
        // Whether the microphone hears the tone at all: it must stand well clear of the noise beside it.
        val heard = b.pilot > b.noise * 20 && b.pilot > 1e-12
        // Other sound (a voice's "s", music) raises the band well away from the tone too; a hand's Doppler shift doesn't
        // reach that far. When it jumps above its usual level, ignore the frame instead of calling it motion (Galaxy S10).
        val floor = b.noise / b.pilot.coerceAtLeast(1e-12)
        if (floorBase < 0) floorBase = floor
        val noisy = frames > CALIBRATION && floor > floorBase * 4
        if (!noisy) floorBase = if (frames <= CALIBRATION) maxOf(floorBase, floor) else floorBase * 0.98 + floor * 0.02
        val side = b.above + b.below
        if (baseline < 0) baseline = side
        val ratio = (side / baseline.coerceAtLeast(1e-9)).toFloat()
        val moving = frames > CALIBRATION && ratio > threshold && !noisy
        // Learn the room only while it is still, and slowly, so one wave does not raise the bar.
        if (!moving && !noisy) baseline = if (frames <= CALIBRATION) maxOf(baseline, side) * 0.5 + side * 0.5 else baseline * 0.97 + side * 0.03
        val dir = when {
            !moving -> EchoReading.Direction.Still
            b.above > b.below * 1.4 -> EchoReading.Direction.Approach
            b.below > b.above * 1.4 -> EchoReading.Direction.Recede
            else -> EchoReading.Direction.Still
        }
        val motion = ((ratio - 1f) / (threshold * 2f)).coerceIn(0f, 1f)
        val event = if (dir != EchoReading.Direction.Still && now - lastEventAt >= refractoryMs) {
            lastEventAt = now; if (dir == EchoReading.Direction.Approach) "approach" else "recede"
        } else null
        return EchoReading(if (frames > CALIBRATION) motion else 0f, dir, heard) to event
    }

    val calibrating get() = frames <= CALIBRATION

    companion object { const val CALIBRATION = 20 } // about a second at 2048-sample frames
}
