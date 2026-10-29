package app.ultimate.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.system.Os
import androidx.core.app.NotificationCompat
import app.ultimate.R
import app.ultimate.UltimateApp
import app.ultimate.audio.model.AudioSettings
import app.ultimate.core.formatBytes
import app.ultimate.files.FileModel
import app.ultimate.files.contentUri
import app.ultimate.recipes.ToolActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.DateFormat
import java.util.Date

/**
 * Answers Ultimate's shell commands: reads requests from a FIFO in the app's files, runs each one, and writes the output
 * (and then the exit code) back to the FIFO the shell made for it. One reader thread; each command runs in its own coroutine.
 */
class TerminalBridge(private val app: UltimateApp) {
    val dir = File(app.filesDir, "term").apply { mkdirs() }
    val fifo = File(dir, "cmd.fifo")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    @Synchronized fun start() {
        if (started) return
        if (fifo.exists()) fifo.delete()
        Os.mkfifo(fifo.path, "600".toInt(8))
        started = true
        Thread({
            var pending = ""
            while (true) {
                runCatching {
                    // Opening blocks until a shell writes; reading ends when it closes its side.
                    fifo.inputStream().bufferedReader().use { r ->
                        val buf = CharArray(4096)
                        while (true) {
                            val n = r.read(buf); if (n < 0) break
                            val (reqs, rest) = Bridge.parse(pending + String(buf, 0, n)); pending = rest
                            reqs.forEach { req -> scope.launch { answer(req) } }
                        }
                    }
                }
            }
        }, "ult-bridge").apply { isDaemon = true }.start()
    }

    /** Writes the startup file every shell reads. */
    fun rcFile(): File = File(dir, "mkshrc").apply { writeText(Bridge.rc(fifo.path)) }

    private suspend fun answer(req: Bridge.Request) = runCatching {
        // Opening waits for the shell's reader; output then streams as the command runs (a long Squeeze shows its first line at once).
        FileOutputStream(req.reply).use { stream ->
            val out = Out(stream)
            val code = runCatching { run(req, out) }.getOrElse { e -> out.append("ult: ").append(e.message ?: e.javaClass.simpleName).append('\n'); 1 }
            File(req.reply + ".rc").writeText(code.toString())
        }
    }

    private class Out(private val s: java.io.OutputStream) : Appendable {
        override fun append(csq: CharSequence?): Appendable = apply { s.write(csq.toString().toByteArray()); s.flush() }
        override fun append(csq: CharSequence?, start: Int, end: Int): Appendable = append(csq.toString().subSequence(start, end))
        override fun append(c: Char): Appendable = append(c.toString())
    }

    private fun file(req: Bridge.Request, i: Int): File {
        val p = req.args.getOrNull(i) ?: error("which file? e.g. ${req.args[0]} video.mp4")
        return File(Bridge.resolve(req.cwd, p)).also { check(it.exists()) { "no such file: $p" } }
    }

    private fun uriOf(f: File): Uri =
        if (f.canonicalPath.startsWith(Environment.getExternalStorageDirectory().canonicalPath)) contentUri(app, f) else Uri.fromFile(f)

