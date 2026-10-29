package app.ultimate.audio.ui

import android.net.Uri
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.verticalScroll
import app.ultimate.core.RecentResults
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.audio.engine.AudioJob
import app.ultimate.audio.engine.AudioQueue
import app.ultimate.audio.engine.AudioStatus
import app.ultimate.audio.engine.PickedVideo
import app.ultimate.audio.model.AudioPlanner
import app.ultimate.audio.model.AudioSettings
import app.ultimate.audio.model.AudioSource
import app.ultimate.audio.model.OutFormat
import app.ultimate.core.AudioPlayerBar
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.foundation.clickable
import app.ultimate.core.pressScale
import app.ultimate.core.appear
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.rounded.Movie
import app.ultimate.core.BackBar
import app.ultimate.core.JobLog
import app.ultimate.core.Terminal
import app.ultimate.core.SuccessCheck
import app.ultimate.core.ToolLaunch
import app.ultimate.core.VideoThumb
import app.ultimate.core.Waveform
import app.ultimate.core.formatBytes
import app.ultimate.core.formatDuration
import app.ultimate.core.rememberNotificationConsent
import app.ultimate.core.shareFiles
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun AudioScreen(c: AudioController, onExit: () -> Unit, launch: ToolLaunch, tool: app.ultimate.core.Tool) {
    val queue by c.queue.collectAsStateWithLifecycle()
    val picked by c.picked.collectAsStateWithLifecycle()
    val probing by c.probing.collectAsStateWithLifecycle()
    val settings by c.settings.collectAsStateWithLifecycle()
    val preview by c.preview.collectAsStateWithLifecycle()
    val withConsent = rememberNotificationConsent()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { c.add(it) }
    val onPick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
    // Audio files are not in the photo picker, so they come from the document picker.
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { c.add(it) }
    val onPickAudio = { audioPicker.launch(arrayOf("audio/*")) }

    LaunchedEffect(launch) {
        if (!launch.consumed) {
            launch.consumed = true
            if (launch.uris.isNotEmpty()) c.add(launch.uris)
        }
    }

    when {
        queue.jobs.isNotEmpty() -> RunScreen(queue, c.log, onCancel = c::cancel, onDone = c::dismissResults)
        picked.isNotEmpty() || probing > 0 -> ConfigureScreen(
            picked, probing > 0, settings, onChange = c::update, onAdd = onPick, onAddAudio = onPickAudio, onRemove = c::remove, onMoveUp = c::moveUp,
            preview = preview, onPreview = c::renderPreview, onBack = c::clear, onStart = { withConsent { c.start() } },
        )
        else -> HomeScreen(tool, onPick, onPickAudio, onExit, onTemplate = { t -> c.applyTemplate(t); if (t.audioFiles) onPickAudio() else onPick() })
    }
}

// ───────────────────────────── Home ─────────────────────────────

