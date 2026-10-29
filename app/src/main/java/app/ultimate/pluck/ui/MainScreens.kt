package app.ultimate.pluck.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.core.AppIcon
import app.ultimate.core.ShellActions
import app.ultimate.core.ToolBackButton
import app.ultimate.core.copyText
import app.ultimate.core.formatBytes
import app.ultimate.core.openApp
import app.ultimate.core.openAppSettings
import app.ultimate.core.relativeTime
import app.ultimate.core.rememberNotificationConsent
import app.ultimate.core.uninstallApp
import app.ultimate.pluck.model.AppItem
import app.ultimate.pluck.model.AppSource
import app.ultimate.pluck.model.Cleanup
import app.ultimate.pluck.model.Replacements
import app.ultimate.pluck.model.Filter
import app.ultimate.pluck.model.QuickFilter
import app.ultimate.pluck.model.Sort
import app.ultimate.pluck.model.TrackerFacts

@Composable
fun PluckScreen(c: PluckController, onExit: () -> Unit) {
    val extraction by c.extraction.collectAsStateWithLifecycle()
    val detailPackage by c.detailPackage.collectAsStateWithLifecycle()
    val page by c.page.collectAsStateWithLifecycle()
    val withConsent = rememberNotificationConsent()
    var sheetFor by remember { mutableStateOf<List<AppItem>?>(null) }
    val onExtract: (List<AppItem>) -> Unit = { sheetFor = it }

    DisposableEffect(Unit) {
        c.reload() // an app may have been installed or uninstalled while Pluck was closed
        onDispose {}
    }
    when {
        extraction.jobs.isNotEmpty() -> ExtractScreen(c, extraction)
        detailPackage != null -> DetailScreen(c, onExtract)
        page == Page.Trackers -> TrackerReport(c, onBack = { c.openPage(Page.Main) })
        page == Page.Cleanup -> CleanupScreen(c, onBack = { c.openPage(Page.Main) }, onExtract)
        else -> MainScreen(c, onExtract, onExit)
    }

    val sdkName by c.sdkSheet.collectAsStateWithLifecycle()
    sdkName?.let { SdkSheet(it, c) }

    sheetFor?.let { items ->
        ExtractSheet(
            items, c, onDismiss = { sheetFor = null },
            onConfirm = { compact, share ->
                sheetFor = null
                withConsent { c.extract(items, compact, share) }
            },
        )
    }
}

// ───────────────────────────── Main shell ────────────────────────

@Composable
private fun MainScreen(vm: PluckController, onExtract: (List<AppItem>) -> Unit, onExit: () -> Unit) {
    val tab by vm.tab.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val selected = apps.filter { it.packageName in selection }

    BackHandler {
        when {
            selection.isNotEmpty() -> vm.clearSelection()
            tab != Tab.Apps -> vm.tab.value = Tab.Apps
            else -> onExit()
        }
    }

    Scaffold(
        bottomBar = {
            if (selection.isNotEmpty() && tab == Tab.Apps) {
                SelectionBar(selected, onAll = vm::selectAllVisible, onClose = vm::clearSelection, onExtract = { onExtract(selected) })
            } else {
                NavigationBar {
                    NavigationBarItem(tab == Tab.Apps, { vm.tab.value = Tab.Apps }, { Icon(Icons.Rounded.Apps, null) }, label = { Text("Apps") })
                    NavigationBarItem(tab == Tab.Insights, { vm.tab.value = Tab.Insights }, { Icon(Icons.Rounded.Insights, null) }, label = { Text("Insights") })
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                Tab.Apps -> AppsTab(vm, onExtract, onExit)
                Tab.Insights -> InsightsTab(vm, onExit)
            }
        }
    }
}

@Composable
private fun SelectionBar(selected: List<AppItem>, onAll: () -> Unit, onClose: () -> Unit, onExtract: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(Icons.Rounded.Close, "Clear selection") }
            Column(Modifier.weight(1f)) {
                Text("${selected.size} selected", style = MaterialTheme.typography.titleMedium)
                Text(formatBytes(selected.sumOf { it.apkBytes }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onAll) { Icon(Icons.Rounded.SelectAll, "Select all shown") }
            Button(onExtract, Modifier.height(52.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Rounded.Download, null)
                Spacer(Modifier.width(8.dp))
                Text("Extract…")
            }
        }
    }
}

