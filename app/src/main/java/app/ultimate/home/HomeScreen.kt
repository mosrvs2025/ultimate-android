package app.ultimate.home

import app.ultimate.BuildConfig
import app.ultimate.insights.InsightsSheet
import app.ultimate.core.appear
import app.ultimate.core.pressScale
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import app.ultimate.core.WorkSnapshot
import app.ultimate.core.WorkSource
import kotlinx.coroutines.flow.map
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.UltimateApp
import app.ultimate.search.PaletteResults
import app.ultimate.ai.AiCall
import app.ultimate.ai.AiTools
import app.ultimate.ai.AiActions
import app.ultimate.ai.LocalAi
import app.ultimate.echo.EchoTool
import app.ultimate.echo.SonarState
import app.ultimate.echo.EchoState
import app.ultimate.core.LibraryRow
import app.ultimate.core.Tool
import app.ultimate.core.ToolLaunch
import app.ultimate.core.formatBytes
import kotlinx.coroutines.flow.combine
import app.ultimate.core.GooeyProgress
import app.ultimate.core.motionOn
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.runtime.collectAsState

@Composable
fun HomeScreen(app: UltimateApp, pad: PaddingValues) {
    val shell = app.shell
    val libraries = remember { combine(app.tools.map { it.library }) { it.toList().flatten() } }
    val all by libraries.collectAsStateWithLifecycle(emptyList())
    val saved = all.sumOf { it.savedBytes }
    val running by remember {
        combine(app.work.sources.map { src -> src.work.map { src to it } }) { it.toList().filter { (_, w) -> w.active } }
    }.collectAsStateWithLifecycle(emptyList())
    val recent = all.sortedByDescending { it.createdAt }.take(3)
    val seen = remember { mutableSetOf<Any>() }
    var showInsights by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (showInsights) InsightsSheet(app) { showInsights = false }
    var showSavings by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (showSavings) SavingsSheet(app.tools, all, onOpen = { t, i -> showSavings = false; openItem(app, t, i) }) { showSavings = false }

    val runningIds = running.map { it.first.workId }.toSet()
    // Recomputed only when something starts or stops running or a tool is opened, so tiles don't jump while you tap.
    // Pinned tools come first; the rest from the shared local memory (recency, time of day, running jobs) that the orb uses too.
    val pins by HomePins.ids(app).collectAsState()
    val revision by app.insights.revision.collectAsStateWithLifecycle()
    val favorites = remember(runningIds, pins, revision) {
        val ids = app.tools.map { it.id }.filter { it != "orb" }
        HomeLayout.withPins(pins, HomeLayout.constellation(ids, app.insights.recentOpens(), runningIds, System.currentTimeMillis()), ids, count = 6)
            .mapNotNull { id -> app.tools.firstOrNull { it.id == id } }
    }
    val pending = remember(revision) { app.insights.pendingTools() }
    // New tools you haven't opened yet: a card near the top and a badge on their tiles, until you try them or hide it.
    var fresh by remember(revision) { mutableStateOf(NewFeatures.pending(app, BuildConfig.VERSION_NAME)) }
    val freshIds = fresh.map { it.id }.toSet()
    val prefs = remember { app.getSharedPreferences("home", android.content.Context.MODE_PRIVATE) }
    var folded by remember { mutableStateOf(prefs.getStringSet("expanded", emptySet()).orEmpty()) }

    // Four columns: favorites take two each, every other tool one, and headers, search and results the full row.
    LazyVerticalGrid(
        GridCells.Fixed(4), Modifier.padding(pad), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Ultimate", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Hero(saved, all.size) { app.insights.count("home.savings"); app.tools.firstOrNull { it.id == "storage" }?.let { app.shell.open(it) } ?: run { showSavings = true } }
                androidx.compose.material3.IconButton({ app.insights.count("home.about"); shell.showAbout() }) { Icon(Icons.Rounded.Settings, "About and settings") }
            }
        }
        // Your tools: pins first, then what you open around this time and what is running. Compact tiles, two to a row.
        item(key = "favorites-header", span = { GridItemSpan(maxLineSpan) }) {
            Text("Your tools", Modifier.padding(start = 4.dp, top = 2.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        itemsIndexed(favorites, key = { _, t -> "fav-${t.id}" }, span = { _, _ -> GridItemSpan(2) }) { i, tool ->
            ToolTile(
                Modifier.animateItem().appear("fav-${tool.id}", seen, i), tool, wide = false,
                active = tool.id in runningIds, stop = app.work.sources.firstOrNull { it.workId == tool.id }?.takeIf { src -> running.any { it.first === src } },
                onLibrary = { shell.showLibraryOf(tool.id) }, onClick = { shell.open(tool) },
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) { AskBar(app) }
        if (fresh.isNotEmpty()) item(key = "new-for-you", span = { GridItemSpan(maxLineSpan) }) {
            NewForYou(fresh, onTry = { shell.open(it) }, onHide = { NewFeatures.hide(app, fresh.map { it.id }); fresh = emptyList() })
        }
        item(span = { GridItemSpan(maxLineSpan) }) { TodayDashboard(app) }
        item(key = "live-senses", span = { GridItemSpan(maxLineSpan) }) { LiveSenses(app) }
        app.tools.firstOrNull { it.id == "echo" }?.let { echo -> item(key = "senses", span = { GridItemSpan(maxLineSpan) }) { SensesCard(app, echo as app.ultimate.echo.EchoTool) } }
        if (pending.isNotEmpty()) item(key = "unfinished", span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text("Needs another look", style = MaterialTheme.typography.titleSmall)
                pending.mapNotNull { id -> app.tools.firstOrNull { it.id == id } }.forEach { tool ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton({ shell.open(tool) }, Modifier.weight(1f)) { Text("${tool.title} · unfinished job") }
                        TextButton({ app.insights.clearPending(tool.id) }) { Text("Clear") }
                    }
                }
            }
        }
        if (running.isNotEmpty()) items(running, key = { "run-${it.first.workId}" }, span = { GridItemSpan(maxLineSpan) }) { (source, snap) ->
            RunningBanner(source, snap, app.tools.firstOrNull { it.id == source.workId }, onView = { app.tools.firstOrNull { it.id == source.workId }?.let(shell::open) })
        }
        // Every tool, small and in fixed places, in families you can fold away; the fold is remembered.
        var order = 0
        toolSections(app.tools).forEach { (section, tools) ->
            val headerAt = order
            val isFolded = section !in folded
            item(key = "section-$section", span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(section, tools.size, isFolded, Modifier.animateItem().appear("section-$section", seen, headerAt)) {
                    folded = if (isFolded) folded + section else folded - section
                    prefs.edit().putStringSet("expanded", folded).apply()
                    app.insights.count("home.fold.$section")
                }
            }
            val base = ++order
            order += tools.size
            if (!isFolded) itemsIndexed(tools, key = { _, t -> t.id }, span = { _, _ -> GridItemSpan(1) }) { i, tool ->
                CompactTile(Modifier.animateItem().appear(tool.id, seen, base + i), tool, active = tool.id in runningIds, isNew = tool.id in freshIds, onLibrary = { shell.showLibraryOf(tool.id) }, onClick = { shell.open(tool) })
            }
        }
        if (recent.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Recent", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 4.dp))
                    TextButton({ shell.showLibrary() }) { Text("See all") }
                }
            }
            itemsIndexed(recent, key = { _, it -> it.id }, span = { _, _ -> GridItemSpan(maxLineSpan) }) { i, item ->
                val tool = app.tools.first { it.id == item.toolId }
                Box(Modifier.animateItem().appear(item.id, seen, app.tools.size + i)) { LibraryRow(item, tool, onClick = { openItem(app, tool, item) }) }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.appear("shape", seen, app.tools.size + 4)) { ShapeCard { showInsights = true } } }
    }
}

