package app.ultimate.insights

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.ultimate.UltimateApp
import app.ultimate.checkup.engine.Collector
import kotlinx.coroutines.delay

/** Builds the usage report: pick what goes in, add notes, preview it, then share, save or copy. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsSheet(app: UltimateApp, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var includeApps by rememberSaveable { mutableStateOf(true) }
    var notes by rememberSaveable { mutableStateOf("") }
    var usage by remember { mutableStateOf(Collector(app).hasUsageAccess()) }
    var text by remember { mutableStateOf<String?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { usage = Collector(app).hasUsageAccess() }
    // Rebuild a moment after the last change, so typing notes does not rescan the phone on every key.
    LaunchedEffect(includeApps, notes, usage) {
        delay(if (text == null) 0 else 400)
        text = InsightsReport.markdown(InsightsCollector.collect(app, includeApps, notes))
    }
    val full = text?.let { InsightsReport.PROMPT + "\n\n" + it }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Help shape Ultimate", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "A report of which tools you use, any crashes, and the other apps on your phone. Give it to Claude to plan what to build next. Nothing leaves your phone unless you share it.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("What do you wish Ultimate did?") }, minLines = 2, maxLines = 6)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Include my other apps")
                    Text("Names and categories, to find apps Ultimate could replace", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(includeApps, { includeApps = it })
            }
            AnimatedVisibility(includeApps && !usage) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Allow usage access to add how much you use each app.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    TextButton({ context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Allow") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ full?.let { copy(context, it) } }, Modifier.weight(1f), enabled = full != null) { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Copy") }
                OutlinedButton({ full?.let { save(context, it)?.let { Toast.makeText(context, "Saved to Downloads/Ultimate", Toast.LENGTH_SHORT).show() } } }, Modifier.weight(1f), enabled = full != null) { Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Save") }
                Button({ full?.let { share(context, it) } }, Modifier.weight(1f), enabled = full != null) { Icon(Icons.Rounded.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Share") }
            }
            Box(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).verticalScroll(rememberScrollState()).padding(12.dp)) {
                val t = text
                if (t == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
                else SelectionContainer { Text(t, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            }
            if (full != null) Text("About ${full.length / 4} tokens", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Ultimate report", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

/** Writes the report to Downloads/Ultimate as Markdown and returns its uri. */
private fun save(context: Context, text: String): Uri? = runCatching {
    val name = "ultimate-report-${java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())}.md"
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, "text/markdown")
        put(MediaStore.Downloads.RELATIVE_PATH, "Download/Ultimate")
    }
    val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
    context.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
    uri
}.getOrNull()

/** Shares the report as a .md file (with the text too, for apps that only take text). */
private fun share(context: Context, text: String) {
    val uri = save(context, text)
    val send = Intent(Intent.ACTION_SEND).setType("text/markdown").putExtra(Intent.EXTRA_SUBJECT, "Ultimate usage report").putExtra(Intent.EXTRA_TEXT, text)
    if (uri != null) send.putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share report"))
}
