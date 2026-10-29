package app.ultimate.files

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.ultimate.UltimateApp
import app.ultimate.core.LibraryItem
import app.ultimate.core.ShellActions
import app.ultimate.core.Tool
import app.ultimate.core.ToolBackButton
import app.ultimate.core.ToolLaunch
import app.ultimate.core.formatBytes
import app.ultimate.core.pressScale
import app.ultimate.core.relativeTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Browse, organise and install everything on the phone's storage, with a bin so nothing is lost by accident. */
class FilesTool(private val app: UltimateApp) : Tool {
    override val id = "files"
    override val title = "Files"
    override val tagline = "Browse, organise and install from your storage"
    override val icon = Icons.Rounded.FolderOpen
    override val acceptedMimePrefixes = listOf("application/vnd.android.package-archive")
    override val headline: StateFlow<String> = MutableStateFlow("Everything on your phone").asStateFlow()
    override val library: StateFlow<List<LibraryItem>> = MutableStateFlow<List<LibraryItem>>(emptyList()).asStateFlow()
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = true
    override suspend fun refresh() = Unit
    @Composable override fun Screen(onExit: () -> Unit, launch: ToolLaunch) = FilesScreen(app, onExit, launch)
}

/** Android 11+ grants a file manager "All files access"; Android 10 uses the classic storage permission with legacy storage. */
fun hasAllFiles(context: android.content.Context) =
    if (android.os.Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED

fun allFilesIntent(context: android.content.Context) =
    if (android.os.Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
    else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

fun contentUri(context: android.content.Context, f: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)

private data class Clip(val files: List<File>, val move: Boolean)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun FilesScreen(app: UltimateApp, onExit: () -> Unit, launch: ToolLaunch) {
    val context = LocalContext.current
    val root = remember { Environment.getExternalStorageDirectory() }
    val trash = remember { Trash(root) }
    val installer = remember { ApkInstaller(context) }
    val scope = rememberCoroutineScope()
    var access by remember { mutableStateOf(hasAllFiles(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { access = hasAllFiles(context) }
    var dir by remember { mutableStateOf(root) }
    var entries by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var sort by remember { mutableStateOf(FileSort.Name) }
    var hidden by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<File>()) }
    var clip by remember { mutableStateOf<Clip?>(null) }
    var sheet by remember { mutableStateOf<Entry?>(null) }
    var renaming by remember { mutableStateOf<File?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var showTrash by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var forward by remember { mutableStateOf(true) }

    fun go(to: File) { forward = to.absolutePath.length >= dir.absolutePath.length; dir = to; selected = emptySet(); query = "" }
    fun refreshMedia(paths: List<String>) = MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
    fun op(label: String, block: suspend () -> Unit) = scope.launch {
        runCatching { withContext(Dispatchers.IO) { block() } }
            .onSuccess { reload++ }
            .onFailure { Toast.makeText(context, "$label: ${it.message}", Toast.LENGTH_LONG).show(); app.insights.problem("Files", "$label: ${it.message}") }
    }
    fun delete(files: List<File>) = op("Delete") {
        files.forEach { trash.put(it) }
        refreshMedia(files.map { it.absolutePath })
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Moved ${files.size} to the bin. Restore from ⋮ › Bin for 30 days.", Toast.LENGTH_SHORT).show()
            selected = emptySet()
        }
        app.insights.count("files.delete")
    }
    fun install(f: File) {
        installer.permissionIntent()?.let { context.startActivity(it); Toast.makeText(context, "Allow Ultimate to install apps, then tap the file again.", Toast.LENGTH_LONG).show(); return }
        scope.launch {
            runCatching { installer.install(Uri.fromFile(f), f.name) }
                .onSuccess { app.insights.count("files.install") }
                .onFailure { Toast.makeText(context, "Couldn't install: ${it.message}", Toast.LENGTH_LONG).show(); app.insights.problem("Install", it.message ?: "failed") }
        }
    }

    LaunchedEffect(launch) {
        if (!launch.consumed) { launch.consumed = true; launch.uris.firstOrNull()?.let { u ->
            // An APK shared into Ultimate installs straight away.
            installer.permissionIntent()?.let { context.startActivity(it) } ?: runCatching { installer.install(u, u.lastPathSegment ?: "app.apk") }
        } }
    }
    LaunchedEffect(dir, reload, access, query) {
        if (!access) return@LaunchedEffect
        entries = withContext(Dispatchers.IO) { if (query.length >= 2) FileOps.search(dir, query) else FileOps.list(dir) }
        withContext(Dispatchers.IO) { trash.purgeOlderThan(30) }
    }
    BackHandler {
        when {
            selected.isNotEmpty() -> selected = emptySet()
            query.isNotEmpty() -> query = ""
            dir.canonicalPath != root.canonicalPath -> go(dir.parentFile ?: root)
            else -> onExit()
        }
    }

    Scaffold(
        topBar = {
            AnimatedContent(selected.isNotEmpty(), label = "bar") { selecting ->
                if (selecting) TopAppBar(
                    title = { Text("${selected.size} selected") },
                    navigationIcon = { IconButton({ selected = emptySet() }) { Icon(Icons.Rounded.Close, "Clear selection") } },
                    actions = {
                        IconButton({ selected = entries.map { it.file }.toSet() }) { Icon(Icons.Rounded.SelectAll, "Select all") }
                        IconButton({ shareFiles(context, selected.toList()) }) { Icon(Icons.Rounded.Share, "Share") }
                        IconButton({ clip = Clip(selected.toList(), false); selected = emptySet() }) { Icon(Icons.Rounded.ContentCopy, "Copy") }
                        IconButton({ clip = Clip(selected.toList(), true); selected = emptySet() }) { Icon(Icons.Rounded.DriveFileMove, "Move") }
                        IconButton({ delete(selected.toList()) }) { Icon(Icons.Rounded.Delete, "Delete") }
                    },
                ) else TopAppBar(
                    title = { Text("Files") }, navigationIcon = { ToolBackButton(onExit) },
                    actions = {
                        IconButton({ newFolder = true }, enabled = access) { Icon(Icons.Rounded.CreateNewFolder, "New folder") }
                        Box {
                            IconButton({ sortMenu = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, "Sort") }
                            DropdownMenu(sortMenu, { sortMenu = false }) {
                                FileSort.entries.forEach { s -> DropdownMenuItem({ Text(s.label + if (s == sort) "  ✓" else "") }, { sort = s; sortMenu = false }) }
                                DropdownMenuItem({ Text(if (hidden) "Hide hidden files" else "Show hidden files") }, { hidden = !hidden; sortMenu = false })
                                DropdownMenuItem({ Text("Bin") }, { showTrash = true; sortMenu = false }, leadingIcon = { Icon(Icons.Rounded.RestoreFromTrash, null) })
                            }
                        }
                        ShellActions()
                    },
                )
            }
        },
        bottomBar = {
            clip?.let { c ->
                Surface(tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${if (c.move) "Move" else "Copy"} ${c.files.size} item${if (c.files.size == 1) "" else "s"} here?", Modifier.weight(1f))
                        TextButton({ clip = null }) { Text("Cancel") }
                        Button({
                            val target = dir; clip = null
                            op(if (c.move) "Move" else "Copy") {
                                val made = FileOps.transfer(c.files, target, c.move)
                                refreshMedia((made + if (c.move) c.files else emptyList()).map { it.absolutePath })
                                app.insights.count(if (c.move) "files.move" else "files.copy")
                            }
                        }) { Text("Paste") }
                    }
                }
            }
        },
    ) { pad ->
        if (!access) {
            Column(Modifier.padding(pad).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.FolderOpen, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                Text("See every file on your phone", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("A file manager needs Android's \"All files access\". Ultimate never uploads anything, and deleted files go to a bin for 30 days first.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button({ context.startActivity(allFilesIntent(context)) }) { Text("Allow all files access") }
            }
            return@Scaffold
        }
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true, shape = RoundedCornerShape(24.dp),
                placeholder = { Text("Search in ${if (dir == root) "all files" else dir.name}") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, "Clear") } },
            )
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Internal" to root, "Download" to File(root, "Download"), "Camera" to File(root, "DCIM/Camera"), "Screenshots" to File(root, "DCIM/Screenshots"),
                    "Pictures" to File(root, "Pictures"), "Movies" to File(root, "Movies"), "Music" to File(root, "Music"), "Documents" to File(root, "Documents"))
                    .filter { it.second.exists() }.forEach { (label, f) -> FilterChip(dir.canonicalPath == f.canonicalPath, { go(f) }, { Text(label) }) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                FileModel.crumbs(dir, root).forEachIndexed { i, (label, f) ->
                    if (i > 0) Text(" › ", color = MaterialTheme.colorScheme.outline)
                    Text(label, Modifier.clip(RoundedCornerShape(8.dp)).combinedClickable { go(f) }.padding(4.dp), style = MaterialTheme.typography.labelLarge,
                        color = if (f == dir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (f == dir) FontWeight.Bold else null)
                }
            }
            val shown = FileModel.sorted(entries, sort, hidden || query.isNotEmpty())
            AnimatedContent(dir to query, transitionSpec = {
                val d = if (forward) 1 else -1
                (slideInHorizontally { it / 4 * d } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 * d } + fadeOut())
            }, label = "dir") { place ->
                // Keyed by the folder being shown, so its list keeps its own scroll position while the next slides in.
                androidx.compose.runtime.key(place) {
                if (shown.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (query.isEmpty()) "Empty folder" else "Nothing found", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 96.dp)) {
                    itemsIndexed(shown, key = { _, e -> e.file.absolutePath }) { _, e ->
                        val isSel = e.file in selected
                        Row(
                            Modifier.fillMaxWidth().animateItem().pressScale(0.98f).clip(RoundedCornerShape(14.dp))
                                .background(if (isSel) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                                .combinedClickable(
                                    onClick = {
                                        when {
                                            selected.isNotEmpty() -> selected = if (isSel) selected - e.file else selected + e.file
                                            e.kind == FileKind.Folder -> go(e.file)
                                            e.kind == FileKind.Apk -> install(e.file)
                                            else -> sheet = e
                                        }
                                    },
                                    onLongClick = { selected = selected + e.file },
                                ).padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(contentAlignment = Alignment.BottomEnd) {
                                FileIcon(e)
                                if (isSel) Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                Text(
                                    (if (e.kind == FileKind.Folder) "${e.children} item${if (e.children == 1) "" else "s"}" else formatBytes(e.bytes)) + " · " + relativeTime(e.modified) +
                                        (if (query.isNotEmpty()) " · ${e.file.parentFile?.name}" else "") + (if (e.kind == FileKind.Apk) " · tap to install" else ""),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                )
                            }
                        }
                    }
                }
                }
            }
        }
    }

    sheet?.let { e ->
        ModalBottomSheet(onDismissRequest = { sheet = null }) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FileIcon(e); Spacer(Modifier.width(12.dp))
                    Column { Text(e.file.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Text("${formatBytes(e.bytes)} · ${relativeTime(e.modified)}", style = MaterialTheme.typography.bodySmall) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ sheet = null; open(context, e.file) }) { Text("Open") }
                    FilledTonalButton({
                        sheet = null
                        val u = contentUri(context, e.file)
                        app.shell.offerShared(listOf(u), FileModel.mimeOf(e.file))
                    }) { Text("Ultimate tools") }
                    IconButton({ shareFiles(context, listOf(e.file)) }) { Icon(Icons.Rounded.Share, "Share") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ renaming = e.file; sheet = null }) { Text("Rename") }
                    TextButton({ clip = Clip(listOf(e.file), false); sheet = null }) { Text("Copy") }
                    TextButton({ clip = Clip(listOf(e.file), true); sheet = null }) { Text("Move") }
                    TextButton({ delete(listOf(e.file)); sheet = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
                Text(e.file.parent ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
    renaming?.let { f ->
        var name by remember(f) { mutableStateOf(f.name) }
        AlertDialog(
            onDismissRequest = { renaming = null }, title = { Text("Rename") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = {
                TextButton({
                    val valid = FileModel.validName(name); renaming = null
                    if (valid != null && valid != f.name) op("Rename") {
                        val target = File(f.parentFile, valid)
                        check(!target.exists()) { "$valid already exists" }
                        check(f.renameTo(target)) { "couldn't rename" }
                        refreshMedia(listOf(f.absolutePath, target.absolutePath))
                    }
                }) { Text("Rename") }
            },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancel") } },
        )
    }
    if (newFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newFolder = false }, title = { Text("New folder") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Name") }) },
            confirmButton = { TextButton({ val n = FileModel.validName(name); newFolder = false; if (n != null) op("New folder") { check(File(dir, FileModel.freeName(dir, n)).mkdirs()) { "couldn't create it" } } }) { Text("Create") } },
            dismissButton = { TextButton({ newFolder = false }) { Text("Cancel") } },
        )
    }
    if (showTrash) TrashSheet(trash, onChanged = { reload++ }, onDismiss = { showTrash = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrashSheet(trash: Trash, onChanged: () -> Unit, onDismiss: () -> Unit) {
    var items by remember { mutableStateOf(trash.items()) }
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bin", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (items.isNotEmpty()) TextButton({ items.forEach(trash::purge); items = trash.items(); onChanged() }) { Text("Empty bin", color = MaterialTheme.colorScheme.error) }
            }
            Text("Deleted files stay here for 30 days.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (items.isEmpty()) Text("Nothing in the bin.", Modifier.padding(vertical = 24.dp))
            LazyColumn(Modifier.height(380.dp)) {
                itemsIndexed(items, key = { _, i -> i.file.name }) { _, i ->
                    Row(Modifier.fillMaxWidth().animateItem().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(i.file.name.substringAfter('~'), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            Text("from ${File(i.originalPath).parentFile?.name} · ${relativeTime(i.deletedAt)}", style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton({
                            runCatching { trash.restore(i) }.onSuccess { MediaScannerConnection.scanFile(context, arrayOf(it.absolutePath), null, null) }
                                .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
                            items = trash.items(); onChanged()
                        }) { Text("Restore") }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileIcon(e: Entry) {
    val icon = when (e.kind) {
        FileKind.Folder -> Icons.Rounded.Folder; FileKind.Image -> Icons.Rounded.Image; FileKind.Video -> Icons.Rounded.Movie
        FileKind.Audio -> Icons.Rounded.AudioFile; FileKind.Apk -> Icons.Rounded.Android; FileKind.Archive -> Icons.Rounded.Archive
        FileKind.Document -> Icons.Rounded.Description; FileKind.Other -> Icons.Rounded.InsertDriveFile
    }
    var thumb by remember(e.file) { mutableStateOf<Bitmap?>(null) }
    if (e.kind == FileKind.Image || e.kind == FileKind.Video) LaunchedEffect(e.file) {
        thumb = withContext(Dispatchers.IO) {
            runCatching {
                if (e.kind == FileKind.Image) android.media.ThumbnailUtils.createImageThumbnail(e.file, android.util.Size(160, 160), null)
                else android.media.ThumbnailUtils.createVideoThumbnail(e.file, android.util.Size(160, 160), null)
            }.getOrNull()
        }
    }
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(if (e.kind == FileKind.Folder) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Icon(icon, null, tint = if (e.kind == FileKind.Folder) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun open(context: android.content.Context, f: File) = runCatching {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(contentUri(context, f), FileModel.mimeOf(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Open with"))
}.onFailure { Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show() }

private fun shareFiles(context: android.content.Context, files: List<File>) {
    val uris = files.filter { it.isFile }.map { contentUri(context, it) }
    if (uris.isEmpty()) { Toast.makeText(context, "Folders can't be shared directly", Toast.LENGTH_SHORT).show(); return }
    app.ultimate.core.shareFiles(context, uris)
}