/** Anything still working in the background, with the one-tap way to end it, so nothing is left running by accident. */
@Composable
private fun RunningBanner(source: WorkSource, snap: WorkSnapshot, tool: Tool?, onView: () -> Unit) {
    val pulse = if (motionOn()) rememberInfiniteTransition(label = "run").animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dot",
    ).value else 1f
    Row(
        Modifier.fillMaxWidth().pressScale(0.97f).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.tertiaryContainer).clickable(onClick = onView).padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error.copy(alpha = pulse)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${tool?.title ?: source.workTitle} is running", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(
                snap.title + (snap.percent?.let { " · $it%" } ?: ""), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            // The work itself, as liquid: it flows forward as the percent climbs, or drops run along it when the length is unknown.
            val bar = Modifier.fillMaxWidth().padding(top = 6.dp).height(5.dp)
            val ink = MaterialTheme.colorScheme.onTertiaryContainer
            snap.percent?.let { pc -> GooeyProgress({ pc / 100f }, bar, color = ink, trackColor = ink.copy(alpha = 0.15f)) } ?: GooeyProgress(bar, color = ink, trackColor = ink.copy(alpha = 0.15f))
        }
        TextButton(source::cancelWork) { Text(source.cancelLabel) }
    }
}

/** A family's name with how many tools it holds; tap to fold it away or open it again. */
@Composable
private fun SectionHeader(name: String, count: Int, folded: Boolean, modifier: Modifier, onToggle: () -> Unit) {
    val turn by androidx.compose.animation.core.animateFloatAsState(if (folded) -90f else 0f, label = "fold")
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = if (folded) "Show $name" else "Fold $name", onClick = onToggle).padding(start = 4.dp, top = 8.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        Text("  $count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.weight(1f))
        Icon(Icons.Rounded.ExpandMore, if (folded) "Folded" else "Open", Modifier.size(20.dp).graphicsLayer { rotationZ = turn }, tint = MaterialTheme.colorScheme.outline)
    }
}

