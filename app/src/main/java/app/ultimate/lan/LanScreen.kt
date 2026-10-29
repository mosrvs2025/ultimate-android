package app.ultimate.lan

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.DeviceUnknown
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.core.ShellActions
import app.ultimate.core.ToolBackButton
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanScreen(m: LanManager, onExit: () -> Unit) {
    val s by m.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<Device?>(null) }
    BackHandler(onBack = onExit)
    Scaffold(topBar = { TopAppBar(title = { Text("Network scanner") }, navigationIcon = { ToolBackButton(onExit) }, actions = { ShellActions() }) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Radar(s.sorted, s.scanning) }
            item {
                if (s.scanning) {
                    Text("Looking for devices… ${s.done}/${s.total}", style = MaterialTheme.typography.titleSmall)
                    LinearProgressIndicator(progress = { if (s.total > 0) s.done / s.total.toFloat() else 0f }, Modifier.fillMaxWidth().padding(top = 6.dp))
                    OutlinedButton(m::stop, Modifier.padding(top = 8.dp)) { Text("Stop") }
                } else {
                    Button(m::scan, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) {
                        Icon(Icons.Rounded.Radar, null); Spacer(Modifier.width(8.dp)); Text(if (s.finishedAt > 0) "Scan again" else "Scan my network")
                    }
                }
                s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            }
            s.info?.let { info -> item { InfoCard(info, s) } }
            items(s.findings, key = { it.ip + it.title }) { f -> FindingRow(f) }
            if (s.devices.isNotEmpty()) item { Text("${s.devices.size} devices", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(s.sorted, key = { it.ip }) { d -> DeviceRow(d) { open = d } }
            if (s.finishedAt > 0) item {
                Text(
                    "Some devices stay quiet (phones asleep, guest networks) and may not show. A phone can't see other devices' traffic without root; this shows who is there and what each one exposes.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    open?.let { d -> DeviceSheet(s.devices[d.ip] ?: d) { open = null } }
}

private fun icon(k: DeviceKind): ImageVector = when (k) {
    DeviceKind.Router -> Icons.Rounded.Router; DeviceKind.Phone -> Icons.Rounded.Smartphone; DeviceKind.Computer -> Icons.Rounded.Computer
    DeviceKind.Tv -> Icons.Rounded.Cast; DeviceKind.Speaker -> Icons.Rounded.Speaker; DeviceKind.Printer -> Icons.Rounded.Print
    DeviceKind.Camera -> Icons.Rounded.Videocam; DeviceKind.Storage -> Icons.Rounded.Storage; DeviceKind.SmartHome -> Icons.Rounded.Home
    DeviceKind.Unknown -> Icons.Rounded.DeviceUnknown
}

@Composable
private fun Radar(devices: List<Device>, scanning: Boolean) {
    val sweep by rememberInfiniteTransition(label = "r").animateFloat(0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "a")
    val primary = MaterialTheme.colorScheme.primary
    val danger = MaterialTheme.colorScheme.error
    val ring = MaterialTheme.colorScheme.outlineVariant
    Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(200.dp)) {
            val c = center; val r = size.minDimension / 2
            listOf(0.33f, 0.66f, 1f).forEach { drawCircle(ring, r * it, c, style = Stroke(1.dp.toPx())) }
            if (scanning) {
                val a = Math.toRadians(sweep.toDouble())
                drawLine(Brush.linearGradient(listOf(primary.copy(alpha = 0f), primary), c, Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())), c,
                    Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat()), strokeWidth = 3.dp.toPx())
            }
            devices.forEach { d ->
                val last = d.ip.substringAfterLast('.').toIntOrNull() ?: 0
                val angle = Math.toRadians(last * 137.5)
                val dist = if (d.isThisPhone) 0f else if (d.isGateway) 0.25f else 0.45f + (last % 7) / 14f
                val p = Offset(c.x + r * dist * cos(angle).toFloat(), c.y + r * dist * sin(angle).toFloat())
                val risky = d.openPorts.any { Lan.service(it)?.risk == Risk.High }
                drawCircle(if (risky) danger else primary, if (d.isGateway || d.isThisPhone) 7.dp.toPx() else 5.dp.toPx(), p)
            }
        }
        Text("${devices.size}", Modifier.align(Alignment.BottomEnd).padding(14.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun InfoCard(info: NetInfo, s: LanManager.State) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOfNotNull(
                "This phone" to "${info.ip}/${info.prefix}",
                info.gateway?.let { "Router" to it },
                "DNS" to info.dns.joinToString().ifEmpty { "unknown" },
                "Router reply" to (s.gatewayMs?.let { "$it ms" } ?: "…"),
                "Internet reply" to (s.internetMs?.let { "$it ms" } ?: if (s.scanning) "…" else "no answer"),
            ).forEach { (k, v) ->
                Row { Text(k, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant); Text(v, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}

@Composable
private fun FindingRow(f: LanFinding) {
    val c = if (f.risk == Risk.High) MaterialTheme.colorScheme.error else Color(0xFFE08A00)
    Card(colors = CardDefaults.cardColors(containerColor = c.copy(alpha = 0.10f)), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(14.dp)) {
            Icon(Icons.Rounded.Warning, null, tint = c)
            Spacer(Modifier.width(10.dp))
            Column { Text(f.title, fontWeight = FontWeight.SemiBold); Text(f.detail, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun DeviceRow(d: Device, onClick: () -> Unit) {
    val kind = Lan.classify(d)
    val risky = d.openPorts.any { Lan.service(it)?.risk == Risk.High }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(if (risky) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon(kind), null)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name ?: kind.label, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(
                listOfNotNull(d.ip, kind.label.takeIf { d.name != null }, if (d.openPorts.isEmpty()) null else "${d.openPorts.size} open port${if (d.openPorts.size == 1) "" else "s"}").joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceSheet(d: Device, onDismiss: () -> Unit) {
    val kind = Lan.classify(d)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon(kind), null, Modifier.size(32.dp)); Spacer(Modifier.width(12.dp))
                Column { Text(d.name ?: kind.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("${d.ip} · ${kind.label}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            d.vendorHint?.let { Text("Says it is: $it", style = MaterialTheme.typography.bodySmall) }
            if (d.announces.isNotEmpty()) Text("Announces: ${d.announces.joinToString()}", style = MaterialTheme.typography.bodySmall)
            d.latencyMs?.let { Text("Replies in $it ms", style = MaterialTheme.typography.bodySmall) }
            Text("Open ports", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (d.openPorts.isEmpty()) Text("None of the common ones. That's good.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            d.openPorts.forEach { p ->
                val svc = Lan.service(p)
                Row {
                    Text("$p", Modifier.width(64.dp), fontFamily = FontFamily.Monospace, color = if (svc?.risk == Risk.High) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    Column { Text(svc?.name ?: "Unknown", fontWeight = FontWeight.Medium); svc?.let { Text(it.meaning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                }
            }
        }
    }
}
