package app.ultimate.echo

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ultimate.core.gooey
import app.ultimate.core.motionOn
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/** What a number or picture is. Echo never shows a drawing as if it were a measurement. */
enum class Truth(val label: String, val color: Color) {
    Measured("MEASURED", Color(0xFF3DDC97)),
    Estimate("ESTIMATE", Color(0xFFFFC857)),
    Illustration("ILLUSTRATION", Color(0xFFB39DDB)),
    Reported("REPORTED BY THE PHONE", Color(0xFF8AB4F8)),
}

@Composable
fun TruthTag(t: Truth, modifier: Modifier = Modifier) {
    Text(t.label, modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 2.dp), color = t.color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
}

/** A scrolling image built column by column: the newest measurement on the right. Kept outside composition so frames don't reallocate. */
class Waterfall(val width: Int, val height: Int) {
    private val px = IntArray(width * height)
    val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private var peak = 1e-9f

    /** Adds one column; [values] index 0 is drawn at the bottom. Brightness is logarithmic so faint echoes still show. */
    fun push(values: FloatArray, palette: (Float) -> Int) {
        for (y in 0 until height) System.arraycopy(px, y * width + 1, px, y * width, width - 1)
        val top = values.maxOrNull() ?: 0f
        peak = maxOf(peak * 0.995f, top)
        for (y in 0 until height) {
            val v = values.getOrElse(y * values.size / height) { 0f }
            val norm = (ln(1f + 40f * v / peak) / ln(41f)).coerceIn(0f, 1f)
            px[(height - 1 - y) * width + width - 1] = palette(norm)
        }
        bitmap.setPixels(px, 0, width, 0, 0, width, height)
    }
}

