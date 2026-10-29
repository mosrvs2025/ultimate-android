package app.ultimate.studio

import androidx.compose.material3.FilledTonalButton
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Rotate90DegreesCw
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.core.Terminal
import app.ultimate.core.formatBytes
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private val Accent = Color(0xFFFFC46B)
private val ClipBorder = Color.White
private val MusicColor = Color(0xFF2E7D5B)
private val TextColor = Color(0xFF7E57C2)
private val StudioColors = darkColorScheme(
    primary = Accent, onPrimary = Color(0xFF2B1D00), secondary = Color(0xFFA5D5C9),
    background = Color(0xFF0B0B0D), surface = Color(0xFF141417), surfaceVariant = Color(0xFF232328), surfaceContainer = Color(0xFF1A1A1F),
    onBackground = Color(0xFFF2F2F4), onSurface = Color(0xFFF2F2F4), onSurfaceVariant = Color(0xFFA7A7B0), outline = Color(0xFF4A4A52),
)

/** The tool open above the toolbar. */
private enum class Panel { Speed, Volume, Filters, Adjust, Text, Audio, Format }

private const val MIN_DP_PER_SECOND = 10f
private const val MAX_DP_PER_SECOND = 480f
private val TILE = 52.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(manager: StudioManager, onExit: () -> Unit) {
    val project by manager.project.collectAsStateWithLifecycle()
    val loading by manager.loading.collectAsStateWithLifecycle()
    val work by manager.work.collectAsStateWithLifecycle()
    val error by manager.error.collectAsStateWithLifecycle()
    val status by manager.status.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current

    var playhead by rememberSaveable { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var selection by rememberSaveable { mutableStateOf<String?>(null) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var showExport by remember { mutableStateOf(false) }
    var showProjects by remember { mutableStateOf(false) }
    var dpPerSecond by rememberSaveable { mutableFloatStateOf(64f) }
    val pxPerMs = with(density) { dpPerSecond.dp.toPx() } / 1000f

    val busy = loading || work.active
    val selected = project.clips.firstOrNull { it.id == selection }
    val underPlayhead = Timeline.locate(project, playhead)
    val videos = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { manager.addVideos(it) }
    val music = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(manager::addMusic) }
    val addVideos = { playing = false; videos.launch(arrayOf("video/*")) }

    // Keep state consistent with edits: the playhead stays inside the film, and a removed clip is no longer selected.
    LaunchedEffect(project.durationMs) { if (playhead > project.durationMs) playhead = project.durationMs }
    LaunchedEffect(project.clips.map { it.id }) { if (project.clips.none { it.id == selection }) { selection = null; if (panel != Panel.Audio && panel != Panel.Format) panel = null } }
    LaunchedEffect(work.active) { if (work.active) { playing = false; showExport = true } }

    fun stop() { playing = false }
    fun change(id: String, f: (StudioClip) -> StudioClip) = manager.updateClip(id, f)
    /** Tools that act on one clip use the selected clip, or the one under the playhead. */
    fun target(): StudioClip? = selected ?: underPlayhead?.first

    BackHandler {
        when {
            panel != null -> panel = null
            selection != null -> selection = null
            else -> { stop(); onExit() }
        }
    }

    MaterialTheme(colorScheme = StudioColors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                TopBar(project.name, status, busy, onClose = { stop(); onExit() }, onProjects = { stop(); showProjects = true }, onExport = { stop(); showExport = true }, canExport = project.clips.isNotEmpty())
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))

                // Preview: always on screen, at the canvas shape.
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                    val ratio = project.shape.ratio
                    Box(
                        Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f).clip(RoundedCornerShape(8.dp))
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = project.clips.isNotEmpty() && !busy) { playing = !playing },
                    ) {
                        if (work.active) {
                            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    CircularProgressIndicator(progress = { (work.percent ?: 0) / 100f })
                                    Text("Rendering · ${work.percent ?: 0}%", color = Color.White)
                                }
                            }
                        } else {
                            StudioPreview(
                                project, playhead, playing,
                                onPlayhead = { playhead = it },
                                onEnded = { playing = false; playhead = project.durationMs },
                                onError = { manager.error.value = it; playing = false },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }

                Transport(
                    playhead, project.durationMs, playing, enabled = project.clips.isNotEmpty() && !busy,
                    onPlay = { if (playhead >= project.durationMs - 50) playhead = 0; playing = !playing },
                    canUndo = manager.canUndo && !busy, canRedo = manager.canRedo && !busy,
                    onUndo = { stop(); manager.undo() }, onRedo = { stop(); manager.redo() },
                )

                TimelineView(
                    project = project, playheadMs = playhead, playing = playing, pxPerMs = pxPerMs, selection = selection, enabled = !busy,
                    onScrub = { ms ->
                        playing = false
                        // Snap to clip edges when close, with a tick, so cuts can be found by feel.
                        val snapMs = (12.dp.value * density.density / pxPerMs).toLong()
                        val edge = Timeline.boundaries(project).firstOrNull { abs(it - ms) <= snapMs }
                        if (edge != null && edge != playhead) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        playhead = (edge ?: ms).coerceIn(0, project.durationMs)
                    },
                    onSelect = { id ->
                        selection = if (selection == id) null else id
                        if (selection != null) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (panel != null && selection == null) panel = null
                    },
                    onTrim = { updated -> stop(); change(updated.id) { updated } },
                    onReorder = { id, index -> stop(); manager.moveTo(id, index); haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                    onZoom = { z -> dpPerSecond = (dpPerSecond * z).coerceIn(MIN_DP_PER_SECOND, MAX_DP_PER_SECOND) },
                    onAddVideos = addVideos,
                    onMusic = { panel = Panel.Audio },
                    onText = { id -> selection = id; panel = Panel.Text },
                )

                AnimatedVisibility(panel != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    val clip = target()
                    ToolPanel(
                        title = when (panel) {
                            Panel.Speed -> "Speed"; Panel.Volume -> "Volume"; Panel.Filters -> "Filters"; Panel.Adjust -> "Adjust"
                            Panel.Text -> "Text"; Panel.Audio -> "Audio"; Panel.Format -> "Format"; null -> ""
                        },
                        onDone = { panel = null },
                    ) {
                        when (panel) {
                            Panel.Speed -> clip?.let { c -> SpeedPanel(c) { v -> stop(); change(c.id) { it.copy(speed = v) } } }
                            Panel.Volume -> clip?.let { c -> VolumePanel(c) { v -> change(c.id) { it.copy(volume = v) } } }
                            Panel.Filters -> clip?.let { c -> FiltersPanel(c, onPick = { look -> change(c.id) { it.copy(look = look) } }, onAll = { look -> manager.edit { p -> p.copy(clips = p.clips.map { it.copy(look = look) }) } }) }
                            Panel.Adjust -> clip?.let { c -> AdjustPanel(c, onBrightness = { v -> change(c.id) { it.copy(brightness = v) } }, onRotate = { stop(); change(c.id) { it.copy(rotation = (it.rotation + 90) % 360) } }) }
                            Panel.Text -> clip?.let { c -> TextPanel(c, manager, onText = { t -> change(c.id) { it.copy(title = t) } }, onPosition = { pos -> change(c.id) { it.copy(titlePosition = pos) } }, onStyle = { st, col -> change(c.id) { it.copy(captionStyle = st, captionColor = col) } }) }
                            Panel.Audio -> AudioPanel(project, playhead, manager, busy, onPick = { stop(); music.launch(arrayOf("audio/*", "video/*")) })
                            Panel.Format -> FormatPanel(project, busy, manager)
                            null -> Unit
                        }
                    }
                }

                error?.let { message ->
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            IconButton({ manager.error.value = null }) { Icon(Icons.Rounded.Close, "Dismiss", tint = MaterialTheme.colorScheme.onErrorContainer) }
                        }
                    }
                }

                // Bottom toolbar: project tools, or clip tools while a clip is selected.
                val clipTools = selected != null
                Toolbar {
                    if (clipTools) {
                        val c = selected!!
                        ToolButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", !busy) { selection = null; panel = null }
                        ToolButton(Icons.Rounded.ContentCut, "Split", !busy) {
                            stop()
                            val at = playhead - Timeline.offset(project, c.id)
                            if (at <= 0 || at >= c.durationMs) manager.error.value = "Move the playhead inside the selected clip to split it"
                            else { manager.split(c.id, at); haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
                        }
                        ToolButton(Icons.Rounded.Speed, "Speed", !busy, panel == Panel.Speed) { panel = Panel.Speed }
                        ToolButton(if (c.volume == 0f) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, "Volume", !busy, panel == Panel.Volume) { panel = Panel.Volume }
                        ToolButton(Icons.Rounded.Palette, "Filters", !busy, panel == Panel.Filters) { panel = Panel.Filters }
                        ToolButton(Icons.Rounded.Tune, "Adjust", !busy, panel == Panel.Adjust) { panel = Panel.Adjust }
                        ToolButton(Icons.Rounded.TextFields, "Text", !busy, panel == Panel.Text) { panel = Panel.Text }
                        ToolButton(Icons.Rounded.ContentCopy, "Duplicate", !busy) {
                            stop(); manager.edit { p -> p.copy(clips = p.clips.flatMap { if (it.id == c.id) listOf(it, it.copy(id = UUID.randomUUID().toString())) else listOf(it) }) }
                        }
                        ToolButton(Icons.Rounded.ChevronLeft, "Move left", !busy && project.clips.first().id != c.id) { stop(); manager.move(c.id, -1) }
                        ToolButton(Icons.Rounded.ChevronRight, "Move right", !busy && project.clips.last().id != c.id) { stop(); manager.move(c.id, 1) }
                        ToolButton(Icons.Rounded.Delete, "Delete", !busy) { stop(); manager.edit { p -> p.copy(clips = p.clips.filterNot { it.id == c.id }) }; selection = null; panel = null }
                    } else {
                        ToolButton(Icons.Rounded.Add, "Add clips", !busy) { addVideos() }
                        ToolButton(Icons.Rounded.MusicNote, "Audio", !busy, panel == Panel.Audio) { panel = Panel.Audio }
                        ToolButton(Icons.Rounded.TextFields, "Text", !busy && project.clips.isNotEmpty(), panel == Panel.Text) { selection = target()?.id; panel = Panel.Text }
                        ToolButton(Icons.Rounded.Palette, "Filters", !busy && project.clips.isNotEmpty(), panel == Panel.Filters) { selection = target()?.id; panel = Panel.Filters }
                        ToolButton(Icons.Rounded.Tune, "Adjust", !busy && project.clips.isNotEmpty(), panel == Panel.Adjust) { selection = target()?.id; panel = Panel.Adjust }
                        ToolButton(Icons.Rounded.Speed, "Speed", !busy && project.clips.isNotEmpty(), panel == Panel.Speed) { selection = target()?.id; panel = Panel.Speed }
                        ToolButton(Icons.Rounded.AspectRatio, "Format", !busy, panel == Panel.Format) { panel = Panel.Format }
                    }
                }
            }
        }

        if (showExport) ExportSheet(project, manager, onDismiss = { showExport = false })
        if (showProjects) ProjectsSheet(manager, busy, onDismiss = { showProjects = false }, onOpened = { stop(); playhead = 0; selection = null; panel = null })
    }
}

