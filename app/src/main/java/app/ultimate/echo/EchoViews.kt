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
