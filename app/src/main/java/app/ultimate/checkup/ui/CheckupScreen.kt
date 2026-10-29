package app.ultimate.checkup.ui

import androidx.compose.foundation.shape.CircleShape
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.checkup.ai.Ai
import app.ultimate.checkup.ai.AiResult
import app.ultimate.checkup.ai.KeyVault
import app.ultimate.checkup.ai.Providers
import app.ultimate.checkup.engine.CheckupManager
import app.ultimate.checkup.model.Access
import app.ultimate.checkup.model.AppFacts
import app.ultimate.checkup.model.Category
import app.ultimate.checkup.model.Checkup
import app.ultimate.checkup.model.Finding
import app.ultimate.checkup.model.Fix
import app.ultimate.checkup.model.Report
import app.ultimate.checkup.model.ReportOptions
import app.ultimate.checkup.model.Scan
import app.ultimate.checkup.model.Severity
import app.ultimate.core.AppIcon
import app.ultimate.core.ShellActions
import app.ultimate.core.ToolBackButton
import app.ultimate.core.copyText
import app.ultimate.core.relativeTime
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckupScreen(manager: CheckupManager, onExit: () -> Unit) {
    val state by manager.state.collectAsStateWithLifecycle()
    var share by remember { mutableStateOf(false) }
    val withConsent = app.ultimate.core.rememberNotificationConsent()
    var ask by remember { mutableStateOf(false) }
    BackHandler(onBack = onExit)
    // Coming back from granting usage access: scan again so the new details appear without another tap.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e ->
            val done = manager.state.value as? CheckupManager.State.Done
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && done != null && !done.scan.usageAccess && manager.usageAccess()) manager.scan()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Phone checkup") }, navigationIcon = { ToolBackButton(onExit) },
                actions = {
                    if (state is CheckupManager.State.Done) IconButton({ withConsent { manager.scan() } }) { Icon(Icons.Rounded.Refresh, "Scan again") }
                    ShellActions()
                },
            )
        },
        bottomBar = {
            (state as? CheckupManager.State.Done)?.let {
                Surface(tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledTonalButton({ share = true }, Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp)) {
                            Icon(Icons.Rounded.Share, null); Spacer(Modifier.width(8.dp)); Text("Share report")
                        }
                        if (Ai.assistant != null) Button({ ask = true }, Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp)) {
                            Icon(Icons.Rounded.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("Ask AI")
                        }
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (val s = state) {
                CheckupManager.State.Idle -> Intro({ withConsent { manager.scan() } })
                is CheckupManager.State.Scanning -> Scanning(s)
                is CheckupManager.State.Failed -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("The scan stopped", style = MaterialTheme.typography.titleLarge)
                    Text(s.message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Button({ withConsent { manager.scan() } }) { Text("Try again") }
                }
                is CheckupManager.State.Done -> Results(s.scan, manager)
            }
        }
    }
    (state as? CheckupManager.State.Done)?.scan?.let { scan ->
        if (share) ShareSheet(scan, manager.own) { share = false }
        if (ask) AskAiSheet(scan, manager.own) { ask = false }
    }
}

// ───────────────────────────── intro & scanning ─────────────────────────────