/** Icon and name only: the whole app fits in a glance, and each tool stays in the same place. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactTile(modifier: Modifier, tool: Tool, active: Boolean, isNew: Boolean = false, onLibrary: () -> Unit, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val container by androidx.compose.animation.animateColorAsState(if (active) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerLow, label = "compact")
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().pressScale(0.93f).clip(RoundedCornerShape(18.dp)).background(container)
                .combinedClickable(onClick = onClick, onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true })
                .padding(vertical = 10.dp, horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(tool.icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(tool.title, Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp), style = MaterialTheme.typography.labelSmall, maxLines = 2, minLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center, overflow = TextOverflow.Ellipsis)
        }
        if (isNew) NewBadge(Modifier.align(Alignment.TopEnd).padding(4.dp))
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text("Open ${tool.title}") }, { menu = false; onClick() }, leadingIcon = { Icon(tool.icon, null) })
            DropdownMenuItem({ Text("Its results in Library") }, { menu = false; onLibrary() }, leadingIcon = { Icon(Icons.Rounded.VideoLibrary, null) })
            PinItem(tool) { menu = false }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolTile(modifier: Modifier = Modifier, tool: Tool, wide: Boolean, active: Boolean, stop: WorkSource?, onLibrary: () -> Unit, onClick: () -> Unit) {
    val headline by tool.headline.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val container by androidx.compose.animation.animateColorAsState(if (active) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerLow, label = "tile")
    Box(modifier) {
        Card(
            colors = CardDefaults.cardColors(containerColor = container),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().pressScale().clip(RoundedCornerShape(24.dp)).combinedClickable(
                onClick = onClick,
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
            ),
        ) {
            val icon = @Composable {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Icon(tool.icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
            val text = @Composable { mod: Modifier ->
                Column(mod) {
                    Text(tool.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(headline, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (wide) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    icon(); Spacer(Modifier.width(14.dp)); text(Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
                }
            } else {
                Column(Modifier.padding(14.dp).heightIn(min = 92.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    icon(); Spacer(Modifier.height(10.dp)); text(Modifier)
                }
            }
        }
        // Long-press for shortcuts, so common jumps are one gesture instead of a trip through screens.
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text("Open ${tool.title}") }, { menu = false; onClick() }, leadingIcon = { Icon(tool.icon, null) })
            DropdownMenuItem({ Text("Its results in Library") }, { menu = false; onLibrary() }, leadingIcon = { Icon(Icons.Rounded.VideoLibrary, null) })
            stop?.let { DropdownMenuItem({ Text(it.cancelLabel) }, { menu = false; it.cancelWork() }, leadingIcon = { Icon(Icons.Rounded.Stop, null) }) }
            PinItem(tool) { menu = false }
        }
    }
}

/** Space saved so far, as a small counting-up chip beside the title. */
@Composable
private fun Hero(saved: Long, count: Int, onClick: () -> Unit) {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(saved) { shown.animateTo(saved.toFloat(), tween(1400, easing = FastOutSlowInEasing)) }
    Column(
        Modifier.pressScale().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primaryContainer).clickable(onClickLabel = "See where your space went", onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Text(formatBytes(shown.value.toLong()), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(
            if (count == 0) "space saved" else "saved · $count made",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

internal fun openItem(app: UltimateApp, tool: app.ultimate.core.Tool, item: app.ultimate.core.LibraryItem) {
    if (tool.opensOwnScreen(item)) app.shell.open(tool, ToolLaunch(openItemId = item.id)) else app.shell.showLibrary()
}

/** The way back to whoever builds Ultimate: a report of what is used and what is missing. */
@Composable
private fun ShapeCard(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).pressScale(0.97f).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.secondaryContainer).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(androidx.compose.material.icons.Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Help shape Ultimate", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text("Export a usage report to plan the next tools", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

/** Home's families of tools, in a fixed order; a tool not named here joins "More". */
internal fun toolSections(tools: List<app.ultimate.core.Tool>): List<Pair<String, List<app.ultimate.core.Tool>>> {
    val families = linkedMapOf(
        "Create" to listOf("studio", "squeeze", "audio", "recorder", "photos", "prompter"),
        "Phone" to listOf("files", "storage", "cleanup", "inbox", "clips", "checkup", "pluck", "footprint"),
        // Spatial sensing: sonar, camera hand tracking and the sensors they build on, together in one place.
        "Senses" to listOf("echo", "air", "watch", "echo-teach", "echo-camera", "ruler", "sensors", "sound-meter"),
        "Network" to listOf("hosts", "ssh", "traffic", "network", "lan", "send", "mesh"),
        "Smart" to listOf("timeline", "focus", "rules", "recipes", "orb", "screentime", "terminal"),
    )
    val placed = families.values.flatten().toSet()
    return (families.map { (name, ids) -> name to ids.mapNotNull { id -> tools.firstOrNull { it.id == id } } } + ("More" to tools.filter { it.id !in placed }))
        .filter { it.second.isNotEmpty() }
}

/**
 * "What do you want to do?" Typed words become ranked actions as you type, and "Open a file" hands anything to the
 * same sheet that sharing into the app uses.
 */
@Composable
private fun AskBar(app: UltimateApp) {
    var query by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    val ids = remember { app.tools.map { it.id }.toSet() }
    val results = remember(query) { Commands.available(Commands.search(query), ids, false) }
    // A request that finds nothing, left for a moment, is worth knowing about: it names a missing tool.
    LaunchedEffect(query, results.isEmpty()) {
        if (results.isEmpty() && query.trim().length >= 4) { kotlinx.coroutines.delay(2500); app.insights.unmatched(query) }
    }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) { app.insights.count("home.openfile"); app.shell.offerShared(uris, app.contentResolver.getType(uris[0])) }
    }
    Column(Modifier.padding(top = 4.dp)) {
        androidx.compose.material3.OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth(),
            placeholder = { Text("Search apps, people, messages, files…") }, singleLine = true, shape = RoundedCornerShape(28.dp),
            leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary) },
            trailingIcon = {
                if (query.isEmpty()) androidx.compose.material3.IconButton({ picker.launch(arrayOf("video/*", "image/*", "audio/*")) }) { Icon(Icons.Rounded.AttachFile, "Open a file") }
                else androidx.compose.material3.IconButton({ query = "" }) { Icon(Icons.Rounded.Close, "Clear") }
            },
        )
        AiSuggestion(app, query) { query = "" }
        // The command palette: apps, people, messages, files, settings, records and Ultimate's own tools, in one list.
        PaletteResults(app, query) { app.insights.count("home.palette"); query = "" }
    }
}

