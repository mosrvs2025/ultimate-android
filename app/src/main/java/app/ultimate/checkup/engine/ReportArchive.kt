package app.ultimate.checkup.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import app.ultimate.core.JsonFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One saved checkup: the summary for the Library and where its full Markdown report lives. */
data class SavedReport(val at: Long, val score: Int, val critical: Int, val warnings: Int, val apps: Int, val uri: String, val bytes: Long)

/** Keeps every checkup's report as a Markdown file in Documents/Ultimate, so past scans can be compared later. */
class ReportArchive(private val context: Context) {
    private val index = JsonFile(File(context.filesDir, "checkup-reports.json"))
    private val _reports = MutableStateFlow(load())
    val reports: StateFlow<List<SavedReport>> = _reports.asStateFlow()

    private fun load(): List<SavedReport> {
        val a = index.read()
        return List(a.length()) { a.getJSONObject(it) }.mapNotNull { o ->
            runCatching { SavedReport(o.getLong("at"), o.getInt("score"), o.getInt("critical"), o.getInt("warnings"), o.getInt("apps"), o.getString("uri"), o.getLong("bytes")) }.getOrNull()
        }.sortedByDescending { it.at }
    }

    suspend fun save(at: Long, score: Int, critical: Int, warnings: Int, apps: Int, markdown: String): SavedReport? = withContext(Dispatchers.IO) {
        val name = "checkup-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(at))}.md"
        val values = ContentValues().apply {
            put(MediaStore.Files.FileColumns.DISPLAY_NAME, name)
            put(MediaStore.Files.FileColumns.MIME_TYPE, "text/markdown")
            put(MediaStore.Files.FileColumns.RELATIVE_PATH, "Documents/Ultimate")
        }
        val bytes = markdown.toByteArray()
        val uri = runCatching {
            context.contentResolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)?.also { u ->
                context.contentResolver.openOutputStream(u)!!.use { it.write(bytes) }
            }
        }.getOrNull() ?: return@withContext null
        val saved = SavedReport(at, score, critical, warnings, apps, uri.toString(), bytes.size.toLong())
        _reports.value = listOf(saved) + _reports.value
        persist()
        saved
    }

    suspend fun remove(at: Long, deleteFile: Boolean): Boolean = withContext(Dispatchers.IO) {
        val r = _reports.value.firstOrNull { it.at == at } ?: return@withContext true
        val deleted = !deleteFile || runCatching { context.contentResolver.delete(Uri.parse(r.uri), null, null) >= 0 }.getOrDefault(false)
        if (deleted) { _reports.value = _reports.value - r; persist() }
        deleted
    }

    private suspend fun persist() = index.write(JSONArray().apply {
        _reports.value.forEach { r -> put(JSONObject().put("at", r.at).put("score", r.score).put("critical", r.critical).put("warnings", r.warnings).put("apps", r.apps).put("uri", r.uri).put("bytes", r.bytes)) }
    })
}
