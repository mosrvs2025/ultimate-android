package app.ultimate.focus

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The running focus session and the ones before it, kept in a small file on the phone so a session survives Ultimate
 * being closed. It stores the task, the timings, each check's app and verdict, and when it nudged: never screen contents.
 */
class FocusStore(private val context: Context) {
    private val file = File(context.filesDir, "focus.json")
    private val _current = MutableStateFlow<Session?>(null)
    private val _history = MutableStateFlow<List<Session>>(emptyList())
    val current: StateFlow<Session?> = _current.asStateFlow()
    val history: StateFlow<List<Session>> = _history.asStateFlow()
    private val prefs = context.getSharedPreferences("focus", Context.MODE_PRIVATE)

    /** The apps picked for tasks last time, offered again next time. */
    var lastAllowed: Set<String>
        get() = prefs.getStringSet("allowed", emptySet()).orEmpty()
        set(v) = prefs.edit().putStringSet("allowed", v).apply()
    var lastMinutes: Int
        get() = prefs.getInt("minutes", 30)
        set(v) = prefs.edit().putInt("minutes", v).apply()

    init { load() }

    @Synchronized fun start(task: String, minutes: Int, allowed: Set<String>, now: Long = System.currentTimeMillis()) {
        _current.value?.let { finish(it.copy(endedAt = now)) }
        lastAllowed = allowed; lastMinutes = minutes
        _current.value = Session(now, task.trim(), now, minutes.coerceIn(1, 240) * 60_000L, allowed = allowed)
        save(); FocusService.start(context)
    }

    @Synchronized fun pause(now: Long = System.currentTimeMillis()) { update { if (it.paused) it else it.copy(pausedAt = now) } }
    @Synchronized fun resume(now: Long = System.currentTimeMillis()) { update { if (!it.paused) it else it.copy(pausedMs = it.pausedMs + (now - it.pausedAt), pausedAt = 0) } }

    @Synchronized fun end(now: Long = System.currentTimeMillis()) {
        val s = _current.value ?: return
        val stoppedAt = if (s.paused) s.pausedAt else now
        finish(s.copy(endedAt = stoppedAt, pausedAt = 0))
    }

    @Synchronized fun addCheck(c: Check) = update { it.copy(checks = (it.checks + c).takeLast(2_000)) }
    @Synchronized fun addNudge(at: Long) = update { it.copy(nudges = it.nudges + at) }
    @Synchronized fun forget(id: Long) { _history.value = _history.value.filterNot { it.id == id }; save() }

    private fun finish(done: Session) {
        _current.value = null
        _history.value = (listOf(done) + _history.value.filterNot { it.id == done.id }).take(100)
        save()
    }

    private fun update(change: (Session) -> Session) { val s = _current.value ?: return; _current.value = change(s); save() }

    private fun save() = runCatching {
        val root = JSONObject().put("current", _current.value?.let(::toJson)).put("history", JSONArray(_history.value.map(::toJson)))
        val tmp = File(file.parentFile, file.name + ".tmp"); tmp.writeText(root.toString()); tmp.renameTo(file)
    }

    private fun load() = runCatching {
        if (!file.exists()) return@runCatching
        val root = JSONObject(file.readText())
        _current.value = root.optJSONObject("current")?.let(::fromJson)
        _history.value = root.optJSONArray("history")?.let { a -> (0 until a.length()).map { fromJson(a.getJSONObject(it)) } }.orEmpty()
    }

    companion object {
        fun toJson(s: Session): JSONObject = JSONObject().put("id", s.id).put("task", s.task).put("start", s.startedAt).put("planned", s.plannedMs)
            .put("end", s.endedAt).put("pausedMs", s.pausedMs).put("pausedAt", s.pausedAt).put("allowed", JSONArray(s.allowed.toList()))
            .put("checks", JSONArray(s.checks.map { JSONArray().put(it.at).put(it.pkg).put(it.verdict.ordinal).put(it.strength.toDouble()) }))
            .put("nudges", JSONArray(s.nudges))

        fun fromJson(o: JSONObject): Session {
            val allowed = o.optJSONArray("allowed")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
            val checks = o.optJSONArray("checks")?.let { a -> (0 until a.length()).map { i -> a.getJSONArray(i).let { c -> Check(c.getLong(0), c.getString(1), Verdict.entries[c.getInt(2)], c.getDouble(3).toFloat()) } } }.orEmpty()
            val nudges = o.optJSONArray("nudges")?.let { a -> (0 until a.length()).map { a.getLong(it) } }.orEmpty()
            return Session(o.getLong("id"), o.getString("task"), o.getLong("start"), o.getLong("planned"), o.optLong("end"), o.optLong("pausedMs"), o.optLong("pausedAt"), allowed, checks, nudges)
        }
    }
}

/** Opens Ultimate on the Focus tool, for notifications. */
internal fun focusIntent(context: Context) = Intent(context, app.ultimate.MainActivity::class.java)
    .putExtra(app.ultimate.MainActivity.EXTRA_TOOL, "focus").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
