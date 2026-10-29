package app.ultimate.screentime

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.ultimate.R
import app.ultimate.UltimateApp
import app.ultimate.checkup.engine.Collector
import app.ultimate.core.AppIcon
import app.ultimate.core.LibraryItem
import app.ultimate.core.ShellActions
import app.ultimate.core.Tool
import app.ultimate.core.ToolBackButton
import app.ultimate.core.ToolLaunch
import app.ultimate.core.appear
import app.ultimate.core.pressScale
import app.ultimate.recipes.Automations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Where your time goes, without lectures, with limits you set yourself. */
class ScreenTimeTool(private val app: UltimateApp) : Tool {
    val data = ScreenTime(app)
    override val id = "screentime"
    override val title = "Screen time"
    override val tagline = "Where your time goes, with gentle limits"
    override val icon = Icons.Rounded.HourglassBottom
    override val acceptedMimePrefixes = emptyList<String>()
    override val headline: StateFlow<String> = MutableStateFlow("Your day, honestly").asStateFlow()
    override val library: StateFlow<List<LibraryItem>> = MutableStateFlow<List<LibraryItem>>(emptyList()).asStateFlow()
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = true
    override suspend fun refresh() = Unit
    @Composable override fun Screen(onExit: () -> Unit, launch: ToolLaunch) = ScreenTimeScreen(app, data, onExit)
}

/** Checks limits every 15 minutes while any are set, and nudges once a day per app. */
class LimitWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val st = ScreenTime(applicationContext)
        val limits = st.limits().filterValues { it > 0 }
        if (limits.isEmpty() || !Collector(applicationContext).hasUsageAccess()) return Result.success()
        val now = System.currentTimeMillis(); val day = UsageMath.startOfDay(now)
        val today = UsageMath.totals(st.events(day - 3_600_000L, now), day, now)
        val pm = applicationContext.packageManager
        limits.forEach { (pkg, limit) ->
            val used = today[pkg] ?: 0
            if (used >= limit && !st.nudged(pkg, day)) {
                val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                Automations.notify(applicationContext, 400 + (pkg.hashCode() and 0xFF), R.drawable.ic_stat, "${UsageMath.hm(used)} on $label today",
                    "That's the limit you set. Maybe a good moment for something else.",
                    Intent(applicationContext, app.ultimate.MainActivity::class.java).putExtra(app.ultimate.MainActivity.EXTRA_TOOL, "screentime").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                st.markNudged(pkg, day)
            }
        }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context, on: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (on) wm.enqueueUniquePeriodicWork("limits", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<LimitWorker>(15, TimeUnit.MINUTES).build())
            else wm.cancelUniqueWork("limits")
        }
    }
}

