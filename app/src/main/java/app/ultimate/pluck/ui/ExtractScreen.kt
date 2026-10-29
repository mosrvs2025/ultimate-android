package app.ultimate.pluck.ui

import app.ultimate.core.*
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.pluck.engine.ExtractJob
import app.ultimate.pluck.engine.ExtractState
import app.ultimate.pluck.engine.JobStatus

@Composable
fun ExtractScreen(vm: PluckController, state: ExtractState) {
    val context = LocalContext.current
    val view = LocalView.current
    val share by vm.shareWhenDone.collectAsStateWithLifecycle()
    BackHandler(enabled = !state.active, onBack = vm::dismissResults)
    val saved = state.saved

    // One celebratory tap when everything lands, and the share sheet if that is what was asked for.
    LaunchedEffect(state.active) {
        if (!state.active && saved.isNotEmpty()) {
            view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
            if (share) {
                vm.shareWhenDone.value = false
                shareFiles(context, saved.map { android.net.Uri.parse(it.uri) })
            }
        }
    }

    Scaffold(
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.active) {
                        OutlinedButton(vm::cancelExtraction, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) { Text("Cancel") }
                    } else {
                        if (saved.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                FilledTonalButton({ shareFiles(context, saved.map { android.net.Uri.parse(it.uri) }) }, Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp)) {
                                    Icon(Icons.Rounded.Share, null); Spacer(Modifier.width(8.dp)); Text("Share")
                                }
                                FilledTonalButton({ openDownloads(context) }, Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp)) {
                                    Icon(Icons.Rounded.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Downloads")
                                }
                            }
                        }
                        Button(vm::dismissResults, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) { Text("Done", style = MaterialTheme.typography.titleMedium) }
                    }
                }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Hero(state) }
            item {
                val lines by vm.log.lines.collectAsStateWithLifecycle()
                Terminal(lines, live = state.active)
            }
            items(state.jobs, key = { it.app.packageName }) { JobRow(it) }
        }
    }
}

@Composable
private fun Hero(state: ExtractState) {
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val done = state.saved
    val total = state.jobs.size.coerceAtLeast(1)
    val finished = state.jobs.count { it.status !is JobStatus.Queued && it.status !is JobStatus.Running }
    val run = state.jobs.firstOrNull { it.status is JobStatus.Running }?.status as? JobStatus.Running
    val overall = ((finished + if (run != null && run.total > 0) run.done.toFloat() / run.total else 0f) / total).coerceIn(0f, 1f)
    val shown by animateFloatAsState(overall, tween(300), label = "overall")

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (state.active) {
                Text("Plucking", style = MaterialTheme.typography.labelLarge, color = onContainer)
                Text("${(shown * 100).toInt()}%", style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold, color = onContainer)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { shown }, modifier = Modifier.fillMaxWidth().height(10.dp).clip(CircleShape),
                    trackColor = onContainer.copy(alpha = 0.15f),
                )
                Spacer(Modifier.height(8.dp))
                Text("App ${minOf(finished + 1, total)} of $total. You can leave Pluck; it keeps going.", style = MaterialTheme.typography.bodySmall, color = onContainer, textAlign = TextAlign.Center)
            } else if (done.isEmpty()) {
                Icon(Icons.Rounded.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.height(56.dp))
                Text("Nothing was saved", style = MaterialTheme.typography.titleLarge, color = onContainer)
            } else {
                SuccessCheck(88.dp, MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text(
                    if (done.size == 1) done.first().label else "${done.size} apps",
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = onContainer, textAlign = TextAlign.Center,
                )
                Text("${formatBytes(done.sumOf { it.bytes })} saved to Downloads/Pluck", style = MaterialTheme.typography.titleSmall, color = onContainer)
                val failed = state.jobs.count { it.status is JobStatus.Failed }
                if (failed > 0) Text("$failed couldn't be extracted", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun JobRow(job: ExtractJob) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(job.app.packageName, size = 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(job.app.label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            when (val s = job.status) {
                JobStatus.Queued -> Sub("Waiting · ${formatBytes(job.app.apkBytes)}")
                is JobStatus.Running -> {
                    val f = if (s.total > 0) s.done.toFloat() / s.total else 0f
                    LinearProgressIndicator(progress = { f }, Modifier.fillMaxWidth().padding(top = 6.dp).height(6.dp).clip(CircleShape))
                    Sub("${formatBytes(s.done)} of ${formatBytes(s.total)}")
                }
                is JobStatus.Done -> Text("${s.entry.displayName} · ${formatBytes(s.entry.bytes)}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                is JobStatus.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                JobStatus.Cancelled -> Sub("Cancelled")
            }
        }
    }
}

@Composable
private fun Sub(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