// ───────────────────────────────── chrome ─────────────────────────────────

@Composable
private fun TopBar(name: String, status: String, busy: Boolean, onClose: () -> Unit, onProjects: () -> Unit, onExport: () -> Unit, canExport: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClose) { Icon(Icons.Rounded.Close, "Close Studio") }
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        IconButton(onProjects, enabled = !busy) { Icon(Icons.Rounded.FolderOpen, "Projects") }
        Button(onExport, enabled = canExport, shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 18.dp), modifier = Modifier.height(36.dp).padding(end = 8.dp)) {
            Text("Export", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Transport(at: Long, total: Long, playing: Boolean, enabled: Boolean, onPlay: () -> Unit, canUndo: Boolean, canRedo: Boolean, onUndo: () -> Unit, onRedo: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${clock(at)} / ${clock(total)}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledIconButton(onPlay, enabled = enabled, modifier = Modifier.size(44.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black)) {
            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(28.dp))
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
            IconButton(onUndo, enabled = canUndo) { Icon(Icons.AutoMirrored.Rounded.Undo, "Undo") }
            IconButton(onRedo, enabled = canRedo) { Icon(Icons.AutoMirrored.Rounded.Redo, "Redo") }
        }
    }
}

@Composable
private fun Toolbar(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 6.dp)) { content() }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, enabled: Boolean, active: Boolean = false, onClick: () -> Unit) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.outline
        active -> Accent
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        Modifier.width(64.dp).clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ToolPanel(title: String, onDone: () -> Unit, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                IconButton(onDone) { Icon(Icons.Rounded.Check, "Done", tint = Accent) }
            }
            content()
        }
    }
}

