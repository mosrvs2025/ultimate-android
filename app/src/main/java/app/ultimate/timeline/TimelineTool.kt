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
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Screenshot
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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures

val Source.icon: ImageVector get() = when (this) {
    Source.Notification -> Icons.Rounded.Notifications; Source.Clip -> Icons.Rounded.ContentPaste; Source.Watched -> Icons.Rounded.PlayCircle; Source.Focus -> Icons.Rounded.CenterFocusStrong
    Source.Photo -> Icons.Rounded.PhotoCamera; Source.Screenshot -> Icons.Rounded.Screenshot; Source.Install -> Icons.Rounded.InstallMobile; Source.Charge -> Icons.Rounded.BatteryChargingFull
}

@Composable
fun Source.tint(): Color = when (this) {
    Source.Notification -> MaterialTheme.colorScheme.primary; Source.Clip -> MaterialTheme.colorScheme.tertiary
    Source.Watched -> Color(0xFFE5704B); Source.Focus -> Color(0xFF3FA66B)
    Source.Photo -> Color(0xFFD9A400); Source.Screenshot -> Color(0xFF8E6CEF); Source.Install -> Color(0xFF2C9AB7); Source.Charge -> Color(0xFF6BAF3A)
}

private fun span(ms: Long) = if (ms >= 3_600_000) "${ms / 3_600_000}h ${ms / 60_000 % 60}m" else "${ms / 60_000}m"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimelineScreen(app: UltimateApp, initialQuery: String, onExit: () -> Unit, preset: List<Moment>? = null, embedded: Boolean = false, header: (@Composable () -> Unit)? = null) {
    var all by remember { mutableStateOf(preset) }
    val now = remember { System.currentTimeMillis() }
    LaunchedEffect(Unit) { if (preset == null) all = withContext(Dispatchers.IO) { TimelineReader.read(app, now - 60 * 86_400_000L) } }
    var query by remember { mutableStateOf(initialQuery) }
    var only by remember { mutableStateOf<Source?>(null) }
    var day by remember { mutableLongStateOf(TimelineMath.dayOf(now)) }
    // A stretch of hours dragged across on the chart (or one tapped), and "only the small hours" from a finding.
    var hours by remember { mutableStateOf<IntRange?>(null) }
    var late by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<Moment?>(null) }
    val list = all.orEmpty()
    val context = androidx.compose.ui.platform.LocalContext.current
    val actions = remember { MomentActions(app, context) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    fun hourOf(at: Long) = java.util.Calendar.getInstance().apply { timeInMillis = at }.get(java.util.Calendar.HOUR_OF_DAY)

    TimeHost(!embedded, onExit) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = if (embedded) 8.dp else 0.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (header != null) item { header() }
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

            if (query.isNotBlank() || late) {
                val hits = (if (query.isNotBlank()) TimelineMath.search(pool, query) else pool.sortedByDescending { it.at })
                    .filter { !late || hourOf(it.at) in 0..3 }.distinctBy { it.uid }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (hits.isEmpty()) "Nothing matches." else "${hits.size} found" + if (late) " after midnight" else "", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (late || only != null || query.isNotBlank()) TextButton({ late = false; only = null; query = "" }) { Text("Clear") }
                    }
                }
                var lastDay = -1L
                hits.take(300).forEach { m ->
                    val d = TimelineMath.dayOf(m.at)
                    if (d != lastDay) { lastDay = d; item(key = "qd-$d") { DayHeader(m.at, now) } }
                    item(key = "q-" + m.uid) { MomentRow(m, open == m, actions) { open = if (open == m) null else m } }
                }
                return@LazyColumn
            }

            val findings = TimelineMath.findings(list, now)
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("What your records say together", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    if (findings.isEmpty()) Text("These appear once there are a few days of notifications, copies, viewing and focus sessions to read across.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    else Text("Tap one to see the records behind it.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                    // Each finding wraps fully and, tapped, filters the records below to exactly what it is about.
                    findings.forEach { f ->
                        Row(Modifier.fillMaxWidth().pressScale(0.98f).clip(RoundedCornerShape(12.dp)).clickable {
                            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                            only = f.source; query = f.query.orEmpty(); late = f.lateNight
                        }.padding(vertical = 6.dp, horizontal = 2.dp)) {
                            Text("•", Modifier.width(16.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(f.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, softWrap = true)
                            Text("›", color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f))
                        }
                    }
                }
            }
            item { DayStrip(TimelineMath.days(pool, 30, now), day) { day = it; hours = null } }
            val todays = TimelineMath.onDay(pool, day).distinctBy { it.uid }
            item { DaySummary(todays, day, now) }
            if (todays.isNotEmpty()) item { HourBars(TimelineMath.hours(todays), hours) { hours = it } }
            val shown = hours?.let { r -> todays.filter { hourOf(it.at) in r } } ?: todays
            hours?.let { r -> item { TextButton({ hours = null }) { Text("Showing ${clock(r.first)}–${clock(r.last + 1)} · show the whole day") } } }
            if (shown.isEmpty()) item { Text("Nothing kept for this day.", Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            // Two records can share an id (a notification that was updated); a repeated key crashes a lazy list, so repeats get a suffix.
            val rows = run { val seen = HashMap<String, Int>(); shown.reversed().take(400).map { m -> val n = seen.merge(m.uid, 1, Int::plus)!!; (if (n == 1) "m-" + m.uid else "m-" + m.uid + "#" + n) to m } }
            items(rows, key = { it.first }) { (_, m) -> MomentRow(m, open == m, actions) { open = if (open == m) null else m } }
        }
    }
}

