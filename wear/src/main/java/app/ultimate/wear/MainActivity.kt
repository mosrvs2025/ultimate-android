package app.ultimate.wear

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import kotlinx.coroutines.delay

/**
 * Ultimate+ on the watch: wrist gestures and big buttons that control the phone, over Bluetooth or, when the phone is
 * further away, Wi-Fi. Gestures start off on every visit and only listen while this screen is on.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val link = PhoneLink(this)
        // Say which version is here, so the phone can offer an update.
        link.send(WatchUpdateService.hello(this))
        setContent { MaterialTheme { WatchScreen(link) } }
    }
}

@Composable
private fun WatchScreen(link: PhoneLink) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("watch", Context.MODE_PRIVATE) }
    var gestures by remember { mutableStateOf(false) }
    var rightWrist by remember { mutableStateOf(prefs.getBoolean("right_wrist", false)) }
    var phone by remember { mutableStateOf<String?>(null) }
    var last by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var sent by remember { mutableStateOf<Boolean?>(null) }
    val detector = remember { WristGestures().apply { prefs.getString("calibration", null)?.let { configure(it) } } }
    detector.rightWrist = rightWrist
    // Heart rate goes to the phone only while this is on and the app is open.
    var shareHeart by remember { mutableStateOf(prefs.getBoolean("share_heart", false)) }
    val heartPermission = if (android.os.Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else android.Manifest.permission.BODY_SENSORS
    val askHeart = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        shareHeart = ok; prefs.edit().putBoolean("share_heart", ok).apply()
    }
    DisposableEffect(shareHeart) {
        val sm = context.getSystemService(SensorManager::class.java)
        var lastSent = 0L
        val l = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            override fun onSensorChanged(e: SensorEvent) {
                val bpm = e.values.firstOrNull()?.toInt() ?: return
                val now = System.currentTimeMillis()
                if (bpm > 0 && now - lastSent >= 5_000) { lastSent = now; link.stream("hr:$bpm") }
            }
        }
        val allowed = context.checkSelfPermission(heartPermission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (shareHeart && allowed) sm?.getDefaultSensor(Sensor.TYPE_HEART_RATE)?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_NORMAL) }
        onDispose { sm?.unregisterListener(l) }
    }
    fun toggleHeart(on: Boolean) {
        if (!on) { shareHeart = false; prefs.edit().putBoolean("share_heart", false).apply(); return }
        if (context.checkSelfPermission(heartPermission) == android.content.pm.PackageManager.PERMISSION_GRANTED) { shareHeart = true; prefs.edit().putBoolean("share_heart", true).apply() }
        else askHeart.launch(heartPermission)
    }
    var calibrated by remember { mutableStateOf(prefs.getString("calibration", null) != null) }
    // The phone asks for the live sensor stream while its Watch screen shows it, and renews the ask every few seconds.
    var streamUntil by remember { mutableStateOf(0L) }
    var streaming by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { streaming = System.currentTimeMillis() < streamUntil; delay(1_000) } }
    DisposableEffect(Unit) {
        val stop = link.listen { text ->
            when {
                text == "stream:on" -> { streamUntil = System.currentTimeMillis() + 8_000; streaming = true }
                text == "stream:off" -> { streamUntil = 0; streaming = false }
                text.startsWith("config:") -> if (detector.configure(text)) {
                    prefs.edit().putString("calibration", text).apply(); calibrated = true
                    last = "Calibrated by your phone" to System.currentTimeMillis(); buzz(context)
                }
                text == "calibration:reset" -> { prefs.edit().remove("calibration").apply(); detector.directions = null; detector.flickRate = 4.5f; detector.twistAxis = 'y'; calibrated = false }
            }
        }
        onDispose { stop() }
    }

    fun send(message: String, label: String) {
        last = label to System.currentTimeMillis()
        buzz(context)
        link.send(message) { ok -> sent = ok }
    }

    // Which phone is in reach, checked every few seconds so the screen says when the link drops.
    LaunchedEffect(Unit) { while (true) { link.phoneName { phone = it }; delay(5_000) } }

    // The sensors listen only while gestures are on or the phone is watching them, and this screen is showing.
    DisposableEffect(gestures, streaming) {
        val frames = StringBuilder(); var batchStart = 0L; var frameCount = 0
        val acc = FloatArray(3)
        val sm = context.getSystemService(SensorManager::class.java)
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            override fun onSensorChanged(e: SensorEvent) {
                val now = System.currentTimeMillis()
                if (e.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) { acc[0] = e.values[0]; acc[1] = e.values[1]; acc[2] = e.values[2] }
                // The live stream: one frame per gyroscope reading with the latest acceleration, sent in batches of ~50 ms.
                if (streaming && e.sensor.type == Sensor.TYPE_GYROSCOPE) {
                    if (frameCount == 0) batchStart = now
                    if (frameCount > 0) frames.append('|')
                    frames.append(now - batchStart).append(',')
                        .append("%.2f,%.2f,%.2f,%.2f,%.2f,%.2f".format(java.util.Locale.ROOT, e.values[0], e.values[1], e.values[2], acc[0], acc[1], acc[2]))
                    frameCount++
                    if (now - batchStart >= 50) { link.stream("sensors:$frames"); frames.setLength(0); frameCount = 0 }
                }
                if (!gestures) return
                val g = when (e.sensor.type) {
                    Sensor.TYPE_GYROSCOPE -> detector.gyro(e.values[0], e.values[1], e.values[2], now)
                    Sensor.TYPE_LINEAR_ACCELERATION -> detector.accel(e.values[0], e.values[1], e.values[2], now)
                    else -> null
                } ?: return
                send("gesture:$g", WristGestures.LABELS[g] ?: g)
            }
        }
        if (gestures || streaming) {
            sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
            sm?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        }
        onDispose { sm?.unregisterListener(listener) }
    }

    // One screen, scrolled with the bezel or a finger: everything in reach without swiping between pages.
    val list = rememberScalingLazyListState()
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?" }
    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(Modifier.fillMaxWidth(), state = list, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Text(phone?.let { "Linked to $it" } ?: "Looking for your phone…", style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center,
                    color = if (phone != null) MaterialTheme.colors.secondary else MaterialTheme.colors.onSurfaceVariant)
            }
            item {
                val now = System.currentTimeMillis()
                val shown = last?.takeIf { now - it.second < 2_500 }
                Text(shown?.first ?: if (gestures) "Flick, twist or shake" else "Ultimate+", fontSize = if (shown != null) 22.sp else 18.sp,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 4.dp),
                    color = if (shown != null) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface)
            }
            if (streaming) item { Text("Showing live sensors on your phone", style = MaterialTheme.typography.caption3, color = MaterialTheme.colors.secondary, textAlign = TextAlign.Center) }
            if (sent == false) item { Text("The phone didn't answer. Is Ultimate+ installed there?", style = MaterialTheme.typography.caption3, textAlign = TextAlign.Center) }
            item {
                ToggleChip(gestures, { gestures = it }, label = { Text("Wrist gestures") },
                    secondaryLabel = { Text(if (gestures) "On while this is open" else "Off") },
                    toggleControl = { androidx.wear.compose.material.Switch(gestures) }, modifier = Modifier.fillMaxWidth(),
                    colors = ToggleChipDefaults.toggleChipColors())
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Round("⏮") { send("button:previous", "Previous") }
                    Round("⏯", primary = true) { send("button:playpause", "Play / pause") }
                    Round("⏭") { send("button:next", "Next") }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Round("−") { send("button:volume_down", "Volume down") }
                    Round("🔦") { send("button:torch", "Flashlight") }
                    Round("+") { send("button:volume_up", "Volume up") }
                }
            }
            item {
                ToggleChip(shareHeart, ::toggleHeart, label = { Text("Share heart rate") }, secondaryLabel = { Text(if (shareHeart) "With the phone, while open" else "Off") },
                    toggleControl = { androidx.wear.compose.material.Switch(shareHeart) }, modifier = Modifier.fillMaxWidth())
            }
            item {
                ToggleChip(rightWrist, { rightWrist = it; prefs.edit().putBoolean("right_wrist", it).apply() }, label = { Text("Worn on the right wrist") },
                    toggleControl = { androidx.wear.compose.material.Switch(rightWrist) }, modifier = Modifier.fillMaxWidth())
            }
            item {
                Text((if (calibrated) "Calibrated to your wrist. " else "Not calibrated yet: on the phone, Ultimate+ → Watch → Calibrate. ") +
                    "Ultimate+ $version; updates come from the phone.",
                    style = MaterialTheme.typography.caption3, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
            }
        }
    }
}

@Composable
private fun Round(label: String, primary: Boolean = false, onClick: () -> Unit) {
    Button(onClick, Modifier.size(ButtonDefaults.DefaultButtonSize), colors = if (primary) ButtonDefaults.primaryButtonColors() else ButtonDefaults.secondaryButtonColors()) {
        Text(label, fontSize = 20.sp)
    }
}

private fun buzz(c: Context) = runCatching { c.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(40, 180)) }