// ───────────────────────────────── timeline ─────────────────────────────────

/**
 * Filmstrip timeline with a fixed playhead in the middle. Scrolling moves the film under the playhead (and seeks); while playing the
 * strip follows playback. Pinch with two fingers to zoom. Tap a clip to select it; drag its white handles to trim.
 */
@Composable
private fun TimelineView(
    project: StudioProject,
    playheadMs: Long,
    playing: Boolean,
    pxPerMs: Float,
    selection: String?,
    enabled: Boolean,
    onScrub: (Long) -> Unit,
    onSelect: (String) -> Unit,
    onTrim: (StudioClip) -> Unit,
    onReorder: (String, Int) -> Unit,
    onZoom: (Float) -> Unit,
    onAddVideos: () -> Unit,
    onMusic: () -> Unit,
    onText: (String) -> Unit,
) {
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    var programmatic by remember { mutableStateOf(false) }
    // A trim in progress, shown live and committed (as one undo step) when the finger lifts.
    var draft by remember { mutableStateOf<StudioClip?>(null) }
    val clips = project.clips.map { c -> if (draft?.id == c.id) draft!! else c }
    // A clip lifted by a long press, being dragged to a new place.
    var lifted by remember { mutableStateOf<Lift?>(null) }
    var boxX by remember { mutableFloatStateOf(0f) }
    val total = clips.sumOf { it.durationMs }

    LaunchedEffect(playheadMs, pxPerMs, total) {
        val target = (playheadMs * pxPerMs).roundToInt()
        if (scroll.value != target && (!scroll.isScrollInProgress || programmatic || playing)) {
            programmatic = true
            scroll.scrollTo(target)
            programmatic = false
        }
    }
    LaunchedEffect(scroll, pxPerMs) {
        snapshotFlow { scroll.value }.collect { v -> if (!programmatic && scroll.isScrollInProgress) onScrub((v / pxPerMs).toLong()) }
    }
    // When a scroll or fling ends, settle the strip exactly under the (possibly snapped) playhead.
    val settledPlayhead by androidx.compose.runtime.rememberUpdatedState(playheadMs)
    LaunchedEffect(scroll, pxPerMs) {
        snapshotFlow { scroll.isScrollInProgress }.collect { moving ->
            val target = (settledPlayhead * pxPerMs).roundToInt()
            if (!moving && !programmatic && scroll.value != target) { programmatic = true; scroll.scrollTo(target); programmatic = false }
        }
    }

    BoxWithConstraints(
        Modifier.fillMaxWidth().height(176.dp).background(MaterialTheme.colorScheme.background)
            .onGloballyPositioned { boxX = it.positionInRoot().x }
            .pointerInput(Unit) {
                // Two fingers zoom; one finger is left to the scroll and the trim handles.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) onZoom(zoom)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        val half = maxWidth / 2
        if (project.clips.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                OutlinedButton(onAddVideos, enabled = enabled, shape = RoundedCornerShape(14.dp), modifier = Modifier.height(64.dp)) {
                    Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("Add videos to start")
                }
            }
            return@BoxWithConstraints
        }
        Row(Modifier.fillMaxSize().horizontalScroll(scroll, enabled = enabled && draft == null && lifted == null)) {
            Spacer(Modifier.width(half))
            Column {
                Ruler(total, pxPerMs)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    clips.forEach { c -> androidx.compose.runtime.key(c.id) {
                        ClipStrip(
                            c, pxPerMs, selected = c.id == selection, enabled = enabled,
                            onTap = { onSelect(c.id) },
                            onDrag = { draft = it },
                            onDragEnd = { draft?.let(onTrim); draft = null },
                            onLift = { rootX -> lifted = Lift(c.id, clips.indexOfFirst { it.id == c.id }, rootX - boxX) },
                            onLiftMove = { dx -> lifted = lifted?.let { it.copy(dx = it.dx + dx) } },
                            onLiftEnd = { drop ->
                                val l = lifted
                                lifted = null
                                if (drop && l != null) {
                                    val to = Timeline.reorderSlot(l.start, l.dx + l.shift, with(density) { REORDER_TILE.toPx() }, clips.size)
                                    if (to != l.start) onReorder(l.id, to)
                                }
                            },
                        )
                    } }
                    Spacer(Modifier.width(10.dp))
                    FilledIconButton(onAddVideos, enabled = enabled, modifier = Modifier.size(40.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black)) {
                        Icon(Icons.Rounded.Add, "Add clips")
                    }
                }
                Spacer(Modifier.height(6.dp))
                // Titles sit under the clip they belong to.
                Row {
                    clips.forEach { c ->
                        val w = with(density) { (c.durationMs * pxPerMs).toDp() }
                        Box(Modifier.width(w).height(20.dp).padding(end = 2.dp)) {
                            if (c.title.isNotBlank()) {
                                Text(
                                    "T  ${c.title.lineSequence().first()}", color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)).background(TextColor).clickable(enabled = enabled) { onText(c.id) }.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                MusicTrack(project.music, with(density) { (total * pxPerMs).toDp() }, enabled, onMusic)
            }
            Spacer(Modifier.width(half))
        }
        // The playhead never moves; the film moves under it.
        Box(Modifier.align(Alignment.TopCenter).offset(y = 18.dp).width(2.dp).fillMaxHeight().background(Color.White))
        Box(Modifier.align(Alignment.TopCenter).offset(y = 12.dp).size(10.dp).clip(CircleShape).background(Color.White))
        lifted?.let { l -> ReorderOverlay(clips, l, constraints.maxWidth.toFloat()) { lifted = it } }
    }
}

private val REORDER_TILE = 60.dp

/** A clip being moved: where it started, where the finger went down (in timeline pixels), how far it has moved, and any auto-scroll. */
private data class Lift(val id: String, val start: Int, val anchorX: Float, val dx: Float = 0f, val shift: Float = 0f)

/**
 * While reordering, every clip becomes an equal square so long and short clips are equally easy to move. The lifted clip follows the
 * finger; the others slide apart to show where it will land; holding near an edge scrolls the row.
 */
@Composable
private fun ReorderOverlay(clips: List<StudioClip>, lift: Lift, widthPx: Float, update: (Lift) -> Unit) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val tile = with(density) { REORDER_TILE.toPx() }
    val edge = with(density) { 56.dp.toPx() }
    val current by androidx.compose.runtime.rememberUpdatedState(lift)
    val target = Timeline.reorderSlot(lift.start, lift.dx + lift.shift, tile, clips.size)
    LaunchedEffect(target) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(220)
            val l = current
            val finger = l.anchorX + l.dx
            val slot = Timeline.reorderSlot(l.start, l.dx + l.shift, tile, clips.size)
            when {
                finger < edge && slot > 0 -> update(l.copy(shift = l.shift - tile))
                finger > widthPx - edge && slot < clips.lastIndex -> update(l.copy(shift = l.shift + tile))
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f))) {
        val others = clips.filter { it.id != lift.id }
        others.forEachIndexed { k, c ->
            val slot = if (k < target) k else k + 1
            val x by androidx.compose.animation.core.animateFloatAsState(lift.anchorX - tile / 2 + (slot - lift.start) * tile - lift.shift, label = "slot")
            ReorderTile(c, Modifier.offset { IntOffset(x.roundToInt(), 0) }.align(Alignment.CenterStart), lifted = false)
        }
        clips.firstOrNull { it.id == lift.id }?.let { c ->
            ReorderTile(c, Modifier.offset { IntOffset((lift.anchorX - tile / 2 + lift.dx).roundToInt(), 0) }.align(Alignment.CenterStart), lifted = true)
        }
        Text(
            "Drag to move · release to drop", Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
            color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun ReorderTile(c: StudioClip, modifier: Modifier, lifted: Boolean) {
    val scale by androidx.compose.animation.core.animateFloatAsState(if (lifted) 1.15f else 0.92f, label = "lift")
    Box(
        modifier.size(REORDER_TILE).graphicsLayer { scaleX = scale; scaleY = scale; shadowElevation = if (lifted) 16f else 0f }
            .clip(RoundedCornerShape(8.dp)).border(if (lifted) 2.dp else 0.dp, if (lifted) Accent else Color.Transparent, RoundedCornerShape(8.dp)),
    ) {
        FrameTile(c.uri, c.startMs, c.look, REORDER_TILE)
    }
}

@Composable
private fun Ruler(totalMs: Long, pxPerMs: Float) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val width = with(density) { (totalMs * pxPerMs).toDp() }
    Canvas(Modifier.width(width).height(18.dp)) {
        // Pick a tick spacing that leaves room for labels at this zoom.
        val stepMs = listOf(250L, 500L, 1000L, 2000L, 5000L, 10_000L, 30_000L, 60_000L).first { it * pxPerMs >= 48.dp.toPx() / 2 }
        var t = 0L
        var i = 0
        while (t <= totalMs) {
            val x = t * pxPerMs
            val major = i % 2 == 0
            drawLine(labelColor.copy(alpha = if (major) 0.7f else 0.35f), Offset(x, size.height - if (major) 7.dp.toPx() else 4.dp.toPx()), Offset(x, size.height), strokeWidth = 1.dp.toPx())
            if (major) drawText(measurer, rulerLabel(t), Offset(x + 3.dp.toPx(), 0f), TextStyle(color = labelColor, fontSize = 9.sp))
            t += stepMs
            i++
        }
    }
}

@Composable
private fun ClipStrip(
    c: StudioClip, pxPerMs: Float, selected: Boolean, enabled: Boolean, onTap: () -> Unit, onDrag: (StudioClip) -> Unit, onDragEnd: () -> Unit,
    onLift: (Float) -> Unit, onLiftMove: (Float) -> Unit, onLiftEnd: (Boolean) -> Unit,
) {
    var rootX by remember { mutableFloatStateOf(0f) }
    val lift by androidx.compose.runtime.rememberUpdatedState(onLift)
    val move by androidx.compose.runtime.rememberUpdatedState(onLiftMove)
    val end by androidx.compose.runtime.rememberUpdatedState(onLiftEnd)
    val density = LocalDensity.current
    val widthPx = c.durationMs * pxPerMs
    val width = with(density) { widthPx.toDp() }
    val tilePx = with(density) { TILE.toPx() }
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier.width(width).height(TILE).clip(shape)
            .border(if (selected) 2.dp else 0.5.dp, if (selected) ClipBorder else Color.Black, shape)
            .onGloballyPositioned { rootX = it.positionInRoot().x }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = { at -> lift(rootX + at.x) },
                    onDragEnd = { end(true) },
                    onDragCancel = { end(false) },
                ) { change, amount -> change.consume(); move(amount.x) }
            }
            .clickable(enabled = enabled, onClick = onTap),
    ) {
        // Filmstrip: one frame per tile, taken from where that tile sits in the source.
        Row {
            val tiles = ceil(widthPx / tilePx).toInt().coerceAtLeast(1)
            repeat(tiles) { i ->
                val sourceMs = c.startMs + ((i * tilePx / pxPerMs) * c.speed).toLong()
                FrameTile(c.uri, sourceMs.coerceAtMost(c.endMs), c.look)
            }
        }
        Row(Modifier.align(Alignment.BottomStart).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (c.speed != 1f) Badge("${fmtSpeed(c.speed)}×")
            if (c.volume == 0f) Badge("muted")
            if (selected) Badge(clock(c.durationMs))
        }
        if (selected && enabled) {
            // Trims are measured from the clip as it was when the finger went down, so they do not compound while dragging.
            var base by remember { mutableStateOf<StudioClip?>(null) }
            TrimHandle(Modifier.align(Alignment.CenterStart), onStart = { base = c }, onEnd = { base = null; onDragEnd() }) { dx ->
                val b = base ?: c
                onDrag(Timeline.trimStart(b, b.startMs + (dx / pxPerMs * b.speed).toLong()))
            }
            TrimHandle(Modifier.align(Alignment.CenterEnd), onStart = { base = c }, onEnd = { base = null; onDragEnd() }) { dx ->
                val b = base ?: c
                onDrag(Timeline.trimEnd(b, b.endMs + (dx / pxPerMs * b.speed).toLong()))
            }
        }
    }
}

