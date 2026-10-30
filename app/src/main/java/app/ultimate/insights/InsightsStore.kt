package app.ultimate.insights

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What Ultimate remembers about its own use: opens and minutes per tool, and recent crashes. All of it stays on the phone until
 * the user exports a report.
 */
class InsightsStore(context: Context, private val version: String) {
    private val prefs = context.getSharedPreferences("insights", Context.MODE_PRIVATE)
    private val crashFile = File(context.filesDir, "crashes.json")
    private var current: String? = null
    private var startedAt = 0L

    private val _revision = kotlinx.coroutines.flow.MutableStateFlow(0)
    val revision: kotlinx.coroutines.flow.StateFlow<Int> = _revision

    /** Bounded, local history for the command surface; no inferred competing-app claims. */
    @Synchronized fun recentOpens(now: Long = System.currentTimeMillis()): List<Pair<String, Long>> = runCatching {
        val a = JSONArray(prefs.getString("recent.opens", "[]"))
        List(a.length()) { a.getJSONObject(it).let { o -> o.getString("id") to o.getLong("at") } }
            .filter { it.second in (now - 48 * 3_600_000L)..now }
    }.getOrDefault(emptyList())

    @Synchronized private fun rememberOpen(id: String, now: Long) {
        val entries = (recentOpens(now) + (id to now)).takeLast(512)
        prefs.edit().putString("recent.opens", JSONArray().apply {
            entries.forEach { (tool, at) -> put(JSONObject().put("id", tool).put("at", at)) }
        }.toString()).apply()
        _revision.value++
    }

    fun pendingTools(): Set<String> = prefs.getStringSet("pending.tools", emptySet()).orEmpty().toSet()
    @Synchronized fun clearPending(id: String) {
        prefs.edit().putStringSet("pending.tools", pendingTools() - id).apply()
        _revision.value++
    }

    fun entered(toolId: String) {
        left()
        rememberOpen(toolId, System.currentTimeMillis())
        current = toolId
        startedAt = SystemClock.elapsedRealtime()
        prefs.edit().putInt("opens.$toolId", prefs.getInt("opens.$toolId", 0) + 1).putLong("last.$toolId", System.currentTimeMillis()).apply()
    }

    fun left() {
        val id = current ?: return
        current = null
        val ms = (SystemClock.elapsedRealtime() - startedAt).coerceIn(0, 6 * 3_600_000L)
        prefs.edit().putLong("ms.$id", prefs.getLong("ms.$id", 0) + ms).apply()
        // Opened and left within seconds: a wrong turn, or the tool did not offer what was wanted.
        if (ms < QUICK_MS && paused == null) prefs.edit().putInt("quick.$id", prefs.getInt("quick.$id", 0) + 1).apply()
    }

    private var paused: String? = null

    /** The app went to the background: stop the clock without counting a new open when it comes back. */
    fun pause() { paused = current; left() }
    fun resume() { paused?.let { current = it; startedAt = SystemClock.elapsedRealtime() }; paused = null }

    /** When counting began, so a report can tell "never opened" from "not counted yet". */
    val since: Long get() = prefs.getLong("since", 0L).takeIf { it > 0 } ?: System.currentTimeMillis().also { prefs.edit().putLong("since", it).apply() }

    fun opens(id: String) = prefs.getInt("opens.$id", 0)
    fun minutes(id: String) = (prefs.getLong("ms.$id", 0) + if (id == current) SystemClock.elapsedRealtime() - startedAt else 0) / 60_000
    fun lastAt(id: String) = prefs.getLong("last.$id", 0)

    fun quick(id: String) = prefs.getInt("quick.$id", 0)

    /** Counts a use of a feature inside a tool, e.g. "studio.autocaptions". */
    fun count(event: String) = prefs.edit().putInt("ev.$event", prefs.getInt("ev.$event", 0) + 1).apply()
    fun events(): Map<String, Int> = prefs.all.filterKeys { it.startsWith("ev.") }.mapKeys { it.key.removePrefix("ev.") }.mapValues { (it.value as? Int) ?: 0 }

    /** A finished background job: whether it worked and how long it took. */
    @Synchronized fun job(toolId: String, ms: Long, ok: Boolean) {
        prefs.edit()
            .putInt("job.${if (ok) "ok" else "fail"}.$toolId", prefs.getInt("job.${if (ok) "ok" else "fail"}.$toolId", 0) + 1)
            .putLong("job.ms.$toolId", prefs.getLong("job.ms.$toolId", 0) + ms)
            .putStringSet("pending.tools", if (ok) pendingTools() - toolId else pendingTools() + toolId).apply()
        _revision.value++
    }
    fun jobs(id: String): Triple<Int, Int, Long> = Triple(prefs.getInt("job.ok.$id", 0), prefs.getInt("job.fail.$id", 0), prefs.getLong("job.ms.$id", 0))

    /** A problem the user was shown (not a crash), kept as the last few. */
    fun problem(tool: String, message: String) = push("problems", "$tool: ${message.take(160)}")
    fun problems() = list("problems")

    /** Something typed into Home's bar that matched nothing: the clearest sign of a missing tool. */
    fun unmatched(query: String) = push("unmatched", query.trim().take(80))
    fun unmatchedSearches() = list("unmatched")

    private fun push(key: String, value: String) {
        val now = list(key).filterNot { it.substringAfter(" · ") == value } + "${java.text.SimpleDateFormat("MM-dd", java.util.Locale.US).format(java.util.Date())} · $value"
        prefs.edit().putString(key, JSONArray(now.takeLast(15)).toString()).apply()
    }
    private fun list(key: String): List<String> = runCatching { JSONArray(prefs.getString(key, "[]")).let { a -> List(a.length()) { a.getString(it) } } }.getOrDefault(emptyList())

    fun crashes(): List<Crash> = runCatching {
        val a = JSONArray(crashFile.readText())
        List(a.length()) { a.getJSONObject(it) }.map { o ->
            Crash(o.getLong("at"), o.getString("version"), o.getString("type"), o.optString("message").ifEmpty { null }, o.getJSONArray("frames").let { f -> List(f.length()) { f.getString(it) } })
        }
    }.getOrDefault(emptyList())

    fun clearCrashes() { crashFile.delete() }

    /** Keeps the last few crashes, with only Ultimate's own stack frames, then lets Android handle the crash as usual. */
    companion object { const val QUICK_MS = 8_000L }

    fun installCrashCatcher() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val root = generateSequence(e) { it.cause }.last()
                val frames = (e.stackTrace + root.stackTrace).filter { it.className.startsWith("app.ultimate") }.distinct().take(12).map { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                val o = JSONObject().put("at", System.currentTimeMillis()).put("version", version).put("type", root.javaClass.name).put("message", root.message ?: "").put("frames", JSONArray(frames))
                val list = runCatching { JSONArray(crashFile.readText()) }.getOrDefault(JSONArray())
                val kept = JSONArray().apply { val start = maxOf(0, list.length() - 4); for (k in start until list.length()) put(list.get(k)); put(o) }
                crashFile.writeText(kept.toString())
            }
            previous?.uncaughtException(thread, e)
        }
    }
}

/** Counts a feature use from anywhere with a context; a no-op if the app object is not Ultimate's. */
fun android.content.Context.track(event: String) { (applicationContext as? app.ultimate.UltimateApp)?.insights?.count(event) }

/** Records a problem the user saw, for the usage report. */
fun android.content.Context.trackProblem(tool: String, message: String?) { if (!message.isNullOrBlank()) (applicationContext as? app.ultimate.UltimateApp)?.insights?.problem(tool, message) }
