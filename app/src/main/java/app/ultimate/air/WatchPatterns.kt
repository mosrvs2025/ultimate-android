package app.ultimate.air

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A wrist flick is two spikes: the flick, then a smaller one back the other way as the wrist stops. This keeps the
 * first and absorbs the rebound, so one flick is one gesture (and a rule fires once). Pure, so it is tested.
 */
class FlickDebounce(private val reboundMs: Long = 450) {
    private var lastKind: String? = null
    private var lastAt = Long.MIN_VALUE / 2
    /** True when [kind] at [t] is a real gesture; false when it's the rebound of the one just before. */
    fun accept(kind: String, t: Long): Boolean {
        val rebound = t - lastAt < reboundMs && opposite(lastKind) == kind
        if (rebound) return false
        lastKind = kind; lastAt = t; return true
    }
    companion object {
        fun opposite(k: String?) = when (k) { "flick_left" -> "flick_right"; "flick_right" -> "flick_left"; "flick_up" -> "flick_down"; "flick_down" -> "flick_up"; else -> null }
    }
}

/**
 * Pattern Studio: teach the phone any motion your watch feels (scratching, fidgeting, a gym rep, brushing teeth) from a
 * few seconds of it, then notice it whenever it happens again. A pattern is summarised by how much and how fast each
 * axis moves and how the axes move together, so it works whatever your wrist's exact position. Pure, so it is tested.
 */
object WatchPatterns {
    /** One reading: turn rates (rad/s) about x, y, z and the jolt (m/s²). */
    data class Frame(val t: Long, val x: Float, val y: Float, val z: Float, val jolt: Float)
    /** The signature of a stretch of motion. */
    data class Signature(val energy: List<Double>, val rate: List<Double>, val coupling: List<Double>, val jolt: Double) {
        fun encode() = (energy + rate + coupling + jolt).joinToString(",") { "%.4f".format(java.util.Locale.ROOT, it) }
        companion object { fun decode(s: String) = s.split(",").mapNotNull { it.toDoubleOrNull() }.takeIf { it.size == 10 }?.let { Signature(it.subList(0, 3), it.subList(3, 6), it.subList(6, 9), it[9]) } }
    }

    fun signature(frames: List<Frame>): Signature? {
        if (frames.size < 20) return null
        val secs = ((frames.last().t - frames.first().t) / 1000.0).coerceAtLeast(0.5)
        val axes = listOf(frames.map { it.x.toDouble() }, frames.map { it.y.toDouble() }, frames.map { it.z.toDouble() })
        fun centred(a: List<Double>) = a.average().let { m -> a.map { it - m } }
        val c = axes.map(::centred)
        val energy = c.map { a -> sqrt(a.sumOf { it * it } / a.size) }
        // How often each axis swings back and forth: zero crossings per second, a cheap stand-in for its main frequency.
        val rate = c.map { a -> a.zipWithNext().count { (p, q) -> p * q < 0 } / secs / 2 }
        fun corr(a: List<Double>, b: List<Double>): Double { val d = sqrt(a.sumOf { it * it } * b.sumOf { it * it }); return if (d < 1e-9) 0.0 else a.zip(b).sumOf { (p, q) -> p * q } / d }
        return Signature(energy, rate, listOf(corr(c[0], c[1]), corr(c[0], c[2]), corr(c[1], c[2])), frames.map { it.jolt.toDouble() }.average())
    }

    /** 0..1: how alike two signatures are. */
    fun similarity(a: Signature, b: Signature): Double {
        fun rel(p: Double, q: Double) = abs(p - q) / (maxOf(abs(p), abs(q)) + 0.3)
        val e = a.energy.indices.map { rel(a.energy[it], b.energy[it]) }.average()
        val r = a.rate.indices.map { rel(a.rate[it], b.rate[it]) }.average()
        val c = a.coupling.indices.map { abs(a.coupling[it] - b.coupling[it]) / 2 }.average()
        val j = rel(a.jolt, b.jolt)
        return (1 - (0.4 * e + 0.25 * r + 0.25 * c + 0.1 * j)).coerceIn(0.0, 1.0)
    }

    /** The best-matching taught pattern for [now], if it's close enough and moving enough to count. */
    fun match(now: Signature, taught: Map<String, Signature>, min: Double = 0.78): Pair<String, Double>? {
        if (now.energy.max() < 0.25) return null   // a still wrist matches nothing
        return taught.mapValues { similarity(now, it.value) }.maxByOrNull { it.value }?.takeIf { it.value >= min }?.toPair()
    }
}