/** Deep blue to cyan to white: echo energy. */
fun heat(v: Float): Int {
    val r = (255 * (v * 2 - 1).coerceIn(0f, 1f)).toInt()
    val g = (255 * (v * 1.6f).coerceIn(0f, 1f)).toInt()
    val b = (60 + 195 * v).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

/**
 * The echogram: time runs left to right, distance from the phone bottom to top (0 to 1 m). Bright is something that
 * moved at that distance at that moment: measured, straight from the correlation. The dot is the tracked target.
 */
@Composable
fun Echogram(fall: Waterfall, tick: Int, rangeM: Double, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFF05070D))) {
        Canvas(Modifier.matchParentSize()) {
            @Suppress("UNUSED_EXPRESSION") tick // redraw with each new column
            drawImage(fall.bitmap.asImageBitmap(), IntOffset.Zero, IntSize(fall.width, fall.height), IntOffset.Zero, IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Low)
            for (cm in listOf(25, 50, 75)) {
                val y = size.height * (1 - cm / 100f)
                drawLine(Color.White.copy(alpha = 0.15f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            if (!rangeM.isNaN()) drawCircle(Color(0xFFFF8A5C), 6.dp.toPx(), Offset(size.width - 6.dp.toPx(), size.height * (1 - (rangeM / 1.0).toFloat().coerceIn(0f, 1f))))
        }
        Column(Modifier.align(Alignment.TopStart).padding(8.dp)) {
            TruthTag(Truth.Measured)
            Text("distance ↑ · time →", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
        }
        for (cm in listOf(25, 50, 75)) Text("$cm cm", color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = (cm * 2.4).dp))
    }
}

/** The live spectrum near the chirp: 16 kHz at the bottom, 22 kHz at the top. The chirp band (17-20 kHz) should glow. */
@Composable
fun Spectrogram(fall: Waterfall, tick: Int, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(Color.Black)) {
        Canvas(Modifier.matchParentSize()) {
            @Suppress("UNUSED_EXPRESSION") tick
            drawImage(fall.bitmap.asImageBitmap(), IntOffset.Zero, IntSize(fall.width, fall.height), IntOffset.Zero, IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Low)
            listOf(17f, 20f).forEach { k ->
                val y = size.height * (1 - (k - 16f) / 6f)
                drawLine(Color.White.copy(alpha = 0.25f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
        }
        Column(Modifier.align(Alignment.TopStart).padding(8.dp)) {
            TruthTag(Truth.Measured)
            Text("16-22 kHz · time →. Lines mark the chirp band", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** Recent positions in front of the phone, for the map's trail. */
class Trail(private val keep: Int = 40) {
    data class Point(val rangeM: Double, val angleDeg: Double?, val confidence: Double, val at: Long)
    val points = ArrayDeque<Point>()
    fun add(p: Point) { points.addLast(p); while (points.size > keep) points.removeFirst() }
}

/**
 * Top-down space in front of the phone (the phone at the bottom, 1 m at the top arc). Distance is measured. Direction
 * is an estimate from two microphones when the phone has them, and otherwise unknown, so the target is drawn as an arc
 * across every direction it could be in, never as a point it isn't.
 */
@Composable
fun SpaceMap(trail: Trail, tick: Int, stereo: Boolean, modifier: Modifier) {
    val accent = Color(0xFFFF8A5C)
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFF0A0C14))) {
        Canvas(Modifier.matchParentSize()) {
            @Suppress("UNUSED_EXPRESSION") tick
            val origin = Offset(size.width / 2, size.height - 12.dp.toPx())
            val scale = (size.height - 24.dp.toPx())
            for (m in listOf(0.25f, 0.5f, 0.75f, 1f)) drawArc(Color.White.copy(alpha = 0.12f), 200f, 140f, false, Offset(origin.x - scale * m, origin.y - scale * m), Size(scale * m * 2, scale * m * 2), style = Stroke(1.dp.toPx()))
            drawRect(Color.White.copy(alpha = 0.7f), Offset(origin.x - 14.dp.toPx(), origin.y - 3.dp.toPx()), Size(28.dp.toPx(), 6.dp.toPx()))
            val now = System.currentTimeMillis()
            trail.points.forEach { p ->
                val age = ((now - p.at) / 2000f).coerceIn(0f, 1f)
                val alpha = (1 - age) * (p.confidence / 10).toFloat().coerceIn(0.2f, 1f)
                val r = (p.rangeM.toFloat() * scale).coerceAtMost(scale)
                if (p.angleDeg != null) {
                    val a = Math.toRadians(p.angleDeg - 90)
                    drawCircle(accent.copy(alpha = alpha), (4 + 6 * (1 - age)).dp.toPx(), Offset(origin.x + r * cos(a).toFloat(), origin.y + r * sin(a).toFloat()))
                } else {
                    drawArc(accent.copy(alpha = alpha * 0.8f), 210f, 120f, false, Offset(origin.x - r, origin.y - r), Size(r * 2, r * 2), style = Stroke((3 + 4 * (1 - age)).dp.toPx()))
                }
            }
        }
        Column(Modifier.align(Alignment.TopStart).padding(8.dp)) {
            TruthTag(Truth.Measured); Text("distance", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
            TruthTag(Truth.Estimate, Modifier.padding(top = 4.dp)); Text(if (stereo) "direction, from two mics" else "direction unknown on one mic: shown as an arc", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
        }
    }
}

/** Keeps a counter that bumps on every new frame, so canvases redraw exactly when there is something new. */
@Composable
fun rememberTick(): Pair<Int, () -> Unit> {
    var t by remember { mutableIntStateOf(0) }
    return t to { t++ }
}

@Composable
fun Readout(label: String, value: String, truth: Truth, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TruthTag(truth, Modifier.padding(top = 2.dp))
    }
}

/**
 * Reach: the live, physical view. A liquid blob rises out of the phone on a tether and floats at the measured
 * distance of the nearest moving thing; recent positions trail behind as droplets that melt together. The blob's
 * height is measured; its shape, tether and colours are illustration, driven by the measurement. A dashed line marks
 * the farthest distance Echo has detected in this session, so you learn its real reach on your phone.
 */
@Composable
fun ReachView(rangeM: Double, confidence: Double, velocity: Double, farthestM: Double, gesture: Pair<EchoGestures.Gesture, Long>?, live: Boolean, modifier: Modifier) {
    val maxM = 0.6f
    val present = !rangeM.isNaN()
    val target = if (present) (rangeM.toFloat() / maxM).coerceIn(0f, 1f) else 0f
    // Springy, so the blob has weight: it overshoots a little and settles, like something liquid.
    val level by androidx.compose.animation.core.animateFloatAsState(target, androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 180f), label = "level")
    val blobSize by androidx.compose.animation.core.animateFloatAsState(if (present) (0.6f + (confidence / 30).toFloat().coerceIn(0f, 0.6f)) else 0.35f, androidx.compose.animation.core.spring(0.5f, 220f), label = "size")
    val warmth by androidx.compose.animation.core.animateFloatAsState(if (!present || velocity.isNaN()) 0f else (-velocity / 0.5).toFloat().coerceIn(-1f, 1f), androidx.compose.animation.core.tween(250), label = "warm")
    val breath = if (live && motionOn()) androidx.compose.animation.core.rememberInfiniteTransition(label = "reach").animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1800), androidx.compose.animation.core.RepeatMode.Reverse), label = "b").value else 0.5f
    val trail = remember { ArrayDeque<Float>() }
    if (present) { trail.addLast(target); while (trail.size > 10) trail.removeFirst() } else if (trail.isNotEmpty()) trail.removeFirst()
    val now = System.currentTimeMillis()
    val pop = gesture?.takeIf { now - it.second < 1_100 }
    val popT by androidx.compose.animation.core.animateFloatAsState(if (pop != null) 1f else 0f, androidx.compose.animation.core.spring(0.45f, 300f), label = "pop")
    val base = Color(0xFF7FD3FF)
    val color = when {
        warmth > 0 -> androidx.compose.ui.graphics.lerp(base, Color(0xFFFF8A5C), warmth)
        warmth < 0 -> androidx.compose.ui.graphics.lerp(base, Color(0xFF7C9CFF), -warmth)
        else -> base
    }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.clip(RoundedCornerShape(24.dp)).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF070B18), Color(0xFF0E1A33))))) {
        val h = constraints.maxHeight.toFloat()
        val bottomPad = with(androidx.compose.ui.platform.LocalDensity.current) { 28.dp.toPx() }
        val topPad = with(androidx.compose.ui.platform.LocalDensity.current) { 16.dp.toPx() }
        fun y(f: Float) = h - bottomPad - f * (h - bottomPad - topPad)
        // Ruler and the farthest-seen line: plain lines, not melted.
        Canvas(Modifier.matchParentSize()) {
            for (cm in listOf(10, 20, 30, 40, 50)) {
                val yy = y(cm / 60f)
                drawLine(Color.White.copy(alpha = 0.08f), Offset(0f, yy), Offset(size.width, yy), strokeWidth = 1.dp.toPx())
            }
            if (farthestM > 0) {
                val yy = y((farthestM.toFloat() / maxM).coerceIn(0f, 1f))
                drawLine(Color(0xFF3DDC97).copy(alpha = 0.7f), Offset(0f, yy), Offset(size.width, yy), strokeWidth = 1.5.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
            }
        }
        // The liquid: phone, tether, blob and trail drawn in one gooey layer so they melt into each other.
        Canvas(Modifier.matchParentSize().gooey(14f)) {
            val cx = size.width * 0.5f
            val phoneY = h - bottomPad + 8.dp.toPx()
            drawRoundRect(Color(0xFFE6ECFF), Offset(cx - 70.dp.toPx(), phoneY - 4.dp.toPx()), Size(140.dp.toPx(), 10.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
            val by = y(level)
            val r = (14.dp.toPx() + 14.dp.toPx() * blobSize) * (1f + 0.06f * breath) * (1f + 0.35f * popT)
            // The tether: droplets from the phone up to the blob, thinning as it stretches.
            val steps = ((phoneY - by) / 12.dp.toPx()).toInt().coerceIn(0, 40)
            for (i in 1 until steps) {
                val t = i.toFloat() / steps
                val rr = 6.dp.toPx() * (1f - 0.55f * t) * (if (present) 1f else 0.6f)
                drawCircle(color.copy(alpha = 0.9f), rr, Offset(cx + kotlin.math.sin((t * 6f + breath * 3f)) * 3.dp.toPx(), phoneY - (phoneY - by) * t))
            }
            trail.forEachIndexed { i, f -> drawCircle(color.copy(alpha = 0.25f + 0.05f * i), 5.dp.toPx() + i * 0.8f.dp.toPx(), Offset(cx + (i - trail.size) * 7.dp.toPx(), y(f))) }
            drawCircle(color, r, Offset(cx, by))
        }
        // Words: the distance (measured) and the gesture, if one was just recognised.
        Column(Modifier.align(Alignment.TopStart).padding(12.dp)) {
            Text(if (present) "%.0f cm".format(rangeM * 100) else if (live) "Hold a hand over the bottom edge, arm from the side" else "Press Start to feel your hand", color = Color.White, fontSize = if (present) 34.sp else 15.sp, fontWeight = FontWeight.Bold)
            if (present) TruthTag(Truth.Measured)
            if (farthestM > 0) Text("Farthest seen: %.0f cm".format(farthestM * 100), color = Color(0xFF3DDC97), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
        TruthTag(Truth.Illustration, Modifier.align(Alignment.TopEnd).padding(10.dp))
        pop?.let { (g, _) ->
            Text(
                when (g.kind) { "push" -> "Push"; "pull" -> "Pull"; else -> "Hover" },
                Modifier.align(Alignment.Center).graphicsLayer { scaleX = 0.6f + 0.6f * popT; scaleY = 0.6f + 0.6f * popT; alpha = popT },
                color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Black,
            )
        }
    }
}
