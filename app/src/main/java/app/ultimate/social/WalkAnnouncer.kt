package app.ultimate.social

/** The owner's choices about telling friends. Everything starts off. */
data class AnnounceSettings(
    val enabled: Boolean = false,
    /** Friends who are told when a walk starts; nobody is told who is not listed here. */
    val notify: Set<String> = emptySet(),
    /** false: tell them automatically; true: ask first ("Tell your friends?"). */
    val askFirst: Boolean = true,
    /** A walk counts only after this long of steady walking, so a trip to the kitchen tells nobody. */
    val steadyMinutes: Int = 4,
    /** Friends are told at most once in this many minutes. */
    val cooldownMinutes: Int = 180,
    /** No announcements between these local times (minutes after midnight); equal numbers mean no quiet hours. */
    val quietFromMin: Int = 22 * 60, val quietToMin: Int = 7 * 60,
    /** "Go private": nothing is announced until this moment (epoch ms). */
    val privateUntil: Long = 0L,
)

sealed interface Announce {
    /** Tell [friends] now. */
    data class Now(val friends: Set<String>, val state: String) : Announce
    /** Ask the owner first; if they agree, tell [friends]. */
    data class Ask(val friends: Set<String>, val state: String) : Announce
}

/**
 * Decides when a walk is worth telling friends about. It is fed what the watch says (walking, running, still, and the steady
 * "still moving" reports it sends while you walk) and answers once per walk, after the owner's chosen time of steady walking, if
 * every one of the owner's rules allows it: switched on, not "go private", outside quiet hours, and at least one friend who is
 * both chosen and not told recently. Pure and clock-free: time is whatever is passed in, so every rule is tested exactly.
 */
class WalkAnnouncer(private var settings: AnnounceSettings = AnnounceSettings()) {
    private var boutStart = 0L
    private var lastMove = 0L
    private var state = "walking"
    private var decided = false
    private val lastTold = HashMap<String, Long>()

    fun configure(s: AnnounceSettings) { settings = s }

    /** Records that [friend] was told at [at] (also called when the server says it sent the notification). */
    fun told(friend: String, at: Long) { lastTold[friend] = at }
    fun restore(told: Map<String, Long>) { lastTold.clear(); lastTold.putAll(told) }
    fun toldTimes(): Map<String, Long> = HashMap(lastTold)

    /**
     * One report from the watch at [now]. [minuteOfDay] is the local time (0..1439) for the quiet hours. Returns what to do, once
     * per walk, or null. The walk ends when the watch says still, or nothing moving has been heard for [GAP_MS].
     */
    fun hear(kind: String, now: Long, minuteOfDay: Int): Announce? {
        val moving = kind == "walking" || kind == "running"
        if (!moving) { if (kind == "still") reset(); return null }
        if (boutStart != 0L && now - lastMove > GAP_MS) reset()
        if (boutStart == 0L) boutStart = now
        lastMove = now; state = kind
        if (decided || now - boutStart < settings.steadyMinutes * 60_000L) return null
        decided = true // one answer per walk, yes or no
        val s = settings
        if (!s.enabled || now < s.privateUntil || inQuiet(minuteOfDay, s)) return null
        val friends = s.notify.filter { f -> now - (lastTold[f] ?: Long.MIN_VALUE / 2) >= s.cooldownMinutes * 60_000L }.toSet()
        if (friends.isEmpty()) return null
        return if (s.askFirst) Announce.Ask(friends, state) else Announce.Now(friends, state)
    }

    private fun reset() { boutStart = 0L; lastMove = 0L; decided = false }

    companion object {
        /** Silence this long during a walk ends it (the watch reports every half minute while you move). */
        const val GAP_MS = 150_000L

        fun inQuiet(minute: Int, s: AnnounceSettings): Boolean {
            if (s.quietFromMin == s.quietToMin) return false
            return if (s.quietFromMin < s.quietToMin) minute in s.quietFromMin until s.quietToMin else minute >= s.quietFromMin || minute < s.quietToMin
        }
    }
}
