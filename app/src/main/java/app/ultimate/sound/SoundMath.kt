package app.ultimate.sound

import kotlin.math.abs
import kotlin.math.log10

/** Digital levels only. An acoustic reference is a separate, explicitly supplied calibration. */
object SoundMath {
    fun db(power: Double): Double = 10.0 * log10(power.coerceAtLeast(1e-12))
    fun measure(pcm: ShortArray, count: Int): SoundBlock {
        require(count in 1..pcm.size)
        var energy = 0.0
        var peak = 0.0
        var clipped = 0L
        for (i in 0 until count) {
            val sample = pcm[i].toDouble() / 32768.0
            energy += sample * sample
            peak = maxOf(peak, abs(sample))
            if (pcm[i] == Short.MAX_VALUE || pcm[i] == Short.MIN_VALUE) clipped++
        }
        return SoundBlock(count.toLong(), energy, peak * peak, clipped)
    }
}

data class SoundBlock(val samples: Long, val energy: Double, val peakPower: Double, val clipped: Long) {
    val rmsDb get() = SoundMath.db(energy / samples)
    val peakDb get() = SoundMath.db(peakPower)
}

/** Weigh by actual sample counts, including partial reads; never average decibel values. */
class SoundAccumulator {
    var samples = 0L; private set
    var energy = 0.0; private set
    var peakPower = 0.0; private set
    var clipped = 0L; private set
    var minDb = Double.POSITIVE_INFINITY; private set
    var maxDb = Double.NEGATIVE_INFINITY; private set
    val averageDb get() = if (samples == 0L) -120.0 else SoundMath.db(energy / samples)
    fun add(block: SoundBlock) {
        require(block.samples > 0 && block.energy.isFinite() && block.energy >= 0)
        samples += block.samples
        energy += block.energy
        peakPower = maxOf(peakPower, block.peakPower)
        clipped += block.clipped
        minDb = minOf(minDb, block.rmsDb)
        maxDb = maxOf(maxDb, block.rmsDb)
    }
}

data class SoundPoint(val timeMs: Long, val rmsDb: Double, val peakDb: Double, val clipped: Long, val samples: Long)
data class SoundSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val durationMs: Long,
    val averageDb: Double,
    val minDb: Double,
    val maxDb: Double,
    val peakDb: Double,
    val clipped: Long,
    val offset: Double?,
    val route: String,
    val endReason: String,
    val points: List<SoundPoint>,
) {
    val unit get() = if (offset == null) "dBFS" else "dB SPL (estimate)"
    fun display(db: Double) = db + (offset ?: 0.0)
}