@Composable
private fun TrimHandle(modifier: Modifier, onStart: () -> Unit, onEnd: () -> Unit, onDelta: (Float) -> Unit) {
    var accumulated by remember { mutableFloatStateOf(0f) }
    val start by androidx.compose.runtime.rememberUpdatedState(onStart)
    val end by androidx.compose.runtime.rememberUpdatedState(onEnd)
    val delta by androidx.compose.runtime.rememberUpdatedState(onDelta)
    Box(
        modifier.width(14.dp).fillMaxHeight().background(ClipBorder)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { accumulated = 0f; start() },
                    onDragEnd = { end() },
                    onDragCancel = { end() },
                ) { change, dx ->
                    change.consume()
                    accumulated += dx
                    delta(accumulated)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(2.dp).height(18.dp).background(Color.Black.copy(alpha = 0.6f)))
    }
}

@Composable
private fun FrameTile(uri: String, atMs: Long, look: Look, size: androidx.compose.ui.unit.Dp = TILE) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    var bitmap by remember(uri, atMs) { mutableStateOf(FrameCache.peek(uri, atMs)) }
    LaunchedEffect(uri, atMs) { if (bitmap == null) bitmap = FrameCache.frame(context, uri, atMs, sizePx) }
    Box(Modifier.size(size).background(Color(0xFF2A2A30))) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, colorFilter = lookFilter(look)) }
    }
}

