package app.ultimate.player

import android.content.ComponentName
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import app.ultimate.MainActivity
import app.ultimate.core.UltimateTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await

/**
 * Ultimate's player for anything you open from another app ("Open with Ultimate+"). Music gets its album art (or a
 * colour drawn from the file), title and artist, a real seek bar, skip ±10 s, the other tracks in the same folder as a
 * queue, repeat, speed and a sleep timer, and keeps playing in the background with lock-screen and notification
 * controls. Video plays full screen with tap-to-show controls; pictures zoom. Nothing is copied or uploaded.
 */
class PlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data ?: run { finish(); return }
        val type = intent.type ?: contentResolver.getType(uri) ?: ""
        setContent { UltimateTheme { PlayerScreen(uri, type, onClose = { finish() }) } }
    }
}

private fun nameOf(c: android.content.Context, uri: Uri) = runCatching {
    c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
}.getOrNull() ?: uri.lastPathSegment ?: "File"

/** The other tracks in the same folder, in name order, when the file came from the phone's media library. */
private fun queueFor(c: android.content.Context, uri: Uri): List<Uri> = runCatching {
    if (uri.authority != MediaStore.AUTHORITY) return listOf(uri)
    val bucket = c.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.BUCKET_ID), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: return listOf(uri)
    val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    c.contentResolver.query(base, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.BUCKET_ID}=?", arrayOf(bucket), "${MediaStore.MediaColumns.DISPLAY_NAME} COLLATE NOCASE")?.use { cur ->
        buildList { while (cur.moveToNext()) add(android.content.ContentUris.withAppendedId(base, cur.getLong(0))) }
    }?.takeIf { it.isNotEmpty() } ?: listOf(uri)
}.getOrDefault(listOf(uri))

private fun art(c: android.content.Context, uri: Uri): android.graphics.Bitmap? = runCatching {
    MediaMetadataRetriever().run { setDataSource(c, uri); embeddedPicture.also { release() } }?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
}.getOrNull()

@Composable
private fun PlayerScreen(uri: Uri, type: String, onClose: () -> Unit) {
    val c = LocalContext.current
    if (type.startsWith("image")) {
        Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
            TopBar(nameOf(c, uri), onClose); Box(Modifier.weight(1f)) { Picture(uri) }; Actions(uri, type)
        }
        return
    }
    // Playback lives in a media service, so it carries on in the background with system controls.
    var controller by remember { mutableStateOf<MediaController?>(null) }
    LaunchedEffect(uri) {
        val ctl = runCatching { MediaController.Builder(c, SessionToken(c, ComponentName(c, PlaybackService::class.java))).buildAsync().await() }.getOrNull() ?: return@LaunchedEffect
        val video = type.startsWith("video")
        val queue = if (video) listOf(uri) else queueFor(c, uri)
        val start = queue.indexOfFirst { it == uri }.coerceAtLeast(0)
        ctl.setMediaItems(queue.map { u -> MediaItem.Builder().setUri(u).setMediaMetadata(MediaMetadata.Builder().setTitle(nameOf(c, u)).build()).build() }, if (queue[start] == uri) start else 0, 0)
        ctl.prepare(); ctl.play(); controller = ctl
    }
    DisposableEffect(Unit) { onDispose { controller?.release() } }
    val ctl = controller
    if (type.startsWith("video")) VideoPlayer(ctl, nameOf(c, uri), uri, type, onClose) else MusicPlayer(ctl, uri, type, onClose)
}

