package app.ultimate.hid

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ultimate.core.GlassCard
import app.ultimate.core.GooeySwitch
import app.ultimate.core.Liquid
import app.ultimate.core.LiquidButton
import app.ultimate.core.LiquidInput
import app.ultimate.core.LibraryItem
import app.ultimate.core.Tool
import app.ultimate.core.ToolLaunch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.hypot

/** Keyboard & mouse: this phone (and your watch through it) as a Bluetooth keyboard and mouse for any computer, TV or headset. */
class HidTool(context: android.content.Context) : Tool {
    private val link = HidLink.get(context)
    override val id = "hid"
    override val title = "Keyboard & mouse"
    override val tagline = "Your phone as a keyboard and trackpad for any computer, TV or headset"
    override val icon = Icons.Rounded.Keyboard
    override val headline: StateFlow<String> = MutableStateFlow("Control any screen, nothing to install")
    override val library: StateFlow<List<LibraryItem>> = MutableStateFlow(emptyList())
    override val acceptedMimePrefixes: List<String> = emptyList()
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = true
    override suspend fun refresh() = Unit

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable
    override fun Screen(onExit: () -> Unit, launch: ToolLaunch) {
        val ctx = LocalContext.current
        val s by link.state.collectAsState()
        val askNearby = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) link.start() }
        val discover = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { link.refreshPaired() }
        Scaffold(containerColor = Liquid.bg, topBar = {
            TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = Liquid.bg, titleContentColor = Color.White, navigationIconContentColor = Color.White),
                title = { Text("Keyboard & mouse") }, navigationIcon = { IconButton(onExit) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
        }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                GlassCard(title = "Be a keyboard and mouse", live = s.connected != null, accent = Liquid.teal) {
                    Text("Your computer, tablet, TV or Quest sees this phone as an ordinary Bluetooth keyboard and mouse called Ultimate. Nothing to install there, and nothing is read from it.",
                        color = Liquid.dim, fontSize = 14.sp, lineHeight = 20.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(when { s.connected != null -> "Connected to ${s.connected!!.name}"; s.connecting != null -> "Connecting to ${s.connecting!!.name}…"; s.registered -> "Ready"; s.on -> "Starting…"; else -> "Off" },
                            color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        GooeySwitch(s.on, { on ->
                            if (!on) link.stop()
                            else if (!link.allowed() && Build.VERSION.SDK_INT >= 31) askNearby.launch(Manifest.permission.BLUETOOTH_CONNECT) else link.start()
                        })
                    }
                    s.error?.let { Text(it, color = Color(0xFFFFB4A9), fontSize = 13.sp) }
                    if (s.on) {
                        LiquidButton("Let a new device find this phone", { discover.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)) }, Modifier.fillMaxWidth())
                        Text("Then on the computer: Bluetooth settings › Add device › Ultimate. Already paired? Tap it below.", color = Liquid.dim, fontSize = 12.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            s.paired.forEach { h ->
                                val here = s.connected?.address == h.address
                                Text(h.name, color = if (here) Color(0xFF0A0E14) else Color.White, fontSize = 14.sp,
                                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (here) Liquid.teal else Color(0x22FFFFFF))
                                        .clickable(enabled = !here) { link.connect(h) }.padding(horizontal = 14.dp, vertical = 9.dp))
                            }
                        }
                    }
                }
                if (s.connected != null) {
                    Trackpad(link)
                    Keys(link)
                    GlassCard(title = "Your watch drives it", live = s.watchDrives, accent = Liquid.violet) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Open Trackpad on the watch and the water moves this computer's pointer instead of the phone's: tap clicks, hold right-clicks, the bezel scrolls.",
                                color = Liquid.dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            GooeySwitch(s.watchDrives, { link.setWatchDrives(it) })
                        }
                    }
                }
            }
        }
    }
}

/**
 * A liquid trackpad: one finger moves the pointer (faster swipes go further), a tap clicks, holding still right-clicks,
 * two fingers scroll. Ripples spread from where you touch.
 */
