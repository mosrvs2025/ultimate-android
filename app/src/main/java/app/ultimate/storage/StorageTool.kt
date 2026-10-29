package app.ultimate.storage

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.UltimateApp
import app.ultimate.core.AppIcon
import app.ultimate.core.LibraryItem
import app.ultimate.core.ShellActions
import app.ultimate.core.Tool
import app.ultimate.core.ToolBackButton
import app.ultimate.core.ToolLaunch
import app.ultimate.core.appear
import app.ultimate.core.formatBytes
import app.ultimate.core.pressScale
import app.ultimate.core.relativeTime
import app.ultimate.files.FileKind
import app.ultimate.files.FileModel
import app.ultimate.home.SavingsSheet
import app.ultimate.home.openItem
import app.ultimate.files.Trash
import app.ultimate.files.allFilesIntent
import app.ultimate.files.contentUri
import app.ultimate.files.hasAllFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Where all the space went: by kind, as a map of folders, the biggest files and apps, and how it changes. */
class StorageTool(private val app: UltimateApp) : Tool {
    override val id = "storage"
    override val title = "Storage"
    override val tagline = "See exactly what fills your phone"
    override val icon = Icons.Rounded.Storage
    override val acceptedMimePrefixes = emptyList<String>()
    override val headline: StateFlow<String> = MutableStateFlow("Map of your space").asStateFlow()
    override val library: StateFlow<List<LibraryItem>> = MutableStateFlow<List<LibraryItem>>(emptyList()).asStateFlow()
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = true
    override suspend fun refresh() = Unit
    /** The last scan, kept while the app runs so reopening is instant; refreshed when older than ten minutes. */
    var last: StorageReport? = null
    @Composable override fun Screen(onExit: () -> Unit, launch: ToolLaunch) = StorageScreen(app, this, onExit)
}

val KIND_COLORS = mapOf(
    FileKind.Video to Color(0xFFAB47BC), FileKind.Image to Color(0xFF42A5F5), FileKind.Audio to Color(0xFFFF7043),
    FileKind.Document to Color(0xFFFFCA28), FileKind.Apk to Color(0xFF66BB6A), FileKind.Archive to Color(0xFF8D6E63), FileKind.Other to Color(0xFF78909C),
)
private val KIND_NAMES = mapOf(FileKind.Video to "Videos", FileKind.Image to "Photos", FileKind.Audio to "Audio", FileKind.Document to "Documents",
    FileKind.Apk to "Installers", FileKind.Archive to "Archives", FileKind.Other to "Other files")
