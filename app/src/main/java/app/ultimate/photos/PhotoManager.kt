package app.ultimate.photos

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import app.ultimate.R
import app.ultimate.core.JobLog
import app.ultimate.core.JsonFile
import app.ultimate.core.WorkHub
import app.ultimate.core.WorkSnapshot
import app.ultimate.core.WorkSource
import app.ultimate.core.formatBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class PickedPhoto(val uri: Uri, val name: String, val bytes: Long, val width: Int, val height: Int)
data class PhotoResult(val id: String, val name: String, val uri: String, val createdAt: Long, val bytes: Long, val originalBytes: Long, val width: Int, val height: Int)

/**
 * Resizes, crops and re-encodes photos into Pictures/Ultimate. Only the pixels are written, so location and camera details in
 * the original never reach the copy. Originals are left untouched.
 */
class PhotoManager(private val context: Context, private val hub: WorkHub) : WorkSource {
    data class Run(val total: Int = 0, val done: Int = 0, val current: String? = null, val active: Boolean = false, val results: List<PhotoResult> = emptyList(), val failed: Int = 0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _run = MutableStateFlow(Run())
    val run: StateFlow<Run> = _run.asStateFlow()
    private val index = JsonFile(File(context.filesDir, "photos.json"))
    private val _results = MutableStateFlow(load())
    val results: StateFlow<List<PhotoResult>> = _results.asStateFlow()
    private var job: Job? = null

    override val notifyIcon = R.drawable.ic_stat_photos
    override val notifyColor = 0xFF0288D1.toInt()
    override val workId = "photos"
    override val workTitle = "Photos"
    override val log = JobLog()
    private val _work = MutableStateFlow(WorkSnapshot())
    override val work: StateFlow<WorkSnapshot> = _work.asStateFlow()
    override fun cancelWork() { job?.cancel() }

    fun probe(uri: Uri): PickedPhoto? = runCatching {
        var name = uri.lastPathSegment ?: "photo"; var size = 0L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) { name = c.getString(0) ?: name; size = c.getLong(1) }
        }
        var w = 0; var h = 0
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
            w = info.size.width; h = info.size.height
            d.setTargetSize(1, 1)
        }
        PickedPhoto(uri, name, size, w, h)
    }.getOrNull()

    fun process(photos: List<PickedPhoto>, s: PhotoSettings) {
        if (_run.value.active || photos.isEmpty()) return
        log.reset()
        log.info("Photos · ${photos.size} · ${s.format.label} q${s.quality} · ${s.size.label} · ${s.crop.label}")
        _run.value = Run(total = photos.size, active = true)
        hub.start()
        job = scope.launch {
            val made = mutableListOf<PhotoResult>(); var failed = 0
            try {
                photos.forEachIndexed { i, p ->
                    _run.value = _run.value.copy(done = i, current = p.name)
                    _work.value = WorkSnapshot(true, "Photos ${i + 1} of ${photos.size}", p.name, i * 100 / photos.size)
                    runCatching { withContext(Dispatchers.IO) { one(p, s) } }
                        .onSuccess { r -> made += r; log.ok("${p.name}: ${formatBytes(p.bytes)} → ${formatBytes(r.bytes)} · ${r.width}×${r.height}") }
                        .onFailure { e -> if (e is CancellationException) throw e; failed++; log.error("${p.name}: ${e.message}") }
                }
            } finally {
                if (made.isNotEmpty()) { _results.value = made.reversed() + _results.value; withContext(kotlinx.coroutines.NonCancellable) { persist() } }
                val saved = made.sumOf { (it.originalBytes - it.bytes).coerceAtLeast(0) }
                _run.value = Run(total = photos.size, done = made.size, results = made, failed = failed)
                _work.value = WorkSnapshot(
                    failed = made.isEmpty(),
                    doneTitle = if (made.size == 1) "${made[0].name} is ready" else "${made.size} photos ready",
                    doneText = (if (saved > 0) "${formatBytes(saved)} smaller · " else "") + "in Pictures/Ultimate, location removed" + if (failed > 0) " · $failed failed" else "",
                )
            }
        }
    }

    fun dismiss() { if (!_run.value.active) _run.value = Run() }

    private fun one(p: PickedPhoto, s: PhotoSettings): PhotoResult {
        val plan = PhotoPlanner.plan(p.width, p.height, s)
        // Decoding at reduced size first keeps a 200 MP photo from needing gigabytes of memory.
        val scale = plan.outWidth.toFloat() / plan.cropWidth
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, p.uri)) { d, _, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            d.setTargetSize((p.width * scale).toInt().coerceAtLeast(1), (p.height * scale).toInt().coerceAtLeast(1))
            d.crop = Rect((plan.cropLeft * scale).toInt(), (plan.cropTop * scale).toInt(), ((plan.cropLeft + plan.cropWidth) * scale).toInt(), ((plan.cropTop + plan.cropHeight) * scale).toInt())
        }
        val base = p.name.substringBeforeLast('.').take(60)
        val name = "$base-ultimate.${s.format.ext}"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, s.format.mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Ultimate"); put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: error("no storage")
        var bytes = 0L
        val (w, h) = bitmap.width to bitmap.height
        try {
            resolver.openOutputStream(uri)!!.use { out ->
                val counting = object : java.io.FilterOutputStream(out) {
                    override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); bytes += len }
                    override fun write(b: Int) { out.write(b); bytes++ }
                }
                @Suppress("DEPRECATION")
                val fmt = when {
                    s.format != PhotoFormat.Webp -> Bitmap.CompressFormat.JPEG
                    android.os.Build.VERSION.SDK_INT >= 30 -> Bitmap.CompressFormat.WEBP_LOSSY
                    else -> Bitmap.CompressFormat.WEBP // Android 10: the same lossy WebP under its older name
                }
                check(bitmap.compress(fmt, s.quality, counting)) { "could not encode" }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null); throw e
        } finally { bitmap.recycle() }
        return PhotoResult(UUID.randomUUID().toString(), name, uri.toString(), System.currentTimeMillis(), bytes, p.bytes, w, h)
    }

    suspend fun remove(id: String, deleteFile: Boolean): Boolean = withContext(Dispatchers.IO) {
        val r = _results.value.firstOrNull { it.id == id } ?: return@withContext true
        val ok = !deleteFile || runCatching { context.contentResolver.delete(Uri.parse(r.uri), null, null) >= 0 }.getOrDefault(false)
        if (ok) { _results.value = _results.value - r; persist() }
        ok
    }

    private fun load(): List<PhotoResult> {
        val a = index.read()
        return List(a.length()) { a.getJSONObject(it) }.mapNotNull { o ->
            runCatching { PhotoResult(o.getString("id"), o.getString("name"), o.getString("uri"), o.getLong("at"), o.getLong("bytes"), o.getLong("orig"), o.getInt("w"), o.getInt("h")) }.getOrNull()
        }
    }

    private suspend fun persist() = index.write(JSONArray().apply {
        _results.value.forEach { r -> put(JSONObject().put("id", r.id).put("name", r.name).put("uri", r.uri).put("at", r.createdAt).put("bytes", r.bytes).put("orig", r.originalBytes).put("w", r.width).put("h", r.height)) }
    })
}
