package app.ultimate.pluck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.core.AppIcon
import app.ultimate.pluck.engine.SdkKind
import app.ultimate.pluck.engine.TrackerScanner
import app.ultimate.pluck.model.SdkKnowledge

/** Explains one known SDK in plain language and shows which of your own apps contain it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SdkSheet(name: String, c: PluckController) {
    val trackers by c.trackers.collectAsStateWithLifecycle()
    val apps by c.apps.collectAsStateWithLifecycle()
    val info = SdkKnowledge.info(name)
    val sdk = TrackerScanner.catalog.firstOrNull { it.name == name }
    val withIt = apps.filter { trackers[it.packageName]?.sdks?.contains(name) == true }.sortedBy { it.label.lowercase() }
    val scanned = apps.count { it.packageName in trackers }
    val kindColor = if (sdk?.kind == SdkKind.Ads) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary

    ModalBottomSheet(onDismissRequest = { c.explainSdk(null) }) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column {
                Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        info?.role?.label ?: sdk?.kind?.label ?: "SDK",
                        Modifier.clip(RoundedCornerShape(6.dp)).background(kindColor.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 2.dp),
                        color = kindColor, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    )
                    info?.company?.let {
                        Spacer(Modifier.width(8.dp))
                        Text("by $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (info == null) {
                Text("No description is available for this SDK yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Section("What it is", info.what)
                Section("What it typically collects", info.collects)
                Section("Why apps include it", info.why)
                Text(
                    "This describes the SDK in general. The app's developer decides what it is configured to send, and what permissions you grant limits what it can reach.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                )
            }
            HorizontalDivider()
            Text(
                if (scanned == 0) "Scan your apps to see which ones contain it."
                else "In ${withIt.size} of your $scanned scanned app${if (scanned == 1) "" else "s"}",
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            )
            if (withIt.isNotEmpty()) {
                Column {
                    withIt.take(8).forEach { a ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { c.explainSdk(null); c.openDetail(a.packageName) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(a.packageName, size = 36.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(a.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (withIt.size > 8) Text("and ${withIt.size - 8} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalButton({ c.openReport(name) }, Modifier.fillMaxWidth().height(48.dp)) {
                    Icon(Icons.Rounded.Radar, null)
                    Spacer(Modifier.width(8.dp))
                    Text("See all ${withIt.size} in the report")
                }
            }
            Text(
                "To limit ad tracking system-wide, reset or delete your advertising ID in Android's privacy settings (usually Settings → Privacy → Ads). " +
                    "That works for every app at once.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Section(title: String, body: String) = Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Text(body, style = MaterialTheme.typography.bodyMedium)
}