private val APPS_COLOR = Color(0xFF26A69A)
private val SYSTEM_COLOR = Color(0xFF9E9E9E)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StorageScreen(app: UltimateApp, tool: StorageTool, onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var access by remember { mutableStateOf(hasAllFiles(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { access = hasAllFiles(context) }
    var report by remember { mutableStateOf(tool.last) }
    var scanned by remember { mutableStateOf<Int?>(null) }
    var path by remember { mutableStateOf(listOf<Folder>()) }
    var kindFilter by remember { mutableStateOf<FileKind?>(null) }
    var picked by remember { mutableStateOf<BigFile?>(null) }
    fun rescan() = scope.launch {
        scanned = 0
        val r = withContext(Dispatchers.IO) { StorageScanner(context).scan { n -> scanned = n } }
        tool.last = r; report = r; path = emptyList(); scanned = null
    }
    LaunchedEffect(access) { if (access && (report == null || System.currentTimeMillis() - report!!.at > 600_000)) rescan() }
    BackHandler { if (path.isNotEmpty()) path = path.dropLast(1) else onExit() }
    val libraries = remember { kotlinx.coroutines.flow.combine(app.tools.map { it.library }) { it.toList().flatten() } }
    val all by libraries.collectAsStateWithLifecycle(emptyList())
    var showSavings by remember { mutableStateOf(false) }
    if (showSavings) SavingsSheet(app.tools, all, onOpen = { t, i -> showSavings = false; openItem(app, t, i) }) { showSavings = false }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Storage") }, navigationIcon = { ToolBackButton(onExit) }, actions = {
            IconButton({ rescan() }, enabled = scanned == null && access) { Icon(Icons.Rounded.Refresh, "Scan again") }
            ShellActions()
        })
    }) { pad ->
        if (!access) {
            Column(Modifier.padding(pad).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("See what fills your phone", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Measuring every folder needs All files access. Nothing leaves your phone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button({ context.startActivity(allFilesIntent(context)) }) { Text("Allow all files access") }
            }
            return@Scaffold
        }
        val r = report
        if (r == null) {
            Column(Modifier.padding(pad).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator(); Spacer(Modifier.height(12.dp))
                Text("Measuring… ${scanned ?: 0} files", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        val seen = remember { mutableSetOf<Any>() }
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Overview(r, kindFilter, onKind = { k -> kindFilter = if (kindFilter == k) null else k }) }
            val saved = all.sumOf { it.savedBytes }
            if (saved > 0) item {
                Row(Modifier.fillMaxWidth().pressScale(0.98f).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.primaryContainer).clickable { showSavings = true }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Ultimate has freed ${formatBytes(saved)} for you", Modifier.weight(1f), color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
                    Text("See how ›", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelLarge)
                }
            }
            if (scanned != null) item { Text("Measuring again… $scanned files", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
            item {
                val here = path.lastOrNull() ?: r.root
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Map of your folders", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(formatBytes(here.bytes), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        (listOf(r.root) + path).forEachIndexed { i, f ->
                            if (i > 0) Text(" › ", color = MaterialTheme.colorScheme.outline)
                            Text(if (i == 0) "All" else f.name, Modifier.clip(RoundedCornerShape(6.dp)).clickable { path = path.take(i) }.padding(4.dp),
                                color = if (i == path.size) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (i == path.size) FontWeight.Bold else null)
                        }
                    }
                    AnimatedContent(here, transitionSpec = { (scaleIn(initialScale = 0.92f) + fadeIn(tween(250))) togetherWith fadeOut(tween(150)) }, label = "map") { folder ->
                        FolderMap(folder) { child -> if (child.children.isNotEmpty() || child.own > 0) path = path + child }
                    }
                    Text("Tap a tile to look inside. Bigger tile, more space.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
            item { Text("Biggest folders", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            items(biggestFolders(r.root).take(8), key = { "f" + it.path }) { f ->
                Row(Modifier.fillMaxWidth().appear(f.path, seen).pressScale(0.98f).clip(RoundedCornerShape(14.dp)).clickable { path = chainTo(r.root, f) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, fontWeight = FontWeight.Medium)
                        Text(f.path.removePrefix(r.root.path).trimStart('/').substringBeforeLast('/', "").ifEmpty { "Internal storage" } + " · ${f.files} files", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Bar(f.bytes.toFloat() / r.root.bytes.coerceAtLeast(1)); Spacer(Modifier.width(10.dp))
                    Text(formatBytes(f.bytes), style = MaterialTheme.typography.labelLarge)
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (kindFilter == null) "Biggest files" else "Biggest ${KIND_NAMES[kindFilter]?.lowercase()}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (kindFilter != null) TextButton({ kindFilter = null }) { Text("All kinds") }
                }
            }
            items(r.biggest.filter { kindFilter == null || it.kind == kindFilter }.take(25), key = { "b" + it.path }) { f -> FileRow(f) { picked = f } }
            if (r.forgotten.isNotEmpty()) {
                item { Text("Big and forgotten", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text("Over 50 MB and untouched for a year", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(r.forgotten.take(10), key = { "o" + it.path }) { f -> FileRow(f) { picked = f } }
            }
            item {
                Text("Biggest apps", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (r.apps == null) TextButton({ context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Allow usage access to see app sizes") }
            }
            items(r.apps.orEmpty().take(12), key = { "a" + it.pkg }) { a ->
                Row(Modifier.fillMaxWidth().pressScale(0.98f).clip(RoundedCornerShape(14.dp))
                    .clickable { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}"))) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(a.pkg, size = 36.dp); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.label, fontWeight = FontWeight.Medium, maxLines = 1)
                        Text("App ${formatBytes(a.app)} · data ${formatBytes(a.data)} · cache ${formatBytes(a.cache)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Text(formatBytes(a.total), style = MaterialTheme.typography.labelLarge)
                }
            }
            if (r.apps != null) item { Text("Tap an app to open its settings, where Clear cache frees its cache safely.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
        }
    }

    picked?.let { f ->
        ModalBottomSheet(onDismissRequest = { picked = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(f.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${formatBytes(f.bytes)} · ${relativeTime(f.modified)}\n${File(f.path).parent}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ picked = null; runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(contentUri(context, File(f.path)), FileModel.mimeOf(File(f.path))).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Open with")) } }) { Text("Open") }
                    if (f.kind == FileKind.Video && app.tools.any { it.id == "squeeze" }) TextButton({
                        picked = null; app.tools.first { it.id == "squeeze" }.let { t -> app.shell.open(t, ToolLaunch(listOf(contentUri(context, File(f.path))))) }
                    }) { Text("Shrink") }
                    TextButton({
                        picked = null
                        scope.launch {
                            withContext(Dispatchers.IO) { Trash(android.os.Environment.getExternalStorageDirectory()).put(File(f.path)) }
                            android.media.MediaScannerConnection.scanFile(context, arrayOf(f.path), null, null)
                            report = report?.let { it.copy(biggest = it.biggest - f, forgotten = it.forgotten - f) }
                            android.widget.Toast.makeText(context, "Moved to the bin (Files › Bin)", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("Move to bin", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

/** Used vs free, split by kind, with the week's change and a 30-day sparkline. Tap a kind to list its biggest files. */
@Composable
private fun Overview(r: StorageReport, selected: FileKind?, onKind: (FileKind) -> Unit) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(r.at) { grow.snapTo(0f); grow.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    val parts = KIND_COLORS.keys.map { Triple(KIND_NAMES.getValue(it), KIND_COLORS.getValue(it), r.byKind[it] ?: 0L) to it } +
        listOfNotNull(r.appsBytes?.let { Triple("Apps", APPS_COLOR, it) to null }, Triple("System & other", SYSTEM_COLOR, r.system) to null)
    val week = Trend.change(r.trend, r.at)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(formatBytes(r.used), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("  used of ${formatBytes(r.total)}", Modifier.padding(bottom = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("${formatBytes(r.free)} free", Modifier.padding(bottom = 4.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        Canvas(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(11.dp))) {
            drawRect(Color.Gray.copy(alpha = 0.18f))
            var x = 0f
            parts.forEach { (p, _) -> val w = size.width * p.third / r.total * grow.value; drawRect(p.second, Offset(x, 0f), Size(w, size.height)); x += w }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            parts.filter { it.first.third > 0 }.sortedByDescending { it.first.third }.forEach { (p, kind) ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).let { m -> if (kind != null) m.clickable { onKind(kind) } else m }
                    .background(if (kind != null && kind == selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent).padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(p.second)); Spacer(Modifier.width(8.dp))
                    Text(p.first, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(formatBytes(p.third), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (week != null) Text((if (week >= 0) "▲ " else "▼ ") + formatBytes(kotlin.math.abs(week)) + (if (week >= 0) " more used than a week ago" else " freed since a week ago"),
            style = MaterialTheme.typography.labelLarge, color = if (week > 1_000_000_000) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        else Text("Check back tomorrow to see how your storage changes.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        if (r.trend.size >= 3) Sparkline(r.trend.takeLast(30).map { it.second })
    }
}

@Composable
private fun Sparkline(values: List<Long>) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(40.dp)) {
        val lo = values.min(); val hi = values.max(); val span = (hi - lo).coerceAtLeast(1)
        val path = Path()
        values.forEachIndexed { i, v -> val x = size.width * i / (values.size - 1); val y = size.height - (v - lo).toFloat() / span * size.height; if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        drawPath(path, color, style = Stroke(2.dp.toPx()))
    }
}

/** The squarified map of a folder's subfolders (and its own files as one tile); tap a tile to go inside. */
@Composable
private fun FolderMap(folder: Folder, onOpen: (Folder) -> Unit) {
    val measurer = rememberTextMeasurer()
    val children = folder.sortedChildren().take(24)
    val shown = children.sumOf { it.bytes }
    val items = children.map { Triple(it.path, it.name, it.bytes) } +
        listOfNotNull((folder.bytes - shown).takeIf { it > 0 }?.let { Triple("__own", if (folder.own > 0 && children.size < 24) "Files here" else "Everything else", it) })
    val tiles = remember(folder) { Treemap.layout(items) }
    val palette = listOf(0xFF5C6BC0, 0xFF26A69A, 0xFFAB47BC, 0xFFFFA726, 0xFF42A5F5, 0xFFEC407A, 0xFF66BB6A, 0xFF8D6E63).map { Color(it) }
    val grow = remember(folder) { Animatable(0f) }
    LaunchedEffect(folder) { grow.animateTo(1f, tween(500, easing = FastOutSlowInEasing)) }
    Box(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(20.dp))) {
        Canvas(Modifier.fillMaxSize().pointerInput(tiles) {
            detectTapGestures { p -> tiles.firstOrNull { t -> p.x / size.width in t.x..(t.x + t.w) && p.y / size.height in t.y..(t.y + t.h) }?.let { t -> children.firstOrNull { it.path == t.key }?.let(onOpen) } }
        }) {
            tiles.forEachIndexed { i, t ->
                val gap = 2.dp.toPx()
                val tl = Offset(t.x * size.width + gap, t.y * size.height + gap)
                val sz = Size((t.w * size.width - gap * 2).coerceAtLeast(0f), (t.h * size.height - gap * 2).coerceAtLeast(0f))
                val base = if (t.key == "__own") Color(0xFF546E7A) else palette[i % palette.size]
                drawRoundRect(base.copy(alpha = 0.35f + 0.5f * grow.value), tl, sz, CornerRadius(8.dp.toPx()))
                if (sz.width > 56.dp.toPx() && sz.height > 30.dp.toPx()) {
                    val style = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    val label = measurer.measure(t.label, style, maxLines = 1, overflow = TextOverflow.Ellipsis, constraints = androidx.compose.ui.unit.Constraints(maxWidth = (sz.width - 12.dp.toPx()).toInt().coerceAtLeast(1)))
                    drawText(label, topLeft = tl + Offset(6.dp.toPx(), 5.dp.toPx()))
                    if (sz.height > 48.dp.toPx()) drawText(measurer.measure(formatBytes(t.bytes), TextStyle(color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp)), topLeft = tl + Offset(6.dp.toPx(), 22.dp.toPx()))
                }
            }
        }
    }
}

@Composable
private fun Bar(frac: Float) = Box(Modifier.width(60.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
    Box(Modifier.fillMaxWidth(frac.coerceIn(0.03f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.primary))
}

@Composable
private fun FileRow(f: BigFile, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().pressScale(0.98f).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(KIND_COLORS[f.kind] ?: Color.Gray)); Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
        Text(f.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(File(f.path).parentFile?.name.orEmpty() + " · " + relativeTime(f.modified), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(formatBytes(f.bytes), style = MaterialTheme.typography.labelLarge)
}

/** Folders up to three levels down, biggest first: the ones worth looking at (not "Internal storage" itself). */
fun biggestFolders(root: Folder): List<Folder> {
    val out = ArrayList<Folder>()
    fun walk(f: Folder, depth: Int) { f.children.values.forEach { c -> out += c; if (depth < 2) walk(c, depth + 1) } }
    walk(root, 0)
    // A folder whose biggest child holds nearly all of it adds nothing; show the child instead.
    return out.filter { f -> f.children.values.none { it.bytes > f.bytes * 0.9 } }.sortedByDescending { it.bytes }
}

/** The chain of folders from the root down to [target], for the map's breadcrumbs. */
fun chainTo(root: Folder, target: Folder): List<Folder> {
    val rel = target.path.removePrefix(root.path).trim('/')
    if (rel.isEmpty()) return emptyList()
    val out = ArrayList<Folder>(); var node = root
    for (part in rel.split('/')) { node = node.children[part] ?: break; out += node }
    return out
}
