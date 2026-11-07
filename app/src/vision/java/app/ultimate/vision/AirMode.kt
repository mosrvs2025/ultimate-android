package app.ultimate.vision

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.provider.Settings
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import app.ultimate.R
import app.ultimate.UltimateApp
import app.ultimate.ai.AiActions
import app.ultimate.ai.LocalAi
import app.ultimate.air.AirPointer
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.Executors
import kotlin.math.hypot

/** What Air mode is doing, shared between the camera service and the cursor drawn over every app. */
object AirBridge {
    /** The cursor: where it is on screen, whether a hand is seen, and whether it's pinched. */
    data class Cursor(val x: Float, val y: Float, val visible: Boolean, val pinched: Boolean)
    val running = MutableStateFlow(false)
    val cursor = MutableStateFlow(Cursor(0f, 0f, false, false))
    /** Things to do: taps and drags at screen positions, and Back, Home, Recents. */
    val actions = MutableSharedFlow<Any>(extraBufferCapacity = 16)
    object Back; object Home; object Recents
    /** Open an app (done by the cursor service, which may start activities from the background). */
    data class Launch(val pkg: String)
    /** The "Ask" pill at the top of the screen: what it says now (Ask, Listening…, what happened). */
    val ask = MutableStateFlow("Ask")
    /** Where the pill is on screen (left, top, right, bottom), set by the cursor service. */
    @Volatile var askRect = floatArrayOf(0f, 0f, 0f, 0f)
    /** True while the phone is in your hand: Air mode steps aside so touch works normally. */
    val paused = MutableStateFlow(false)
    /** A shortcut shape being held: what it will do and how full its ring is (0..1). */
    val hold = MutableStateFlow<Pair<String, Float>?>(null)
    /** A short tip shown next to the cursor while you get used to Air mode. */
    val hint = MutableStateFlow<String?>(null)

    private fun prefs(c: Context) = c.getSharedPreferences("air_mode", Context.MODE_PRIVATE)
    /** Hand-shape shortcuts (palm Back, fist Home, peace Recents): off unless turned on, so nothing happens by accident. */
    fun shortcutsOn(c: Context) = prefs(c).getBoolean("shortcuts", false)
    fun setShortcuts(c: Context, on: Boolean) = prefs(c).edit().putBoolean("shortcuts", on).apply()
    /** Whether the practice has been done once (it's offered before the first start). */
    /** Recording for training: off unless turned on; hand points and motion only, never pictures. */
    fun recordOn(c: Context) = prefs(c).getBoolean("record", false)
    fun setRecord(c: Context, on: Boolean) = prefs(c).edit().putBoolean("record", on).apply()
    fun recorder(c: Context) = app.ultimate.air.SenseRecorder(java.io.File(c.filesDir, "senses"))
    fun practiced(c: Context) = prefs(c).getBoolean("practiced", false)
    fun setPracticed(c: Context) = prefs(c).edit().putBoolean("practiced", true).apply()

    fun accessibilityOn(c: Context): Boolean {
        val me = ComponentName(c, AirCursorService::class.java).flattenToString()
        return Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').any { it.equals(me, true) }
    }
    fun start(c: Context) = ContextCompat.startForegroundService(c, Intent(c, AirModeService::class.java))
    fun stop(c: Context) = c.startService(Intent(c, AirModeService::class.java).setAction(AirModeService.STOP))
}

/**
 * Air mode's eyes: the front camera watches your hand while you use any app, and turns it into a cursor and taps.
 * It runs as a foreground service (Android shows its camera indicator and a notification with Stop), started only
 * from Air control, and stops when you tap Stop. Frames are looked at and dropped.
 */
