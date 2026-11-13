package app.ultimate.storage

import android.app.Activity
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ultimate.UltimateApp
import app.ultimate.core.AppIcon
import app.ultimate.core.formatBytes
import app.ultimate.pluck.model.Replacements
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads the installed apps and what the phone knows about their use. The second value is whether usage history was available at all (it needs Usage access). */
internal fun loadInstalledApps(context: Context, app: UltimateApp, sizes: Map<String, Long>): Pair<List<InstalledApp>, Boolean> {
    val pm = context.packageManager; val now = System.currentTimeMillis()
    val infos = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).mapNotNull { it.activityInfo?.applicationInfo }.distinctBy { it.packageName }
        .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != context.packageName && it.packageName !in Replacements.ours }
    val stats = runCatching { context.getSystemService(UsageStatsManager::class.java).queryAndAggregateUsageStats(now - UnusedApps.WINDOW_DAYS * UnusedApps.DAY, now) }.getOrDefault(emptyMap())
    return infos.map { i ->
        InstalledApp(i.packageName, pm.getApplicationLabel(i).toString(), sizes[i.packageName] ?: runCatching { java.io.File(i.sourceDir).length() }.getOrDefault(0L),
            stats[i.packageName]?.lastTimeUsed ?: 0L, runCatching { pm.getPackageInfo(i.packageName, 0).firstInstallTime }.getOrDefault(now),
            Replacements.forPackage(i.packageName)?.let { r -> app.tools.firstOrNull { it.id == r.toolId }?.title })
    } to stats.isNotEmpty()
}

/**
 * Apps you could drop: installed apps that have not been opened for a while, or that an Ultimate tool already does, biggest first. Tick the ones to go and
 * Android asks about each in turn (it always asks; this app never removes anything on its own). Apps that might hold codes, messages or money are
 * flagged and never ticked for you.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppsToDropSheet(app: UltimateApp, sizes: List<AppSize>?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var loaded by remember { mutableStateOf<Pair<List<Droppable>, Boolean>?>(null) }
    LaunchedEffect(sizes) {
        loaded = withContext(Dispatchers.IO) {
            val (apps, known) = loadInstalledApps(context, app, sizes.orEmpty().associate { it.pkg to it.total })
            UnusedApps.candidates(apps, System.currentTimeMillis(), known) to known
        }
    }
    val picked = remember { mutableStateListOf<String>() }
    val removed = remember { mutableStateListOf<String>() }
    var queue by remember { mutableStateOf(listOf<String>()) }
    var current by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        current?.let { if (r.resultCode == Activity.RESULT_OK) { removed.add(it); picked.remove(it) } }
        current = null
    }
    // One app at a time: Android shows its own confirmation for each and cannot be asked for several at once.
    LaunchedEffect(queue, current) {
        if (current == null && queue.isNotEmpty()) {
            val next = queue.first(); queue = queue.drop(1); current = next
            launcher.launch(Intent(Intent.ACTION_DELETE, Uri.parse("package:$next")).putExtra(Intent.EXTRA_RETURN_RESULT, true))
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        val data = loaded
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Apps you could drop", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            when {
                data == null -> Text("Looking at what you open…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                !data.second -> {
                    Text("To tell which apps you never open, Ultimate needs Usage access. It only reads when each app was last opened, on this phone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button({ context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Allow usage access") }
                }
                else -> {
                    val shown = data.first.filter { it.app.pkg !in removed }
                    if (shown.isEmpty()) Text(if (removed.isEmpty()) "Nothing here: everything you have installed was opened in the last month." else "Done. ${removed.size} app${if (removed.size == 1) "" else "s"} removed.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else {
                        val chosen = shown.filter { it.app.pkg in picked }
                        Text("${shown.size} app${if (shown.size == 1) "" else "s"}, ${formatBytes(UnusedApps.bytes(shown))} between them. Android asks about each one you remove.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton({ picked.clear(); picked.addAll(UnusedApps.suggested(shown).map { it.app.pkg }) }) { Text("Pick the long-idle ones") }
                            if (picked.isNotEmpty()) TextButton({ picked.clear() }) { Text("Clear") }
                        }
                        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            items(shown, key = { it.app.pkg }) { d ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(d.app.pkg in picked, { on -> if (on) picked.add(d.app.pkg) else picked.remove(d.app.pkg) })
                                    AppIcon(d.app.pkg, size = 34.dp); Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(d.app.label, fontWeight = FontWeight.Medium, maxLines = 1)
                                        Text(d.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                        if (d.careful) Text("Check first: it may hold codes, messages or money you can't get back", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                    }
                                    Text(formatBytes(d.app.sizeBytes), style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Button({ queue = chosen.map { it.app.pkg } }, Modifier.fillMaxWidth(), enabled = chosen.isNotEmpty() && current == null && queue.isEmpty()) {
                            Text(if (current != null || queue.isNotEmpty()) "Removing… (answer Android's questions)" else if (chosen.isEmpty()) "Pick apps to remove" else "Remove ${chosen.size} · frees ${formatBytes(UnusedApps.bytes(chosen))}")
                        }
                        Text("Sizes are the app plus its data. You can reinstall any of them later from where you got it; nothing is removed unless you confirm it on Android's screen.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
}
