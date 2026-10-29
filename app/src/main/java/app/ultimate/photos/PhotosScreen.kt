package app.ultimate.photos

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.core.RecentResults
import app.ultimate.core.ShellActions
import app.ultimate.core.ToolBackButton
import app.ultimate.core.ToolLaunch
import app.ultimate.core.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotosScreen(tool: PhotosTool, onExit: () -> Unit, launch: ToolLaunch) {
    val m = tool.manager
    val run by m.run.collectAsStateWithLifecycle()
    var picked by remember { mutableStateOf<List<PickedPhoto>>(emptyList()) }
    var settings by remember { mutableStateOf(PhotoSettings()) }
    var reading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val withConsent = app.ultimate.core.rememberNotificationConsent()
    fun add(uris: List<Uri>) {
        if (uris.isEmpty()) return
        reading = true
        scope.launch {
            val fresh = withContext(Dispatchers.IO) { uris.filter { u -> picked.none { it.uri == u } }.mapNotNull(m::probe) }
            picked = picked + fresh; reading = false
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { add(it) }
    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    LaunchedEffect(launch) { if (!launch.consumed) { launch.consumed = true; add(launch.uris) } }
    BackHandler { when { run.total > 0 && !run.active -> m.dismiss(); picked.isNotEmpty() -> picked = emptyList(); else -> onExit() } }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Photos") }, navigationIcon = { ToolBackButton(onExit) }, actions = { ShellActions() }) },
        bottomBar = {
            if (picked.isNotEmpty() && run.total == 0) Surface(tonalElevation = 3.dp) {
                Button({ withConsent { m.process(picked, settings); picked = emptyList() } }, Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(56.dp), shape = RoundedCornerShape(18.dp)) {
                    Text(if (picked.size == 1) "Save photo" else "Save ${picked.size} photos", style = MaterialTheme.typography.titleMedium)
                }
            }
        },
    ) { pad ->
        AnimatedContent(
            when { run.total > 0 -> 2; picked.isNotEmpty() || reading -> 1; else -> 0 }, Modifier.padding(pad), label = "photos",
        ) { stage ->
            when (stage) {
                2 -> Done(run, onAgain = m::dismiss)
                1 -> Configure(picked, settings, { settings = it }, onRemove = { u -> picked = picked.filterNot { it.uri == u } }, onAdd = pick)
                else -> Landing(tool, pick)
            }
        }
    }
}

@Composable
private fun Landing(tool: PhotosTool, onPick: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(32.dp))
        Box(Modifier.size(96.dp).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.AddPhotoAlternate, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(16.dp))
        Text("Photos", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Make photos smaller to send or store, crop them for posts, and strip location and camera details before you share. Your originals stay as they are.",
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onPick, Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(20.dp)) { Text("Choose photos", style = MaterialTheme.typography.titleMedium) }
        Spacer(Modifier.height(20.dp))
        RecentResults(tool, "Recent photos", onOpenOwn = {}, limit = 6)
    }
}

@Composable
private fun Configure(picked: List<PickedPhoto>, s: PhotoSettings, onChange: (PhotoSettings) -> Unit, onRemove: (Uri) -> Unit, onAdd: () -> Unit) {
    val plans = picked.map { PhotoPlanner.plan(it.width, it.height, s) }
    val before = picked.sumOf { it.bytes }
    val after = plans.sumOf { PhotoPlanner.estimateBytes(it, s) }
    val shown by animateFloatAsState(after.toFloat(), label = "estimate")
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text("Estimated result", style = MaterialTheme.typography.labelLarge)
                    Text("≈ ${formatBytes(shown.toLong())}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    if (before > 0) Text("from ${formatBytes(before)}" + if (after < before) " · about ${((1 - after.toDouble() / before) * 100).roundToInt()}% smaller" else "", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(picked, key = { it.uri.toString() }) { p ->
                    val plan = PhotoPlanner.plan(p.width, p.height, s)
                    Box(Modifier.animateItem()) {
                        Thumb(p.uri, plan.cropWidth.toFloat() / plan.cropHeight)
                        IconButton({ onRemove(p.uri) }, Modifier.align(Alignment.TopEnd).size(32.dp)) {
                            Icon(Icons.Rounded.Close, "Remove", Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.5f)).padding(2.dp), tint = Color.White)
                        }
                    }
                }
                item { TextButton(onAdd, Modifier.height(140.dp)) { Text("+ Add") } }
            }
        }
        item { Section("Crop") { PhotoCrop.entries.forEach { c -> FilterChip(s.crop == c, { onChange(s.copy(crop = c)) }, { Text(c.label) }) } } }
        item { Section("Size") { PhotoSize.entries.forEach { z -> FilterChip(s.size == z, { onChange(s.copy(size = z)) }, { Text(z.label) }) } } }
        item {
            Column {
                Text("Format", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    PhotoFormat.entries.forEachIndexed { i, f -> SegmentedButton(s.format == f, { onChange(s.copy(format = f)) }, SegmentedButtonDefaults.itemShape(i, PhotoFormat.entries.size)) { Text(f.label) } }
                }
                Text(if (s.format == PhotoFormat.Webp) "Smaller, opens almost everywhere" else "Opens everywhere", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Column {
                Text("Quality ${s.quality}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(s.quality.toFloat(), { onChange(s.copy(quality = it.roundToInt())) }, valueRange = 40f..100f)
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, null, tint = Color(0xFF2E9E5B))
                Spacer(Modifier.width(8.dp))
                Text("Location and camera details are always removed.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Section(title: String, chips: @Composable () -> Unit) = Column {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
}

/** A picked photo shown at the crop it will get, so the change is visible before saving. */
@Composable
private fun Thumb(uri: Uri, ratio: Float) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) { bitmap = withContext(Dispatchers.IO) { runCatching { context.contentResolver.loadThumbnail(uri, android.util.Size(400, 400), null) }.getOrNull() } }
    val r by animateFloatAsState(ratio, label = "crop")
    Box(Modifier.height(140.dp).aspectRatio(r).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

@Composable
private fun Done(run: PhotoManager.Run, onAgain: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (run.active) {
            Text("Saving ${minOf(run.done + 1, run.total)} of ${run.total}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            val p by animateFloatAsState(run.done.toFloat() / run.total.coerceAtLeast(1), label = "p")
            LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth())
            run.current?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text("You can leave; it keeps going.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        } else {
            val saved = run.results.sumOf { (it.originalBytes - it.bytes).coerceAtLeast(0) }
            Text(if (run.results.isEmpty()) "Nothing was saved" else "${run.results.size} saved", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (saved > 0) Text("${formatBytes(saved)} smaller, in Pictures/Ultimate", color = MaterialTheme.colorScheme.primary)
            if (run.failed > 0) Text("${run.failed} couldn't be read", color = MaterialTheme.colorScheme.error)
            run.results.firstOrNull()?.let { app.ultimate.core.SendTo(Uri.parse(it.uri), if (it.name.endsWith(".webp")) "image/webp" else "image/jpeg", "photos") }
            Button(onAgain, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) { Text("Done") }
        }
    }
}
