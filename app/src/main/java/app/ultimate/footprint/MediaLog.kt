package app.ultimate.footprint

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.content.ComponentName
import android.os.Handler
import android.os.Looper

/**
 * What you watched or listened to, kept on the phone only when you turn it on. It records the title the app itself shows
 * (a video title, a song and its artist), which app, when, and how long it played. It can be erased at any time.
 */
class MediaLog(private val context: Context) : SQLiteOpenHelper(context, "media.db", null, 1) {
    private val prefs = context.getSharedPreferences("footprint", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("media", false)
        set(v) { prefs.edit().putBoolean("media", v).apply() }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE played (id INTEGER PRIMARY KEY AUTOINCREMENT, pkg TEXT NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, at INTEGER NOT NULL, ms INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX played_at ON played(at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit

    @Synchronized fun add(p: Played) {
        writableDatabase.insert("played", null, ContentValues().apply { put("pkg", p.pkg); put("title", p.title); put("artist", p.artist); put("at", p.startedAt); put("ms", p.ms) })
        // Four months is plenty to see a pattern, and keeps this small.
        writableDatabase.delete("played", "at < ?", arrayOf((System.currentTimeMillis() - 120 * 86_400_000L).toString()))
    }

    @Synchronized fun since(from: Long, limit: Int = 500): List<Played> = readableDatabase.rawQuery("SELECT pkg, title, artist, at, ms FROM played WHERE at >= ? ORDER BY at DESC LIMIT ?", arrayOf(from.toString(), limit.toString())).use { c ->
        buildList { while (c.moveToNext()) add(Played(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4))) }
    }

    @Synchronized fun clear() { writableDatabase.delete("played", null, null) }
}

/** Watches every app's media session through the notification access the app already has, and logs what plays, if switched on. */
object MediaWatcher {
    private var tracker: MediaTracker? = null
    private var manager: MediaSessionManager? = null
    private var listener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private val callbacks = HashMap<String, Pair<MediaController, MediaController.Callback>>()
    private val main = Handler(Looper.getMainLooper())

    @Synchronized fun start(context: Context, component: ComponentName) {
        stop()
        val log = MediaLog(context.applicationContext)
        val t = MediaTracker(log::add).also { tracker = it }
        val msm = context.getSystemService(MediaSessionManager::class.java) ?: return
        manager = msm
        fun sync(controllers: List<MediaController>?) {
            val now = controllers.orEmpty().associateBy { it.packageName }
            (callbacks.keys - now.keys).toList().forEach { pkg -> callbacks.remove(pkg)?.let { (c, cb) -> c.unregisterCallback(cb) }; t.end(System.currentTimeMillis(), pkg) }
            now.forEach { (pkg, c) ->
                if (pkg in callbacks) return@forEach
                fun report(md: MediaMetadata?, st: PlaybackState?) {
                    if (!log.enabled) return
                    val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
                    val artist = (md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty()
                    t.update(System.currentTimeMillis(), pkg, title, artist, st?.state == PlaybackState.STATE_PLAYING)
                }
                val cb = object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) = report(metadata, c.playbackState)
                    override fun onPlaybackStateChanged(state: PlaybackState?) = report(c.metadata, state)
                    override fun onSessionDestroyed() { t.end(System.currentTimeMillis(), pkg) }
                }
                c.registerCallback(cb, main); callbacks[pkg] = c to cb
                report(c.metadata, c.playbackState)
            }
        }
        val l = MediaSessionManager.OnActiveSessionsChangedListener { sync(it) }
        listener = l
        runCatching { msm.addOnActiveSessionsChangedListener(l, component, main); sync(msm.getActiveSessions(component)) }
    }

    @Synchronized fun stop() {
        listener?.let { l -> runCatching { manager?.removeOnActiveSessionsChangedListener(l) } }
        callbacks.values.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
        callbacks.clear()
        tracker?.endAll(System.currentTimeMillis())
        tracker = null; listener = null; manager = null
    }
}
