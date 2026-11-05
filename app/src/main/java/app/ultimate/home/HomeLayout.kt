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
    /** Pins first (in pin order), then the usual favourites; showing at least [count], and every pin. */
    fun withPins(pins: List<String>, favourites: List<String>, available: List<String>, count: Int = 4): List<String> {
        val have = available.toSet()
        val pinned = pins.filter { it in have }
        return (pinned + favourites.filter { it !in pinned }).take(maxOf(count, pinned.size).coerceAtMost(8))
    }

    /** Adds [id] to the pins, or takes it off if it was there. At most eight. */
    fun togglePin(pins: List<String>, id: String) = if (id in pins) pins - id else (pins + id).takeLast(8)

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

/** Tools you pinned to the top of Home (hold one on Home or in Jump to), in the order you pinned them. Kept on the phone. */
object HomePins {
    private var prefs: android.content.SharedPreferences? = null
    private val _ids = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList())
    fun ids(context: android.content.Context): kotlinx.coroutines.flow.StateFlow<List<String>> {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("home", android.content.Context.MODE_PRIVATE).also { p ->
            _ids.value = p.getString("pins", "").orEmpty().split(',').filter { it.isNotBlank() }
        }
        return _ids
    }
    fun toggle(context: android.content.Context, id: String) {
        ids(context)
        _ids.value = HomeLayout.togglePin(_ids.value, id)
        prefs?.edit()?.putString("pins", _ids.value.joinToString(","))?.apply()
    }
}

