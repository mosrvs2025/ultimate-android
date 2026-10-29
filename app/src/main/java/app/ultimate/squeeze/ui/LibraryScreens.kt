package app.ultimate.squeeze.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import app.ultimate.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.ultimate.squeeze.model.LibraryEntry
import app.ultimate.squeeze.model.Source
import app.ultimate.squeeze.model.bitsPerPixel
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

// ───────────────────────────── Detail ───────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    entry: LibraryEntry,
    beforeFile: File,
    afterFile: File,
    clipFile: File,
    onBack: () -> Unit,
    onRemove: suspend (deleteFile: Boolean) -> Boolean,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    val uri = Uri.parse(entry.outputUri)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = { IconButton({ confirm = true }) { Icon(Icons.Rounded.Delete, "Delete") } },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.material3.Button({ play(context, uri) }, Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Play")
                    }
                    androidx.compose.material3.FilledTonalButton({ share(context, uri) }, Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Rounded.Share, null); Spacer(Modifier.width(8.dp)); Text("Share")
                    }
                }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { CompareBlock(entry, beforeFile, afterFile, clipFile, Modifier.fillMaxWidth()) }
            item { SavingsHero(entry) }
            item { app.ultimate.core.SendTo(uri, "video/mp4", "squeeze") }
            item { StatsTable(entry) }
            item { EncodeStats(entry) }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Remove from library?") },
            text = { Text("You can keep the compressed video in your gallery or delete it too.") },
            confirmButton = {
                TextButton({
                    confirm = false
                    scope.launch {
                        if (onRemove(true)) onBack() else Toast.makeText(context, "Couldn't delete the file. Remove it from your gallery instead.", Toast.LENGTH_LONG).show()
                    }
                }) { Text("Delete video", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                Row {
                    TextButton({ confirm = false }) { Text("Cancel") }
                    TextButton({ confirm = false; scope.launch { onRemove(false); onBack() } }) { Text("Keep video") }
                }
            },
        )
    }
}

