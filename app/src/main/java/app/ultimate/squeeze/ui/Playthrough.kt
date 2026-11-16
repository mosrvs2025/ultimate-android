package app.ultimate.squeeze.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.ultimate.squeeze.engine.FrameExtractor
import app.ultimate.squeeze.model.FramePlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Frames spread across a whole clip, ready to flip through. Owns [bitmaps]; [Holder] recycles them when the composable
 * that asked for them leaves the screen.
 */
class Flipbook internal constructor(bitmaps: List<Bitmap>) {
    val images: List<ImageBitmap> = bitmaps.map { it.asImageBitmap() }
    val size: Int get() = images.size

    /** Tracks the decoded bitmaps so they are recycled exactly once, even if they arrive after the screen is gone. */
    internal class Holder {
        private var bitmaps: List<Bitmap> = emptyList()
        private var released = false

        /** Takes ownership of [frames]; returns false (after recycling them) if this holder was already released. */
        fun adopt(frames: List<Bitmap>): Boolean {
            if (released) { frames.forEach { it.recycle() }; return false }
            bitmaps = frames
            return true
        }

        fun release() {
            released = true
            bitmaps.forEach { it.recycle() }
            bitmaps = emptyList()
        }
    }
}

/**
 * Pulls frames from [uri] on a background thread, downscaled, and hands back a [Flipbook] once they are ready: null
 * while loading and, for good, when extraction fails or finds nothing, so callers can keep showing a single still.
 * The bitmaps are recycled when [uri] changes or the caller leaves composition.
 */
@Composable
fun rememberFlipbook(uri: Uri?, durationMs: Long): Flipbook? {
    val context = LocalContext.current.applicationContext
    val holder = remember(uri) { Flipbook.Holder() }
    var book by remember(uri) { mutableStateOf<Flipbook?>(null) }
    DisposableEffect(holder) { onDispose { holder.release() } }
    LaunchedEffect(holder) {
        if (uri == null) return@LaunchedEffect
        val frames = withContext(Dispatchers.IO) { FrameExtractor.extract(context, uri, durationMs) }
        if (frames.isNotEmpty() && holder.adopt(frames)) book = Flipbook(frames)
    }
    return book
}

/** Seconds each frame is on screen. A 12-frame clip makes one fast-forward pass in about five seconds. */
private const val SECONDS_PER_FRAME = 0.42f

/** The flipbook's playhead in frames (0 until [count], wrapping), looping at fast-forward speed. Read it only while drawing. */
@Composable
fun rememberFlipPosition(count: Int): State<Float> {
    val n = max(count, 1)
    return rememberInfiniteTransition(label = "flip").animateFloat(
        0f, n.toFloat(), infiniteRepeatable(tween((n * SECONDS_PER_FRAME * 1000).toInt(), easing = LinearEasing), RepeatMode.Restart), label = "flipPosition",
    )
}

/**
 * The clip being squeezed, played through like a fast-forward flipbook, with a ghost of the previous frame trailing it
 * and faint scanlines over it. [squeeze] (0..1) pinches the picture inward as compression advances, with a slight pulse.
 */
@Composable
fun FlipbookFrames(book: Flipbook, position: State<Float>, squeeze: Float, modifier: Modifier = Modifier.fillMaxSize()) {
    Canvas(
        modifier.clipToBounds().graphicsLayer {
            val pulse = sin(position.value / book.size * 2f * PI.toFloat() * 3f) * 0.008f
            scaleX = 1f - 0.10f * squeeze + pulse
            scaleY = 1f - 0.04f * squeeze + pulse
        },
    ) {
        val n = book.size
        val step = FramePlan.step(position.value, n)
        // Motion trail: the frame just before, a little behind and faint, so movement smears rather than snaps.
        if (n > 1) drawCover(book.images[(step.from - 1 + n) % n], alpha = 0.22f, shiftX = -size.width * 0.03f, zoom = 1.04f)
        drawCover(book.images[step.from], alpha = 1f)
        if (step.mix > 0f) drawCover(book.images[step.to], alpha = step.mix)
        // Scanlines.
        val gap = 3.dp.toPx()
        var y = 0f
        while (y < size.height) {
            drawRect(Color.Black.copy(alpha = 0.14f), Offset(0f, y), Size(size.width, 1f))
            y += gap
        }
    }
}

/** Draws [img] scaled to cover the whole canvas, centred, like ContentScale.Crop. */
private fun DrawScope.drawCover(img: ImageBitmap, alpha: Float, shiftX: Float = 0f, zoom: Float = 1f) {
    val scale = max(size.width / img.width, size.height / img.height) * zoom
    val w = img.width * scale
    val h = img.height * scale
    drawImage(
        img,
        srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
        dstOffset = IntOffset(((size.width - w) / 2 + shiftX).toInt(), ((size.height - h) / 2).toInt()),
        dstSize = IntSize(w.toInt(), h.toInt()),
        alpha = alpha, filterQuality = FilterQuality.Low,
    )
}

/**
 * A row of tiny frames under the picture. Frames already covered by compression ([progress], 0..1 for this video) are
 * lit, the rest dimmed, and a marker follows the flipbook's playhead.
 */
@Composable
fun FilmStrip(book: Flipbook, position: State<Float>, progress: Float, accent: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val n = book.size
        val slot = size.width / n
        val done = FramePlan.framesDone(progress, n)
        val gap = 2.dp.toPx()
        val corner = CornerRadius(4.dp.toPx())
        for (i in 0 until n) {
            val left = i * slot + gap / 2
            val img = book.images[i]
            val dst = Size(slot - gap, size.height)
            val scale = max(dst.width / img.width, dst.height / img.height)
            val w = img.width * scale
            val h = img.height * scale
            // Crop each thumbnail to its slot.
            androidx.compose.ui.graphics.drawscope.clipRect(left, 0f, left + dst.width, size.height) {
                drawImage(
                    img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
                    dstOffset = IntOffset((left + (dst.width - w) / 2).toInt(), ((size.height - h) / 2).toInt()), dstSize = IntSize(w.toInt(), h.toInt()),
                    alpha = if (i < done) 1f else 0.35f, filterQuality = FilterQuality.Low,
                )
            }
            drawRoundRect(if (i < done) accent.copy(alpha = 0.9f) else Color.Transparent, Offset(left, size.height - 3.dp.toPx()), Size(dst.width, 3.dp.toPx()), corner)
        }
        // Playhead.
        val x = (position.value / n).coerceIn(0f, 1f) * size.width
        drawRect(Color.White.copy(alpha = 0.9f), Offset(x - 1f, 0f), Size(2f, size.height))
    }
}
