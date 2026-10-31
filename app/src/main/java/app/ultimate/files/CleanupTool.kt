package app.ultimate.files

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.UltimateApp
import app.ultimate.core.LibraryItem
import app.ultimate.core.ShellActions
import app.ultimate.core.Tool
import app.ultimate.core.ToolBackButton
import app.ultimate.core.ToolLaunch
import app.ultimate.core.appear
import app.ultimate.core.formatBytes
import app.ultimate.core.pressScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

/** Free space the fun way: swipe through photos, clear duplicates and bursts, act on smart suggestions. */
class CleanupTool(private val app: UltimateApp) : Tool {
    private val prefs = app.getSharedPreferences("cleanup", Context.MODE_PRIVATE)
    private val _freed = MutableStateFlow(prefs.getLong("freed", 0))
    override val id = "cleanup"
    override val title = "Clean up"
    override val tagline = "Swipe, de-duplicate and free space"
    override val icon = Icons.Rounded.CleaningServices
    override val acceptedMimePrefixes = emptyList<String>()
    override val headline: StateFlow<String> = MutableStateFlow(if (_freed.value > 0) "${formatBytes(_freed.value)} freed so far" else "Swipe your camera roll clean").asStateFlow()
    override val library: StateFlow<List<LibraryItem>> = MutableStateFlow<List<LibraryItem>>(emptyList()).asStateFlow()
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = true
    override suspend fun refresh() = Unit
    @Composable override fun Screen(onExit: () -> Unit, launch: ToolLaunch) = CleanupScreen(app, this, onExit)

    fun addFreed(bytes: Long) { _freed.value += bytes; prefs.edit().putLong("freed", _freed.value).apply() }
    val freed: StateFlow<Long> = _freed.asStateFlow()
}

