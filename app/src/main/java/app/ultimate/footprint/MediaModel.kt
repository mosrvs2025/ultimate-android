package app.ultimate.footprint

/** One thing you watched or listened to: what, where, when, and for how long it was actually playing. */
data class Played(val pkg: String, val title: String, val artist: String, val startedAt: Long, val ms: Long)

/**
 * Turns the moments an app reports ("now playing X", "paused", "playing") into sessions. A pause shorter than [mergeGapMs] stays
 * part of the same session, a new title ends the old one, and anything that played for less than [minMs] is not worth keeping.
 * One tracker watches every app at once. Pure, so the bookkeeping is tested.
 */
class MediaTracker(private val sink: (Played) -> Unit, private val mergeGapMs: Long = 10 * 60_000L, private val minMs: Long = 10_000L) {
    private class Open(val title: String, val artist: String, val startedAt: Long) { var played = 0L; var since = -1L; var lastPause = 0L }
    private val open = HashMap<String, Open>()

    /** [title] blank means the app has nothing loaded. */
    fun update(at: Long, pkg: String, title: String, artist: String, playing: Boolean) {
        var o = open[pkg]
        if (o != null && (title.isBlank() || o.title != title)) { finish(pkg, at); o = null }
        if (title.isBlank()) return
        if (o != null && o.since < 0 && at - o.lastPause > mergeGapMs) { finish(pkg, at); o = null } // paused so long it is a new sitting
        val cur = o ?: Open(title, artist, at).also { open[pkg] = it }
        if (playing && cur.since < 0) cur.since = at
        else if (!playing && cur.since >= 0) { cur.played += at - cur.since; cur.since = -1; cur.lastPause = at }
    }

    /** The app's player went away, or the listener stopped: whatever was open is over. */
    fun end(at: Long, pkg: String) = finish(pkg, at)
    fun endAll(at: Long) = open.keys.toList().forEach { finish(it, at) }

    private fun finish(pkg: String, at: Long) {
        val o = open.remove(pkg) ?: return
        val ms = o.played + if (o.since >= 0) at - o.since else 0L
        if (ms >= minMs) sink(Played(pkg, o.title, o.artist, o.startedAt, ms))
    }
}

object MediaStats {
    /** Total time by app, biggest first. */
    fun byApp(list: List<Played>): List<Pair<String, Long>> = list.groupBy { it.pkg }.mapValues { (_, v) -> v.sumOf { it.ms } }.entries.sortedByDescending { it.value }.map { it.key to it.value }

    /** The artists, channels or shows you spent the most time with. */
    fun topArtists(list: List<Played>, n: Int = 5): List<Pair<String, Long>> =
        list.filter { it.artist.isNotBlank() }.groupBy { it.artist }.mapValues { (_, v) -> v.sumOf { it.ms } }.entries.sortedByDescending { it.value }.take(n).map { it.key to it.value }
}
