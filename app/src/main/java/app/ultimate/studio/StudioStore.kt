package app.ultimate.studio

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object StudioJson {
    fun encode(p: StudioProject): String = JSONObject().apply {
        put("schema", 1); put("id", p.id); put("name", p.name); put("updatedAt", p.updatedAt)
        put("shape", p.shape.name); put("fill", p.fillCanvas); put("bpm", p.bpm); put("beatOffset", p.beatOffsetMs); put("size", p.shortSide)
        put("fadeIn", p.fadeInMs); put("fadeOut", p.fadeOutMs)
        put("clips", JSONArray().apply { p.clips.forEach { c -> put(JSONObject().apply {
            put("id", c.id); put("uri", c.uri); put("name", c.name); put("sourceMs", c.sourceMs); put("startMs", c.startMs); put("endMs", c.endMs)
            put("speed", c.speed); put("volume", c.volume); put("look", c.look.name); put("brightness", c.brightness); put("rotation", c.rotation)
            put("title", c.title); put("titlePosition", c.titlePosition); put("captionStyle", c.captionStyle.name); put("captionColor", c.captionColor)
            put("captions", JSONArray().apply { c.captions.forEach { put(JSONObject().put("s", it.startMs).put("e", it.endMs).put("t", it.text)) } })
        }) } })
        p.music?.let { m -> put("music", JSONObject().apply { put("uri", m.uri); put("name", m.name); put("durationMs", m.durationMs); put("startMs", m.startMs); put("volume", m.volume) }) }
    }.toString()
    fun decode(text: String): StudioProject {
        val j = JSONObject(text); require(j.getInt("schema") == 1) { "Unsupported Studio project version" }
        val a = j.getJSONArray("clips")
        return StudioProject(j.getString("id"), j.getString("name"), j.getLong("updatedAt"), List(a.length()) { i -> a.getJSONObject(i).let { c ->
            StudioClip(c.getString("id"), c.getString("uri"), c.getString("name"), c.getLong("sourceMs"), c.getLong("startMs"), c.getLong("endMs"),
                c.getDouble("speed").toFloat(), c.getDouble("volume").toFloat(), Look.valueOf(c.getString("look")), c.getDouble("brightness").toFloat(), c.getInt("rotation"), c.getString("title"), c.getInt("titlePosition"),
                runCatching { CaptionStyle.valueOf(c.optString("captionStyle")) }.getOrDefault(CaptionStyle.Box), c.optInt("captionColor", 0xFFFFFF),
                c.optJSONArray("captions")?.let { a -> List(a.length()) { k -> a.getJSONObject(k).let { o -> app.ultimate.speech.Segment(o.getLong("s"), o.getLong("e"), o.getString("t")) } } }.orEmpty())
        } }, CanvasShape.valueOf(j.getString("shape")), j.getBoolean("fill"), j.optJSONObject("music")?.let { m ->
            StudioMusic(m.getString("uri"), m.getString("name"), m.getLong("durationMs"), m.getLong("startMs"), m.getDouble("volume").toFloat())
        }, j.getInt("bpm"), j.getLong("beatOffset"), j.getInt("size"), j.optLong("fadeIn", 0), j.optLong("fadeOut", 0))
    }
}

class StudioStore(private val context: Context) {
    private val dir = File(context.filesDir, "studio").apply { mkdirs() }
    private val lock = Mutex()
    suspend fun save(project: StudioProject) = lock.withLock { withContext(Dispatchers.IO) { atomic(File(dir, "${project.id}.project"), StudioJson.encode(project)) } }
    suspend fun projects(): List<StudioProject> = lock.withLock { withContext(Dispatchers.IO) {
        dir.listFiles().orEmpty().filter { it.extension == "project" }.map { StudioJson.decode(AtomicFile(it).openRead().bufferedReader().use { r -> r.readText() }) }.sortedByDescending { it.updatedAt }
    } }
    suspend fun exports(): List<StudioExport> = lock.withLock { withContext(Dispatchers.IO) {
        val f = File(dir, "exports.json")
        if (!f.exists()) emptyList() else JSONArray(AtomicFile(f).openRead().bufferedReader().use { it.readText() }).let { a -> List(a.length()) { i ->
            val j = a.getJSONObject(i); StudioExport(j.getString("id"), j.getString("name"), j.getString("uri"), j.getLong("createdAt"), j.getLong("durationMs"), j.getLong("bytes"), j.getInt("width"), j.getInt("height"))
        } }
    } }
    suspend fun exports(items: List<StudioExport>) = lock.withLock { withContext(Dispatchers.IO) {
        atomic(File(dir, "exports.json"), JSONArray().apply { items.forEach { e -> put(JSONObject().apply {
            put("id", e.id); put("name", e.name); put("uri", e.uri); put("createdAt", e.createdAt); put("durationMs", e.durationMs); put("bytes", e.bytes); put("width", e.width); put("height", e.height)
        }) } }.toString())
    } }
    private fun atomic(file: File, text: String) {
        val f = AtomicFile(file); val out = f.startWrite()
        try { out.write(text.toByteArray(Charsets.UTF_8)); f.finishWrite(out) } catch (e: Exception) { f.failWrite(out); throw e }
    }
    suspend fun video(uri: Uri): StudioClip = withContext(Dispatchers.IO) {
        retain(uri)
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            require(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes") { "Choose a video file" }
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            require(ms >= Timeline.MIN_CLIP_MS) { "Video is too short" }
            StudioClip(uri = uri.toString(), name = name(uri), sourceMs = ms)
        } finally { r.release() }
    }
    suspend fun music(uri: Uri): StudioMusic = withContext(Dispatchers.IO) {
        retain(uri)
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            require(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes") { "Choose a file with an audio track" }
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            require(ms >= Timeline.MIN_CLIP_MS) { "Music is too short" }
            StudioMusic(uri.toString(), name(uri), ms)
        } finally { r.release() }
    }
    /** Keeps access across restarts where Android allows it; files handed over by other tools or apps may only grant it for now. */
    private fun retain(uri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    private fun name(uri: Uri): String = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: "Media"
}