/** Moves files to Ultimate's bin (restorable for 30 days in Files › Bin) and tells the gallery they are gone. */
private fun binAll(context: Context, files: List<File>): Long {
    val trash = Trash(Environment.getExternalStorageDirectory())
    var freed = 0L
    files.forEach { f -> runCatching { val b = FileOps.size(f); trash.put(f); freed += b } }
    MediaScannerConnection.scanFile(context, files.map { it.absolutePath }.toTypedArray(), null, null)
    return freed
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CleanupScreen(app: UltimateApp, tool: CleanupTool, onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var access by remember { mutableStateOf(hasAllFiles(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { access = hasAllFiles(context) }
    var overview by remember { mutableStateOf<CleanupScanner.Overview?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var reviewing by remember { mutableStateOf<Suggestion?>(null) }
    var swiping by remember { mutableStateOf<List<Item>?>(null) }
    var rescan by remember { mutableIntStateOf(0) }
    val freed by tool.freed.collectAsStateWithLifecycle()

    LaunchedEffect(access, rescan) {
        if (!access) return@LaunchedEffect
        progress = "Starting…"
        overview = withContext(Dispatchers.IO) { CleanupScanner(context).scan({ p -> progress = p }) { first -> overview = first } }
        progress = null
    }
    BackHandler { if (swiping != null) swiping = null else onExit() }

    val swipe = swiping
    if (swipe != null) {
        SwipeDeck(swipe, onDone = { toDelete ->
            swiping = null
            if (toDelete.isNotEmpty()) scope.launch {
                val bytes = withContext(Dispatchers.IO) { binAll(context, toDelete.map { it.file }) }
                tool.addFreed(bytes); app.insights.count("cleanup.swipe"); rescan++
            }
        })
        return
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Clean up") }, navigationIcon = { ToolBackButton(onExit) }, actions = { ShellActions() }) }) { pad ->
        if (!access) Column(Modifier.padding(pad).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Find what's filling your phone", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Clean up looks through your storage on the phone. It needs all files access, and anything you remove waits in the bin for 30 days.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button({ context.startActivity(allFilesIntent(context)) }) { Text("Allow all files access") }
        } else LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { StorageHero(overview, freed, progress) }
            overview?.photos?.takeIf { it.isNotEmpty() }?.let { photos ->
                item {
                    Text("Swipe your photos", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Left to let go, right to keep. Nothing is deleted until you confirm at the end.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    val months = photos.groupBy { CleanupRules.month(it.modified) }.entries.toList()
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(months.take(36), key = { it.key }) { (m, list) -> MonthCard(m, list) { swiping = list.shuffled(Random(m.hashCode())).sortedBy { it.modified } } }
                    }
                }
            }
            val seen = mutableSetOf<Any>()
            overview?.suggestions?.let { list ->
                if (list.isNotEmpty()) item { Text("Ideas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                itemsIndexed(list, key = { _, s -> s.id }) { i, s ->
                    Row(
                        Modifier.fillMaxWidth().animateItem().appear(s.id, seen, i).pressScale(0.97f).clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow).clickable { reviewing = s }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("${s.count} · ${s.detail}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(formatBytes(s.bytes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
                if (list.isEmpty() && progress == null) item { Text("Nothing obvious to clear. Nicely kept.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }

    reviewing?.let { s ->
        ReviewSheet(s, onSqueeze = { files ->
            reviewing = null
            app.tools.firstOrNull { it.id == "squeeze" }?.let { t -> app.shell.open(t, ToolLaunch(files.map { contentUri(context, it) })) }
        }, onDelete = { files ->
            reviewing = null
            scope.launch {
                val bytes = withContext(Dispatchers.IO) { binAll(context, files) }
                tool.addFreed(bytes); app.insights.count("cleanup.${s.id}"); rescan++
            }
        }, onDismiss = { reviewing = null })
    }
}

@Composable
private fun StorageHero(o: CleanupScanner.Overview?, freed: Long, progress: String?) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(o) { if (o != null) { grow.snapTo(0f); grow.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) } }
    val colors = mapOf(
        FileKind.Image to Color(0xFF42A5F5), FileKind.Video to Color(0xFFAB47BC), FileKind.Audio to Color(0xFFFF7043),
        FileKind.Apk to Color(0xFF66BB6A), FileKind.Document to Color(0xFFFFCA28), FileKind.Archive to Color(0xFF8D6E63),
    )
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (o == null) {
            Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp); Spacer(Modifier.width(12.dp)); Text(progress ?: "…") }
            return@Column
        }
        val possible = o.suggestions.sumOf { it.bytes }
        Text("${formatBytes(o.free)} free of ${formatBytes(o.total)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Canvas(Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(8.dp))) {
            drawRect(Color.White.copy(alpha = 0.35f))
            var x = 0f
            colors.forEach { (k, c) -> val w = size.width * (o.byKind[k] ?: 0) / o.total * grow.value; drawRect(c, Offset(x, 0f), androidx.compose.ui.geometry.Size(w, size.height)); x += w }
            val other = (o.total - o.free - colors.keys.sumOf { o.byKind[it] ?: 0 }).coerceAtLeast(0)
            drawRect(Color.Gray, Offset(x, 0f), androidx.compose.ui.geometry.Size(size.width * other / o.total * grow.value, size.height))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(FileKind.Image to "Photos", FileKind.Video to "Videos", FileKind.Audio to "Audio", FileKind.Apk to "Apps").forEach { (k, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(colors.getValue(k))); Spacer(Modifier.width(4.dp))
                    Text("$label ${formatBytes(o.byKind[k] ?: 0)}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        // The first overview arrives in seconds; duplicates and similar shots keep being looked for meanwhile.
        if (progress != null) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(progress, style = MaterialTheme.typography.labelMedium) }
        if (possible > 0) Text("You could free about ${formatBytes(possible)}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        if (freed > 0) Text("Freed so far with Ultimate: ${formatBytes(freed)}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MonthCard(month: String, photos: List<Item>, onClick: () -> Unit) {
    val label = remember(month) { SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(SimpleDateFormat("yyyy-MM", Locale.US).parse(month)!!) }
    Box(Modifier.width(140.dp).aspectRatio(0.8f).pressScale(0.95f).clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick)) {
        Thumb(photos.first().file, Modifier.fillMaxSize(), id = photos.first().mediaId)
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(label, color = Color.White, fontWeight = FontWeight.Bold)
            Text("${photos.size} photos · ${formatBytes(photos.sumOf { it.bytes })}", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Thumb(f: File, modifier: Modifier, size: Int = 360, id: Long = 0) {
    val context = LocalContext.current
    var bmp by remember(f) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(f) {
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                val uri = when {
                    id <= 0 -> null
                    FileModel.kindOfName(f.name) == FileKind.Video -> android.content.ContentUris.withAppendedId(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    else -> android.content.ContentUris.withAppendedId(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                }
                // Indexed media: Android's cached thumbnail, which shows at once instead of decoding the file.
                if (uri != null) context.contentResolver.loadThumbnail(uri, android.util.Size(size, size), null)
                else if (FileModel.kindOf(f) == FileKind.Video) android.media.ThumbnailUtils.createVideoThumbnail(f, android.util.Size(size, size), null)
                else android.media.ThumbnailUtils.createImageThumbnail(f, android.util.Size(size, size), null)
            }.getOrNull()
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) { bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } }
}

/** One idea's files to look over: everything ticked except the copy worth keeping. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewSheet(s: Suggestion, onSqueeze: (List<File>) -> Unit, onDelete: (List<File>) -> Unit, onDismiss: () -> Unit) {
    val all = if (s.groups.isNotEmpty()) s.groups.flatMap { g -> g.items.map { it to (it == g.keep) } } else s.items.map { it to false }
    var chosen by remember(s) { mutableStateOf(all.filterNot { it.second }.map { it.first.file }.toSet()) }
    val bytes = all.filter { it.first.file in chosen }.sumOf { it.first.bytes }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(s.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(if (s.groups.isNotEmpty()) "The one marked Keep stays. Tap any photo to change it." else s.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.height(420.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(all.take(600), key = { it.first.file.path }) { (item, keep) ->
                    val on = item.file in chosen
                    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).clickable { chosen = if (on) chosen - item.file else chosen + item.file }) {
                        if (item.kind == FileKind.Image || item.kind == FileKind.Video) Thumb(item.file, Modifier.fillMaxSize(), 200, item.mediaId)
                        else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).padding(6.dp)) { Text(item.file.name, style = MaterialTheme.typography.labelSmall, maxLines = 4) }
                        Box(Modifier.fillMaxSize().background(if (on) Color.Black.copy(alpha = 0.35f) else Color.Transparent))
                        Icon(if (on) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null, Modifier.align(Alignment.TopEnd).padding(4.dp), tint = if (on) MaterialTheme.colorScheme.error else Color.White)
                        if (keep) Text("Keep", Modifier.align(Alignment.BottomStart).padding(4.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2E9E5B)).padding(horizontal = 6.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
                        Text(formatBytes(item.bytes), Modifier.align(Alignment.BottomEnd).padding(4.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.squeezable) FilledTonalButton({ onSqueeze(chosen.toList()) }, enabled = chosen.isNotEmpty()) { Text("Shrink instead") }
                Button({ onDelete(chosen.toList()) }, Modifier.weight(1f), enabled = chosen.isNotEmpty()) { Text("Move ${chosen.size} to bin · ${formatBytes(bytes)}") }
            }
            Text("Restore anything from Files › Bin for 30 days.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

/**
 * The fun part: one photo at a time. Drag left to let it go, right to keep, with the card tilting and tinting as you drag.
 * Undo goes back one. At the end, one confirmation moves the let-go pile to the bin.
 */
@Composable
private fun SwipeDeck(photos: List<Item>, onDone: (List<Item>) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var index by remember { mutableIntStateOf(0) }
    val decisions = remember { mutableStateOf(listOf<Pair<Item, Boolean>>()) }
    val dx = remember { Animatable(0f) }
    var width by remember { mutableStateOf(1f) }
    val goneBytes = decisions.value.filter { !it.second }.sumOf { it.first.bytes }

    fun decide(keep: Boolean) {
        val item = photos.getOrNull(index) ?: return
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        scope.launch {
            dx.animateTo(if (keep) width * 1.5f else -width * 1.5f, tween(220))
            decisions.value = decisions.value + (item to keep)
            index++
            dx.snapTo(0f)
        }
    }
    fun undo() { if (index > 0) { index--; decisions.value = decisions.value.dropLast(1) } }

    BackHandler { onDone(emptyList()) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        if (index >= photos.size) {
            Celebration(decisions.value.count { !it.second }, goneBytes, onConfirm = { onDone(decisions.value.filter { !it.second }.map { it.first }) }, onCancel = { onDone(emptyList()) }, onUndo = ::undo)
            return@Box
        }
        Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ onDone(emptyList()) }) { Icon(Icons.Rounded.Close, "Stop") }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${index + 1} of ${photos.size}", fontWeight = FontWeight.Bold)
                    Text(if (goneBytes > 0) "${formatBytes(goneBytes)} to free" else "Swipe to sort", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                IconButton(::undo, enabled = index > 0) { Icon(Icons.AutoMirrored.Rounded.Undo, "Undo") }
            }
            androidx.compose.material3.LinearProgressIndicator(progress = { index.toFloat() / photos.size }, Modifier.fillMaxWidth().padding(vertical = 8.dp))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                photos.getOrNull(index + 1)?.let { next -> Thumb(next.file, id = next.mediaId, modifier = Modifier.fillMaxWidth(0.92f).aspectRatio(0.75f).graphicsLayer { scaleX = 0.94f; scaleY = 0.94f; alpha = 0.7f }.clip(RoundedCornerShape(28.dp)), size = 900) }
                val item = photos[index]
                val frac = (dx.value / width).coerceIn(-1f, 1f)
                Box(
                    Modifier.fillMaxWidth(0.92f).aspectRatio(0.75f)
                        .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
                        .graphicsLayer { translationX = dx.value; rotationZ = frac * 14f }
                        .clip(RoundedCornerShape(28.dp))
                        .pointerInput(index) {
                            detectDragGestures(
                                onDragEnd = { if (abs(dx.value) > width * 0.28f) decide(dx.value > 0) else scope.launch { dx.animateTo(0f, spring(Spring.DampingRatioMediumBouncy)) } },
                                onDragCancel = { scope.launch { dx.animateTo(0f) } },
                            ) { change, drag -> change.consume(); scope.launch { dx.snapTo(dx.value + drag.x) } }
                        },
                ) {
                    Thumb(item.file, Modifier.fillMaxSize(), 1200, item.mediaId)
                    Box(Modifier.fillMaxSize().background((if (frac > 0) Color(0xFF2E9E5B) else Color(0xFFE53935)).copy(alpha = abs(frac) * 0.35f)))
                    if (abs(frac) > 0.08f) Text(
                        if (frac > 0) "KEEP" else "LET GO", Modifier.align(if (frac > 0) Alignment.TopStart else Alignment.TopEnd).padding(20.dp).graphicsLayer { alpha = (abs(frac) * 2).coerceAtMost(1f); rotationZ = if (frac > 0) -12f else 12f }
                            .clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.3f)).padding(horizontal = 12.dp, vertical = 4.dp),
                        color = if (frac > 0) Color(0xFF7CF0A0) else Color(0xFFFF8A80), fontSize = 28.sp, fontWeight = FontWeight.Black,
                    )
                    Text(
                        "${SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(item.modified)} · ${formatBytes(item.bytes)}",
                        Modifier.align(Alignment.BottomStart).padding(14.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 8.dp, vertical = 2.dp),
                        color = Color.White, style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Row(Modifier.padding(vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton({ decide(false) }, Modifier.size(68.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFE53935))) { Icon(Icons.Rounded.Close, "Let go", Modifier.size(32.dp)) }
                FilledIconButton({ decide(true) }, Modifier.size(68.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF2E9E5B))) { Icon(Icons.Rounded.Favorite, "Keep", Modifier.size(30.dp)) }
            }
        }
    }
}

/** End of a month: a burst of confetti, the tally, and the one confirmation. */
@Composable
private fun Celebration(count: Int, bytes: Long, onConfirm: () -> Unit, onCancel: () -> Unit, onUndo: () -> Unit) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) { t.animateTo(1f, tween(1800, easing = FastOutSlowInEasing)) }
    val pieces = remember { List(80) { Triple(Random.nextFloat(), Random.nextFloat() * 0.6f + 0.4f, listOf(0xFF42A5F5, 0xFFAB47BC, 0xFFFFCA28, 0xFF66BB6A, 0xFFFF7043)[it % 5]) } }
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            pieces.forEachIndexed { i, (x, speed, c) ->
                val y = size.height * (t.value * speed * 1.2f) - 40f
                drawCircle(Color(c), 6f + (i % 4) * 2f, Offset(size.width * x + kotlin.math.sin(t.value * 12 + i) * 30f, y), alpha = 1f - t.value * 0.6f)
            }
        }
        Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (count == 0) "All keepers!" else "Month sorted", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
            Text(if (count == 0) "Nothing to let go this time." else "$count photo${if (count == 1) "" else "s"} to let go · ${formatBytes(bytes)}", textAlign = TextAlign.Center)
            if (count > 0) Button(onConfirm, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) { Text("Move to bin and free ${formatBytes(bytes)}") }
            Row { TextButton(onUndo) { Text("Go back one") }; TextButton(onCancel) { Text(if (count == 0) "Done" else "Keep them all") } }
            Text("Restore anything from Files › Bin for 30 days.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}
