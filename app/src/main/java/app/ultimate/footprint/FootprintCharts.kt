package app.ultimate.footprint

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ultimate.core.pressScale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Chart arithmetic, kept apart from drawing so it can be tested. */
object ChartMath {
    /** Total time in each hour of the day across the week, from the seven-by-24 heat map. */
    fun hourTotals(heat: Array<LongArray>): LongArray = LongArray(24) { h -> heat.sumOf { it.getOrElse(h) { 0L } } }

    fun peakHour(totals: LongArray): Int? = totals.indices.maxByOrNull { totals[it] }?.takeIf { totals[it] > 0 }

    /** Sweep angles in degrees for each share, leaving a [gap] between slices. Zero total gives no slices. */
    fun sweeps(values: List<Int>, gap: Float = 3f): List<Float> {
        val total = values.sum(); if (total <= 0) return values.map { 0f }
        val usable = 360f - gap * values.count { it > 0 }
        return values.map { if (it <= 0) 0f else usable * it / total }
    }

    /** y positions (0 top, 1 bottom) for a line chart, scaled to the biggest value. */
    fun normalized(values: List<Long>): List<Float> { val m = values.maxOrNull()?.coerceAtLeast(1) ?: 1; return values.map { 1f - it / m.toFloat() } }
}

/** A slow-moving gradient behind the intro card, and a dot that pulses to say it was read live. */
@Composable
internal fun LiveHero(title: String, body: String) {
    val t = rememberInfiniteTransition(label = "hero")
    val drift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Reverse), label = "drift")
    val pulse by t.animateFloat(0.4f, 1f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val a = MaterialTheme.colorScheme.primaryContainer; val b = MaterialTheme.colorScheme.tertiaryContainer; val on = MaterialTheme.colorScheme.onPrimaryContainer
    val dots = remember { List(14) { i -> Triple((i * 37 % 100) / 100f, (i * 61 % 100) / 100f, 0.5f + (i % 4) * 0.35f) } }
    val dotColor = MaterialTheme.colorScheme.primary
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(a, b, a), start = Offset(-400f + 900f * drift, 0f), end = Offset(400f + 900f * drift, 500f)))
            .drawBehind { dots.forEach { (x, y, r) -> drawCircle(dotColor.copy(alpha = 0.10f + 0.10f * ((drift + x) % 1f)), r * 5.dp.toPx(), Offset(((x + drift * 0.08f * r) % 1f) * size.width, ((y + drift * 0.05f) % 1f) * size.height)) } }
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = pulse)))
            Text("  Read live from this phone", style = MaterialTheme.typography.labelMedium, color = on)
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = on)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = on)
    }
}