/** "Pin to the top" or "Unpin", for a tool's long-press menu. */
@Composable
private fun PinItem(tool: Tool, done: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pins by HomePins.ids(context).collectAsState()
    val on = tool.id in pins
    DropdownMenuItem({ Text(if (on) "Unpin from the top" else "Pin to the top") }, { done(); HomePins.toggle(context, tool.id) },
        leadingIcon = { Icon(if (on) Icons.Rounded.PushPin else Icons.Outlined.PushPin, null) })
}



/**
 * What is using the phone's senses right now (Echo listening, a remote session open), each with its own Stop. Nothing
 * sensitive runs without showing up here and in the notification shade.
 */
@Composable
private fun LiveSenses(app: UltimateApp) {
    val sessions by app.senses.sessions.collectAsStateWithLifecycle()
    if (sessions.isEmpty()) return
    androidx.compose.material3.Card(
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    ) {
        androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text("Live now", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onTertiaryContainer)
            sessions.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.label, Modifier.weight(1f).clickable { app.tools.firstOrNull { it.id == s.module }?.let { app.shell.open(it) } }, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    androidx.compose.material3.TextButton({ app.senses.stop(s.id) }) { Text("Stop") }
                }
            }
            if (sessions.size > 1) androidx.compose.material3.TextButton({ app.senses.stopAll() }) { Text("Stop everything") }
        }
    }
}