@Composable
private fun Intro(onScan: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(0.92f, 1.06f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "s")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier.size((112 * pulse).dp).clip(RoundedCornerShape(36.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.HealthAndSafety, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        Spacer(Modifier.height(24.dp))
        Text("How safe is this phone?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "A full look at security, privacy and every app, in plain language, with a button to fix each thing it finds.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Bullet(Icons.Rounded.Lock, "Security", "Screen lock, encryption, how old your security updates are, USB debugging, root")
        Bullet(Icons.Rounded.Visibility, "Privacy", "Apps that can read your screen, your notifications, your location all the time, your texts")
        Bullet(Icons.Rounded.Apps, "Apps", "Sideloaded, abandoned, built for old Android, carrying ad SDKs, never opened")
        Bullet(Icons.Rounded.PhoneAndroid, "Device", "Battery health and cycles, storage, memory, chip")
        Spacer(Modifier.height(24.dp))
        Button(onScan, Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(20.dp)) {
            Icon(Icons.Rounded.Shield, null); Spacer(Modifier.width(10.dp)); Text("Scan my phone", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(10.dp))
        Text("Runs on the phone. Nothing is uploaded.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Bullet(icon: ImageVector, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Scanning(s: CheckupManager.State.Scanning) {
    val sweep by rememberInfiniteTransition(label = "sweep").animateFloat(0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "a")
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(180.dp)) {
                val stroke = 14.dp.toPx()
                drawArc(track, 0f, 360f, false, style = Stroke(stroke))
                drawArc(primary, sweep, 90f, false, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Icon(Icons.Rounded.Shield, null, Modifier.size(56.dp), tint = primary)
        }
        Spacer(Modifier.height(28.dp))
        Text(s.step, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (s.total > 0) {
            Spacer(Modifier.height(8.dp))
            Text("${s.done} of ${s.total} apps", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(progress = { s.done / s.total.toFloat() }, Modifier.fillMaxWidth(0.7f).height(6.dp).clip(RoundedCornerShape(3.dp)))
        }
    }
}

// ───────────────────────────── results ─────────────────────────────

@Composable
private fun Results(scan: Scan, manager: CheckupManager) {
    val context = LocalContext.current
    var filter by rememberSaveable { mutableStateOf<Category?>(null) }
    var showGood by rememberSaveable { mutableStateOf(false) }
    val issues = scan.findings.filter { it.severity != Severity.Good && (filter == null || it.category == filter) }.sortedBy { it.severity.ordinal }
    val good = scan.findings.filter { it.severity == Severity.Good }
    val apps = scan.apps.associateBy { it.packageName }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScoreHero(scan) }
        item { ChangesCard(manager) }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(filter == null, { filter = null }, { Text("All ${scan.findings.count { it.severity != Severity.Good }}") })
                Category.entries.forEach { c ->
                    val n = scan.findings.count { it.category == c && it.severity != Severity.Good }
                    if (n > 0) FilterChip(filter == c, { filter = c }, { Text("${c.label} $n") })
                }
            }
        }
        items(issues, key = { it.id }) { f -> FindingCard(f, apps, onFix = { open(context, it) }) }
        if (good.isNotEmpty() && filter == null) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().animateContentSize()) {
                    Row(Modifier.fillMaxWidth().clickable { showGood = !showGood }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = GOOD)
                        Spacer(Modifier.width(12.dp))
                        Text("${good.size} thing${if (good.size == 1) "" else "s"} look${if (good.size == 1) "s" else ""} good", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Icon(if (showGood) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                    }
                    if (showGood) good.forEach { g ->
                        Column(Modifier.padding(start = 52.dp, end = 16.dp, bottom = 12.dp)) {
                            Text(g.title, fontWeight = FontWeight.Medium)
                            Text(g.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (filter == null || filter == Category.Privacy) item { PrivacyMap(scan, manager.own) { open(context, Fix.AppSettings(it)) } }
        if (filter == null || filter == Category.Device) item { DeviceCard(scan) }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

private val GOOD = Color(0xFF2E9E5B)

@Composable
private fun severityColor(s: Severity) = when (s) {
    Severity.Critical -> MaterialTheme.colorScheme.error
    Severity.Warning -> Color(0xFFE08A00)
    Severity.Info -> MaterialTheme.colorScheme.primary
    Severity.Good -> GOOD
}

@Composable
private fun ScoreHero(scan: Scan) {
    val shown = remember(scan.at) { Animatable(0f) }
    LaunchedEffect(scan.at) { shown.animateTo(scan.score.toFloat(), tween(1300, easing = FastOutSlowInEasing)) }
    val color = when {
        scan.score >= 90 -> GOOD
        scan.score >= 75 -> Color(0xFF7CB342)
        scan.score >= 50 -> Color(0xFFE08A00)
        else -> MaterialTheme.colorScheme.error
    }
    val track = MaterialTheme.colorScheme.surfaceVariant
    val critical = scan.findings.count { it.severity == Severity.Critical }
    val warnings = scan.findings.count { it.severity == Severity.Warning }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(28.dp)) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(110.dp)) {
                    val stroke = 12.dp.toPx()
                    val inset = stroke / 2
                    val arc = Size(size.width - stroke, size.height - stroke)
                    drawArc(track, 135f, 270f, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
                    drawArc(color, 135f, 270f * shown.value / 100f, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Text("${shown.value.toInt()}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(Checkup.verdict(scan.score), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        critical > 0 -> "$critical serious, $warnings to check"
                        warnings > 0 -> "$warnings thing${if (warnings == 1) "" else "s"} to check"
                        else -> "Nothing serious found"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${scan.apps.count { !it.system }} apps · ${scan.device.manufacturer} ${scan.device.model} · ${relativeTime(scan.at)}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun FindingCard(f: Finding, apps: Map<String, AppFacts>, onFix: (Fix) -> Unit) {
    var open by rememberSaveable(f.id) { mutableStateOf(false) }
    val color = severityColor(f.severity)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).animateContentSize()) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(color))
            Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        when (f.severity) { Severity.Critical -> Icons.Rounded.Error; Severity.Warning -> Icons.Rounded.Warning; else -> Icons.Rounded.Info },
                        null, tint = color, modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.title, fontWeight = FontWeight.SemiBold)
                        Text(f.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (f.apps.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open = !open }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy((-6).dp)) { f.apps.take(7).forEach { AppIcon(it, size = 30.dp) } }
                        Text(if (open) "Hide" else "See ${f.apps.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    if (open) f.apps.forEach { pkg ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onFix(Fix.AppSettings(pkg)) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(pkg, size = 34.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(apps[pkg]?.label ?: pkg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(pkg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "App settings", tint = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
                f.fix?.let { fix -> OutlinedButton({ onFix(fix) }, shape = RoundedCornerShape(12.dp)) { Text(f.fixLabel ?: "Fix") } }
            }
        }
    }
}

@Composable
private fun PrivacyMap(scan: Scan, own: Set<String>, onApp: (String) -> Unit) {
    val map = Checkup.privacyMap(scan.apps, own)
    if (map.isEmpty()) return
    var openAccess by rememberSaveable { mutableStateOf<Access?>(null) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).animateContentSize()) {
            Text("Who can reach what", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val max = map.first().second.size.toFloat()
            map.forEach { (access, holders) ->
                Column(Modifier.fillMaxWidth().clickable { openAccess = if (openAccess == access) null else access }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(access.label, fontWeight = FontWeight.Medium)
                            Text(access.blurb, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${holders.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Icon(if (openAccess == access) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                    }
                    LinearProgressIndicator(progress = { holders.size / max }, Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp).clip(RoundedCornerShape(2.dp)))
                    AnimatedVisibility(openAccess == access) {
                        Column(Modifier.padding(top = 6.dp)) {
                            holders.forEach { a ->
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onApp(a.packageName) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                    AppIcon(a.packageName, size = 30.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Text(a.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("Manage", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(scan: Scan) {
    val context = LocalContext.current
    val d = scan.device
    var open by rememberSaveable { mutableStateOf(false) }
    val rows = listOfNotNull(
        "Model" to "${d.manufacturer} ${d.model}",
        "Android" to "${d.androidVersion} (API ${d.sdk})",
        d.securityPatch?.let { "Security patch" to it },
        d.chip?.let { "Chip" to it },
        "Memory" to "${Checkup.formatSize(d.ramBytes)} · ${Checkup.formatSize(d.ramFreeBytes)} free",
        "Storage" to "${Checkup.formatSize(d.storageBytes)} · ${Checkup.formatSize(d.storageFreeBytes)} free",
        d.batteryPercent?.let { "Battery" to listOfNotNull("$it%", d.batteryHealth, d.batteryTempC?.let { t -> "${t.toInt()} °C" }, d.batteryCycles?.let { c -> "$c cycles" }).joinToString(" · ") },
        "Screen lock" to yesNo(d.screenLock),
        "Encrypted" to yesNo(d.encrypted),
        "Developer options" to yesNo(d.developerOptions),
        "USB debugging" to yesNo(d.usbDebugging),
        d.kernel?.let { "Kernel" to it },
        "Up for" to "${d.uptimeMs / 3_600_000} h",
    )
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().animateContentSize()) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.PhoneAndroid, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text("This phone", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
            if (open) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rows.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { copyText(context, k, v) }) {
                        Text(k, Modifier.weight(0.9f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(v, Modifier.weight(1.4f), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End)
                    }
                }
                Text("Tap a value to copy it.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

private fun yesNo(v: Boolean?) = when (v) { true -> "Yes"; false -> "No"; null -> "Unknown" }

// ───────────────────────────── share & ask ─────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareSheet(scan: Scan, own: Set<String>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var includeApps by rememberSaveable { mutableStateOf(true) }
    val text = remember(scan.at, includeApps) { Report.markdown(scan, ReportOptions(includeApps), own) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Share report", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "A plain-text report you can paste into any AI chat, or keep. It is about ${text.length / 1000 + 1} KB (≈ ${text.length / 4} tokens).",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Include the app list")
                    Text("Names, versions and permissions of your apps", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(includeApps, { includeApps = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton({ copyText(context, "Phone checkup", Report.PROMPT + "\n\n" + text) }, Modifier.weight(1f)) {
                    Icon(Icons.Rounded.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Copy")
                }
                Button({ shareText(context, Report.PROMPT + "\n\n" + text) }, Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Share, null); Spacer(Modifier.width(6.dp)); Text("Share")
                }
            }
            HorizontalDivider()
            Text("Preview", style = MaterialTheme.typography.labelLarge)
            Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).verticalScroll(rememberScrollState()).padding(12.dp)) {
                SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AskAiSheet(scan: Scan, own: Set<String>, onDismiss: () -> Unit) {
    val assistant = Ai.assistant ?: return
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ai", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var providerId by rememberSaveable { mutableStateOf(prefs.getString("provider", "anthropic")!!) }
    val provider = Providers.byId(providerId)
    val vault = remember(providerId) { KeyVault(context, providerId) }
    var key by remember(providerId) { mutableStateOf(vault.load()) }
    var keyDraft by remember(providerId) { mutableStateOf("") }
    var model by remember(providerId) { mutableStateOf(prefs.getString("model.$providerId", null) ?: provider.models.first().id) }
    var question by rememberSaveable { mutableStateOf(Report.PROMPT) }
    var includeApps by rememberSaveable { mutableStateOf(true) }
    var preview by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<AiResult?>(null) }
    val report = remember(scan.at, includeApps) { Report.markdown(scan, ReportOptions(includeApps), own) }
    fun pickModel(m: String) { model = m; prefs.edit().putString("model.$providerId", m).apply() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Ask AI about this phone", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                assistant.providers.forEach { p ->
                    val saved = KeyVault(context, p.id).load() != null
                    FilterChip(providerId == p.id, { providerId = p.id; result = null; prefs.edit().putString("provider", p.id).apply() },
                        { Text(p.label + if (saved) " ✓" else "") })
                }
            }
            if (key == null) {
                Text(
                    "Uses your own ${provider.label} key: you pay ${provider.label} directly (Gemini has a free tier), and nobody else is involved. " +
                        "The key is stored encrypted on this phone. Get one at ${provider.keyUrl}.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(keyDraft, { keyDraft = it.trim() }, Modifier.fillMaxWidth(), label = { Text("${provider.label} API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                Button({ vault.save(keyDraft); key = keyDraft; keyDraft = "" }, enabled = keyDraft.length >= 20) { Text("Save key") }
                if (keyDraft.isNotEmpty() && !keyDraft.startsWith(provider.keyPrefix)) Text("${provider.label} keys usually start with ${provider.keyPrefix}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                return@Column
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                provider.models.forEach { m -> FilterChip(model == m.id, { pickModel(m.id) }, { Text("${m.label} · ${m.blurb}") }) }
            }
            OutlinedTextField(model, { pickModel(it.trim()) }, Modifier.fillMaxWidth(), label = { Text("Model name (edit to use a newer one)") }, singleLine = true)
            OutlinedTextField(question, { question = it }, Modifier.fillMaxWidth(), label = { Text("Your question") }, minLines = 2, maxLines = 5)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("What should I fix first?", "Which apps should I uninstall?", "Is anything suspicious?", "Explain my permissions").forEach { q ->
                    FilterChip(question == q, { question = q }, { Text(q) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Include the app list", Modifier.weight(1f))
                Switch(includeApps, { includeApps = it })
            }
            TextButton({ preview = !preview }) { Text(if (preview) "Hide what will be sent" else "See exactly what will be sent to ${provider.label} (≈ ${report.length / 4} tokens)") }
            if (preview) Box(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).verticalScroll(rememberScrollState()).padding(12.dp)) {
                SelectionContainer { Text("$question\n\n---\n\n$report", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            }
            Button(
                {
                    busy = true; result = null
                    scope.launch { result = assistant.ask(provider, key!!, model, report, question); busy = false }
                },
                Modifier.fillMaxWidth().height(52.dp), enabled = !busy && question.isNotBlank() && model.isNotBlank(), shape = RoundedCornerShape(16.dp),
            ) {
                if (busy) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Thinking…") }
                else { Icon(Icons.Rounded.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("Ask ${provider.label}") }
            }
            when (val r = result) {
                is AiResult.Answer -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        SelectionContainer { Text(r.text, style = MaterialTheme.typography.bodyMedium) }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${r.model} · ${r.inputTokens} in / ${r.outputTokens} out tokens", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            IconButton({ copyText(context, "AI answer", r.text) }) { Icon(Icons.Rounded.ContentCopy, "Copy answer") }
                        }
                    }
                }
                is AiResult.Refused -> Text("The model declined to answer${r.category?.let { " ($it)" } ?: ""}. Try rephrasing the question.", color = MaterialTheme.colorScheme.error)
                is AiResult.Failed -> {
                    Text(r.message, color = MaterialTheme.colorScheme.error)
                    if (r.badKey) TextButton({ vault.forget(); key = null; result = null }) { Text("Enter a different key") }
                }
                null -> Unit
            }
            TextButton({ vault.forget(); key = null; result = null }) { Text("Forget my ${provider.label} key") }
        }
    }
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, "Phone checkup report")
    context.startActivity(Intent.createChooser(send, "Share report"))
}

/** Opens the Android settings screen that fixes a finding, falling back to the main settings screen if the phone has no such page. */
private fun open(context: Context, fix: Fix) {
    val intent = when (fix) {
        Fix.SecuritySettings -> Intent(Settings.ACTION_SECURITY_SETTINGS)
        Fix.UpdateSettings -> Intent("android.settings.SYSTEM_UPDATE_SETTINGS")
        Fix.DeveloperSettings -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        Fix.AccessibilitySettings -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        Fix.NotificationAccessSettings -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        Fix.DeviceAdminSettings -> Intent("android.settings.DEVICE_ADMIN_SETTINGS")
        Fix.UsageAccess -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        Fix.StorageSettings -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
        Fix.BatterySettings -> Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
        is Fix.AppSettings -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", fix.packageName, null))
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}

/** What is different since the previous checkup, worst first; the point of checking regularly. */
@Composable
private fun ChangesCard(manager: CheckupManager) {
    val changes by manager.changes.collectAsStateWithLifecycle()
    val (since, list) = changes ?: return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Since ${app.ultimate.core.relativeTime(since)}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (list.isEmpty()) Text("Nothing changed. Same apps, same access.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            list.take(12).forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (c.bad) MaterialTheme.colorScheme.error else GOOD))
                    Spacer(Modifier.width(10.dp))
                    Text(c.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (list.size > 12) Text("and ${list.size - 12} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}