/** Your day as a clock: 24 spokes, each as long as the time you spend on the phone in that hour. They grow out from the middle once. */
@Composable
internal fun DayClock(heat: Array<LongArray>) {
    val totals = remember(heat) { ChartMath.hourTotals(heat) }
    val peak = ChartMath.peakHour(totals)
    val grow = remember(heat) { Animatable(0f) }
    LaunchedEffect(heat) { grow.animateTo(1f, tween(1000, easing = FastOutSlowInEasing)) }
    var picked by remember(heat) { mutableIntStateOf(-1) }
    val haptics = LocalHapticFeedback.current
    val shown = if (picked >= 0) picked else peak
    val color = MaterialTheme.colorScheme.primary; val dim = MaterialTheme.colorScheme.surfaceVariant; val hot = MaterialTheme.colorScheme.tertiary
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(230.dp).clickable { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); picked = if (shown == null) -1 else (shown + 1) % 24 }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(230.dp)) {
                val c = center; val inner = size.minDimension * 0.24f; val outer = size.minDimension / 2f - 4.dp.toPx(); val max = (totals.maxOrNull() ?: 1L).coerceAtLeast(1L).toFloat()
                for (h in 0 until 24) {
                    val ang = (h / 24f) * 2 * PI - PI / 2
                    val f = totals[h] / max * grow.value
                    val len = inner + (outer - inner) * f.coerceAtLeast(0.04f)
                    val start = Offset(c.x + cos(ang).toFloat() * inner, c.y + sin(ang).toFloat() * inner)
                    val end = Offset(c.x + cos(ang).toFloat() * len, c.y + sin(ang).toFloat() * len)
                    drawLine(if (h == shown) hot else if (totals[h] > 0) color.copy(alpha = 0.45f + 0.55f * f.coerceIn(0f, 1f)) else dim, start, end, strokeWidth = 7.dp.toPx(), cap = StrokeCap.Round)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (shown != null) {
                    Text(Habits.clock(shown * 60), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("${totals[shown] / 60_000 / 7} min a day", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else Text("No data yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(if (peak != null) "Tap the clock to step through the hours. Your busiest is ${Habits.clock(peak * 60)}." else "Use your phone for a day and this fills in.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A ring split by kind of access, sweeping round once. Tap a slice's row to pick it. */
@Composable
internal fun AccessDonut(items: List<Pair<String, Int>>, colors: List<Color>, selected: Int, onPick: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val sweeps = remember(items) { ChartMath.sweeps(items.map { it.second }) }
    val grow = remember(items) { Animatable(0f) }
    LaunchedEffect(items) { grow.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(130.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(130.dp)) {
                var from = -90f; val w = 16.dp.toPx(); val pad = w / 2 + 4.dp.toPx()
                sweeps.forEachIndexed { i, s ->
                    val span = s * grow.value
                    drawArc(colors[i % colors.size].copy(alpha = if (selected < 0 || selected == i) 1f else 0.3f), from, span, false, Offset(pad, pad), Size(size.width - 2 * pad, size.height - 2 * pad), style = Stroke(if (selected == i) w + 4.dp.toPx() else w, cap = StrokeCap.Butt))
                    from += s + 3f
                }
            }
            Text(items.getOrNull(selected)?.second?.toString() ?: items.sumOf { it.second }.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items.take(6).forEachIndexed { i, (name, n) ->
                Row(Modifier.fillMaxWidth().pressScale(0.97f).clip(RoundedCornerShape(10.dp)).clickable { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onPick(if (selected == i) -1 else i) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(colors[i % colors.size])); Text("  $name", Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodySmall); Text("$n", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** A smooth line with a fill underneath, drawn left to right once. */
@Composable
internal fun AreaChart(values: List<Long>, modifier: Modifier = Modifier.fillMaxWidth().height(90.dp)) {
    val draw = remember(values) { Animatable(0f) }
    LaunchedEffect(values) { draw.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    val line = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val ys = ChartMath.normalized(values); val dx = size.width / (values.size - 1); val top = 6.dp.toPx(); val h = size.height - top - 2.dp.toPx()
        fun pt(i: Int) = Offset(i * dx, top + ys[i] * h)
        val path = Path().apply {
            moveTo(pt(0).x, pt(0).y)
            for (i in 1 until values.size) { val a = pt(i - 1); val b = pt(i); cubicTo((a.x + b.x) / 2, a.y, (a.x + b.x) / 2, b.y, b.x, b.y) }
        }
        val clipW = size.width * draw.value
        clipRect(right = clipW) {
            val fill = Path().apply { addPath(path); lineTo(pt(values.size - 1).x, size.height); lineTo(0f, size.height); close() }
            drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.35f), Color.Transparent)))
            drawPath(path, line, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }
        if (draw.value >= 1f) drawCircle(line, 5.dp.toPx(), pt(values.size - 1))
    }
}


/** A big gauge: how much the apps on this phone could see, from Quiet to Wide open. The arc sweeps round, the number counts up, and the colour warms as it climbs. */
@Composable
internal fun ExposureGauge(score: Int, word: String, detail: String) {
    val haptics = LocalHapticFeedback.current
    val shown = remember(score) { Animatable(0f) }
    LaunchedEffect(score) {
        shown.animateTo(score.toFloat(), spring(Spring.DampingRatioLowBouncy, Spring.StiffnessVeryLow))
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    val track = MaterialTheme.colorScheme.surfaceVariant
    val calm = MaterialTheme.colorScheme.primary; val warn = Color(0xFFF2A33A); val hot = MaterialTheme.colorScheme.error
    val f = (shown.value / 100f).coerceIn(0f, 1f)
    val arc = if (f < 0.5f) androidx.compose.ui.graphics.lerp(calm, warn, f * 2) else androidx.compose.ui.graphics.lerp(warn, hot, (f - 0.5f) * 2)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(200.dp)) {
                val w = 18.dp.toPx(); val pad = w; val sz = Size(size.width - 2 * pad, size.height - 2 * pad)
                drawArc(track, 150f, 240f, false, Offset(pad, pad), sz, style = Stroke(w, cap = StrokeCap.Round))
                drawArc(arc.copy(alpha = 0.25f), 150f, 240f * f, false, Offset(pad - 3.dp.toPx(), pad - 3.dp.toPx()), Size(sz.width + 6.dp.toPx(), sz.height + 6.dp.toPx()), style = Stroke(w + 6.dp.toPx(), cap = StrokeCap.Round))
                drawArc(arc, 150f, 240f * f, false, Offset(pad, pad), sz, style = Stroke(w, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Math.round(shown.value).toString(), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = arc)
                Text(word, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

/** A grey bar that sweeps with light, standing in for content that is loading. */
@Composable
internal fun Shimmer(modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "x")
    val base = MaterialTheme.colorScheme.surfaceVariant; val hi = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(Brush.horizontalGradient(listOf(base, hi, base), startX = x * 600f, endX = x * 600f + 500f)))
}

/** Three bars that bounce, to show something is being listened to right now. */
@Composable
internal fun Equalizer(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "eq")
    val a by t.animateFloat(0.2f, 1f, infiniteRepeatable(tween(520, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(1f, 0.25f, infiniteRepeatable(tween(680, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    val c by t.animateFloat(0.3f, 0.9f, infiniteRepeatable(tween(440, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "c")
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(18.dp)) {
        listOf(a, b, c).forEachIndexed { i, v -> val w = size.width / 5f; val h = size.height * v; drawRoundRect(color, Offset(i * 2 * w, size.height - h), Size(w, h), androidx.compose.ui.geometry.CornerRadius(w / 2)) }
    }
}
