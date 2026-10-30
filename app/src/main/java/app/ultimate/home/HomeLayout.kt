package app.ultimate.home

/** Which tools Home shows as big live tiles at the top. Pure, so the choice is tested. */
object HomeLayout {
    private const val DAY = 86_400_000L

    /** What a new install shows before it has learned anything. */
    val STARTERS = listOf("studio", "squeeze", "files", "storage", "recorder", "cleanup")

    /**
     * Anything running comes first, then the tools opened most (a recent open counts extra for a few days), then the
     * starters for whatever is still missing. At most [count] ids, each once, only ones in [available].
     */
    fun favorites(available: List<String>, opens: (String) -> Int, lastAt: (String) -> Long, running: Set<String>, now: Long, count: Int = 4): List<String> {
        val have = available.toSet()
        fun score(id: String): Double {
            val n = opens(id)
            if (n <= 0) return 0.0
            val days = (now - lastAt(id)) / DAY.toDouble()
            return n + maxOf(0.0, 5.0 - days)
        }
        val used = available.filter { opens(it) > 0 }.sortedByDescending { score(it) }
        return (available.filter { it in running } + used + STARTERS.filter { it in have } + available).distinct().take(count)
    }

    /** Freeze this ordering during a visit; time-of-day only uses observed opens within 48 hours. */
    fun constellation(available: List<String>, recent: List<Pair<String, Long>>, running: Set<String>, now: Long, count: Int = 6): List<String> {
        val hour = java.util.Calendar.getInstance().apply { timeInMillis = now }.get(java.util.Calendar.HOUR_OF_DAY)
        fun score(id: String): Double = recent.asSequence().filter { it.first == id && it.second in (now - 2 * DAY)..now }.sumOf { (_, at) ->
            val usedHour = java.util.Calendar.getInstance().apply { timeInMillis = at }.get(java.util.Calendar.HOUR_OF_DAY)
            val distance = kotlin.math.abs(hour - usedHour).let { minOf(it, 24 - it) }
            1.0 + (1.0 - (now - at) / (2 * DAY).toDouble()) + if (distance <= 2) 1.0 else 0.0
        }
        val rank = available.distinct().sortedWith(compareByDescending<String> { it in running }.thenByDescending { score(it) }
            .thenBy { STARTERS.indexOf(it).let { n -> if (n < 0) Int.MAX_VALUE else n } })
        return rank.take(count.coerceAtLeast(0))
    }
}
