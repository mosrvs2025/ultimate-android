package app.ultimate.mesh

import app.ultimate.echo.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * How far apart two phones are, measured with sound and no clock agreement between them (the BeepBeep scheme).
 *
 * Each phone plays one short chirp near the top of hearing, 17-20 kHz, and both phones record the whole time. A hears
 * its own speaker and then B's; B hears its own and then A's. Each phone finds the two chirps in its own recording and
 * only ever compares sample positions inside that one recording, so neither phone needs to know when the other
 * started, and the (unknown, variable) audio latency of Android cancels out. With positions in samples at [RATE]:
 *
 *   A: own at a1, B's at a2      B: own at b1, A's at b2
 *   distance = c / 2 * ((a2 - a1) + (b2 - b1)) / RATE + (speakerToMicA + speakerToMicB) / 2
 *
 * Two different chirps (A sweeps up, B sweeps down) so each phone can tell its own from the other's.
 *
 * This file is pure arithmetic and is tested against synthesised recordings with known delays. What it cannot say:
 * whether a particular pair of phones' speakers and microphones reach 20 kHz, how loud a room is, or how well the
 * result holds up in a reverberant room. Those can only be found out on real phones; the phone reports how confident
 * each detection was so a doubtful result is shown as doubtful, and never rounded into a number that looks sure.
 */
object PeerRange {
    const val RATE = 48_000
    const val F_LOW = 17_000.0
    const val F_HIGH = 20_000.0
    const val CHIRP = 960 // 20 ms
    const val SOUND = 343.0 // m/s at about 20 °C

    /** Which chirp this phone plays. The phone that starts the measurement is [Role.Caller]. */
    enum class Role { Caller, Callee }

    /** One chirp, tapered at both ends so its edges do not click. Caller sweeps up, callee sweeps down. */
    fun chirp(role: Role, amplitude: Float = 0.6f): FloatArray {
        val (f0, f1) = if (role == Role.Caller) F_LOW to F_HIGH else F_HIGH to F_LOW
        val k = (f1 - f0) / (CHIRP.toDouble() / RATE)
        return FloatArray(CHIRP) { i ->
            val t = i.toDouble() / RATE
            val u = i.toDouble() / (CHIRP - 1)
            val taper = when { u < 0.1 -> 0.5 * (1 - cos(2 * PI * u / 0.2)); u > 0.9 -> 0.5 * (1 - cos(2 * PI * (1 - u) / 0.2)); else -> 1.0 }
            (sin(2 * PI * (f0 * t + k * t * t / 2)) * taper * amplitude).toFloat()
        }
    }

    /** Where a chirp starts inside a recording, to a fraction of a sample, and how sure the detection is. */
    data class Detection(val position: Double, val strength: Double, val noise: Double) {
        /** How far the peak stands above the typical background. Below [MIN_SNR] it is treated as not heard. */
        val snr: Double get() = if (noise <= 0.0) Double.POSITIVE_INFINITY else strength / noise
        val heard: Boolean get() = snr >= MIN_SNR
    }

    const val MIN_SNR = 8.0

    /**
     * Matched-filters [recording] with [reference] through the analytic signal, so the peak is the chirp's envelope
     * (no carrier ripple) and can be placed to a fraction of a sample. Takes the EARLIEST peak that is at least half as strong
     * as the strongest: the direct path always arrives before any echo of it, so a loud reflection must not win.
     * [from]/[to] limit the search (used to stop a phone mistaking its own chirp for the other's).
     */
    fun detect(recording: FloatArray, reference: FloatArray, from: Int = 0, to: Int = recording.size - reference.size): Detection? {
        if (recording.size < reference.size + 2 || to <= from) return null
        var n = 1; while (n < recording.size + reference.size) n = n shl 1
        val xr = DoubleArray(n) { if (it < recording.size) recording[it].toDouble() else 0.0 }; val xi = DoubleArray(n)
        val cr = DoubleArray(n) { if (it < reference.size) reference[it].toDouble() else 0.0 }; val ci = DoubleArray(n)
        Fft.transform(xr, xi, false); Fft.transform(cr, ci, false)
        val half = n / 2
        for (i in 0 until n) {
            // X * conj(C); keep only positive frequencies (doubled) to get the analytic signal of the correlation.
            val pr = xr[i] * cr[i] + xi[i] * ci[i]; val pi = xi[i] * cr[i] - xr[i] * ci[i]
            val g = when { i == 0 || i == half -> 1.0; i < half -> 2.0; else -> 0.0 }
            xr[i] = pr * g; xi[i] = pi * g
        }
        Fft.transform(xr, xi, true)
        val last = minOf(to, recording.size - reference.size)
        if (last <= from) return null
        val env = DoubleArray(last - from + 1) { hypot(xr[from + it], xi[from + it]) }
        var best = 0
        for (i in env.indices) if (env[i] > env[best]) best = i
        val top = env[best]
        if (top <= 0.0) return null
        // Earliest local maximum within half the strongest: the direct path, not a louder echo.
        var pick = best
        for (i in 1 until env.size - 1) {
            if (env[i] >= 0.5 * top && env[i] >= env[i - 1] && env[i] > env[i + 1]) { pick = i; break }
        }
        val a = env.getOrElse(pick - 1) { env[pick] }; val b = env[pick]; val c = env.getOrElse(pick + 1) { env[pick] }
        val d = a - 2 * b + c
        val frac = if (d == 0.0) 0.0 else (0.5 * (a - c) / d).coerceIn(-0.5, 0.5)
        // Background level: the median of the envelope away from the peak (a median ignores the few big peaks).
        val away = env.filterIndexed { i, _ -> abs(i - pick) > 2 * CHIRP }.sorted()
        val noise = if (away.isEmpty()) 0.0 else away[away.size / 2]
        return Detection(from + pick + frac, b, noise)
    }

