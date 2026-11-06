package app.ultimate.health

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.core.LibraryItem
import app.ultimate.core.Tool
import app.ultimate.core.ToolLaunch
import app.ultimate.screentime.ScreenTime
import app.ultimate.screentime.UsageMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Health: everything your phone and watch record about your body, in one place, read from Health Connect (where
 * Samsung Health puts what the watch measures), plus live heart rate from the watch. Then the part no single app does:
 * patterns between your health and how you use your phone. Read-only, on the phone, only the kinds you allow.
 */
class HealthTool(private val context: android.content.Context) : Tool {
    override val id = "health"
    override val title = "Health"
    override val tagline = "Steps, sleep, heart and body from your watch, and how they relate to your phone use"
    override val icon = Icons.Rounded.MonitorHeart
    override val acceptedMimePrefixes = emptyList<String>()
    override val headline: StateFlow<String> = MutableStateFlow("Your health, in one place")
    override val library: StateFlow<List<LibraryItem>> = MutableStateFlow(emptyList())
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = false
    override suspend fun refresh() = Unit

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable
    override fun Screen(onExit: () -> Unit, launch: ToolLaunch) {
        BackHandler(onBack = onExit)
        val c = LocalContext.current
        val available = remember { HealthReader.available(c) }
        var granted by remember { mutableStateOf<Set<HealthKind>>(emptySet()) }
        var days by remember { mutableStateOf<List<HealthDay>>(emptyList()) }
        var phone by remember { mutableStateOf<List<PhoneDay>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var reload by remember { mutableIntStateOf(0) }
        val live by LiveHeart.bpm.collectAsStateWithLifecycle()
        val ask = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { reload++ }

        LaunchedEffect(reload) {
            loading = true
            if (available) {
                granted = HealthReader.granted(c)
                days = withContext(Dispatchers.IO) { runCatching { HealthReader.days(c, granted) }.getOrDefault(emptyList()) }
            }
            phone = withContext(Dispatchers.IO) { phoneDays(c) }
            loading = false
        }
        val today = LocalDate.now().toEpochDay()
        val byDay = days.associateBy { it.day }

        Scaffold(topBar = { TopAppBar(title = { Text("Health") }, navigationIcon = { IconButton(onExit) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }) }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                live?.takeIf { System.currentTimeMillis() - it.second < 30_000 }?.let { (bpm, _) -> LiveHeartCard(bpm) }

                if (!available) Card("Health Connect isn't available", "It's built into Android 14 and later; on older phones, install \"Health Connect\" from the Play Store. Your watch's data reaches it through Samsung Health.")
                // Setup: what's allowed, one kind at a time.
                if (available && granted.size < HealthKind.entries.size) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (granted.isEmpty()) "Connect your health data" else "Add more", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("Choose what Ultimate may read. It's read-only and stays on this phone.", style = MaterialTheme.typography.bodySmall)
                    HealthKind.entries.forEach { k ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(k.title, fontWeight = FontWeight.SemiBold); Text(k.what, style = MaterialTheme.typography.bodySmall) }
                            Switch(k in granted, { if (k !in granted) ask.launch(k.permissions) })
                        }
                    }
                    Text("Watch data missing? In Samsung Health: Settings → Health Connect → allow Samsung Health to share each type.", style = MaterialTheme.typography.bodySmall)
                }

                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                else if (granted.isNotEmpty() && days.isEmpty()) Card("Nothing recorded yet", "Health Connect has no data for these kinds in the last 60 days. If your watch records them, turn on sharing in Samsung Health (Settings → Health Connect).")

