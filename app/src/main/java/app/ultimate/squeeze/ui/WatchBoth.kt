package app.ultimate.squeeze.ui

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

/**
 * The original and the squeezed copy, one above the other, playing in step: one play button and one seek bar drive
 * both, so any difference shows at the same moment. The original is muted so the two don't echo.
 */
@Composable
fun WatchBoth(original: Uri, squeezed: Uri, beforeLabel: String, afterLabel: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val a = remember { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(original)); volume = 0f; prepare() } }
    val b = remember { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(squeezed)); prepare() } }
    DisposableEffect(Unit) { onDispose { a.release(); b.release() } }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(playing) {
        a.playWhenReady = playing; b.playWhenReady = playing
        while (true) {
            if (!dragging) {
                val d = b.duration.takeIf { it > 0 } ?: 1L
                pos = (b.currentPosition.toFloat() / d).coerceIn(0f, 1f)
                // Keep the two together: nudge the original when it drifts more than a fifth of a second.
                if (playing && kotlin.math.abs(a.currentPosition - b.currentPosition) > 200) a.seekTo(b.currentPosition)
                if (b.playbackState == androidx.media3.common.Player.STATE_ENDED) playing = false
            }
            delay(100)
        }
    }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Watch both", color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
                IconButton(onDismiss) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
            }
            Pane(a, beforeLabel, Modifier.weight(1f))
            Pane(b, afterLabel, Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton({
                    if (!playing && b.playbackState == androidx.media3.common.Player.STATE_ENDED) { a.seekTo(0); b.seekTo(0) }
                    playing = !playing
                }) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play") }
                Slider(pos, { f ->
                    dragging = true; pos = f
                    val at = (f * (b.duration.takeIf { it > 0 } ?: 0L)).toLong(); a.seekTo(at); b.seekTo(at)
                }, onValueChangeFinished = { dragging = false }, modifier = Modifier.weight(1f).padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun Pane(p: ExoPlayer, label: String, modifier: Modifier) {
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF111114))) {
        AndroidView({ ctx -> TextureView(ctx).also { p.setVideoTextureView(it) } }, Modifier.fillMaxSize())
        Text(label, color = Color.White, fontSize = 12.sp, modifier = Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

/** Which originals have been deleted from here, so every part of the screen (the big card, each row) agrees without asking Android again. Reading it recomposes. */
object OriginalsState {
    val deleted = androidx.compose.runtime.mutableStateListOf<String>()
    fun isGone(uri: Uri) = uri.toString() in deleted
    fun markGone(uris: List<Uri>) { uris.forEach { deleted.add(it.toString()) } }
}

/**
 * Deleting the original after a squeeze. It goes to the system trash where Android has one (Android 11 and up, kept 30
 * days), after Android's own confirmation; a file picked through the document picker is deleted through it instead.
 */
object Originals {
    /**
     * The gallery address of [uri], when it has one: only those can go to the trash. A video picked through Android's photo picker has
     * a picker address (`content://media/picker/...`) that cannot be trashed; for a video on this phone its last number is the gallery
     * entry's id, so the gallery entry is looked up and used only if its size is exactly [expectedSize] (the id alone is not trusted).
     */
    fun mediaUri(context: Context, uri: Uri, expectedSize: Long = -1L): Uri? = when {
        uri.authority == MediaStore.AUTHORITY && uri.toString().contains("/picker") -> pickerEntry(context, uri, expectedSize)
        uri.authority == MediaStore.AUTHORITY -> uri
        DocumentsContract.isDocumentUri(context, uri) && Build.VERSION.SDK_INT >= 29 -> runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull()
        else -> null
    }?.let { u -> runCatching { ContentUris.parseId(u); u }.getOrNull() }

    private fun pickerEntry(context: Context, uri: Uri, expectedSize: Long): Uri? {
        if (expectedSize <= 0) return null
        val id = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: return null
        val candidate = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
        val size = runCatching { context.contentResolver.query(candidate, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else null } }.getOrNull()
        return candidate.takeIf { size == expectedSize }
    }

    fun canDelete(context: Context, uri: Uri, expectedSize: Long = -1L): Boolean = mediaUri(context, uri, expectedSize) != null || DocumentsContract.isDocumentUri(context, uri)

    /** Whether the original is still there: not deleted from here and, where Android can say, still in the gallery. */
    fun exists(context: Context, uri: Uri, expectedSize: Long = -1L): Boolean {
        if (OriginalsState.isGone(uri)) return false
        val target = mediaUri(context, uri, expectedSize) ?: return true       // a picker address cannot be asked; trust what we recorded
        return try { context.contentResolver.query(target, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { it.moveToFirst() } ?: true }
        catch (e: SecurityException) { true } catch (e: Exception) { false }
    }
}

/**
 * Returns a function that deletes (trashes) originals, given each one's address and size, with ONE confirmation from Android for all of the
 * gallery ones, and reports whether it went.
 */
@Composable
fun rememberDeleteOriginals(onResult: (Boolean) -> Unit): (List<Pair<Uri, Long>>) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val ok = r.resultCode == Activity.RESULT_OK
        if (ok) OriginalsState.markGone(pending)
        onResult(ok)
    }
    return { items ->
        val resolved = items.map { (u, size) -> Pair(u, Originals.mediaUri(context, u, size)) }
        val docs = resolved.filter { it.second == null }.map { it.first }
        val media = resolved.filter { it.second != null }
        val docsGone = docs.filter { runCatching { DocumentsContract.deleteDocument(context.contentResolver, it) }.getOrDefault(false) }
        OriginalsState.markGone(docsGone)
        when {
            media.isEmpty() -> onResult(docsGone.isNotEmpty())
            Build.VERSION.SDK_INT >= 30 -> {
                pending = media.map { it.first }
                launcher.launch(IntentSenderRequest.Builder(MediaStore.createTrashRequest(context.contentResolver, media.map { it.second!! }, true).intentSender).build())
            }
            else -> {
                val done = media.filter { runCatching { context.contentResolver.delete(it.second!!, null, null) > 0 }.getOrDefault(false) }
                OriginalsState.markGone(done.map { it.first }); onResult(done.isNotEmpty() || docsGone.isNotEmpty())
            }
        }
    }
}

/** Deletes (trashes) one original; see [rememberDeleteOriginals]. */
@Composable
fun rememberDeleteOriginal(onResult: (Boolean) -> Unit): (Uri, Long) -> Unit {
    val bulk = rememberDeleteOriginals(onResult)
    return { uri, size -> bulk(listOf(uri to size)) }
}
