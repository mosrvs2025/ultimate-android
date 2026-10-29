package app.ultimate.squeeze.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import app.ultimate.squeeze.model.LibraryEntry
import app.ultimate.squeeze.model.Source
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
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

/** File-backed history of finished compressions plus a before/after preview frame for each. Lives in app-private storage. */
class LibraryStore(private val context: Context) {
    private val index = File(context.filesDir, "library.json")
    private val frames = File(context.filesDir, "library").apply { mkdirs() }
    private val writeLock = Mutex()
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<LibraryEntry>> = _entries.asStateFlow()

    fun beforeFrame(id: String) = File(frames, "$id-before.jpg")
    fun afterFrame(id: String) = File(frames, "$id-after.jpg")
    fun clipFile(id: String) = File(frames, "$id-original.mp4")

    private fun deleteFiles(id: String) {
        beforeFrame(id).delete(); afterFrame(id).delete(); clipFile(id).delete()
    }

    suspend fun add(entry: LibraryEntry) {
        _entries.update { listOf(entry) + it }
        persist()
    }

    /** Removes the entry; with [deleteFile] also deletes the video. Returns false if the video could not be deleted. */
    suspend fun remove(id: String, deleteFile: Boolean): Boolean {
        val entry = _entries.value.firstOrNull { it.id == id } ?: return true
        if (deleteFile) {
            val deleted = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.delete(Uri.parse(entry.outputUri), null, null) > 0 }.getOrDefault(false)
            }
            if (!deleted) return false
        }
        _entries.update { list -> list.filterNot { it.id == id } }
        withContext(Dispatchers.IO) { deleteFiles(id) }
        persist()
        return true
    }

    /** Drops entries whose video was deleted outside Squeeze. A query that fails is treated as "still there". */
    suspend fun refresh() {
        val gone = withContext(Dispatchers.IO) { _entries.value.filterNot { exists(Uri.parse(it.outputUri)) }.map { it.id } }
        if (gone.isEmpty()) return
        _entries.update { list -> list.filterNot { it.id in gone } }
        withContext(Dispatchers.IO) { gone.forEach(::deleteFiles) }
        persist()
    }

    /** Saves one matching frame from each video so they can be compared later, after the original may be gone. */
    suspend fun saveFrames(id: String, original: Uri, output: Uri, durationMs: Long): Boolean = withContext(Dispatchers.IO) {
        val atUs = durationMs * 300 // 30% in, in microseconds
        val before = grab(original, atUs) ?: return@withContext false
        val after = grab(output, atUs) ?: return@withContext false
        write(before, beforeFrame(id))
        write(after, afterFrame(id))
        true
    }

    /** Saves a short lossless copy of the original so it can be played beside the result later. Returns its start in ms, or -1. */
    suspend fun saveClip(id: String, original: Uri, durationMs: Long): Long = withContext(Dispatchers.IO) {
        val startUs = ClipExtractor.extract(context, original, clipFile(id), ClipExtractor.targetStartUs(durationMs))
        if (startUs == null) clipFile(id).delete()
        startUs?.let { it / 1000 } ?: -1L
    }

    fun loadFrame(file: File): Bitmap? = runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()

    private fun exists(uri: Uri): Boolean = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count > 0 } ?: false
    }.getOrDefault(true)

    private fun grab(uri: Uri, atUs: Long): Bitmap? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val f = r.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
            val k = MAX_FRAME_SIDE.toFloat() / max(f.width, f.height)
            if (k < 1f) Bitmap.createScaledBitmap(f, (f.width * k).roundToInt(), (f.height * k).roundToInt(), true) else f
        } finally {
            r.release()
        }
    }.getOrNull()

    private fun write(bitmap: Bitmap, file: File) = file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }

    private suspend fun persist() = writeLock.withLock {
        withContext(Dispatchers.IO) {
            val json = JSONArray().apply { _entries.value.forEach { put(it.toJson()) } }.toString()
            val tmp = File(index.parentFile, "library.json.tmp")
            tmp.writeText(json)
            tmp.renameTo(index)
        }
    }

    private fun load(): List<LibraryEntry> = runCatching {
        if (!index.exists()) return emptyList()
        val array = JSONArray(index.readText())
        (0 until array.length()).mapNotNull { runCatching { array.getJSONObject(it).toEntry() }.getOrNull() }
    }.getOrDefault(emptyList())

    private fun LibraryEntry.toJson() = JSONObject()
        .put("id", id).put("at", createdAt).put("name", name).put("uri", outputUri)
        .put("before", before.toJson()).put("after", after.toJson())
        .put("encodeMs", encodeMs).put("planned", plannedBytes).put("label", label).put("frames", hasFrames).put("clipStart", clipStartMs)

    private fun JSONObject.toEntry() = LibraryEntry(
        id = getString("id"), createdAt = getLong("at"), name = getString("name"), outputUri = getString("uri"),
        before = getJSONObject("before").toSource(), after = getJSONObject("after").toSource(),
        encodeMs = getLong("encodeMs"), plannedBytes = getLong("planned"), label = getString("label"), hasFrames = getBoolean("frames"), clipStartMs = optLong("clipStart", -1),
    )

    private fun Source.toJson() = JSONObject()
        .put("size", sizeBytes).put("dur", durationMs).put("w", width).put("h", height).put("fps", fps.toDouble())
        .put("vbr", videoBitrate).put("abr", audioBitrate).put("audio", hasAudio).put("hdr", isHdr).put("mime", mime)

    private fun JSONObject.toSource() = Source(
        sizeBytes = getLong("size"), durationMs = getLong("dur"), width = getInt("w"), height = getInt("h"),
        fps = getDouble("fps").toFloat(), videoBitrate = getInt("vbr"), audioBitrate = getInt("abr"),
        hasAudio = getBoolean("audio"), isHdr = getBoolean("hdr"), mime = getString("mime"),
    )

    private companion object {
        const val MAX_FRAME_SIDE = 720
    }
}