// ───────────────────────────── Apps tab ──────────────────────────

@Composable
private fun AppsTab(vm: PluckController, onExtract: (List<AppItem>) -> Unit, onExit: () -> Unit) {
    val visible by vm.visible.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val options by vm.options.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val trackers by vm.trackers.collectAsStateWithLifecycle()
    val cleanup by vm.cleanup.collectAsStateWithLifecycle()
    val targets by vm.markTargets.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    var sortMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var markFor by remember { mutableStateOf<AppItem?>(null) }
    val replacedBy = remember(cleanup) { cleanup.associateBy { it.app.packageName } }
    val userApps = apps.filter { !it.isSystem }
    val needsScan = (options.sort == Sort.Trackers || QuickFilter.HasAds in options.quick) && trackers.isEmpty()

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolBackButton(onExit)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Apps", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "${visible.size} shown · ${formatBytes(visible.sumOf { it.apkBytes })}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton({ vm.openPage(Page.Trackers) }) { Icon(Icons.Rounded.Radar, "Ads & trackers report") }
            ShellActions()
            Box {
                IconButton({ moreMenu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(moreMenu, { moreMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Extract all user apps (${userApps.size})") }, leadingIcon = { Icon(Icons.Rounded.Download, null) },
                        onClick = { moreMenu = false; onExtract(userApps) }, enabled = userApps.isNotEmpty(),
                    )
                    DropdownMenuItem(
                        text = { Text("Extract everything shown (${visible.size})") }, leadingIcon = { Icon(Icons.Rounded.SelectAll, null) },
                        onClick = { moreMenu = false; onExtract(visible) }, enabled = visible.isNotEmpty(),
                    )
                    DropdownMenuItem(
                        text = { Text("Clean up replaced apps (${cleanup.size})") }, leadingIcon = { Icon(Icons.Rounded.CleaningServices, null) },
                        onClick = { moreMenu = false; vm.openPage(Page.Cleanup) }, enabled = cleanup.isNotEmpty(),
                    )
                    DropdownMenuItem(
                        text = { Text("Scan apps for trackers") }, leadingIcon = { Icon(Icons.Rounded.Radar, null) },
                        onClick = { moreMenu = false; vm.scanAll() },
                    )
                }
            }
        }
        OutlinedTextField(
            value = query, onValueChange = { vm.query.value = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            placeholder = { Text("Search by name or package") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton({ vm.query.value = "" }) { Icon(Icons.Rounded.Close, "Clear") } },
            singleLine = true, shape = RoundedCornerShape(28.dp),
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                FilterChip(
                    true, { sortMenu = true }, { Text(options.sort.label + if (options.reversed) " ↑" else "") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Sort, null, Modifier.size(18.dp)) },
                )
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    Sort.entries.forEach { s ->
                        DropdownMenuItem(
                            { Text(s.label, fontWeight = if (s == options.sort) FontWeight.Bold else FontWeight.Normal) },
                            { vm.updateOptions { it.copy(sort = s) }; sortMenu = false },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        { Text(if (options.reversed) "Normal order" else "Reverse order") },
                        { vm.updateOptions { it.copy(reversed = !it.reversed) }; sortMenu = false },
                        leadingIcon = { Icon(Icons.Rounded.SwapVert, null) },
                    )
                }
            }
            Filter.entries.forEach { f -> FilterChip(options.filter == f, { vm.updateOptions { it.copy(filter = f) } }, { Text(f.label) }) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickFilter.entries.forEach { q ->
                FilterChip(
                    q in options.quick, { vm.updateOptions { o -> o.copy(quick = if (q in o.quick) o.quick - q else o.quick + q) } }, { Text(q.label) },
                )
            }
        }
        if (cleanup.isNotEmpty() && options.quick.isEmpty()) {
            val ready = cleanup.filter { it.safe }
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(20.dp)).clickable { vm.openPage(Page.Cleanup) },
            ) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CleaningServices, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${cleanup.size} app${if (cleanup.size == 1) " has" else "s have"} a cleaner version",
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            "${formatBytes(cleanup.sumOf { it.app.apkBytes })} to free · ${ready.size} ready, ${cleanup.size - ready.size} to check",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        if (needsScan) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Scan your apps to sort and filter by trackers.", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(vm::scanAll) { Text("Scan") }
                }
            }
        }
        when {
            loading && apps.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            visible.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("No apps match.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                items(visible, key = { it.packageName }) { app ->
                    AppRow(
                        app, trackers[app.packageName], replacedBy[app.packageName], options.sort, selected = app.packageName in selection,
                        selecting = selection.isNotEmpty(),
                        onClick = { if (selection.isNotEmpty()) vm.toggleSelected(app.packageName) else vm.openDetail(app.packageName) },
                        onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); vm.toggleSelected(app.packageName) },
                        onExtract = { onExtract(listOf(app)) },
                        onMark = { markFor = app },
                        onUnmark = { vm.unmarkReplaced(app.packageName) },
                        canMark = targets.isNotEmpty(),
                    )
                }
            }
        }
    }

    markFor?.let { target ->
        AlertDialog(
            onDismissRequest = { markFor = null },
            title = { Text("${target.label} is replaced by…") },
            text = {
                Column {
                    Text("Pick the tool that now does this job. It will show up in Clean up so you can uninstall ${target.label}.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    targets.forEach { (id, where) ->
                        TextButton({ vm.markReplaced(target, id); markFor = null }, Modifier.fillMaxWidth()) { Text(where, Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ markFor = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRow(
    app: AppItem, facts: TrackerFacts?, cleanup: Cleanup?, sort: Sort, selected: Boolean, selecting: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit, onExtract: () -> Unit, onMark: () -> Unit, onUnmark: () -> Unit, canMark: Boolean,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val background by animateColorAsState(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, label = "rowBackground")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            AppIcon(app.packageName)
            val pop by animateFloatAsState(if (selected) 1f else 0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "check")
            if (pop > 0.01f) {
                Icon(
                    Icons.Rounded.CheckCircle, null,
                    Modifier.align(Alignment.BottomEnd).size(22.dp).graphicsLayer { scaleX = pop; scaleY = pop }
                        .background(MaterialTheme.colorScheme.surface, CircleShape),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(app.label, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                facts?.let { TrackerBadge(it) }
            }
            Text(
                buildString {
                    append("v${app.versionName} · ${formatBytes(app.apkBytes)}")
                    if (app.isSplit) append(" · ${app.apkPaths.size} APKs")
                },
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            cleanup?.let { ReplacedBadge(it) }
            Text(
                when (sort) {
                    Sort.Installed -> "Installed ${relativeTime(app.installTime)}"
                    Sort.Permissions -> "${app.dangerousCount} sensitive of ${app.permissionCount} permissions"
                    Sort.TargetSdk -> "Built for Android API ${app.targetSdk} · ${app.source.label}"
                    else -> "Updated ${relativeTime(app.updateTime)} · ${app.source.label}"
                },
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
            )
        }
        if (!selecting) {
            Box {
                IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Actions for ${app.label}") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Extract…") }, { menu = false; onExtract() }, leadingIcon = { Icon(Icons.Rounded.Download, null) })
                    DropdownMenuItem({ Text("Open app") }, { menu = false; openApp(context, app.packageName) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) })
                    DropdownMenuItem({ Text("App info") }, { menu = false; openAppSettings(context, app.packageName) }, leadingIcon = { Icon(Icons.Rounded.Info, null) })
                    DropdownMenuItem({ Text("Copy package name") }, { menu = false; copyText(context, "Package name", app.packageName) }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) })
                    DropdownMenuItem({ Text("Select") }, { menu = false; onLongClick() }, leadingIcon = { Icon(Icons.Rounded.SelectAll, null) })
                    if (cleanup == null && !app.isSystem && canMark) {
                        DropdownMenuItem({ Text("Mark as replaced…") }, { menu = false; onMark() }, leadingIcon = { Icon(Icons.Rounded.CleaningServices, null) })
                    }
                    if (cleanup != null && cleanup.replacement.note?.startsWith("You marked") == true) {
                        DropdownMenuItem({ Text("Unmark as replaced") }, { menu = false; onUnmark() }, leadingIcon = { Icon(Icons.Rounded.Close, null) })
                    }
                    if (!app.isSystem) {
                        DropdownMenuItem(
                            { Text("Uninstall", color = MaterialTheme.colorScheme.error) }, { menu = false; uninstallApp(context, app.packageName) },
                            leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        )
                    }
                }
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}

/** "Replaced · Squeeze" when nothing would be lost, "Mostly replaced · Squeeze" when the original does more. */
@Composable
fun ReplacedBadge(c: Cleanup) {
    val color = if (c.safe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    Text(
        (if (c.safe) "✓ Replaced" else "◐ Mostly replaced") + " · " + Replacements.toolName(c.replacement.toolId),
        Modifier.padding(vertical = 2.dp).clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 1.dp),
        color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
    )
}

@Composable
fun TrackerBadge(facts: TrackerFacts) {
    val color = when {
        facts.adSdks > 0 -> MaterialTheme.colorScheme.error
        facts.sdks.isNotEmpty() -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Text(
        if (facts.sdks.isEmpty()) "clean" else "${facts.sdks.size} SDK${if (facts.sdks.size == 1) "" else "s"}",
        Modifier.padding(start = 8.dp).clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 1.dp),
        color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
    )
}

// ──────────────────────────── Insights tab ───────────────────────

@Composable
private fun InsightsTab(vm: PluckController, onExit: () -> Unit) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    val trackers by vm.trackers.collectAsStateWithLifecycle()
    val progress by vm.scanProgress.collectAsStateWithLifecycle()
    val user = apps.filter { !it.isSystem }
    val scannedUser = user.filter { it.packageName in trackers }
    val withAds = scannedUser.count { (trackers[it.packageName]?.adSdks ?: 0) > 0 }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToolBackButton(onExit)
                Column(Modifier.weight(1f)) {
                    Text("Insights", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Everything here is worked out on your phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ShellActions()
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.fillMaxWidth().padding(24.dp)) {
                    Text("Your apps", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("${user.size}", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        "${formatBytes(user.sumOf { it.apkBytes })} of APKs · ${apps.size - user.size} system apps",
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
        item {
            val cleanup by vm.cleanup.collectAsStateWithLifecycle()
            if (cleanup.isNotEmpty()) {
                InsightCard("Clean up", "Review", { vm.openPage(Page.Cleanup) }) {
                    Text(
                        "${cleanup.size} installed app${if (cleanup.size == 1) " has" else "s have"} a cleaner version here. " +
                            "Uninstalling them frees ${formatBytes(cleanup.sumOf { it.app.apkBytes })}.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    cleanup.take(4).forEach { c -> BarRow(c.app, 0f, if (c.safe) "ready" else "check", vm::openDetail, showBar = false) }
                }
            }
        }
        item {
            InsightCard("Ads & trackers", "Full report", { vm.openPage(Page.Trackers) }) {
                val p = progress
                when {
                    p != null -> {
                        LinearProgressIndicator(progress = { if (p.total > 0) p.done.toFloat() / p.total else 0f }, Modifier.fillMaxWidth())
                        Text("Scanning ${p.done} of ${p.total}…", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(vm::cancelScan) { Text("Stop") }
                    }
                    scannedUser.isEmpty() -> {
                        Text("Find out which of your apps carry advertising and tracking code.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FilledTonalButton(vm::scanAll) { Icon(Icons.Rounded.Radar, null); Spacer(Modifier.width(8.dp)); Text("Scan my apps") }
                    }
                    else -> {
                        Text("$withAds of ${scannedUser.size} scanned apps contain an ad SDK.", style = MaterialTheme.typography.titleSmall)
                        scannedUser.sortedByDescending { trackers[it.packageName]?.sdks?.size ?: 0 }.take(5).forEach { a ->
                            val n = trackers[a.packageName]?.sdks?.size ?: 0
                            BarRow(a, n / 20f, "$n SDKs", vm::openDetail, color = MaterialTheme.colorScheme.error)
                        }
                        if (scannedUser.size < user.size) {
                            TextButton(vm::scanAll) { Text("Scan ${user.size - scannedUser.size} new or updated apps") }
                        }
                    }
                }
            }
        }
        item {
            InsightCard("Biggest apps", "See all", { vm.showApps(Sort.Size) }) {
                val top = user.sortedByDescending { it.apkBytes }.take(5)
                val max = top.firstOrNull()?.apkBytes?.coerceAtLeast(1) ?: 1
                top.forEach { a -> BarRow(a, a.apkBytes.toFloat() / max, formatBytes(a.apkBytes), vm::openDetail) }
            }
        }
        item {
            InsightCard("Where your apps came from", null, null) {
                val counts = user.groupingBy { it.source }.eachCount()
                val colors = mapOf(
                    AppSource.PlayStore to MaterialTheme.colorScheme.primary, AppSource.OtherStore to MaterialTheme.colorScheme.tertiary,
                    AppSource.Sideloaded to MaterialTheme.colorScheme.error, AppSource.System to MaterialTheme.colorScheme.outline,
                )
                Row(Modifier.fillMaxWidth().height(14.dp).clip(CircleShape)) {
                    AppSource.entries.filter { (counts[it] ?: 0) > 0 }.forEach { s ->
                        Box(Modifier.weight(counts.getValue(s).toFloat()).fillMaxHeight().background(colors.getValue(s)))
                    }
                }
                AppSource.entries.filter { (counts[it] ?: 0) > 0 }.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = s == AppSource.Sideloaded || s == AppSource.PlayStore) {
                            vm.showApps(Sort.Name, quick = setOf(if (s == AppSource.Sideloaded) QuickFilter.Sideloaded else QuickFilter.PlayStore))
                        }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(10.dp).background(colors.getValue(s), CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(s.label, Modifier.weight(1f))
                        Text("${counts.getValue(s)}", fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        item {
            InsightCard("Asks for the most sensitive permissions", "See all", { vm.showApps(Sort.Permissions) }) {
                user.sortedByDescending { it.dangerousCount }.take(5).filter { it.dangerousCount > 0 }.forEach { a ->
                    BarRow(a, a.dangerousCount / 15f, "${a.dangerousCount}", vm::openDetail, color = MaterialTheme.colorScheme.tertiary)
                }
                Text(
                    "Permissions Android treats as dangerous that the app requests, whether or not you granted them.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            val old = user.filter { it.targetSdk < 30 }.sortedBy { it.targetSdk }
            InsightCard("Built for older Android", if (old.isEmpty()) null else "See all", { vm.showApps(Sort.TargetSdk) }) {
                if (old.isEmpty()) {
                    Text("All your apps target Android 11 or newer.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("${old.size} app${if (old.size == 1) "" else "s"} target Android 10 or older and skip newer privacy protections.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    old.take(5).forEach { a -> BarRow(a, 0f, "API ${a.targetSdk}", vm::openDetail, showBar = false) }
                }
            }
        }
    }
}

@Composable
private fun InsightCard(title: String, action: String?, onAction: (() -> Unit)?, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (action != null && onAction != null) TextButton(onAction) { Text(action) }
            }
            Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        }
    }
}

@Composable
fun BarRow(
    app: AppItem, fraction: Float, value: String, onClick: (String) -> Unit,
    color: Color = MaterialTheme.colorScheme.primary, showBar: Boolean = true,
) {
    var target by remember { mutableStateOf(0f) }
    LaunchedEffect(fraction) { target = fraction.coerceIn(0.02f, 1f) }
    val animated by animateFloatAsState(target, tween(700), label = "bar")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onClick(app.packageName) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.packageName, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            if (showBar) Box(Modifier.padding(top = 4.dp).fillMaxWidth(animated).height(8.dp).clip(CircleShape).background(color))
        }
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
    }
}
