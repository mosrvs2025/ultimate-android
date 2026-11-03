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
        if (BuildConfig.HAS_FOOTPRINT) {
            runCatching { addAll(photos(app, from)) }
            runCatching { addAll(installs(app, from)) }
            runCatching {
                (ChargeLog.sessions(app) + listOfNotNull(ChargeLog.current(app))).filter { it.end >= from }.forEach { c ->
                    add(Moment(Source.Charge, c.start, if (c.from >= 0 && c.to >= 0) "${c.from}% → ${c.to}%" else "On the charger", "", "Charging", end = c.end, level = c.from, level2 = c.to))
                }
            }
        }
    }.sortedBy { it.at }

    /** Photos and screenshots from the gallery: when each was taken, and which folder it is in. Only with photo access. */
    private fun photos(app: UltimateApp, from: Long): List<Moment> {
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES else android.Manifest.permission.READ_EXTERNAL_STORAGE
        if (app.checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) return emptyList()
        val uri = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val cols = arrayOf(android.provider.MediaStore.Images.Media.DATE_TAKEN, android.provider.MediaStore.Images.Media.DATE_ADDED, android.provider.MediaStore.Images.Media.BUCKET_DISPLAY_NAME, android.provider.MediaStore.Images.Media.DISPLAY_NAME)
        return app.contentResolver.query(uri, cols, "${android.provider.MediaStore.Images.Media.DATE_ADDED} >= ?", arrayOf((from / 1000).toString()), null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val at = c.getLong(0).takeIf { it > 0 } ?: (c.getLong(1) * 1000)
                    val bucket = c.getString(2).orEmpty(); val name = c.getString(3).orEmpty()
                    val shot = bucket.contains("screenshot", true) || name.contains("screenshot", true)
                    add(Moment(if (shot) Source.Screenshot else Source.Photo, at, if (shot) "Screenshot" else bucket.ifBlank { "Photo" }, name, bucket))
                }
            }
        }.orEmpty()
    }

    /** Apps you installed yourself since [from], by their install date. */
    private fun installs(app: UltimateApp, from: Long): List<Moment> = app.packageManager.getInstalledPackages(0)
        .filter { it.firstInstallTime >= from && (it.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != app.packageName }
        .map { p -> Moment(Source.Install, p.firstInstallTime, FootprintReaders.label(app, p.packageName), "Installed", FootprintReaders.label(app, p.packageName), p.packageName) }
}