@Composable
private fun Badge(text: String) = Text(
    text, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 4.dp, vertical = 1.dp),
)

@Composable
private fun MusicTrack(music: StudioMusic?, width: androidx.compose.ui.unit.Dp, enabled: Boolean, onClick: () -> Unit) {
    if (music == null) {
        Row(
            Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.MusicNote, null, Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add audio", fontSize = 12.sp)
        }
    } else {
        Row(
            Modifier.width(width).height(28.dp).clip(RoundedCornerShape(6.dp)).background(MusicColor).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.MusicNote, null, Modifier.size(14.dp), tint = Color.White)
            Spacer(Modifier.width(6.dp))
            Text("${music.name} · ${(music.volume * 100).roundToInt()}%", color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ───────────────────────────────── tool panels ─────────────────────────────────

@Composable
private fun SpeedPanel(c: StudioClip, onCommit: (Float) -> Unit) {
    var v by remember(c.id, c.speed) { mutableFloatStateOf(c.speed) }
    Text("${fmtSpeed(v)}×  ·  ${clock((((c.endMs - c.startMs) / v).toLong()))}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Slider(v, { v = (it * 20).roundToInt() / 20f }, onValueChangeFinished = { onCommit(v) }, valueRange = 0.25f..4f)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 3f, 4f).forEach { s -> FilterChip(c.speed == s, { onCommit(s) }, { Text("${fmtSpeed(s)}×") }) }
    }
    Text("Sound keeps its pitch.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun VolumePanel(c: StudioClip, onCommit: (Float) -> Unit) {
    var v by remember(c.id, c.volume) { mutableFloatStateOf(c.volume) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton({ onCommit(if (c.volume == 0f) 1f else 0f) }) { Icon(if (v == 0f) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, if (v == 0f) "Unmute" else "Mute") }
        Slider(v, { v = it }, Modifier.weight(1f), onValueChangeFinished = { onCommit(v) })
        Text("${(v * 100).roundToInt()}%", Modifier.width(48.dp), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun FiltersPanel(c: StudioClip, onPick: (Look) -> Unit, onAll: (Look) -> Unit) {
    val context = LocalContext.current
    var thumb by remember(c.uri, c.startMs) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(c.uri, c.startMs) { thumb = FrameCache.frame(context, c.uri, c.startMs, 240) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(Look.entries) { look ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onPick(look) }) {
                Box(
                    Modifier.size(68.dp).clip(RoundedCornerShape(10.dp)).border(if (c.look == look) 2.dp else 0.dp, if (c.look == look) Accent else Color.Transparent, RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, colorFilter = lookFilter(look)) }
                }
                Spacer(Modifier.height(4.dp))
                Text(look.label, style = MaterialTheme.typography.labelSmall, color = if (c.look == look) Accent else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
    TextButton({ onAll(c.look) }) { Text("Apply ${c.look.label} to all clips") }
}

@Composable
private fun AdjustPanel(c: StudioClip, onBrightness: (Float) -> Unit, onRotate: () -> Unit) {
    var v by remember(c.id, c.brightness) { mutableFloatStateOf(c.brightness) }
    Text("Exposure ${if (v >= 0) "+" else ""}${(v * 200).roundToInt()}", style = MaterialTheme.typography.labelLarge)
    Slider(v, { v = it }, onValueChangeFinished = { onBrightness(v) }, valueRange = -0.5f..0.5f)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
        OutlinedButton(onRotate) { Icon(Icons.Rounded.Rotate90DegreesCw, null); Spacer(Modifier.width(6.dp)); Text("Rotate ${c.rotation}°") }
        TextButton({ onBrightness(0f) }) { Text("Reset") }
    }
}

@Composable
private fun TextPanel(c: StudioClip, manager: StudioManager, onText: (String) -> Unit, onPosition: (Int) -> Unit, onStyle: (CaptionStyle, Int) -> Unit) {
    // Typed text is kept locally and saved after a short pause, so each key is not its own undo step.
    var draft by remember(c.id) { mutableStateOf(c.title) }
    LaunchedEffect(draft) { delay(500); if (draft != c.title) onText(draft) }
    OutlinedTextField(draft, { draft = it.take(200) }, Modifier.fillMaxWidth(), placeholder = { Text("Title, caption or lyric for this clip") }, maxLines = 3)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        listOf("Middle", "Top", "Bottom").forEachIndexed { i, label -> FilterChip(c.titlePosition == i, { onPosition(i) }, { Text(label) }) }
        if (c.title.isNotEmpty()) TextButton({ draft = ""; onText("") }) { Text("Remove") }
    }
    // Captions from speech, made on the phone; they follow the style and colour below.
    val captioning by manager.captioning.collectAsStateWithLifecycle()
    val project by manager.project.collectAsStateWithLifecycle()
    androidx.compose.animation.AnimatedContent(captioning, label = "captions") { busy ->
        if (busy != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp))
            Text(busy, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(manager::cancelCaptions) { Text("Stop") }
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            FilledTonalButton({ manager.autoCaption(listOf(c.id)) }) { Icon(Icons.Rounded.ClosedCaption, null); Spacer(Modifier.width(6.dp)); Text(if (c.captions.isEmpty()) "Auto captions" else "Redo captions") }
            if (project.clips.size > 1) OutlinedButton({ manager.autoCaption(project.clips.map { it.id }) }) { Text("All clips") }
            if (c.captions.isNotEmpty()) TextButton({ manager.edit { p -> p.copy(clips = p.clips.map { if (it.id == c.id) it.copy(captions = emptyList()) else it }) } }) { Text("Clear") }
        }
    }
    if (c.captions.isNotEmpty()) Text("${c.captions.size} captions from speech · \"${c.captions.first().text.take(40)}…\"", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CaptionStyle.entries.forEach { st -> FilterChip(c.captionStyle == st, { onStyle(st, c.captionColor) }, { Text(st.label) }) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        CAPTION_COLORS.forEach { col ->
            val on = c.captionColor == col
            val ring by animateDpAsState(if (on) 3.dp else 0.dp, label = "swatch")
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(Color(0xFF000000 or col.toLong())).border(ring, MaterialTheme.colorScheme.primary, CircleShape)
                    .clickable { onStyle(c.captionStyle, col) },
            )
        }
    }
}

@Composable
private fun AudioPanel(project: StudioProject, playhead: Long, manager: StudioManager, busy: Boolean, onPick: () -> Unit) {
    var showBeats by remember { mutableStateOf(false) }
    val m = project.music
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onPick, enabled = !busy) { Icon(Icons.Rounded.MusicNote, null); Spacer(Modifier.width(6.dp)); Text(if (m == null) "Add music" else "Replace") }
        if (m != null) TextButton({ manager.edit { it.copy(music = null) } }, enabled = !busy) { Text("Remove") }
    }
    if (m != null) {
        Text(m.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        var vol by remember(m.volume) { mutableFloatStateOf(m.volume) }
        Text("Music volume ${(vol * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
        Slider(vol, { vol = it }, onValueChangeFinished = { manager.edit { it.copy(music = m.copy(volume = vol)) } }, enabled = !busy)
        var start by remember(m.startMs) { mutableFloatStateOf(m.startMs.toFloat()) }
        Text("Music starts at ${clock(start.toLong())}", style = MaterialTheme.typography.labelMedium)
        Slider(start, { start = it }, onValueChangeFinished = { manager.edit { it.copy(music = m.copy(startMs = start.toLong())) } }, valueRange = 0f..(m.durationMs - 100).toFloat().coerceAtLeast(1f), enabled = !busy)
        Text("Music loops to the length of the film.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text("Fade the film's sound", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0L to "No fades", 1_000L to "1 s", 2_000L to "2 s", 4_000L to "4 s").forEach { (ms, label) ->
            FilterChip(project.fadeInMs == ms && project.fadeOutMs == ms, { manager.edit { it.copy(fadeInMs = ms, fadeOutMs = ms) } }, { Text(label) }, enabled = !busy)
        }
    }
    Text("In at the start and out at the end, in the exported video.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    TextButton({ showBeats = !showBeats }) { Text(if (showBeats) "Hide beat tools" else "Beat tools") }
    if (showBeats) {
        var bpm by remember(project.bpm) { mutableStateOf(project.bpm.toString()) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(bpm, { bpm = it.filter(Char::isDigit).take(3) }, label = { Text("BPM") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(110.dp))
            OutlinedButton({ bpm.toIntOrNull()?.takeIf { it in 30..300 }?.let { v -> manager.edit { it.copy(bpm = v) } } }, enabled = !busy && bpm.toIntOrNull() in 30..300) { Text("Set") }
            TextButton({ manager.edit { it.copy(beatOffsetMs = playhead) } }, enabled = !busy) { Text("Beat 1 here") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
            OutlinedButton({ manager.edit { Timeline.cutToBeats(it, 4) } }, enabled = !busy && project.clips.isNotEmpty()) { Text("Cut every clip to 4 beats") }
        }
        Text("Uses the BPM you enter; it does not detect beats.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
    }
}

@Composable
private fun FormatPanel(project: StudioProject, busy: Boolean, manager: StudioManager) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CanvasShape.entries.forEach { shape ->
            val on = project.shape == shape
            Column(
                Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = !busy) { manager.edit { it.copy(shape = shape) } }.padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.height(44.dp).aspectRatio(shape.ratio).border(2.dp, if (on) Accent else MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)))
                Spacer(Modifier.height(4.dp))
                Text(shape.label, style = MaterialTheme.typography.labelSmall, color = if (on) Accent else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        FilterChip(!project.fillCanvas, { manager.edit { it.copy(fillCanvas = false) } }, { Text("Fit (whole picture)") }, enabled = !busy)
        FilterChip(project.fillCanvas, { manager.edit { it.copy(fillCanvas = true) } }, { Text("Fill (crop edges)") }, enabled = !busy)
    }
}

// ───────────────────────────────── sheets ─────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportSheet(project: StudioProject, manager: StudioManager, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val work by manager.work.collectAsStateWithLifecycle()
    val exports by manager.exports.collectAsStateWithLifecycle()
    val log by manager.log.lines.collectAsStateWithLifecycle()
    var details by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Export", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("${project.width} × ${project.height} · ${clock(project.durationMs)} · ${project.clips.size} clips · MP4, no watermark", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Text("Made for", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExportPreset.entries.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { preset ->
                                val on = preset.matches(project)
                                val bg by animateColorAsState(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow, label = "preset")
                                Column(
                                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(bg).clickable(enabled = !work.active) { manager.edit { preset.applyTo(it) } }.padding(12.dp),
                                ) {
                                    Text(preset.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(preset.blurb, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    listOf(720 to "720p", 1080 to "1080p HD").forEach { (side, label) -> FilterChip(project.shortSide == side, { manager.edit { it.copy(shortSide = side) } }, { Text(label) }, enabled = !work.active) }
                }
            }
            item {
                if (work.active) {
                    LinearProgressIndicator(progress = { (work.percent ?: 0) / 100f }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Rendering · ${work.percent ?: 0}%", Modifier.weight(1f))
                        TextButton(manager::cancelWork) { Text("Cancel") }
                    }
                } else {
                    Button({ manager.export() }, Modifier.fillMaxWidth().height(52.dp), enabled = project.clips.isNotEmpty(), shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Rounded.Movie, null); Spacer(Modifier.width(8.dp)); Text("Export video", fontWeight = FontWeight.Bold)
                    }
                }
                TextButton({ details = !details }) { Text(if (details) "Hide render log" else "Render log") }
                if (details) Terminal(log, work.active, startExpanded = true)
            }
            exports.firstOrNull()?.let { latest -> item { app.ultimate.core.SendTo(Uri.parse(latest.uri), "video/mp4", "studio", onSent = onDismiss) } }
            if (exports.isNotEmpty()) item { Text("Your videos", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
            items(exports, key = { it.id }) { e ->
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("${formatBytes(e.bytes)} · ${e.width}×${e.height} · ${clock(e.durationMs)}") },
                    trailingContent = {
                        IconButton({
                            runCatching {
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, Uri.parse(e.uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share video"))
                            }.onFailure { manager.error.value = it.message }
                        }) { Icon(Icons.Rounded.Share, "Share") }
                    },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectsSheet(manager: StudioManager, busy: Boolean, onDismiss: () -> Unit, onOpened: () -> Unit) {
    val projects by manager.projects.collectAsStateWithLifecycle()
    val current by manager.project.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Projects", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                var name by remember(current.id) { mutableStateOf(current.name) }
                OutlinedTextField(name, { name = it.take(80) }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("This project's name") }, singleLine = true, enabled = !busy)
                LaunchedEffect(name) { delay(500); if (name.isNotBlank() && name != current.name) manager.edit { it.copy(name = name) } }
                Button({ manager.newProject(); onOpened(); onDismiss() }, enabled = !busy, modifier = Modifier.padding(top = 8.dp)) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("New project") }
            }
            items(projects.filter { it.id != current.id }, key = { it.id }) { p ->
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(p.name) }, supportingContent = { Text("${p.clips.size} clips · ${clock(p.durationMs)}") },
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = !busy) { manager.open(p); onOpened(); onDismiss() },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ───────────────────────────────── helpers ─────────────────────────────────

/** Approximates each look for thumbnails; the preview and export use the real Media3 effects. */
private fun lookFilter(look: Look): ColorFilter? = when (look) {
    Look.Natural -> null
    Look.Cinema -> ColorFilter.colorMatrix(ColorMatrix(contrast(1.18f, r = 1.04f, b = 0.94f)))
    Look.Warm -> ColorFilter.colorMatrix(ColorMatrix(contrast(1f, r = 1.12f, g = 1.02f, b = 0.88f)))
    Look.Noir -> ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f); timesAssign(ColorMatrix(contrast(1.25f))) })
}

private fun contrast(k: Float, r: Float = 1f, g: Float = 1f, b: Float = 1f): FloatArray {
    val t = 128f * (1 - k)
    return floatArrayOf(k * r, 0f, 0f, 0f, t, 0f, k * g, 0f, 0f, t, 0f, 0f, k * b, 0f, t, 0f, 0f, 0f, 1f, 0f)
}

private fun fmtSpeed(s: Float) = if (s == s.toInt().toFloat()) s.toInt().toString() else String.format(Locale.US, "%.2f", s).trimEnd('0')

private fun rulerLabel(ms: Long): String = if (ms % 1000 == 0L) String.format(Locale.US, "%d:%02d", ms / 60000, ms / 1000 % 60) else String.format(Locale.US, "%.1fs", ms / 1000f)

private fun clock(ms: Long): String = String.format(Locale.US, "%02d:%02d.%01d", ms / 60000, ms / 1000 % 60, ms % 1000 / 100)
