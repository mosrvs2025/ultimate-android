package app.ultimate.vision

import android.Manifest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ultimate.UltimateApp
import app.ultimate.air.AirCanvas
import app.ultimate.air.DeskPiano
import app.ultimate.air.FaceFrame
import app.ultimate.air.HandPose
import app.ultimate.core.LibraryItem
import app.ultimate.core.Tool
import app.ultimate.core.ToolBackButton
import app.ultimate.core.ToolLaunch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Playground: the camera as a toy, separate from Air control's phone shortcuts so nothing fires by accident.
 * Desk piano (back camera looking at the desk, all five fingertips play), Air draw (pinch to draw in the air) and
 * Face fun (stickers that sit on your face, and drawings that stay on it as you move).
 */
class PlaygroundTool(private val app: UltimateApp) : Tool {
    override val id = "playground"
    override val title = "Playground"
    override val tagline = "Play a piano on your desk, draw in the air, put stickers on your face"
    override val icon = Icons.Rounded.Celebration
    override val acceptedMimePrefixes = emptyList<String>()
    override val headline: StateFlow<String> = MutableStateFlow("Desk piano · Air draw · Face fun")
    override val library: StateFlow<List<LibraryItem>> = MutableStateFlow(emptyList())
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = false
    override suspend fun refresh() = Unit

    private val hand = MutableStateFlow<HandPose?>(null)
    private val face = MutableStateFlow<AirTool.FacePose?>(null)

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Screen(onExit: () -> Unit, launch: ToolLaunch) {
        BackHandler(onBack = onExit)
        val context = LocalContext.current
        var allowed by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
        // "call" opens straight into hands-free calling (from the call-me hand shape in Air control).
        var mode by remember { mutableIntStateOf(if (launch.query == "call") 3 else 0) }
        val view = LocalView.current
        DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
        Scaffold(topBar = { TopAppBar(title = { Text("Playground") }, navigationIcon = { ToolBackButton(onExit) }) }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!allowed) {
                    Text("The camera watches your hands or face and turns them into music and drawings. Everything stays on the phone; frames are looked at and dropped.", style = MaterialTheme.typography.bodyLarge)
                    Button({ ask.launch(Manifest.permission.CAMERA) }) { Text("Allow the camera") }
                    return@Column
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(4.dp)) {
                    listOf("🎹 Piano", "✏️ Draw", "😎 Face", "📞 Call").forEachIndexed { i, label ->
                        Text(label, Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(if (mode == i) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable { mode = i; hand.value = null; face.value = null }.padding(vertical = 10.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontWeight = if (mode == i) FontWeight.SemiBold else null)
                    }
                }
                when (mode) { 0 -> Piano(); 1 -> Draw(); 2 -> FaceFun(); else -> FaceCall() }
            }
        }
    }

