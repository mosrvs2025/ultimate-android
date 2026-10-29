package app.ultimate.prompter

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.runtime.Composable
import app.ultimate.core.LibraryItem
import app.ultimate.core.Thumb
import app.ultimate.core.Tool
import app.ultimate.core.ToolLaunch
import app.ultimate.core.formatDuration
import app.ultimate.core.JsonFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Take(val id: String, val name: String, val uri: String, val createdAt: Long, val durationMs: Long, val bytes: Long)

/** Pure timing for the scrolling script: the whole script scrolls past in its reading time at the chosen pace. */
object PrompterMath {
    fun words(script: String) = script.split(Regex("\\s+")).count { it.isNotBlank() }

    /** Reading time of a script at a pace, rounded up to the second. */
    fun readingMs(script: String, wordsPerMinute: Int): Long = if (wordsPerMinute <= 0) 0 else (words(script) * 60_000L + wordsPerMinute - 1) / wordsPerMinute

    /** Time left to scroll from [at] of [max] pixels when the whole run takes [totalMs]. */
    fun remainingMs(at: Int, max: Int, totalMs: Long): Long = if (max <= 0) 0 else (totalMs * (max - at).coerceAtLeast(0) / max)
}

/** Remembers the script and the takes recorded with it. */
class PrompterStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("prompter", Context.MODE_PRIVATE)
    private val index = JsonFile(File(context.filesDir, "takes.json"))
    private val _takes = MutableStateFlow(load())
    val takes: StateFlow<List<Take>> = _takes.asStateFlow()
    var script: String
        get() = prefs.getString("script", "") ?: ""
        set(v) = prefs.edit().putString("script", v).apply()
    var wpm: Int
        get() = prefs.getInt("wpm", 140)
        set(v) = prefs.edit().putInt("wpm", v).apply()
    var textSize: Int
        get() = prefs.getInt("size", 30)
        set(v) = prefs.edit().putInt("size", v).apply()

    suspend fun add(t: Take) { _takes.value = listOf(t) + _takes.value; persist() }
    suspend fun remove(id: String, deleteFile: Boolean): Boolean = withContext(Dispatchers.IO) {
        val t = _takes.value.firstOrNull { it.id == id } ?: return@withContext true
        val ok = !deleteFile || runCatching { context.contentResolver.delete(android.net.Uri.parse(t.uri), null, null) >= 0 }.getOrDefault(false)
        if (ok) { _takes.value = _takes.value - t; persist() }
        ok
    }
    private fun load(): List<Take> {
        val a = index.read()
        return List(a.length()) { a.getJSONObject(it) }.mapNotNull { o -> runCatching { Take(o.getString("id"), o.getString("name"), o.getString("uri"), o.getLong("at"), o.getLong("ms"), o.getLong("bytes")) }.getOrNull() }
    }
    private suspend fun persist() = index.write(JSONArray().apply { _takes.value.forEach { t -> put(JSONObject().put("id", t.id).put("name", t.name).put("uri", t.uri).put("at", t.createdAt).put("ms", t.durationMs).put("bytes", t.bytes)) } })
}

/** Read a script while you film yourself: the words scroll by next to the lens. */
class PrompterTool(val store: PrompterStore) : Tool {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override val id = "prompter"
    override val title = "Teleprompter"
    override val tagline = "Read your script while you film"
    override val icon = Icons.Rounded.Subtitles
    override val acceptedMimePrefixes = emptyList<String>()
    override val library: StateFlow<List<LibraryItem>> = store.takes.map { list ->
        list.map { t ->
            LibraryItem(t.id, id, t.name, formatDuration(t.durationMs), t.createdAt, t.bytes, 0, t.uri, Thumb.Image(t.uri),
                listOf("Length" to formatDuration(t.durationMs), "Saved in" to "Movies/Ultimate Prompter"), "video/mp4")
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    override val headline: StateFlow<String> = store.takes.map { if (it.isEmpty()) "Script beside the lens" else "${it.size} take${if (it.size == 1) "" else "s"}" }
        .stateIn(scope, SharingStarted.Eagerly, "Script beside the lens")
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = store.remove(id, deleteFile)
    override suspend fun refresh() = Unit
    @Composable override fun Screen(onExit: () -> Unit, launch: ToolLaunch) = PrompterScreen(this, onExit)
}