private data class AppTime(val pkg: String, val label: String, val ms: Long)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScreenTimeScreen(app: UltimateApp, data: ScreenTime, onExit: () -> Unit) {
    BackHandler(onBack = onExit)
    val context = LocalContext.current
    var access by remember { mutableStateOf(Collector(app).hasUsageAccess()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { access = Collector(app).hasUsageAccess() }
    var days by remember { mutableStateOf<List<Pair<Long, Map<String, Long>>>>(emptyList()) }
    var selected by remember { mutableIntStateOf(6) }
    var limitFor by remember { mutableStateOf<AppTime?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(access, tick) { if (access) days = withContext(Dispatchers.IO) { data.days() } }
    val pm = context.packageManager
    val launchable = remember { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).map { it.activityInfo.packageName }.toSet() }
    val home = remember { pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName }
    fun apps(m: Map<String, Long>) = m.filterKeys { it in launchable && it != home }.map { (p, ms) ->
        AppTime(p, runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() }.getOrDefault(p), ms)
    }.sortedByDescending { it.ms }
    val day = days.getOrNull(selected)
    val list = day?.let { apps(it.second) }.orEmpty()
    val seen = remember(selected) { mutableSetOf<Any>() }

    Scaffold(topBar = { TopAppBar(title = { Text("Screen time") }, navigationIcon = { ToolBackButton(onExit) }, actions = { ShellActions() }) }) { pad ->
        if (!access) Column(Modifier.padding(pad).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("See where your time goes", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Android keeps a record of which apps were open and for how long. Allow usage access and Ultimate reads it, on the phone only.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button({ context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Allow usage access") }
        } else LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                val total = list.sumOf { it.ms }
                Text(if (selected == days.lastIndex) "Today" else day?.let { SimpleDateFormat("EEEE", Locale.getDefault()).format(it.first) } ?: "", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(UsageMath.hm(total), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                val week = days.map { d -> apps(d.second).sumOf { it.ms } }
                if (week.isNotEmpty()) Text("Average ${UsageMath.hm(week.average().toLong())} a day this week", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { WeekBars(days.map { d -> apps(d.second).sumOf { it.ms } }, days.map { it.first }, selected) { selected = it } }
            itemsIndexed(list.take(25), key = { _, a -> a.pkg }) { i, a ->
                val limit = data.limit(a.pkg)
                Row(
                    Modifier.fillMaxWidth().animateItem().appear(a.pkg, seen, i).pressScale(0.98f).clip(RoundedCornerShape(16.dp)).clickable { limitFor = a }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(a.pkg, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row { Text(a.label, Modifier.weight(1f), fontWeight = FontWeight.Medium, maxLines = 1); Text(UsageMath.hm(a.ms), style = MaterialTheme.typography.labelLarge) }
                        val frac = (a.ms.toFloat() / (list.first().ms.coerceAtLeast(1))).coerceIn(0.02f, 1f)
                        val over = limit > 0 && a.ms >= limit
                        Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            Box(Modifier.fillMaxWidth(frac).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary))
                        }
                        if (limit > 0) Text("Limit ${UsageMath.hm(limit)}", style = MaterialTheme.typography.labelSmall, color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { Text("Tap an app to set a daily limit. You'll get one gentle reminder when you reach it; nothing is blocked.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
        }
    }
    limitFor?.let { a ->
        ModalBottomSheet(onDismissRequest = { limitFor = null }) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Daily limit for ${a.label}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                val current = data.limit(a.pkg)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0L to "None", 15L to "15m", 30L to "30m", 60L to "1h", 120L to "2h", 180L to "3h").forEach { (m, label) ->
                        FilterChip(current == m * 60_000, {
                            data.setLimit(a.pkg, m * 60_000); LimitWorker.schedule(context, data.limits().isNotEmpty()); tick++; limitFor = null
                        }, { Text(label) })
                    }
                }
            }
        }
    }
}

/** Seven days as bars that grow in; tap one to see that day. */
@Composable
private fun WeekBars(totals: List<Long>, starts: List<Long>, selected: Int, onSelect: (Int) -> Unit) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(totals.size) { grow.animateTo(1f, tween(800, easing = FastOutSlowInEasing)) }
    val max = (totals.maxOrNull() ?: 1L).coerceAtLeast(1L)
    val primary = MaterialTheme.colorScheme.primary
    val rest = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    val fmt = remember { SimpleDateFormat("EEEEE", Locale.getDefault()) }
    Row(Modifier.fillMaxWidth().height(140.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        totals.forEachIndexed { i, t ->
            Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { onSelect(i) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.fillMaxWidth().height(116.dp)) {
                    val h = size.height * (t.toFloat() / max) * grow.value
                    drawRoundRect(if (i == selected) primary else rest, Offset(size.width * 0.15f, size.height - h), Size(size.width * 0.7f, h.coerceAtLeast(2f)), CornerRadius(8f))
                }
                Text(starts.getOrNull(i)?.let { fmt.format(it) } ?: "", style = MaterialTheme.typography.labelSmall, fontWeight = if (i == selected) FontWeight.Bold else null)
            }
        }
    }
}
