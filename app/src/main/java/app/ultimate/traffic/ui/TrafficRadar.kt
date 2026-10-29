package app.ultimate.traffic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import app.ultimate.traffic.model.DomainKind
import app.ultimate.traffic.model.HostView
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin

private class Pulse(val host: String, val born: Float, val inward: Boolean)

/**
 * Every host the apps have talked to, as a dot around the phone. Ad networks sit on the outer ring, analytics in the middle, everything
 * else close in. A dot grows when it first appears and sends a pulse along its line each time data moves.
 */
@Composable
fun TrafficRadar(hosts: List<HostView>, modifier: Modifier = Modifier, adColor: Color = MaterialTheme.colorScheme.error) {
    val primary = MaterialTheme.colorScheme.primary
    val analytics = MaterialTheme.colorScheme.tertiary
    val ring = MaterialTheme.colorScheme.outlineVariant
    val centre = MaterialTheme.colorScheme.primaryContainer

    var clock by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { while (true) androidx.compose.runtime.withFrameNanos { clock = it / 1e9f } }

    val shown = remember(hosts) { hosts.sortedByDescending { it.lastAt }.take(30) }
    val firstSeen = remember { ConcurrentHashMap<String, Float>() }
    val lastBytes = remember { ConcurrentHashMap<String, Long>() }
    val lastDown = remember { ConcurrentHashMap<String, Long>() }
    val pulses = remember { ArrayList<Pulse>() }

    // A pulse for every host whose byte count moved since the last snapshot; inward when mostly downloaded.
    LaunchedEffect(hosts) {
        val now = clock
        hosts.forEach { h ->
            val before = lastBytes.put(h.host, h.total)
            val downBefore = lastDown.put(h.host, h.down) ?: 0
            if (before != null && h.total > before && pulses.size < 60) pulses += Pulse(h.host, now, (h.down - downBefore) * 2 > (h.total - before))
        }
    }

    Canvas(modifier.fillMaxWidth().aspectRatio(1f)) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = min(size.width, size.height) / 2 * 0.94f
        listOf(0.46f, 0.70f, 0.94f).forEach { drawCircle(ring.copy(alpha = 0.55f), r * it, c, style = Stroke(1.5f)) }

        // The sweep.
        val sweep = (clock * 70f) % 360f
        drawCircle(Brush.sweepGradient(0f to Color.Transparent, 0.82f to Color.Transparent, 1f to primary.copy(alpha = 0.28f), center = c), r, c)
        val a = sweep * PI.toFloat() / 180f
        drawLine(primary.copy(alpha = 0.55f), c, Offset(c.x + cos(a) * r, c.y + sin(a) * r), 2f)

        fun place(h: HostView): Offset {
            val seed = (h.host.hashCode() and 0x7fffffff) % 3600 / 10f
            val ang = seed * PI.toFloat() / 180f
            val ringR = when (h.kind) { DomainKind.Ads -> 0.94f; DomainKind.Analytics, DomainKind.Crash -> 0.70f; DomainKind.Other -> 0.46f }
            val jitter = ((h.host.hashCode() ushr 9) % 100) / 100f * 0.12f - 0.06f
            return Offset(c.x + cos(ang) * r * (ringR + jitter), c.y + sin(ang) * r * (ringR + jitter))
        }

        shown.forEach { h ->
            val p = place(h)
            val color = when (h.kind) { DomainKind.Ads -> adColor; DomainKind.Analytics, DomainKind.Crash -> analytics; DomainKind.Other -> primary }
            val born = firstSeen.getOrPut(h.host) { clock }
            val pop = ((clock - born) / 0.5f).coerceIn(0f, 1f)
            val eased = 1f - (1f - pop) * (1f - pop)
            drawLine(color.copy(alpha = 0.16f * eased), c, p, 1.5f)
            val base = 5f + 9f * (ln(1f + h.total / 2000f) / ln(1f + 500_000f / 2000f)).coerceIn(0f, 1f)
            val glow = if (h.openFlows > 0) 0.25f + 0.15f * sin(clock * 4f + base) else 0f
            drawCircle(color.copy(alpha = (0.22f + glow) * eased), base * 2.0f * eased, p)
            drawCircle(color.copy(alpha = eased), base * eased, p)
            if (h.blocked) drawCircle(Color.White.copy(alpha = 0.9f * eased), base * 0.35f * eased, p)
        }

        // Pulses travel along the line between a dot and the middle.
        val live = pulses.iterator()
        while (live.hasNext()) {
            val pl = live.next()
            val age = (clock - pl.born) / 0.9f
            if (age >= 1f) { live.remove(); continue }
            val h = shown.firstOrNull { it.host == pl.host } ?: continue
            val p = place(h)
            val t = if (pl.inward) age else 1f - age
            val pos = Offset(c.x + (p.x - c.x) * (1f - t), c.y + (p.y - c.y) * (1f - t))
            drawCircle(Color.White.copy(alpha = 1f - age), 4f, pos)
        }

        // The phone.
        drawCircle(centre, r * 0.17f, c)
        drawCircle(primary, r * 0.17f, c, style = Stroke(3f))
        drawCircle(primary.copy(alpha = 0.18f + 0.12f * sin(clock * 3f)), r * (0.20f + 0.015f * sin(clock * 3f)), c)
    }
}