/**
 * The phone's senses, up top where they can't get lost among the tools: Echo's live state at a glance (what it is
 * measuring right now), and one tap to open it.
 */
@Composable
private fun SensesCard(app: UltimateApp, echo: EchoTool) {
    val sonar by echo.sonar.state.collectAsStateWithLifecycle()
    val motion by echo.engine.state.collectAsStateWithLifecycle()
    val line = when (val s = sonar) {
        is SonarState.Running -> s.frame.fix.rangeM.takeIf { !it.isNaN() }?.let { "Measuring · something %.0f cm away".format(it * 100) } ?: "Measuring · nothing moving"
        SonarState.Warming -> "Learning the room…"
        else -> if (motion is EchoState.Sensing) "Sensing motion" else "Feel distance and movement with sound you can't hear"
    }
    val live = sonar is SonarState.Running || motion is EchoState.Sensing
    val glow = if (live && motionOn()) androidx.compose.animation.core.rememberInfiniteTransition(label = "senses").animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1600)), label = "g").value else 0f
    Row(
        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(androidx.compose.ui.graphics.Color(0xFF14213D), androidx.compose.ui.graphics.Color(0xFF1F4E79))))
            .clickable { app.insights.count("home.senses"); app.shell.open(echo) }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.Canvas(Modifier.padding(end = 14.dp).size(44.dp)) {
            val o = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
            for (i in 0 until 3) {
                val p = ((i / 3f) + glow) % 1f
                drawCircle(androidx.compose.ui.graphics.Color(0xFF7FD3FF).copy(alpha = if (live) 1f - p else 0.25f), size.minDimension / 2 * (if (live) p else (i + 1) / 3f), o, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            }
            drawCircle(androidx.compose.ui.graphics.Color(0xFF7FD3FF), 4.dp.toPx(), o)
        }
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text("Senses · Echo", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(line, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
        }
    }
}


/**
 * Local AI's answer to what was typed: a proposal to confirm, never an action taken by itself. "When…" requests are
 * rules, handed to the rule maker; the rest go to Needle after a short pause in typing, and only a confident answer shows.
 */
@Composable
private fun AiSuggestion(app: UltimateApp, query: String, onDone: () -> Unit) {
    val ai = app.localAi ?: return
    val state by ai.state.collectAsStateWithLifecycle()
    var calls by remember { mutableStateOf<List<AiCall>>(emptyList()) }
    val q = query.trim()
    LaunchedEffect(q, state) {
        calls = emptyList()
        if (q.length < 4 || state != LocalAi.State.Ready || AiTools.isRule(q)) return@LaunchedEffect
        kotlinx.coroutines.delay(600)
        calls = ai.propose(q)
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    if (q.length >= 4 && AiTools.isRule(q) && app.tools.any { it.id == "rules" }) {
        SuggestionRow("Make this a rule", q) { app.tools.firstOrNull { it.id == "rules" }?.let { app.shell.open(it, ToolLaunch(query = q)) }; onDone() }
    }
    calls.firstOrNull()?.let { c ->
        SuggestionRow(AiTools.describe(c), "Local AI · %.0f%% sure · tap to do it".format(c.confidence * 100)) {
            android.widget.Toast.makeText(context, AiActions.run(app, c), android.widget.Toast.LENGTH_SHORT).show(); onDone()
        }
    }
}

@Composable
private fun SuggestionRow(title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primaryContainer).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

/** A small "New" pill for tools added or changed in the latest versions that you haven't opened yet. */
@Composable
internal fun NewBadge(modifier: Modifier = Modifier) {
    Text("New", modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.tertiary).padding(horizontal = 6.dp, vertical = 1.dp),
        color = MaterialTheme.colorScheme.onTertiary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
}

/** "New for you": each new tool with what it does and one button to try it. Gone once tried, or when hidden. */
@Composable
private fun NewForYou(tools: List<Tool>, onTry: (Tool) -> Unit, onHide: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("New for you", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TextButton(onHide) { Text("Hide") }
            }
            tools.forEach { tool ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.tertiary), contentAlignment = Alignment.Center) {
                        Icon(tool.icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onTertiary)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(tool.title, fontWeight = FontWeight.SemiBold)
                        Text(tool.tagline, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    androidx.compose.material3.FilledTonalButton({ onTry(tool) }) { Text("Try") }
                }
            }
        }
    }
}
