package app.ultimate.timeline

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ultimate.UltimateApp
import app.ultimate.core.LibraryItem
import app.ultimate.core.ShellActions
import app.ultimate.core.Tool
import app.ultimate.core.ToolBackButton
import app.ultimate.core.ToolLaunch
import app.ultimate.core.motionOn
import app.ultimate.core.pressScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/** Everything the app keeps (notifications, copies, what you played, focus sessions) on one searchable timeline. */
class TimelineTool(private val app: UltimateApp) : Tool {
    override val id = "timeline"
    override val title = "Timeline"
    override val tagline = "Search everything, day by day, and what it adds up to"
    override val icon = Icons.Rounded.Timeline
    override val acceptedMimePrefixes = emptyList<String>()
    override val headline: StateFlow<String> = MutableStateFlow("Your days, searchable").asStateFlow()
    override val library: StateFlow<List<LibraryItem>> = MutableStateFlow<List<LibraryItem>>(emptyList()).asStateFlow()
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = true
    override suspend fun refresh() = Unit
    @Composable override fun Screen(onExit: () -> Unit, launch: ToolLaunch) = TimelineScreen(app, launch.query.orEmpty(), onExit)
}

val Source.icon: ImageVector get() = when (this) { Source.Notification -> Icons.Rounded.Notifications; Source.Clip -> Icons.Rounded.ContentPaste; Source.Watched -> Icons.Rounded.PlayCircle; Source.Focus -> Icons.Rounded.CenterFocusStrong }

@Composable
fun Source.tint(): Color = when (this) {
    Source.Notification -> MaterialTheme.colorScheme.primary; Source.Clip -> MaterialTheme.colorScheme.tertiary
    Source.Watched -> Color(0xFFE5704B); Source.Focus -> Color(0xFF3FA66B)
}

private fun span(ms: Long) = if (ms >= 3_600_000) "${ms / 3_600_000}h ${ms / 60_000 % 60}m" else "${ms / 60_000}m"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimelineScreen(app: UltimateApp, initialQuery: String, onExit: () -> Unit, preset: List<Moment>? = null) {
    var all by remember { mutableStateOf(preset) }
    val now = remember { System.currentTimeMillis() }
    LaunchedEffect(Unit) { if (preset == null) all = withContext(Dispatchers.IO) { TimelineReader.read(app, now - 60 * 86_400_000L) } }
    var query by remember { mutableStateOf(initialQuery) }
    var only by remember { mutableStateOf<Source?>(null) }
    var day by remember { mutableLongStateOf(TimelineMath.dayOf(now)) }
    var hour by remember { mutableIntStateOf(-1) }
    var open by remember { mutableStateOf<Moment?>(null) }
    val list = all.orEmpty()

    Scaffold(topBar = { TopAppBar(title = { Text("Timeline") }, navigationIcon = { ToolBackButton(onExit) }, actions = { ShellActions() }) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(28.dp),
                    placeholder = { Text("Search notifications, copies, what you played…") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, "Clear search") } })
            }
            if (all == null) { item { Text("Reading your records…", color = MaterialTheme.colorScheme.onSurfaceVariant) }; return@LazyColumn }
            val present = Source.entries.filter { s -> list.any { it.source == s } }
            if (present.size > 1) item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(only == null, { only = null }, { Text("Everything") })
                    present.forEach { s -> FilterChip(only == s, { only = if (only == s) null else s }, { Text(s.label) }, leadingIcon = { Icon(s.icon, null, Modifier.size(18.dp), tint = s.tint()) }) }
                }
            }
            val pool = list.filter { only == null || it.source == only }

            if (query.isNotBlank()) {
                val hits = TimelineMath.search(pool, query)
                item { Text(if (hits.isEmpty()) "Nothing matches." else "${hits.size} found", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                var lastDay = -1L
                hits.take(300).forEach { m ->
                    val d = TimelineMath.dayOf(m.at)
                    if (d != lastDay) { lastDay = d; item(key = "qd-$d") { DayHeader(m.at, now) } }
                    item(key = "q-${m.source}-${m.at}-${m.title.hashCode()}") { MomentRow(m, open == m, query) { open = if (open == m) null else m } }
                }
                return@LazyColumn
            }

            val insights = TimelineMath.insights(list, now)
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("What your records say together", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    if (insights.isEmpty()) Text("These appear once there are a few days of notifications, copies, viewing and focus sessions to read across.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    insights.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                }
            }
            item { DayStrip(TimelineMath.days(pool, 30, now), day) { day = it; hour = -1 } }
            val todays = TimelineMath.onDay(pool, day)
            item { DaySummary(todays, day, now) }
            if (todays.isNotEmpty()) item { HourBars(TimelineMath.hours(todays), hour) { hour = if (hour == it) -1 else it } }
            val shown = if (hour >= 0) todays.filter { java.util.Calendar.getInstance().apply { timeInMillis = it.at }.get(java.util.Calendar.HOUR_OF_DAY) == hour } else todays
            if (hour >= 0) item { TextButton({ hour = -1 }) { Text("Showing ${hour % 12 + if (hour % 12 == 0) 12 else 0}${if (hour < 12) "am" else "pm"} only · show the whole day") } }
            if (shown.isEmpty()) item { Text("Nothing kept for this day.", Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(shown.reversed().take(400), key = { "m-${it.source}-${it.at}-${it.title.hashCode()}" }) { m -> MomentRow(m, open == m, "") { open = if (open == m) null else m } }
        }
    }
}

@Composable
private fun DayHeader(at: Long, now: Long) = Text(app.ultimate.inbox.dayLabel(at, now), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

/** The last 30 days as small columns: tap one to look at that day. Each bar's height is how much happened. */
@Composable
private fun DayStrip(days: List<DayCount>, selected: Long, onPick: (Long) -> Unit) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (days.size - 6).coerceAtLeast(0))
    val max = (days.maxOfOrNull { it.total } ?: 1).coerceAtLeast(1)
    LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(days, key = { it.day }) { d ->
            val on = d.day == selected
            val start = TimelineMath.startOf(d.day)
            Column(
                Modifier.width(46.dp).pressScale(0.94f).clip(RoundedCornerShape(16.dp)).background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow)
                    .clickable { onPick(d.day) }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val ink = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                Text(SimpleDateFormat("EEE", Locale.getDefault()).format(start), style = MaterialTheme.typography.labelSmall, color = ink.copy(alpha = 0.7f))
                Text(SimpleDateFormat("d", Locale.getDefault()).format(start), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = ink)
                Box(Modifier.padding(top = 4.dp).size(6.dp, 28.dp), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.size(6.dp, (28f * d.total / max).coerceAtLeast(2f).dp).clip(RoundedCornerShape(3.dp)).background(if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary))
                }
            }
        }
    }
}