    /**
     * Finds both chirps in one phone's recording. The phone's own chirp is far louder than the other's and the up and
     * down sweeps are not perfectly separable (their cross-correlation is only about 18 dB down), so after finding its
     * own chirp the phone blanks that stretch before looking for the other's: otherwise its own speaker would leak
     * into the search and be mistaken for the other phone.
     */
    fun measure(recording: FloatArray, me: Role): Side? {
        val peer = if (me == Role.Caller) Role.Callee else Role.Caller
        val own = detect(recording, chirp(me)) ?: return null
        val blanked = recording.copyOf()
        val a = (own.position.toInt() - 100).coerceAtLeast(0); val b = (own.position.toInt() + CHIRP + 2_400).coerceAtMost(blanked.size)
        for (i in a until b) blanked[i] = 0f
        val other = detect(blanked, chirp(peer)) ?: return null
        return Side(own, other)
    }

    /** What one phone measured in its own recording. */
    data class Side(val own: Detection, val other: Detection) {
        /** Seconds from this phone's own chirp to the other phone's, in this phone's recording. */
        val gapSeconds: Double get() = (other.position - own.position) / RATE
        val heard: Boolean get() = own.heard && other.heard
    }

    data class Result(val metres: Double, val confidence: Confidence, val note: String)
    enum class Confidence { Good, Doubtful, None }

    /**
     * The distance, or null with the reason if either phone did not hear both chirps clearly.
     * [speakerToMicMetres] is the sum of the two phones' speaker-to-microphone separations; unknown, it is taken as
     * 0.14 m (about 7 cm each) and the result is that much less certain, which the note says.
     */
    fun distance(caller: Side, callee: Side, speakerToMicMetres: Double = DEFAULT_SPEAKER_TO_MIC): Result {
        if (!caller.own.heard) return Result(Double.NaN, Confidence.None, "The calling phone could not hear its own chirp: turn the media volume up.")
        if (!callee.own.heard) return Result(Double.NaN, Confidence.None, "The other phone could not hear its own chirp: its speaker or microphone may not reach this high.")
        if (!caller.other.heard) return Result(Double.NaN, Confidence.None, "The calling phone did not hear the other phone's chirp: too far apart, blocked, or the other phone is too quiet.")
        if (!callee.other.heard) return Result(Double.NaN, Confidence.None, "The other phone did not hear the calling phone's chirp: too far apart, blocked, or too quiet.")
        val metres = SOUND / 2 * (caller.gapSeconds + callee.gapSeconds) + speakerToMicMetres / 2
        if (metres < -0.2 || metres > 30.0) return Result(Double.NaN, Confidence.None, "The timings do not make sense together, so the measurement is discarded.")
        val weakest = minOf(caller.own.snr, caller.other.snr, callee.own.snr, callee.other.snr)
        val conf = if (weakest >= 3 * MIN_SNR) Confidence.Good else Confidence.Doubtful
        val note = if (conf == Confidence.Good) "Both phones heard both chirps clearly." else "A chirp was faint: treat this as rough."
        return Result(metres.coerceAtLeast(0.0), conf, note)
    }

    const val DEFAULT_SPEAKER_TO_MIC = 0.14

    /** One sample of timing error in either phone's recording is this many metres of distance error (about 3.6 mm). */
    const val M_PER_SAMPLE = SOUND / RATE / 2

    /**
     * Plan for one measurement, in milliseconds after each phone starts recording. The caller chirps first and the
     * callee later, far enough apart that neither chirp is inside the other's, and each phone records past the other's
     * so the network's delay in starting them does not matter.
     */
    object Plan {
        const val CALLER_AT_MS = 350
        const val CALLEE_AT_MS = 900
        const val RECORD_MS = 1_800
        fun samples(ms: Int) = ms * RATE / 1000
    }
}
