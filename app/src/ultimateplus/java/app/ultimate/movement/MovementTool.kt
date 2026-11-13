package app.ultimate.movement

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DirectionsRun
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ultimate.UltimateApp
import app.ultimate.core.LibraryItem
import app.ultimate.core.Tool
import app.ultimate.core.ToolLaunch
import app.ultimate.fusion.*
import app.ultimate.worldscan.WorldColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Movement: how far, how fast, how many laps, which way, checked by every sensor against every other. It records a walk or a run with
 * the phone (inertial sensors and GPS), reads World Scan walks (which add camera tracking) and motion recorded on another device, and
 * shows what the sensors agree on, where they disagree and why. See docs/MOVEMENT.md for how it works and what is not yet verified.
 */
class MovementTool(private val host: UltimateApp) : Tool {
    internal val store = MovementStore(host)
    internal val recorder = MovementRecorder.get(host)
    private val _headline = MutableStateFlow("Record a walk or a run")
    override val id = "movement"
    override val title = "Movement"
    override val tagline = "Distance, laps and pace from your sensors, checked against each other"
    override val icon = Icons.Rounded.DirectionsRun
    override val headline: StateFlow<String> = _headline
    override val library: StateFlow<List<LibraryItem>> = MutableStateFlow(emptyList())
    override val acceptedMimePrefixes: List<String> = emptyList()
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = true
    override suspend fun refresh() { withContext(Dispatchers.IO) { val l = store.list(); _headline.value = if (l.isEmpty()) "Record a walk or a run" else "${l.size} recording${if (l.size == 1) "" else "s"}" } }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Screen(onExit: () -> Unit, launch: ToolLaunch) {
        var mode by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("L") }
        val live by recorder.live.collectAsState()
        // A recording in progress always shows its live screen, even after the app was left and came back.
        val showing = if (live.recording) "R" else mode
        BackHandler(enabled = showing != "L") { if (!live.recording) mode = "L" }
        Scaffold(containerColor = WorldColors.bg, topBar = {
            TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = WorldColors.bg, titleContentColor = Color.White, navigationIconContentColor = Color.White),
                title = { Text(when { showing == "R" -> "Recording"; showing.startsWith("V:") -> "Review"; else -> "Movement" }) },
                navigationIcon = { IconButton({ if (showing == "L" || live.recording) onExit() else mode = "L" }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
        }) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when {
                    showing == "R" -> RecordScreen(live, onSaved = { id -> mode = if (id != null) "V:$id" else "L" })
                    showing.startsWith("V:") -> ReviewScreen(showing.removePrefix("V:"))
                    else -> ListScreen(onOpen = { mode = "V:$it" }, onRecord = { mode = "R" })
                }
            }
        }
    }

    // ---------------------------------------------------------------- list

    @Composable private fun ListScreen(onOpen: (String) -> Unit, onRecord: () -> Unit) {
        val context = LocalContext.current
        var entries by remember { mutableStateOf<List<MovementStore.Entry>>(emptyList()) }
        var loaded by remember { mutableStateOf(false) }
        var confirm by remember { mutableStateOf<MovementStore.Entry?>(null) }
        var bump by remember { mutableIntStateOf(0) }
        LaunchedEffect(bump) { entries = withContext(Dispatchers.IO) { store.list() }; loaded = true; _headline.value = if (entries.isEmpty()) "Record a walk or a run" else "${entries.size} recording${if (entries.size == 1) "" else "s"}" }
        val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
            if (r[Manifest.permission.ACCESS_FINE_LOCATION] == true || context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) { if (recorder.start()) onRecord() }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card("Start") {
                Text("Walk or run with the phone in a pocket, hand or armband. It records its motion sensors and GPS, and afterwards tells you the distance, pace, laps and turns, and whether its own sensors agree.", color = WorldColors.dim, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Button({
                    val need = buildList { add(Manifest.permission.ACCESS_FINE_LOCATION); if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS) }
                    if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) { if (recorder.start()) onRecord() } else perms.launch(need.toTypedArray())
                }, Modifier.fillMaxWidth()) { Text("Start recording") }
                live2()?.let { Text(it, color = WorldColors.red, fontSize = 12.sp) }
            }
            if (!loaded) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = WorldColors.teal) }
            else if (entries.isEmpty()) Text("Nothing recorded yet. World Scan walks and motion recorded on your other devices (Octopus Mesh) show up here too.", color = WorldColors.dim, fontSize = 13.sp)
            entries.forEach { e ->
                Column(Modifier.fillMaxWidth().background(WorldColors.card, RoundedCornerShape(16.dp)).clickable { onOpen(e.id) }.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(e.name, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (e.origin != MovementStore.Origin.Walk) TextButton({ confirm = e }) { Text("Delete", color = WorldColors.dim, fontSize = 12.sp) }
                    }
                    Text("${e.origin.label} · ${fmtTime(e.durationS)}" + (if (e.note.isNotBlank()) " · ${e.note}" else ""), color = WorldColors.dim, fontSize = 12.sp)
                }
            }
            CalibrationSummary()
        }
        confirm?.let { e -> AlertDialog({ confirm = null }, confirmButton = { TextButton({ store.delete(e); confirm = null; bump++ }) { Text("Delete") } },
            dismissButton = { TextButton({ confirm = null }) { Text("Keep") } }, title = { Text("Delete this recording?") }, text = { Text("The raw sensor record is removed from this phone. It cannot be reanalysed afterwards.") }) }
    }

    @Composable private fun live2(): String? { val l by recorder.live.collectAsState(); return l.error }

    @Composable private fun CalibrationSummary() {
        var cal by remember { mutableStateOf(store.calibrator()) }
        val dev = Build.MODEL
        val lines = listOf(
            "Walking stride" to cal.correction(Calibrator.key(dev, "walk", "stride"), StrideModel.DEFAULT_REL_SIGMA),
            "Running stride" to cal.correction(Calibrator.key(dev, "run", "stride"), StrideModel.DEFAULT_REL_SIGMA),
            "Camera scale" to cal.cameraScale(dev),
        )
        Card("Calibration") {
            Text("Learns your stride and the camera's scale, only from courses whose length you tell it. It never learns from its own estimates.", color = WorldColors.dim, fontSize = 12.sp)
            lines.forEach { (n, c) -> Text("$n: ${c.note}", color = if (c.active) WorldColors.green else Color.White, fontSize = 13.sp) }
            if (cal.keys().isNotEmpty()) TextButton({ store.clearCalibration(); cal = Calibrator() }) { Text("Reset calibration", color = WorldColors.dim) }
        }
    }

    // ---------------------------------------------------------------- recording

    @Composable private fun RecordScreen(live: MovementRecorder.Live, onSaved: (String?) -> Unit) {
        val r = live.report
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(fmtTime(live.elapsedS), color = Color.White, fontSize = 52.sp, fontWeight = FontWeight.Bold)
            val d = r?.distance
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Metric("Distance", d?.let { fmtDist(it.value) } ?: "—", d?.let { "± ${fmtDist(2 * it.sigma)}" } ?: "waiting", Modifier.weight(1f))
                Metric("Speed", r?.speeds?.lastOrNull()?.let { "%.1f km/h".format(it.metresPerSecond * 3.6) } ?: "—", r?.gait?.lastOrNull()?.gait?.label ?: "", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Metric("Steps", r?.steps?.let { "%.0f".format(it.value) } ?: "—", r?.cadence?.let { "%.0f a minute".format(it.value) } ?: "", Modifier.weight(1f))
                Metric("Laps", "${r?.laps?.size ?: 0}", "${r?.turns?.size ?: 0} turns", Modifier.weight(1f))
            }
            r?.path?.let { Card("Where you have been") { PathCanvas(it, r.laps, r.turns, null, Modifier.fillMaxWidth().height(240.dp)) } }
            Card("Sensors") {
                val g = live.gps
                Text("GPS: " + if (!g.enabled) "off or no signal" else g.accuracyM?.let { "±${"%.0f".format(it)} m, ${g.fixes} fixes" } ?: "searching…", color = Color.White, fontSize = 13.sp)
                Text("Recorded ${"%.1f".format(live.bytes / 1_000_000.0)} MB so far" + if (live.busy) " · analysing…" else "", color = WorldColors.dim, fontSize = 12.sp)
                r?.fusion?.contradictions?.firstOrNull()?.let { Text(it.text, color = WorldColors.amber, fontSize = 12.sp) }
                Text("Numbers here are live and rough; the review after you stop uses everything recorded.", color = WorldColors.dim, fontSize = 12.sp)
            }
            Button({ onSaved(recorder.stop()) }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = WorldColors.red)) { Text("Stop and review") }
        }
    }

    // ---------------------------------------------------------------- review

    @Composable private fun ReviewScreen(entryId: String) {
        val context = LocalContext.current
        var entry by remember { mutableStateOf<MovementStore.Entry?>(null) }
        var report by remember { mutableStateOf<MovementReport?>(null) }
        var failed by remember { mutableStateOf<String?>(null) }
        var reload by remember { mutableIntStateOf(0) }
        LaunchedEffect(entryId, reload) {
            report = null
            withContext(Dispatchers.Default) {
                val e = store.list().firstOrNull { it.id == entryId }
                entry = e
                if (e == null) failed = "This recording is gone." else runCatching {
                    val t = store.read(e)
                    report = MovementAnalyzer.analyze(t, store.calibrator(), Build.MODEL, motionOf(t))
                }.onFailure { failed = it.message ?: "Could not read this recording." }
            }
        }
        val r = report
        when {
            failed != null -> Text(failed!!, Modifier.padding(16.dp), color = WorldColors.red)
            r == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(color = WorldColors.teal); Spacer(Modifier.height(8.dp)); Text("Reading every sensor…", color = WorldColors.dim) } }
            else -> {
                var scrub by remember { mutableFloatStateOf(0f) }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Headline(entry, r)
                    r.path?.let { p -> Card("Where you went") {
                        PathCanvas(p, r.laps, r.turns, scrub, Modifier.fillMaxWidth().height(280.dp))
                        Legend()
                        Text("Frame: ${p.frame}" + (p.registrationRms?.let { " · camera and GPS fit to ${"%.1f".format(it)} m" } ?: ""), color = WorldColors.dim, fontSize = 11.sp)
                    } }
                    Card("Timeline") { Timeline(r, scrub) { scrub = it }; ScrubReadout(r, scrub) }
                    AgreementCard(r)
                    FactsGrid(r)
                    if (r.failures.isNotEmpty()) Card("What went wrong") { r.failures.forEach { Text("• " + it.text, color = WorldColors.amber, fontSize = 13.sp) } }
                    AskCard(r)
                    TeachCard(r, entry) { reload++ }
                    Card("Facts for an AI") {
                        Text("A plain list of this recording's measurements, each marked observed, derived or inferred. Copy it to ask any assistant about the recording; nothing is sent from here.", color = WorldColors.dim, fontSize = 12.sp)
                        TextButton({ (context.getSystemService(android.content.ClipboardManager::class.java)).setPrimaryClip(android.content.ClipData.newPlainText("movement facts", MotionQA.factsBlock(r))); android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show() }) { Text("Copy the facts") }
                    }
                }
            }
        }
    }

    private fun motionOf(t: Trace): String {
        val g = runCatching { Pdr.run(t.imu).windows.groupBy { it.gait }.mapValues { (_, v) -> v.size } }.getOrDefault(emptyMap())
        return if ((g[Gait.Running] ?: 0) > (g[Gait.Walking] ?: 0)) "run" else "walk"
    }

    @Composable private fun Headline(e: MovementStore.Entry?, r: MovementReport) {
        val d = r.distance
        Column(Modifier.fillMaxWidth().background(WorldColors.card, RoundedCornerShape(20.dp)).padding(18.dp)) {
            Text(e?.name ?: "", color = WorldColors.dim, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(d?.let { fmtDist(it.value) } ?: "No distance", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                d?.let { Text("± ${fmtDist(2 * it.sigma)}", color = WorldColors.dim, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BasisChip(Basis.Derived)
                Text("${fmtTime(r.durationS)} · from ${r.sources.joinToString(", ").ifBlank { "no sensors" }}", color = WorldColors.dim, fontSize = 12.sp)
            }
            val n = r.fusion.sources.size
            val (msg, col) = when {
                n < 2 -> "Only one sensor saw this, so nothing could check it." to WorldColors.amber
                r.fusion.contradictions.isEmpty() -> "All $n sensors agree within their uncertainty." to WorldColors.green
                r.fusion.excluded.isNotEmpty() -> "${r.fusion.excluded.joinToString()} disagreed with the others and was left out." to WorldColors.amber
                else -> "The sensors disagree and nothing can say which is right; the doubt is in the ± above." to WorldColors.red
            }
            Spacer(Modifier.height(6.dp)); Text(msg, color = col, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (r.fusion.unobservedS > 1) Text("${"%.0f".format(r.fusion.unobservedS)} s were seen by no trusted sensor and are not counted: the real distance is at least this.", color = WorldColors.dim, fontSize = 12.sp)
        }
    }

    @Composable private fun AgreementCard(r: MovementReport) {
        Card("Do the sensors agree?") {
            val sources = r.fusion.sources
            if (sources.isEmpty()) { Text("No sensor saw enough movement.", color = WorldColors.dim); return@Card }
            val fused = r.distance?.value
            val top = (sources.maxOf { it.total.value + 2 * it.total.sigma }).coerceAtLeast(fused ?: 1.0).coerceAtLeast(1.0)
            sources.forEach { s ->
                Row { Text(s.name, color = if (s.trusted) Color.White else WorldColors.dim, fontSize = 13.sp, modifier = Modifier.weight(1f)); Text(fmtDist(s.total.value) + "  saw ${(s.coverage * 100).toInt()}%", color = WorldColors.dim, fontSize = 12.sp) }
                Canvas(Modifier.fillMaxWidth().height(22.dp)) {
                    val w = size.width; val h = size.height
                    val col = if (!s.trusted) WorldColors.red else sourceColor(s.name)
                    drawRect(WorldColors.line, Offset(0f, h * 0.35f), androidx.compose.ui.geometry.Size(w, h * 0.3f))
                    drawRect(col, Offset(0f, h * 0.2f), androidx.compose.ui.geometry.Size((w * (s.total.value / top)).toFloat(), h * 0.6f))
                    val lo = (w * ((s.total.value - 2 * s.total.sigma).coerceAtLeast(0.0) / top)).toFloat(); val hi = (w * ((s.total.value + 2 * s.total.sigma) / top)).toFloat()
                    drawLine(Color.White, Offset(lo, h * 0.5f), Offset(hi, h * 0.5f), 2f); drawLine(Color.White, Offset(lo, h * 0.25f), Offset(lo, h * 0.75f), 2f); drawLine(Color.White, Offset(hi, h * 0.25f), Offset(hi, h * 0.75f), 2f)
                    if (fused != null) { val x = (w * (fused / top)).toFloat(); drawLine(WorldColors.teal, Offset(x, 0f), Offset(x, h), 3f) }
                }
            }
            Text("White whiskers show each sensor's 95% band; the teal line is the fused answer.", color = WorldColors.dim, fontSize = 11.sp)
            r.fusion.contradictions.forEach { Text(it.text, color = WorldColors.amber, fontSize = 12.sp) }
        }
    }

    @Composable private fun FactsGrid(r: MovementReport) {
        Card("What was measured") {
            @Composable fun row(a: String, v: String, b: Basis) { Row(verticalAlignment = Alignment.CenterVertically) { Text(a, color = WorldColors.dim, fontSize = 13.sp, modifier = Modifier.weight(1f)); Text(v, color = Color.White, fontSize = 14.sp); Spacer(Modifier.width(8.dp)); BasisChip(b) } }
            r.averageSpeed?.let { row("Average speed", "%.1f km/h".format(it.value * 3.6), it.basis) }
            r.topSpeed?.let { row("Fastest stretch", "%.1f km/h".format(it.value * 3.6), it.basis) }
            r.steps?.let { row("Steps", "%.0f".format(it.value), it.basis) }
            r.cadence?.let { row("Cadence", "%.0f / min".format(it.value), it.basis) }
            row("Laps", if (r.laps.isEmpty()) "none" else "${r.laps.size}" + (r.lapConsistency?.let { " (±${"%.0f".format(it * 100)}%)" } ?: ""), Basis.Derived)
            row("Turns", "${r.turns.size}" + if (r.turns.any { it.uTurn }) ", ${r.turns.count { it.uTurn }} U-turn" else "", Basis.Derived)
            row("Stops", "${r.stops.size}", Basis.Derived)
            r.gait.groupBy { it.gait }.mapValues { (_, v) -> v.sumOf { it.span.length } }.entries.sortedByDescending { it.value }.take(3).forEach { row(it.key.label.replaceFirstChar { c -> c.uppercase() }, fmtTime(it.value), Basis.Inferred) }
            r.heart?.let { row("Heart rate", "${"%.0f".format(it.average.value)} avg · ${"%.0f".format(it.peak.value)} peak", Basis.Observed) }
            r.elevation?.let { row("Climbed", "${"%.0f".format(it.gain.value)} m up · ${"%.0f".format(it.loss.value)} m down", Basis.Derived) }
            r.calibration.forEach { Text(it, color = WorldColors.dim, fontSize = 11.sp) }
        }
    }

    @Composable private fun AskCard(r: MovementReport) {
        var q by remember { mutableStateOf("") }
        var a by remember { mutableStateOf<Answer?>(null) }
        Card("Ask about this recording") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(q, { q = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("How far did I actually travel?") })
                TextButton({ a = MotionQA.answer(q.ifBlank { "how far did I travel" }, r) }) { Text("Ask") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("How far did I actually travel?", "Which sensor disagreed?", "Where did tracking fail?", "How accurate is this?", "How many laps?").forEach { s ->
                    AssistChip({ q = s; a = MotionQA.answer(s, r) }, { Text(s, fontSize = 12.sp) })
                }
            }
            a?.let { ans ->
                Column(Modifier.fillMaxWidth().background(WorldColors.bg, RoundedCornerShape(12.dp)).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { BasisChip(ans.basis); Spacer(Modifier.width(8.dp)); Text("computed from the measurements, not written by a model", color = WorldColors.dim, fontSize = 11.sp) }
                    Spacer(Modifier.height(6.dp)); Text(ans.text, color = Color.White, fontSize = 14.sp)
                }
            }
        }
    }

    @Composable private fun TeachCard(r: MovementReport, e: MovementStore.Entry?, onLearned: () -> Unit) {
        var text by remember { mutableStateOf("") }
        var result by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        Card("Teach it from a course you know") {
            Text("Walked a 400 m track lap or a measured route? Enter its true length. Each sensor is compared with it, and the system learns your stride and the camera's scale. Only distances you enter teach it; it never learns from its own guesses.", color = WorldColors.dim, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(text, { text = it.filter { c -> c.isDigit() || c == '.' } }, Modifier.weight(1f), singleLine = true, label = { Text("True distance, metres") })
                TextButton({
                    val truth = text.toDoubleOrNull()
                    if (truth == null || e == null) { result = "Enter the length in metres." } else scope.launch(Dispatchers.Default) {
                        val cal = store.calibrator(); val t = store.read(e)
                        val obs = MovementAnalyzer.calibrationObservations(t)
                        val motion = motionOf(t); val msgs = ArrayList<String>()
                        if (obs.isEmpty()) msgs += "No sensor saw enough of this recording (85% or more) to learn from it."
                        obs["stride"]?.let { m -> msgs += lbl("Stride", cal.learn(Calibrator.key(Build.MODEL, motion, "stride"), m, Truth(truth, TruthKind.KnownCourse))) }
                        obs["camera scale"]?.let { m -> msgs += lbl("Camera scale", cal.learn(Calibrator.key(Build.MODEL, "any", "camera scale"), m, Truth(truth, TruthKind.KnownCourse))) }
                        store.saveCalibrator(cal); result = msgs.joinToString("\n"); onLearned()
                    }
                }) { Text("Learn") }
            }
            result?.let { Text(it, color = WorldColors.teal, fontSize = 12.sp) }
        }
    }
    private fun lbl(what: String, l: Learned) = when (l) { is Learned.Accepted -> "$what: learned from this course (run ${l.n}, it measured ${"%.1f".format((1 / l.ratio) * 100)}% of the truth)."; is Learned.Rejected -> "$what: not learned. ${l.why}" }

    // ---------------------------------------------------------------- drawing

    private fun sourceColor(n: String) = when (n) { "camera tracking" -> WorldColors.teal; "GPS" -> WorldColors.amber; "inertial steps" -> WorldColors.violet; else -> WorldColors.dim }
    private fun gaitColor(g: Gait) = when (g) { Gait.Walking -> WorldColors.green; Gait.Running -> WorldColors.amber; Gait.Still -> WorldColors.line; Gait.Other -> WorldColors.violet.copy(alpha = 0.5f) }

    @Composable private fun Legend() {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("camera tracking", "inertial steps", "GPS").forEach { n -> Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(8.dp).background(sourceColor(n), RoundedCornerShape(4.dp))); Spacer(Modifier.width(4.dp)); Text(n, color = WorldColors.dim, fontSize = 11.sp) } }
        }
    }

    @Composable private fun PathCanvas(p: FusedPath, laps: List<Lap>, turns: List<Turn>, scrub: Float?, modifier: Modifier) {
        Canvas(modifier.background(WorldColors.bg, RoundedCornerShape(14.dp))) {
            if (p.size < 2) return@Canvas
            val minE = p.e.min(); val maxE = p.e.max(); val minN = p.n.min(); val maxN = p.n.max()
            val spanE = max(maxE - minE, 20.0); val spanN = max(maxN - minN, 20.0)
            val pad = 24f
            val sc = min((size.width - 2 * pad) / spanE, (size.height - 2 * pad) / spanN).toFloat()
            val ox = (size.width - (spanE * sc).toFloat()) / 2; val oy = (size.height - (spanN * sc).toFloat()) / 2
            fun px(i: Int) = Offset(ox + ((p.e[i] - minE) * sc).toFloat(), size.height - oy - ((p.n[i] - minN) * sc).toFloat())
            for (i in 1 until p.size) drawLine(sourceColor(p.from[i]), px(i - 1), px(i), 5f, StrokeCap.Round)
            turns.forEach { t -> val i = p.t.indexOfFirst { it >= t.span.from + t.span.length / 2 }.takeIf { it >= 0 } ?: return@forEach; drawCircle(Color.White.copy(alpha = 0.7f), 4f, px(i)) }
            laps.forEach { l -> val i = p.t.indexOfFirst { it >= l.span.to }.takeIf { it >= 0 } ?: return@forEach; drawCircle(WorldColors.green, 9f, px(i), style = Stroke(3f)) }
            drawCircle(WorldColors.green, 9f, px(0)); drawCircle(WorldColors.red, 9f, px(p.size - 1))
            if (scrub != null) { val i = (scrub * (p.size - 1)).toInt().coerceIn(0, p.size - 1); drawCircle(Color.White, 12f, px(i)); drawCircle(WorldColors.teal, 7f, px(i)) }
        }
    }

    @Composable private fun Timeline(r: MovementReport, scrub: Float, onScrub: (Float) -> Unit) {
        val dur = r.durationS.coerceAtLeast(1.0)
        Canvas(Modifier.fillMaxWidth().height(150.dp).background(WorldColors.bg, RoundedCornerShape(12.dp))
            .pointerInput(Unit) { detectTapGestures { onScrub((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) { detectHorizontalDragGestures { c, _ -> onScrub((c.position.x / size.width).coerceIn(0f, 1f)) } }) {
            val w = size.width; val h = size.height
            fun x(t: Double) = (w * (t / dur)).toFloat()
            // Gait lane.
            r.gait.forEach { g -> drawRect(gaitColor(g.gait), Offset(x(g.span.from), 0f), androidx.compose.ui.geometry.Size(x(g.span.to) - x(g.span.from), 14f)) }
            // Trouble: spans where a sensor was blind.
            r.failures.filter { it.span.length < dur * 0.95 }.forEach { f -> drawRect(WorldColors.red.copy(alpha = 0.22f), Offset(x(f.span.from), 18f), androidx.compose.ui.geometry.Size(x(f.span.to) - x(f.span.from), h - 18f)) }
            r.stops.forEach { s -> drawRect(WorldColors.dim.copy(alpha = 0.18f), Offset(x(s.span.from), 18f), androidx.compose.ui.geometry.Size(x(s.span.to) - x(s.span.from), h - 18f)) }
            // Speed.
            val top = ((r.speeds.maxOfOrNull { it.metresPerSecond } ?: 1.0) * 1.15).coerceAtLeast(1.0)
            val path = Path()
            r.speeds.forEachIndexed { i, s -> val px = x(s.t); val py = (h - 8 - (h - 34) * (s.metresPerSecond / top)).toFloat(); if (i == 0) path.moveTo(px, py) else path.lineTo(px, py) }
            drawPath(path, WorldColors.teal, style = Stroke(3f, cap = StrokeCap.Round))
            r.laps.forEach { l -> drawLine(WorldColors.green, Offset(x(l.span.to), 18f), Offset(x(l.span.to), h), 2f) }
            r.turns.forEach { t -> drawCircle(Color.White.copy(alpha = 0.8f), 3f, Offset(x((t.span.from + t.span.to) / 2), h - 4f)) }
            drawLine(Color.White, Offset(w * scrub, 0f), Offset(w * scrub, h), 2f)
        }
        Text("Colour bar: walking green, running amber, still grey, other violet. Red: a sensor was blind. Green lines: laps. Drag to scrub.", color = WorldColors.dim, fontSize = 11.sp)
    }

    @Composable private fun ScrubReadout(r: MovementReport, scrub: Float) {
        val t = scrub * r.durationS
        val sp = r.speeds.minByOrNull { abs(it.t - t) }
        val gait = r.gait.firstOrNull { t in it.span }
        val drew = r.path?.let { p -> p.from.getOrNull(p.t.indexOfFirst { it >= t }.coerceAtLeast(0)) }
        val trouble = r.failures.firstOrNull { it.span.length < r.durationS * 0.95 && t in it.span }
        Text("${fmtTime(t)} · " + (sp?.let { "%.1f km/h".format(it.metresPerSecond * 3.6) } ?: "—") + (gait?.let { " · ${it.gait.label}" } ?: "") + (drew?.takeIf { it != "none" }?.let { " · path from $it" } ?: ""), color = Color.White, fontSize = 13.sp)
        trouble?.let { Text(it.text, color = WorldColors.amber, fontSize = 12.sp) }
    }

    // ---------------------------------------------------------------- small pieces

    @Composable private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
        Column(Modifier.fillMaxWidth().background(WorldColors.card, RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp); content()
        }
    }

    @Composable private fun Metric(label: String, value: String, sub: String, modifier: Modifier) {
        Column(modifier.background(WorldColors.card, RoundedCornerShape(16.dp)).padding(14.dp)) {
            Text(label, color = WorldColors.dim, fontSize = 12.sp); Text(value, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold); Text(sub, color = WorldColors.dim, fontSize = 12.sp)
        }
    }

    @Composable private fun BasisChip(b: Basis) {
        val c = when (b) { Basis.Observed -> WorldColors.green; Basis.Derived -> WorldColors.teal; Basis.Inferred -> WorldColors.violet }
        Text(b.label, color = c, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.background(c.copy(alpha = 0.14f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
    }

    private fun fmtDist(m: Double) = if (m >= 1000) "%.2f km".format(m / 1000) else "%.0f m".format(m)
    private fun fmtTime(s: Double): String { val t = s.toInt(); return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60) }
}
