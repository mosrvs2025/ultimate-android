package app.ultimate.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlin.math.abs

/** What changes the picture of a clip. Speed and volume are not here: they are applied live without reloading. */
private data class PictureKey(
    val id: String, val uri: String, val startMs: Long, val endMs: Long, val rotation: Int, val look: Look, val brightness: Float,
    val title: String, val titlePosition: Int, val shape: CanvasShape, val fill: Boolean, val shortSide: Int,
)

/**
 * Plays the project as one film on a single output clock. [playheadMs] is the position on that clock; while playing, the preview
 * reports progress through [onPlayhead] and steps from clip to clip. A playhead change that did not come from playback is a seek.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun StudioPreview(
    project: StudioProject,
    playheadMs: Long,
    playing: Boolean,
    onPlayhead: (Long) -> Unit,
    onEnded: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val video = remember { ExoPlayer.Builder(context).build() }
    val music = remember { ExoPlayer.Builder(context).build() }
    val located = Timeline.locate(project, playheadMs)
    val clip = located?.first
    val local = located?.second ?: 0L
    val current by rememberUpdatedState(project)
    val playhead by rememberUpdatedState(playheadMs)
    val report by rememberUpdatedState(onPlayhead)
    val ended by rememberUpdatedState(onEnded)
    val failed by rememberUpdatedState(onError)
    var loadedId by remember { mutableStateOf<String?>(null) }
    var reported by remember { mutableLongStateOf(-1L) }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_ENDED) return
                // Step to the next clip, or stop at the end of the film.
                val p = current
                val here = Timeline.locate(p, playhead)?.first ?: return
                val next = p.clips.getOrNull(p.clips.indexOfFirst { it.id == here.id } + 1)
                if (next != null) report(Timeline.offset(p, next.id)) else ended()
            }
            override fun onPlayerError(error: PlaybackException) = failed(error.message ?: "Preview failed")
        }
        video.addListener(listener)
        onDispose { video.removeListener(listener); video.release(); music.release() }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) { video.pause(); music.pause() } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val key = clip?.let { PictureKey(it.id, it.uri, it.startMs, it.endMs, it.rotation, it.look, it.brightness, it.title, it.titlePosition, project.shape, project.fillCanvas, project.shortSide) }
    LaunchedEffect(key) {
        if (clip == null) { video.stop(); loadedId = null; return@LaunchedEffect }
        // Typing a title changes the picture on every key; wait for a pause before rebuilding it.
        if (loadedId == clip.id) delay(250)
        val at = Timeline.locate(current, playhead)?.second ?: 0L
        video.setVideoEffects(StudioEffects.video(clip, current, false))
        video.setMediaItem(StudioEffects.mediaItem(clip), (at * clip.speed).toLong())
        video.prepare()
        video.playWhenReady = playing
        loadedId = clip.id
        reported = playhead
    }
    LaunchedEffect(clip?.speed) { clip?.let { video.playbackParameters = PlaybackParameters(it.speed, 1f) } }
    LaunchedEffect(clip?.volume) { clip?.let { video.volume = it.volume.coerceIn(0f, 1f) } }

    // A playhead that moved without playback (scrubbing, a tapped clip, undo) is a seek within the loaded clip.
    LaunchedEffect(playheadMs, loadedId) {
        if (clip != null && loadedId == clip.id && (!playing || abs(playheadMs - reported) > 300)) {
            video.seekTo((local * clip.speed).toLong())
            reported = playheadMs
        }
    }

    LaunchedEffect(playing, loadedId) {
        video.playWhenReady = playing
        if (!playing) { music.pause(); return@LaunchedEffect }
        while (true) {
            val c = clip
            if (c != null && loadedId == c.id && video.playbackState == Player.STATE_READY) {
                val at = Timeline.offset(current, c.id) + (video.currentPosition / c.speed).toLong().coerceIn(0, c.durationMs)
                reported = at
                report(at)
            }
            delay(33)
        }
    }

    LaunchedEffect(project.music?.uri) {
        val m = project.music
        if (m == null) music.stop() else { music.setMediaItem(MediaItem.fromUri(m.uri)); music.prepare() }
    }
    LaunchedEffect(project.music?.volume) { project.music?.let { music.volume = it.volume.coerceIn(0f, 1f) } }
    LaunchedEffect(playing, project.music) {
        val m = project.music ?: return@LaunchedEffect
        while (playing) {
            val loop = (m.durationMs - m.startMs).coerceAtLeast(1)
            val expected = m.startMs + playhead % loop
            if (abs(music.currentPosition - expected) > 150) music.seekTo(expected)
            music.playWhenReady = video.isPlaying
            delay(100)
        }
        music.pause()
    }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (clip == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Your next film starts here", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text("Add videos to begin", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            AndroidView(
                factory = {
                    PlayerView(it).apply {
                        useController = false
                        player = video
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        // Keep the last frame while the next clip loads, instead of flashing black between clips.
                        setKeepContentOnPlayerReset(true)
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