@Composable
private fun Trackpad(link: HidLink) {
    val haptic = LocalHapticFeedback.current
    val rings = remember { mutableStateListOf<Pair<Offset, Long>>() }
    var tick by remember { mutableStateOf(0L) }
    Box(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(28.dp))
        .background(Brush.linearGradient(listOf(Color(0xFF12202A), Color(0xFF1B1730))))
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(); val t0 = down.uptimeMillis
                rings += down.position to System.currentTimeMillis(); tick++
                var moved = 0f; var fingers = 1; var longDone = false; var last = down.position; var lastT = t0
                while (true) {
                    val ev = awaitPointerEvent()
                    val pressed = ev.changes.filter { it.pressed }
                    fingers = maxOf(fingers, pressed.size)
                    val c = ev.changes.firstOrNull { it.id == down.id }
                    if (pressed.isEmpty() || c == null) break
                    val d = c.position - last; val dt = (c.uptimeMillis - lastT).coerceAtLeast(1)
                    moved += hypot(d.x, d.y)
                    if (pressed.size >= 2) { if (kotlin.math.abs(d.y) > 18f) { link.scroll(if (d.y > 0) 1 else -1); last = c.position } }
                    else {
                        val speed = hypot(d.x, d.y) / dt // px per ms
                        val gain = 0.9f + (speed * 0.9f).coerceAtMost(2.5f)
                        link.move(d.x * gain, d.y * gain); last = c.position
                        if (!longDone && moved < 12f && c.uptimeMillis - t0 > 520) { longDone = true; link.click(HidReports.RIGHT); haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
                    }
                    lastT = c.uptimeMillis
                    ev.changes.forEach { it.consume() }
                }
                if (!longDone && moved < 12f && fingers == 1) { link.click(); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
            }
        }) {
        val now = System.currentTimeMillis()
        androidx.compose.runtime.LaunchedEffect(tick) { while (rings.isNotEmpty()) { kotlinx.coroutines.delay(16); rings.removeAll { System.currentTimeMillis() - it.second > 700 }; tick++ } }
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION") tick
            for ((p, t) in rings) { val a = ((now - t) / 700f).coerceIn(0f, 1f)
                drawCircle(Liquid.teal.copy(alpha = 0.6f * (1 - a)), 12f + a * 140f, p, style = Stroke(4f * (1 - a) + 1f)) }
        }
        Text("Trackpad · tap to click · hold to right-click · two fingers to scroll", color = Liquid.dim, fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Keys(link: HidLink) {
    var text by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    LiquidInput(text, { text = it }, "Type on the computer", {
        val skipped = link.type(text); note = if (skipped > 0) "$skipped character(s) have no key on a US keyboard and were skipped." else null; text = ""
    }, sendLabel = "Type")
    note?.let { Text(it, color = Liquid.dim, fontSize = 12.sp) }
    val chip: @Composable (String, () -> Unit) -> Unit = { label, act ->
        Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x22FFFFFF)).clickable(onClick = act).padding(horizontal = 16.dp, vertical = 10.dp))
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        chip("Esc") { link.key(HidReports.Key.Escape) }; chip("Tab") { link.key(HidReports.Key.Tab) }
        chip("←") { link.key(HidReports.Key.Left) }; chip("↑") { link.key(HidReports.Key.Up) }; chip("↓") { link.key(HidReports.Key.Down) }; chip("→") { link.key(HidReports.Key.Right) }
        chip("⌫") { link.key(HidReports.Key.Backspace) }; chip("Enter") { link.key(HidReports.Key.Enter) }
        chip("⏮") { link.media(HidReports.Media.Previous) }; chip("⏯") { link.media(HidReports.Media.PlayPause) }; chip("⏭") { link.media(HidReports.Media.Next) }
        chip("🔉") { link.media(HidReports.Media.VolumeDown) }; chip("🔊") { link.media(HidReports.Media.VolumeUp) }
    }
}
