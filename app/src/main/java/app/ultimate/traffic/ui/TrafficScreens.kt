package app.ultimate.traffic.ui

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.ui.draw.rotate
import androidx.compose.material.icons.rounded.KeyboardArrowDown
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
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.core.AppIcon
import app.ultimate.core.BackBar
import app.ultimate.core.ShellActions
import app.ultimate.core.Terminal
import app.ultimate.core.ToolBackButton
import app.ultimate.core.formatBytes
import app.ultimate.core.formatDuration
import app.ultimate.traffic.engine.FinishedSession
import app.ultimate.traffic.model.AppTraffic
import app.ultimate.traffic.model.DomainKind
import app.ultimate.traffic.model.FlowView
import app.ultimate.traffic.model.HostView
import app.ultimate.traffic.model.TrafficSnapshot
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun TrafficScreen(c: TrafficController, onExit: () -> Unit) {
    val st by c.state.collectAsStateWithLifecycle()
    val sheet by c.hostSheet.collectAsStateWithLifecycle()
    when {
        st.running -> LiveScreen(c, st.startedAt, st.captured, st.snapshot, st.autoStopAt)
        st.finished != null -> SummaryScreen(c, st.finished!!, onExit)
        else -> SetupScreen(c, onExit, st.error)
    }
    sheet?.let { HostSheet(it, c) }
}

fun kindColor(kind: DomainKind, scheme: androidx.compose.material3.ColorScheme): Color = when (kind) {
    DomainKind.Ads -> scheme.error
    DomainKind.Analytics, DomainKind.Crash -> scheme.tertiary
    DomainKind.Other -> scheme.primary
}