@Composable
private fun HomeScreen(tool: app.ultimate.core.Tool, onPick: () -> Unit, onPickAudio: () -> Unit, onExit: () -> Unit, onTemplate: (app.ultimate.audio.model.AudioTemplate) -> Unit) {
    BackHandler(onBack = onExit)
    val phase by rememberInfiniteTransition(label = "wave").animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    val demo = remember(phase) {
        List(40) { i -> (0.25f + 0.65f * kotlin.math.abs(kotlin.math.sin((i / 4.5f) + phase * 6.28f)) * (0.5f + 0.5f * kotlin.math.sin(i / 9f))).coerceIn(0.05f, 1f) }
    }
    val hasRecents = tool.library.collectAsStateWithLifecycle().value.isNotEmpty()
    Scaffold(topBar = { BackBar(onExit) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!hasRecents) Spacer(Modifier.height(48.dp))
            Waveform(demo, Modifier.fillMaxWidth().height(if (hasRecents) 64.dp else 96.dp), bars = 40, played = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("Video to audio", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Pull the sound out of videos, or cut, convert, speed up and join audio files. MP3, M4A or WAV.",
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onPick, Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(20.dp)) {
                Icon(Icons.Rounded.Add, null)
                Spacer(Modifier.width(10.dp))
                Text("Choose videos", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onPickAudio, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(20.dp)) {
                Icon(Icons.Rounded.MusicNote, null)
                Spacer(Modifier.width(10.dp))
                Text("Choose audio files", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(20.dp))
            RecentResults(tool, "Recent conversions", onOpenOwn = {}, limit = 6)
            Spacer(Modifier.height(12.dp))
            Text("Quick starts", Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            val seen = remember { mutableSetOf<Any>() }
            app.ultimate.audio.model.AudioTemplates.all.forEachIndexed { i, t ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).appear(t.title, seen, i).pressScale(0.97f).clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow).clickable { onTemplate(t) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (t.audioFiles) Icons.Rounded.MusicNote else Icons.Rounded.Movie, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(t.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ─────────────────────────── Configure ──────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigureScreen(
    picked: List<PickedVideo>,
    probing: Boolean,
    settings: AudioSettings,
    onChange: ((AudioSettings) -> AudioSettings) -> Unit,
    onAdd: () -> Unit,
    onAddAudio: () -> Unit,
    onRemove: (Uri) -> Unit,
    onMoveUp: (Uri) -> Unit,
    preview: AudioController.Preview,
    onPreview: () -> Unit,
    onBack: () -> Unit,
    onStart: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val usable = picked.filter { it.audio != null }
    // The preview follows every change by itself, a moment after the last one, so the waveform always shows the sound as set.
    val soundKey = listOf(settings.pitch, settings.bassDb, settings.trebleDb, settings.highCutHz, settings.fadeInMs, settings.fadeOutMs, settings.normalize, settings.speed, settings.trimStartMs, settings.trimEndMs, settings.mono)
    LaunchedEffect(soundKey, usable.size == 1, usable.firstOrNull()?.uri) {
        if (usable.size != 1) return@LaunchedEffect
        kotlinx.coroutines.delay(if (preview.edited == null) 0 else 700)
        onPreview()
    }
    val single = usable.size == 1
    val joining = settings.join && usable.size > 1
    val effective = (if (single) settings else settings.copy(trimStartMs = 0, trimEndMs = -1)).copy(join = joining)
    val total = usable.sumOf { AudioPlanner.estimateBytes(it.audio!!, effective) }
    val anyCopyable = usable.any { AudioPlanner.copies(it.audio!!, effective) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (picked.size == 1) "1 file" else "${picked.size} files") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    IconButton(onAddAudio) { Icon(Icons.Rounded.MusicNote, "Add audio files") }
                    IconButton(onAdd) { Icon(Icons.Rounded.Add, "Add videos") }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = onStart, enabled = usable.isNotEmpty() && !probing,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(56.dp), shape = RoundedCornerShape(18.dp),
                ) {
                    Text(
                        when {
                            probing -> "Reading files…"
                            usable.isEmpty() -> "No audio to convert"
                            joining -> "Join ${usable.size} into one ${settings.format.label}"
                            usable.size == 1 -> "Convert to ${settings.format.label}"
                            else -> "Convert ${usable.size} files"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { EstimateCard(total, if (joining) 1 else usable.size) }
            item { FormatSection(settings, anyCopyable, onChange) }
            item { SpeedSection(settings, onChange) }
            item { SoundSection(settings, onChange) }
            if (single) item { PreviewCard(preview, settings, onPreview) }
            if (single) item { TrimSection(usable[0].audio!!, settings, onChange) }
            if (usable.size > 1) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Join into one file", style = MaterialTheme.typography.bodyLarge)
                        Text("Plays them back to back, in the order below.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(settings.join, { v -> onChange { it.copy(join = v) } })
                }
            }
            itemsIndexed(picked, key = { _, v -> v.uri.toString() }) { i, v ->
                VideoRow(v, effective, onRemove = { onRemove(v.uri) }, onMoveUp = if (joining && i > 0) ({ onMoveUp(v.uri) }) else null)
            }
            if (probing) item { Box(Modifier.fillMaxWidth().padding(8.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) } }
        }
    }
}

@Composable
private fun EstimateCard(bytes: Long, count: Int) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.fillMaxWidth().padding(24.dp)) {
            Text("Estimated result", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                if (count == 0) "…" else "≈ ${formatBytes(bytes)}",
                style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                if (count > 1) "across $count files" else "Saved to Music/Ultimate",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun FormatSection(settings: AudioSettings, anyCopyable: Boolean, onChange: ((AudioSettings) -> AudioSettings) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            OutFormat.entries.forEachIndexed { i, f ->
                SegmentedButton(
                    selected = settings.format == f, onClick = { onChange { it.copy(format = f) } },
                    shape = SegmentedButtonDefaults.itemShape(i, OutFormat.entries.size),
                    label = { Text(f.label) },
                )
            }
        }
        Text(settings.format.blurb, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (settings.format == OutFormat.M4a && anyCopyable) {
            Text(
                "AAC audio will be copied untouched, so the quality setting does not apply to it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
            )
        }
        if (settings.format != OutFormat.Wav) {
            Text("Quality", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AudioPlanner.MP3_BITRATES.forEach { k ->
                    FilterChip(selected = settings.kbps == k, onClick = { onChange { it.copy(kbps = k) } }, label = { Text("$k kbps") })
                }
            }
            Text(
                AudioPlanner.bitrateLabel(settings.kbps).let { if (it.isEmpty()) "" else "$it quality" },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        run {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Mono", style = MaterialTheme.typography.bodyLarge)
                    Text("Halves the size of voice recordings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(settings.mono, { v -> onChange { it.copy(mono = v) } })
            }
        }
    }
}

@Composable
private fun SpeedSection(settings: AudioSettings, onChange: ((AudioSettings) -> AudioSettings) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Speed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AudioPlanner.SPEEDS.forEach { x ->
                FilterChip(selected = settings.speed == x, onClick = { onChange { it.copy(speed = x) } }, label = { Text(if (x == 1f) "Normal" else "${x}×") })
            }
        }
        if (settings.speed != 1f) {
            Text(
                "Pitch stays the same, so voices don't sound like chipmunks.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Pitch, tone, fades and level. Plain editing controls; each one is off until changed. */
@Composable
private fun SoundSection(settings: AudioSettings, onChange: ((AudioSettings) -> AudioSettings) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sound", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (settings.shaped) TextButton({ onChange { it.copy(pitch = 0, bassDb = 0f, trebleDb = 0f, highCutHz = 0, fadeInMs = 0, fadeOutMs = 0, normalize = false) } }) { Text("Reset") }
            }
            fun signed(v: Number, unit: String) = if (v.toFloat() == 0f) "0" else "${if (v.toFloat() > 0) "+" else ""}$v $unit"
            Text("Pitch  ${signed(settings.pitch, "semitones")}", style = MaterialTheme.typography.bodyMedium)
            Slider(settings.pitch.toFloat(), { v -> onChange { it.copy(pitch = v.roundToInt()) } }, valueRange = -6f..6f, steps = 11)
            Text("Bass  ${signed(settings.bassDb.roundToInt(), "dB")}", style = MaterialTheme.typography.bodyMedium)
            Slider(settings.bassDb, { v -> onChange { it.copy(bassDb = v.roundToInt().toFloat()) } }, valueRange = -12f..12f, steps = 23)
            Text("Treble  ${signed(settings.trebleDb.roundToInt(), "dB")}", style = MaterialTheme.typography.bodyMedium)
            Slider(settings.trebleDb, { v -> onChange { it.copy(trebleDb = v.roundToInt().toFloat()) } }, valueRange = -12f..12f, steps = 23)
            Text("High cut", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AudioPlanner.HIGH_CUTS.forEach { (hz, label) ->
                    FilterChip(selected = settings.highCutHz == hz, onClick = { onChange { it.copy(highCutHz = hz) } }, label = { Text(label) })
                }
            }
            Text("Fade in", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AudioPlanner.FADES.forEach { (ms, label) -> FilterChip(selected = settings.fadeInMs == ms, onClick = { onChange { it.copy(fadeInMs = ms) } }, label = { Text(label) }) }
            }
            Text("Fade out", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AudioPlanner.FADES.forEach { (ms, label) -> FilterChip(selected = settings.fadeOutMs == ms, onClick = { onChange { it.copy(fadeOutMs = ms) } }, label = { Text(label) }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Normalize", style = MaterialTheme.typography.bodyLarge)
                    Text("Brings the loudest moment up (or down) to just under full scale.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(settings.normalize, { v -> onChange { it.copy(normalize = v) } })
            }
        }
    }
}

/** Renders 20 seconds from the trim start both ways so the edit can be heard before saving. */
@Composable
private fun PreviewCard(preview: AudioController.Preview, settings: AudioSettings, onPreview: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Preview", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        if (settings.shaped || settings.speed != 1f) "Updates as you change the sound. Flip between original and edited while it plays." else "20 seconds from the start. Change the sound above and hear the difference.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.animation.AnimatedVisibility(preview.rendering) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp) }
            }
            preview.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (preview.original != null && preview.edited != null) {
                val dim by androidx.compose.animation.core.animateFloatAsState(if (preview.rendering) 0.55f else 1f, label = "rendering")
                Box(Modifier.graphicsLayer { alpha = dim }) {
                    app.ultimate.core.AbPlayer(preview.original, preview.edited, preview.originalPeaks, preview.editedPeaks, "Original", "Edited")
                }
            } else if (preview.error == null) {
                Text("Listening to your file…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrimSection(src: AudioSource, settings: AudioSettings, onChange: ((AudioSettings) -> AudioSettings) -> Unit) {
    val end = if (settings.trimEndMs > 0) minOf(settings.trimEndMs, src.durationMs) else src.durationMs
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Trim", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            RangeSlider(
                value = settings.trimStartMs.toFloat()..end.toFloat(),
                onValueChange = { r -> onChange { it.copy(trimStartMs = r.start.toLong(), trimEndMs = if (r.endInclusive >= src.durationMs - 50) -1 else r.endInclusive.toLong()) } },
                valueRange = 0f..src.durationMs.toFloat(),
            )
            Text(
                "Keep ${formatDuration(settings.trimStartMs)} – ${formatDuration(end)}  (${formatDuration(end - settings.trimStartMs)})",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VideoRow(video: PickedVideo, settings: AudioSettings, onRemove: () -> Unit, onMoveUp: (() -> Unit)? = null) {
    val src = video.audio
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        VideoThumb(video.uri, Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)), src?.durationMs ?: 0)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(video.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (src == null) {
                Text("No audio track", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            } else {
                Text(sourceSummary(src), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (settings.join) "→ part of the joined file · ${formatDuration(AudioPlanner.outputMs(src, settings))}"
                    else "→ ${settings.format.label}${if (AudioPlanner.copies(src, settings)) " (copied)" else ""} · ≈ ${formatBytes(AudioPlanner.estimateBytes(src, settings))}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                )
            }
        }
        onMoveUp?.let { IconButton(it) { Icon(Icons.Rounded.KeyboardArrowUp, "Move up") } }
        IconButton(onRemove) { Icon(Icons.Rounded.Close, "Remove") }
    }
}

private fun sourceSummary(s: AudioSource): String {
    val codec = when (s.mime.lowercase()) {
        "audio/mp4a-latm" -> "AAC"
        "audio/mpeg" -> "MP3"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/ac3" -> "AC-3"
        "audio/eac3" -> "E-AC-3"
        else -> s.mime.substringAfter('/').uppercase()
    }
    val layout = when (s.channels) { 1 -> "mono"; 2 -> "stereo"; else -> "${s.channels} ch" }
    return "$codec · ${String.format(Locale.US, "%.1f", s.sampleRate / 1000f)} kHz · $layout · ${formatDuration(s.durationMs)}"
}

// ───────────────────────────── Run ──────────────────────────────

@Composable
private fun RunScreen(queue: AudioQueue, log: JobLog, onCancel: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    BackHandler(enabled = !queue.active, onBack = onDone)
    val done = queue.done
    LaunchedEffect(queue.active) {
        if (!queue.active && done.isNotEmpty()) {
            view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
        }
    }

    Scaffold(
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (queue.active) {
                        OutlinedButton(onCancel, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) { Text("Cancel") }
                    } else {
                        if (done.isNotEmpty()) {
                            FilledTonalButton({ shareFiles(context, done.map { Uri.parse(it.uri) }) }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                                Icon(Icons.Rounded.Share, null); Spacer(Modifier.width(8.dp)); Text(if (done.size == 1) "Share" else "Share all")
                            }
                        }
                        Button(onDone, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) { Text("Done", style = MaterialTheme.typography.titleMedium) }
                    }
                }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { RunHero(queue) }
            item {
                val lines by log.lines.collectAsStateWithLifecycle()
                Terminal(lines, live = queue.active)
            }
            items(queue.jobs, key = { it.id }) { RunRow(it) }
        }
    }
}

@Composable
private fun RunHero(queue: AudioQueue) {
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val total = queue.jobs.size.coerceAtLeast(1)
    val finished = queue.jobs.count { it.status !is AudioStatus.Queued && it.status !is AudioStatus.Running }
    val running = queue.jobs.firstOrNull { it.status is AudioStatus.Running }?.status as? AudioStatus.Running
    val overall = ((finished + (running?.progress ?: 0f)) / total).coerceIn(0f, 1f)

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                queue.active -> {
                    Text("Extracting sound", style = MaterialTheme.typography.labelLarge, color = onContainer)
                    Text("${(overall * 100).roundToInt()}%", style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold, color = onContainer)
                    Spacer(Modifier.height(12.dp))
                    Waveform(
                        running?.peaks.orEmpty(), Modifier.fillMaxWidth().height(80.dp), bars = 160,
                        played = MaterialTheme.colorScheme.primary, rest = MaterialTheme.colorScheme.primary,
                        ghost = onContainer.copy(alpha = 0.15f),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "File ${minOf(finished + 1, total)} of $total" + (running?.speedX?.let { String.format(Locale.US, " · %.1f× real time", it) } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = onContainer,
                    )
                    Text("You can leave the app. It keeps working.", style = MaterialTheme.typography.bodySmall, color = onContainer.copy(alpha = 0.8f))
                }
                queue.done.isEmpty() -> {
                    Icon(Icons.Rounded.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(56.dp))
                    Text("Nothing was converted", style = MaterialTheme.typography.titleLarge, color = onContainer)
                }
                else -> {
                    SuccessCheck(88.dp, MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (queue.done.size == 1) queue.done.first().displayName else "${queue.done.size} audio files",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = onContainer, textAlign = TextAlign.Center,
                    )
                    Text("${formatBytes(queue.done.sumOf { it.bytes })} in Music/Ultimate", style = MaterialTheme.typography.titleSmall, color = onContainer)
                }
            }
        }
    }
}

@Composable
private fun RunRow(job: AudioJob) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VideoThumb(job.uri, Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)), job.source.durationMs)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(job.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                when (val s = job.status) {
                    AudioStatus.Queued -> Sub("Waiting")
                    is AudioStatus.Running -> {
                        LinearProgressIndicator(progress = { s.progress }, Modifier.fillMaxWidth().padding(top = 6.dp).height(6.dp).clip(RoundedCornerShape(3.dp)))
                        Sub("${(s.progress * 100).roundToInt()}%")
                    }
                    is AudioStatus.Done -> {
                        val e = s.entry
                        Text(
                            "${e.format.label} · ${formatBytes(e.bytes)} · ${formatDuration(e.durationMs)}",
                            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                        )
                    }
                    is AudioStatus.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    AudioStatus.Cancelled -> Sub("Cancelled")
                }
            }
        }
        (job.status as? AudioStatus.Done)?.let { AudioPlayerBar(Uri.parse(it.entry.uri), it.entry.peaks, Modifier.padding(top = 6.dp)) }
    }
}

@Composable
private fun Sub(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
