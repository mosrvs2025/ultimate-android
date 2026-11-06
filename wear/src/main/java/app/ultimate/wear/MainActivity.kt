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
                // The live stream: one frame per gyroscope reading with the latest acceleration, sent in batches of ~100 ms.
                if (streaming && e.sensor.type == Sensor.TYPE_GYROSCOPE) {
                    if (frameCount == 0) batchStart = now
                    if (frameCount > 0) frames.append('|')
                    frames.append(now - batchStart).append(',')
                        .append("%.2f,%.2f,%.2f,%.2f,%.2f,%.2f".format(java.util.Locale.ROOT, e.values[0], e.values[1], e.values[2], acc[0], acc[1], acc[2]))
                    frameCount++
                    if (now - batchStart >= 100) { link.stream("sensors:$frames"); frames.setLength(0); frameCount = 0 }
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

    // Three pages, swiped sideways: gestures, a remote, and status. Each fits the round screen without scrolling far.
    val pager = androidx.compose.foundation.pager.rememberPagerState { 3 }
    Scaffold(timeText = { TimeText() }, pageIndicator = {
        androidx.wear.compose.material.HorizontalPageIndicator(object : androidx.wear.compose.material.PageIndicatorState {
            override val pageOffset get() = pager.currentPageOffsetFraction
            override val selectedPage get() = pager.currentPage
            override val pageCount get() = 3
        })
    }) {
        androidx.compose.foundation.pager.HorizontalPager(pager) { page ->
            when (page) {
                0 -> GesturesPage(gestures, { gestures = it }, last, streaming, phone)
                1 -> RemotePage(::send)
                else -> StatusPage(phone, calibrated, rightWrist, { rightWrist = it; prefs.edit().putBoolean("right_wrist", it).apply() }, sent == false)
            }
        }
    }
}

/** Page 1: the switch and a big readout of what the watch heard. */
@Composable
private fun GesturesPage(on: Boolean, setOn: (Boolean) -> Unit, last: Pair<String, Long>?, streaming: Boolean, phone: String?) {
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
        val now = System.currentTimeMillis()
        val shown = last?.takeIf { now - it.second < 2_500 }
        Text(shown?.first ?: if (on) "Flick, twist or shake" else "Wrist gestures", fontSize = if (shown != null) 22.sp else 16.sp,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = if (shown != null) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface)
        ToggleChip(on, setOn, label = { Text(if (on) "On" else "Off") }, secondaryLabel = { Text(if (on) "Listening while open" else "Tap to start") },
            toggleControl = { androidx.wear.compose.material.Switch(on) }, modifier = Modifier.fillMaxWidth(), colors = ToggleChipDefaults.toggleChipColors())
        Text(when { phone == null -> "Phone not in reach"; streaming -> "Live on your phone"; else -> "Swipe for the remote →" },
            style = MaterialTheme.typography.caption3, textAlign = TextAlign.Center, color = MaterialTheme.colors.onSurfaceVariant)
    }
}

/** Page 2: big buttons for the phone's music, volume and flashlight. */
@Composable
private fun RemotePage(send: (String, String) -> Unit) {
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Round("⏮") { send("button:previous", "Previous") }
            Round("⏯", primary = true) { send("button:playpause", "Play / pause") }
            Round("⏭") { send("button:next", "Next") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Round("−") { send("button:volume_down", "Volume down") }
            Round("🔦") { send("button:torch", "Flashlight") }
            Round("+") { send("button:volume_up", "Volume up") }
        }
    }
}

/** Page 3: the link, calibration, which wrist, and the version. */
@Composable
private fun StatusPage(phone: String?, calibrated: Boolean, rightWrist: Boolean, setRight: (Boolean) -> Unit, noAnswer: Boolean) {
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?" }
    ScalingLazyColumn(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Text(phone?.let { "Linked to $it" } ?: "Looking for your phone…", style = MaterialTheme.typography.caption1, textAlign = TextAlign.Center,
            color = if (phone != null) MaterialTheme.colors.secondary else MaterialTheme.colors.onSurfaceVariant) }
        if (noAnswer) item { Text("The phone didn't answer. Is Ultimate+ installed there?", style = MaterialTheme.typography.caption3, textAlign = TextAlign.Center) }
        item { Text(if (calibrated) "Calibrated to your wrist ✓" else "Not calibrated: on the phone, Watch → Calibrate", style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center) }
        item { ToggleChip(rightWrist, setRight, label = { Text("Right wrist") }, toggleControl = { androidx.wear.compose.material.Switch(rightWrist) }, modifier = Modifier.fillMaxWidth()) }
        item { Text("Ultimate+ $version · updates come from the phone (Watch → Setup)", style = MaterialTheme.typography.caption3, textAlign = TextAlign.Center,
            color = MaterialTheme.colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp)) }
    }
}

@Composable
private fun Round(label: String, primary: Boolean = false, onClick: () -> Unit) {
    Button(onClick, Modifier.size(ButtonDefaults.DefaultButtonSize), colors = if (primary) ButtonDefaults.primaryButtonColors() else ButtonDefaults.secondaryButtonColors()) {
        Text(label, fontSize = 20.sp)
    }
}

private fun buzz(c: Context) = runCatching { c.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(40, 180)) }
