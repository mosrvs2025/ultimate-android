package app.ultimate.pluck.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.core.AppIcon
import app.ultimate.pluck.engine.SdkKind
import app.ultimate.pluck.engine.TrackerScanner
import app.ultimate.pluck.model.AppItem
import app.ultimate.pluck.model.TrackerFacts

private enum class ReportSort(val label: String) { Most("Most SDKs"), MostAds("Most ad SDKs"), Name("Name") }

/** Every scanned app and every SDK found, searchable and sortable; any app opens its detail screen. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TrackerReport(c: PluckController, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val apps by c.apps.collectAsStateWithLifecycle()
    val trackers by c.trackers.collectAsStateWithLifecycle()
    val progress by c.scanProgress.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(ReportSort.Most) }
    var kind by rememberSaveable { mutableStateOf<SdkKind?>(null) }
    val requested by c.reportSdk.collectAsStateWithLifecycle()
    var sdk by rememberSaveable(requested) { mutableStateOf(requested) }
    var includeClean by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }

    val catalog = remember { TrackerScanner.catalog.associateBy { it.name } }
    val scanned = apps.filter { it.packageName in trackers }.map { it to trackers.getValue(it.packageName) }
    val unscannedUser = apps.count { !it.isSystem && it.packageName !in trackers }
    val withAds = scanned.count { it.second.adSdks > 0 }
    val findings = scanned.sumOf { it.second.sdks.size }

    val rows = scanned.filter { (a, f) ->
        (includeClean || f.sdks.isNotEmpty()) &&
            (query.isBlank() || a.label.contains(query, true) || a.packageName.contains(query, true) || f.sdks.any { it.contains(query, true) }) &&
            (kind == null || f.sdks.any { catalog[it]?.kind == kind }) &&
            (sdk == null || sdk in f.sdks)
    }.let { list ->
        when (sort) {
            ReportSort.Most -> list.sortedWith(compareByDescending<Pair<AppItem, TrackerFacts>> { it.second.sdks.size }.thenBy { it.first.label.lowercase() })
            ReportSort.MostAds -> list.sortedWith(compareByDescending<Pair<AppItem, TrackerFacts>> { it.second.adSdks }.thenByDescending { it.second.sdks.size })
            ReportSort.Name -> list.sortedBy { it.first.label.lowercase() }
        }
    }
    val sdkCounts = scanned.flatMap { it.second.sdks }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ads & trackers") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = { IconButton(c::scanAll, enabled = progress == null) { Icon(Icons.Rounded.Radar, "Scan new and updated apps") } },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.padding(16.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        Text("$withAds of ${scanned.size}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text("scanned apps contain an ad SDK", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "$findings SDK findings · ${sdkCounts.size} different SDKs",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        progress?.let { p ->
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(progress = { if (p.total > 0) p.done.toFloat() / p.total else 0f }, Modifier.fillMaxWidth())
                            Text("Scanning ${p.done} of ${p.total}…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                        if (progress == null && unscannedUser > 0) {
                            Spacer(Modifier.height(8.dp))
                            FilledTonalButton(c::scanAll) { Text("Scan $unscannedUser new or updated app${if (unscannedUser == 1) "" else "s"}") }
                        }
                    }
                }
            }
            item {
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(tab == 0, { tab = 0 }, text = { Text("Apps (${rows.size})") })
                    Tab(tab == 1, { tab = 1 }, text = { Text("SDKs (${sdkCounts.size})") })
                }
            }
            if (tab == 0) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Search apps or SDK names") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                            trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, "Clear") } },
                            singleLine = true, shape = RoundedCornerShape(28.dp),
                        )
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box {
                                FilterChip(true, { sortMenu = true }, { Text(sort.label) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Sort, null, Modifier.size(18.dp)) })
                                DropdownMenu(sortMenu, { sortMenu = false }) {
                                    ReportSort.entries.forEach { s -> DropdownMenuItem({ Text(s.label) }, { sort = s; sortMenu = false }) }
                                }
                            }
                            FilterChip(kind == null, { kind = null }, { Text("All kinds") })
                            SdkKind.entries.forEach { k -> FilterChip(kind == k, { kind = if (kind == k) null else k }, { Text(k.label) }) }
                            FilterChip(includeClean, { includeClean = !includeClean }, { Text("Include clean apps") })
                        }
                        sdk?.let { name ->
                            InputChip(true, { sdk = null }, { Text("Only apps with $name") }, trailingIcon = { Icon(Icons.Rounded.Close, "Clear", Modifier.size(18.dp)) })
                        }
                    }
                }
                if (rows.isEmpty()) {
                    item {
                        Text(
                            if (scanned.isEmpty()) "Nothing scanned yet. Tap the radar to scan your apps." else "No apps match.",
                            Modifier.fillMaxWidth().padding(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(rows, key = { it.first.packageName }) { (app, facts) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { c.openDetail(app.packageName) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(app.packageName, size = 44.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(app.label, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                TrackerBadge(facts)
                            }
                            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                facts.sdks.sortedBy { catalog[it]?.kind?.ordinal ?: 9 }.forEach { name ->
                                    val ad = catalog[name]?.kind == SdkKind.Ads
                                    Text(
                                        name,
                                        Modifier.clip(RoundedCornerShape(6.dp)).clickable { c.explainSdk(name) }
                                            .background((if (ad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline).copy(alpha = 0.12f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (ad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
                    }
                }
            } else {
                item {
                    Text(
                        "How many of your scanned apps include each SDK. Tap one to see those apps.",
                        Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(sdkCounts, key = { it.key }) { (name, count) ->
                    val k = catalog[name]?.kind
                    val color = if (k == SdkKind.Ads) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
                    Row(
                        Modifier.fillMaxWidth().clickable { sdk = name; tab = 0 }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(name, fontWeight = FontWeight.Medium)
                            Text(k?.label ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Box(
                                Modifier.padding(top = 4.dp).fillMaxWidth((count.toFloat() / scanned.size.coerceAtLeast(1)).coerceIn(0.02f, 1f))
                                    .height(8.dp).clip(CircleShape).background(color),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text("$count app${if (count == 1) "" else "s"}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge)
                        IconButton({ c.explainSdk(name) }) { Icon(Icons.Rounded.Info, "What is $name?", tint = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
        }
    }
}
