package app.ultimate.squeeze.ui

import app.ultimate.core.*
import android.graphics.Bitmap
import android.net.Uri
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private const val DRIFT_LIMIT_MS = 90L
private const val FALLBACK_CLIP_MS = 4000L

/**
 * Plays the saved original clip and the matching stretch of the compressed video in lock-step, with a draggable
 * reveal between them. Left of the handle is the original, right is the squeezed result.
 */
@Composable
fun LiveCompare(
    original: File,
    squeezed: Uri,
    clipStartMs: Long,
    aspect: Float,
    beforeStill: Bitmap?,
    afterStill: Bitmap?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val a = remember { ExoPlayer.Builder(context).build() }
    val b = remember { ExoPlayer.Builder(context).build() }
    var playing by remember { mutableStateOf(true) }
    var muted by remember { mutableStateOf(true) }
    var clipMs by remember { mutableLongStateOf(FALLBACK_CLIP_MS) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var fraction by remember { mutableFloatStateOf(0.5f) }

    DisposableEffect(original, squeezed) {
        a.setMediaItem(MediaItem.fromUri(Uri.fromFile(original)))
        b.setMediaItem(MediaItem.fromUri(squeezed))
        a.volume = 0f
        a.prepare()
        b.prepare()
        b.seekTo(clipStartMs)
        val ready = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && a.duration > 0) clipMs = a.duration
            }
        }
        a.addListener(ready)
        onDispose {
            a.removeListener(ready)
            a.release()
            b.release()
        }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) playing = false }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(muted) { b.volume = if (muted) 0f else 1f }

    // Single clock: the original clip drives, the squeezed video follows and is nudged back when it drifts.
    LaunchedEffect(playing) {
        a.playWhenReady = playing
        b.playWhenReady = playing
        while (playing) {
            val p = a.currentPosition
            val end = a.playbackState == Player.STATE_ENDED || (clipMs > 0 && p >= clipMs - 60)
            if (end) {
                a.seekTo(0)
                b.seekTo(clipStartMs)
            } else if (abs(b.currentPosition - (clipStartMs + p)) > DRIFT_LIMIT_MS && b.playbackState == Player.STATE_READY) {
                b.seekTo(clipStartMs + p)
            }
            positionMs = a.currentPosition.coerceIn(0, clipMs)
            delay(80)
        }
    }

    Column(modifier) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black)
                .pointerInput(Unit) { detectTapGestures { playing = !playing } }
                .pointerInput(Unit) {
                    detectDragGestures(onDragStart = { fraction = (it.x / size.width).coerceIn(0f, 1f) }) { change, _ ->
                        change.consume()
                        fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                },
        ) {
            val w = maxWidth
            val h = maxHeight
            val widthPx = constraints.maxWidth.toFloat()
            afterStill?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            AndroidView({ ctx -> TextureView(ctx).also { b.setVideoTextureView(it) } }, Modifier.fillMaxSize())

            // The original sits on top, clipped to the left of the handle by layout so it works with any video surface.
            Box(Modifier.width(w * fraction).fillMaxHeight().clipToBounds()) {
                Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(w, h)) {
                    beforeStill?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    AndroidView({ ctx -> TextureView(ctx).also { a.setVideoTextureView(it) } }, Modifier.fillMaxSize())
                }
            }

            Box(Modifier.offset { IntOffset((widthPx * fraction).roundToInt() - 1.5.dp.roundToPx(), 0) }.width(3.dp).fillMaxHeight().background(Color.White))
            Box(
                Modifier.offset { IntOffset((widthPx * fraction).roundToInt() - 18.dp.roundToPx(), 0) }.align(Alignment.CenterStart)
                    .size(36.dp).clip(CircleShape).background(Color.White),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.SwapHoriz, null, tint = Color.Black) }
            Label("Original", Modifier.align(Alignment.TopStart))
            Label("Squeezed", Modifier.align(Alignment.TopEnd))
        }

        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ playing = !playing }) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play") }
            Slider(
                value = positionMs.toFloat(),
                onValueChange = { v ->
                    positionMs = v.toLong()
                    a.seekTo(positionMs)
                    b.seekTo(clipStartMs + positionMs)
                },
                valueRange = 0f..max(clipMs, 1L).toFloat(),
                modifier = Modifier.weight(1f),
            )
            IconButton({ muted = !muted }) { Icon(if (muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, if (muted) "Unmute" else "Mute") }
        }
        Text(
            "Drag across the video to reveal the original. Tap to pause.",
            Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Label(text: String, modifier: Modifier) = Text(
    text, modifier.padding(10.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 3.dp),
    color = Color.White, style = MaterialTheme.typography.labelMedium,
)