// ───────────────────────────────── setup ─────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupScreen(c: TrafficController, onExit: () -> Unit, error: String?) {
    BackHandler(onBack = onExit)
    val context = androidx.compose.ui.platform.LocalContext.current
    val apps by c.installed.collectAsStateWithLifecycle()
    val selected by c.selected.collectAsStateWithLifecycle()
    val showNames by c.showNames.collectAsStateWithLifecycle()
    val blockAds by c.blockAds.collectAsStateWithLifecycle()
    val autoStop by c.autoStop.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { c.loadApps() }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { if (it.resultCode == Activity.RESULT_OK) c.startNow() }
    val withAds = c.appsWithAds
    val shown = apps.filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }.sortedByDescending { it.pkg in selected }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Traffic inspector") }, navigationIcon = { ToolBackButton(onExit) }, actions = { ShellActions() })
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                    Button(
                        onClick = { val i = VpnService.prepare(context); if (i == null) c.startNow() else consent.launch(i) },
                        enabled = selected.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp),
                    ) {
                        Icon(Icons.Rounded.Radar, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (selected.isEmpty()) "Choose apps to inspect" else "Inspect ${selected.size} app${if (selected.size == 1) "" else "s"}", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(28.dp)) {
                    Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("See who your apps talk to", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(
                            "Pick the apps to watch. Their traffic passes through a private tunnel on this phone, with no server and no account, so you can see every site and ad network they contact, how much they send, and which of them are trackers.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            "Only the apps you pick are touched. Everything else on the phone is unaffected, and one tap stops it.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            error?.let {
                item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
            item { Collapsible("What it can and can't see") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("✓  Which servers each app contacts, when, and how much data goes each way", style = MaterialTheme.typography.bodySmall)
                    Text("✓  Site names, from DNS and the start of each secure connection", style = MaterialTheme.typography.bodySmall)
                    Text("✓  Anything sent without encryption, shown as a warning", style = MaterialTheme.typography.bodySmall)
                    Text("✗  What encrypted traffic says. Nothing is decrypted, and no certificate is installed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("✗  Traffic over IPv6 only (most apps still use IPv4)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } }
            item { SafetyCard(autoStop, c::setAutoStop) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SwitchRow("Show site names", "Refuses HTTP/3 (QUIC) so apps use normal HTTPS, where the name is visible.", showNames, c::setShowNames)
                    SwitchRow("Block ad networks", "Refuses their DNS lookups and connections while inspecting. Some apps may show errors or fewer features.", blockAds, c::setBlockAds)
                }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (withAds.isNotEmpty()) FilterChip(false, { c.select(withAds.filter { p -> apps.any { it.pkg == p } }) }, { Text("Add apps with ad SDKs (${withAds.count { p -> apps.any { it.pkg == p } }})") })
                }
                val allShownSelected = shown.isNotEmpty() && shown.all { it.pkg in selected }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, modifier = Modifier.weight(1f), singleLine = true,
                        placeholder = { Text("Search apps") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, "Clear") } }, shape = RoundedCornerShape(28.dp),
                    )
                    TextButton(
                        { if (allShownSelected) c.deselect(shown.map { it.pkg }) else c.select(shown.map { it.pkg }) },
                        enabled = shown.isNotEmpty(),
                    ) { Text(if (allShownSelected) "Deselect all" else "Select all") }
                }
                Text(
                    if (selected.isEmpty()) "No apps selected" else "${selected.size} selected" + if (query.isNotEmpty()) " · buttons act on the ${shown.size} shown" else "",
                    Modifier.padding(start = 4.dp, top = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(shown, key = { it.pkg }) { a ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { c.toggle(a.pkg) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(a.pkg, size = 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        if (a.pkg in withAds) Text("has ad SDKs in its code", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                    Checkbox(a.pkg in selected, { c.toggle(a.pkg) })
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) = Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.width(12.dp))
    Switch(checked, onChange)
}

// ───────────────────────────────── live ──────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun LiveScreen(c: TrafficController, startedAt: Long, captured: List<String>, snap: TrafficSnapshot, autoStopAt: Long) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(startedAt) { while (true) { elapsed = System.currentTimeMillis() - startedAt; delay(500) } }
    val lines by c.manager.log.lines.collectAsStateWithLifecycle()
    val trackerCount = snap.trackerHosts.size

    Scaffold(
        topBar = { TopAppBar(title = { Text("Inspecting") }, actions = { ShellActions() }) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    c::stop, Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(56.dp), shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(8.dp)); Text("Stop inspecting", style = MaterialTheme.typography.titleMedium) }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(28.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${captured.size} app${if (captured.size == 1) "" else "s"} · ${formatDuration(elapsed)}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) { captured.take(5).forEach { AppIcon(it, size = 28.dp) } }
                        }
                        TrafficRadar(snap.hosts, Modifier.padding(vertical = 6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Legend(kindColor(DomainKind.Ads, MaterialTheme.colorScheme), "Ads")
                            Legend(kindColor(DomainKind.Analytics, MaterialTheme.colorScheme), "Analytics")
                            Legend(kindColor(DomainKind.Other, MaterialTheme.colorScheme), "Everything else")
                        }
                    }
                }
            }
            item { LiveStatus(captured.size, snap, Modifier.padding(horizontal = 16.dp)) }
            if (autoStopAt > 0) item {
                val left = (autoStopAt - startedAt - elapsed).coerceAtLeast(0)
                Text(
                    "Stops automatically in ${formatDuration(left)} · your apps return to normal",
                    Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Stat("Sent", snap.up, Modifier.weight(1f))
                    Stat("Received", snap.down, Modifier.weight(1f))
                    CountStat("Trackers", trackerCount, MaterialTheme.colorScheme.error, Modifier.weight(1f))
                    CountStat("Hosts", snap.hosts.size, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                }
            }
            item {
                PrimaryScrollableTabRow(tab, edgePadding = 12.dp) {
                    listOf("Domains", "Apps", "Connections", "DNS", "Log").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
                }
            }
            when (tab) {
                0 -> domains(c, snap)
                1 -> items(snap.apps, key = { it.pkg }) { AppCard(c, it) }
                2 -> items(snap.flows.take(150), key = { it.id }) { FlowRow(c, it) }
                3 -> items(snap.dns, key = { "${it.at}${it.name}${it.type}" }) { d ->
                    DnsRow(d.name, d.label, d.type, d.hit?.sdk, d.hit?.kind, d.blocked || c.isBlocked(d.name)) { c.setBlocked(d.name, !c.isBlocked(d.name)) }
                }
                else -> item { Box(Modifier.padding(horizontal = 16.dp)) { Terminal(lines, live = true, title = "traffic.log", startExpanded = true) } }
            }
        }
    }
}

/** A card whose body folds away: a tap on the header rotates the arrow and eases the content in or out. */
@Composable
fun Collapsible(title: String, startOpen: Boolean = false, body: @Composable () -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(startOpen) }
    val turn by androidx.compose.animation.core.animateFloatAsState(if (open) 180f else 0f, label = "arrow")
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().animateContentSize()) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Icon(Icons.Rounded.KeyboardArrowDown, if (open) "Collapse" else "Expand", Modifier.rotate(turn))
            }
            if (open) Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) { body() }
        }
    }
}

