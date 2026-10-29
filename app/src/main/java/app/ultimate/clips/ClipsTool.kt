package app.ultimate.clips

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.core.LibraryItem
import app.ultimate.core.ShellActions
import app.ultimate.core.Tool
import app.ultimate.core.ToolBackButton
import app.ultimate.core.ToolLaunch
import app.ultimate.core.appear
import app.ultimate.core.copyText
import app.ultimate.core.pressScale
import app.ultimate.core.relativeTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Everything you copied, searchable, with links, numbers and emails one tap from being used. */
class ClipsTool(private val store: ClipStore) : Tool {
    override val id = "clips"
    override val title = "Clipboard"
    override val tagline = "Everything you copied, kept and searchable"
    override val icon = Icons.Rounded.ContentPaste
    override val acceptedMimePrefixes = emptyList<String>()
    override val headline: StateFlow<String> = MutableStateFlow("Copy once, find it later").asStateFlow()
    override val library: StateFlow<List<LibraryItem>> = MutableStateFlow<List<LibraryItem>>(emptyList()).asStateFlow()
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = true
    override suspend fun refresh() = Unit
    @Composable override fun Screen(onExit: () -> Unit, launch: ToolLaunch) = ClipsScreen(store, onExit)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClipsScreen(store: ClipStore, onExit: () -> Unit) {
    BackHandler(onBack = onExit)
    val context = LocalContext.current
    val clips by store.clips.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf<ClipKind?>(null) }
    val shown = clips.filter { (kind == null || it.kind == kind) && (query.isBlank() || it.text.contains(query.trim(), ignoreCase = true)) }
    val seen = remember { mutableSetOf<Any>() }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Clipboard") }, navigationIcon = { ToolBackButton(onExit) }, actions = {
            TextButton({ store.captureNow("button") }) { Text("Grab now") }
            ShellActions()
        })
    }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(28.dp),
                    placeholder = { Text("Search what you copied") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, "Clear") } })
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(kind == null, { kind = null }, { Text("All ${clips.size}") })
                    ClipKind.entries.forEach { k -> val n = clips.count { it.kind == k }; if (n > 0) FilterChip(kind == k, { kind = if (kind == k) null else k }, { Text("${k.label} $n") }) }
                }
            }
            if (clips.isEmpty()) item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("How clips get here", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Android only lets an app read the clipboard while it's on screen, so Ultimate catches clips when you:")
                    Text("• open Ultimate after copying something")
                    Text("• tap \"Save clip\" in quick settings (pull down twice, ✎ edit, drag it in)")
                    Text("• select text anywhere and choose \"Save to Ultimate\"")
                    Text("• share text to Ultimate")
                    Text("Text an app marks as a password is never kept.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            itemsIndexed(shown, key = { _, c -> c.id }) { i, c ->
                Column(
                    Modifier.fillMaxWidth().animateItem().appear(c.id, seen, i).pressScale(0.98f).clip(RoundedCornerShape(18.dp))
                        .background(if (c.pinned) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
                        .clickable { copyText(context, "Clip", c.text) }.padding(14.dp),
                ) {
                    Row {
                        Text(c.kind.label + " · " + relativeTime(c.at), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        if (c.pinned) Icon(Icons.Rounded.PushPin, "Pinned", tint = MaterialTheme.colorScheme.primary)
                    }
                    Text(c.text, maxLines = 6, overflow = TextOverflow.Ellipsis, fontFamily = if (c.kind == ClipKind.Code) FontFamily.Monospace else null)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        val action: Pair<String, Intent>? = when (c.kind) {
                            ClipKind.Link -> "Open" to Intent(Intent.ACTION_VIEW, Uri.parse(if (c.text.trim().startsWith("http")) c.text.trim() else "https://${c.text.trim()}"))
                            ClipKind.Phone -> "Call" to Intent(Intent.ACTION_DIAL, Uri.parse("tel:${c.text.trim()}"))
                            ClipKind.Email -> "Email" to Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${c.text.trim()}"))
                            else -> null
                        }
                        action?.let { (label, intent) -> FilledTonalButton({ runCatching { context.startActivity(intent) } }) { Text(label) } }
                        TextButton({ context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, c.text), "Share")) }) { Text("Share") }
                        TextButton({ store.pin(c.id, !c.pinned) }) { Text(if (c.pinned) "Unpin" else "Pin") }
                        TextButton({ store.delete(c.id) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            if (clips.any { !it.pinned }) item { TextButton({ store.clearUnpinned() }) { Text("Clear all except pinned") } }
            item { Text("Tap a clip to copy it again.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
        }
    }
}