@Composable
private fun TopBar(title: String, onClose: () -> Unit) = Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    IconButton(onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Close", tint = Color.White) }
    Text(title, Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    Box(Modifier.size(48.dp))
}

/** The player's state, refreshed a few times a second for the seek bar. */
private class Now { var playing by mutableStateOf(false); var pos by mutableLongStateOf(0); var dur by mutableLongStateOf(0); var index by mutableIntStateOf(0); var count by mutableIntStateOf(1); var title by mutableStateOf(""); var repeat by mutableIntStateOf(Player.REPEAT_MODE_OFF); var current by mutableStateOf<Uri?>(null) }

@Composable
private fun rememberNow(ctl: MediaController?): Now {
    val n = remember { Now() }
    LaunchedEffect(ctl) {
        while (ctl != null) {
            n.playing = ctl.isPlaying; n.pos = ctl.currentPosition; n.dur = ctl.duration.coerceAtLeast(0); n.index = ctl.currentMediaItemIndex; n.count = ctl.mediaItemCount
            n.title = ctl.mediaMetadata.title?.toString() ?: ""; n.repeat = ctl.repeatMode; n.current = ctl.currentMediaItem?.localConfiguration?.uri
            delay(250)
        }
    }
    return n
}

private fun time(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

@Composable
private fun MusicPlayer(ctl: MediaController?, opened: Uri, type: String, onClose: () -> Unit) {
    val c = LocalContext.current
    val n = rememberNow(ctl)
    val track = n.current ?: opened
    val cover = remember(track) { art(c, track) }
    val meta = remember(track) { runCatching { MediaMetadataRetriever().run { setDataSource(c, track); listOf(extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE), extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST), extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)).also { release() } } }.getOrNull() }
    // A colour drawn from the file name, so tracks without art still look distinct.
    val hue = remember(track) { (track.toString().hashCode() and 0xffff) % 360f }
    val tint = Color.hsv(hue, 0.55f, 0.55f)
    var dragging by remember { mutableStateOf<Float?>(null) }
    var speed by remember { mutableFloatStateOf(1f) }
    var sleepAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(sleepAt) { if (sleepAt > 0) { delay(sleepAt - System.currentTimeMillis()); ctl?.pause(); sleepAt = 0 } }
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(tint, Color(0xFF0B0D10)))).statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        TopBar(if (n.count > 1) "Playing ${n.index + 1} of ${n.count}" else "Now playing", onClose)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth(0.82f).aspectRatio(1f).clip(RoundedCornerShape(24.dp)).background(tint.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                if (cover != null) Image(cover.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Bars(n.playing)
            }
        }
        Text(meta?.get(0) ?: n.title.substringBeforeLast('.').ifBlank { nameOf(c, track) }, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(listOfNotNull(meta?.get(1), meta?.get(2)).joinToString(" · ").ifBlank { type.substringAfter('/').uppercase() }, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        Slider(dragging ?: if (n.dur > 0) n.pos.toFloat() / n.dur else 0f, { dragging = it }, Modifier.fillMaxWidth().padding(top = 16.dp),
            onValueChangeFinished = { dragging?.let { ctl?.seekTo((it * n.dur).toLong()) }; dragging = null },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.25f)))
        Row(Modifier.fillMaxWidth()) {
            Text(time(((dragging ?: -1f).takeIf { it >= 0 }?.let { (it * n.dur).toLong() }) ?: n.pos), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
            Box(Modifier.weight(1f)); Text("-" + time((n.dur - n.pos).coerceAtLeast(0)), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton({ ctl?.seekToPreviousMediaItem() }, enabled = n.index > 0) { Icon(Icons.Rounded.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(34.dp)) }
            IconButton({ ctl?.seekTo((n.pos - 10_000).coerceAtLeast(0)) }) { Icon(Icons.Rounded.Replay10, "Back 10 seconds", tint = Color.White, modifier = Modifier.size(32.dp)) }
            Box(Modifier.size(76.dp).clip(CircleShape).background(Color.White).clickable { ctl?.let { if (it.isPlaying) it.pause() else it.play() } }, contentAlignment = Alignment.Center) {
                Icon(if (n.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (n.playing) "Pause" else "Play", tint = Color.Black, modifier = Modifier.size(44.dp))
            }
            IconButton({ ctl?.seekTo((n.pos + 10_000).coerceAtMost(n.dur)) }) { Icon(Icons.Rounded.Forward10, "Forward 10 seconds", tint = Color.White, modifier = Modifier.size(32.dp)) }
            IconButton({ ctl?.seekToNextMediaItem() }, enabled = n.index < n.count - 1) { Icon(Icons.Rounded.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(34.dp)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton({ val next = mapOf(1f to 1.25f, 1.25f to 1.5f, 1.5f to 2f, 2f to 0.75f, 0.75f to 1f)[speed] ?: 1f; speed = next; ctl?.setPlaybackSpeed(next) }) { Text("${speed}×", color = Color.White) }
            IconButton({ ctl?.repeatMode = when (n.repeat) { Player.REPEAT_MODE_OFF -> if (n.count > 1) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_ONE; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF } }) {
                Icon(if (n.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat", tint = if (n.repeat == Player.REPEAT_MODE_OFF) Color.White.copy(alpha = 0.5f) else Color.White)
            }
            TextButton({ sleepAt = when { sleepAt == 0L -> System.currentTimeMillis() + 15 * 60_000; sleepAt - System.currentTimeMillis() < 20 * 60_000 -> System.currentTimeMillis() + 30 * 60_000; sleepAt - System.currentTimeMillis() < 35 * 60_000 -> System.currentTimeMillis() + 60 * 60_000; else -> 0L } }) {
                Text(if (sleepAt == 0L) "Sleep" else "Sleep ${((sleepAt - System.currentTimeMillis()) / 60_000) + 1}m", color = Color.White)
            }
            IconButton({ share(c, track, type) }) { Icon(Icons.Rounded.Share, "Share", tint = Color.White) }
        }
        TextButton({ doMore(c, track, type) }, Modifier.padding(bottom = 8.dp)) { Text("Do more with it: trim, convert, transcribe…", color = Color.White.copy(alpha = 0.8f)) }
    }
}

/** Playing bars for tracks without album art. */
@Composable
private fun Bars(playing: Boolean) {
    val t = rememberInfiniteTransition(label = "bars")
    val phase by t.animateFloat(0f, 6.283f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "phase")
    Canvas(Modifier.fillMaxSize(0.5f)) {
        val n = 7; val w = size.width / (n * 1.6f)
        for (i in 0 until n) {
            val h = if (playing) (0.35f + 0.65f * ((kotlin.math.sin(phase + i * 0.9f) + 1) / 2)) else 0.2f
            drawRoundRect(Color.White.copy(alpha = 0.9f), androidx.compose.ui.geometry.Offset(i * w * 1.6f, size.height * (1 - h)), androidx.compose.ui.geometry.Size(w, size.height * h), androidx.compose.ui.geometry.CornerRadius(w / 2))
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoPlayer(ctl: MediaController?, name: String, uri: Uri, type: String, onClose: () -> Unit) {
    val c = LocalContext.current
    val n = rememberNow(ctl)
    var show by remember { mutableStateOf(true) }
    LaunchedEffect(show, n.playing) { if (show && n.playing) { delay(3_000); show = false } }
    var dragging by remember { mutableStateOf<Float?>(null) }
    Box(Modifier.fillMaxSize().background(Color.Black).clickable { show = !show }) {
        AndroidView({ ctx -> PlayerView(ctx).apply { useController = false } }, Modifier.fillMaxSize(), update = { it.player = ctl })
        if (show) Column(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).statusBarsPadding().navigationBarsPadding()) {
            TopBar(name, onClose)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ ctl?.seekTo((n.pos - 10_000).coerceAtLeast(0)) }) { Icon(Icons.Rounded.Replay10, "Back 10 seconds", tint = Color.White, modifier = Modifier.size(36.dp)) }
                    Box(Modifier.size(72.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.9f)).clickable { ctl?.let { if (it.isPlaying) it.pause() else it.play() } }, contentAlignment = Alignment.Center) {
                        Icon(if (n.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(42.dp))
                    }
                    IconButton({ ctl?.seekTo((n.pos + 10_000).coerceAtMost(n.dur)) }) { Icon(Icons.Rounded.Forward10, "Forward 10 seconds", tint = Color.White, modifier = Modifier.size(36.dp)) }
                }
            }
            Slider(dragging ?: if (n.dur > 0) n.pos.toFloat() / n.dur else 0f, { dragging = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                onValueChangeFinished = { dragging?.let { ctl?.seekTo((it * n.dur).toLong()) }; dragging = null })
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${time(n.pos)} / ${time(n.dur)}", color = Color.White, style = MaterialTheme.typography.labelMedium)
                Box(Modifier.weight(1f))
                TextButton({ doMore(c, uri, type) }) { Text("Do more", color = Color.White) }
                IconButton({ share(c, uri, type) }) { Icon(Icons.Rounded.Share, "Share", tint = Color.White) }
            }
        }
    }
}

private fun share(c: android.content.Context, uri: Uri, type: String) =
    c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(type.ifBlank { "*/*" }).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share"))
private fun doMore(c: android.content.Context, uri: Uri, type: String) =
    c.startActivity(Intent(Intent.ACTION_SEND).setClass(c, MainActivity::class.java).setType(type.ifBlank { "*/*" }).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))

@Composable
private fun Actions(uri: Uri, type: String) {
    val c = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        TextButton({ doMore(c, uri, type) }) { Text("Do more with it", color = Color.White) }
        TextButton({ share(c, uri, type) }) { Text("Share", color = Color.White) }
    }
}

@Composable
private fun Picture(uri: Uri) {
    val c = LocalContext.current
    val bmp = remember {
        runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var s = 1; while (o.outWidth / s > 3000 || o.outHeight / s > 3000) s *= 2
            c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }
        }.getOrNull()
    }
    var zoom by remember { mutableFloatStateOf(1f) }; var dx by remember { mutableFloatStateOf(0f) }; var dy by remember { mutableFloatStateOf(0f) }
    if (bmp == null) { Text("Can't show this picture", Modifier.padding(24.dp), color = Color.White); return }
    Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize().pointerInput(Unit) {
        detectTransformGestures { _, pan, z, _ -> zoom = (zoom * z).coerceIn(1f, 8f); dx += pan.x; dy += pan.y; if (zoom == 1f) { dx = 0f; dy = 0f } }
    }.graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = dx; translationY = dy }, contentScale = ContentScale.Fit)
}
