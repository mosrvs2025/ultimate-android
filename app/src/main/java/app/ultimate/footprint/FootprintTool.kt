package app.ultimate.footprint

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import app.ultimate.core.appear
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.ultimate.UltimateApp
import app.ultimate.core.AppIcon
import app.ultimate.core.LibraryItem
import app.ultimate.core.ShellActions
import app.ultimate.core.Tool
import app.ultimate.core.ToolBackButton
import app.ultimate.core.ToolLaunch
import app.ultimate.core.formatBytes
import app.ultimate.core.pressScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

/** Shows what this phone already knows about its owner, read from the phone itself. Nothing leaves it. */
class FootprintTool(private val app: UltimateApp) : Tool {
    override val id = "footprint"
    override val title = "Footprint"
    override val tagline = "What your phone knows about you"
    override val icon = Icons.Rounded.Fingerprint
    override val acceptedMimePrefixes = emptyList<String>()
    override val headline: StateFlow<String> = MutableStateFlow("What it knows about you").asStateFlow()
    override val library: StateFlow<List<LibraryItem>> = MutableStateFlow<List<LibraryItem>>(emptyList()).asStateFlow()
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = true
    override suspend fun refresh() = Unit
    @Composable override fun Screen(onExit: () -> Unit, launch: ToolLaunch) = FootprintScreen(app, onExit)
}

/** Everything the screen shows. Tests hand a sample one to the screen; the app builds it from the phone. */
internal class Loaded(val usage: UsageSummary?, val apps: List<AppAccess>, val net: List<NetUse>?, val facts: List<Fact>)

