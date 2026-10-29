package app.ultimate.audio.model

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A standard second-order filter (RBJ Audio EQ Cookbook). One instance per channel, since it keeps state. */
class Biquad private constructor(private val b0: Double, private val b1: Double, private val b2: Double, private val a1: Double, private val a2: Double) {
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

    fun next(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }

    companion object {
        private fun of(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) = Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

        fun lowPass(rate: Int, hz: Double, q: Double = 0.7071): Biquad {
            val w = 2 * PI * hz / rate; val alpha = sin(w) / (2 * q); val c = cos(w)
            return of((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }

        fun lowShelf(rate: Int, hz: Double, db: Double): Biquad {
            val a = 10.0.pow(db / 40); val w = 2 * PI * hz / rate; val c = cos(w); val alpha = sin(w) / 2 * sqrt(2.0); val sa = 2 * sqrt(a) * alpha
            return of(a * ((a + 1) - (a - 1) * c + sa), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - sa),
                (a + 1) + (a - 1) * c + sa, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - sa)
        }

        fun highShelf(rate: Int, hz: Double, db: Double): Biquad {
            val a = 10.0.pow(db / 40); val w = 2 * PI * hz / rate; val c = cos(w); val alpha = sin(w) / 2 * sqrt(2.0); val sa = 2 * sqrt(a) * alpha
            return of(a * ((a + 1) + (a - 1) * c + sa), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - sa),
                (a + 1) - (a - 1) * c + sa, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - sa)
        }
    }
}

/**
 * Bass, treble, high-cut, fades and overall gain on interleaved 16-bit PCM, in place. [totalFrames] is the length of the
 * whole output, so the fade-out lands at the real end.
 */
class ToneShaper(
    private val rate: Int, private val channels: Int, bassDb: Float, trebleDb: Float, highCutHz: Int,
    private val fadeInFrames: Long, private val fadeOutFrames: Long, private val totalFrames: Long, private val gain: Float = 1f,
) {
    private val chains: List<List<Biquad>> = List(channels) {
        buildList {
            if (bassDb != 0f) add(Biquad.lowShelf(rate, 120.0, bassDb.toDouble()))
            if (trebleDb != 0f) add(Biquad.highShelf(rate, 6000.0, trebleDb.toDouble()))
            if (highCutHz in 1 until rate / 2) add(Biquad.lowPass(rate, highCutHz.toDouble()))
        }
    }
    private var frame = 0L

    fun process(samples: ShortArray, count: Int) {
        var i = 0
        while (i + channels <= count) {
            var env = gain.toDouble()
            if (fadeInFrames > 0 && frame < fadeInFrames) env *= frame.toDouble() / fadeInFrames
            val left = totalFrames - frame
            if (fadeOutFrames > 0 && left < fadeOutFrames) env *= (left.coerceAtLeast(0)).toDouble() / fadeOutFrames
            for (c in 0 until channels) {
                var v = samples[i + c].toDouble()
                for (f in chains[c]) v = f.next(v)
                samples[i + c] = (v * env).toInt().coerceIn(-32768, 32767).toShort()
            }
            frame++
            i += channels
        }
    }
}
