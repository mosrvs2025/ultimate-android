package app.ultimate.pluck.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Button
import kotlinx.coroutines.launch
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.core.AppIcon
import app.ultimate.core.formatBytes
import app.ultimate.core.uninstallApp
import app.ultimate.pluck.model.AppItem
import app.ultimate.pluck.model.Cleanup

/**
 * Installed apps that one of our tools replaces. Apps with nothing to lose are grouped as ready; apps whose original does
 * more are listed with exactly what is missing, so the decision is the user's.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CleanupScreen(c: PluckController, onBack: () -> Unit, onExtract: (List<AppItem>) -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val list by c.cleanup.collectAsStateWithLifecycle()
    val ready = list.filter { it.safe }
    val check = list.filterNot { it.safe }
    val freed = ready.sumOf { it.app.apkBytes }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Clean up") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
        bottomBar = {
            if (ready.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            { uninstallApp(context, ready.first().app.packageName) }, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp),
                        ) {
                            Icon(Icons.Rounded.Delete, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Uninstall next (${ready.size} left)", style = MaterialTheme.typography.titleMedium)
                        }
                        OutlinedButton(
                            { onExtract(ready.map { it.app }) }, Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(16.dp),
                        ) {
                            Icon(Icons.Rounded.Download, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Back up their APKs first")
                        }
                    }
                }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(28.dp)) {
                    Column(Modifier.fillMaxWidth().padding(24.dp)) {
                        Text("Ready to uninstall", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(
                            "${ready.size} app${if (ready.size == 1) "" else "s"} · ${formatBytes(freed)}",
                            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            "Android asks you to confirm each one. Uninstalling an app also deletes the data it keeps inside itself. " +
                                "Videos and files it saved to your gallery or storage stay.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            if (check.isNotEmpty()) item { BriefCard(c) }
            if (list.isEmpty()) {
                item {
                    Text(
                        "None of your installed apps have a replacement here yet. Mark any app as replaced from its ⋮ menu in the Apps list.",
                        Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (ready.isNotEmpty()) item { Text("Nothing you'd lose", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
            items(ready, key = { "r" + it.app.packageName }) { CleanupRow(it, c) }
            if (check.isNotEmpty()) {
                item {
                    Text("Check first", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    Text(
                        "The original does a few things this doesn't do yet. If you don't use them, say so and it moves up.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(check, key = { "c" + it.app.packageName }) { CleanupRow(it, c) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CleanupRow(item: Cleanup, c: PluckController) {
    val context = LocalContext.current
    val marks by c.marks.collectAsStateWithLifecycle()
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(item.app.packageName, size = 48.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.app.label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text("${formatBytes(item.app.apkBytes)} · v${item.app.versionName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Replaced by ${item.via}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                FilledTonalButton({ uninstallApp(context, item.app.packageName) }) { Text("Uninstall") }
            }
            if (item.replacement.missing.isNotEmpty() && !item.accepted) {
                Text("Not in the replacement yet:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                item.replacement.missing.forEach { Text("•  $it", style = MaterialTheme.typography.bodySmall) }
                item.replacement.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton({ c.acceptMissing(item.app.packageName) }) {
                    Icon(Icons.Rounded.Check, null)
                    Spacer(Modifier.width(6.dp))
                    Text("I don't need those")
                }
                FoundFeatures(item, c)
            } else if (item.accepted && item.replacement.missing.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("You said you don't need what's missing.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton({ c.revokeAccepted(item.app.packageName) }) { Text("Undo") }
                }
            } else if (item.replacement.note != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.replacement.note, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (item.replacement.originalPackage in marks.custom) TextButton({ c.unmarkReplaced(item.app.packageName) }) { Text("Unmark") }
                }
            }
        }
    }
}


/** Copies one brief covering every app still waiting, ready to paste into a coding chat to build what's missing. */
@Composable
private fun BriefCard(c: PluckController) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var busy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("What's left to build", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(
                "Each app below is read for what it does that Ultimate may not. Mark what you need, then copy the list and paste it into a chat to have it built.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({
                    busy = true
                    scope.launch {
                        val text = c.buildBrief(); busy = false
                        app.ultimate.core.copyText(context, "Build brief", text)
                        android.widget.Toast.makeText(context, "Copied. Paste it into your chat", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }, enabled = !busy) { Text(if (busy) "Reading apps…" else "Copy build brief") }
                OutlinedButton({
                    busy = true
                    scope.launch {
                        val text = c.buildBrief(); busy = false
                        context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text), "Share build brief"))
                    }
                }, enabled = !busy) { Text("Share") }
            }
        }
    }
}

/** What reading the original turned up that the replacement may not do, each to mark as needed or not. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FoundFeatures(item: Cleanup, c: PluckController) {
    val scans by c.gaps.collectAsStateWithLifecycle()
    val answers by c.gapAnswers.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(item.app.packageName) { c.scanGaps(item) }
    val scan = scans[item.app.packageName]
    var all by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (scan == null) { Text("Reading the app for anything else it does…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); return }
    val open = scan.open
    if (open.isEmpty()) { Text("Reading the app found nothing else it does.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); return }
    val needCount = open.count { answers[app.ultimate.pluck.model.FeatureGap.key(scan.pkg, it.text)] == app.ultimate.pluck.model.GapMark.Need }
    Text(
        "Found in the app, possibly missing (${open.size}" + (if (needCount > 0) ", $needCount needed" else "") + "):",
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary,
    )
    (if (all) open else open.take(6)).forEach { g ->
        val k = app.ultimate.pluck.model.FeatureGap.key(scan.pkg, g.text)
        val mark = answers[k]
        Column {
            Text(g.text, style = MaterialTheme.typography.bodySmall, color = if (mark == app.ultimate.pluck.model.GapMark.Skip) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.material3.FilterChip(mark == app.ultimate.pluck.model.GapMark.Need, { c.markGap(scan.pkg, g.text, if (mark == app.ultimate.pluck.model.GapMark.Need) null else app.ultimate.pluck.model.GapMark.Need) }, { Text("Need it") })
                androidx.compose.material3.FilterChip(mark == app.ultimate.pluck.model.GapMark.Skip, { c.markGap(scan.pkg, g.text, if (mark == app.ultimate.pluck.model.GapMark.Skip) null else app.ultimate.pluck.model.GapMark.Skip) }, { Text("Don't need") })
                Text(g.kind.label, Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
    if (open.size > 6) TextButton({ all = !all }) { Text(if (all) "Show fewer" else "Show all ${open.size}") }
}
