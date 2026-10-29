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
