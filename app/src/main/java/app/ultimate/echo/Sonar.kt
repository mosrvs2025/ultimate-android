package app.ultimate.echo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Echo's ranging sonar. The speaker repeats a short chirp sweeping 17-20 kHz every [PERIOD] samples; the microphone
 * hears it directly and again off anything nearby. Correlating each received period with the chirp (a matched
 * filter) gives an echo profile: how much sound came back after each delay. Delay is distance, at 3.57 mm a sample.
 *
 * Everything here is pure arithmetic so it can be tested exactly against synthesised echoes. Physical limits worth
 * remembering: range resolution is c / 2B = 5.7 cm for a 3 kHz sweep (two things closer together than that merge into
 * one peak), and the phone's speaker-to-mic latency is unknown, so distances are measured from the direct-path peak.
 */
object Sonar {
    const val RATE = 48_000
    /** 2048 samples = 42.7 ms per measurement; echoes from up to 7.3 m return before the next chirp. */
    const val PERIOD = 2_048
    const val CHIRP = 480 // 10 ms
    const val F0 = 17_000.0
    const val F1 = 20_000.0
    const val SOUND = 343.0 // m/s at about 20 °C
    /** Metres of round trip per sample of delay, halved: the distance one sample of delay means. */
    const val M_PER_SAMPLE = SOUND / RATE / 2

    /** One period: a tapered linear chirp, then silence while the echoes come back. Tapering keeps the start and end inaudible. */
    fun period(amplitude: Float = 0.5f): FloatArray {
        val out = FloatArray(PERIOD)
        val k = (F1 - F0) / (CHIRP.toDouble() / RATE)
        for (i in 0 until CHIRP) {
            val t = i.toDouble() / RATE
            val taper = tukey(i, CHIRP, 0.2)
            out[i] = (sin(2 * PI * (F0 * t + k * t * t / 2)) * taper * amplitude).toFloat()
        }
        return out
    }

    /** The transmitted signal at any real-valued sample position (wrapping each period): lets tests delay it by fractions of a sample. */
    fun at(x: Double, amplitude: Float = 0.5f): Double {
        val i = ((x % PERIOD) + PERIOD) % PERIOD
        if (i >= CHIRP - 1) return 0.0
        val t = i / RATE
        val k = (F1 - F0) / (CHIRP.toDouble() / RATE)
        val u = i / (CHIRP - 1); val a = 0.2
        val taper = when { u < a / 2 -> 0.5 * (1 - cos(2 * PI * u / a)); u > 1 - a / 2 -> 0.5 * (1 - cos(2 * PI * (1 - u) / a)); else -> 1.0 }
        return sin(2 * PI * (F0 * t + k * t * t / 2)) * taper * amplitude
    }

    fun periodPcm(amplitude: Float = 0.5f): ShortArray = period(amplitude).let { p -> ShortArray(p.size) { (p[it] * Short.MAX_VALUE).toInt().toShort() } }

    private fun tukey(i: Int, n: Int, a: Double): Double {
        val x = i.toDouble() / (n - 1)
        return when {
            x < a / 2 -> 0.5 * (1 - cos(2 * PI * x / a))
            x > 1 - a / 2 -> 0.5 * (1 - cos(2 * PI * (1 - x) / a))
            else -> 1.0
        }
    }

    /** The chirp's spectrum, computed once and reused for every period. */
    class Matched(reference: FloatArray = period()) {
        private val re = DoubleArray(PERIOD) { reference[it].toDouble() }
        private val im = DoubleArray(PERIOD)
        init { Fft.transform(re, im, false) }

        /**
         * Circular cross-correlation of one received [block] with the chirp, as a complex (analytic) profile: its
         * magnitude is the echo strength at each delay with no carrier ripple, and its phase moves with sub-millimetre
         * motion. Writes into [outRe]/[outIm] of length [PERIOD].
         */
        fun profile(block: FloatArray, outRe: DoubleArray, outIm: DoubleArray) {
            for (i in 0 until PERIOD) { outRe[i] = block[i].toDouble(); outIm[i] = 0.0 }
            Fft.transform(outRe, outIm, false)
            val half = PERIOD / 2
            for (i in 0 until PERIOD) {
                // X * conj(S), keeping only positive frequencies (doubled): the analytic signal of the correlation.
                val xr = outRe[i]; val xi = outIm[i]
                val pr = xr * re[i] + xi * im[i]; val pi = xi * re[i] - xr * im[i]
                val g = when { i == 0 || i == half -> 1.0; i < half -> 2.0; else -> 0.0 }
                outRe[i] = pr * g; outIm[i] = pi * g
            }
            Fft.transform(outRe, outIm, true)
        }
    }

