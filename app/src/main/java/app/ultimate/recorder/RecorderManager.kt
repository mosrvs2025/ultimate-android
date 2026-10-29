package app.ultimate.recorder

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import app.ultimate.core.JsonFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** How a recording is encoded: voice is mono and small, music is stereo and full range. */
enum class RecordQuality(val label: String, val blurb: String, val channels: Int, val rate: Int, val bitrate: Int) {
    Voice("Voice", "Mono · small files", 1, 32_000, 64_000),
    Music("Music", "Stereo · full quality", 2, 48_000, 192_000),
}

data class Recording(val id: String, val name: String, val uri: String, val createdAt: Long, val durationMs: Long, val bytes: Long, val peaks: List<Float>)

/** Pure helpers, so the numbers on screen are tested. */
object RecorderMath {
    /** MediaRecorder's max amplitude (0..32767) as a 0..1 bar height on a perceptual (dB) scale, quiet room ≈ 0.1. */
    fun level(amplitude: Int): Float {
        if (amplitude <= 0) return 0f
        val db = 20 * kotlin.math.log10(amplitude / 32767.0)
        return ((db + 60) / 60).toFloat().coerceIn(0f, 1f)
    }

    /** Shrinks a level history to [buckets] peaks for the Library thumbnail and player. */
    fun downsample(levels: List<Float>, buckets: Int = 120): List<Float> {
        if (levels.size <= buckets) return levels
        val per = levels.size.toFloat() / buckets
        return List(buckets) { b -> levels.subList((b * per).toInt(), ((b + 1) * per).toInt().coerceAtMost(levels.size)).maxOrNull() ?: 0f }
    }

    fun defaultName(at: Long) = "Recording " + SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(at))
}

/**
 * Records from the microphone to AAC, keeping going with the screen off through [RecorderService]. Finished recordings are saved
 * to Music/Ultimate/Recordings and listed in the Library.
 */
class RecorderManager(private val context: Context) {
    sealed interface State {
        data object Idle : State
        data class Recording(val startedAt: Long, val elapsedMs: Long, val paused: Boolean, val levels: List<Float>, val quality: RecordQuality) : State
        data object Saving : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private val index = JsonFile(File(context.filesDir, "recordings.json"))
    private val _recordings = MutableStateFlow(load())
    val recordings: StateFlow<List<Recording>> = _recordings.asStateFlow()
    val error = MutableStateFlow<String?>(null)
    private var recorder: MediaRecorder? = null
    private var temp: File? = null
    private var ticker: Job? = null
    private var activeMs = 0L
    private var resumedAt = 0L
    private val allLevels = ArrayList<Float>()

    fun start(quality: RecordQuality) {
        if (_state.value !is State.Idle) return
        val file = File(context.cacheDir, "recording-${UUID.randomUUID()}.m4a")
        val r = runCatching {
            @Suppress("DEPRECATION")
            (if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(quality.channels)
                setAudioSamplingRate(quality.rate)
                setAudioEncodingBitRate(quality.bitrate)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
        }.getOrElse { error.value = "The microphone isn't available: ${it.message}"; file.delete(); return }
        recorder = r; temp = file; allLevels.clear(); activeMs = 0; resumedAt = SystemClock.elapsedRealtime()
        _state.value = State.Recording(System.currentTimeMillis(), 0, false, emptyList(), quality)
        ContextCompat.startForegroundService(context, Intent(context, RecorderService::class.java))
        ticker = scope.launch {
            while (isActive) {
                val s = _state.value as? State.Recording ?: break
                if (!s.paused) {
                    val level = RecorderMath.level(runCatching { r.maxAmplitude }.getOrDefault(0))
                    allLevels += level
                    _state.value = s.copy(elapsedMs = activeMs + SystemClock.elapsedRealtime() - resumedAt, levels = (s.levels + level).takeLast(160))
                }
                delay(60)
            }
        }
    }

    fun togglePause() {
        val s = _state.value as? State.Recording ?: return
        val r = recorder ?: return
        if (s.paused) { r.resume(); resumedAt = SystemClock.elapsedRealtime() } else { r.pause(); activeMs += SystemClock.elapsedRealtime() - resumedAt }
        _state.update { (it as? State.Recording)?.copy(paused = !s.paused) ?: it }
    }

    /** Stops and saves. Recordings shorter than half a second are dropped as accidental taps. */
    fun stop() {
        val s = _state.value as? State.Recording ?: return
        ticker?.cancel()
        _state.value = State.Saving
        scope.launch {
            withContext(NonCancellable) {
                val file = temp
                val ok = runCatching { recorder?.stop() }.isSuccess
                recorder?.release(); recorder = null
                context.stopService(Intent(context, RecorderService::class.java))
                if (ok && file != null && s.elapsedMs >= 500) runCatching { publish(file, s) }.onFailure { error.value = "Couldn't save the recording: ${it.message}" }
                file?.delete(); temp = null
                _state.value = State.Idle
            }
        }
    }

    private suspend fun publish(file: File, s: State.Recording) = withContext(Dispatchers.IO) {
        val name = RecorderMath.defaultName(s.startedAt)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$name.m4a".replace(':', '.'))
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Ultimate/Recordings")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: error("no storage")
        resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        val rec = Recording(UUID.randomUUID().toString(), name, uri.toString(), s.startedAt, s.elapsedMs, file.length(), RecorderMath.downsample(allLevels))
        _recordings.value = listOf(rec) + _recordings.value
        persist()
    }

    suspend fun remove(id: String, deleteFile: Boolean): Boolean = withContext(Dispatchers.IO) {
        val r = _recordings.value.firstOrNull { it.id == id } ?: return@withContext true
        val ok = !deleteFile || runCatching { context.contentResolver.delete(android.net.Uri.parse(r.uri), null, null) >= 0 }.getOrDefault(false)
        if (ok) { _recordings.value = _recordings.value - r; persist() }
        ok
    }

    private fun load(): List<Recording> {
        val a = index.read()
        return List(a.length()) { a.getJSONObject(it) }.mapNotNull { o ->
            runCatching {
                val p = o.getJSONArray("peaks")
                Recording(o.getString("id"), o.getString("name"), o.getString("uri"), o.getLong("at"), o.getLong("ms"), o.getLong("bytes"), List(p.length()) { p.getDouble(it).toFloat() })
            }.getOrNull()
        }
    }

    private suspend fun persist() = index.write(JSONArray().apply {
        _recordings.value.forEach { r -> put(JSONObject().put("id", r.id).put("name", r.name).put("uri", r.uri).put("at", r.createdAt).put("ms", r.durationMs).put("bytes", r.bytes).put("peaks", JSONArray(r.peaks.map { it.toDouble() }))) }
    })
}