/** The promise that the inspector can't be forgotten: it stops itself, and the apps get their network back the moment it does. */
@Composable
private fun SafetyCard(autoStop: Int, onChange: (Int) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Stop automatically", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "Only the chosen apps are routed through the inspector. If it ever stops, is closed from recent apps, or can't reach the internet, it shuts itself off and they go back to normal.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15 to "15 min", 30 to "30 min", 60 to "1 hour", 180 to "3 hours", 0 to "Never").forEach { (m, label) ->
                    FilterChip(autoStop == m, { onChange(m) }, { Text(label) })
                }
            }
        }
    }
}

/** Says what the numbers mean right now, so zeros read as "nothing yet" rather than "broken". */
@Composable
private fun LiveStatus(apps: Int, snap: TrafficSnapshot, modifier: Modifier) {
    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "listen").animateFloat(
        0.3f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "pulse",
    )
    val waiting = snap.flows.isEmpty()
    val tint = if (waiting) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(tint.copy(alpha = 0.10f)).padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 5.dp).size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(tint.copy(alpha = if (waiting) pulse else 1f)))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(if (waiting) "Listening…" else "Live", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = tint)
            Text(
                if (waiting) {
                    "Waiting for the $apps selected app${if (apps == 1) "" else "s"} to make network requests. Switch to ${if (apps == 1) "it" else "them"} and use ${if (apps == 1) "it" else "them"} " +
                        "while Traffic Inspector runs in the background. Zeros here mean nothing has connected yet."
                } else {
                    "${snap.flows.size} connection${if (snap.flows.size == 1) "" else "s"} seen · ${snap.openFlows} open now. Only the selected apps are counted."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.domains(c: TrafficController, snap: TrafficSnapshot) {
    DomainKind.entries.forEach { kind ->
        val group = snap.hosts.filter { it.kind == kind }
        if (group.isNotEmpty()) {
            item(key = "h-$kind") { SectionHeader(kind, group.size, group.sumOf { it.total }) }
            items(group, key = { "${kind}-${it.host}" }) { HostRow(c, it) }
        }
    }
}

@Composable
private fun SectionHeader(kind: DomainKind, count: Int, bytes: Long) = Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(10.dp).background(kindColor(kind, MaterialTheme.colorScheme), CircleShape))
    Spacer(Modifier.width(8.dp))
    Text("${kind.label} · $count", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Text(formatBytes(bytes), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
}

@Composable
private fun HostRow(c: TrafficController, h: HostView) {
    val color = kindColor(h.kind, MaterialTheme.colorScheme)
    Row(
        Modifier.fillMaxWidth().clickable { c.explain(h.host) }.padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(if (h.openFlows > 0) color else color.copy(alpha = 0.35f), CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(h.host, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(
                listOfNotNull(h.hit?.sdk, "${h.flows} connection${if (h.flows == 1) "" else "s"}", if (h.cleartext) "unencrypted HTTP" else null, if (h.blocked) "blocked" else null).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = if (h.cleartext) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) { h.pkgs.take(3).forEach { AppIcon(it, size = 22.dp) } }
        Spacer(Modifier.width(10.dp))
        Text(formatBytes(h.total), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Legend(color: Color, label: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(8.dp).background(color, CircleShape))
    Spacer(Modifier.width(6.dp))
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A byte counter that rolls up to its value instead of jumping. */
@Composable
private fun Stat(label: String, bytes: Long, modifier: Modifier) {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(bytes) { shown.animateTo(bytes.toFloat(), tween(450, easing = FastOutSlowInEasing)) }
    StatCard(label, formatBytes(shown.value.toLong()), MaterialTheme.colorScheme.onSurface, modifier)
}

@Composable
private fun CountStat(label: String, count: Int, color: Color, modifier: Modifier) {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(count) { shown.animateTo(count.toFloat(), tween(450, easing = FastOutSlowInEasing)) }
    StatCard(label, shown.value.toInt().toString(), color, modifier)
}

@Composable
private fun StatCard(label: String, value: String, color: Color, modifier: Modifier) = Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(18.dp), modifier = modifier,
) {
    Column(Modifier.fillMaxWidth().padding(10.dp)) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ─────────────────────────── apps, connections, DNS ───────────────────────────

@Composable
private fun AppCard(c: TrafficController, a: AppTraffic) {
    var open by rememberSaveable(a.pkg) { mutableStateOf(false) }
    val code = c.codeSdks(a.pkg)
    val seen = a.seenSdks
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(24.dp),
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(a.pkg, size = 44.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("↑ ${formatBytes(a.up)}  ↓ ${formatBytes(a.down)} · ${a.hosts.size} hosts · ${a.flows} connections", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val trackers = a.hosts.count { it.hit != null }
                if (trackers > 0) Text("$trackers trackers", Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.error.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            }
            if (open) {
                if (code != null) {
                    val inCodeNotSeen = code.filter { it !in seen }
                    Text("Code vs network", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    if (seen.isNotEmpty()) {
                        Text("Contacted this session", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        FlowChips(seen.toList(), true)
                    }
                    if (inCodeNotSeen.isNotEmpty()) {
                        Text("In the app's code, not seen yet", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        FlowChips(inCodeNotSeen, false)
                        Text("Not seen yet doesn't mean unused. Some SDKs only talk at certain moments, such as when an ad loads.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (app.ultimate.BuildConfig.HAS_PLUCK) {
                    Text("Scan this app's code with Pluck to compare it with what it actually did.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                a.hosts.take(12).forEach { h -> HostRow(c, h) }
                if (a.hosts.size > 12) Text("and ${a.hosts.size - 12} more hosts", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowChips(names: List<String>, seen: Boolean) = FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    names.forEach {
        Text(
            (if (seen) "✓ " else "") + it,
            Modifier.clip(RoundedCornerShape(8.dp)).background((if (seen) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline).copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium, color = if (seen) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FlowRow(c: TrafficController, f: FlowView) {
    Row(
        Modifier.fillMaxWidth().clickable { c.explain(f.name) }.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(if (f.blocked) MaterialTheme.colorScheme.error else if (f.open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${f.label} · ${f.protocol} ${f.port}" + (f.hostSource?.let { " · name via $it" } ?: "") + (f.note?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            f.cleartext?.let { Text("unencrypted: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Text("↑${formatBytes(f.up)} ↓${formatBytes(f.down)}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun DnsRow(name: String, app: String, type: String, sdk: String?, kind: DomainKind?, blocked: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, color = if (blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Text(listOfNotNull(app, type, sdk, if (blocked) "refused" else null).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onToggle) { Icon(if (blocked) Icons.Rounded.LockOpen else Icons.Rounded.Block, if (blocked) "Allow $name" else "Block $name", tint = if (blocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) }
    }
}

// ──────────────────────────────── summary ────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SummaryScreen(c: TrafficController, s: FinishedSession, onExit: () -> Unit) {
    val snap = s.snapshot
    var saved by rememberSaveable(s.startedAt) { mutableStateOf(false) }
    val ads = snap.hosts.filter { it.kind == DomainKind.Ads }
    val unencrypted = snap.hosts.filter { it.cleartext }
    val count = remember { Animatable(0f) }
    LaunchedEffect(Unit) { count.animateTo(snap.trackerHosts.size.toFloat(), tween(1100, easing = FastOutSlowInEasing)) }

    Scaffold(
        topBar = { BackBar(c.manager::dismissSummary, "Session summary") },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(
                        { if (!saved) { c.manager.saveReport(s); saved = true } }, Modifier.weight(1f).height(52.dp), enabled = !saved && snap.apps.isNotEmpty(), shape = RoundedCornerShape(16.dp),
                    ) { Icon(if (saved) Icons.Rounded.CheckCircle else Icons.Rounded.Save, null); Spacer(Modifier.width(8.dp)); Text(if (saved) "Saved to Library" else "Save report") }
                    Button(c.manager::dismissSummary, Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp)) { Text("Done") }
                }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            s.reason?.let { why ->
                item {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(12.dp))
                        Text(why, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(28.dp)) {
                    Column(Modifier.fillMaxWidth().padding(24.dp)) {
                        Text("${s.labels.joinToString(", ").take(60)} · ${formatDuration(s.endedAt - s.startedAt)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(
                            String.format(Locale.US, "%d tracker host%s", count.value.toInt(), if (count.value.toInt() == 1) "" else "s"),
                            style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            "${snap.hosts.size} hosts in all · ↑ ${formatBytes(snap.up)} ↓ ${formatBytes(snap.down)}" + if (ads.isNotEmpty()) " · ${ads.size} ad network${if (ads.size == 1) "" else "s"}" else "",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        s.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            if (snap.hosts.isEmpty()) item { Text("Nothing was seen. The apps may not have used the network, or used only IPv6.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (unencrypted.isNotEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("${unencrypted.size} unencrypted connection${if (unencrypted.size == 1) "" else "s"}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                            Text("Anyone on the same network can read these: " + unencrypted.take(4).joinToString(", ") { it.host }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }
            items(snap.apps, key = { it.pkg }) { a ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(a.pkg, size = 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(a.label, fontWeight = FontWeight.SemiBold)
                                Text("↑ ${formatBytes(a.up)}  ↓ ${formatBytes(a.down)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        a.hosts.filter { it.hit != null }.take(8).forEach { h -> HostRow(c, h) }
                    }
                }
            }
        }
    }
}