    /** Index of the largest magnitude, with a parabolic refinement to a fraction of a sample. */
    fun peak(mag: DoubleArray, from: Int = 0, to: Int = mag.size): Double {
        var best = from
        for (i in from until to) if (mag[i] > mag[best]) best = i
        if (best <= from || best >= to - 1) return best.toDouble()
        val a = mag[best - 1]; val b = mag[best]; val c = mag[best + 1]
        val d = a - 2 * b + c
        return if (d == 0.0) best.toDouble() else best + 0.5 * (a - c) / d
    }

    fun magnitude(re: DoubleArray, im: DoubleArray, out: DoubleArray) { for (i in out.indices) out[i] = hypot(re[i], im[i]) }
}

/**
 * Turns successive echo profiles into a moving target: what changed against the learned still scene. The still scene
 * (the table, the phone's own body, the direct path) is learned as a slow complex average, so only movement stands
 * out, and because the comparison is complex, a hand moving even a millimetre changes the phase and shows up.
 */
class SonarTracker(private val maxRangeM: Double = 1.0, private val learn: Double = 0.06) {
    private val n = Sonar.PERIOD
    private val wr = DoubleArray(n); private val wi = DoubleArray(n)
    private val bgRe = DoubleArray(n); private val bgIm = DoubleArray(n)
    /** How much each distance normally changes from frame to frame when nothing is there. */
    private val sig = DoubleArray(n)
    private val diff = DoubleArray(n)
    private val mag = DoubleArray(n)
    private var frames = 0
    private var direct = -1.0
    private var lastRange = Double.NaN
    private var lastAt = 0L
    private var velocity = 0.0

    /** One measurement. All distances are from the direct path, so they measure from the phone itself. */
    data class Fix(
        /** The direct path's delay in samples: where "zero distance" sits in the profile. */
        val directLag: Double,
        /** Distance to the strongest moving reflector, metres; NaN when nothing moved clearly. */
        val rangeM: Double,
        /** How many times its usual change this distance changed: under ~5 is the room's own jitter. */
        val confidence: Double,
        /** Metres per second, from the range track; positive is moving away. NaN when unknown. */
        val velocity: Double,
        /** How strongly the direct path stands out: a weak link means the mic barely hears the speaker. */
        val linkSnr: Double,
        /** Change at each distance bin up to [maxRangeM], in units of that bin's usual change (index 0 = the phone). */
        val motion: FloatArray,
        /** Echo strength at each bin relative to the direct path, the still scene included. */
        val echo: FloatArray,
    )

    val bins: Int = (maxRangeM / Sonar.M_PER_SAMPLE).toInt()

    fun feed(re: DoubleArray, im: DoubleArray, now: Long): Fix {
        Sonar.magnitude(re, im, mag)
        frames++
        val p = Sonar.peak(mag)
        if (direct < 0 || abs(circular(p - direct)) > 3) direct = p
        val d0 = Math.round(direct).toInt() % n
        val median = mag.sortedArray()[n / 2].coerceAtLeast(1e-12)
        val linkSnr = mag[d0] / median
        // Lock the frame to the direct path: divide by its complex value. The speaker and mic clocks drift by fractions
        // of a sample and the volume wanders; both move the whole profile together, and this cancels them, so what is
        // left to change is what really moved. (Found on a real phone, where the drift alone looked like a target.)
        val zr = re[d0]; val zi = im[d0]; val z2 = (zr * zr + zi * zi).coerceAtLeast(1e-24)
        for (i in 0 until n) { wr[i] = (re[i] * zr + im[i] * zi) / z2; wi[i] = (im[i] * zr - re[i] * zi) / z2 }
        if (frames == 1) for (i in 0 until n) { bgRe[i] = wr[i]; bgIm[i] = wi[i] }
        for (i in 0 until n) diff[i] = hypot(wr[i] - bgRe[i], wi[i] - bgIm[i])
        // Judge each distance against its own usual change (constant false-alarm rate): bins beside the direct path
        // always wobble and need a big change to count; quiet bins far out notice a faint hand.
        val floor = 2e-4
        val start = (0.04 / Sonar.M_PER_SAMPLE).toInt()
        val motion = FloatArray(bins); val echo = FloatArray(bins)
        var best = start; var bestScore = 0.0
        for (k in 0 until bins) {
            val i = (d0 + k) % n
            val score = diff[i] / (sig[i] + floor)
            motion[k] = score.toFloat(); echo[k] = hypot(wr[i], wi[i]).toFloat()
            if (k >= start && score > bestScore) { bestScore = score; best = k }
        }
        val warm = frames > WARMUP
        val detected = warm && bestScore >= CONFIDENT
        val pk = if (best in start + 1 until bins - 1) {
            val a = motion[best - 1].toDouble(); val b = motion[best].toDouble(); val c = motion[best + 1].toDouble()
            val d = a - 2 * b + c; if (d == 0.0) best.toDouble() else best + 0.5 * (a - c) / d
        } else best.toDouble()
        val range = if (detected) pk * Sonar.M_PER_SAMPLE else Double.NaN
        if (!range.isNaN()) {
            if (!lastRange.isNaN() && now > lastAt) {
                val v = (range - lastRange) / ((now - lastAt) / 1000.0)
                velocity = if (abs(v) < 5) velocity * 0.6 + v * 0.4 else velocity // a jump of metres in a frame is a different reflector, not speed
            }
            lastRange = range; lastAt = now
        } else if (now - lastAt > 400) { lastRange = Double.NaN; velocity = 0.0 }
        // Learn the still scene and its usual jitter; barely while something is moving, so a hand held still fades in slowly
        // and a moving hand doesn't teach the tracker that its own bins are noisy.
        val rate = if (detected) learn * 0.15 else learn
        val sRate = if (!warm) 0.3 else if (detected) 0.0 else 0.04
        for (i in 0 until n) {
            sig[i] += (diff[i] - sig[i]) * sRate
            bgRe[i] += (wr[i] - bgRe[i]) * rate; bgIm[i] += (wi[i] - bgIm[i]) * rate
        }
        return Fix(direct, range, bestScore, if (lastRange.isNaN()) Double.NaN else velocity, linkSnr, motion, echo)
    }

