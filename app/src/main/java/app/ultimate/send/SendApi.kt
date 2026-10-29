package app.ultimate.send

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import app.ultimate.BuildConfig
import app.ultimate.UltimateApp
import app.ultimate.recipes.ToolActions
import app.ultimate.recipes.ActionResult
import app.ultimate.pluck.model.AppItem
import app.ultimate.squeeze.model.Settings as SqueezeSettings
import app.ultimate.squeeze.model.Preset
import app.ultimate.audio.model.AudioSettings
import app.ultimate.audio.model.OutFormat
import app.ultimate.photos.PhotoSettings
import app.ultimate.photos.PhotoSize
import app.ultimate.photos.PhotoCrop

import app.ultimate.files.FileModel
import app.ultimate.files.hasAllFiles
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * The companion page's data: phone status, the Library, clipboard both ways, and, only when switched on in the app,
 * notifications and the phone's files. Every request has already passed the address name or a remembered-computer cookie.
 */
class SendApi(private val context: Context, private val server: SendServer) {
    private val app get() = context.applicationContext as UltimateApp
    private val prefs = context.getSharedPreferences("send", Context.MODE_PRIVATE)
    private val root: File get() = Environment.getExternalStorageDirectory()

    var shareNotifications: Boolean
        get() = prefs.getBoolean("shareNotifications", false)
        set(v) = prefs.edit().putBoolean("shareNotifications", v).apply()
    var shareFiles: Boolean
        get() = prefs.getBoolean("shareFiles", true)
        set(v) = prefs.edit().putBoolean("shareFiles", v).apply()

    /** One tool run started from the computer, for the page to follow and to fetch its result. */
    data class Run(val id: String, val tool: String, val name: String, val startedAt: Long, val state: String = "running", val line: String = "", val output: Uri? = null)
    private val runs = java.util.concurrent.CopyOnWriteArrayList<Run>()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    private val actions by lazy { ToolActions(app) }
    private var appsCache: Pair<Long, List<AppItem>>? = null

    private fun update(id: String, change: (Run) -> Run) { val i = runs.indexOfFirst { it.id == id }; if (i >= 0) runs[i] = change(runs[i]) }

    /** Starts [tool] on [uri] with [opts]; returns the run id at once, the work goes on in the tool's own manager. */
    private fun start(tool: String, uri: Uri?, name: String, opts: JSONObject): String {
        val id = SendHttp.newCode()
        runs.add(0, Run(id, tool, name, System.currentTimeMillis()))
        while (runs.size > 30) runs.removeAt(runs.lastIndex)
        app.insights.count("remote.$tool")
        scope.launch {
            val result = runCatching {
                when (tool) {
                    "squeeze" -> actions.shrink(uri!!, SqueezeSettings(preset = runCatching { Preset.valueOf(opts.optString("preset", "Balanced")) }.getOrDefault(Preset.Balanced)))
                    "audio" -> actions.audio(uri!!, AudioSettings(
                        format = runCatching { OutFormat.valueOf(opts.optString("format", "Mp3")) }.getOrDefault(OutFormat.Mp3),
                        kbps = opts.optInt("kbps", 192), mono = opts.optBoolean("mono", false), normalize = opts.optBoolean("normalize", false)))
                    "photos" -> actions.photo(uri!!, PhotoSettings(
                        size = runCatching { PhotoSize.valueOf(opts.optString("size", "Hd")) }.getOrDefault(PhotoSize.Hd),
                        crop = runCatching { PhotoCrop.valueOf(opts.optString("crop", "None")) }.getOrDefault(PhotoCrop.None)))
                    "transcribe" -> actions.transcribe(uri!!)
                    "pluck" -> actions.pluck(opts.getString("package"))
                    "checkup" -> { app.checkupManager.runScan(); val score = app.checkupManager.last.value?.first; ActionResult(null, "Checkup done: ${score ?: "?"}/100") }
                    else -> error("unknown tool $tool")
                }
            }
            result.onSuccess { r -> update(id) { it.copy(state = "done", line = r.line, output = r.output) } }
                .onFailure { e -> update(id) { it.copy(state = "failed", line = e.message ?: "failed") }; app.insights.problem("Remote $tool", e.message ?: "failed") }
        }
        return id
    }

