package app.ultimate.squeeze.ui

import app.ultimate.core.*
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ultimate.squeeze.engine.QueueState
import app.ultimate.squeeze.engine.Status
import app.ultimate.squeeze.model.LibraryEntry
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

// ─────────────────────────── While compressing ───────────────────

/** Progress as a picture: the original's size shrinking toward the size this job is aiming for. */
@Composable
fun ProgressHero(queue: QueueState) {
    val encoded = queue.jobs.filter { it.plan.skipReason == null }
    val total = max(encoded.size, 1)
    val finished = encoded.count { it.status !is Status.Queued && it.status !is Status.Running }
    val running = encoded.firstOrNull { it.status is Status.Running }
    val live = running?.status as? Status.Running
    val overall = ((finished + (live?.progress ?: 0f)) / total).coerceIn(0f, 1f)
    val shown by animateFloatAsState(overall, tween(450, easing = LinearEasing), label = "progress")

    val before = encoded.sumOf { it.source.sizeBytes }
    val target = encoded.sumOf { it.plan.estimatedBytes }
    val ratio = if (before > 0) (target.toFloat() / before).coerceIn(0.02f, 1f) else 1f
    // Ease so the bar visibly bites early, then settles toward the target.
    val width = 1f - (1f - ratio) * FastOutSlowInEasing.transform(shown)

    val shimmer = rememberInfiniteTransition(label = "shimmer").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "shimmerX",
    )
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer

    val eased = FastOutSlowInEasing.transform(shown)
    val sizeNow = (before - (before - target) * eased).toLong()
    val saved = percentSaved(before, sizeNow)
    val dark = Color.Black.copy(alpha = 0.62f)

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // The frame being squeezed, with a light sweeping down it as it encodes.
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(20.dp)).background(Color.Black)) {
                running?.let { VideoThumb(it.uri, Modifier.fillMaxSize(), it.source.durationMs) }
                Canvas(Modifier.fillMaxSize()) {
                    val y = size.height * shimmer.value; val band = size.height * 0.28f
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, primary.copy(alpha = 0.55f), Color.Transparent), startY = y - band, endY = y + band), topLeft = Offset(0f, y - band), size = Size(size.width, band * 2))
                    drawLine(Color.White.copy(alpha = 0.85f), Offset(0f, y), Offset(size.width, y), strokeWidth = 2f)
                }
                Text("${(shown * 100).roundToInt()}%", Modifier.align(Alignment.TopEnd).padding(10.dp).clip(RoundedCornerShape(50)).background(dark).padding(horizontal = 12.dp, vertical = 5.dp), color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                AnimatedContent(
                    targetState = statusLine(shown), modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                    transitionSpec = { (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut()) }, label = "message",
                ) { Text(it, Modifier.clip(RoundedCornerShape(50)).background(dark).padding(horizontal = 12.dp, vertical = 5.dp), color = Color.White, style = MaterialTheme.typography.labelMedium) }
            }
            // The number that matters, falling as it goes.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(formatBytes(before), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
                Text("  ➔  ", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatBytes(sizeNow), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                Text("−$saved%", Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 12.dp, vertical = 5.dp), color = onContainer, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            // One thin bar, the only progress on the screen.
            Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                Box(Modifier.fillMaxWidth(shown.coerceAtLeast(0.01f)).fillMaxHeight().clip(CircleShape).background(Brush.linearGradient(listOf(primary, tertiary, primary), start = Offset(-600f + 1800f * shimmer.value, 0f), end = Offset(600f + 1800f * shimmer.value, 200f))))
            }
            val detail = buildList {
                live?.speedX?.let { add(String.format(Locale.US, "%.1f× real time", it)) }
                live?.etaMs?.let { add(formatEta(it) + " left") }
            }.joinToString(" · ")
            Text("Video ${minOf(finished + 1, total)} of $total" + if (detail.isNotEmpty()) " · $detail" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Aiming for ≈ ${formatBytes(target)}. You can leave the app; it keeps working.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
        }
    }
}

private fun statusLine(p: Float) = when {
    p < 0.06f -> "Warming up the encoder…"
    p < 0.35f -> "Rewriting every frame, smarter…"
    p < 0.70f -> "Packing pixels tighter…"
    p < 0.92f -> "Polishing the details…"
    else -> "Almost there…"
}

// ───────────────────────────── When finished ─────────────────────

