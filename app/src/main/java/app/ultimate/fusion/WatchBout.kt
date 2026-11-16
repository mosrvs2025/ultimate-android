package app.ultimate.fusion

/**
 * The watch usually notices a walk before the phone is asked to record it: it has been counting steps (and heart beats) for
 * minutes when Movement is opened. Rather than start the clock at 0:00 as if the walk had only just begun, the phone adopts the
 * time the watch says the walk began, so both devices tell one story, and says plainly that the stretch before the phone's own
 * sensors started comes from the watch alone. Pure, tested.
 */
object WatchBout {
    /** A walk older than this is not "the current walk", whatever the watch last said. */
    const val MAX_AGE_MS = 3L * 3_600_000L
    /** The watch reports about every half minute while you move; silence this long means the walk is over. */
    const val FRESH_MS = 150_000L

    /**
     * When the current walk or run began (epoch ms), if the watch has said it is moving right now and gave a believable start,
     * else null and the phone's recording simply starts when it was pressed.
     */
    fun adoptedStart(now: Long, state: String?, since: Long, heardAt: Long): Long? {
        if (state != "walking" && state != "running") return null
        if (since <= 0L || since >= now) return null
        if (now - heardAt > FRESH_MS) return null
        if (now - since > MAX_AGE_MS) return null
        return since
    }

    /** "The first 12:04 comes from your watch (about 1,350 steps), before this phone started recording." */
    fun note(earlySeconds: Double, watchSteps: Long?): String? {
        if (earlySeconds < 20) return null
        val t = earlySeconds.toInt()
        val clock = if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
        val steps = watchSteps?.takeIf { it > 0 }?.let { " (about ${"%,d".format(java.util.Locale.US, it)} steps)" } ?: ""
        return "The first $clock comes from your watch$steps, before this phone started recording. It is counted in the time, but not drawn on the map."
    }
}
