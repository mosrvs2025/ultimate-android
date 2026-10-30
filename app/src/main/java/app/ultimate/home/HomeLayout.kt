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
}
