package app.ultimate.pluck.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import app.ultimate.pluck.model.FileNames
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AssistChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ultimate.core.formatBytes
import app.ultimate.pluck.engine.ApkComposition
import app.ultimate.pluck.model.AppItem

private val LIMITS = listOf(16_000_000L to "16 MB", 25_000_000L to "25 MB", 30_000_000L to "30 MB", 100_000_000L to "100 MB")

/** Choose how to extract: the installable APK, or a compact pack for sending. Shows sizes before anything is written. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtractSheet(items: List<AppItem>, c: PluckController, onDismiss: () -> Unit, onConfirm: (compact: Boolean, share: Boolean) -> Unit) {
    var compact by rememberSaveable { mutableStateOf(false) }
    var share by rememberSaveable { mutableStateOf(false) }
    // Per-app estimates, so the size limits can be judged on the biggest single file.
    var estimates by remember(items) { mutableStateOf<List<ApkComposition>?>(null) }
    LaunchedEffect(items) { estimates = items.map { c.composition(listOf(it)) } }
    val total = items.sumOf { it.apkBytes }
    val compactTotal = estimates?.sumOf { it.estimatedCompact }
    val anySplit = items.any { it.isSplit }
    val largest = if (compact) estimates?.maxOfOrNull { it.estimatedCompact } else items.maxOfOrNull { it.apkBytes }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (items.size == 1) "Extract ${items.first().label}" else "Extract ${items.size} apps",
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            )
            Option(
                selected = !compact, onClick = { compact = false }, icon = { Icon(Icons.Rounded.Android, null) },
                title = "APK", size = formatBytes(total),
                body = if (anySplit) "Installs as-is. Apps made of several APKs are bundled as one .apks for a split installer such as SAI."
                else "Installs as-is, exactly as on this phone.",
            )
            Option(
                selected = compact, onClick = { compact = true }, icon = { Icon(Icons.Rounded.FolderZip, null) },
                title = "Compact", size = compactTotal?.let { "≈ ${formatBytes(it)}  (−${((1 - it.toDouble() / total.coerceAtLeast(1)) * 100).toInt()}%)" } ?: "measuring…",
                body = "Packed for sending. Unzip it to get the untouched APK" + if (anySplit) "; split apps stay an .apks that SAI installs directly." else ".",
            )

            largest?.let { biggest ->
                Text(
                    if (items.size == 1) "Fits under" else "Largest file fits under",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    LIMITS.forEach { (limit, label) ->
                        val fits = biggest <= limit
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (fits) Icons.Rounded.Check else Icons.Rounded.Close, null, Modifier.size(16.dp),
                                tint = if (fits) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(label, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }

            NameSection(items.first(), c, if (compact) (if (items.first().isSplit) ".apks" else ".zip") else if (items.first().isSplit) ".apks" else ".apk")

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Open the share sheet when done", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(share, { share = it })
            }
            Button({ onConfirm(compact, share) }, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) {
                Text(if (items.size == 1) "Extract" else "Extract ${items.size} apps", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "Why can't the APK itself be smaller? Android keeps code and libraries uncompressed inside an APK so apps start fast, " +
                    "and the signature covers every byte. Changing it would stop it installing or updating as the same app. Compact packs it " +
                    "without changing a byte.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The file-name pattern, with one-tap placeholders and a live preview of the first file. */
@Composable
private fun NameSection(first: AppItem, c: PluckController, ext: String) {
    val template by c.nameTemplate.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open = !open }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("File name", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(FileNames.render(template, first) + ext, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(if (open) "Done" else "Change", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
        if (open) {
            OutlinedTextField(template, c::setNameTemplate, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Pattern") })
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FileNames.TOKENS.forEach { (token, label) -> AssistChip({ c.setNameTemplate(template + token) }, { Text("+ $label") }) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FileNames.PRESETS.forEach { p -> FilterChip(template == p, { c.setNameTemplate(p) }, { Text(p) }) }
            }
        }
    }
}

@Composable
private fun Option(selected: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit, title: String, size: String, body: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            RadioButton(selected, onClick)
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    icon()
                    Spacer(Modifier.width(8.dp))
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(size, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(4.dp))
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
