package app.ultimate.player

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.ultimate.MainActivity
import app.ultimate.core.UltimateTheme

/**
 * Ultimate's own player, for any audio, video or picture you open from another app ("Open with Ultimate+"): plays it
 * right away with seek, speed and loop, shows pictures with pinch to zoom, and hands the file to Ultimate's tools
 * (trim, convert, compress, share clean…) with one tap. Nothing is copied or uploaded.
 */
class PlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data ?: run { finish(); return }
        val type = intent.type ?: contentResolver.getType(uri) ?: ""
        val name = runCatching { contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } }.getOrNull()
            ?: uri.lastPathSegment ?: "File"
        setContent { UltimateTheme { PlayerScreen(uri, type, name, onClose = { finish() }) } }
    }
}

@Composable
private fun PlayerScreen(uri: Uri, type: String, name: String, onClose: () -> Unit) {
    val c = LocalContext.current
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClose) { Text("Close", color = Color.White) }
            Text(name, Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (type.startsWith("image")) Picture(uri) else Media(uri, type.startsWith("audio"), name)
        }
        // Do more with it: the same sheet sharing into Ultimate uses (convert, trim, compress, clean, transcribe…).
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({
                c.startActivity(Intent(Intent.ACTION_SEND).setClass(c, MainActivity::class.java).setType(type.ifBlank { "*/*" })
                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }, Modifier.weight(1f)) { Text("Do more with it", color = Color.White) }
            OutlinedButton({
                c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(type.ifBlank { "*/*" }).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share"))
            }) { Text("Share", color = Color.White) }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun Media(uri: Uri, audio: Boolean, name: String) {
    val c = LocalContext.current
    val player = remember { ExoPlayer.Builder(c).build().apply { setMediaItem(MediaItem.fromUri(uri)); prepare(); playWhenReady = true } }
    DisposableEffect(Unit) { onDispose { player.release() } }
    var speed by remember { mutableFloatStateOf(1f) }
    var loop by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        if (audio) Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("♪", color = Color(0xFF7FD3FF), fontSize = 96.sp)
            Text(name, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp))
        }
        AndroidView({ ctx -> PlayerView(ctx).apply { this.player = player; controllerShowTimeoutMs = if (audio) 0 else 3_000; controllerHideOnTouch = !audio; setShowNextButton(false); setShowPreviousButton(false) } },
            if (audio) Modifier.fillMaxWidth().height(140.dp) else Modifier.fillMaxWidth().weight(1f))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(0.5f, 1f, 1.5f, 2f).forEach { s -> FilterChip(speed == s, { speed = s; player.setPlaybackSpeed(s) }, { Text("${s}×") }) }
            FilterChip(loop, { loop = !loop; player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }, { Text("Loop") })
        }
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
    Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize().clip(RoundedCornerShape(0.dp)).pointerInput(Unit) {
        detectTransformGestures { _, pan, z, _ -> zoom = (zoom * z).coerceIn(1f, 8f); dx += pan.x; dy += pan.y; if (zoom == 1f) { dx = 0f; dy = 0f } }
    }.graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = dx; translationY = dy }, contentScale = ContentScale.Fit)
}
