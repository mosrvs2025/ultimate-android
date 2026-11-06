package app.ultimate.watch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.air.WatchMessages
import app.ultimate.air.WristCalibration
import kotlinx.coroutines.delay

private val X = Color(0xFFFF6B6B)
private val Y = Color(0xFF3DDC97)
private val Z = Color(0xFF7FD3FF)
private val J = Color(0xFFFFC857)

/**
 * The watch's sensors, live: how fast the wrist turns about each axis, and how hard it jolts, over the last four
 * seconds, with each gesture the watch recognised marked. While this shows, the phone asks the watch to stream.
 */
@Composable
internal fun LiveSensorsCard() {
    val context = LocalContext.current
    val samples by WatchLink.sensors.collectAsStateWithLifecycle()
    val marks by WatchLink.marks.collectAsStateWithLifecycle()
    // Keep asking: the watch stops streaming on its own a few seconds after the phone stops asking.
    DisposableEffect(Unit) { onDispose { WatchLink.sendToWatch(context, "stream:off") } }
    LaunchedEffect(Unit) { while (true) { WatchLink.sendToWatch(context, "stream:on"); delay(4_000) } }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(50) } }
    val live = samples.isNotEmpty() && now - samples.last().t < 1_500

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Live from your watch", fontWeight = FontWeight.SemiBold)
        if (!live) Text("Open Ultimate+ on the watch to see its sensors here.", style = MaterialTheme.typography.bodySmall)
        Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF111418))) {
            Canvas(Modifier.fillMaxWidth().height(150.dp)) {
                val span = 4_000f; val maxTurn = 12f; val maxJolt = 30f
                fun xOf(t: Long) = size.width * (1f - (now - t) / span)
                val mid = size.height / 2
                drawLine(Color.White.copy(alpha = 0.15f), Offset(0f, mid), Offset(size.width, mid))
                val recent = samples.filter { now - it.t <= span }
                fun line(c: Color, v: (WristCalibration.Sample) -> Float, scale: Float, base: Float = mid) {
                    if (recent.size < 2) return
                    val p = Path()
                    recent.forEachIndexed { i, s -> val y = base - v(s) / scale * mid * 0.95f; if (i == 0) p.moveTo(xOf(s.t), y) else p.lineTo(xOf(s.t), y) }
                    drawPath(p, c, style = Stroke(2.dp.toPx()))
                }
                line(J.copy(alpha = 0.6f), { it.jolt }, maxJolt, size.height)
                line(X, { it.gx }, maxTurn); line(Y, { it.gy }, maxTurn); line(Z, { it.gz }, maxTurn)
                marks.filter { now - it.first <= span }.forEach { (t, _) ->
                    drawLine(Color.White.copy(alpha = 0.7f), Offset(xOf(t), 0f), Offset(xOf(t), size.height), strokeWidth = 1.5.dp.toPx())
                }
            }
            marks.lastOrNull()?.takeIf { now - it.first < 1_500 }?.let { (_, k) ->
                Text(WatchMessages.GESTURES.firstOrNull { it.first == k }?.second ?: k, Modifier.align(Alignment.TopEnd).padding(8.dp), color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(X to "Turn X", Y to "Turn Y", Z to "Turn Z", J to "Jolt").forEach { (c, l) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(c)); Text(" $l", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        samples.lastOrNull()?.takeIf { live }?.let { s ->
            Text("Now: X %+.1f  Y %+.1f  Z %+.1f rad/s · jolt %.1f m/s²".format(s.gx, s.gy, s.gz, s.jolt), style = MaterialTheme.typography.labelMedium)
        }
        Text("White lines mark gestures the watch recognised. A flick shows as a spike on one colour and a smaller one back as your wrist stops.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Calibrate: do each gesture three times; the phone reads which way each turns on this watch and how hard you flick,
 * then sends that to the watch. Fixes "flick up reads as flick down" without anyone guessing axes.
 */
@Composable
internal fun CalibrateCard() {
    val context = LocalContext.current
    val samples by WatchLink.sensors.collectAsStateWithLifecycle()
    val order = listOf("flick_up", "flick_down", "flick_left", "flick_right", "twist")
    var step by remember { mutableStateOf(-1) } // -1 not started, order.size done
    val got = remember { mutableStateMapOf<String, List<WristCalibration.Signature>>() }
    var since by remember { mutableStateOf(0L) }
    val counted = remember { HashSet<Long>() }
    var result by remember { mutableStateOf<WristCalibration.Config?>(null) }
    var sent by remember { mutableStateOf(false) }

    // Each new burst of turning since this step began is one example of the gesture being taught.
    LaunchedEffect(samples, step) {
        val kind = order.getOrNull(step) ?: return@LaunchedEffect
        val bursts = WristCalibration.bursts(samples.filter { it.t > since })
        // A burst is complete once the wrist has been still for a moment after it.
        val done = bursts.filter { b -> samples.lastOrNull()?.let { it.t - b.last().t > 250 } == true }
        // Counted once each, by when they started, so a slow learner never loses one to the graph's short window.
        done.forEach { b ->
            if (b.first().t in counted) return@forEach
            counted += b.first().t
            WristCalibration.signature(b)?.let { got[kind] = (got[kind].orEmpty() + it).take(3) }
        }
        if ((got[kind]?.size ?: 0) >= 3) { step++; since = System.currentTimeMillis(); if (step >= order.size) result = WristCalibration.build(got.toMap()) }
    }

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Calibrate gestures to your wrist", fontWeight = FontWeight.SemiBold)
        when {
            step < 0 -> {
                Text("If a flick reads as the wrong one (up as down, or as a shake), teach the watch how you move: three of each gesture, about a minute. Keep Ultimate+ open on the watch.",
                    style = MaterialTheme.typography.bodySmall)
                Button({ got.clear(); result = null; sent = false; since = System.currentTimeMillis(); step = 0 }) { Text("Start calibrating") }
            }
            step < order.size -> {
                val kind = order[step]
                Text("${step + 1} of ${order.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(WatchMessages.GESTURES.first { it.first == kind }.second, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(HOW[kind] ?: "", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    repeat(3) { i -> Box(Modifier.size(18.dp).clip(CircleShape).background(if (i < (got[kind]?.size ?: 0)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)) }
                    Text("  ${got[kind]?.size ?: 0} of 3")
                }
                got[kind]?.lastOrNull()?.let { Text("Last one: " + WristCalibration.describe(it), style = MaterialTheme.typography.labelSmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ got.remove(kind); since = System.currentTimeMillis() }) { Text("Redo this one") }
                    TextButton({ step = -1 }) { Text("Cancel") }
                }
            }
            else -> result?.let { c ->
                Text("What your wrist does:", style = MaterialTheme.typography.bodySmall)
                order.forEach { k ->
                    val label = WatchMessages.GESTURES.first { it.first == k }.second
                    val where = if (k == "twist") c.twistAxis?.let { "turning about ${it.uppercaseChar()} and back" } else c.directions.entries.firstOrNull { it.value == k }?.key?.let { "turning about ${it[0].uppercaseChar()}, ${if (it[1] == '+') "positive" else "negative"}" }
                    Text("$label: ${where ?: "not distinct enough, try it again"}", style = MaterialTheme.typography.bodySmall)
                }
                Text("Flicks count from %.1f rad/s.".format(c.flickRate), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ WatchLink.sendToWatch(context, WristCalibration.encode(c)); sent = true }) { Text(if (sent) "Sent ✓" else "Send to watch") }
                    OutlinedButton({ got.clear(); result = null; sent = false; since = System.currentTimeMillis(); step = 0 }) { Text("Again") }
                }
                if (sent) Text("The watch buzzes when it takes the calibration. Try your flicks on the graph above.", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (step < 0) TextButton({ WatchLink.sendToWatch(context, "calibration:reset") }) { Text("Reset the watch to its default") }
    }
}

private val HOW = mapOf(
    "flick_up" to "Flick your hand up, as if flicking water off your fingers, then let it rest. Three times.",
    "flick_down" to "Flick your hand down, then let it rest. Three times.",
    "flick_left" to "Flick your hand sideways to the left, then rest. Three times.",
    "flick_right" to "Flick your hand sideways to the right, then rest. Three times.",
    "twist" to "Turn your wrist like turning a key, and straight back. Three times.",
)
