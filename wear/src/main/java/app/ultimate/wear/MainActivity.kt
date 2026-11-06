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
    val detector = remember { WristGestures() }
    detector.rightWrist = rightWrist

    fun send(message: String, label: String) {
        last = label to System.currentTimeMillis()
        buzz(context)
        link.send(message) { ok -> sent = ok }
    }

    // Which phone is in reach, checked every few seconds so the screen says when the link drops.
    LaunchedEffect(Unit) { while (true) { link.phoneName { phone = it }; delay(5_000) } }

    // The sensors listen only while gestures are on and this screen is showing.
    DisposableEffect(gestures) {
        val sm = context.getSystemService(SensorManager::class.java)
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            override fun onSensorChanged(e: SensorEvent) {
                val now = System.currentTimeMillis()
                val g = when (e.sensor.type) {
                    Sensor.TYPE_GYROSCOPE -> detector.gyro(e.values[0], e.values[1], e.values[2], now)
                    Sensor.TYPE_LINEAR_ACCELERATION -> detector.accel(e.values[0], e.values[1], e.values[2], now)
                    else -> null
                } ?: return
                send("gesture:$g", WristGestures.LABELS[g] ?: g)
            }
        }
        if (gestures) {
            sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
            sm?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        }
        onDispose { sm?.unregisterListener(listener) }
    }

    val list = rememberScalingLazyListState()
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
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 4.dp))
            }
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
                ToggleChip(rightWrist, { rightWrist = it; prefs.edit().putBoolean("right_wrist", it).apply() }, label = { Text("Worn on the right wrist") },
                    toggleControl = { androidx.wear.compose.material.Switch(rightWrist) }, modifier = Modifier.fillMaxWidth())
            }
            item {
                Text("What each gesture does is set on the phone, in Ultimate+ → Watch, where \"Watch controls the phone\" must be on.",
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
