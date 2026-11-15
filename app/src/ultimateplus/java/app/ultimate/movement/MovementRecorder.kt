package app.ultimate.movement

import android.content.Context
import android.content.Intent
import app.ultimate.UltimateApp
import app.ultimate.fusion.MovementAnalyzer
import app.ultimate.core.Signal
import app.ultimate.health.LiveHeart
import app.ultimate.watch.WatchLink
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
        /** The newest GPS fix (latitude, longitude, accuracy in metres), refreshed every second for the live map. */
        val fix: DoubleArray? = null,
    )

    private val store = MovementStore(app)
    private val recorder = WorldSensorRecorder(app, maxBytes = 48L * 1024 * 1024)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _live = MutableStateFlow(Live())
    val live: StateFlow<Live> = _live.asStateFlow()
    private var job: Job? = null
    private var heartJob: Job? = null
    private var tickJob: Job? = null

    /** The watch's step counter, written into the recording (on this phone's clock, when it arrived) as an independent count of the same steps. */
    fun watchSteps(count: Long) {
        if (_live.value.recording) recorder.extra("wsteps", "{\"type\":\"wsteps\",\"t\":${android.os.SystemClock.elapsedRealtimeNanos()},\"n\":$count}")
    }
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
            // The watch shows the recording and measures heart rate closely while it runs; its readings are written into the recording.
            WatchLink.sendToWatch(app, "movement:start")
            heartJob = scope.launch { LiveHeart.bpm.collect { v -> if (v != null && v.second >= startedAt) recorder.extra("hr", "{\"type\":\"hr\",\"t\":${android.os.SystemClock.elapsedRealtimeNanos()},\"bpm\":${v.first}}") } }
            val pending = java.util.concurrent.ConcurrentLinkedQueue<String>()
            recorder.tap = pending
            // Every second: the clock, GPS and where you are now, without waiting for the next full analysis.
            tickJob = scope.launch {
                while (isActive) {
                    delay(1_000)
                    recorder.refreshAge()
                    val f = recorder.fixes().lastOrNull()
                    _live.value = _live.value.copy(elapsedS = (android.os.SystemClock.elapsedRealtime() - startNs) / 1000.0, bytes = file.length(), gps = recorder.gnss.value,
                        fix = f?.let { doubleArrayOf(it.latitudeDeg, it.longitudeDeg, it.horizontalAccuracyMeters.toDouble()) } ?: _live.value.fix)
                }
            }
            job = scope.launch {
                var every = 4_000L
                while (isActive) {
                    delay(every)
                    val t0 = System.currentTimeMillis()
                    val size = file.length()
                    _live.value = _live.value.copy(bytes = size, busy = true)
                    // Only the lines written since the last pass are added (handed over by the recorder as they were written).
                    val rep = runCatching {
                        while (true) { val l = pending.poll() ?: break; builder.add(l) }
                        MovementAnalyzer.analyze(builder.build(), store.calibrator(), android.os.Build.MODEL)
                    }.getOrNull()
                    if (rep != null) for (sig in MovementSignals.between(_live.value.report, rep)) app.senses.publish(Signal("movement", sig.kind, sig.label, sig.data))
                    _live.value = _live.value.copy(report = rep ?: _live.value.report, busy = false)
                    WatchLink.sendToWatch(app, "movement:status:${"%.0f".format(java.util.Locale.US, rep?.distance?.value ?: 0.0)}:${_live.value.elapsedS.toInt()}")
                    // Re-reading a long recording costs more each time, so it is done less often as the file grows (never less than 6 s apart).
                    every = (4_000L + (System.currentTimeMillis() - t0) * 2).coerceAtMost(30_000L)
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
        heartJob?.cancel(); heartJob = null
        tickJob?.cancel(); tickJob = null
        recorder.tap = null
        WatchLink.sendToWatch(app, "movement:stop")
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
