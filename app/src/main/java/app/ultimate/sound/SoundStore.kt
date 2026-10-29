package app.ultimate.sound

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Each session is an atomic, app-private summary; microphone audio is never written. */
class SoundStore(context: Context) {
    private val dir = File(context.filesDir, "sound-sessions")
    private val mutex = Mutex()
    suspend fun load(): List<SoundSession> = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!dir.exists()) emptyList() else dir.listFiles().orEmpty()
                .filter { it.extension == "json" }.map { decode(AtomicFile(it).openRead().use { input -> input.bufferedReader().readText() }) }
                .sortedByDescending { it.createdAt }
        }
    }
    suspend fun save(session: SoundSession) = mutex.withLock {
        withContext(Dispatchers.IO) {
            check(dir.isDirectory || dir.mkdirs()) { "Cannot create session storage" }
            val file = AtomicFile(File(dir, "${session.id}.json"))
            val stream = file.startWrite()
            try {
                stream.write(encode(session).toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (e: Exception) {
                file.failWrite(stream)
                throw e
            }
        }
    }
    suspend fun delete(id: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            require(id.matches(Regex("[a-zA-Z0-9-]+")))
            val f = File(dir, "$id.json")
            if (!f.exists()) true else f.delete().also { if (it) AtomicFile(f).delete() }
        }
    }
    private fun encode(s: SoundSession): String = JSONObject().apply {
        put("id", s.id); put("title", s.title); put("created", s.createdAt); put("duration", s.durationMs)
        put("average", s.averageDb); put("min", s.minDb); put("max", s.maxDb); put("peak", s.peakDb)
        put("clipped", s.clipped); put("offset", s.offset ?: JSONObject.NULL); put("route", s.route); put("end", s.endReason)
        put("points", JSONArray().apply { s.points.forEach { p -> put(JSONArray(listOf(p.timeMs, p.rmsDb, p.peakDb, p.clipped, p.samples))) } })
    }.toString()
    private fun decode(text: String): SoundSession {
        val j = JSONObject(text)
        val a = j.getJSONArray("points")
        return SoundSession(j.getString("id"), j.getString("title"), j.getLong("created"), j.getLong("duration"),
            j.getDouble("average"), j.getDouble("min"), j.getDouble("max"), j.getDouble("peak"), j.getLong("clipped"),
            if (j.isNull("offset")) null else j.getDouble("offset"), j.getString("route"), j.getString("end"),
            List(a.length()) { i -> a.getJSONArray(i).let { SoundPoint(it.getLong(0), it.getDouble(1), it.getDouble(2), it.getLong(3), it.getLong(4)) } })
    }
}

/** CSV cells are quoted, including user titles; formula-like titles are made spreadsheet-safe. */
fun SoundSession.csv(): String {
    fun cell(value: String): String {
        val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'" + value else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }
    fun number(value: Double) = String.format(Locale.US, "%.6f", value)
    return buildString {
        append("session,title,started_unix_ms,elapsed_ms,rms_dbfs,sample_peak_dbfs,calibration_offset_db,estimated_spl_db,clipped_samples,samples,route,end_reason\n")
        points.forEach { p ->
            append(listOf(cell(id), cell(title), createdAt.toString(), p.timeMs.toString(), number(p.rmsDb), number(p.peakDb),
                offset?.let(::number).orEmpty(), offset?.let { number(p.rmsDb + it) }.orEmpty(), p.clipped.toString(), p.samples.toString(), cell(route), cell(endReason)).joinToString(","))
            append('\n')
        }
    }
}
