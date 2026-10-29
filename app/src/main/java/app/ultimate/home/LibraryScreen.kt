package app.ultimate.home

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.UltimateApp
import app.ultimate.core.appear
import androidx.compose.foundation.lazy.itemsIndexed
import app.ultimate.core.AppIcon
import app.ultimate.core.AudioPlayerBar
import app.ultimate.core.LibraryDetailSheet
import app.ultimate.core.LibraryItem
import app.ultimate.core.LibraryRow
import app.ultimate.core.Thumb
import app.ultimate.core.Tool
import app.ultimate.core.Waveform
import app.ultimate.core.copyText
import app.ultimate.core.formatBytes
import app.ultimate.core.relativeTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(app: UltimateApp, pad: PaddingValues) {
    val libraries = remember { combine(app.tools.map { it.library }) { it.toList().flatten() } }
    val all by libraries.collectAsStateWithLifecycle(emptyList())
    val filter by app.shell.libraryFilter.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf<LibraryItem?>(null) }
    LaunchedEffect(Unit) { app.tools.forEach { it.refresh() } }
    val seen = remember { mutableSetOf<Any>() }
    var filterMenu by remember { mutableStateOf(false) }
    var bySize by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val transcripts by app.transcripts.all.collectAsStateWithLifecycle()
    // Search covers names, details and anything said in transcribed recordings and videos.
    val q = query.trim().lowercase()
    val shown = all.filter { filter == null || it.toolId == filter }
        .filter { q.isEmpty() || it.title.lowercase().contains(q) || it.subtitle.lowercase().contains(q) || transcripts[it.uri]?.lowercase()?.contains(q) == true }.sortedByDescending { if (bySize) it.bytes else it.createdAt }

    LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Library", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            Text(
                "${all.size} item${if (all.size == 1) "" else "s"} · ${formatBytes(all.sumOf { it.bytes })}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            androidx.compose.material3.OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(24.dp),
                placeholder = { Text("Search names and what was said") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
            )
        }
        item {
            // One compact control instead of a row of chips to scroll through.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    val current = app.tools.firstOrNull { it.id == filter }
                    FilledTonalButton({ filterMenu = true }) {
                        Icon(current?.icon ?: Icons.Rounded.VideoLibrary, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(current?.title ?: "All tools")
                        Icon(Icons.Rounded.ArrowDropDown, null)
                    }
                    DropdownMenu(filterMenu, { filterMenu = false }) {
                        DropdownMenuItem({ Text("All tools · ${all.size}") }, { app.shell.setLibraryFilter(null); filterMenu = false }, leadingIcon = { Icon(Icons.Rounded.VideoLibrary, null) })
                        app.tools.forEach { t ->
                            val n = all.count { it.toolId == t.id }
                            DropdownMenuItem(
                                { Text("${t.title} · $n") }, { app.shell.setLibraryFilter(t.id); filterMenu = false },
                                leadingIcon = { Icon(t.icon, null) }, enabled = n > 0 || filter == t.id,
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton({ bySize = !bySize }) {
                    Icon(Icons.AutoMirrored.Rounded.Sort, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (bySize) "Largest" else "Newest")
                }
            }
        }
        if (shown.isEmpty()) {
            item(key = "empty-$filter") {
                Column(Modifier.fillMaxWidth().padding(vertical = 72.dp).animateItem().appear("empty-$filter", seen), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.VideoLibrary, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(12.dp))
                    Text(if (q.isEmpty()) "Nothing here yet" else "Nothing matches \"$query\"", style = MaterialTheme.typography.titleMedium)
                    Text("Everything you make shows up here.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
        }
        itemsIndexed(shown, key = { _, it -> it.id }) { i, item ->
            val tool = app.tools.first { it.id == item.toolId }
            // Changing the filter slides rows out and in rather than swapping the list in one frame.
            Box(Modifier.animateItem().appear(item.id, seen, i)) {
                LibraryRow(item, tool, onClick = { if (tool.opensOwnScreen(item)) openItem(app, tool, item) else sheet = item })
            }
        }
    }

    sheet?.let { item ->
        val tool = app.tools.first { it.id == item.toolId }
        LibraryDetailSheet(item, tool, onDismiss = { sheet = null })
    }
}
