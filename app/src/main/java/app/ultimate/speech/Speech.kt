package app.ultimate.speech

/** A stretch of speech and when it happens, in milliseconds of the file it came from. */
data class Segment(val startMs: Long, val endMs: Long, val text: String)

/**
 * Splits speech into caption-sized pieces at its quietest moments, so each piece is recognised on its own and its timing is
 * known exactly. Works on loudness per 100 ms window. Pure, so it is tested.
 */
object Chunker {
    const val WINDOW_MS = 100L

    /** Mean absolute level per [WINDOW_MS] of 16 kHz mono PCM. */
    fun levels(pcm: ShortArray, rate: Int = 16_000): FloatArray {
        val per = (rate * WINDOW_MS / 1000).toInt()
        return FloatArray((pcm.size + per - 1) / per) { w ->
            var sum = 0L; val from = w * per; val to = minOf(pcm.size, from + per)
            for (i in from until to) sum += kotlin.math.abs(pcm[i].toInt())
            sum.toFloat() / (to - from).coerceAtLeast(1)
        }
    }

    /**
     * Window ranges [start, end) of [minWin]..[maxWin] windows, cut at the quietest window in each stretch. Stretches quieter
     * than [silence] throughout are dropped: nothing is said there.
     */
    fun split(levels: FloatArray, minWin: Int = 20, maxWin: Int = 70, silence: Float = 120f): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var start = 0
        while (start < levels.size) {
            val remaining = levels.size - start
            val end = if (remaining <= maxWin) levels.size else {
                var cut = start + maxWin; var lowest = Float.MAX_VALUE
                for (w in start + minWin until start + maxWin) if (levels[w] < lowest) { lowest = levels[w]; cut = w }
                cut
            }
            if ((start until end).any { levels[it] >= silence }) out += start until end
            start = end
        }
        return out
    }
}

object Captions {
    /** The caption showing at [ms], or null between captions. */
    fun at(segments: List<Segment>, ms: Long): String? = segments.firstOrNull { ms >= it.startMs && ms < it.endMs }?.text

    /** Plain transcript: one line per segment, with its start time. */
    fun transcript(segments: List<Segment>): String = segments.joinToString("\n") { s ->
        val t = s.startMs / 1000
        "[%d:%02d] %s".format(t / 60, t % 60, s.text)
    }
}
