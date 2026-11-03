package app.ultimate.timeline

import app.ultimate.BuildConfig
import app.ultimate.UltimateApp
import app.ultimate.focus.FocusMath
import app.ultimate.focus.Verdict
import app.ultimate.footprint.FootprintReaders
import app.ultimate.footprint.MediaLog

/** Reads every record the app keeps into one list of moments. Only what this edition has, and only from the phone. */
object TimelineReader {
    fun read(app: UltimateApp, from: Long): List<Moment> = buildList {
        if (BuildConfig.HAS_INBOX) runCatching {
            app.notificationLog.since(from, limit = 30_000).forEach { n ->
                add(Moment(Source.Notification, n.at, n.title.ifBlank { n.app }, n.text, n.app, n.pkg))
            }
        }
        if (BuildConfig.HAS_CLIPS) runCatching {
            app.clips.clips.value.filter { it.at >= from }.forEach { c -> add(Moment(Source.Clip, c.at, c.text.lineSequence().firstOrNull().orEmpty().take(120), c.text, c.source)) }
        }
        if (BuildConfig.HAS_FOOTPRINT) runCatching {
            val labels = HashMap<String, String>()
            MediaLog(app).since(from, limit = 20_000).forEach { p ->
                val name = labels.getOrPut(p.pkg) { FootprintReaders.label(app, p.pkg) }
                add(Moment(Source.Watched, p.startedAt, p.title, p.artist, name, p.pkg, end = p.startedAt + p.ms))
            }
        }
        if (BuildConfig.HAS_FOCUS) runCatching {
            (listOfNotNull(app.focus.current.value) + app.focus.history.value).filter { it.startedAt >= from }.forEach { s ->
                val end = if (s.running) System.currentTimeMillis() else s.endedAt
                add(Moment(Source.Focus, s.startedAt, s.task.ifBlank { "Focus session" }, "${FocusMath.countdown(s.elapsed(end))} focused", "Focus", end = end,
                    focusDrift = s.checks.count { it.verdict == Verdict.Drift }))
            }
        }
    }.sortedBy { it.at }
}
