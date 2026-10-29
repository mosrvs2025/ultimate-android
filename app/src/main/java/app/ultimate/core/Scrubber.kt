package app.ultimate.core

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.launch
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * A waveform you can touch: bars rise in when it appears, the played part glows in the primary color, and the playhead dot swells
 * while a finger holds it, with the time floating above. Tap or drag anywhere to move it.
 */
@Composable
fun ScrubWaveform(
    peaks: List<Float>,
    progress: Float,
    durationMs: Long,
    onScrub: (fraction: Float, done: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onScrubStart: () -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    val measurer = rememberTextMeasurer()
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val rest = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    val bubble = MaterialTheme.colorScheme.inverseSurface
    val bubbleText = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.inverseOnSurface)
    var touching by remember { mutableStateOf(false) }
    var touchFraction by remember { mutableFloatStateOf(0f) }
    val dot by animateFloatAsState(if (touching) 1f else 0f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "dot")
    val reveal = remember(peaks.size) { Animatable(0f) }
    LaunchedEffect(peaks.size) { reveal.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    val shown = if (touching) touchFraction else progress
    val scrub by rememberUpdatedState(onScrub)
    val start by rememberUpdatedState(onScrubStart)
    // Bars ease towards new peaks, so switching between two versions of a sound morphs instead of jumping.
    val bars = if (peaks.isEmpty()) 64 else peaks.size
    val heights = remember(bars) { List(bars) { Animatable(0.04f) } }
    LaunchedEffect(peaks) {
        heights.forEachIndexed { i, a -> launch { a.animateTo((peaks.getOrNull(i) ?: 0.04f).coerceIn(0.04f, 1f), tween(450, i * 2, FastOutSlowInEasing)) } }
    }

    Canvas(
        modifier.fillMaxWidth().height(72.dp).pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                touching = true
                start()
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                touchFraction = (down.position.x / size.width).coerceIn(0f, 1f)
                scrub(touchFraction, false)
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    change.consume()
                    touchFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    scrub(touchFraction, false)
                }
                scrub(touchFraction, true)
                touching = false
            }
        },
    ) {
        val top = 24.dp.toPx()
        val waveH = size.height - top
        val slot = size.width / bars
        val barW = slot * 0.62f
        val playedBrush = Brush.horizontalGradient(listOf(tertiary, primary), 0f, size.width * shown.coerceAtLeast(0.01f))
        for (i in 0 until bars) {
            val stagger = ((reveal.value * 1.6f) - i.toFloat() / bars * 0.6f).coerceIn(0f, 1f)
            val h = (heights[i].value * waveH * stagger).coerceAtLeast(2.dp.toPx())
            val x = i * slot + (slot - barW) / 2
            val tl = Offset(x, top + (waveH - h) / 2)
            if ((i + 0.5f) / bars <= shown) drawRoundRect(playedBrush, tl, Size(barW, h), CornerRadius(barW / 2))
            else drawRoundRect(rest, tl, Size(barW, h), CornerRadius(barW / 2))
        }
        playhead(shown, top, primary, dot)
        if (dot > 0.05f) {
            val label = measurer.measure(formatDuration((durationMs * shown).toLong()), bubbleText)
            val w = label.size.width + 16.dp.toPx(); val hh = label.size.height + 6.dp.toPx()
            val cx = (size.width * shown).coerceIn(w / 2, size.width - w / 2)
            val y = top - hh - 2.dp.toPx() + (1 - dot) * 8.dp.toPx()
            drawRoundRect(bubble.copy(alpha = dot), Offset(cx - w / 2, y), Size(w, hh), CornerRadius(hh / 2))
            drawText(label, topLeft = Offset(cx - label.size.width / 2, y + 3.dp.toPx()), alpha = dot)
        }
    }
}

