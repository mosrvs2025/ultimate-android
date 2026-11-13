package app.ultimate.fusion

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * How a number came to exist. This is the line the whole fusion layer keeps straight, because a language model (or a
 * hurried reader) cannot tell these apart by looking at the digits:
 *
 *  - [Observed]: read directly from a sensor or entered by a person (a step the accelerometer felt, a GPS fix, a distance the user measured).
 *  - [Derived]: computed from observations by a fixed, deterministic rule, so the same input always gives the same output (a path length, a cadence, a lap count). Its uncertainty is modelled.
 *  - [Inferred]: a judgement made from patterns (a gait label, "the walk was probably a loop"). Useful, never to be taught back as truth.
 */
enum class Basis(val label: String) { Observed("OBSERVED"), Derived("DERIVED"), Inferred("INFERRED") }

/** A measurement that carries its own doubt: [sigma] is one standard deviation in the same unit as [value]. */
data class Quantity(val value: Double, val sigma: Double, val unit: String, val basis: Basis, val source: String, val note: String = "") {
    /** The interval that holds the truth about 95% of the time if the stated sigma is honest. */
    val low: Double get() = value - 2 * sigma
    val high: Double get() = value + 2 * sigma
    fun text(digits: Int = 0): String = "%.${digits}f ± %.${digits}f %s".format(value, 2 * sigma, unit) // shown as the 95% band

    /** The z-score of the gap between two measurements of the same thing: how many combined sigmas apart they are. */
    fun zAgainst(o: Quantity): Double = abs(value - o.value) / sqrt(sigma * sigma + o.sigma * o.sigma).coerceAtLeast(1e-9)
}

/** A stretch of the recording, in seconds from its start. */
data class Span(val from: Double, val to: Double) {
    val length: Double get() = to - from
    operator fun contains(t: Double) = t >= from && t <= to
    fun overlaps(o: Span) = from < o.to && o.from < to
}

/** One thing the analysis noticed, with the evidence under it, so a reader (or a question) can say why. */
data class Finding(val kind: String, val span: Span, val text: String, val basis: Basis, val confidence: Double)