                // Today, at a glance.
                if (days.isNotEmpty()) {
                    val t = byDay[today]; val night = byDay[today]?.sleepMin ?: byDay[today - 1]?.sleepMin
                    Text("Today", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        t?.steps?.let { Tile("Steps", "%,d".format(it)) }
                        night?.let { Tile("Sleep", HealthInsights.hm(it.toInt())) }
                        (t?.restingHr ?: days.lastOrNull { it.restingHr != null }?.restingHr)?.let { Tile("Resting heart", "$it bpm") }
                        (t?.hrvMs ?: days.lastOrNull { it.hrvMs != null }?.hrvMs)?.let { Tile("HRV", "${it.roundToInt()} ms") }
                        t?.activeKcal?.let { Tile("Active", "${it.roundToInt()} kcal") }
                        t?.distanceM?.let { Tile("Distance", "%.1f km".format(it / 1000)) }
                        (t?.spo2 ?: days.lastOrNull { it.spo2 != null }?.spo2)?.let { Tile("Blood oxygen", "${it.roundToInt()}%") }
                        days.lastOrNull { it.weightKg != null }?.weightKg?.let { Tile("Weight", "%.1f kg".format(it)) }
                    }
                    HealthInsights.trends(days, today).takeIf { it.isNotEmpty() }?.let { tr ->
                        Text("This week", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        tr.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    }
                }

                // The new part: health and phone use, together.
                val insights = remember(days, phone) { HealthInsights.find(days, phone) }
                Text("Patterns", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (insights.isEmpty()) Text(
                    if (phone.isEmpty()) "Allow usage access (Screen time asks for it) to set your phone use beside your health." else "No clear patterns yet. They appear once there are at least ${HealthInsights.MIN_DAYS} days with both sides recorded and a strong enough link.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                insights.forEach { i ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(i.title, fontWeight = FontWeight.SemiBold)
                        Text(i.text, style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Strength ", style = MaterialTheme.typography.labelSmall)
                            LinearProgressIndicator({ i.strength.toFloat() }, Modifier.width(120.dp))
                        }
                    }
                }

                if (days.isNotEmpty()) {
                    Text("Last 30 days", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Bars("Steps", (today - 29..today).map { byDay[it]?.steps?.toFloat() }, MaterialTheme.colorScheme.primary)
                    Bars("Sleep (hours)", (today - 29..today).map { byDay[it]?.sleepMin?.let { m -> m / 60f } }, Color(0xFF8B7CF6))
                    Bars("Resting heart rate", (today - 29..today).map { byDay[it]?.restingHr?.toFloat() }, Color(0xFFFF6B6B))
                    val w = days.filter { it.day >= today - 29 }; val count = w.sumOf { it.workouts }
                    if (count > 0) Text("Workouts: $count in 30 days, ${HealthInsights.hm(w.sumOf { it.workoutMin }.toInt())} in all", style = MaterialTheme.typography.bodyMedium)
                }

                Card("What Android doesn't share", "ECG readings, the raw signals behind body composition, and blood pressure from Samsung's own monitor stay inside Samsung Health Monitor: they're regulated medical features that other apps can't read. Everything else your watch records can show up here once Samsung Health shares it.")
                TextButton({ reload++ }) { Text("Refresh") }
            }
        }
    }
}

@Composable
private fun LiveHeartCard(bpm: Int) {
    val beat = rememberInfiniteTransition(label = "beat")
    val scale by beat.animateFloat(1f, 1.18f, infiniteRepeatable(tween((60_000 / bpm.coerceAtLeast(30)) / 2), RepeatMode.Reverse), label = "scale")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF3A1720)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Favorite, null, Modifier.graphicsLayer { scaleX = scale; scaleY = scale }, tint = Color(0xFFFF6B6B))
        Column(Modifier.padding(start = 14.dp)) {
            Text("$bpm bpm", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.White)
            Text("Live from your watch", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
        }
    }
}

@Composable
private fun Tile(label: String, value: String) {
    Column(Modifier.width(150.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Card(title: String, text: String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(14.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold); Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    }
}

/** A day-by-day bar chart; gaps are days with nothing recorded. */
@Composable
private fun Bars(title: String, values: List<Float?>, color: Color) {
    val max = values.filterNotNull().maxOrNull()?.takeIf { it > 0 } ?: return
    val last = values.lastOrNull { it != null }
    Column {
        Row { Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge); last?.let { Text(if (it >= 100) "%,d".format(it.roundToInt()) else "%.1f".format(it), style = MaterialTheme.typography.labelLarge, color = color) } }
        Box(Modifier.fillMaxWidth().height(70.dp).padding(top = 4.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width / values.size
                values.forEachIndexed { i, v -> if (v != null) { val h = size.height * (v / max); drawRect(color, Offset(i * w + w * 0.15f, size.height - h), Size(w * 0.7f, h)) } }
            }
        }
    }
}

/** Each day's screen time and its midnight-to-5am part, from Android's usage log (needs usage access). */
private fun phoneDays(c: android.content.Context, count: Int = 60): List<PhoneDay> = runCatching {
    val st = ScreenTime(c); val now = System.currentTimeMillis()
    val today = UsageMath.startOfDay(now); val from = today - (count - 1) * 86_400_000L
    val events = st.events(from - 3_600_000L, now)
    if (events.isEmpty()) return emptyList()
    (0 until count).map { d ->
        val start = from + d * 86_400_000L
        val total = UsageMath.totals(events, start, minOf(start + 86_400_000L, now)).values.sum()
        val late = UsageMath.totals(events, start, minOf(start + 5 * 3_600_000L, now)).values.sum()
        PhoneDay(Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay(), total / 60_000, late / 60_000)
    }
}.getOrDefault(emptyList())