@Composable
private fun DayHeader(at: Long, now: Long) = Text(app.ultimate.inbox.dayLabel(at, now), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

/**
 * The last 30 days as small columns: tap one to look at that day. Each bar is stacked by kind (notifications, copies,
 * playing, focus) in their colours, and grows in from the bottom, one day after another.
 */
@Composable
private fun DayStrip(days: List<DayCount>, selected: Long, onPick: (Long) -> Unit) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (days.size - 6).coerceAtLeast(0))
    val max = (days.maxOfOrNull { it.weight } ?: 1f).coerceAtLeast(1f)
    val motion = motionOn()
    val grow = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { if (motion) grow.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    val colors = Source.entries.associateWith { it.tint() }
    LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(days.size, key = { days[it].day }) { i ->
            val d = days[i]
            val on = d.day == selected
            val start = TimelineMath.startOf(d.day)
            // Later days start growing a moment after earlier ones, so the month fills in left to right.
            val k = ((grow.value * 1.6f) - i / days.size.toFloat() * 0.6f).coerceIn(0f, 1f)
            Column(
                Modifier.width(46.dp).pressScale(0.94f).clip(RoundedCornerShape(16.dp)).background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
                    .clickable { onPick(d.day) }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val ink = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                Text(SimpleDateFormat("EEE", Locale.getDefault()).format(start), style = MaterialTheme.typography.labelSmall, color = ink.copy(alpha = 0.7f))
                Text(SimpleDateFormat("d", Locale.getDefault()).format(start), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = ink)
                Canvas(Modifier.padding(top = 4.dp).size(14.dp, 44.dp)) {
                    val full = size.height * (d.weight / max) * k
                    var y = size.height
                    if (d.weight <= 0f) drawRoundRect(colors.getValue(Source.Notification).copy(alpha = 0.2f), Offset(0f, size.height - 2.dp.toPx()), Size(size.width, 2.dp.toPx()), CornerRadius(2f))
                    d.parts.forEach { (src, w) ->
                        if (w <= 0f) return@forEach
                        val h = full * (w / d.weight)
                        drawRoundRect(colors.getValue(src), Offset(0f, y - h), Size(size.width, h), CornerRadius(3.dp.toPx())); y -= h
                    }
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
            list.count { it.source == Source.Photo }.takeIf { it > 0 }?.let { Stat(Source.Photo, "$it photos") }
            list.count { it.source == Source.Screenshot }.takeIf { it > 0 }?.let { Stat(Source.Screenshot, "$it screenshots") }
            list.count { it.source == Source.Install }.takeIf { it > 0 }?.let { Stat(Source.Install, "$it installed") }
            list.filter { it.source == Source.Charge }.takeIf { it.isNotEmpty() }?.let { Stat(Source.Charge, "${span(it.sumOf { c -> c.end - c.at })} charging") }
        }
    }
}

@Composable
private fun Stat(s: Source, text: String) = Row(Modifier.clip(RoundedCornerShape(50)).background(s.tint().copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
    Icon(s.icon, null, Modifier.size(16.dp), tint = s.tint()); Spacer(Modifier.width(6.dp)); Text(text, style = MaterialTheme.typography.labelLarge)
}

private fun clock(h: Int) = "${if (h % 12 == 0) 12 else h % 12}${if (h % 24 < 12) "am" else "pm"}"

/**
 * The day by hour, each bar stacked by kind. Tap an hour to see only it, or drag across the bars to pick a stretch of
 * hours: the list below follows as you scrub, with a tick at each hour.
 */
@Composable
private fun HourBars(hours: Array<IntArray>, picked: IntRange?, onPick: (IntRange?) -> Unit) {
    val colors = Source.entries.map { it.tint() }
    val dim = MaterialTheme.colorScheme.surfaceVariant
    val grow = remember(hours.sumOf { it.sum() }) { Animatable(0f) }
    val motion = motionOn()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    LaunchedEffect(hours.sumOf { it.sum() }) { if (motion) grow.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) else grow.snapTo(1f) }
    val max = (hours.maxOfOrNull { it.sum() } ?: 1).coerceAtLeast(1).toFloat()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(14.dp)) {
        Canvas(Modifier.fillMaxWidth().height(90.dp)
            .pointerInput(Unit) { detectTapGestures { o -> val h = (o.x / (size.width / 24f)).toInt().coerceIn(0, 23); onPick(if (picked == h..h) null else h..h) } }
            .pointerInput(Unit) {
                var from = 0; var last = -1
                detectHorizontalDragGestures(
                    onDragStart = { o -> from = (o.x / (size.width / 24f)).toInt().coerceIn(0, 23); last = from; onPick(from..from); haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove) },
                ) { change, _ ->
                    val h = (change.position.x / (size.width / 24f)).toInt().coerceIn(0, 23)
                    if (h != last) { last = h; haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove); onPick(minOf(from, h)..maxOf(from, h)) }
                }
            }) {
            val w = size.width / 24f
            picked?.let { r -> drawRoundRect(colors[0].copy(alpha = 0.1f), Offset(r.first * w, 0f), Size((r.last - r.first + 1) * w, size.height), CornerRadius(6.dp.toPx())) }
            hours.forEachIndexed { h, counts ->
                var y = size.height
                if (counts.sum() == 0) drawRoundRect(dim, Offset(h * w + 2, size.height - 3.dp.toPx()), Size(w - 4, 3.dp.toPx()), CornerRadius(2f))
                counts.forEachIndexed { k, n ->
                    if (n == 0) return@forEachIndexed
                    val hh = size.height * n / max * grow.value
                    drawRect(colors[k].copy(alpha = if (picked == null || h in picked) 1f else 0.3f), Offset(h * w + 2, y - hh), Size(w - 4, hh)); y -= hh
                }
            }
        }
        Row { listOf("12am", "6am", "12pm", "6pm").forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) } }
        Text("Tap an hour, or drag across to pick a stretch.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}

/**
 * One moment. Tap does the obvious thing (opens the notification or the app, copies again, opens the photo); hold for
 * everything else. Copied text gets one-tap chips for what it is (JSON, a link, a command, a colour).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MomentRow(m: Moment, open: Boolean, actions: MomentActions, onToggle: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
                .combinedClickable(onLongClick = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); menu = true }) { if (!actions.primary(m)) onToggle() }
                .animateContentSize().padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
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
                val smart = remember(m.uid) { actions.smart(m) }
                if (smart.isNotEmpty()) androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (m.source == Source.Clip) app.ultimate.clips.ClipSmart.color(m.detail.ifBlank { m.title })?.takeIf { app.ultimate.clips.ClipSmart.kind(m.detail.ifBlank { m.title }) == app.ultimate.clips.ClipSmart.Kind.Color }?.let { c ->
                        Box(Modifier.size(28.dp).clip(CircleShape).background(Color(c)))
                    }
                    smart.forEach { a -> Text(a.label, Modifier.clip(RoundedCornerShape(50)).background(m.source.tint().copy(alpha = 0.14f)).clickable(onClick = a.run).padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = m.source.tint()) }
                }
            }
        }
        androidx.compose.material3.DropdownMenu(menu, { menu = false }) {
            actions.menu(m) { onToggle() }.forEach { a -> androidx.compose.material3.DropdownMenuItem({ Text(a.label) }, { menu = false; a.run() }) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeHost(withBar: Boolean, onExit: () -> Unit, content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit) {
    if (withBar) Scaffold(topBar = { TopAppBar(title = { Text("Timeline") }, navigationIcon = { ToolBackButton(onExit) }, actions = { ShellActions() }) }) { content(it) }
    else content(androidx.compose.foundation.layout.PaddingValues(0.dp))
}

