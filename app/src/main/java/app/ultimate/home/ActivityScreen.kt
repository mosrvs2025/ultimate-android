package app.ultimate.home

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.UltimateApp
import app.ultimate.core.appear
import app.ultimate.core.pressScale
import androidx.compose.foundation.lazy.itemsIndexed
import app.ultimate.core.Terminal
import app.ultimate.core.WorkSource

/** Every tool's current or last run, with its live terminal. */
@Composable
fun ActivityScreen(app: UltimateApp, pad: PaddingValues) {
    val sources = app.work.sources.filter { s -> app.tools.any { it.id == s.workId } }
    val seen = androidx.compose.runtime.remember { mutableSetOf<Any>() }
    LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Activity", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            Text(
                "Live and recent work (scans, conversions, exports) with its full log. Finished files are in the Library.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        itemsIndexed(sources, key = { _, it -> it.workId }) { i, src -> Box(Modifier.animateItem().appear(src.workId, seen, i)) { SourceCard(app, src) } }
    }
}

@Composable
private fun SourceCard(app: UltimateApp, source: WorkSource) {
    val snap by source.work.collectAsStateWithLifecycle()
    val lines by source.log.lines.collectAsStateWithLifecycle()
    val tool = app.tools.firstOrNull { it.id == source.workId }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().pressScale(0.98f).clip(RoundedCornerShape(16.dp)).let { m -> if (tool != null) m.clickable { app.shell.open(tool) } else m },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(tool?.icon ?: Icons.Rounded.Terminal, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(source.workTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        snap.active -> snap.title + (snap.percent?.let { " · $it%" } ?: "")
                        lines.isEmpty() -> "Idle"
                        else -> snap.doneText ?: "Idle"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (snap.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (snap.active) {
                if (tool != null) FilledTonalButton({ app.shell.open(tool) }) { Text("View live") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(source::cancelWork) { Text("Cancel") }
            } else if (tool != null) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Open ${tool.title}", tint = MaterialTheme.colorScheme.outline)
            }
        }
        val progress by androidx.compose.animation.core.animateFloatAsState((snap.percent ?: 0) / 100f, label = "progress")
        androidx.compose.animation.AnimatedVisibility(snap.active) { LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))) }
        if (lines.isNotEmpty()) {
            Terminal(lines, live = snap.active, title = source.workTitle.lowercase().replace(' ', '-') + ".log", startExpanded = snap.active)
        } else {
            Text(
                "Nothing has run since Ultimate started. Start something in ${tool?.title ?: "this tool"} and you can follow it here, even after leaving the tool.",
                Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Start,
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}