@Composable
fun DoneHero(queue: QueueState) {
    val done = queue.jobs.filter { it.status is Status.Done }
    val saved = queue.savedBytes
    val view = LocalView.current
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer

    val count = remember { Animatable(0f) }
    LaunchedEffect(saved) {
        if (done.isNotEmpty()) {
            view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
        }
        count.animateTo(saved.toFloat(), tween(1800, easing = FastOutSlowInEasing))
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.fillMaxWidth().padding(24.dp)) {
            if (done.isEmpty()) {
                Text("Already lean", style = MaterialTheme.typography.labelLarge, color = onContainer)
                Text("Nothing to squeeze", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = onContainer)
                Text("These videos were already efficient, so they were left alone.", style = MaterialTheme.typography.bodyMedium, color = onContainer)
            } else {
                val before = done.sumOf { it.source.sizeBytes }
                val after = done.sumOf { (it.status as Status.Done).outputBytes }
                Text("Space, reclaimed", style = MaterialTheme.typography.labelLarge, color = onContainer)
                Text(formatBytes(count.value.toLong()), style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold, color = onContainer)
                Text(
                    if (done.size == 1) "${done.first().name} is now ${String.format(Locale.US, "%.1f", before.toDouble() / max(after, 1))}× smaller"
                    else "${done.size} videos, ${percentSaved(before, after)}% smaller on average",
                    style = MaterialTheme.typography.titleMedium, color = onContainer,
                )
                Spacer(Modifier.height(6.dp))
                Text("Saved to Movies/Squeeze. Your originals were not touched.", style = MaterialTheme.typography.bodySmall, color = onContainer.copy(alpha = 0.85f))
            }
        }
    }
}

private data class Fact(val big: String, val caption: String)

private const val PHOTO_BYTES = 3_000_000L
private const val SONG_BYTES = 4_000_000L
private const val UPLOAD_BPS = 20_000_000.0
private const val EMAIL_LIMIT = 25_000_000L

/** Everyday-scale equivalents. All derived from the measured sizes; the constants are stated in the captions. */
@Composable
fun FunFacts(queue: QueueState) {
    val done = queue.jobs.filter { it.status is Status.Done }
    if (done.isEmpty()) return
    val before = done.sumOf { it.source.sizeBytes }
    val after = done.sumOf { (it.status as Status.Done).outputBytes }
    val saved = before - after

    val facts = buildList {
        add(Fact(String.format(Locale.US, "%.1f×", before.toDouble() / max(after, 1)), "smaller than before"))
        if (saved >= PHOTO_BYTES) add(Fact("≈ ${(saved / PHOTO_BYTES).coerceAtLeast(1)}", "more photos fit (3 MB each)"))
        if (saved >= SONG_BYTES) add(Fact("≈ ${(saved / SONG_BYTES).coerceAtLeast(1)}", "more songs fit (4 MB each)"))
        add(Fact("${uploadTime(before)} → ${uploadTime(after)}", "to upload at 20 Mbps"))
        if (after <= EMAIL_LIMIT) add(Fact("Email-ready", "fits a 25 MB attachment"))
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        facts.forEach { f ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.width(150.dp).padding(16.dp)) {
                    Text(f.big, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(f.caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
    }
}

private fun uploadTime(bytes: Long): String {
    val s = (bytes * 8 / UPLOAD_BPS).roundToInt().coerceAtLeast(1)
    return if (s >= 60) "${s / 60}m ${s % 60}s" else "${s}s"
}

// ───────────────────────────── Confetti ──────────────────────────

private class Piece(val angle: Float, val speed: Float, val size: Float, val color: Int, val spin: Float)

/** One celebratory burst from the top of the screen. Draws nothing once finished and never intercepts touches. */
@Composable
fun Confetti(modifier: Modifier = Modifier) {
    val clock = remember { Animatable(0f) }
    LaunchedEffect(Unit) { clock.animateTo(1f, tween(DURATION_MS, easing = LinearEasing)) }
    val palette = listOf(
        MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.secondary,
        Color(0xFFFFC857), Color(0xFFFF6B6B), Color(0xFF4ECDC4),
    )
    val pieces = remember {
        val r = Random(7)
        List(90) { Piece((-PI * (0.05 + 0.9 * r.nextDouble())).toFloat(), 700f + r.nextFloat() * 1100f, 8f + r.nextFloat() * 10f, r.nextInt(6), r.nextFloat() * 720f - 360f) }
    }
    Canvas(modifier) {
        if (clock.value >= 1f) return@Canvas
        val t = clock.value * DURATION_MS / 1000f
        val origin = Offset(size.width / 2, size.height * 0.22f)
        val fade = (1f - ((t - 1.7f) / 0.9f)).coerceIn(0f, 1f)
        pieces.forEach { p ->
            val reach = p.speed * (1f - exp(-3f * t)) / 3f
            val x = origin.x + cos(p.angle) * reach
            val y = origin.y + sin(p.angle) * reach + 0.5f * 1500f * t * t
            rotate(p.spin * t, Offset(x, y)) {
                drawRect(palette[p.color].copy(alpha = fade), Offset(x - p.size / 2, y - p.size / 4), Size(p.size, p.size / 2))
            }
        }
    }
}

private const val DURATION_MS = 2800