    fun handle(req: SendHttp.Request, path: String, input: InputStream, out: OutputStream) {
        fun json(o: Any) = server.respond(out, 200, "application/json; charset=utf-8", o.toString().toByteArray())
        fun deny(why: String) = server.respond(out, 403, "application/json", JSONObject().put("error", why).toString().toByteArray())
        when {
            req.method == "GET" && path == "/phone" -> json(phone())
            req.method == "POST" && path == "/screen" -> {
                val n = req.headers["content-length"]?.toIntOrNull()?.takeIf { it in 1..4_000_000 } ?: return deny("bad frame")
                val buf = ByteArray(n); var read = 0
                while (read < n) { val r = input.read(buf, read, n - read); if (r < 0) break; read += r }
                server.actions.frame(buf, SendHttp.browserLabel(req.headers["user-agent"]))
                json(JSONObject().put("ok", true))
            }
            req.method == "POST" && path == "/screen/stop" -> { server.actions.stopScreen(); json(JSONObject().put("ok", true)) }
            req.method == "POST" && path == "/open" -> { val url = body(req, input, 4000)?.trim().orEmpty(); if (url.isEmpty()) return deny("empty"); server.actions.openLink(url); app.insights.count("remote.open"); json(JSONObject().put("ok", true)) }
            req.method == "POST" && path == "/ring" -> { server.actions.ring(); app.insights.count("remote.ring"); json(JSONObject().put("ok", true)) }
            req.method == "POST" && path == "/ring/stop" -> { server.actions.stopRing(); json(JSONObject().put("ok", true)) }
            req.method == "GET" && path == "/tools" -> json(JSONArray(app.tools.map { it.id }))
            req.method == "GET" && path == "/runs" -> json(JSONArray().apply { runs.forEach { r ->
                put(JSONObject().put("id", r.id).put("tool", r.tool).put("name", r.name).put("state", r.state).put("line", r.line).put("at", r.startedAt).put("file", r.output != null))
            } })
            req.method == "GET" && path == "/runs/file" -> {
                val r = runs.firstOrNull { it.id == req.query["id"] } ?: return deny("not found")
                val uri = r.output ?: return deny("no file")
                var name = r.name; var size = 0L
                runCatching { context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst()) { name = c.getString(0) ?: name; size = c.getLong(1) } } }
                server.stream(out, req, name, context.contentResolver.getType(uri) ?: "application/octet-stream", size) { context.contentResolver.openInputStream(uri) }
            }
            req.method == "PUT" && path == "/upload" -> {
                // Files from the computer land in Downloads/Ultimate; the page then runs a tool on the returned uri.
                val name = SendHttp.safeName(req.query["name"] ?: "file")
                val length = req.headers["content-length"]?.toLongOrNull()?.takeIf { it >= 0 } ?: return deny("length required")
                val uri = server.save(name, req.headers["content-type"] ?: "application/octet-stream", input, length)
                json(JSONObject().put("uri", uri.toString()).put("name", name))
            }
            req.method == "POST" && path == "/run" -> {
                val body = JSONObject(body(req, input, 20_000) ?: return deny("bad request"))
                val tool = body.getString("tool")
                if (app.tools.none { it.id == tool || (tool == "transcribe") }) return deny("This phone doesn't have that tool")
                val (uri, name) = when (body.optString("kind")) {
                    "file" -> { if (!shareFiles || !hasAllFiles(context)) return deny("Allow file browsing on the phone first"); val f = SendApiPaths.resolve(root, body.getString("path"))?.takeIf { it.isFile } ?: return deny("file not found"); Uri.fromFile(f) to f.name }
                    "library" -> { val i = app.tools.flatMap { it.library.value }.firstOrNull { it.id == body.getString("id") && it.uri != null } ?: return deny("not in the Library"); Uri.parse(i.uri) to i.title }
                    "uri" -> Uri.parse(body.getString("uri")) to body.optString("name", "file")
                    else -> null to (body.optJSONObject("options")?.optString("label") ?: tool)
                }
                json(JSONObject().put("id", start(tool, uri, name, body.optJSONObject("options") ?: JSONObject())))
            }
            req.method == "GET" && path == "/apps" -> {
                val now = System.currentTimeMillis()
                val list = appsCache?.takeIf { now - it.first < 120_000 }?.second ?: kotlinx.coroutines.runBlocking { app.pluckRepository.loadAll() }.also { appsCache = now to it }
                val q = req.query["q"].orEmpty().lowercase()
                json(JSONArray().apply { list.filter { !it.isSystem && (q.isEmpty() || it.label.lowercase().contains(q) || it.packageName.contains(q)) }.sortedBy { it.label.lowercase() }.take(300).forEach { a ->
                    put(JSONObject().put("pkg", a.packageName).put("label", a.label).put("version", a.versionName).put("bytes", a.apkBytes).put("split", a.apkPaths.size > 1))
                } })
            }
            req.method == "GET" && path == "/appicon" -> {
                val png = runCatching {
                    val d = context.packageManager.getApplicationIcon(req.query["pkg"].orEmpty())
                    val b = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
                    d.setBounds(0, 0, 96, 96); d.draw(android.graphics.Canvas(b))
                    java.io.ByteArrayOutputStream().also { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                }.getOrNull() ?: return deny("no icon")
                out.write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${png.size}\r\nCache-Control: max-age=86400\r\nConnection: close\r\n\r\n".toByteArray()); out.write(png); out.flush()
            }
            req.method == "GET" && path == "/library" -> json(library())
            req.method == "GET" && path == "/library/file" -> {
                val item = app.tools.flatMap { it.library.value }.firstOrNull { it.id == req.query["id"] && it.uri != null } ?: return deny("not found")
                val uri = Uri.parse(item.uri)
                val bytes = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull()?.takeIf { it > 0 } ?: item.bytes
                server.stream(out, req, item.title, item.mimeType, bytes) { context.contentResolver.openInputStream(uri) }
            }
            req.method == "GET" && path == "/clips" -> json(JSONArray().apply { app.clips.clips.value.take(200).forEach { put(JSONObject().put("id", it.id).put("text", it.text).put("at", it.at).put("kind", it.kind.label).put("pinned", it.pinned)) } })
            req.method == "POST" && path == "/clips" -> {
                val text = body(req, input, 200_000) ?: return deny("too long")
                app.clips.add(text, "computer")
                // Writing the clipboard is allowed from the background (only reading is not), so the phone can paste it right away.
                Handler(Looper.getMainLooper()).post { runCatching { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("From computer", text)) } }
                json(JSONObject().put("ok", true))
            }
            req.method == "GET" && path == "/notifications" -> {
                if (!shareNotifications || !BuildConfig.HAS_INBOX) return deny("Turn on \"Show notifications on computer\" in Ultimate first.")
                json(JSONArray().apply { app.notificationLog.search(req.query["q"].orEmpty(), null, false, 300).forEach { n ->
                    put(JSONObject().put("app", n.app).put("pkg", n.pkg).put("at", n.at).put("title", n.title).put("text", n.text).put("pulled", n.removedByApp))
                } })
            }
            path.startsWith("/files") -> {
                if (!shareFiles || !hasAllFiles(context)) return deny("Allow file browsing in Ultimate's Send to computer, and all files access in Files.")
                val target = SendApiPaths.resolve(root, req.query["path"].orEmpty()) ?: return deny("outside storage")
                when {
                    req.method == "GET" && path == "/files" -> json(listing(target))
                    req.method == "GET" && path == "/files/raw" && target.isFile -> server.stream(out, req, target.name, FileModel.mimeOf(target), target.length()) { target.inputStream() }
                    req.method == "PUT" && path == "/files/upload" && target.isDirectory -> {
                        val name = SendHttp.safeName(req.query["name"] ?: "file")
                        val length = req.headers["content-length"]?.toLongOrNull()?.takeIf { it >= 0 } ?: return deny("length required")
                        val dest = File(target, FileModel.freeName(target, name))
                        dest.outputStream().use { o -> val buf = ByteArray(256 * 1024); var left = length; while (left > 0) { val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; o.write(buf, 0, n); left -= n }; check(left == 0L) { "upload cut short" } }
                        android.media.MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), null, null)
                        json(JSONObject().put("ok", true).put("name", dest.name))
                    }
                    else -> deny("bad request")
                }
            }
            else -> server.respond(out, 404, "application/json", "{}".toByteArray())
        }
    }

    private fun body(req: SendHttp.Request, input: InputStream, max: Int): String? {
        val n = req.headers["content-length"]?.toIntOrNull() ?: return null
        if (n > max) return null
        val buf = ByteArray(n); var read = 0
        while (read < n) { val r = input.read(buf, read, n - read); if (r < 0) break; read += r }
        return String(buf, 0, read)
    }

    private fun phone(): JSONObject {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) } ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val stat = StatFs(Environment.getDataDirectory().path)
        return JSONObject().put("model", "${android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${android.os.Build.MODEL}")
            .put("battery", level).put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
            .put("free", stat.availableBytes).put("total", stat.totalBytes).put("version", BuildConfig.VERSION_NAME)
            .put("notifications", shareNotifications && BuildConfig.HAS_INBOX).put("files", shareFiles && hasAllFiles(context))
            .put("clips", BuildConfig.HAS_CLIPS)
    }

    private fun library() = JSONArray().apply {
        app.tools.forEach { t -> t.library.value.filter { it.uri != null }.forEach { i ->
            put(JSONObject().put("id", i.id).put("tool", t.title).put("title", i.title).put("subtitle", i.subtitle).put("mime", i.mimeType).put("bytes", i.bytes).put("at", i.createdAt))
        } }
    }

    private fun listing(dir: File) = JSONObject().put("path", dir.relativeTo(root).path).put("entries", JSONArray().apply {
        dir.listFiles().orEmpty().filterNot { it.name.startsWith(".") }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).take(2000).forEach { f ->
            put(JSONObject().put("name", f.name).put("dir", f.isDirectory).put("bytes", if (f.isFile) f.length() else 0).put("at", f.lastModified())
                .put("kind", FileModel.kindOf(f).name).put("mime", if (f.isFile) FileModel.mimeOf(f) else ""))
        }
    })
}

/** Keeps every browser path inside shared storage, whatever "../" tricks it contains. */
object SendApiPaths {
    fun resolve(root: File, relative: String): File? {
        val f = File(root, relative.trimStart('/')).canonicalFile
        return f.takeIf { FileModel.isInside(it, root.canonicalFile) }
    }
}

