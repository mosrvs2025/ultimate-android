package app.ultimate.core

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** The lub-dub of one heartbeat: how much the heart swells at [phase] (0..1 of a beat). Pure, for the drawing and its test. */
object HeartBeat {
    fun swell(phase: Double): Double {
        val p = phase - kotlin.math.floor(phase)
        fun bump(at: Double, width: Double) = exp(-((p - at) / width).pow(2))
        return 0.16 * bump(0.05, 0.05) + 0.09 * bump(0.24, 0.05)
    }
}

/**
 * A liquid heart beating at [bpm]: it swells twice per beat (lub-dub), its skin ripples, and a ring spreads out with each
 * beat. Still (and dimmed) when there is no reading; steady with animations switched off.
 */
@Composable
fun LiquidHeart(bpm: Int?, size: Dp = 64.dp) {
    val motion = motionOn()
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(bpm, motion) {
        if (bpm == null || !motion) { phase = 0f; return@LaunchedEffect }
        var last = 0L
        while (true) withFrameNanos { now -> if (last != 0L) phase = (phase + (now - last) / 1e9f * bpm / 60f) % 1000f; last = now }
    }
    Canvas(Modifier.size(size)) {
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val base = this.size.minDimension * 0.30f
        val ph = phase - kotlin.math.floor(phase)
        val s = (1 + HeartBeat.swell(phase.toDouble())).toFloat()
        // The ring that leaves the heart on each beat.
        if (bpm != null && motion) drawCircle(Color(0xFFFF5C7A).copy(alpha = 0.45f * (1 - ph)), base * (1.1f + 0.9f * ph), c, style = Stroke(3f * (1 - ph) + 0.5f))
        val path = Path()
        for (k in 0..64) {
            val t = k / 64.0 * 2 * PI
            val wob = if (bpm != null && motion) 1 + 0.025 * sin(5 * t + phase * 2 * PI) else 1.0
            val x = 16 * sin(t).pow(3) * wob; val y = -(13 * cos(t) - 5 * cos(2 * t) - 2 * cos(3 * t) - cos(4 * t)) * wob
            val px = c.x + (x / 17 * base * s).toFloat(); val py = c.y + (y / 17 * base * s).toFloat() - base * 0.1f
            if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        val alive = bpm != null
        drawPath(path, Brush.radialGradient(listOf(if (alive) Color(0xFFFF9AAE) else Color(0xFF6B5560), if (alive) Color(0xFFE8335A) else Color(0xFF3A2A31)), c - Offset(base * 0.35f, base * 0.45f), base * 1.6f))
        drawCircle(Color.White.copy(alpha = if (alive) 0.55f else 0.2f), base * 0.16f, c - Offset(base * 0.42f, base * 0.48f))
    }
}

/** The heart, the number, and a trace of the last few minutes of readings. */
@Composable
fun HeartPanel(bpm: Int?, modifier: Modifier = Modifier) {
    val history = remember { mutableStateListOf<Int>() }
    LaunchedEffect(bpm) { if (bpm != null) { history += bpm; while (history.size > 90) history.removeAt(0) } }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LiquidHeart(bpm, 64.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(bpm?.toString() ?: "—", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text(" bpm", color = Liquid.dim, fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
            }
            if (history.size >= 2) Canvas(Modifier.fillMaxWidth().height(26.dp)) {
                val lo = (history.minOrNull() ?: 0) - 3; val hi = (history.maxOrNull() ?: 1) + 3
                val p = Path()
                history.forEachIndexed { i, v ->
                    val x = i / (history.size - 1f) * this.size.width; val y = this.size.height * (1 - (v - lo) / (hi - lo).toFloat().coerceAtLeast(1f))
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                drawPath(p, Brush.horizontalGradient(listOf(Color(0x33FF5C7A), Color(0xFFFF5C7A))), style = Stroke(3f))
            }
        }
    }
}
