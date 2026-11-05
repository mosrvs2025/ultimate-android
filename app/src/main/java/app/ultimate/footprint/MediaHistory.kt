package app.ultimate.footprint

import android.app.SearchManager
import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.ultimate.core.AppIcon
import app.ultimate.core.GooeyProgress
import app.ultimate.core.GooeyTabs
import app.ultimate.core.motionOn
import app.ultimate.core.pressScale
import app.ultimate.inbox.dayLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Everything you watched and listened to, searchable. The dashboard on top follows whatever you have searched or
 * filtered: tap an app in the ring to show only it, or an artist to search for them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaHistory(onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) { MediaHistoryContent(onClose) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaHistoryContent(onClose: () -> Unit) {
    run {
        val context = LocalContext.current
        val log = remember { MediaLog(context) }
        var all by remember { mutableStateOf<List<Played>?>(null) }
        var tick by remember { mutableIntStateOf(0) }
        LaunchedEffect(tick) { all = withContext(Dispatchers.IO) { log.since(0, limit = 20_000) } }
        var query by remember { mutableStateOf("") }
        var range by remember { mutableIntStateOf(2) }
        var app by remember { mutableStateOf<String?>(null) }
        var open by remember { mutableStateOf<Long?>(null) }
        val spans = listOf(1, 7, 30, 0)
        val labels = remember { HashMap<String, String>() }
        fun label(pkg: String) = labels.getOrPut(pkg) { FootprintReaders.label(context, pkg) }
        val now = System.currentTimeMillis()
        val inRange = all.orEmpty().filter { spans[range] == 0 || it.startedAt >= now - spans[range] * 86_400_000L }
        val shown = inRange.filter { (app == null || it.pkg == app) && (query.isBlank() || MediaStats.matches(it, query, label(it.pkg))) }

        Scaffold(topBar = {
            TopAppBar(title = { Text("Watched and listened") }, navigationIcon = { IconButton(onClose) { Icon(Icons.Rounded.Close, "Close") } })
        }) { pad ->
            LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(28.dp),
                        placeholder = { Text("Search titles, artists, channels, apps") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, "Clear search") } })
                }
                item { GooeyTabs(listOf("Today", "7 days", "30 days", "All"), range, { range = it }) }
                val apps = MediaStats.byApp(inRange)
                if (apps.size > 1) item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(app == null, { app = null }, { Text("All apps") })
                        apps.forEach { (pkg, _) -> FilterChip(app == pkg, { app = if (app == pkg) null else pkg }, { Text(label(pkg)) }, leadingIcon = { AppIcon(pkg, size = 18.dp) }) }
                    }
                }
                if (all == null) { item { Text("Reading…", color = MaterialTheme.colorScheme.onSurfaceVariant) }; return@LazyColumn }
                if (shown.isEmpty()) { item { Text(if (all!!.isEmpty()) "Nothing kept yet. Play something for ten seconds and it shows up here." else "Nothing matches.", Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }; return@LazyColumn }

                item { Totals(shown, spans[range], now) }
                if (app == null && apps.size > 1) item {
                    val top = MediaStats.byApp(shown).take(6)
                    val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.inversePrimary, MaterialTheme.colorScheme.outline)
                    AccessDonut(top.map { (pkg, ms) -> label(pkg) to (ms / 60_000).toInt().coerceAtLeast(1) }, colors, -1, unit = "minutes") { i -> if (i >= 0) app = top[i].first }
                }
                val artists = MediaStats.topArtists(shown, 5)
                if (artists.isNotEmpty() && query.isBlank()) {
                    item { Text("Most time with", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                    items(artists, key = { "ar-" + it.first }) { (name, ms) ->
                        Column(Modifier.fillMaxWidth().pressScale(0.98f).clip(RoundedCornerShape(14.dp)).clickable { query = name }.padding(vertical = 4.dp)) {
                            Row { Text(name, Modifier.weight(1f), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(hm(ms), style = MaterialTheme.typography.labelLarge) }
                            GooeyProgress({ ms / artists.first().second.toFloat() }, Modifier.fillMaxWidth().padding(top = 4.dp).height(6.dp), live = false)
                        }
                    }
                }

                item { Text("${shown.size} ${if (shown.size == 1) "thing" else "things"}", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                var lastDay = ""
                shown.take(500).forEach { p ->
                    val day = dayLabel(p.startedAt, now)
                    if (day != lastDay) { lastDay = day; item(key = "d-$day-${p.id}") { Text(day, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) } }
                    item(key = "p-${p.id}-${p.startedAt}") {
                        PlayedRow(p, label(p.pkg), open == p.id, onToggle = { open = if (open == p.id) null else p.id }, onDelete = { log.delete(p.id); tick++ })
                    }
                }
                if (shown.size > 500) item { Text("Showing the newest 500. Search to find older ones.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
            }
        }
    }
}

/** The big number and the day-by-day line for what is on screen. */
@Composable
private fun Totals(list: List<Played>, span: Int, now: Long) {
    val total = list.sumOf { it.ms }
    val shown = remember(total) { Animatable(0f) }
    val motion = motionOn()
    LaunchedEffect(total) { if (motion) shown.animateTo(total.toFloat(), tween(800, easing = FastOutSlowInEasing)) else shown.snapTo(total.toFloat()) }
    val days = if (span == 0) 30 else span
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(hm(shown.value.toLong()), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(
            "playing, across ${list.size} ${if (list.size == 1) "thing" else "things"}" + (MediaStats.busiestHour(list)?.let { " · usually around ${app.ultimate.footprint.Habits.clock(it * 60)}" } ?: ""),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        if (days > 1) AreaChart(MediaStats.daily(list, days, now), Modifier.fillMaxWidth().height(70.dp))
    }
}

/** One thing played. Tap to open it again, search for it, or forget it. */
@Composable
private fun PlayedRow(p: Played, app: String, open: Boolean, onToggle: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).clickable(onClick = onToggle).animateContentSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(p.pkg, size = 36.dp); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.title, fontWeight = FontWeight.SemiBold, maxLines = if (open) 4 else 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(p.artist, app, SimpleDateFormat("h:mm a", Locale.getDefault()).format(p.startedAt), hm(p.ms)).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (open) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton({ context.packageManager.getLaunchIntentForPackage(p.pkg)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Open $app") }
            TextButton({ runCatching { context.startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, listOf(p.title, p.artist).filter { it.isNotBlank() }.joinToString(" ")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Look it up") }
            TextButton(onDelete) { Text("Forget", color = MaterialTheme.colorScheme.error) }
        }
    }
}