    @Composable
    private fun ColumnScope.Piano() {
        val pose by hand.collectAsState()
        var front by remember { mutableStateOf(false) }
        val piano = remember { DeskPiano() }
        val synth = remember { Synth(DeskPiano.NOTES) }
        DisposableEffect(Unit) { onDispose { synth.release() } }
        val lit = remember { mutableStateListOf<Pair<Int, Long>>() }
        var line by remember { mutableStateOf<Float?>(null) }
        fun x(p: Pair<Float, Float>) = if (front) 1 - p.first else p.first
        LaunchedEffect(pose) {
            val h = pose ?: run { piano.feed(List(5) { null }); return@LaunchedEffect }
            piano.feed(DeskPiano.TIPS.map { x(h.points[it]) to h.points[it].second }).forEach { k -> synth.play(k); lit += k to System.currentTimeMillis() }
            lit.removeAll { System.currentTimeMillis() - it.second > 250 }
        }
        Text(if (line == null) "Point the back camera down at your desk, rest your fingertips on it, then tap Set the desk." else "Lift a finger and bring it down onto a key. Each fingertip plays on its own.",
            style = MaterialTheme.typography.bodyMedium)
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(24.dp)).background(Color.Black)) {
            androidx.compose.runtime.key(front) { GestureCamera(front, hand) }
            Canvas(Modifier.fillMaxSize()) {
                val l = line
                if (l != null) {
                    val left = 0.06f * size.width; val w = 0.88f * size.width / piano.keys
                    val top = l * size.height; val h = 0.16f * size.height
                    val now = System.currentTimeMillis()
                    for (k in 0 until piano.keys) {
                        val on = lit.any { it.first == k && now - it.second < 250 }
                        drawRect(if (on) Color(0xFF7FD3FF) else Color.White.copy(alpha = 0.55f), Offset(left + k * w + 2, top), Size(w - 4, h))
                    }
                    drawLine(Color(0xFFFFC857), Offset(0f, top), Offset(size.width, top), 4f)
                }
                pose?.let { h -> DeskPiano.TIPS.forEach { t -> drawCircle(Color(0xFFFF8A5C), 10.dp.toPx(), Offset(x(h.points[t]) * size.width, h.points[t].second * size.height)) } }
            }
            if (line != null) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)) {
                DeskPiano.NAMES.forEach { Text(it, Modifier.weight(1f), color = Color.Black, textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 12.sp) }
            }
            IconButton({ front = !front }, Modifier.align(Alignment.TopEnd).padding(6.dp)) { Icon(Icons.Rounded.Cameraswitch, "Switch camera", tint = Color.White) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ pose?.let { h -> piano.setDesk(DeskPiano.TIPS.map { x(h.points[it]) to h.points[it].second }); line = piano.line } }, enabled = pose != null) {
                Text(if (line == null) "Set the desk" else "Set it again")
            }
            if (pose == null) Text("Show the camera your hand", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun ColumnScope.Draw() {
        val pose by hand.collectAsState()
        val canvas = remember { AirCanvas() }
        var tick by remember { mutableIntStateOf(0) }
        val colors = listOf(0xFF7FD3FF, 0xFFFF8A5C, 0xFF3DDC97, 0xFFFFC857, 0xFFE25CFF, 0xFFFFFFFF)
        var color by remember { mutableStateOf(colors[0]) }
        LaunchedEffect(pose) {
            val h = pose ?: run { canvas.lost(); return@LaunchedEffect }
            val p = h.points
            val gap = hypot(p[4].first - p[8].first, p[4].second - p[8].second) / hypot(p[0].first - p[9].first, p[0].second - p[9].second).coerceAtLeast(1e-3f)
            canvas.feed(1 - p[8].first, p[8].second, gap, color); tick++
        }
        Text("Pinch thumb and index together to put the pen down, move to draw, open to lift it.", style = MaterialTheme.typography.bodyMedium)
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(24.dp)).background(Color.Black)) {
            GestureCamera(true, hand)
            Canvas(Modifier.fillMaxSize()) {
                tick.let { }
                canvas.strokes.forEach { s ->
                    if (s.points.size < 2) return@forEach
                    val path = Path().apply { s.points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * size.width, y * size.height) else lineTo(x * size.width, y * size.height) } }
                    drawPath(path, Color(s.color).copy(alpha = 0.35f), style = Stroke(22f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(path, Color(s.color), style = Stroke(8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                pose?.let { h -> drawCircle(if (canvas.drawing) Color(color) else Color.White, 12.dp.toPx(), Offset((1 - h.points[8].first) * size.width, h.points[8].second * size.height), style = if (canvas.drawing) androidx.compose.ui.graphics.drawscope.Fill else Stroke(4f)) }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            colors.forEach { c -> Box(Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).background(Color(c)).clickable { color = c }.then(if (c == color) Modifier.padding(4.dp) else Modifier)) }
            OutlinedButton({ canvas.undo(); tick++ }) { Text("Undo") }
            FilledTonalButton({ canvas.clear(); tick++ }) { Text("Clear") }
        }
    }

    /**
     * Calling with your face alone: eyebrows up or down (or looking up or down) moves through your contacts, holding
     * your mouth open picks one, and a three-second countdown follows that closing your eyes cancels.
     */
    @Composable
    private fun ColumnScope.FaceCall() {
        val context = LocalContext.current
        val f by face.collectAsState()
        var contactsOk by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
        val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { contactsOk = it[Manifest.permission.READ_CONTACTS] == true }
        if (!contactsOk) {
            Text("Hands-free calling: move through your contacts with your eyebrows or eyes, and open your mouth to call. It needs your contacts, and to place the call without a tap, permission to make calls.", style = MaterialTheme.typography.bodyLarge)
            Button({ askContacts.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)) }) { Text("Allow contacts and calls") }
            return
        }
        val people = remember { loadCallees(context) }
        val nav = remember { app.ultimate.air.FaceNavigator() }
        var at by remember { mutableIntStateOf(0) }
        var calling by remember { mutableStateOf<Pair<Callee, Long>?>(null) }
        var hold by remember { mutableStateOf<Pair<String, Float>?>(null) }
        val list = androidx.compose.foundation.lazy.rememberLazyListState()
        LaunchedEffect(f) {
            val face = f ?: return@LaunchedEffect
            val move = nav.feed(face.scores, face.at); hold = nav.holding
            val c = calling
            if (c != null) { if (move == app.ultimate.air.FaceNavigator.Move.Back || move == app.ultimate.air.FaceNavigator.Move.Up) calling = null; return@LaunchedEffect }
            when (move) {
                app.ultimate.air.FaceNavigator.Move.Up -> at = (at - 1).coerceAtLeast(0)
                app.ultimate.air.FaceNavigator.Move.Down -> at = (at + 1).coerceAtMost((people.size - 1).coerceAtLeast(0))
                app.ultimate.air.FaceNavigator.Move.Select -> people.getOrNull(at)?.let { calling = it to System.currentTimeMillis() }
                else -> Unit
            }
            list.animateScrollToItem((at - 2).coerceAtLeast(0))
        }
        LaunchedEffect(calling) {
            val (who, _) = calling ?: return@LaunchedEffect
            kotlinx.coroutines.delay(3_000)
            if (calling?.first != who) return@LaunchedEffect
            calling = null
            val direct = context.checkSelfPermission(Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED
            runCatching { context.startActivity(android.content.Intent(if (direct) android.content.Intent.ACTION_CALL else android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + android.net.Uri.encode(who.number))).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(width = 96.dp, height = 128.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black)) { FaceCamera(true, face) }
            Column(Modifier.weight(1f)) {
                Text("🤨 Eyebrows up or look up: up", style = MaterialTheme.typography.bodySmall)
                Text("😠 Eyebrows down or look down: down", style = MaterialTheme.typography.bodySmall)
                Text("😮 Hold your mouth open: call", style = MaterialTheme.typography.bodySmall)
                Text("😌 Close your eyes a moment: cancel", style = MaterialTheme.typography.bodySmall)
                hold?.let { (what, p) -> if (what == "select" || what == "back") androidx.compose.material3.LinearProgressIndicator({ p }, Modifier.fillMaxWidth().padding(top = 4.dp)) }
                if (f == null) Text("Look at the camera", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
            }
        }
        calling?.let { (who, since) ->
            var left by remember(since) { mutableIntStateOf(3) }
            LaunchedEffect(since) { while (left > 0) { kotlinx.coroutines.delay(1_000); left-- } }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Calling ${who.name} in $left…", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Close your eyes or raise your eyebrows to cancel", style = MaterialTheme.typography.bodySmall)
                OutlinedButton({ calling = null }) { Text("Cancel") }
            }
        }
        if (people.isEmpty()) Text("No contacts with phone numbers yet.", style = MaterialTheme.typography.bodyMedium)
        androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(people.size) { i ->
                val p = people[i]
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (i == at) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow)
                    .clickable { at = i; calling = p to System.currentTimeMillis() }.padding(horizontal = 16.dp, vertical = if (i == at) 18.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text((if (p.starred) "⭐ " else "") + p.name, Modifier.weight(1f), color = if (i == at) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        style = if (i == at) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge, fontWeight = if (i == at) FontWeight.Bold else null)
                }
            }
        }
    }

    @Composable
    private fun ColumnScope.FaceFun() {
        val f by face.collectAsState()
        val stickers = listOf("👑" to (0f to -1.05f), "🕶️" to (0f to -0.32f), "👃" to (0f to 0f), "🐶" to (0f to -1.0f), "😺" to (0f to -1.0f), "🦋" to (0.45f to -0.55f), "⭐" to (-0.45f to -0.6f), "🎉" to (0.5f to 0.4f))
        var sticker by remember { mutableStateOf(0) }
        // Finger-paint by touching the screen; each point is kept relative to your face, so it moves with you.
        val strokes = remember { mutableStateListOf<MutableList<Pair<Float, Float>>>() }
        var tick by remember { mutableIntStateOf(0) }
        Text("Pick a sticker, or paint on your face with your finger on the screen. It stays on as you move.", style = MaterialTheme.typography.bodyMedium)
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(24.dp)).background(Color.Black)) {
            FaceCamera(true, face)
            Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
                detectDragGestures(onDragStart = { o ->
                    val fr = face.value?.let { FaceFrame.of(it.points) } ?: return@detectDragGestures
                    strokes += mutableListOf(FaceFrame.toFace(fr, 1 - o.x / size.width, o.y / size.height)); tick++
                }) { change, _ ->
                    val fr = face.value?.let { FaceFrame.of(it.points) } ?: return@detectDragGestures
                    strokes.lastOrNull()?.add(FaceFrame.toFace(fr, 1 - change.position.x / size.width, change.position.y / size.height)); tick++
                }
            }) {
                tick.let { }
                val fr = f?.takeIf { System.currentTimeMillis() - it.at < 500 }?.let { FaceFrame.of(it.points) } ?: return@Canvas
                fun pic(u: Float, v: Float) = FaceFrame.toPicture(fr, u, v).let { (x, y) -> Offset((1 - x) * size.width, y * size.height) }
                strokes.forEach { s ->
                    if (s.size < 2) return@forEach
                    val path = Path().apply { s.forEachIndexed { i, (u, v) -> val o = pic(u, v); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
                    drawPath(path, Color(0xFFE25CFF), style = Stroke(fr.scale * size.width * 0.04f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                val (emoji, at) = stickers[sticker]
                val o = pic(at.first, at.second)
                val px = fr.scale * size.width * (if (emoji == "🕶️") 0.9f else 0.6f)
                drawContext.canvas.nativeCanvas.drawText(emoji, o.x, o.y + px / 3, android.graphics.Paint().apply { textSize = px; textAlign = android.graphics.Paint.Align.CENTER })
            }
            if (f == null) Text("Look at the camera", Modifier.align(Alignment.BottomCenter).padding(12.dp), color = Color.White)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            stickers.forEachIndexed { i, (e, _) ->
                Text(e, Modifier.clip(RoundedCornerShape(14.dp)).background(if (i == sticker) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { sticker = i }.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 22.sp)
            }
            OutlinedButton({ strokes.clear(); tick++ }) { Text("Wipe face") }
        }
    }
}

/** A person to call: name and number, starred ones first. */
private data class Callee(val name: String, val number: String, val starred: Boolean)

private fun loadCallees(c: android.content.Context): List<Callee> = runCatching {
    c.contentResolver.query(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER, android.provider.ContactsContract.CommonDataKinds.Phone.STARRED),
        null, null, null)?.use { cur ->
        val out = ArrayList<Callee>()
        while (cur.moveToNext()) out += Callee(cur.getString(0) ?: continue, cur.getString(1) ?: continue, cur.getInt(2) == 1)
        out.distinctBy { it.name }.sortedWith(compareByDescending<Callee> { it.starred }.thenBy { it.name.lowercase() })
    }.orEmpty()
}.getOrDefault(emptyList())

/**
 * A little piano voice, made on the phone: each note is a few harmonics with a quick attack and a soft decay,
 * rendered once and kept ready, so a key plays the instant it's struck.
 */
private class Synth(freqs: List<Double>) {
    private val rate = 44_100
    private val tracks = freqs.map { f ->
        val n = (rate * 1.2).toInt()
        val pcm = ShortArray(n) { i ->
            val t = i.toDouble() / rate
            val env = minOf(1.0, t / 0.005) * exp(-3.2 * t)
            val v = sin(2 * PI * f * t) + 0.5 * sin(4 * PI * f * t) * exp(-2.0 * t) + 0.22 * sin(6 * PI * f * t) * exp(-4.0 * t) + 0.08 * sin(8 * PI * f * t) * exp(-6.0 * t)
            (v / 1.8 * env * Short.MAX_VALUE * 0.8).toInt().toShort()
        }
        runCatching {
            AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(n * 2).build().also { it.write(pcm, 0, n) }
        }.getOrNull()
    }

    fun play(i: Int) {
        val t = tracks.getOrNull(i) ?: return
        runCatching { if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop(); t.reloadStaticData(); t.play() }
    }
    fun release() = tracks.forEach { runCatching { it?.release() } }
}
