package app.ultimate.audio.engine

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import app.ultimate.audio.model.OutFormat
import app.ultimate.core.JsonFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class AudioEntry(
    val id: String,
    val createdAt: Long,
    val sourceName: String,
    val displayName: String,
    val uri: String,
    val bytes: Long,
    val durationMs: Long,
    val format: OutFormat,
    val bitrate: Int,
    val sampleRate: Int,
    val channels: Int,
    val peaks: List<Float>,
)

/** History of finished conversions. Entries disappear when their file is deleted outside the app. */
class AudioStore(private val context: Context) {
    private val json = JsonFile(File(context.filesDir, "audio.json"))
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<AudioEntry>> = _entries.asStateFlow()

    suspend fun add(entry: AudioEntry) {
        _entries.update { listOf(entry) + it }
        persist()
    }

    /** Removes the entry; with [deleteFile] also the audio file. Returns false if the file could not be deleted. */
    suspend fun remove(id: String, deleteFile: Boolean): Boolean {
        val entry = _entries.value.firstOrNull { it.id == id } ?: return true
        if (deleteFile) {
            val deleted = withContext(Dispatchers.IO) { runCatching { context.contentResolver.delete(Uri.parse(entry.uri), null, null) > 0 }.getOrDefault(false) }
            if (!deleted) return false
        }
        _entries.update { list -> list.filterNot { it.id == id } }
        persist()
        return true
    }

    /** A query that fails is treated as "still there" so a transient error never erases history. */
    suspend fun refresh() {
        val gone = withContext(Dispatchers.IO) { _entries.value.filterNot { exists(Uri.parse(it.uri)) }.map { it.id } }
        if (gone.isEmpty()) return
        _entries.update { list -> list.filterNot { it.id in gone } }
        persist()
    }

    private fun exists(uri: Uri): Boolean = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count > 0 } ?: false
    }.getOrDefault(true)

    private suspend fun persist() = json.write(JSONArray().apply { _entries.value.forEach { put(it.toJson()) } })

    private fun load(): List<AudioEntry> {
        val a = json.read()
        return (0 until a.length()).mapNotNull { runCatching { a.getJSONObject(it).toEntry() }.getOrNull() }
    }

    private fun AudioEntry.toJson() = JSONObject().put("id", id).put("at", createdAt).put("src", sourceName).put("name", displayName)
        .put("uri", uri).put("bytes", bytes).put("dur", durationMs).put("fmt", format.name).put("br", bitrate)
        .put("sr", sampleRate).put("ch", channels).put("peaks", JSONArray(peaks.map { (it * 100).toInt() }))

    private fun JSONObject.toEntry(): AudioEntry {
        val p = getJSONArray("peaks")
        return AudioEntry(
            getString("id"), getLong("at"), getString("src"), getString("name"), getString("uri"), getLong("bytes"), getLong("dur"),
            OutFormat.valueOf(getString("fmt")), getInt("br"), getInt("sr"), getInt("ch"), (0 until p.length()).map { p.getInt(it) / 100f },
        )
    }
}
