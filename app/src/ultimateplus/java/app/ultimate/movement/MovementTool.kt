package app.ultimate.movement

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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
import app.ultimate.core.GlassCard
import app.ultimate.core.GooeyProgress
import app.ultimate.core.LibraryItem
import app.ultimate.core.Liquid
import app.ultimate.core.LiquidButton
import app.ultimate.core.LiquidInput
import app.ultimate.core.LiquidMetric
import app.ultimate.core.SuggestionPills
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
                LiquidButton("Start recording", {
                    val need = buildList { add(Manifest.permission.ACCESS_FINE_LOCATION); if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS) }
                    if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) { if (recorder.start()) onRecord() } else perms.launch(need.toTypedArray())
                }, Modifier.fillMaxWidth())
                live2()?.let { Text(it, color = WorldColors.red, fontSize = 12.sp) }
            }
            WatchCard()
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
            PlacesAsk()
            CalibrationSummary()
        }
        confirm?.let { e -> AlertDialog({ confirm = null }, confirmButton = { TextButton({ store.delete(e); confirm = null; bump++ }) { Text("Delete") } },
            dismissButton = { TextButton({ confirm = null }) { Text("Keep") } }, title = { Text("Delete this recording?") }, text = { Text("The raw sensor record is removed from this phone. It cannot be reanalysed afterwards.") }) }
    }

    @Composable private fun PlacesAsk() {
        var places by remember { mutableStateOf<List<app.ultimate.worldscan.Place>>(emptyList()) }
        LaunchedEffect(Unit) { places = withContext(Dispatchers.IO) { store.places() } }
        if (places.isEmpty()) return
        var chosen by remember { mutableStateOf(places.first().id) }
        var a by remember { mutableStateOf<Answer?>(null) }
        Card("Ask about a mapped place") {
            SuggestionPills(places.map { it.name }, { n -> places.firstOrNull { it.name == n }?.let { chosen = it.id; a = null } }, selected = places.firstOrNull { it.id == chosen }?.name)
            SuggestionPills(listOf("How accurate was the previous scan?", "What changed in this room?", "Can these walks be merged?", "Where is it on the map?"),
                { s -> places.firstOrNull { it.id == chosen }?.let { a = ScanQA.answer(s, it) } })
            a?.let { ans -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Liquid.bg).padding(14.dp)) { BasisChip(ans.basis); Spacer(Modifier.height(6.dp)); Text(ans.text, color = Color.White, fontSize = 15.sp, lineHeight = 22.sp) } }
        }
    }

    /** The watch link: drops that flow while the watch is telling the phone you are moving, and the switch for the Record offer. */
    @Composable private fun WatchCard() {
        val context = LocalContext.current
        val w by app.ultimate.watch.WatchActivity.now.collectAsState()
        var offers by remember { mutableStateOf(app.ultimate.watch.WatchActivity.offers(context)) }
        val fresh = w?.let { System.currentTimeMillis() - it.at < 120_000 } == true
        val moving = fresh && w?.state != "still"
        Card("Your watch", live = moving, accent = WorldColors.violet) {
            app.ultimate.core.LiquidSync(moving, "This phone", "Watch", status = when {
                moving -> "Watch says ${w!!.state} · ${w!!.spm} steps a minute"
                fresh -> "Connected · you are still"
                else -> "Turn on Share walking in Ultimate+ on the watch"
            })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Offer to record when I start walking", color = Color.White, fontSize = 14.sp)
                    Text("Your watch notices you walking or running and the phone offers to record it, with one tap. Android does not let it start GPS by itself in the background.", color = WorldColors.dim, fontSize = 12.sp, lineHeight = 17.sp)
                }
                Spacer(Modifier.width(8.dp))
                app.ultimate.core.GooeySwitch(offers, { offers = it; app.ultimate.watch.WatchActivity.setOffers(context, it) })
            }
        }
    }

    /** While recording: the watch link, flowing while the watch is sending steps and heart rate. */
    @Composable private fun WatchSyncLive(live: MovementRecorder.Live) {
        val w by app.ultimate.watch.WatchActivity.now.collectAsState()
        val hr by app.ultimate.health.LiveHeart.bpm.collectAsState()
        val fresh = w?.let { System.currentTimeMillis() - it.at < 90_000 } == true
        if (!fresh && hr == null) return
        Card("Synced with your watch", live = fresh, accent = WorldColors.violet) {
            app.ultimate.core.LiquidSync(fresh, "This phone", "Watch", status = w?.takeIf { fresh }?.let { "${it.spm} steps a minute" })
            // The heart beats at the rate the watch reads; it dims when the last reading is more than two minutes old.
            app.ultimate.core.HeartPanel(hr?.takeIf { System.currentTimeMillis() - it.second < 120_000 }?.first)
            live.report?.stepCheck?.let { Text(it.text, color = if (it.agree) WorldColors.green else WorldColors.amber, fontSize = 13.sp, lineHeight = 19.sp) }
        }
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
        val scroll = rememberScrollState()
        val density = androidx.compose.ui.platform.LocalDensity.current
        // The clock counts by itself from the moment recording started, on the exact second, whatever the analysis is doing.
        var nowS by remember { mutableStateOf(live.elapsedS) }
        LaunchedEffect(live.startedRealtime) {
            if (live.startedRealtime == 0L) return@LaunchedEffect
            while (true) {
                val ms = android.os.SystemClock.elapsedRealtime() - live.startedRealtime
                nowS = ms / 1000.0
                kotlinx.coroutines.delay(1_000 - ms % 1_000 + 5)
            }
        }
        val clock = fmtTime(if (live.startedRealtime != 0L) nowS else live.elapsedS)
        val d = r?.distance
        val bpm by app.ultimate.health.LiveHeart.bpm.collectAsState()
        val heart = bpm?.takeIf { System.currentTimeMillis() - it.second < 45_000 }?.first
        // Once the big clock has scrolled away, a slim copy stays at the top, so looking at the sensors never loses the time.
        val bigClockGone = scroll.value > with(density) { 96.dp.toPx() }
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(clock, color = Color.White, fontSize = 52.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Metric("Distance", d?.let { fmtDist(it.value) } ?: "—", d?.let { "± ${fmtDist(2 * it.sigma)}" } ?: "waiting", Modifier.weight(1f))
                    Metric("Speed", r?.speeds?.lastOrNull()?.let { "%.1f km/h".format(it.metresPerSecond * 3.6) } ?: "—", r?.gait?.lastOrNull()?.gait?.label ?: "", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Metric("Steps", r?.steps?.let { "%.0f".format(it.value) } ?: "—", r?.cadence?.let { "%.0f a minute".format(it.value) } ?: "", Modifier.weight(1f))
                    Metric("Laps", "${r?.laps?.size ?: 0}", "${r?.turns?.size ?: 0} turns", Modifier.weight(1f))
                }
                WatchSyncLive(live)
                app.ultimate.fusion.WatchBout.note(live.earlySeconds, live.earlyWatchSteps)?.let { Text(it, color = WorldColors.dim, fontSize = 12.sp, lineHeight = 16.sp) }
                Card("Where you are") {
                    LiveMap(r?.path, live.fix, live = true, modifier = Modifier.fillMaxWidth().height(if (Liquid.compact()) 300.dp else 360.dp))
                    Text("Glows by pace: teal easy, violet steady, amber fast. Pinch, drag and twist; double-tap to follow you again; 3D lifts the route by height.",
                        color = WorldColors.dim, fontSize = 11.sp, lineHeight = 15.sp)
                }
                Card("Sensors") {
                    val g = live.gps
                    Text("GPS: " + if (!g.enabled) "off or no signal" else g.accuracyM?.let { "±${"%.0f".format(it)} m, ${g.fixes} fixes" } ?: "searching…", color = Color.White, fontSize = 13.sp)
                    Text("Recorded ${"%.1f".format(live.bytes / 1_000_000.0)} MB so far" + if (live.busy) " · analysing…" else "", color = WorldColors.dim, fontSize = 12.sp)
                    r?.fusion?.contradictions?.firstOrNull()?.let { Text(it.text, color = WorldColors.amber, fontSize = 12.sp) }
                    Text("Numbers here are live and rough; the review after you stop uses everything recorded.", color = WorldColors.dim, fontSize = 12.sp)
                }
                WatchMeCard()
            }
            androidx.compose.animation.AnimatedVisibility(bigClockGone, Modifier.align(Alignment.TopCenter), enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { -it }, exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { -it }) {
                Row(Modifier.fillMaxWidth().background(Liquid.bg.copy(alpha = 0.94f)).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(clock, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(d?.let { fmtDist(it.value) } ?: "—", color = WorldColors.dim, fontSize = 15.sp)
                    r?.speeds?.lastOrNull()?.let { Text("%.1f km/h".format(it.metresPerSecond * 3.6), color = WorldColors.dim, fontSize = 15.sp) }
                    heart?.let { Text("♥ $it", color = WorldColors.amber, fontSize = 15.sp) }
                }
            }
            CameraPip(host.sendServer, Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 12.dp))
            // Stop never scrolls away: it is right here however far down the sensors you have looked.
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Liquid.bg))).padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!bigClockGone) Column { Text(clock, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold); heart?.let { Text("♥ $it", color = WorldColors.amber, fontSize = 12.sp) } }
                LiquidButton("Stop and review", { onSaved(recorder.stop()) }, Modifier.weight(1f), danger = true)
            }
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
            else -> ReviewBody(entry, r, onTeach = { truth -> teach(entry, truth).also { reload++ } })
        }
    }

    /** Teaches calibration from this recording against a distance the person entered; returns what happened, in words. */
    private fun teach(e: MovementStore.Entry?, truth: Double): String {
        if (e == null) return "This recording is gone."
        val cal = store.calibrator(); val t = store.read(e)
        val obs = MovementAnalyzer.calibrationObservations(t)
        val motion = motionOf(t); val msgs = ArrayList<String>()
        if (obs.isEmpty()) msgs += "No sensor saw enough of this recording (85% or more) to learn from it."
        obs["stride"]?.let { m -> msgs += lbl("Stride", cal.learn(Calibrator.key(Build.MODEL, motion, "stride"), m, Truth(truth, TruthKind.KnownCourse))) }
        obs["camera scale"]?.let { m -> msgs += lbl("Camera scale", cal.learn(Calibrator.key(Build.MODEL, "any", "camera scale"), m, Truth(truth, TruthKind.KnownCourse))) }
        store.saveCalibrator(cal)
        return msgs.joinToString("\n")
    }

    /** Everything below the loading spinner: the analysed recording, laid out to fit the screen it is on. */
    @Composable internal fun ReviewBody(entry: MovementStore.Entry?, r: MovementReport, onTeach: (Double) -> String) {
        val context = LocalContext.current
        var scrub by remember { mutableFloatStateOf(0f) }
        val pad = Liquid.gutter()
        Column(Modifier.fillMaxSize().background(Liquid.bg).verticalScroll(rememberScrollState()).padding(pad), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Headline(entry, r)
            PlaceCard(entry, r)
            ConditionsCard(entry, r)
            r.path?.let { p -> Card("Where you went") {
                LiveMap(p, null, live = false, modifier = Modifier.fillMaxWidth().height(if (Liquid.compact()) 280.dp else 340.dp), scrub = scrub)
                Legend()
                Text("Frame: ${p.frame}" + (p.registrationRms?.let { " · camera and GPS fit to ${"%.1f".format(it)} m" } ?: ""), color = WorldColors.dim, fontSize = 11.sp)
            } }
            Card("Timeline") { Timeline(r, scrub) { scrub = it }; ScrubReadout(r, scrub) }
            AgreementCard(r)
            FactsGrid(r)
            if (r.failures.isNotEmpty()) Card("What went wrong", accent = WorldColors.amber) { r.failures.forEach { Text("• " + it.text, color = WorldColors.amber, fontSize = 13.sp) } }
            AskCard(r)
            TeachCard(onTeach)
            Card("Facts for an AI", accent = WorldColors.violet) {
                Text("A plain list of this recording's measurements, each marked observed, derived or inferred. Copy it to ask any assistant about the recording; nothing is sent from here.", color = WorldColors.dim, fontSize = 13.sp, lineHeight = 19.sp)
                LiquidButton("Copy the facts", { (context.getSystemService(android.content.ClipboardManager::class.java)).setPrimaryClip(android.content.ClipData.newPlainText("movement facts", MotionQA.factsBlock(r))); android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show() }, Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    private fun motionOf(t: Trace): String {
        val g = runCatching { Pdr.run(t.imu).windows.groupBy { it.gait }.mapValues { (_, v) -> v.size } }.getOrDefault(emptyMap())
        return if ((g[Gait.Running] ?: 0) > (g[Gait.Walking] ?: 0)) "run" else "walk"
    }

    /** "Backyard loop · 3rd visit · pace 8% faster than usual", or, the second time you walk the same ground, an offer to name it. */
    @Composable private fun PlaceCard(entry: MovementStore.Entry?, r: MovementReport) {
        val id = entry?.id ?: return
        val ctx = LocalContext.current
        val store = remember { BlueprintStore(ctx) }
        var outcome by remember(id) { mutableStateOf<app.ultimate.fusion.Blueprints.Outcome?>(null) }
        LaunchedEffect(id) { outcome = withContext(Dispatchers.IO) { store.see(id, r) } }
        when (val o = outcome) {
            is app.ultimate.fusion.Blueprints.Outcome.Visit -> Card(o.place.name, WorldColors.violet) {
                Text("${app.ultimate.fusion.Blueprints.ordinal(o.visit)} time here." + (app.ultimate.fusion.Blueprints.paceWords(o.pace)?.let { " $it" } ?: ""), color = Color.White, fontSize = 14.sp)
            }
            is app.ultimate.fusion.Blueprints.Outcome.Offer -> {
                var name by remember { mutableStateOf("") }
                Card("You've been here before", WorldColors.violet) {
                    Text("This walk covered the same ground as an earlier one. Name the place and Ultimate will recognise it from now on and compare your pace each time. Only squares of ground and a few totals are kept, on this phone.", color = WorldColors.dim, fontSize = 13.sp, lineHeight = 18.sp)
                    app.ultimate.core.LiquidInput(name, { name = it }, "For example: Backyard loop", { if (name.isNotBlank()) outcome = store.name(o.place.id, name, id) }, sendLabel = "Name it", sendEnabled = name.isNotBlank())
                    Text("Not this time", color = WorldColors.dim, fontSize = 13.sp, modifier = Modifier.clickable { store.decline(o.place.id); outcome = app.ultimate.fusion.Blueprints.Outcome.Nothing })
                }
            }
            else -> Unit
        }
    }

    /** Set up another device's camera to watch you walk, shown as a small window on this screen (see [CameraPip]). */
    @Composable private fun WatchMeCard() {
        val ctx = LocalContext.current
        val server = host.sendServer
        val st by server.state.collectAsState()
        val shot by server.actions.screen.collectAsState()
        Card("Watch me with another camera") {
            Text("Prop up another phone, tablet or laptop where it can see you walk. Its picture appears here in a small window you can drag, so a screen recording of this page captures you and the numbers together. It is only shown, never saved.", color = WorldColors.dim, fontSize = 12.sp, lineHeight = 17.sp)
            // Your own devices, through Octopus Mesh: tap one to watch its camera from here.
            val mesh = remember { app.ultimate.mesh.MeshManager.get(ctx) }
            val ms by mesh.state.collectAsState()
            val sharing = ms.peers.filter { it.id in ms.alive && it.profile.camOn }
            Text("Your devices", color = Color.White, fontSize = 14.sp)
            if (!ms.running) Text("Octopus Mesh is off. Turn it on (the Octopus Mesh tool) and your other devices appear here, ready to tap.", color = WorldColors.dim, fontSize = 12.sp, lineHeight = 17.sp)
            else if (sharing.isEmpty()) Text("None of your devices is sharing a camera. On the device that has the camera, open Octopus Mesh and turn on \"Let my other devices see through this camera\".", color = WorldColors.dim, fontSize = 12.sp, lineHeight = 17.sp)
            sharing.forEach { p ->
                val on = p.id in ms.viewing
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(p.name, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(if (on) "Stop watching" else "Watch camera", color = if (on) WorldColors.amber else WorldColors.teal, fontSize = 14.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable { if (on) mesh.stopViewingCamera(p.id) else mesh.viewCamera(p.id) }.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
            Text("Or use any other phone, tablet or laptop with a browser:", color = WorldColors.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            if (!st.running) {
                LiquidButton("Turn on Send to computer", { androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, app.ultimate.send.SendService::class.java)) }, Modifier.fillMaxWidth())
            } else {
                val link = st.secureAddress?.let { "$it#desk" }
                Text(if (shot != null) "Showing ${shot!!.from}." else "Waiting for a camera…", color = if (shot != null) WorldColors.green else Color.White, fontSize = 13.sp)
                if (link != null && shot == null) {
                    Text("On the other device (same Wi-Fi), open:", color = WorldColors.dim, fontSize = 12.sp)
                    Text(link.removePrefix("https://"), color = Color.White, fontSize = 14.sp)
                    Text("Its browser warns once about the certificate: choose Advanced, then Proceed. Then tap Share my camera.", color = WorldColors.dim, fontSize = 12.sp, lineHeight = 17.sp)
                    Text("Share this link", color = WorldColors.teal, fontSize = 13.sp, modifier = Modifier.clickable {
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
                } else if (link == null) Text("Starting the secure address…", color = WorldColors.dim, fontSize = 12.sp)
                st.address?.let { addr ->
                    Text("Every camera on one page (open on a computer, the Quest or another phone):", color = WorldColors.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    Text("${addr.removePrefix("http://")}/cameras", color = Color.White, fontSize = 14.sp)
                    Text("Open it", color = WorldColors.teal, fontSize = 13.sp, modifier = Modifier.clickable { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("$addr/cameras")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } })
                }
            }
        }
    }

    /** The weather and air quality for the hour of the walk, added only if the owner has allowed asking for it. */
    @Composable private fun ConditionsCard(entry: MovementStore.Entry?, r: MovementReport) {
        val e = entry ?: return
        val anchor = r.path?.anchor ?: return
        val ctx = LocalContext.current
        val store = remember { ConditionsStore(ctx) }
        var allowed by remember { mutableStateOf(store.allowed) }
        var snap by remember(e.id) { mutableStateOf(store.saved(e.id)) }
        var state by remember(e.id) { mutableStateOf(if (snap != null) "done" else "idle") }
        LaunchedEffect(e.id, allowed) {
            if (allowed == true && snap == null) {
                state = "loading"
                snap = withContext(Dispatchers.IO) { store.fetch(e.id, anchor[0], anchor[1], e.startedAt) }
                state = if (snap != null) "done" else "none"
            }
        }
        val s = snap
        when {
            s != null -> Card("In the air", WorldColors.teal) { Text(app.ultimate.fusion.Conditions.words(s), color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
                Text("From Open-Meteo for roughly where and when you walked. Your phone and watch can't measure this themselves.", color = WorldColors.dim, fontSize = 11.sp) }
            allowed == null -> Card("Weather and air quality", WorldColors.teal) {
                Text("Add the temperature, humidity, wind and air quality at the time of this walk? Your phone has no sensor for most of this, so it is looked up from the free Open-Meteo service. Your location is rounded to about a kilometre and sent to them; nothing else is.", color = WorldColors.dim, fontSize = 13.sp, lineHeight = 18.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Add it", color = WorldColors.teal, fontSize = 14.sp, modifier = Modifier.clickable { store.allowed = true; allowed = true })
                    Text("No thanks", color = WorldColors.dim, fontSize = 14.sp, modifier = Modifier.clickable { store.allowed = false; allowed = false })
                }
            }
            allowed == true && state == "loading" -> Card("In the air", WorldColors.teal) { Text("Looking up the conditions…", color = WorldColors.dim, fontSize = 13.sp) }
            allowed == true && state == "none" -> Card("In the air", WorldColors.teal) { Text("No weather data was available for this walk (no connection, or it is older than two weeks).", color = WorldColors.dim, fontSize = 13.sp) }
            else -> Unit
        }
    }

    @Composable private fun Headline(e: MovementStore.Entry?, r: MovementReport) {
        val d = r.distance
        GlassCard(live = r.fusion.contradictions.isEmpty() && r.fusion.sources.size >= 2) {
            Text(e?.name ?: "", color = WorldColors.dim, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(d?.let { fmtDist(it.value) } ?: "No distance", color = Color.White, fontSize = if (Liquid.compact()) 38.sp else 46.sp, fontWeight = FontWeight.Bold)
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
                r.fusion.excluded.isNotEmpty() -> "${r.fusion.excluded.joinToString().replaceFirstChar { it.uppercase() }} disagreed with the others and was left out." to WorldColors.amber
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
            r.fusion.explain().forEach { Text(it, color = WorldColors.amber, fontSize = 13.sp, lineHeight = 19.sp) }
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
            r.stepCheck?.let { row("Watch steps", "%.0f".format(it.watch.value) + if (it.agree) " · agrees" else " · differs", Basis.Observed) }
            r.heart?.let { row("Heart rate", "${"%.0f".format(it.average.value)} avg · ${"%.0f".format(it.peak.value)} peak", Basis.Observed) }
            r.elevation?.let { row("Climbed", "${"%.0f".format(it.gain.value)} m up · ${"%.0f".format(it.loss.value)} m down", Basis.Derived) }
            r.calibration.forEach { Text(it, color = WorldColors.dim, fontSize = 11.sp) }
        }
    }

    @Composable private fun AskCard(r: MovementReport) {
        var q by remember { mutableStateOf("") }
        var a by remember { mutableStateOf<Answer?>(null) }
        var picked by remember { mutableStateOf<String?>(null) }
        Card("Ask about this recording") {
            LiquidInput(q, { q = it; picked = null }, "How far did I actually travel?", { a = MotionQA.answer(q, r) }, sendLabel = "Ask")
            SuggestionPills(listOf("How far did I actually travel?", "Which sensor disagreed?", "Where did tracking fail?", "How accurate is this?", "How many laps?", "How fast was I?"),
                { s -> picked = s; q = ""; a = MotionQA.answer(s, r) }, selected = picked)
            a?.let { ans ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Liquid.bg).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { BasisChip(ans.basis); Spacer(Modifier.width(8.dp)); Text("from the measurements, not written by a model", color = WorldColors.dim, fontSize = 11.sp) }
                    Text(ans.text, color = Color.White, fontSize = 15.sp, lineHeight = 22.sp)
                }
            }
        }
    }

    @Composable private fun TeachCard(onTeach: (Double) -> String) {
        var text by remember { mutableStateOf("") }
        var result by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        Card("Teach it from a course you know", accent = WorldColors.green) {
            Text("Walked a 400 m track lap or a measured route? Enter its true length. Each sensor is compared with it, and the system learns your stride and the camera's scale. Only distances you enter teach it; it never learns from its own guesses.", color = WorldColors.dim, fontSize = 13.sp, lineHeight = 19.sp)
            LiquidInput(text, { text = it.filter { c -> c.isDigit() || c == '.' } }, "True distance in metres", {
                val truth = text.toDoubleOrNull()
                if (truth == null) result = "Enter the length in metres." else { busy = true; scope.launch(Dispatchers.Default) { result = onTeach(truth); busy = false } }
            }, numeric = true, sendLabel = "Learn", sendEnabled = text.toDoubleOrNull() != null && !busy)
            if (busy) GooeyProgress(Modifier.fillMaxWidth())
            result?.let { Text(it, color = WorldColors.teal, fontSize = 13.sp, lineHeight = 19.sp) }
        }
    }
    private fun lbl(what: String, l: Learned) = when (l) { is Learned.Accepted -> "$what: learned from this course (run ${l.n}, it measured ${"%.1f".format((1 / l.ratio) * 100)}% of the truth)."; is Learned.Rejected -> "$what: not learned. ${l.why}" }

    // ---------------------------------------------------------------- drawing

    private fun sourceColor(n: String) = when (n) { "camera tracking" -> WorldColors.teal; "GPS" -> WorldColors.amber; "GPS + steps" -> WorldColors.green; "inertial steps" -> WorldColors.violet; else -> WorldColors.dim }
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

    @Composable private fun Card(title: String, accent: Color = WorldColors.teal, live: Boolean = false, content: @Composable ColumnScope.() -> Unit) =
        GlassCard(title = title, accent = accent, live = live, content = content)

    @Composable private fun Metric(label: String, value: String, sub: String, modifier: Modifier) = LiquidMetric(label, value, sub, modifier)

    @Composable private fun BasisChip(b: Basis) {
        val c = when (b) { Basis.Observed -> WorldColors.green; Basis.Derived -> WorldColors.teal; Basis.Inferred -> WorldColors.violet }
        Text(b.label, color = c, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.background(c.copy(alpha = 0.14f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
    }

    private fun fmtDist(m: Double) = if (m >= 1000) "%.2f km".format(m / 1000) else "%.0f m".format(m)
    private fun fmtTime(s: Double): String { val t = s.toInt(); return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60) }
}
