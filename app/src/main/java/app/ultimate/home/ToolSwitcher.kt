package app.ultimate.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
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

        // The bubble melts into the jump panel and back.
        if (open) BackHandler { open = false }
        JumpPanel(app, current, running, open, androidx.compose.ui.geometry.Rect(x.value, y.value, x.value + sizePx, y.value + sizePx)) { open = false }

        if (!open) {
            val pressed by animateFloatAsState(if (tucked) 0.55f else 1f, label = "bubble")
            val tuck by animateFloatAsState(if (tucked) (if (BubblePlace.right) sizePx * 0.55f else -sizePx * 0.55f) else 0f, label = "tuck")
            Surface(
                shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, shadowElevation = 8.dp,
                modifier = Modifier.offset { IntOffset((x.value + tuck).roundToInt(), y.value.roundToInt()) }.size(size).graphicsLayer { alpha = 0.92f * pressed }
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
                                x.snapTo((x.value + drag.x).coerceIn(0f, w - sizePx))
                                y.snapTo((y.value + drag.y).coerceIn(margin * 6, h - sizePx - margin * 6))
                            }
                        }
                    }
                    .clickable { haptics.performHapticFeedback(HapticFeedbackType.LongPress); open = true },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Apps, "Switch tool", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    if (running.isNotEmpty()) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(9.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                }
            }
        }
    }
}