internal fun hm(ms: Long): String { val m = ms / 60_000; return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FootprintScreen(app: UltimateApp, onExit: () -> Unit, preset: Loaded? = null, part: FootprintPart = FootprintPart.All) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    var data by remember { mutableStateOf(preset) }
    LaunchedEffect(tick) {
        if (preset == null) data = withContext(Dispatchers.IO) { Loaded(FootprintReaders.usage(context), FootprintReaders.appAccess(context), FootprintReaders.network(context), FootprintReaders.facts(context)) }
    }
    var showAccess by remember { mutableStateOf<Access?>(null) }
    var allFacts by remember { mutableStateOf(false) }
    val seen = remember { mutableSetOf<Any>() }
    var slice by remember { mutableIntStateOf(-1) }
    val d = data
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var appSheet by remember { mutableStateOf<String?>(null) }
    var gaugeSheet by remember { mutableStateOf(false) }
    val marks = remember { mutableMapOf<String, Int>() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val nav = remember { listOf("Your day, as a clock" to "Day", "What you open most" to "Apps", "Data your apps sent and received this week" to "Data", "Your steps" to "Steps", "Your body, from your health apps" to "Body", "What you watch and listen to" to "Media", "Where you have been" to "Places", "Who can see what" to "Access", "Things any app can read without asking" to "Facts") }
    fun jump(title: String) { marks[title]?.let { i -> scope.launch { listState.animateScrollToItem(i) } } }

    FootHost(part == FootprintPart.All, onExit) { pad ->
        Column(Modifier.padding(pad)) {
        if (d != null) {
            val shown = nav.filter { (t, _) -> (if (t in PRIVACY_SECTIONS) part.privacy else part.patterns) && when (t) { "Your day, as a clock" -> d.usage != null; "What you open most" -> d.usage?.opens?.isNotEmpty() == true; "Data your apps sent and received this week" -> d.net?.isNotEmpty() == true; else -> true } }
            val sel by remember(shown) { androidx.compose.runtime.derivedStateOf { val at = listState.firstVisibleItemIndex; shown.indexOfLast { (t, _) -> (marks[t] ?: Int.MAX_VALUE) <= at + 1 }.coerceAtLeast(0) } }
            SectionNav(shown.map { it.second }, sel) { jump(shown[it].first) }
        }
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            var n = 0
            fun row(key: Any? = null, content: @Composable androidx.compose.foundation.lazy.LazyItemScope.() -> Unit) { item(key = key, content = content); n++ }
            fun <T> rows(list: List<T>, key: (T) -> Any, content: @Composable androidx.compose.foundation.lazy.LazyItemScope.(T) -> Unit) { items(list, key = key, itemContent = content); n += list.size }
            fun sec(title: String) { marks[title] = n; row { Section(title) } }
            if (part == FootprintPart.All) row { Box(Modifier.graphicsLayer { val o = if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset.toFloat() else 600f; translationY = o * 0.45f; alpha = (1f - o / 500f).coerceIn(0f, 1f) }) { LiveHero("What your phone knows about you", "Everything on this page was read from this phone, just now, and stays on it. It is what any app you let in could learn too.") } }
            if (d == null) { items(4) { Shimmer(Modifier.fillMaxWidth().height(if (it == 0) 120.dp else 80.dp)) }; return@LazyColumn }

            if (part.patterns) row {
                val locationApps = d.apps.count { Access.Location in it.access || Access.BackgroundLocation in it.access }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    d.usage?.let { u ->
                        item { Stat("Unlocks a day", u.unlocks / u.days.toFloat(), { jump("Your day, as a clock") }) { Math.round(it).toString() } }
                        item { Stat("Screen time a day", u.screenMs / u.days.toFloat(), { jump("What you open most") }) { hm(it.toLong()) } }
                    }
                    item { Stat("Apps that know where you are", locationApps.toFloat(), { jump("Who can see what") }) { Math.round(it).toString() } }
                    item { Stat("Facts any app can read", d.facts.size.toFloat(), { jump("Things any app can read without asking") }) { Math.round(it).toString() } }
                }
            }

            if (part.privacy) row {
                val score = ExposureMath.score(d.apps); val big = ExposureMath.biggest(d.apps)
                Box(Modifier.appear("gauge", seen, 1).pressScale(0.98f).clip(RoundedCornerShape(28.dp)).clickable { gaugeSheet = true }) { ExposureGauge(score, ExposureMath.word(score), big?.let { (a, n) -> "$n of your apps can see ${a.label.lowercase()}. That counts most. Tap to see which apps." } ?: "No app you installed holds a sensitive permission.") }
            }

            if (!part.patterns) Unit else if (d.usage == null) row {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.tertiaryContainer).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Allow usage access to see your week", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Text("Android keeps a log of every app you open and every time you unlock. Any app can read it once you allow this, so this is a fair look at what that means. It is read here, on the phone, and goes nowhere.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Button({ context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Allow usage access") }
                }
            } else {
                val u = d.usage
                sec("Your day, as a clock")
                row { Box(Modifier.appear("clock", seen, 1)) { DayClock(u.heat) } }
                sec("Your week, hour by hour")
                row { HeatMap(u.heat) }
                val label = { pkg: String -> FootprintReaders.label(context, pkg) }
                val said = Habits.sentences(u, label)
                if (said.isNotEmpty()) row { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { said.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium) } } }
                val most = u.opens.entries.sortedByDescending { it.value }.take(6)
                if (most.isNotEmpty()) {
                    sec("What you open most")
                    rows(most, key = { "o-" + it.key }) { (pkg, n) -> AppBar(pkg, label(pkg), n.toFloat() / most.first().value, "$n opens · ${hm(u.foregroundMs[pkg] ?: 0)}") { appSheet = pkg } }
                }
            }

            if (part.privacy) d.net?.takeIf { it.isNotEmpty() }?.let { net ->
                sec("Data your apps sent and received this week")
                val top = net.take(6)
                rows(top, key = { "n-" + it.pkg }) { n -> AppBar(n.pkg, FootprintReaders.label(context, n.pkg), n.total.toFloat() / top.first().total, "${formatBytes(n.total)} · sent ${formatBytes(n.sent)}") { appSheet = n.pkg } }
            }

            if (part.patterns) {
                sec("Your steps")
                row { StepsCard() }

                sec("Your body, from your health apps")
                row { HealthCard() }

                sec("What you watch and listen to")
                row { MediaCard() }

                sec("Where you have been")
                row { PlacesCard() }
            }
            if (!part.privacy) return@LazyColumn

            sec("Who can see what")
            val counts = Access.values().map { a -> a to d.apps.count { a in it.access } }.filter { it.second > 0 }.sortedWith(compareByDescending<Pair<Access, Int>> { it.first.sensitive }.thenBy { it.first.ordinal })
            if (counts.isNotEmpty()) row {
                val palette = listOf(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.inversePrimary, MaterialTheme.colorScheme.outline)
                Box(Modifier.appear("donut", seen, 2)) { AccessDonut(counts.map { it.first.label to it.second }, palette, slice) { slice = it } }
            }
            rows(counts, key = { "a-" + it.first.name }) { (a, n) ->
                Row(Modifier.fillMaxWidth().pressScale(0.98f).clip(RoundedCornerShape(16.dp)).clickable { showAccess = a }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(a.label, fontWeight = FontWeight.Medium); Text(a.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text("$n apps ›", style = MaterialTheme.typography.labelLarge, color = if (a.sensitive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            }

            sec("Things any app can read without asking")
            rows(if (allFacts) d.facts else d.facts.take(6), key = { "f-" + it.name }) { f -> Fact(f) }
            if (d.facts.size > 6 && !allFacts) row { TextButton({ allFacts = true }) { Text("Show all ${d.facts.size}") } }

            row {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("What this page can't see", fontWeight = FontWeight.SemiBold)
                    Text("Google also keeps things in your Google account: places you have been, what you searched, what you watched. This app can't read that. You can see and delete it at myactivity.google.com.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://myactivity.google.com")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Open my Google activity") }
                }
            }
        }
        }
    }

    appSheet?.let { pkg -> d?.let { AppSheet(pkg, it) { appSheet = null } } }
    if (gaugeSheet) d?.let { GaugeSheet(it, { pkg -> gaugeSheet = false; appSheet = pkg }) { gaugeSheet = false } }
    showAccess?.let { a ->
        ModalBottomSheet(onDismissRequest = { showAccess = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(a.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Apps that can use it right now. Tap to change in Android's settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.height(380.dp)) {
                    items(d?.apps.orEmpty().filter { a in it.access }, key = { it.pkg }) { ap ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ap.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(ap.pkg, size = 36.dp); Spacer(Modifier.width(12.dp)); Text(ap.label + if (ap.system) "  (built in)" else "", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun Section(title: String) = Text(title, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

/** A big number that counts up once when it appears. */
@Composable
private fun Stat(label: String, value: Float, onClick: () -> Unit, format: (Float) -> String) {
    val shown = remember(value) { Animatable(0f) }
    LaunchedEffect(value) { shown.animateTo(value, tween(900, easing = FastOutSlowInEasing)) }
    Column(Modifier.width(140.dp).pressScale(0.96f).clip(RoundedCornerShape(22.dp)).clickable(onClick = onClick).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(format(shown.value), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun AppBar(pkg: String, label: String, fraction: Float, detail: String, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.pressScale(0.98f).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick) else Modifier), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(pkg, size = 36.dp); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.padding(vertical = 3.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                val grown by animateFloatAsState(fraction.coerceIn(0.03f, 1f), tween(900, easing = FastOutSlowInEasing), label = "bar")
                Box(Modifier.fillMaxWidth(grown).height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.primary))
            }
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Fact(f: Fact) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().pressScale(0.98f).clip(RoundedCornerShape(14.dp)).clickable { open = !open }.padding(vertical = 6.dp).animateContentSize()) {
        Text(f.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(f.value, style = MaterialTheme.typography.bodyMedium, maxLines = if (open) 6 else 1, overflow = TextOverflow.Ellipsis)
        if (open) Text(f.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Seven rows (oldest on top) by 24 hours; a darker cell means more time in apps that hour. The columns appear left to right once. */
@Composable
private fun HeatMap(heat: Array<LongArray>) {
    var picked by remember(heat) { mutableStateOf<Pair<Int, Int>?>(null) }
    val measurer = rememberTextMeasurer()
    val color = MaterialTheme.colorScheme.primary
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val reveal = remember(heat) { Animatable(0f) }
    LaunchedEffect(heat) { reveal.animateTo(1f, tween(800, easing = LinearEasing)) }
    val names = remember { val c = Calendar.getInstance(); List(heat.size) { ago -> (c.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -ago) }.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.getDefault()).orEmpty() } }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val hapt = androidx.compose.ui.platform.LocalHapticFeedback.current
    val ring = MaterialTheme.colorScheme.tertiary
    Column {
    Canvas(Modifier.fillMaxWidth().height((heat.size * 22 + 24).dp).pointerInput(heat) {
        detectTapGestures { o ->
            val left = with(density) { 38.dp.toPx() }; val ch = with(density) { 22.dp.toPx() }; val cw = (size.width - left) / 24f
            val row = (o.y / ch).toInt(); val h = ((o.x - left) / cw).toInt()
            picked = if (row in heat.indices && h in 0..23) { hapt.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove); row to h } else null
        }
    }) {
        val left = 38.dp.toPx(); val cw = (size.width - left) / 24f; val ch = 22.dp.toPx()
        val small = TextStyle(color = label, fontSize = 10.sp)
        for (row in heat.indices) {
            val ago = heat.size - 1 - row
            drawText(measurer.measure(names[ago], small), topLeft = Offset(0f, row * ch + 4.dp.toPx()))
            for (h in 0 until 24) {
                val reach = ((reveal.value * 27 - h) / 3f).coerceIn(0f, 1f)
                val strength = Math.pow((heat[ago][h] / 3_600_000.0).coerceIn(0.0, 1.0), 0.6).toFloat()
                drawRoundRect(color.copy(alpha = (0.08f + 0.92f * strength) * reach), Offset(left + h * cw + 1.dp.toPx(), row * ch + 1.dp.toPx()), Size(cw - 2.dp.toPx(), ch - 2.dp.toPx()), CornerRadius(3.dp.toPx()))
            }
        }
        listOf(0 to "12a", 6 to "6a", 12 to "12p", 18 to "6p").forEach { (h, t) -> drawText(measurer.measure(t, small), topLeft = Offset(left + h * cw, heat.size * ch + 4.dp.toPx())) }
        picked?.let { (row, h) -> drawRoundRect(ring, Offset(left + h * cw, row * ch), Size(cw, ch), CornerRadius(4.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx())) }
    }
    androidx.compose.animation.AnimatedContent(picked, label = "heatpick") { p ->
        val text = p?.let { (row, h) -> val ago = heat.size - 1 - row; val mins = heat[ago][h] / 60_000; "${names[ago]} ${Habits.clock(h * 60)}: " + if (mins == 0L) "you barely touched your phone" else "$mins min on your phone" } ?: "Tap any square to see that hour."
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
    }
}

/** Starts counting steps on request, then shows days as they build up. It only knows from the moment it is switched on. */
@Composable
private fun StepsCard() {
    val context = LocalContext.current
    val store = remember { StepStore(context) }
    var on by remember { mutableStateOf(store.enabled && StepSampler.allowed(context)) }
    var tick by remember { mutableIntStateOf(0) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) { StepSampler.start(context); on = true; tick++ } }
    var days by remember { mutableStateOf<Map<Long, Long>>(emptyMap()) }
    LaunchedEffect(on, tick) { if (on) days = withContext(Dispatchers.IO) { StepSampler.sampleNow(context); StepMath.daily(store.all()) } }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            !StepSampler.supported(context) -> Text("This phone has no step counter.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            !on -> {
                Text("Your phone counts every step you take, all day, with a sensor that needs only one permission. Switch this on and I will read it every half hour and build your days. I can only count from now on; your Google or Samsung account may hold years.", style = MaterialTheme.typography.bodyMedium)
                Button({ if (StepSampler.allowed(context)) { StepSampler.start(context); on = true; tick++ } else ask.launch(android.Manifest.permission.ACTIVITY_RECOGNITION) }) { Text("Start counting steps") }
            }
            else -> {
                val list = days.entries.sortedBy { it.key }.takeLast(14)
                Text(list.lastOrNull()?.let { "${it.value} steps today so far" } ?: "Counting… check back in a few hours.", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (list.size >= 2) AreaChart(list.map { it.value })
                TextButton({ StepSampler.stop(context, forget = true); on = false; days = emptyMap() }) { Text("Stop and forget my steps") }
            }
        }
    }
}

/** Which part of Footprint to show: all of it as its own screen, or one half inside the You hub. */
enum class FootprintPart {
    All, Patterns, Privacy;
    val patterns get() = this != Privacy
    val privacy get() = this != Patterns
}

private val PRIVACY_SECTIONS = setOf("Data your apps sent and received this week", "Who can see what", "Things any app can read without asking")

/** Footprint's own top bar when it stands alone; inside the You hub the hub has the bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FootHost(withBar: Boolean, onExit: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    if (withBar) Scaffold(topBar = { TopAppBar(title = { Text("Footprint") }, navigationIcon = { ToolBackButton(onExit) }, actions = { ShellActions() }) }) { content(it) }
    else content(PaddingValues(0.dp))
}

