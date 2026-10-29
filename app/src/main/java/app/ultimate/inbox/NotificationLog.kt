package app.ultimate.inbox

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One notification as it was shown. [removedByApp] marks ones the app took back itself, often an unsent message. */
data class Logged(
    val id: Long, val pkg: String, val app: String, val at: Long, val title: String, val text: String,
    val conversation: String?, val removedByApp: Boolean,
)

/** Pure rules for what gets kept, so noise and duplicates are tested. */
object LogRules {
    /** Joins the useful text a notification carries: big text, inbox lines and messaging-style messages. */
    fun body(text: String?, big: String?, lines: List<String>, messages: List<String>): String {
        val parts = buildList {
            if (messages.isNotEmpty()) addAll(messages)
            else if (!big.isNullOrBlank()) add(big)
            else if (lines.isNotEmpty()) addAll(lines)
            else if (!text.isNullOrBlank()) add(text)
        }
        return parts.joinToString("\n").trim()
    }

    /** Ongoing progress, media controls, our own and system chatter are not worth keeping. */
    fun skip(pkg: String, ownPackage: String, ongoing: Boolean, title: String, body: String, excluded: Set<String>): Boolean =
        pkg == ownPackage || pkg in excluded || ongoing || (title.isBlank() && body.isBlank()) || pkg == "android" || pkg == "com.android.systemui"

    /** Lines of a conversation not yet kept, in order. */
    fun unseen(all: List<String>, seen: Collection<String>): List<String> = all.filter { it !in seen }

    /** A fingerprint of the content, so the same update posted again is stored once. */
    fun fingerprint(pkg: String, title: String, body: String) = "$pkg\u0000$title\u0000$body".hashCode()
}

/**
 * Notification history kept in a private database on the phone. Nothing is ever sent anywhere and it is left out of the
 * usage report. Old entries are trimmed by age and count.
 */
class NotificationLog(private val context: Context) : SQLiteOpenHelper(context, "notifications.db", null, 1) {
    private val prefs = context.getSharedPreferences("inbox", Context.MODE_PRIVATE)
    private val _changed = MutableStateFlow(0)
    /** Bumps whenever something is added or removed, so open screens refresh. */
    val changed: StateFlow<Int> = _changed.asStateFlow()

    var keepDays: Int
        get() = prefs.getInt("days", 30)
        set(v) = prefs.edit().putInt("days", v).apply()
    var paused: Boolean
        get() = prefs.getBoolean("paused", false)
        set(v) = prefs.edit().putBoolean("paused", v).apply()
    var excluded: Set<String>
        get() = prefs.getStringSet("excluded", emptySet()).orEmpty()
        set(v) = prefs.edit().putStringSet("excluded", v).apply()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE n (id INTEGER PRIMARY KEY AUTOINCREMENT, pkg TEXT, app TEXT, at INTEGER, title TEXT, text TEXT, convo TEXT, nkey TEXT, fp INTEGER, removed INTEGER DEFAULT 0)")
        db.execSQL("CREATE INDEX n_at ON n(at)")
        db.execSQL("CREATE INDEX n_key ON n(nkey)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit

    /** Whether Android currently has the listener connected, and when it last caught something. */
    fun connected(on: Boolean) = prefs.edit().putBoolean("connected", on).apply()
    val isConnected get() = prefs.getBoolean("connected", false)
    val lastCaught get() = prefs.getLong("lastCaught", 0)

    private val seenMessages = HashMap<String, ArrayDeque<String>>()

    /** The messages in [all] that were not already stored for this conversation [key]. */
    @Synchronized fun newMessages(key: String, all: List<String>): List<String> {
        if (all.isEmpty()) return emptyList()
        val seen = seenMessages.getOrPut(key) {
            // After a restart, rebuild what was stored for this chat from the latest rows.
            ArrayDeque(readableDatabase.rawQuery("SELECT text FROM n WHERE nkey = ? ORDER BY at DESC LIMIT 20", arrayOf(key)).use { c ->
                buildList { while (c.moveToNext()) addAll(c.getString(0).split('\n')) }
            })
        }
        val fresh = LogRules.unseen(all, seen)
        fresh.forEach { seen.addLast(it); if (seen.size > 200) seen.removeFirst() }
        return fresh
    }

    fun today(now: Long = System.currentTimeMillis()): Int {
        val start = java.util.Calendar.getInstance().apply { timeInMillis = now; set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0) }.timeInMillis
        return readableDatabase.rawQuery("SELECT COUNT(*) FROM n WHERE at >= ?", arrayOf(start.toString())).use { it.moveToFirst(); it.getInt(0) }
    }

    fun add(pkg: String, app: String, at: Long, title: String, text: String, convo: String?, key: String) {
        val fp = LogRules.fingerprint(pkg, title, text)
        val db = writableDatabase
        // The same notification updated with identical content (a refresh, a re-post) is kept once.
        db.rawQuery("SELECT 1 FROM n WHERE nkey = ? AND fp = ? AND at > ? LIMIT 1", arrayOf(key, fp.toString(), (at - 6 * 3_600_000L).toString())).use { if (it.moveToFirst()) return }
        prefs.edit().putLong("lastCaught", System.currentTimeMillis()).apply()
        db.insert("n", null, ContentValues().apply {
            put("pkg", pkg); put("app", app); put("at", at); put("title", title); put("text", text); put("convo", convo); put("nkey", key); put("fp", fp)
        })
        _changed.value++
    }

    /** The app withdrew its own notification (not the user swiping it away): often a deleted or unsent message. */
    fun markRemovedByApp(key: String) {
        writableDatabase.execSQL("UPDATE n SET removed = 1 WHERE id = (SELECT id FROM n WHERE nkey = ? ORDER BY at DESC LIMIT 1)", arrayOf(key))
        _changed.value++
    }

    fun search(query: String, pkg: String?, onlyRemoved: Boolean, limit: Int = 500): List<Logged> {
        val where = mutableListOf<String>(); val args = mutableListOf<String>()
        if (query.isNotBlank()) { where += "(title LIKE ? OR text LIKE ? OR app LIKE ?)"; repeat(3) { args += "%${query.trim()}%" } }
        if (pkg != null) { where += "pkg = ?"; args += pkg }
        if (onlyRemoved) where += "removed = 1"
        val sql = "SELECT id, pkg, app, at, title, text, convo, removed FROM n" + (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) + " ORDER BY at DESC LIMIT $limit"
        return readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) add(Logged(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4), c.getString(5), c.getString(6), c.getInt(7) == 1)) }
        }
    }

    /** Apps with stored notifications, busiest first. */
    fun apps(): List<Triple<String, String, Int>> = readableDatabase.rawQuery("SELECT pkg, app, COUNT(*) c FROM n GROUP BY pkg ORDER BY c DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getInt(2))) }
    }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM n", null).use { it.moveToFirst(); it.getInt(0) }

    fun delete(id: Long) { writableDatabase.delete("n", "id = ?", arrayOf(id.toString())); _changed.value++ }
    fun deleteApp(pkg: String) { writableDatabase.delete("n", "pkg = ?", arrayOf(pkg)); _changed.value++ }
    fun clear() { writableDatabase.delete("n", null, null); _changed.value++ }

    /** Drops entries older than the chosen days, and the oldest past 50,000. */
    fun trim(now: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        db.delete("n", "at < ?", arrayOf((now - keepDays * 86_400_000L).toString()))
        db.execSQL("DELETE FROM n WHERE id IN (SELECT id FROM n ORDER BY at DESC LIMIT -1 OFFSET 50000)")
    }
}
