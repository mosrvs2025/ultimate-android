package app.ultimate.footprint

/** One thing you watched or listened to: what, where, when, and for how long it was actually playing. */
data class Played(val pkg: String, val title: String, val artist: String, val startedAt: Long, val ms: Long, val id: Long = 0)

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

    /** Whether [p] matches what was typed: every word must appear in the title, the artist or the app's name. */
    fun matches(p: Played, query: String, appName: String = ""): Boolean {
        val hay = "${p.title} ${p.artist} $appName".lowercase()
        return query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }.all { it in hay }
    }

    /** Time played on each of the last [days] days, oldest first, with empty days as zero. */
    fun daily(list: List<Played>, days: Int, now: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): List<Long> {
        fun day(t: Long) = Math.floorDiv(t + zone.getOffset(t), 86_400_000L)
        val today = day(now)
        val out = LongArray(days)
        list.forEach { p -> val ago = (today - day(p.startedAt)).toInt(); if (ago in 0 until days) out[days - 1 - ago] += p.ms }
        return out.toList()
    }

    /** The hour of the day you most often start playing something, or null with nothing to go on. */
    fun busiestHour(list: List<Played>, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Int? =
        list.groupingBy { (Math.floorMod(it.startedAt + zone.getOffset(it.startedAt), 86_400_000L) / 3_600_000L).toInt() }
            .fold(0L) { acc, p -> acc + p.ms }.maxByOrNull { it.value }?.key
}