/** One line per kind for the chosen day. */
@Composable
private fun DaySummary(list: List<Moment>, day: Long, now: Long) {
    val notes = list.count { it.source == Source.Notification }; val clips = list.count { it.source == Source.Clip }
    val watched = list.filter { it.source == Source.Watched }.sumOf { it.end - it.at }; val focus = list.filter { it.source == Source.Focus }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(app.ultimate.inbox.dayLabel(TimelineMath.startOf(day) + 3_600_000, now), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (notes > 0) Stat(Source.Notification, "$notes notifications")
            if (watched > 0) Stat(Source.Watched, "${span(watched)} watched")
            if (clips > 0) Stat(Source.Clip, "$clips copied")
            if (focus.isNotEmpty()) Stat(Source.Focus, "${span(focus.sumOf { it.end - it.at })} focused")
        }
    }
}

@Composable
private fun Stat(s: Source, text: String) = Row(Modifier.clip(RoundedCornerShape(50)).background(s.tint().copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
    Icon(s.icon, null, Modifier.size(16.dp), tint = s.tint()); Spacer(Modifier.width(6.dp)); Text(text, style = MaterialTheme.typography.labelLarge)
}

/** The day by hour, each bar stacked by kind. Tap an hour to see only it. */
@Composable
private fun HourBars(hours: Array<IntArray>, picked: Int, onPick: (Int) -> Unit) {
    val colors = Source.entries.map { it.tint() }
    val dim = MaterialTheme.colorScheme.surfaceVariant
    val grow = remember(hours.sumOf { it.sum() }) { Animatable(0f) }
    val motion = motionOn()
    LaunchedEffect(hours.sumOf { it.sum() }) { if (motion) grow.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) else grow.snapTo(1f) }
    val max = (hours.maxOfOrNull { it.sum() } ?: 1).coerceAtLeast(1).toFloat()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(14.dp)) {
        Canvas(Modifier.fillMaxWidth().height(90.dp).pointerInput(Unit) { detectTapGestures { o -> onPick((o.x / (size.width / 24f)).toInt().coerceIn(0, 23)) } }) {
            val w = size.width / 24f
            hours.forEachIndexed { h, counts ->
                var y = size.height
                if (counts.sum() == 0) drawRoundRect(dim, Offset(h * w + 2, size.height - 3.dp.toPx()), Size(w - 4, 3.dp.toPx()), CornerRadius(2f))
                counts.forEachIndexed { k, n ->
                    if (n == 0) return@forEachIndexed
                    val hh = size.height * n / max * grow.value
                    drawRect(colors[k].copy(alpha = if (picked < 0 || picked == h) 1f else 0.3f), Offset(h * w + 2, y - hh), Size(w - 4, hh)); y -= hh
                }
            }
        }
        Row { listOf("12am", "6am", "12pm", "6pm").forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) } }
    }
}

/** One moment. Tap to read all of it. */
@Composable
private fun MomentRow(m: Moment, open: Boolean, query: String, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).clickable(onClick = onToggle).animateContentSize().padding(12.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(m.source.tint().copy(alpha = 0.16f)), contentAlignment = Alignment.Center) { Icon(m.source.icon, null, Modifier.size(18.dp), tint = m.source.tint()) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(listOf(m.app, m.source.label).filter { it.isNotBlank() }.distinct().first(), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = m.source.tint(), maxLines = 1)
                Text(SimpleDateFormat("h:mm a", Locale.getDefault()).format(m.at) + if (m.end > m.at + 60_000) " · ${span(m.end - m.at)}" else "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(m.title, fontWeight = FontWeight.SemiBold, maxLines = if (open) 4 else 1, overflow = TextOverflow.Ellipsis)
            if (m.detail.isNotBlank() && m.detail != m.title) Text(m.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (open) 30 else 2, overflow = TextOverflow.Ellipsis)
            if (open && m.source == Source.Focus && m.focusDrift > 0) Text("${m.focusDrift} drifting check${if (m.focusDrift == 1) "" else "s"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

