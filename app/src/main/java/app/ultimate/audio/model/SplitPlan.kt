package app.ultimate.audio.model

import kotlin.math.abs
import kotlin.math.max

/**
 * Cutting a long recording into parts: where the parts start and end, how to nudge each cut into the quietest moment nearby (so
 * no word is chopped in half), and what the files are called. Plain arithmetic, so it is tested without a phone.
 */
object SplitPlan {
    /** A last part shorter than this share of a full part is joined to the one before it, instead of being a stray little file. */
    const val MIN_TAIL = 0.25
    /** The lengths offered, in minutes. */
    val CHOICES = listOf(5, 10, 15, 30, 60, 120)
    /** How far either side of a planned cut to look for a quiet moment, at most. */
    const val SEARCH_MS = 30_000L
    /** Loudness is measured in slices of this length, and a pause must last this many of them to count. */
    const val HOP_MS = 100
    const val PAUSE_HOPS = 4

    data class Part(val index: Int, val startMs: Long, val endMs: Long) { val ms get() = endMs - startMs }

    /**
     * [startMs] to [endMs] in parts of [partMs]. Times are on the original recording. A range not much longer than one part stays
     * whole, and a short leftover is folded into the last part.
     */
    fun plan(startMs: Long, endMs: Long, partMs: Long): List<Part> {
        val total = endMs - startMs
        if (partMs <= 0 || total <= 0) return listOf(Part(0, startMs, max(endMs, startMs)))
        var full = (total / partMs).toInt()
        if (full == 0) return listOf(Part(0, startMs, endMs))
        // A leftover too small to be a file of its own stays on the last full part; a bigger one becomes a part of its own.
        if (total - full * partMs >= partMs * MIN_TAIL) full += 1
        val cuts = (1 until full).map { startMs + it * partMs }
        val bounds = listOf(startMs) + cuts + listOf(endMs)
        return (0 until bounds.size - 1).map { Part(it, bounds[it], bounds[it + 1]) }
    }

    /**
     * Where the quietest pause is in a stretch of sound, as milliseconds from its start. [energies] is the mean power in each
     * [hopMs] slice; [aroundMs] is where the cut was planned, so of two equally quiet places the nearer wins. A flat run of silence
     * is a pause; so is the dip between two sentences. Null if the stretch is too short to say.
     */
    fun quietestMs(energies: DoubleArray, hopMs: Int, aroundMs: Long, pauseHops: Int = PAUSE_HOPS): Long? {
        if (energies.size < pauseHops) return null
        val loudest = energies.max()
        // Anything under a millionth of the loudest slice is as good as silent; the floor keeps a run of exact zeros from tying everything.
        val floor = max(loudest * 1e-6, 1e-12)
        val half = max(energies.size * hopMs / 2.0, 1.0)
        var best = Double.MAX_VALUE
        var bestCentre = -1L
        for (s in 0..energies.size - pauseHops) {
            var sum = 0.0
            for (k in 0 until pauseHops) sum += energies[s + k]
            val centre = (s + pauseHops / 2.0) * hopMs
            val score = (sum / pauseHops + floor) * (1.0 + 0.5 * abs(centre - aroundMs) / half)
            if (score < best) { best = score; bestCentre = centre.toLong() }
        }
        return bestCentre.takeIf { it >= 0 }
    }

    /**
     * Moves the cuts between [parts] to [cuts] (one per cut, null to keep the planned one). A cut never moves more than half way to
     * its neighbour, so no part can vanish and the parts always add up to the whole range.
     */
    fun nudge(parts: List<Part>, cuts: List<Long?>): List<Part> {
        if (parts.size < 2) return parts
        val bounds = (listOf(parts.first().startMs) + parts.drop(1).map { it.startMs } + listOf(parts.last().endMs)).toMutableList()
        for (i in 1 until bounds.size - 1) {
            val wanted = cuts.getOrNull(i - 1) ?: continue
            val low = bounds[i - 1] + (bounds[i] - bounds[i - 1]) / 2
            val high = bounds[i] + (bounds[i + 1] - bounds[i]) / 2
            bounds[i] = wanted.coerceIn(low, high)
        }
        return (0 until bounds.size - 1).map { Part(it, bounds[it], bounds[it + 1]) }
    }

    /** "Meeting - part 03 of 08": numbers padded so the files sort in order in any music app. */
    fun partName(base: String, index: Int, count: Int): String {
        val width = count.toString().length.coerceAtLeast(2)
        return "$base - part ${(index + 1).toString().padStart(width, '0')} of ${count.toString().padStart(width, '0')}"
    }

    fun minutesLabel(min: Int) = if (min >= 60 && min % 60 == 0) "${min / 60} h" else "$min min"
}
