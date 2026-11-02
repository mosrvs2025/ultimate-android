package app.ultimate.focus

/** What one check found: working on the task, drifting away from it, or not sure. */
enum class Verdict { OnTask, Drift, Unsure }

/** One look at the phone: which app was in front, and what that means for the task. [strength] says how sure a drift is (0 to 1). */
data class Check(val at: Long, val pkg: String, val verdict: Verdict, val strength: Float = 0f)

/** A finished or running focus session. Times are the phone's clock; [pausedMs] is time spent paused. */
data class Session(
    val id: Long, val task: String, val startedAt: Long, val plannedMs: Long,
    val endedAt: Long = 0, val pausedMs: Long = 0, val pausedAt: Long = 0,
    val allowed: Set<String> = emptySet(), val checks: List<Check> = emptyList(), val nudges: List<Long> = emptyList(),
) {
    val running get() = endedAt == 0L
    val paused get() = pausedAt != 0L

    /** Focus time used so far: wall time since the start, minus pauses. */
    fun elapsed(now: Long): Long = ((if (running) (if (paused) pausedAt else now) else endedAt) - startedAt - pausedMs).coerceAtLeast(0)
    fun left(now: Long): Long = (plannedMs - elapsed(now)).coerceAtLeast(0)
}

/** A stretch of the session timeline in one colour. Paused stretches have [verdict] null. */
data class Segment(val start: Long, val end: Long, val verdict: Verdict?)

/**
 * Decides what an app in front means for the task, from the phone alone: no screenshots, no network. Apps you picked
 * for the task are on task; games, social, video and news apps are drift (games and social the strongest); anything
 * else, including the home screen, is unsure. Music is left unsure, since plenty of people work to it.
 */
object FocusClassifier {
    // ApplicationInfo.CATEGORY_* values, copied so this stays a plain JVM object.
    const val CATEGORY_GAME = 0; const val CATEGORY_AUDIO = 1; const val CATEGORY_VIDEO = 2; const val CATEGORY_IMAGE = 3
    const val CATEGORY_SOCIAL = 4; const val CATEGORY_NEWS = 5; const val CATEGORY_MAPS = 6; const val CATEGORY_PRODUCTIVITY = 7

    private val known = mapOf(
        "com.zhiliaoapp.musically" to 0.95f, "com.ss.android.ugc.trill" to 0.95f, "com.instagram.android" to 0.9f, "com.facebook.katana" to 0.85f,
        "com.twitter.android" to 0.85f, "com.reddit.frontpage" to 0.85f, "com.snapchat.android" to 0.85f, "com.google.android.youtube" to 0.8f,
        "com.netflix.mediaclient" to 0.85f, "tv.twitch.android.app" to 0.85f, "com.pinterest" to 0.75f, "com.tumblr" to 0.8f,
        "com.facebook.orca" to 0.6f, "com.whatsapp" to 0.55f, "org.telegram.messenger" to 0.55f, "com.discord" to 0.7f,
    )

    fun classify(pkg: String, allowed: Set<String>, category: Int?, ownPackage: String, launchers: Set<String> = emptySet()): Pair<Verdict, Float> = when {
        pkg == ownPackage || pkg in allowed -> Verdict.OnTask to 0f
        pkg in launchers || pkg == "com.android.systemui" || pkg.isBlank() -> Verdict.Unsure to 0f
        pkg in known -> Verdict.Drift to known.getValue(pkg)
        category == CATEGORY_GAME -> Verdict.Drift to 0.9f
        category == CATEGORY_SOCIAL -> Verdict.Drift to 0.8f
        category == CATEGORY_VIDEO -> Verdict.Drift to 0.75f
        category == CATEGORY_NEWS -> Verdict.Drift to 0.6f
        category == CATEGORY_PRODUCTIVITY -> Verdict.OnTask to 0f
        else -> Verdict.Unsure to 0f
    }
}

/**
 * When to nudge. Drift has to hold across checks in a row before anything is said, and the run needed shrinks the
 * stronger the drift is: two checks for a game or a feed, four for something only a little off. An unsure or
 * on-task check resets the run, and after a nudge there is a quiet spell before the next.
 */
class FocusPolicy(private val cooldownMs: Long = 120_000) {
    private var run = 0
    private var runStrength = 0f
    private var lastNudge = Long.MIN_VALUE / 2

    fun needed(strength: Float) = when { strength >= 0.85f -> 2; strength >= 0.6f -> 3; else -> 4 }

    /** Feeds one check in; true when it is time to nudge. */
    fun next(c: Check): Boolean {
        if (c.verdict != Verdict.Drift) { run = 0; runStrength = 0f; return false }
        run++; runStrength = maxOf(runStrength, c.strength)
        if (run >= needed(runStrength) && c.at - lastNudge >= cooldownMs) { lastNudge = c.at; run = 0; runStrength = 0f; return true }
        return false
    }

    /** Pausing, the screen going off or a lost check: start counting again from nothing. */
    fun reset() { run = 0; runStrength = 0f }
}

object FocusMath {
    /** Minutes and seconds, or hours too when it is that long. */
    fun countdown(ms: Long): String {
        val s = (ms + 999) / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /**
     * The coloured bar: each check colours the time up to the next one (checks run every [everyMs]); runs of the same
     * colour are merged. Gaps longer than two checks (paused, or the screen off) are left as their own grey stretch.
     */
    fun timeline(s: Session, now: Long, everyMs: Long = 30_000): List<Segment> {
        val end = if (s.running) now else s.endedAt
        val out = ArrayList<Segment>()
        var cursor = s.startedAt
        s.checks.sortedBy { it.at }.forEach { c ->
            val from = maxOf(cursor, c.at - everyMs)
            if (from > cursor) out += Segment(cursor, from, null)
            if (c.at > from) out += Segment(from, c.at, c.verdict)
            cursor = maxOf(cursor, c.at)
        }
        if (end > cursor) out += Segment(cursor, end, null)
        return out.fold(mutableListOf()) { acc, seg ->
            val last = acc.lastOrNull()
            if (last != null && last.verdict == seg.verdict && last.end == seg.start) acc[acc.size - 1] = last.copy(end = seg.end) else acc += seg
            acc
        }
    }

    /** Share of checked time that was on task, or null before any check. */
    fun onTaskShare(s: Session): Float? {
        val decided = s.checks.filter { it.verdict != Verdict.Unsure }
        return if (decided.isEmpty()) null else decided.count { it.verdict == Verdict.OnTask }.toFloat() / decided.size
    }

    /** A gentle nudge in plain words, never an alarm. Varies with [n] so it does not repeat itself. */
    fun nudge(task: String, app: String, n: Int): String {
        val t = task.ifBlank { "your task" }
        val lines = listOf(
            "Still on “$t”? $app can wait.",
            "Back to “$t”, when you're ready.",
            "$app's been open a little while. How's “$t” going?",
            "Quick one: was $app part of “$t”?",
            "A minute of $app is fine. Ten is a detour. Back to “$t”?",
        )
        return lines[Math.floorMod(n, lines.size)]
    }
}