    // VIBRATE is declared by the editions that include the terminal (ultimate, ultimateplus); others never reach this code.
    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun run(req: Bridge.Request, out: Appendable): Int {
        val a = req.args
        when (a.firstOrNull()) {
            null, "help" -> out.append(HELP)
            "battery" -> {
                val bm = app.getSystemService(BatteryManager::class.java)
                val sticky = app.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val temp = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)?.div(10.0)
                out.append("${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%")
                    .append(if (bm.isCharging) " · charging" else " · on battery")
                    .append(temp?.let { " · %.1f°C".format(it) } ?: "").append('\n')
            }
            "storage" -> {
                val s = StatFs(Environment.getDataDirectory().path)
                out.append("${formatBytes(s.totalBytes - s.availableBytes)} used of ${formatBytes(s.totalBytes)} · ${formatBytes(s.availableBytes)} free\n")
                (app.tools.firstOrNull { it.id == "storage" } as? app.ultimate.storage.StorageTool)?.last?.let { r ->
                    r.byKind.entries.sortedByDescending { it.value }.filter { it.value > 0 }.forEach { (k, v) -> out.append("  ${k.name.padEnd(10)} ${formatBytes(v)}\n") }
                } ?: out.append("Open Storage in Ultimate for the full map.\n")
            }
            "clip" -> {
                val clips = app.clips.clips.value
                when (a.getOrNull(1)) {
                    "set" -> {
                        val text = a.drop(2).joinToString(" ")
                        withContext(Dispatchers.Main) { app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Terminal", text)) }
                        app.clips.add(text, "Terminal"); out.append("Copied ${text.length} characters\n")
                    }
                    "list" -> clips.take(15).forEachIndexed { i, c -> out.append("${(i + 1).toString().padStart(2)}  ${c.text.lines().first().take(70)}\n") }
                    null -> out.append(clips.firstOrNull()?.text ?: "").append('\n')
                    else -> out.append(clips.getOrNull((a[1].toIntOrNull() ?: 1) - 1)?.text ?: error("no clip ${a[1]}")).append('\n')
                }
            }
            "notify" -> {
                val text = a.drop(1).joinToString(" ").ifBlank { "Done" }
                val nm = app.getSystemService(android.app.NotificationManager::class.java)
                nm.createNotificationChannel(android.app.NotificationChannel("terminal", "Terminal", android.app.NotificationManager.IMPORTANCE_HIGH))
                nm.notify((System.currentTimeMillis() % 100000).toInt() + 5000, NotificationCompat.Builder(app, "terminal").setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle("Terminal").setContentText(text).setAutoCancel(true).build())
            }
            "open" -> {
                val target = a.getOrNull(1) ?: error("open what? a link or a file")
                val intent = if (Regex("^[a-z]+://").containsMatchIn(target)) Intent(Intent.ACTION_VIEW, Uri.parse(target))
                else file(req, 1).let { f -> Intent(Intent.ACTION_VIEW).setDataAndType(uriOf(f), FileModel.mimeOf(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                withContext(Dispatchers.Main) { app.startActivity(Intent.createChooser(intent, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            "share" -> {
                val f = file(req, 1)
                val send = Intent(Intent.ACTION_SEND).setType(FileModel.mimeOf(f)).putExtra(Intent.EXTRA_STREAM, uriOf(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                withContext(Dispatchers.Main) { app.startActivity(Intent.createChooser(send, "Share ${f.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            "ring" -> if (a.getOrNull(1) == "stop") app.sendServer.actions.stopRing() else { app.sendServer.actions.ring(); out.append("Ringing for 30 seconds · ring stop to end\n") }
            "squeeze" -> { out.append("Shrinking ${file(req, 1).name}…\n"); out.append(ToolActions(app).shrink(uriOf(file(req, 1))).line).append('\n') }
            "toaudio" -> out.append(ToolActions(app).audio(uriOf(file(req, 1)), AudioSettings()).line).append('\n')
            "transcribe" -> {
                val f = file(req, 1)
                val r = ToolActions(app).transcribe(uriOf(f))
                app.transcripts.all.value[uriOf(f).toString()]?.let { out.append(it).append('\n') } ?: out.append(r.line).append('\n')
            }
            "pluck" -> out.append(ToolActions(app).pluck(a.getOrNull(1) ?: error("which app? e.g. pluck com.whatsapp")).line).append('\n')
            "notifications" -> {
                val q = a.drop(1).joinToString(" ")
                val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                app.notificationLog.search(q, null, false, 25).forEach { n -> out.append("${fmt.format(Date(n.at))}  ${n.app}: ${n.title} — ${n.text.lines().first().take(80)}\n") }
            }
            "torch" -> {
                val cm = app.getSystemService(CameraManager::class.java)
                val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } ?: error("no flashlight")
                cm.setTorchMode(id, a.getOrNull(1) != "off")
            }
            "vibrate" -> app.getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createOneShot((a.getOrNull(1)?.toLongOrNull() ?: 300L).coerceIn(10, 5000), VibrationEffect.DEFAULT_AMPLITUDE))
            "myip" -> NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp && !it.isLoopback }.forEach { ni ->
                ni.inetAddresses.toList().filterIsInstance<Inet4Address>().forEach { out.append("${ni.name.padEnd(8)} ${it.hostAddress}\n") }
            }
            else -> { out.append("ult: unknown command ${a[0]} · type help\n"); return 127 }
        }
        return 0
    }

    companion object {
        val HELP = """
            |Phone commands (plus the usual: ls cd cp mv rm cat grep find du df ps top …)
            |  battery                charge, state, temperature
            |  storage                used and free space, by kind
            |  clip [n|list]          clipboard history · echo hi | clip  copies
            |  notifications [word]   search kept notifications
            |  squeeze <video>        make a video smaller
            |  toaudio <video>        save its sound as MP3
            |  transcribe <file>      speech to text
            |  pluck <package>        save an installed app as an APK
            |  open <link|file>       open with an app
            |  share <file>           share sheet
            |  notify <text>          post a notification (e.g. after a long job)
            |  torch on|off · vibrate [ms] · ring [stop] · myip
            |Tip: tap ✨ to describe what you want in plain English.
            |""".trimMargin()
    }
}
