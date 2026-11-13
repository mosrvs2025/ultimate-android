package app.ultimate.movement

import android.content.Context
import app.ultimate.fusion.Calibrator
import app.ultimate.fusion.Trace
import app.ultimate.mesh.MotionLog
import app.ultimate.worldscan.LogLines
import app.ultimate.worldscan.WalkStore
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Where recordings live, and the one place raw observations are kept so a better algorithm can re-read them. Each recording is a
 * folder with the raw sensor log (the World Scan log format, gzip'd JSON lines) and a small `meta.json`. The analysis report is NOT stored
 * as the truth: it is recomputed when a recording is opened, so improving the analysis improves every old recording too.
 *
 * World Scan walks are listed too, read in place (never copied or changed): their raw logs are exactly this format.
 */
class MovementStore(private val context: Context) {
    enum class Origin(val label: String) { Recorded("recorded here"), Walk("World Scan walk"), Mesh("recorded on another device") }
    data class Entry(val id: String, val name: String, val startedAt: Long, val durationS: Double, val origin: Origin, val note: String, val file: File)

    private val root = File(context.filesDir, "movement").apply { mkdirs() }
    private val scanStore get() = WalkStore(File(context.filesDir, "worldscan"))

    fun list(): List<Entry> {
        val own = root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
            val log = File(dir, "trace.jsonl.gz"); val meta = File(dir, "meta.json")
            if (!log.exists() || !meta.exists()) return@mapNotNull null
            runCatching {
                val m = JSONObject(meta.readText())
                Entry(dir.name, m.optString("name", dir.name), m.optLong("startedAt"), m.optDouble("durationS", 0.0), runCatching { Origin.valueOf(m.optString("origin", "Recorded")) }.getOrDefault(Origin.Recorded), m.optString("note"), log)
            }.getOrNull()
        }
        val walks = runCatching {
            scanStore.places().flatMap { p -> p.walks.mapNotNull { w -> scanStore.rawLog(p.id, w.id)?.let { f -> Entry("walk:${p.id}:${w.id}", "${p.name} · ${w.name}", w.startedAt, w.durationS, Origin.Walk, "${"%.0f".format(w.lengthM)} m by camera tracking", f) } } }
        }.getOrDefault(emptyList())
        return (own + walks).sortedByDescending { it.startedAt }
    }

    fun places() = runCatching { scanStore.places() }.getOrDefault(emptyList())

    /** A new, empty recording folder: returns its id and the file the recorder should write. */
    fun newRecording(): Pair<String, File> {
        val id = "r" + System.currentTimeMillis().toString(36)
        val dir = File(root, id).apply { mkdirs() }
        return id to File(dir, "trace.jsonl.gz")
    }

    fun finish(id: String, name: String, startedAt: Long, durationS: Double, origin: Origin = Origin.Recorded, note: String = "") {
        File(File(root, id), "meta.json").writeText(JSONObject().put("name", name).put("startedAt", startedAt).put("durationS", durationS).put("origin", origin.name).put("note", note).toString())
    }

    fun rename(e: Entry, name: String) {
        if (e.origin == Origin.Walk) return
        val f = File(File(root, e.id), "meta.json"); runCatching { f.writeText(JSONObject(f.readText()).put("name", name.trim().take(60).ifBlank { e.name }).toString()) }
    }

    fun delete(e: Entry) { if (e.origin != Origin.Walk) File(root, e.id).deleteRecursively() }
    fun bytes(e: Entry) = e.file.length()

    /** Reads a recording. Tolerates a log that is still being written or was cut short (it keeps every complete line it can reach). */
    fun read(e: Entry): Trace = Trace.read(lines(e.file))

    fun lines(file: File): Sequence<String> = sequence {
        val r = try { BufferedReader(InputStreamReader(GZIPInputStream(file.inputStream().buffered()), Charsets.UTF_8)) } catch (_: Exception) { null }
        if (r != null) try {
            while (true) {
                val line = try { r.readLine() } catch (_: Exception) { null } ?: break   // a log still being written ends mid-stream: keep what was read
                yield(line)
            }
        } finally { try { r.close() } catch (_: Exception) {} }
    }

    /**
     * Keeps a motion recording received from another device (accelerometer + gyroscope only) as a recording here, in the same log format,
     * so the same analysis and the same questions apply to it.
     */
    fun importMotion(log: MotionLog, deviceName: String, startedAt: Long = System.currentTimeMillis()): Entry? {
        if (log.rows < 100) return null
        val (id, file) = newRecording()
        val base = 1_000_000_000_000L
        GZIPOutputStream(file.outputStream().buffered()).bufferedWriter().use { w ->
            for (i in 0 until log.rows) {
                val t = base + log.timeMs(i) * 1_000_000L
                w.write(LogLines.sensor(1, t, 3, floatArrayOf(log.accel(i, 0), log.accel(i, 1), log.accel(i, 2)))); w.newLine()
                w.write(LogLines.sensor(4, t + 1_000_000L, 3, floatArrayOf(log.gyro(i, 0), log.gyro(i, 1), log.gyro(i, 2)))); w.newLine()
            }
        }
        val name = "$deviceName · ${java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(startedAt))}"
        finish(id, name, startedAt, log.durationMs / 1000.0, Origin.Mesh, "motion sensors only")
        return list().firstOrNull { it.id == id }
    }

    // ---- calibration ----

    private val calFile get() = File(root, "calibration.json")
    fun calibrator(): Calibrator = runCatching { Calibrator.fromJson(calFile.readText()) }.getOrDefault(Calibrator())
    fun saveCalibrator(c: Calibrator) { runCatching { calFile.writeText(c.toJson()) } }
    fun clearCalibration() { calFile.delete() }
}