@Composable
private fun SavingsHero(e: LibraryEntry) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(28.dp)) {
        Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${percentSaved(e.before.sizeBytes, e.after.sizeBytes)}% smaller", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("${formatBytes(e.before.sizeBytes)} → ${formatBytes(e.after.sizeBytes)}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(ratio(e), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("compression", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

private fun ratio(e: LibraryEntry) = String.format(Locale.US, "%.1f×", e.before.sizeBytes.toDouble() / max(e.after.sizeBytes, 1))

private data class StatRow(val label: String, val before: String, val after: String, val change: String? = null)

private fun statRows(e: LibraryEntry): List<StatRow> {
    val b = e.before
    val a = e.after
    fun pct(x: Number, y: Number): String? {
        val from = x.toDouble()
        if (from <= 0) return null
        val d = ((y.toDouble() / from - 1) * 100).roundToInt()
        return if (d == 0) null else "${if (d > 0) "+" else "−"}${abs(d)}%"
    }
    return buildList {
        add(StatRow("Size", formatBytes(b.sizeBytes), formatBytes(a.sizeBytes), pct(b.sizeBytes, a.sizeBytes)))
        add(StatRow("Resolution", "${b.width}×${b.height}", "${a.width}×${a.height}", pct(b.width.toLong() * b.height, a.width.toLong() * a.height)?.let { "$it px" }))
        add(StatRow("Frame rate", fpsText(b.fps), fpsText(a.fps)))
        add(StatRow("Codec", codecName(b.mime), codecName(a.mime)))
        add(StatRow("Video bitrate", formatMbps(b.videoBitrate), formatMbps(a.videoBitrate), pct(b.videoBitrate, a.videoBitrate)))
        add(StatRow("Audio", audioText(b), audioText(a)))
        add(StatRow("Bits / pixel", bppText(b), bppText(a)))
        add(StatRow("Dynamic range", if (b.isHdr) "HDR" else "SDR", if (a.isHdr) "HDR" else "SDR"))
        add(StatRow("Duration", formatDuration(b.durationMs), formatDuration(a.durationMs)))
    }
}

private fun fpsText(f: Float) = if (abs(f - f.roundToInt()) < 0.05f) "${f.roundToInt()} fps" else String.format(Locale.US, "%.2f fps", f)
private fun audioText(s: Source) = if (!s.hasAudio) "None" else if (s.audioBitrate > 0) "${s.audioBitrate / 1000} kbps" else "Yes"
private fun bppText(s: Source) = s.bitsPerPixel().let { if (it > 0) String.format(Locale.US, "%.3f", it) else "—" }

@Composable
private fun StatsTable(e: LibraryEntry) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Spacer(Modifier.weight(1.1f))
                HeaderCell("Original", Modifier.weight(1f))
                HeaderCell("Squeezed", Modifier.weight(1f))
            }
            statRows(e).forEachIndexed { i, r ->
                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.label, Modifier.weight(1.1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(r.before, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End)
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        val changed = r.before != r.after
                        Text(
                            r.after, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End,
                            fontWeight = if (changed) FontWeight.Bold else FontWeight.Normal,
                            color = if (changed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                        r.change?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
            Text(
                "Bits / pixel is how much data each pixel of each frame gets. Lower means smaller files; newer codecs hold quality at lower values.",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier) =
    Text(text, modifier, textAlign = TextAlign.End, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)

@Composable
private fun EncodeStats(e: LibraryEntry) {
    val speed = if (e.encodeMs > 0) e.before.durationMs.toDouble() / e.encodeMs else 0.0
    val diff = if (e.plannedBytes > 0) ((e.after.sizeBytes.toDouble() / e.plannedBytes - 1) * 100).roundToInt() else 0
    val accuracy = when {
        e.plannedBytes <= 0 -> "—"
        diff == 0 -> "spot on"
        diff < 0 -> "${abs(diff)}% under estimate"
        else -> "$diff% over estimate"
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Encode", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            EncodeLine("Settings", e.label)
            EncodeLine("Time taken", formatDuration(e.encodeMs))
            EncodeLine("Speed", String.format(Locale.US, "%.1f× real time", speed))
            EncodeLine("Space freed", formatBytes(e.savedBytes))
            EncodeLine("Estimate", "${formatBytes(e.plannedBytes)} planned · $accuracy")
            EncodeLine("Average data rate", "${formatBytes(e.after.sizeBytes * 1000 / max(e.after.durationMs, 1))}/s")
        }
    }
}

@Composable
private fun EncodeLine(label: String, value: String) = Row(Modifier.fillMaxWidth()) {
    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End)
}

// ──────────────────────── Before / after slider ─────────────────────

@Composable
private fun CompareSlider(before: Bitmap, after: Bitmap, modifier: Modifier = Modifier) {
    var fraction by remember { mutableFloatStateOf(0.5f) }
    val shape = RoundedCornerShape(20.dp)
    BoxWithConstraints(
        modifier
            .aspectRatio(before.width.toFloat() / before.height)
            .clip(shape)
            .pointerInput(Unit) { detectTapGestures { fraction = (it.x / size.width).coerceIn(0f, 1f) } }
            .pointerInput(Unit) {
                detectDragGestures(onDragStart = { fraction = (it.x / size.width).coerceIn(0f, 1f) }) { change, _ ->
                    change.consume()
                    fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            },
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        Image(after.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Image(
            before.asImageBitmap(), null,
            Modifier.fillMaxSize().clip(GenericShape { size, _ -> addRect(Rect(0f, 0f, size.width * fraction, size.height)) }),
            contentScale = ContentScale.Crop,
        )
        Box(Modifier.offset { IntOffset((widthPx * fraction).roundToInt() - 1.5.dp.roundToPx(), 0) }.width(3.dp).fillMaxSize().background(Color.White))
        Box(
            Modifier.offset { IntOffset((widthPx * fraction).roundToInt() - 18.dp.roundToPx(), 0) }.align(Alignment.CenterStart)
                .size(36.dp).clip(CircleShape).background(Color.White),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.SwapHoriz, null, tint = Color.Black) }
        Tag("Original", Modifier.align(Alignment.TopStart))
        Tag("Squeezed", Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun Tag(text: String, modifier: Modifier) = Text(
    text, modifier.padding(10.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 3.dp),
    color = Color.White, style = MaterialTheme.typography.labelMedium,
)

@Composable
private fun rememberFrame(file: File, available: Boolean): Bitmap? {
    var bitmap by remember(file.path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file.path, available) {
        if (available) bitmap = withContext(Dispatchers.IO) { runCatching { android.graphics.BitmapFactory.decodeFile(file.path) }.getOrNull() }
    }
    return bitmap
}

private fun play(context: Context, uri: Uri) = runCatching {
    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
}

private fun share(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share video"))
}

private fun codecName(mime: String) = when (mime.lowercase()) {
    "video/hevc" -> "HEVC"
    "video/avc" -> "H.264"
    "video/av01" -> "AV1"
    "video/x-vnd.on2.vp9" -> "VP9"
    "video/x-vnd.on2.vp8" -> "VP8"
    "video/mp4v-es" -> "MPEG-4"
    else -> mime.substringAfter('/').uppercase().ifBlank { "?" }
}

/** Live synced playback when the original clip was saved, otherwise the still-frame slider, otherwise nothing. */
@Composable
fun CompareBlock(entry: LibraryEntry, beforeFile: File, afterFile: File, clipFile: File, modifier: Modifier = Modifier) {
    val before = rememberFrame(beforeFile, entry.hasFrames)
    val after = rememberFrame(afterFile, entry.hasFrames)
    val aspect = (entry.after.width.toFloat() / max(entry.after.height, 1)).coerceIn(0.4f, 2.5f)
    when {
        entry.hasClip && clipFile.exists() -> LiveCompare(clipFile, Uri.parse(entry.outputUri), entry.clipStartMs, aspect, before, after, modifier)
        before != null && after != null -> Column(modifier) {
            CompareSlider(before, after, Modifier.fillMaxWidth())
            Text(
                "Drag to compare the same moment, before and after.",
                Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
