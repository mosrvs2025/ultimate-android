package app.ultimate.pluck.engine

import android.content.Context
import app.ultimate.core.JsonFile
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class SavedEntry(
    val id: String,
    val createdAt: Long,
    val packageName: String,
    val label: String,
    val versionName: String,
    val uri: String,
    val displayName: String,
    val bytes: Long,
    val splitCount: Int,
    val sha256: String,
)

/** History of extractions. Entries disappear when their file is deleted outside Pluck. */
class HistoryStore(private val context: Context) {
    private val json = JsonFile(File(context.filesDir, "history.json"))
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<SavedEntry>> = _entries.asStateFlow()

    suspend fun add(entry: SavedEntry) {
        _entries.update { listOf(entry) + it }
        persist()
    }

    /** Removes the entry; with [deleteFile] also deletes the file. Returns false if the file could not be deleted. */
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

    private fun load(): List<SavedEntry> {
        val a = json.read()
        return (0 until a.length()).mapNotNull { runCatching { a.getJSONObject(it).toEntry() }.getOrNull() }
    }

    private fun SavedEntry.toJson() = JSONObject().put("id", id).put("at", createdAt).put("pkg", packageName).put("label", label)
        .put("version", versionName).put("uri", uri).put("name", displayName).put("bytes", bytes).put("splits", splitCount).put("sha", sha256)

    private fun JSONObject.toEntry() = SavedEntry(
        getString("id"), getLong("at"), getString("pkg"), getString("label"), getString("version"),
        getString("uri"), getString("name"), getLong("bytes"), getInt("splits"), getString("sha"),
    )
}

/** Caches ad/tracker scan results per app version so each app is scanned at most once per update. */
class ScanStore(context: Context) {
    /** [mediation] is null for results saved before mediation was analysed; such apps are re-scanned when next needed. */
    data class Record(val versionCode: Long, val updateTime: Long, val sdks: List<String>, val mediation: List<String>? = null)

    private val json = JsonFile(File(context.filesDir, "scans.json"))
    private val _results = MutableStateFlow(load())
    val results: StateFlow<Map<String, Record>> = _results.asStateFlow()

    fun cached(pkg: String, versionCode: Long, updateTime: Long): Record? =
        _results.value[pkg]?.takeIf { it.versionCode == versionCode && it.updateTime == updateTime }

    suspend fun put(pkg: String, record: Record) {
        _results.update { it + (pkg to record) }
    }

    suspend fun flush() = json.write(JSONArray().apply {
        _results.value.forEach { (pkg, r) ->
            put(
                JSONObject().put("pkg", pkg).put("v", r.versionCode).put("t", r.updateTime).put("sdks", JSONArray(r.sdks))
                    .apply { r.mediation?.let { put("med", JSONArray(it)) } },
            )
        }
    })

    private fun load(): Map<String, Record> {
        val a = json.read()
        return (0 until a.length()).mapNotNull {
            runCatching {
                val o = a.getJSONObject(it)
                val sdks = o.getJSONArray("sdks")
                val med = o.optJSONArray("med")
                o.getString("pkg") to Record(
                    o.getLong("v"), o.getLong("t"), (0 until sdks.length()).map { i -> sdks.getString(i) },
                    med?.let { m -> (0 until m.length()).map { i -> m.getString(i) } },
                )
            }.getOrNull()
        }.toMap()
    }
}