    private fun circular(d: Double): Double { var x = d % n; if (x > n / 2) x -= n; if (x < -n / 2) x += n; return x }

    companion object {
        const val WARMUP = 24 // about a second of learning the room and its jitter
        const val CONFIDENT = 5.0
    }
}

/**
 * Direction from two microphones: a reflector off to one side reaches one mic a little earlier. With the mics ~1.5 cm
 * apart that is at most ±2 samples, so this gives a lean (left, centre, right), never a sharp bearing.
 */
object Bearing {
    /** Delay of [b] behind [a] in samples, by cross-correlating the two motion profiles near the target. */
    fun lag(a: FloatArray, b: FloatArray, around: Int, span: Int = 24, maxLag: Int = 4): Double {
        val lo = (around - span).coerceAtLeast(0); val hi = (around + span).coerceAtMost(minOf(a.size, b.size) - 1)
        if (hi - lo < 4) return 0.0
        val scores = DoubleArray(2 * maxLag + 1) { j ->
            val l = j - maxLag
            var s = 0.0
            for (i in lo..hi) { val k = i + l; if (k in b.indices) s += a[i] * b[k] }
            s
        }
        return Sonar.peak(scores) - maxLag
    }

    /** Angle in degrees from straight ahead, for mics [spacingM] apart; clamped, because noise can exceed the geometry. */
    fun angle(lagSamples: Double, spacingM: Double): Double {
        val s = (lagSamples / Sonar.RATE * Sonar.SOUND / spacingM).coerceIn(-1.0, 1.0)
        return Math.toDegrees(kotlin.math.asin(s))
    }
}

/** A small iterative radix-2 FFT, in place. */
object Fft {
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        require(n and (n - 1) == 0) { "FFT size must be a power of two" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * PI / len * if (inverse) 1 else -1
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ar = re[i + k]; val ai = im[i + k]
                    val br = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val bi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ar + br; im[i + k] = ai + bi
                    re[i + k + len / 2] = ar - br; im[i + k + len / 2] = ai - bi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }

    /** Magnitudes of [x] (windowed) between two frequencies, for the live spectrum. */
    fun band(x: FloatArray, fromHz: Double, toHz: Double, rate: Int = Sonar.RATE): FloatArray {
        val n = Integer.highestOneBit(x.size)
        val re = DoubleArray(n) { x[it] * (0.5 - 0.5 * cos(2 * PI * it / (n - 1))) }; val im = DoubleArray(n)
        transform(re, im, false)
        val a = (fromHz * n / rate).toInt(); val b = (toHz * n / rate).toInt().coerceAtMost(n / 2)
        return FloatArray(b - a) { k -> sqrt(re[a + k] * re[a + k] + im[a + k] * im[a + k]).toFloat() }
    }
}
