package app.ultimate.clips

import android.content.ClipboardManager
import android.content.Context
import app.ultimate.core.JsonFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class ClipKind(val label: String) { Link("Link"), Email("Email"), Phone("Phone"), Code("Code"), Number("Number"), Text("Text") }

data class Clip(val id: Long, val text: String, val at: Long, val pinned: Boolean, val source: String) {
    val kind: ClipKind get() = ClipRules.kindOf(text)
}

object ClipRules {
    fun kindOf(t: String): ClipKind {
        val s = t.trim()
        return when {
            Regex("^(https?://|www\\.)\\S+$", RegexOption.IGNORE_CASE).matches(s) -> ClipKind.Link
            Regex("^[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+$").matches(s) -> ClipKind.Email
            Regex("^\\+?[\\d ()-]{7,}$").matches(s) && s.count { it.isDigit() } >= 7 -> ClipKind.Phone
            Regex("^-?[\\d.,]+$").matches(s) -> ClipKind.Number
            s.contains('\n') && (s.contains('{') || s.contains(';') || s.contains("=>") || s.contains("def ") || s.contains("fun ")) -> ClipKind.Code
            else -> ClipKind.Text
        }
    }

    /**
     * Adds a clip to the front, moving an identical one up instead of keeping two. Pinned clips are never trimmed; the rest are
     * capped at [max].
     */
    fun add(list: List<Clip>, text: String, at: Long, source: String, max: Int = 500): List<Clip> {
        val t = text.take(20_000)
        if (t.isBlank()) return list
        val existing = list.firstOrNull { it.text == t }
        val clip = Clip(existing?.id ?: at, t, at, existing?.pinned ?: false, source)
        val rest = list.filterNot { it.text == t }
        val (pinned, other) = rest.partition { it.pinned }
        return listOf(clip) + pinned + other.take((max - 1 - pinned.size).coerceAtLeast(0))
    }
}

/**
 * Clipboard history, kept privately on the phone. Android only lets apps read the clipboard while they are on screen, so clips
 * arrive when Ultimate is opened, from the quick-settings tile, from "Save to Ultimate" in the text-selection menu, or by sharing
 * text to Ultimate.
 */
class ClipStore(private val context: Context) {
    private val file = JsonFile(File(context.filesDir, "clips.json"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _clips = MutableStateFlow(load())
    val clips: StateFlow<List<Clip>> = _clips.asStateFlow()

    /** Told about each newly kept copy, so rules can react to it. */
    var onAdded: ((text: String, source: String) -> Unit)? = null

    fun add(text: String, source: String) {
        val next = ClipRules.add(_clips.value, text, System.currentTimeMillis(), source)
        if (next == _clips.value) return
        _clips.value = next; save()
        onAdded?.invoke(text, source)
    }

    /** Reads what is on the clipboard now; works only while Ultimate is in front. Returns whether something new was kept. */
    fun captureNow(source: String): Boolean {
        val cm = context.getSystemService(ClipboardManager::class.java)
        val clip = runCatching { cm.primaryClip }.getOrNull() ?: return false
        if (clip.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true) return false // passwords marked sensitive stay out
        val text = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).coerceToText(context)?.toString() }.joinToString("\n").trim()
        if (text.isEmpty() || _clips.value.firstOrNull()?.text == text) return false
        add(text, source); return true
    }

    fun pin(id: Long, on: Boolean) { _clips.value = _clips.value.map { if (it.id == id) it.copy(pinned = on) else it }; save() }
    fun delete(id: Long) { _clips.value = _clips.value.filterNot { it.id == id }; save() }
    fun clearUnpinned() { _clips.value = _clips.value.filter { it.pinned }; save() }

    private fun load(): List<Clip> = runCatching {
        val a = file.read()
        List(a.length()) { a.getJSONObject(it) }.map { Clip(it.getLong("id"), it.getString("t"), it.getLong("at"), it.optBoolean("pin"), it.optString("src")) }
    }.getOrDefault(emptyList())

    private fun save() {
        val snapshot = _clips.value
        scope.launch { file.write(JSONArray().apply { snapshot.forEach { c -> put(JSONObject().put("id", c.id).put("t", c.text).put("at", c.at).put("pin", c.pinned).put("src", c.source)) } }) }
    }
}