private fun DrawScope.playhead(fraction: Float, top: Float, color: Color, grow: Float) {
    val x = size.width * fraction
    val mid = top + (size.height - top) / 2
    drawLine(color.copy(alpha = 0.5f + grow * 0.5f), Offset(x, top), Offset(x, size.height), strokeWidth = 2.dp.toPx())
    val r = (5 + grow * 5).dp.toPx()
    drawCircle(color.copy(alpha = 0.18f * grow), r * 2.4f, Offset(x, mid))
    drawCircle(color, r, Offset(x, mid))
    drawCircle(Color.White.copy(alpha = 0.85f), r * 0.38f, Offset(x, mid))
}

/** One ExoPlayer per uri, released with the composable; progress is read every frame so the playhead glides. */
@Stable
class PlayerState(val player: ExoPlayer) {
    var playing by mutableStateOf(false)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    val fraction get() = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    private var resume = false

    fun toggle() = if (playing) player.pause() else player.play()
    fun beginScrub() { resume = player.isPlaying; player.pause() }
    fun scrub(f: Float, done: Boolean) {
        if (duration <= 0) return
        position = (duration * f).toLong()
        player.seekTo(position)
        if (done && resume) player.play()
    }
}

@Composable
fun rememberPlayer(uri: Uri): PlayerState {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state = remember(uri) { PlayerState(ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(uri)); prepare() }) }
    DisposableEffect(state) {
        val p = state.player
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { state.playing = isPlaying }
            override fun onPlaybackStateChanged(s: Int) {
                if (s == Player.STATE_READY) state.duration = maxOf(p.duration, 0)
                if (s == Player.STATE_ENDED) { p.pause(); p.seekTo(0); state.position = 0 }
            }
        }
        p.addListener(listener)
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) p.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); p.removeListener(listener); p.release() }
    }
    LaunchedEffect(state, state.playing) {
        while (state.playing) withFrameMillis { state.position = state.player.currentPosition }
    }
    return state
}

@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
    FilledIconButton(onClick, Modifier.size(48.dp)) {
        AnimatedContent(playing, transitionSpec = { (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut()) }, label = "play") { p ->
            Icon(if (p) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (p) "Pause" else "Play")
        }
    }
}

@Composable
private fun Times(position: Long, duration: Long) = Row(Modifier.fillMaxWidth()) {
    Text(formatDuration(position), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.weight(1f))
    Text(formatDuration(duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Play/pause with a touchable waveform, for audio saved by any tool. */
@Composable
fun AudioPlayerBar(uri: Uri, peaks: List<Float>, modifier: Modifier = Modifier) {
    val p = rememberPlayer(uri)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        PlayButton(p.playing, p::toggle)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            ScrubWaveform(peaks, p.fraction, p.duration, p::scrub, onScrubStart = p::beginScrub)
            Times(p.position, p.duration)
        }
    }
}

/**
 * Two versions of the same sound on one waveform. Flipping between them keeps the place and keeps playing, so the difference is
 * all you hear, and the bars morph to the chosen version's shape.
 */
@Composable
fun AbPlayer(a: Uri, b: Uri, peaksA: List<Float>, peaksB: List<Float>, labelA: String, labelB: String, modifier: Modifier = Modifier) {
    val pa = rememberPlayer(a)
    val pb = rememberPlayer(b)
    var useB by remember { mutableStateOf(true) }
    val cur = if (useB) pb else pa
    fun pick(toB: Boolean) {
        if (toB == useB) return
        val from = cur; val to = if (toB) pb else pa
        val wasPlaying = from.playing
        from.player.pause()
        to.position = from.position.coerceAtMost(maxOf(to.duration, 0))
        to.player.seekTo(to.position)
        if (wasPlaying) to.player.play()
        useB = toB
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(!useB, { pick(false) }, SegmentedButtonDefaults.itemShape(0, 2)) { Text(labelA) }
            SegmentedButton(useB, { pick(true) }, SegmentedButtonDefaults.itemShape(1, 2)) { Text(labelB) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayButton(cur.playing, cur::toggle)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                ScrubWaveform(if (useB) peaksB else peaksA, cur.fraction, cur.duration, cur::scrub, onScrubStart = cur::beginScrub)
                Times(cur.position, cur.duration)
            }
        }
    }
}
