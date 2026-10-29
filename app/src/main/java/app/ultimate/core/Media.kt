package app.ultimate.core

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Bars for a waveform. Bars beyond [peaks] (a conversion still in progress) are drawn as faint placeholders,
 * and bars up to [progress] use [played], the rest [rest].
 */
@Composable
fun Waveform(
    peaks: List<Float>,
    modifier: Modifier = Modifier,
    bars: Int = peaks.size.coerceAtLeast(1),
    progress: Float = 1f,
    played: Color = MaterialTheme.colorScheme.primary,
    rest: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
    ghost: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
) {
    Canvas(modifier) {
        val slot = size.width / bars
        val barWidth = slot * 0.6f
        for (i in 0 until bars) {
            val value = peaks.getOrNull(i)
            val h = ((value ?: 0.04f).coerceAtLeast(0.04f) * size.height).coerceAtMost(size.height)
            val color = when {
                value == null -> ghost
                (i + 0.5f) / bars <= progress -> played
                else -> rest
            }
            drawRoundRect(color, Offset(i * slot + (slot - barWidth) / 2, (size.height - h) / 2), Size(barWidth, h), CornerRadius(barWidth / 2))
        }
    }
}

/** A frame from a video, taken a third of the way in so an opening black frame does not stand in for the whole clip. */
@Composable
fun VideoThumb(uri: Uri, modifier: Modifier, durationMs: Long = 0) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) { frameAt(context, uri, durationMs * 300) ?: frameAt(context, uri, 0) }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Icon(Icons.Rounded.VideoLibrary, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun frameAt(context: android.content.Context, uri: Uri, atUs: Long): Bitmap? = runCatching {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(context, uri)
        r.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 256, 256)
    } finally {
        r.release()
    }
}.getOrNull()
