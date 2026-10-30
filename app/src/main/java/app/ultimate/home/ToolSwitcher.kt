package app.ultimate.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.UltimateApp
import app.ultimate.core.Tool
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Where the bubble rests, shared by every tool so it stays where the user put it. */
private object BubblePlace {
    var right by mutableStateOf(true)
    var heightFraction by mutableStateOf(0.62f)
}

/**
 * A small bubble floating over every tool. Drag it anywhere; it snaps to the nearer edge. Tap it to open a panel with every
 * tool plus Home, Library and Activity, so any tool is one tap away without backing out.
 */
@Composable
fun ToolSwitcher(app: UltimateApp, current: Tool) {
    var open by remember { mutableStateOf(false) }
    var catalog by remember { mutableStateOf(false) }
    // After a moment of rest the bubble tucks half off the edge so it does not cover content; touching it brings it back.
    var tucked by remember { mutableStateOf(false) }
    var touched by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(touched, open) { tucked = false; if (!open) { kotlinx.coroutines.delay(2500); tucked = true } }
    val haptics = LocalHapticFeedback.current
    val running by remember { combine(app.work.sources.map { s -> s.work.map { s.workId to it.active } }) { it.filter { (_, a) -> a }.map { it.first }.toSet() } }
        .collectAsStateWithLifecycle(emptySet())

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val size = 50.dp
        val sizePx = with(density) { size.toPx() }
        val margin = with(density) { 8.dp.toPx() }
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val scope = rememberCoroutineScope()
        val x = remember { Animatable(if (BubblePlace.right) w - sizePx - margin else margin) }
        val y = remember { Animatable(BubblePlace.heightFraction * h) }

        androidx.compose.runtime.LaunchedEffect(w, h) {
            x.snapTo(if (BubblePlace.right) (w - sizePx - margin).coerceAtLeast(0f) else margin.coerceAtMost((w - sizePx).coerceAtLeast(0f)))
            y.snapTo((BubblePlace.heightFraction * h).coerceIn(0f, (h - sizePx).coerceAtLeast(0f)))
        }
        AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(remember { MutableInteractionSource() }, null) { open = false })
        }
        AnimatedVisibility(
            open, Modifier.align(Alignment.BottomCenter),
            enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy), transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (BubblePlace.right) 1f else 0f, 1f)) + fadeIn(),
            exit = scaleOut() + fadeOut(),
        ) {
            BackHandler { open = false }
            if (catalog) Panel(app, current, running) { open = false; catalog = false }
            else OrbitPanel(app, current, running, onCatalog = { catalog = true }) { open = false }
        }

        if (!open) {
            val pressed by animateFloatAsState(if (tucked) 0.55f else 1f, label = "bubble")
            val tuck by animateFloatAsState(if (tucked) (if (BubblePlace.right) sizePx * 0.55f else -sizePx * 0.55f) else 0f, label = "tuck")
            InstrumentOrb(current, running.isNotEmpty(),
                Modifier.offset { IntOffset((x.value + tuck).roundToInt(), y.value.roundToInt()) }.size(size).graphicsLayer { alpha = 0.92f * pressed }
                    .pointerInput(w, h) {
                        detectDragGestures(
                            onDragStart = { touched++ },
                            onDragEnd = {
                                touched++
                                val right = x.value + sizePx / 2 > w / 2
                                BubblePlace.right = right
                                BubblePlace.heightFraction = y.value / h
                                scope.launch { x.animateTo(if (right) w - sizePx - margin else margin, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) }
                            },
                        ) { change, drag ->
                            change.consume()
                            scope.launch {
                                x.snapTo((x.value + drag.x).coerceIn(0f, (w - sizePx).coerceAtLeast(0f)))
                                y.snapTo((y.value + drag.y).coerceIn(0f, (h - sizePx).coerceAtLeast(0f)))
                            }
                        }
                    }, onClick = { catalog = false; open = true }, onLongClick = { app.shell.showHome() })
        }
    }
}

@Composable
private fun Panel(app: UltimateApp, current: Tool, running: Set<String>, close: () -> Unit) {
    val shell = app.shell
    Surface(
        Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding().shadow(24.dp, RoundedCornerShape(28.dp)),
        shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Jump to", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp))
            app.tools.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { t ->
                        Tile(t.icon, t.title, Modifier.weight(1f), selected = t.id == current.id, busy = t.id in running) {
                            close(); if (t.id != current.id) shell.open(t)
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(vertical = 4.dp)) {
                Tile(Icons.Rounded.Home, "Home", Modifier.weight(1f)) { close(); shell.showHome() }
                Tile(Icons.Rounded.VideoLibrary, "Library", Modifier.weight(1f)) { close(); shell.showLibrary() }
                Tile(Icons.Rounded.History, "Activity", Modifier.weight(1f), busy = running.isNotEmpty()) { close(); shell.showActivity() }
            }
        }
    }
}

@Composable
private fun Tile(icon: ImageVector, label: String, modifier: Modifier, selected: Boolean = false, busy: Boolean = false, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(16.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer) }
            if (busy) Box(Modifier.align(Alignment.TopEnd).size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.width(76.dp))
    }
}

/** The current tool stays at the center; recent tools and its results are one tap away. */
@Composable
private fun OrbitPanel(app: UltimateApp, current: Tool, running: Set<String>, onCatalog: () -> Unit, close: () -> Unit) {
    val nearby = remember(current.id, running) {
        HomeLayout.constellation(app.tools.map { it.id }.filter { it != current.id && it != "orb" }, app.insights.recentOpens(), running, System.currentTimeMillis(), 2)
            .mapNotNull { id -> app.tools.firstOrNull { it.id == id } }
    }
    Surface(Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding(), shape = RoundedCornerShape(28.dp), color = InstrumentInk, contentColor = InstrumentLight) {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(current.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
            val headline by current.headline.collectAsStateWithLifecycle()
            Text(headline, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            BoxWithConstraints(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                nearby.forEachIndexed { i, t ->
                    Tile(t.icon, t.title, Modifier.align(if (i == 0) Alignment.TopStart else Alignment.TopEnd), busy = t.id in running) { close(); app.shell.openFrom(t, if (BubblePlace.right) EntryOrigin.ORB_RIGHT else EntryOrigin.ORB_LEFT) }
                }
                InstrumentOrb(current, current.id in running, Modifier.size(80.dp).align(Alignment.BottomCenter), onClick = { close(); app.shell.showHome() })
            }
            Text("Hold the orb to return home", style = MaterialTheme.typography.labelSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                androidx.compose.material3.TextButton({ close(); app.shell.showLibraryOf(current.id) }) { Text("Results", color = InstrumentLight) }
                androidx.compose.material3.TextButton({ close(); app.shell.showActivity() }) { Text("Activity", color = InstrumentLight) }
                androidx.compose.material3.TextButton({ close(); app.shell.showHome() }) { Text("Home", color = InstrumentLight) }
            }
            if (current.id in running) app.work.sources.firstOrNull { it.workId == current.id }?.let { source ->
                androidx.compose.material3.TextButton({ source.cancelWork(); close() }) { Text("${source.cancelLabel} ${current.title}", color = instrumentAccent(current.id)) }
            }
            androidx.compose.material3.TextButton(onCatalog) { Text("All tools", color = instrumentAccent(current.id)) }
        }
    }
}
