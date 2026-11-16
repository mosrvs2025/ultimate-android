package app.ultimate.movement

import android.content.Context
import android.content.Intent
import app.ultimate.UltimateApp
import app.ultimate.fusion.MovementAnalyzer
import app.ultimate.core.Signal
import app.ultimate.health.LiveHeart
import app.ultimate.watch.WatchActivity
import app.ultimate.watch.WatchLink
import app.ultimate.fusion.WatchBout
import app.ultimate.MainActivity
import app.ultimate.R
import app.ultimate.fusion.Gait
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
        /** When recording started, on the phone's uptime clock, so the screen's clock can count by itself. */
        val startedRealtime: Long = 0L,
        /** Seconds of this walk that happened before the phone started recording, known from the watch (0 if none). */
        val earlySeconds: Double = 0.0, val earlyWatchSteps: Long? = null,
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
            // If the watch has been walking with you for a while, the clock starts when it says the walk began, not at 0:00.
            val w = WatchActivity.now.value
            val adopted = w?.let { WatchBout.adoptedStart(startedAt, it.state, it.since, it.at) }
            val early = adopted?.let { (startedAt - it) / 1000.0 } ?: 0.0
            if (adopted != null) { startedAt = adopted; startNs -= (early * 1000).toLong() }
            val earlySteps = if (adopted != null) WatchActivity.stepsSince(adopted) else null
            _live.value = Live(recording = true, startedRealtime = startNs, earlySeconds = early, earlyWatchSteps = earlySteps)
            if (adopted != null) recorder.extra("early", "{\"type\":\"early\",\"t\":${android.os.SystemClock.elapsedRealtimeNanos()},\"seconds\":${"%.0f".format(java.util.Locale.US, early)},\"wsteps\":${earlySteps ?: -1}}")
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
                    if (rep != null) stillNudge(rep)
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

    private var nudged = false

    /**
     * A recording left running while you stand or sit still fills the review with minutes of nothing (one walk ended with twelve).
     * After five quiet minutes, once the recording is at least ten minutes long, a notification offers to stop it. It never stops by
     * itself: you may be waiting at a crossing or resting between sets. Walking again re-arms it.
     */
    private fun stillNudge(rep: MovementReport) {
        val last = rep.gait.lastOrNull() ?: return
        if (last.gait == Gait.Walking || last.gait == Gait.Running) { nudged = false; return }
        if (nudged || last.gait != Gait.Still || rep.durationS < 600 || rep.durationS - last.span.from < STILL_NUDGE_S) return
        if (android.os.Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        nudged = true
        val nm = app.getSystemService(android.app.NotificationManager::class.java) ?: return
        nm.createNotificationChannel(android.app.NotificationChannel("movement-still", "Movement: still for a while", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        val stop = android.app.PendingIntent.getService(app, 53, Intent(app, MovementService::class.java).setAction(MovementService.ACTION_STOP), android.app.PendingIntent.FLAG_IMMUTABLE)
        val open = android.app.PendingIntent.getActivity(app, 54, Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_TOOL, "movement").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), android.app.PendingIntent.FLAG_IMMUTABLE)
        nm.notify(STILL_ID, androidx.core.app.NotificationCompat.Builder(app, "movement-still").setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Still for ${(STILL_NUDGE_S / 60).toInt()} minutes. Stop recording?")
            .setContentText("The recording is still running. It keeps going if you're only resting.").setContentIntent(open).setAutoCancel(true)
            .addAction(0, "Stop and review", stop).build())
    }

    private fun cleanup(rid: String) { runCatching { java.io.File(java.io.File(app.filesDir, "movement"), rid).deleteRecursively() } }

    /** Stops, keeps the recording, and returns its id (null if it was too short to be worth keeping). */
    fun stop(): String? {
        if (!_live.value.recording) return _live.value.savedId
        job?.cancel(); job = null
        heartJob?.cancel(); heartJob = null
        tickJob?.cancel(); tickJob = null
        recorder.tap = null
        nudged = false
        runCatching { app.getSystemService(android.app.NotificationManager::class.java)?.cancel(STILL_ID) }
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
        const val STILL_NUDGE_S = 300.0
        const val STILL_ID = 75
        @Volatile private var instance: MovementRecorder? = null
        fun get(app: UltimateApp) = instance ?: synchronized(this) { instance ?: MovementRecorder(app).also { instance = it } }
    }
}