class AirModeService : LifecycleService() {
    private val executor = Executors.newSingleThreadExecutor()
    private var recognizer: GestureRecognizer? = null
    private var pointer: AirPointer? = null
    private val holder = app.ultimate.air.ShortcutHold()
    private var seenSince = 0L
    /** Learns your shapes while you use Air mode; saved between sessions. */
    private var learner = app.ultimate.air.ShapeLearner()
    private var pointingFrames = 0
    private var lastX = 0f
    private var lastY = 0f
    private val trainer = Executors.newSingleThreadExecutor()
    /** The pinch decided from the camera and, when the watch is on your wrist, the taps it feels. */
    private val fusion = app.ultimate.air.PinchFusion()
    private val wristTap = app.ultimate.air.WristTap()
    private val context = app.ultimate.air.PhoneContext()
    private val motion = object : android.hardware.SensorEventListener {
        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            val held = context.feed(System.currentTimeMillis(), hypot(hypot(e.values[0], e.values[1]), e.values[2]))
            if (held != AirBridge.paused.value) AirBridge.paused.value = held
        }
    }
    private var listening: android.speech.SpeechRecognizer? = null
    private var rec: app.ultimate.air.SenseRecorder? = null
    private fun log(line: () -> String) { rec?.let { r -> trainer.execute { r.write(line()) } } }
    private fun learnFile() = java.io.File(filesDir, "air_shapes.json")
    private var taps = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Air mode", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, AirModeService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat).setContentTitle("Air mode is on")
            .setContentText("Point to move, pinch to tap, pinch and move to scroll.").setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA) else startForeground(ID, n)
        if (recognizer == null) begin()
        return START_NOT_STICKY
    }

    private fun begin() {
        val metrics = resources.displayMetrics
        pointer = AirPointer(metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
        recognizer = runCatching {
            GestureRecognizer.createFromOptions(this, GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").build())
                .setRunningMode(RunningMode.LIVE_STREAM).setNumHands(1)
                .setResultListener { result, _ -> onHand(result) }.setErrorListener { }.build())
        }.getOrNull() ?: run { stopSelf(); return }
        learner = runCatching { app.ultimate.air.ShapeLearner.fromJson(org.json.JSONObject(learnFile().readText())) }.getOrDefault(app.ultimate.air.ShapeLearner())
        AirBridge.running.value = true
        listenToWatch()
        if (AirBridge.recordOn(this)) rec = AirBridge.recorder(this).also { it.start(System.currentTimeMillis()) }
        getSystemService(android.hardware.SensorManager::class.java)?.let { sm ->
            sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(motion, it, android.hardware.SensorManager.SENSOR_DELAY_GAME) }
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
            analysis.setAnalyzer(executor) { proxy ->
                runCatching {
                    val rot = proxy.imageInfo.rotationDegrees
                    val bmp = proxy.toBitmap().let { b -> if (rot == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot.toFloat()) }, true) }
                    recognizer?.recognizeAsync(BitmapImageBuilder(bmp).build(), System.currentTimeMillis())
                }
                proxy.close()
            }
            runCatching { provider.unbindAll(); provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onHand(result: com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult) {
        val p = pointer ?: return
        val lm = result.landmarks().firstOrNull()
        val now = System.currentTimeMillis()
        if (lm == null || lm.size < 21) {
            p.lost(); AirBridge.cursor.value = AirBridge.cursor.value.copy(visible = false, pinched = false)
            holder.feed(null, 0f, 0f, now); AirBridge.hold.value = null; AirBridge.hint.value = null; seenSince = 0; return
        }
        // In your hand, the phone is for touching: the cursor hides and nothing is tapped until you set it down.
        if (AirBridge.paused.value) {
            p.lost(); AirBridge.cursor.value = AirBridge.cursor.value.copy(visible = false, pinched = false)
            AirBridge.hold.value = null; AirBridge.hint.value = null; return
        }
        if (seenSince == 0L) seenSince = now
        // The hand's size (wrist to middle knuckle) makes the pinch the same near the camera and far from it.
        val size = hypot(lm[0].x() - lm[9].x(), lm[0].y() - lm[9].y()).coerceAtLeast(1e-3f)
        val pinch = synchronized(fusion) { fusion.feed(hypot(lm[4].x() - lm[8].x(), lm[4].y() - lm[8].y()) / size, now) }
        // Front camera: mirror so moving your hand right moves the cursor right.
        p.feed(1f - lm[8].x(), lm[8].y(), pinch, now)?.let {
            val r = AirBridge.askRect
            if (it is AirPointer.Action.Tap && it.x in r[0]..r[2] && it.y in r[1]..r[3]) { ask(); return@let }
            if (it is AirPointer.Action.Tap) taps++; AirBridge.actions.tryEmit(it)
            log { app.ultimate.air.SenseRecorder.Lines.event(now, it.javaClass.simpleName.lowercase(), it.toString()) }
        }
        AirBridge.cursor.value = AirBridge.Cursor(p.x, p.y, true, p.pinched)
        // Tips next to the cursor for the first taps of each session, then they go away.
        val shown = now - seenSince
        AirBridge.hint.value = when {
            taps >= 3 || shown > 20_000 -> null
            p.pinched -> "Let go to tap · move to scroll"
            taps == 0 -> "Pinch thumb and finger to tap"
            else -> "Pinch and move to scroll"
        }
        // Shortcuts only when turned on, and only for a shape held still for a full second while its ring fills.
        if (!AirBridge.shortcutsOn(this)) { AirBridge.hold.value = null; return }
        val top = result.gestures().firstOrNull()?.firstOrNull()?.takeIf { it.score() > 0.7f }?.categoryName()
            ?.takeIf { it in NAMES && !p.pinched }
        val shape = app.ultimate.air.ShapeLearner.features(lm.map { it.x() to it.y() })
        if (rec != null) { val pts = lm.map { it.x() to it.y() }; val sc = result.gestures().firstOrNull()?.firstOrNull(); log { app.ultimate.air.SenseRecorder.Lines.hand(now, sc?.categoryName(), sc?.score() ?: 0f, pinch, pts) } }
        // Plain pointing around (moving, no shortcut shape) is what "not a shortcut" looks like: keep one now and then.
        val speed = hypot(p.x - lastX, p.y - lastY); lastX = p.x; lastY = p.y
        if (top == null && speed > 25f && ++pointingFrames % 45 == 0) learn(app.ultimate.air.ShapeLearner.NONE, shape)
        // Learned: shapes you clearly mean need less holding; ones you never mean start no ring.
        val hold = top?.let { synchronized(learner) { learner.holdMs(it, shape) } }
        val fired = holder.feed(top?.takeIf { hold != null }, p.x, p.y, now, hold ?: app.ultimate.air.ShapeLearner.FULL)
        holder.abandoned?.let { a -> holder.abandoned = null; learn(app.ultimate.air.ShapeLearner.NONE, shape); log { app.ultimate.air.SenseRecorder.Lines.event(now, "abandoned", a) } }
        AirBridge.hold.value = holder.progress?.let { (s, f) -> NAMES.getValue(s) to f }?.takeIf { it.second > 0.15f }
        if (fired != null) { learn(fired, shape); log { app.ultimate.air.SenseRecorder.Lines.event(now, "shortcut", fired) } }
        when (fired) { "Open_Palm" -> AirBridge.Back; "Closed_Fist" -> AirBridge.Home; "Victory" -> AirBridge.Recents; else -> null }
            ?.let { AirBridge.actions.tryEmit(it) }
    }

    /**
     * The watch, if linked, streams its motion while Air mode runs; a finger tap it feels backs up the camera's pinch.
     * Its readings are timed by when they arrive here, so the two clocks don't need to agree.
     */
    private fun listenToWatch() {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            var lastT = Long.MIN_VALUE; var renewed = 0L
            while (isActive) {
                val now = System.currentTimeMillis()
                if (now - renewed > 4_000) { app.ultimate.air.WatchFeed.request(true); renewed = now }
                app.ultimate.air.WatchFeed.samples.value.filter { it.t > lastT }.forEach { s ->
                    lastT = s.t
                    if (rec != null) log { app.ultimate.air.SenseRecorder.Lines.watch(s.t, s.gx, s.gy, s.gz, s.ax, s.ay, s.az) }
                    if (wristTap.feed(s.t, s.jolt, s.turn)) { synchronized(fusion) { fusion.watchTap(System.currentTimeMillis()) }; log { app.ultimate.air.SenseRecorder.Lines.event(System.currentTimeMillis(), "watch_tap") } }
                }
                kotlinx.coroutines.delay(20)
            }
        }
    }

    /** Pinching the Ask pill: listen once (on the phone where it can), then do what was said. */
    private fun ask() = android.os.Handler(mainLooper).post {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) { say("Allow the microphone for Ultimate first"); return@post }
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) { say("No speech recognition on this phone"); return@post }
        listening?.destroy()
        val sr = if (Build.VERSION.SDK_INT >= 31 && android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) android.speech.SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            else android.speech.SpeechRecognizer.createSpeechRecognizer(this)
        listening = sr
        sr.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) { AirBridge.ask.value = "Listening…" }
            override fun onResults(results: android.os.Bundle?) {
                val said = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (said.isNullOrBlank()) say("Didn't catch that") else act(said)
                sr.destroy(); if (listening === sr) listening = null
            }
            override fun onError(error: Int) { say("Didn't catch that"); sr.destroy(); if (listening === sr) listening = null }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { AirBridge.ask.value = "Thinking…" }
            override fun onPartialResults(partialResults: android.os.Bundle?) = Unit
            override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
        })
        sr.startListening(Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
    }

    /** Shows [text] on the pill for a few seconds, then it goes back to "Ask". */
    private fun say(text: String) {
        AirBridge.ask.value = text
        lifecycleScope.launch { kotlinx.coroutines.delay(4_000); if (AirBridge.ask.value == text) AirBridge.ask.value = "Ask" }
    }

    /** Does what was said: navigation and opening apps directly, anything else through Local AI (Needle). */
    private fun act(said: String) {
        val pm = packageManager
        val launchers = runCatching { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0) }.getOrDefault(emptyList())
        val labels = launchers.associate { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        val w = resources.displayMetrics.widthPixels.toFloat(); val h = resources.displayMetrics.heightPixels.toFloat()
        when (val c = app.ultimate.air.AirCommands.parse(said, labels.keys.toList())) {
            is app.ultimate.air.AirCommands.Command.OpenApp -> { AirBridge.actions.tryEmit(AirBridge.Launch(labels.getValue(c.label))); say("Opening ${c.label}") }
            app.ultimate.air.AirCommands.Command.Back -> { AirBridge.actions.tryEmit(AirBridge.Back); say("Back") }
            app.ultimate.air.AirCommands.Command.Home -> { AirBridge.actions.tryEmit(AirBridge.Home); say("Home") }
            app.ultimate.air.AirCommands.Command.Recents -> { AirBridge.actions.tryEmit(AirBridge.Recents); say("Recent apps") }
            is app.ultimate.air.AirCommands.Command.Scroll -> {
                val (a, b) = if (c.down) h * 0.7f to h * 0.3f else h * 0.3f to h * 0.7f
                AirBridge.actions.tryEmit(AirPointer.Action.Drag(w / 2, a, w / 2, b, 300)); say(if (c.down) "Scrolled down" else "Scrolled up")
            }
            null -> {
                val ua = application as? UltimateApp
                val ai = ua?.localAi
                if (ua == null || ai == null || ai.state.value != LocalAi.State.Ready) { say("\"$said\": I can open apps, go back, home or scroll. Turn on Local AI in About for more"); return }
                lifecycleScope.launch {
                    val call = runCatching { ai.propose(said) }.getOrDefault(emptyList()).firstOrNull()
                    say(if (call == null) "\"$said\": not sure what to do" else AiActions.run(ua, call))
                }
            }
        }
    }

    /** One more lesson; now and then the network retrains and everything is saved, off the camera's thread. */
    private fun learn(label: String, x: FloatArray) {
        val l = learner
        trainer.execute {
            synchronized(l) {
                l.add(label, x)
                if (l.due()) runCatching { l.train() }
                runCatching { learnFile().writeText(l.toJson().toString()) }
            }
        }
    }

    override fun onDestroy() {
        AirBridge.running.value = false
        AirBridge.cursor.value = AirBridge.Cursor(0f, 0f, false, false)
        AirBridge.hold.value = null; AirBridge.hint.value = null
        runCatching { recognizer?.close() }; recognizer = null
        rec?.let { r -> trainer.execute { r.stop() } }; rec = null
        executor.shutdown(); trainer.shutdown()
        app.ultimate.air.WatchFeed.request(false)
        getSystemService(android.hardware.SensorManager::class.java)?.unregisterListener(motion)
        listening?.destroy(); listening = null
        AirBridge.paused.value = false; AirBridge.ask.value = "Ask"
        super.onDestroy()
    }

    companion object {
        const val STOP = "app.ultimate.air.STOP"
        private val NAMES = mapOf("Open_Palm" to "Back", "Closed_Fist" to "Home", "Victory" to "Recent apps")
        private const val CHANNEL = "air_mode"
        private const val ID = 4401
    }
}
