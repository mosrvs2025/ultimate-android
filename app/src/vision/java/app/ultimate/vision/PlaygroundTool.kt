package app.ultimate.vision

import app.ultimate.air.direction
import app.ultimate.air.BlastShot
import app.ultimate.air.ArcMenu
import app.ultimate.air.Throttle
import app.ultimate.air.Shake
import app.ultimate.air.Dwell
import app.ultimate.air.HAND_BONES
import app.ultimate.air.Outfits
import app.ultimate.air.Blasts
import app.ultimate.air.BodyGestures
import app.ultimate.air.Trails
import app.ultimate.air.Sparks
import app.ultimate.air.RepCounter
import app.ultimate.air.Joints
import app.ultimate.air.CameraFilters
import app.ultimate.air.BodyEffect
import app.ultimate.air.BodyPose
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.material3.FilterChip
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
import app.ultimate.air.FaceNavigator
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
        var mode by remember { mutableIntStateOf(if (launch.query == "call" && context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY)) 4 else 0) }
        val view = LocalView.current
        DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
        var ring by remember { mutableStateOf(false) }
        androidx.compose.foundation.layout.Box {
        Scaffold(topBar = { TopAppBar(title = { Text("Playground") }, navigationIcon = { ToolBackButton(onExit) }) }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!allowed) {
                    Text("The camera watches your hands or face and turns them into music and drawings. Everything stays on the phone; frames are looked at and dropped.", style = MaterialTheme.typography.bodyLarge)
                    Button({ ask.launch(Manifest.permission.CAMERA) }) { Text("Allow the camera") }
                    return@Column
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(4.dp)) {
                    // Calling only on devices that can make calls (not on Wi-Fi tablets).
                    val canCall = remember { context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY) }
                    (if (canCall) listOf("🎹 Piano", "✏️ Draw", "😎 Face", "🪄 AR", "📞 Call") else listOf("🎹 Piano", "✏️ Draw", "😎 Face", "🪄 AR")).forEachIndexed { i, label ->
                        Text(label, Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(if (mode == i) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable { mode = i; hand.value = null; face.value = null }.padding(vertical = 10.dp),
                            maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontWeight = if (mode == i) FontWeight.SemiBold else null)
                    }
                }
                DarkHint(ring) { ring = !ring }
                when (mode) { 0 -> Piano(); 1 -> Draw(); 2 -> FaceFun(); 3 -> BodyAr(); else -> FaceCall() }
            }
        }
        RingLight(ring)
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
        // Finger pads (each finger its own note, where it rests) or a keyboard along the desk.
        var padMode by remember { mutableStateOf(true) }
        val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
        fun x(p: Pair<Float, Float>) = if (front) 1 - p.first else p.first
        LaunchedEffect(pose) {
            val h = pose ?: run { piano.feed(List(5) { null }); return@LaunchedEffect }
            piano.feed(DeskPiano.TIPS.map { x(h.points[it]) to h.points[it].second }).forEach { k -> synth.play(k); lit += k to System.currentTimeMillis(); haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove) }
            lit.removeAll { System.currentTimeMillis() - it.second > 250 }
        }
        Text(if (line == null) "Point the camera down at your desk, rest all five fingertips on it, then tap Set the desk." else if (padMode) "Each finger has its own note right where it rests: tap a finger down to play it (thumb C, index D, middle E, ring F, little G)." else "Lift a finger and tap it down onto a key.",
            style = MaterialTheme.typography.bodyMedium)
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(24.dp)).background(Color.Black)) {
            androidx.compose.runtime.key(front) { GestureCamera(front, hand) }
            Canvas(Modifier.fillMaxSize()) {
                val l = line
                val now0 = System.currentTimeMillis()
                piano.pads?.let { pads ->
                    pads.forEachIndexed { i, (px, py) ->
                        val on = lit.any { it.first == i && now0 - it.second < 250 }
                        val c = Offset(px * size.width, py * size.height)
                        drawCircle(if (on) Color(0xFF7FD3FF) else Color.White.copy(alpha = 0.25f), 34.dp.toPx() * (if (on) 1.25f else 1f), c)
                        drawCircle(Color.White, 34.dp.toPx(), c, style = Stroke(3f))
                        drawContext.canvas.nativeCanvas.drawText(DeskPiano.NAMES[i], c.x, c.y + 10.dp.toPx(), android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = 26.sp.toPx(); textAlign = android.graphics.Paint.Align.CENTER; isFakeBoldText = true })
                    }
                }
                if (l != null && piano.pads == null) {
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
            if (line != null && piano.pads == null) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)) {
                DeskPiano.NAMES.forEach { Text(it, Modifier.weight(1f), color = Color.Black, textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 12.sp) }
            }
            IconButton({ front = !front }, Modifier.align(Alignment.TopEnd).padding(6.dp)) { Icon(Icons.Rounded.Cameraswitch, "Switch camera", tint = Color.White) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ pose?.let { h -> piano.setDesk(DeskPiano.TIPS.map { x(h.points[it]) to h.points[it].second }, padMode); line = piano.line } }, enabled = pose != null) {
                Text(if (line == null) "Set the desk" else "Set it again")
            }
            androidx.compose.material3.FilterChip(padMode, { padMode = !padMode; line = null }, { Text(if (padMode) "Finger pads" else "Keyboard") })
            if (pose == null) Text("Show the camera your hand", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun ColumnScope.Draw() {
        val pose by hand.collectAsState()
        val canvas = remember { AirCanvas() }
        // Drawn on your face: kept in face coordinates, so it moves, tilts and scales with your head.
        val onFace = remember { AirCanvas() }
        var lockToFace by remember { mutableStateOf(true) }
        val fpose by face.collectAsState()
        var tick by remember { mutableIntStateOf(0) }
        val colors = listOf(0xFF7FD3FF, 0xFFFF8A5C, 0xFF3DDC97, 0xFFFFC857, 0xFFE25CFF, 0xFFFFFFFF)
        var color by remember { mutableStateOf(colors[0]) }
        LaunchedEffect(pose) {
            val h = pose ?: run { canvas.lost(); return@LaunchedEffect }
            val p = h.points
            val gap = hypot(p[4].first - p[8].first, p[4].second - p[8].second) / hypot(p[0].first - p[9].first, p[0].second - p[9].second).coerceAtLeast(1e-3f)
            val fr = fpose?.takeIf { lockToFace && System.currentTimeMillis() - it.at < 400 }?.let { FaceFrame.of(it.points) }
            if (fr != null) { val (u, v) = FaceFrame.toFace(fr, p[8].first, p[8].second); onFace.feed(u, v, gap, color); canvas.lost() }
            else { canvas.feed(1 - p[8].first, p[8].second, gap, color); onFace.lost() }
            tick++
        }
        Text("Pinch thumb and index together to put the pen down, move to draw, open to lift it." + if (lockToFace) " Drawing sticks to your face." else "", style = MaterialTheme.typography.bodyMedium)
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(24.dp)).background(Color.Black)) {
            HolisticCamera(true, hand, face)
            Canvas(Modifier.fillMaxSize()) {
                tick.let { }
                fun neon(pts: List<Offset>, c: Long) {
                    if (pts.size < 2) return
                    val path = Path().apply { pts.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
                    drawPath(path, Color(c).copy(alpha = 0.35f), style = Stroke(22f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(path, Color(c), style = Stroke(8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                canvas.strokes.forEach { s -> neon(s.points.map { (x, y) -> Offset(x * size.width, y * size.height) }, s.color) }
                fpose?.takeIf { System.currentTimeMillis() - it.at < 500 }?.let { FaceFrame.of(it.points) }?.let { fr ->
                    onFace.strokes.forEach { s -> neon(s.points.map { (u, v) -> FaceFrame.toPicture(fr, u, v).let { (x, y) -> Offset((1 - x) * size.width, y * size.height) } }, s.color) }
                }
                pose?.let { h -> drawCircle(if (canvas.drawing) Color(color) else Color.White, 12.dp.toPx(), Offset((1 - h.points[8].first) * size.width, h.points[8].second * size.height), style = if (canvas.drawing) androidx.compose.ui.graphics.drawscope.Fill else Stroke(4f)) }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            colors.forEach { c -> Box(Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).background(Color(c)).clickable { color = c }.then(if (c == color) Modifier.padding(4.dp) else Modifier)) }
            androidx.compose.material3.FilterChip(lockToFace, { lockToFace = !lockToFace }, { Text(if (lockToFace) "📌 On my face" else "🌐 On the screen") })
            OutlinedButton({ if (lockToFace) onFace.undo() else canvas.undo(); tick++ }) { Text("Undo") }
            FilledTonalButton({ canvas.clear(); onFace.clear(); tick++ }) { Text("Clear") }
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
        val nav = remember { FaceNavigator() }
        var at by remember { mutableIntStateOf(0) }
        var calling by remember { mutableStateOf<Pair<Callee, Long>?>(null) }
        var hold by remember { mutableStateOf<Pair<String, Float>?>(null) }
        var levels by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
        val list = androidx.compose.foundation.lazy.rememberLazyListState()
        LaunchedEffect(f) {
            val face = f ?: return@LaunchedEffect
            val move = nav.feed(face.scores, face.at, FaceNavigator.pitch(face.points)); hold = nav.holding; levels = nav.levels
            val c = calling
            if (c != null) { if (move == FaceNavigator.Move.Back || move == FaceNavigator.Move.Up) calling = null; return@LaunchedEffect }
            when (move) {
                FaceNavigator.Move.Up -> at = (at - 1).coerceAtLeast(0)
                FaceNavigator.Move.Down -> at = (at + 1).coerceAtMost((people.size - 1).coerceAtLeast(0))
                FaceNavigator.Move.Select -> people.getOrNull(at)?.let { calling = it to System.currentTimeMillis() }
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
        // A live view of your face with its mesh, and a meter for each move, so you can see what the camera reads.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(width = 120.dp, height = 160.dp).clip(RoundedCornerShape(20.dp)).background(Color.Black)) {
                FaceCamera(true, face)
                f?.let { ff -> Canvas(Modifier.fillMaxSize()) { ff.points.forEachIndexed { i, p -> if (i % 3 == 0) drawCircle(Color(0xFF7FD3FF).copy(alpha = 0.7f), 1.5f, Offset((1 - p.first) * size.width, p.second * size.height)) } } }
                if (f == null) Text("Look here", Modifier.align(Alignment.Center), color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Triple("up", "🤨 Up", "eyebrows up, look up or tilt back"), Triple("down", "😠 Down", "eyebrows down, look down or nod"),
                    Triple("select", "😮 Call", "hold mouth open or tongue out"), Triple("back", "😌 Cancel", "close your eyes")).forEach { (k, name, how) ->
                    val lv = levels[k] ?: 0f
                    val holdP = hold?.takeIf { it.first == k && (k == "select" || k == "back") }?.second
                    Column {
                        Row { Text(name, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, fontWeight = if (lv >= 1f) FontWeight.Bold else null); Text(how, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        androidx.compose.material3.LinearProgressIndicator({ holdP ?: lv }, Modifier.fillMaxWidth().padding(top = 2.dp),
                            color = if (lv >= 1f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                    }
                }
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

    private val body = MutableStateFlow<BodyPose?>(null)
    private val bodyHands = MutableStateFlow<List<List<Pair<Float, Float>>>>(emptyList())

    /** AR for your whole body: looks for the picture, and effects that follow your hands, feet and limbs. */
    @Composable
    private fun ColumnScope.BodyAr() {
        val context = LocalContext.current
        if (!remember { hasPoseModel(context) }) { Text("Body tracking isn't in this build.", style = MaterialTheme.typography.bodyMedium); return }
        val pose by body.collectAsState()
        var front by remember { mutableStateOf(true) }
        var effect by remember { mutableStateOf(BodyEffect.Neon) }
        var look by remember { mutableIntStateOf(0) }
        val trails = remember { Trails() }
        val sparks = remember { Sparks() }
        val reps = remember { RepCounter() }
        val gestures = remember { BodyGestures() }
        val dwell = remember { Dwell() }
        val shake = remember { Shake() }
        val throttle = remember { Throttle() }
        // The floating palette: 0 hidden, 1 effects, 2 outfits. It hides itself a few seconds after the last time a hand was near it.
        var palette by remember { mutableIntStateOf(0) }
        var paletteAt by remember { mutableLongStateOf(0L) }
        var lite by remember { mutableStateOf(false) }
        var beam by remember { mutableStateOf<Pair<BlastShot, Long>?>(null) }
        val blasts = remember { Blasts() }
        val fingers by bodyHands.collectAsState()
        var outfit by remember { mutableIntStateOf(0) }
        // What a swipe in the air changes: the effect, or (after both hands up) the outfit. The banner says which, big enough to read from across the room.
        var swipeOutfits by remember { mutableStateOf(false) }
        var banner by remember { mutableStateOf("" to 0L) }
        var isDown by remember { mutableStateOf(false) }
        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
        var repCount by remember { mutableIntStateOf(0) }
        val lastAt = remember { HashMap<Int, Pair<Float, Float>>() }
        var frame by remember { mutableLongStateOf(0L) }
        // One clock for the effects: sparks fall and trails fade even while you stand still.
        LaunchedEffect(Unit) { var prev = 0L; while (true) { androidx.compose.runtime.withFrameMillis { t -> if (prev != 0L) { val dt = ((t - prev) / 1000f).coerceAtMost(0.1f); sparks.step(dt); blasts.step(dt) }; prev = t; frame = t } } }
        LaunchedEffect(pose, effect) {
            val p = pose ?: return@LaunchedEffect
            val now = System.currentTimeMillis()
            throttle.frame(now); lite = throttle.lite
            val few = if (lite) 2 else 1
            if (palette != 0 && now - paletteAt > 6000) { palette = 0; dwell.reset() }
            if (palette != 0) {
                val total = if (palette == 2) Outfits.all.size else BodyEffect.entries.size
                val cur = if (palette == 2) outfit else effect.ordinal
                val win = ArcMenu.window(cur, total)
                val slots = ArcMenu.slots(p, front, win.size)
                val unitN = Joints.scale(p)
                if (slots != null && unitN != null) {
                    val hands = listOf(BodyPose.L_INDEX, BodyPose.R_INDEX, BodyPose.L_WRIST, BodyPose.R_WRIST).mapNotNull { p.at(it, front) }
                    val pick = dwell.feed(hands, slots, unitN * 0.45f, now)
                    if (dwell.hover >= 0) paletteAt = now
                    if (pick >= 0) {
                        if (palette == 2) { outfit = win[pick]; banner = Outfits.all[outfit].label to now }
                        else { effect = BodyEffect.entries[win[pick]]; swipeOutfits = effect == BodyEffect.Outfit; trails.clear(); sparks.all.clear(); banner = effect.label to now; if (effect == BodyEffect.Outfit) palette = 2 }
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); dwell.reset()
                    }
                }
            }
            gestures.swipeHand?.let { trails.add(99, it.first, it.second, now) }
            if (effect == BodyEffect.Blast) gestures.chargeAt?.let { c -> sparks.implode(c.first, c.second, (2 + (gestures.charge * 6).toInt()) / few, 0.12f + 0.1f * gestures.charge) }
            gestures.feed(p, front, now).forEach { e ->
                when (e) {
                    is BodyGestures.Event.Fired -> if (effect == BodyEffect.Blast) { blasts.fire(e.shot); sparks.emit(e.shot.x, e.shot.y, (30 + (e.shot.power * 60).toInt()) / few, 0.6f + e.shot.power, 200f); shake.kick(0.4f + 0.6f * e.shot.power, now); beam = e.shot to now; haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) }
                    is BodyGestures.Event.Did -> {
                        when (e.kind) {
                            // Both hands up opens the floating palette: outfits when you're wearing one, effects otherwise. Again closes it.
                            BodyGestures.Kind.Menu -> { palette = if (palette != 0) 0 else if (effect == BodyEffect.Outfit) 2 else 1; paletteAt = now; dwell.reset(); swipeOutfits = effect == BodyEffect.Outfit
                                banner = (if (palette == 0) "Palette closed" else "Point at one and hold") to now }
                            else -> {
                                val step = if (e.kind == BodyGestures.Kind.SwipeRight) 1 else -1
                                if (swipeOutfits && effect == BodyEffect.Outfit) { outfit = (outfit + step + Outfits.all.size) % Outfits.all.size; banner = Outfits.all[outfit].label to now }
                                else { val all = BodyEffect.entries; effect = all[(effect.ordinal + step + all.size) % all.size]; swipeOutfits = false; trails.clear(); sparks.all.clear(); banner = effect.label to now }
                            }
                        }
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    }
                }
            }
            fun moved(i: Int, at: Pair<Float, Float>) = (lastAt.put(i, at)?.let { hypot(at.first - it.first, at.second - it.second) } ?: 0f)
            when (effect) {
                BodyEffect.Trails -> (BodyPose.HANDS + BodyPose.FEET).forEach { i -> p.at(i, front)?.let { trails.add(i, it.first, it.second, now) } }
                BodyEffect.Fire -> listOf(BodyPose.L_INDEX, BodyPose.R_INDEX).forEach { i -> p.at(i, front)?.let { sparks.emit(it.first, it.second, 4 / few, moved(i, it) * 8f, 18f) } }
                BodyEffect.Kicks -> listOf(BodyPose.L_TOE, BodyPose.R_TOE).forEach { i -> p.at(i, front)?.let { val m = moved(i, it); if (m > 0.012f) sparks.emit(it.first, it.second, (m * 400).toInt().coerceIn(2, 14), m * 10f, 190f) } }
                BodyEffect.Coach -> { if (reps.feed(Joints.knee(p), now)) repCount = reps.count; isDown = reps.isDown }
                else -> Unit
            }
        }
        Text(if (pose == null) "Step back so the camera sees you. Prop the phone up for your whole body, or point it at your hands or feet."
            else if (effect == BodyEffect.Blast) "Bring both hands together to charge, then throw them apart to fire."
            else "No need to touch the phone: raise a hand and sweep it sideways for the next ${if (swipeOutfits) "outfit" else "effect"} (the bar at the top shows it being read). Hold both hands above your head for the floating palette, then point at a choice and hold." + if (lite) " Running light to keep up." else "", style = MaterialTheme.typography.bodyMedium)
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(24.dp)).background(Color.Black)) {
            androidx.compose.runtime.key(front) { BodyCamera(front, CameraFilters.all[look].second, body, if (effect == BodyEffect.Neon && !lite) bodyHands else null) }
            Canvas(Modifier.fillMaxSize()) {
                @Suppress("UNUSED_VARIABLE") val tick = frame
                val nowMs = System.currentTimeMillis()
                // An impact shakes the drawn effects (the camera picture itself stays still, so you don't lose your place).
                val sh = shake.offset(nowMs); drawContext.transform.translate(sh.first * size.width, sh.second * size.height)
                val p = pose
                fun pt(i: Int) = p?.at(i, front)?.let { Offset(it.first * size.width, it.second * size.height) }
                fun emoji(s: String, at: Offset, px: Float, turn: Float = 0f) { val c = drawContext.canvas.nativeCanvas; c.save(); c.rotate(turn, at.x, at.y); c.drawText(s, at.x, at.y + px * 0.35f, android.graphics.Paint().apply { textSize = px; textAlign = android.graphics.Paint.Align.CENTER }); c.restore() }
                fun turnOf(a: Int, b: Int): Float? { val pa = pt(a); val pb = pt(b); return if (pa != null && pb != null) direction(pa.x to pa.y, pb.x to pb.y) else null }
                val unit = (p?.let { Joints.scale(it) } ?: 0.2f) * size.width
                when (effect) {
                    BodyEffect.Neon -> if (p != null) {
                        BodyPose.BONES.forEachIndexed { n, (a, b) ->
                            val pa = pt(a); val pb = pt(b)
                            if (pa != null && pb != null) {
                                val c = Color.hsv((n * 37f + (frame / 12f)) % 360f, 0.85f, 1f)
                                drawLine(c.copy(alpha = 0.25f), pa, pb, strokeWidth = unit * 0.16f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                drawLine(c, pa, pb, strokeWidth = unit * 0.05f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                            }
                        }
                        pt(BodyPose.NOSE)?.let { drawCircle(Color.White, unit * 0.32f, it, style = Stroke(unit * 0.05f)) }
                        // The face from the same body points: eyes and mouth, so the head isn't an empty ring.
                        listOf(2, 5).forEach { i -> pt(i)?.let { drawCircle(Color(0xFF7FD3FF), unit * 0.035f, it) } }
                        val ml = pt(9); val mr = pt(10); if (ml != null && mr != null) drawLine(Color(0xFFFF6BD6), ml, mr, strokeWidth = unit * 0.03f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        // Every finger, when a hand is close enough to be read.
                        fingers.forEach { h ->
                            fun hp(i: Int) = Offset((if (front) 1f - h[i].first else h[i].first) * size.width, h[i].second * size.height)
                            HAND_BONES.forEach { (a, b) -> drawLine(Color(0xFF3DDC97), hp(a), hp(b), strokeWidth = unit * 0.025f, cap = androidx.compose.ui.graphics.StrokeCap.Round) }
                            listOf(4, 8, 12, 16, 20).forEach { drawCircle(Color.White, unit * 0.03f, hp(it)) }
                        }
                    }
                    BodyEffect.Trails -> trails.lines(System.currentTimeMillis()).forEach { (key, line) ->
                        val hue = if (key in BodyPose.HANDS) 285f else 170f
                        for (i in 1 until line.size) {
                            val a = line[i - 1]; val b = line[i]
                            if (hypot(a.first - b.first, a.second - b.second) < 0.25f)
                                drawLine(Color.hsv(hue, 0.7f, 1f).copy(alpha = b.third.coerceIn(0f, 1f)), Offset(a.first * size.width, a.second * size.height), Offset(b.first * size.width, b.second * size.height), strokeWidth = unit * 0.12f * b.third.coerceIn(0.1f, 1f), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        }
                    }
                    BodyEffect.Fire, BodyEffect.Kicks -> Unit
                    BodyEffect.Blast -> {
                        gestures.chargeAt?.let { c ->
                            val o = Offset((c.first) * size.width, c.second * size.height); val r = unit * (0.12f + 0.45f * gestures.charge)
                            drawCircle(Color(0xFF7FD3FF).copy(alpha = 0.35f), r * 1.6f, o); drawCircle(Color.White, r * (0.6f + 0.1f * kotlin.math.sin(frame / 40f)), o); drawCircle(Color(0xFF00E5FF), r, o, style = Stroke(unit * 0.04f))
                            // Wind-up: rings closing in faster as it charges, and the colours pulling apart at the edges.
                            val pulse = 1f - ((frame % (520 - (gestures.charge * 320).toLong()).coerceAtLeast(120)) / (520f - gestures.charge * 320f).coerceAtLeast(120f))
                            drawCircle(Color.White.copy(alpha = 0.5f * (1f - pulse)), r * (1.2f + 2.2f * pulse), o, style = Stroke(unit * 0.02f))
                            val split = unit * 0.05f * gestures.charge
                            drawCircle(Color.Red.copy(alpha = 0.35f), r * 1.15f, o + Offset(-split, 0f), style = Stroke(unit * 0.02f)); drawCircle(Color.Blue.copy(alpha = 0.35f), r * 1.15f, o + Offset(split, 0f), style = Stroke(unit * 0.02f))
                        }
                        beam?.let { (s, at) ->
                            val age = (nowMs - at) / 320f
                            if (age in 0f..1f) {
                                val from = Offset(s.x * size.width, s.y * size.height); val to = from + Offset(s.dx, s.dy) * (size.maxDimension * 1.4f)
                                val wide = unit * (0.25f + 0.6f * s.power) * (1f - age)
                                drawLine(Color.Red.copy(alpha = 0.35f * (1 - age)), from + Offset(-wide * 0.2f, 0f), to + Offset(-wide * 0.2f, 0f), strokeWidth = wide, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                drawLine(Color.Blue.copy(alpha = 0.35f * (1 - age)), from + Offset(wide * 0.2f, 0f), to + Offset(wide * 0.2f, 0f), strokeWidth = wide, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                drawLine(Color(0xFF7FD3FF).copy(alpha = 0.8f * (1 - age)), from, to, strokeWidth = wide, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                drawLine(Color.White.copy(alpha = 1 - age), from, to, strokeWidth = wide * 0.4f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                drawRect(Color.White.copy(alpha = 0.35f * (1 - age) * (1 - age)), Offset(-size.width, -size.height), androidx.compose.ui.geometry.Size(size.width * 3, size.height * 3))
                            }
                        }
                        blasts.all.forEach { b ->
                            val o = Offset(b.x * size.width, b.y * size.height); val r = unit * (0.25f + 0.5f * b.power) * (1.3f - 0.3f * b.life)
                            drawCircle(Color(0xFF00E5FF).copy(alpha = 0.3f * b.life), r * 1.8f, o); drawCircle(Color.White.copy(alpha = b.life.coerceIn(0f, 1f)), r * 0.7f, o); drawCircle(Color(0xFF7FD3FF).copy(alpha = b.life.coerceIn(0f, 1f)), r, o, style = Stroke(unit * 0.05f))
                        }
                    }
                    BodyEffect.Outfit -> if (p != null) {
                        val o = Outfits.all[outfit]
                        fun limb(a: Int, b: Int, c: Long, w: Float) { val pa = pt(a); val pb = pt(b); if (pa != null && pb != null) drawLine(Color(c), pa, pb, strokeWidth = unit * w, cap = androidx.compose.ui.graphics.StrokeCap.Round) }
                        fun poly(pts: List<Offset>, c: Long) = drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close() }, Color(c))
                        val torso = Outfits.torso(p, front)?.map { Offset(it.first * size.width, it.second * size.height) }
                        if (o.cape != null && torso != null) poly(listOf(torso[0], torso[1], Offset(torso[2].x + unit * 0.5f, torso[2].y + unit * 1.1f), Offset(torso[3].x - unit * 0.5f, torso[3].y + unit * 1.1f)), o.cape)
                        if (o.bottom != null && o.legs > 0) {
                            limb(BodyPose.L_HIP, BodyPose.L_KNEE, o.bottom, 0.42f); limb(BodyPose.R_HIP, BodyPose.R_KNEE, o.bottom, 0.42f)
                            if (o.legs == 2) { limb(BodyPose.L_KNEE, BodyPose.L_ANKLE, o.bottom, 0.34f); limb(BodyPose.R_KNEE, BodyPose.R_ANKLE, o.bottom, 0.34f) }
                            limb(BodyPose.L_HIP, BodyPose.R_HIP, o.bottom, 0.42f)
                        }
                        if (o.top != null) {
                            if (o.sleeves >= 1) { limb(BodyPose.L_SHOULDER, BodyPose.L_ELBOW, o.top, 0.32f); limb(BodyPose.R_SHOULDER, BodyPose.R_ELBOW, o.top, 0.32f) }
                            if (o.sleeves == 2) { limb(BodyPose.L_ELBOW, BodyPose.L_WRIST, o.top, 0.26f); limb(BodyPose.R_ELBOW, BodyPose.R_WRIST, o.top, 0.26f) }
                            torso?.let { poly(it, o.top) }
                            o.trim?.let { c -> torso?.let { tq -> drawLine(Color(c), Offset((tq[0].x + tq[1].x) / 2, (tq[0].y + tq[1].y) / 2), Offset((tq[2].x + tq[3].x) / 2, (tq[2].y + tq[3].y) / 2), strokeWidth = unit * 0.05f); drawLine(Color(c), tq[3], tq[2], strokeWidth = unit * 0.07f) } }
                        }
                    }
                    BodyEffect.Costume -> if (p != null) {
                        val ears = pt(BodyPose.L_EAR) to pt(BodyPose.R_EAR)
                        val head = (ears.first?.let { l -> ears.second?.let { r -> (l - r).getDistance() } } ?: (unit * 0.45f))
                        // Everything is tied to the joints: the crown and shades tilt with your head, each glove turns and sits along its forearm
                        // (and grows as that arm comes toward the camera), each shoe points where the foot points.
                        val tilt = turnOf(BodyPose.R_EAR, BodyPose.L_EAR)?.let { if (front) it + 180f else it }?.let { ((it + 540f) % 360f) - 180f }?.coerceIn(-45f, 45f) ?: 0f
                        pt(BodyPose.NOSE)?.let { n ->
                            val up = Offset(kotlin.math.sin(Math.toRadians(tilt.toDouble())).toFloat(), -kotlin.math.cos(Math.toRadians(tilt.toDouble())).toFloat())
                            emoji("👑", n + up * (head * 1.35f), head * 1.3f, tilt); emoji("🕶️", n + up * (head * 0.2f), head * 1.25f, tilt)
                        }
                        listOf(BodyPose.L_ELBOW to BodyPose.L_WRIST, BodyPose.R_ELBOW to BodyPose.R_WRIST).forEach { (el, wr) ->
                            val w = pt(wr) ?: return@forEach; val e = pt(el)
                            val fore = e?.let { (w - it).getDistance() } ?: unit
                            val along = e?.let { (w - it) / fore.coerceAtLeast(1f) } ?: Offset.Zero
                            emoji("🥊", w + along * (fore * 0.25f), (fore * 0.75f).coerceIn(unit * 0.35f, unit * 0.9f), (turnOf(el, wr) ?: -90f) + 90f)
                        }
                        listOf(BodyPose.L_TOE to BodyPose.L_HEEL, BodyPose.R_TOE to BodyPose.R_HEEL).forEach { (toe, heel) ->
                            val a = pt(toe); val b = pt(heel); if (a != null && b != null) emoji("👟", Offset((a.x + b.x) / 2, (a.y + b.y) / 2), ((a - b).getDistance() * 1.6f).coerceIn(unit * 0.3f, unit * 0.8f), (turnOf(heel, toe) ?: 0f).let { if (kotlin.math.abs(it) > 90f) it + 180f else it })
                        }
                    }
                    BodyEffect.Coach -> if (p != null) {
                        BodyPose.BONES.forEach { (a, b) -> val pa = pt(a); val pb = pt(b); if (pa != null && pb != null) drawLine(Color.White.copy(alpha = 0.8f), pa, pb, strokeWidth = 4f) }
                        val paint = android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = 15.sp.toPx(); isFakeBoldText = true; setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK) }
                        fun label(a: Int, b: Int, c: Int) { val pa = pt(a); val pb = pt(b); val pc = pt(c); if (pa != null && pb != null && pc != null) drawContext.canvas.nativeCanvas.drawText("%.0f°".format(Joints.angle(pa.x to pa.y, pb.x to pb.y, pc.x to pc.y)), pb.x + 12f, pb.y, paint) }
                        label(BodyPose.L_SHOULDER, BodyPose.L_ELBOW, BodyPose.L_WRIST); label(BodyPose.R_SHOULDER, BodyPose.R_ELBOW, BodyPose.R_WRIST)
                        label(BodyPose.L_HIP, BodyPose.L_KNEE, BodyPose.L_ANKLE); label(BodyPose.R_HIP, BodyPose.R_KNEE, BodyPose.R_ANKLE)
                    }
                }
                // The swipe being read: a ghost of the raised hand's path, and a meter that fills toward the side you're sweeping.
                trails.lines(nowMs)[99]?.let { line -> for (i in 1 until line.size) drawLine(Color.White.copy(alpha = 0.5f * line[i].third.coerceIn(0f, 1f)), Offset(line[i - 1].first * size.width, line[i - 1].second * size.height), Offset(line[i].first * size.width, line[i].second * size.height), strokeWidth = unit * 0.08f, cap = androidx.compose.ui.graphics.StrokeCap.Round) }
                if (kotlin.math.abs(gestures.swipeProgress) > 0.15f) {
                    val w = size.width * 0.5f; val mid = Offset(size.width / 2, size.height * 0.06f); val full = kotlin.math.abs(gestures.swipeProgress) >= 1f
                    drawLine(Color.White.copy(alpha = 0.3f), mid - Offset(w / 2, 0f), mid + Offset(w / 2, 0f), strokeWidth = 14f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    drawLine(if (full) Color(0xFF3DDC97) else Color.White, mid, mid + Offset(w / 2 * gestures.swipeProgress, 0f), strokeWidth = 14f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                }
                // The floating palette: an arc of choices over your shoulders that moves with you. Point at one and hold; the ring fills, then it's chosen.
                if (palette != 0 && p != null) {
                    val total = if (palette == 2) Outfits.all.size else BodyEffect.entries.size
                    val win = ArcMenu.window(if (palette == 2) outfit else effect.ordinal, total)
                    ArcMenu.slots(p, front, win.size)?.forEachIndexed { i, s ->
                        val o = Offset(s.first * size.width, s.second * size.height); val label = if (palette == 2) Outfits.all[win[i]].label else BodyEffect.entries[win[i]].label
                        val current = win[i] == (if (palette == 2) outfit else effect.ordinal); val hot = dwell.hover == i; val r = unit * (if (hot) 0.42f else 0.34f)
                        drawCircle(Color.Black.copy(alpha = 0.45f), r, o); drawCircle(if (current) Color(0xFF3DDC97) else Color.White.copy(alpha = 0.8f), r, o, style = Stroke(unit * 0.025f))
                        if (hot) drawArc(Color(0xFF7FD3FF), -90f, 360f * dwell.progress, false, o - Offset(r, r), androidx.compose.ui.geometry.Size(r * 2, r * 2), style = Stroke(unit * 0.06f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                        emoji(label.substringBefore(' '), o, r * 1.1f)
                        if (hot) drawContext.canvas.nativeCanvas.drawText(label.substringAfter(' '), o.x, o.y - r - 10f, android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = 14.sp.toPx(); textAlign = android.graphics.Paint.Align.CENTER; isFakeBoldText = true; setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK) })
                    }
                }
                sparks.all.forEach { s -> drawCircle(Color.hsv(s.hue % 360f, 0.9f, 1f).copy(alpha = s.life.coerceIn(0f, 1f)), unit * 0.035f * (0.5f + s.life), Offset(s.x * size.width, s.y * size.height)) }
            }
            if (effect == BodyEffect.Coach) Column(Modifier.align(Alignment.TopStart).padding(12.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text("$repCount squats" + if (isDown) "  ⬇ down, now up" else "", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                pose?.let { Joints.shoulderTilt(it) }?.let { Text(if (it < 4f) "Shoulders level" else "Shoulders tilted %.0f°".format(it), color = Color.White, style = MaterialTheme.typography.labelMedium) }
                Text("Reset", Modifier.clip(RoundedCornerShape(8.dp)).clickable { reps.reset(); repCount = 0 }.padding(vertical = 4.dp), color = Color(0xFF7FD3FF), style = MaterialTheme.typography.labelLarge)
            }
            if (System.currentTimeMillis() - banner.second < 1800 && frame >= 0) Text(banner.first, Modifier.align(Alignment.Center).clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 22.dp, vertical = 14.dp),
                color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            androidx.compose.material3.FilledTonalIconButton({ front = !front; trails.clear() }, Modifier.align(Alignment.TopEnd).padding(8.dp)) { Text("🔄") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BodyEffect.entries.forEach { e -> FilterChip(effect == e, { effect = e; swipeOutfits = e == BodyEffect.Outfit; if (e == BodyEffect.Outfit) { palette = 2; paletteAt = System.currentTimeMillis() }; trails.clear(); sparks.all.clear() }, { Text(e.label) }) }
        }
        if (effect == BodyEffect.Outfit) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Outfits.all.forEachIndexed { i, o -> FilterChip(outfit == i, { outfit = i }, { Text(o.label) }) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CameraFilters.all.forEachIndexed { i, (name, _) -> FilterChip(look == i, { look = i }, { Text(name) }) }
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
        // Pinch in the air to paint on your face too, not only with a finger on the screen.
        val hp by hand.collectAsState()
        val pen = remember { AirCanvas() }
        LaunchedEffect(hp) {
            val h = hp ?: run { pen.lost(); return@LaunchedEffect }
            val fr = face.value?.let { FaceFrame.of(it.points) } ?: return@LaunchedEffect
            val p = h.points
            val gap = hypot(p[4].first - p[8].first, p[4].second - p[8].second) / hypot(p[0].first - p[9].first, p[0].second - p[9].second).coerceAtLeast(1e-3f)
            val was = pen.strokes.size
            val (u, v) = FaceFrame.toFace(fr, p[8].first, p[8].second)
            pen.feed(u, v, gap, 0xFFE25CFF)
            if (pen.drawing) { if (pen.strokes.size != was) strokes += mutableListOf<Pair<Float, Float>>(); strokes.lastOrNull()?.add(u to v); tick++ }
        }
        Text("Pick a sticker. Paint on your face by pinching in the air, or with your finger on the screen. It stays on as you move.", style = MaterialTheme.typography.bodyMedium)
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(24.dp)).background(Color.Black)) {
            HolisticCamera(true, hand, face)
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
            hp?.let { h -> Canvas(Modifier.fillMaxSize()) { drawCircle(if (pen.drawing) Color(0xFFE25CFF) else Color.White, 10.dp.toPx(), Offset((1 - h.points[8].first) * size.width, h.points[8].second * size.height), style = if (pen.drawing) androidx.compose.ui.graphics.drawscope.Fill else Stroke(4f)) } }
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
