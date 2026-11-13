package app.ultimate.movement

import android.content.Context
import android.content.Intent
import app.ultimate.UltimateApp
import app.ultimate.fusion.MovementAnalyzer
import app.ultimate.core.Signal
import app.ultimate.fusion.MovementReport
import app.ultimate.fusion.MovementSignals
import app.ultimate.fusion.TraceBuilder
import app.ultimate.worldscan.GnssLive
import app.ultimate.worldscan.WorldSensorRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Records a walk or a run with the phone's own sensors (the same recorder World Scan uses, so there is one place that knows how to
 * write the raw log) and re-reads it every few seconds to show the analysis live. The recording is a file; the live view is just the
 * analysis of that file so far, so what you see while moving is exactly what you will see afterwards.
 *
 * It runs only after you press Start, keeps a notification with Stop on screen the whole time (a foreground service, so recording
 * continues with the screen off and the phone in a pocket or on an arm), writes nothing but a private file, and uses no network.
 */
class MovementRecorder private constructor(private val app: UltimateApp) {
    data class Live(
        val recording: Boolean = false, val elapsedS: Double = 0.0, val bytes: Long = 0, val gps: GnssLive = GnssLive(),
        val report: MovementReport? = null, val busy: Boolean = false, val error: String? = null, val savedId: String? = null,
    )

    private val store = MovementStore(app)
    private val recorder = WorldSensorRecorder(app, maxBytes = 48L * 1024 * 1024)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _live = MutableStateFlow(Live())
    val live: StateFlow<Live> = _live.asStateFlow()
    private var job: Job? = null
    private var id: String? = null
    private var startedAt = 0L
    private var startNs = 0L
    private var builder = TraceBuilder()
    /** How many lines of the log are already in [builder] (up to the last one that parsed, so a half-written last line is retried). */
    private var consumed = 0

    fun start(): Boolean {
        if (_live.value.recording) return true
        val (rid, file) = store.newRecording()
        return try {
            recorder.start(file)
            builder = TraceBuilder(); consumed = 0
            id = rid; startedAt = System.currentTimeMillis(); startNs = android.os.SystemClock.elapsedRealtime()
            _live.value = Live(recording = true)
            runCatching { app.startForegroundService(Intent(app, MovementService::class.java)) }
            app.senses.begin(SESSION, "movement", "Recording movement", sensitive = true) { stop() }
            job = scope.launch {
                var every = 6_000L
                while (isActive) {
                    delay(every)
                    val t0 = System.currentTimeMillis()
                    val size = file.length()
                    _live.value = _live.value.copy(elapsedS = (android.os.SystemClock.elapsedRealtime() - startNs) / 1000.0, bytes = size, gps = recorder.gnss.value, busy = true)
                    // Only the lines written since the last pass are parsed; the rest of the recording is already in the builder.
                    val rep = runCatching {
                        var index = 0
                        for (line in store.lines(file)) { if (index >= consumed) { if (builder.add(line)) consumed = index + 1 }; index++ }
                        MovementAnalyzer.analyze(builder.build(), store.calibrator(), android.os.Build.MODEL)
                    }.getOrNull()
                    if (rep != null) for (sig in MovementSignals.between(_live.value.report, rep)) app.senses.publish(Signal("movement", sig.kind, sig.label, sig.data))
                    _live.value = _live.value.copy(report = rep ?: _live.value.report, busy = false)
                    // Re-reading a long recording costs more each time, so it is done less often as the file grows (never less than 6 s apart).
                    every = (6_000L + (System.currentTimeMillis() - t0) * 3).coerceAtMost(40_000L)
                }
            }
            true
        } catch (e: Exception) {
            runCatching { file.delete() }; cleanup(rid)
            _live.value = Live(error = e.message ?: "Could not start recording"); false
        }
    }

    private fun cleanup(rid: String) { runCatching { java.io.File(java.io.File(app.filesDir, "movement"), rid).deleteRecursively() } }

    /** Stops, keeps the recording, and returns its id (null if it was too short to be worth keeping). */
    fun stop(): String? {
        if (!_live.value.recording) return _live.value.savedId
        job?.cancel(); job = null
        val summary = runCatching { recorder.stop() }.getOrNull()
        val rid = id; id = null
        app.senses.end(SESSION)
        runCatching { app.stopService(Intent(app, MovementService::class.java)) }
        val dur = (android.os.SystemClock.elapsedRealtime() - startNs) / 1000.0
        var saved: String? = null
        if (rid != null && dur >= 15 && summary != null) {
            val name = "Movement · " + java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(startedAt))
            store.finish(rid, name, startedAt, dur); saved = rid
        } else if (rid != null) cleanup(rid)
        _live.value = Live(savedId = saved, report = null)
        return saved
    }

    fun dismissSaved() { _live.value = Live() }

    companion object {
        const val SESSION = "movement.record"
        @Volatile private var instance: MovementRecorder? = null
        fun get(app: UltimateApp) = instance ?: synchronized(this) { instance ?: MovementRecorder(app).also { instance = it } }
    }
}
