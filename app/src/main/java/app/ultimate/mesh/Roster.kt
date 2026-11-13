package app.ultimate.mesh

/**
 * Remembers which devices this phone has met, so it can say "a new phone joined" the first time and stay quiet when a
 * known phone merely wobbles off the Wi-Fi for a moment. Pure: the Android layer saves and loads [known].
 */
class Roster(initial: Collection<Known> = emptyList(), private val backAfterMs: Long = 10 * 60_000L) {
    data class Known(val id: String, val name: String, val firstSeen: Long, val lastSeen: Long)

    sealed interface Sighting {
        /** Never seen before under this pairing code. */
        data class New(val known: Known) : Sighting
        /** Known, and it has been gone at least [backAfterMs]. */
        data class Back(val known: Known, val awayMs: Long) : Sighting
        /** Known and seen recently: nothing to announce. */
        data object Same : Sighting
    }

    private val byId = initial.associateBy { it.id }.toMutableMap()
    val known: List<Known> get() = byId.values.sortedBy { it.firstSeen }

    /** Records a device being seen at [now] and says whether it deserves an announcement. */
    @Synchronized fun seen(id: String, name: String, now: Long): Sighting {
        val old = byId[id]
        val k = Known(id, name, old?.firstSeen ?: now, now)
        byId[id] = k
        return when {
            old == null -> Sighting.New(k)
            now - old.lastSeen >= backAfterMs -> Sighting.Back(k, now - old.lastSeen)
            else -> Sighting.Same
        }
    }

    /** Touches the last-seen time without announcing anything (used while a device stays connected). */
    @Synchronized fun touch(id: String, now: Long) { byId[id]?.let { byId[id] = it.copy(lastSeen = now) } }

    @Synchronized fun forget(id: String) { byId.remove(id) }
    @Synchronized fun forgetAll() { byId.clear() }

    fun encode(): String = known.joinToString("\n") { listOf(it.id, it.firstSeen, it.lastSeen, it.name.replace('\n', ' ').replace('\t', ' ')).joinToString("\t") }

    companion object {
        fun decode(text: String, backAfterMs: Long = 10 * 60_000L): Roster = Roster(text.lines().mapNotNull { line ->
            val p = line.split('\t', limit = 4)
            if (p.size < 4) null else runCatching { Known(p[0], p[3], p[1].toLong(), p[2].toLong()) }.getOrNull()
        }, backAfterMs)
    }
}
