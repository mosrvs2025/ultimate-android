package app.ultimate.echo

import kotlin.math.sqrt

/**
 * Talking near the phone can look like movement to Echo: on phones whose microphone is always processed (no
 * "unprocessed" input), your voice changes the level the ultrasonic tone is heard at. This listens to the speech
 * band (everything well below the tone) and, while you're talking and for [holdMs] after, says to ignore Echo's
 * motion. The speech level is compared with the room's own quiet level, which it keeps learning. Pure, so it is tested.
 */
class VoiceGate(private val ratio: Float = 4f, private val floor: Float = 0.01f, private val holdMs: Long = 300) {
    private var quiet = Float.NaN
    private var until = 0L
    /** True while Echo's motion should be ignored because someone is talking. */
    var paused = false; private set

    /** Feeds one frame of microphone samples (-1..1); returns [paused]. */
    fun feed(samples: FloatArray, now: Long): Boolean {
        // Averaging each millisecond (48 samples) keeps speech and all but cancels the ~19 kHz tone (and other highs).
        val blocks = samples.size / 48
        var sum = 0.0
        for (b in 0 until blocks) { var a = 0f; for (i in b * 48 until b * 48 + 48) a += samples[i]; val v = a / 48f; sum += v * v }
        val level = sqrt(sum / blocks.coerceAtLeast(1)).toFloat()
        if (quiet.isNaN()) quiet = level
        val voice = level > floor && level > quiet * ratio
        if (voice) until = now + holdMs else quiet += 0.02f * (level - quiet)
        paused = now < until
        return paused
    }
}
