package app.ultimate.mesh

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ultimate.core.LibraryItem
import app.ultimate.core.Tool
import app.ultimate.core.ToolLaunch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Registered reflectively from main so offline editions, which have no INTERNET permission, never include it. */
class MeshTool(context: Context) : Tool {
    private val manager = MeshManager.get(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override val id = "mesh"
    override val title = "Octopus Mesh"
    override val tagline = "Your trusted devices share work, no server"
    override val icon = Icons.Rounded.Hub
    override val headline = manager.state.map { s -> if (s.running) "LIVE · ${s.alive.size} arm(s) connected" else "Off · pair devices with a code" }
        .stateIn(scope, SharingStarted.Eagerly, "Off · pair devices with a code")
    override val library = MutableStateFlow<List<LibraryItem>>(emptyList())
    override val acceptedMimePrefixes = emptyList<String>()
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = false
    override suspend fun refresh() = Unit

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable override fun Screen(onExit: () -> Unit, launch: ToolLaunch) {
        val s by manager.state.collectAsState()
        var code by remember { mutableStateOf("") }
        var address by remember { mutableStateOf("") }
        Scaffold(topBar = {
            TopAppBar(title = { Text("Octopus Mesh") }, navigationIcon = {
                IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            })
        }) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                if (s.running) LiveBar(s) { manager.stop() }
                OctopusCanvas(s, Modifier.fillMaxWidth().height(300.dp))
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!s.running) {
                        Text("Type the same pairing code on each device. Only devices with the code can see or send work. Nothing listens until you start, and payloads are signed, not encrypted.",
                            style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(code, { code = it }, label = { Text("Pairing code (6+)") }, singleLine = true, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { manager.start(code) }, enabled = code.trim().length >= 6) { Text("Start") }
                        }
                        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    } else {
                        Text("Key ${s.fingerprint} · this phone ${"%.1f".format(s.capacity)} units/ms · port ${s.port}" +
                            (s.stats?.let { " · sent ${it.sent} recv ${it.received} refused ${it.rejected}" } ?: ""),
                            style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { manager.run(20_000) }) { Text("Reflex task") }
                            FilledTonalButton(onClick = { manager.run(3_000_000) }) { Text("Heavy task") }
                            FilledTonalButton(onClick = { manager.run(1_500_000, 12) }) { Text("Burst ×12") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(address, { address = it }, label = { Text("Add by address (ip:port)") }, singleLine = true, modifier = Modifier.weight(1f))
                            TextButton(onClick = { manager.addAddress(address); address = "" }, enabled = address.isNotBlank()) { Text("Add") }
                        }
                    }
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(16.dp)) {
                    items(s.log) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp) }
                }
            }
        }
    }

    @Composable private fun LiveBar(s: MeshManager.State, onStop: () -> Unit) {
        Row(Modifier.fillMaxWidth().background(Color(0xFF3A0D2E)).padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics { contentDescription = "Mesh is live and listening on the local network" },
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(Color(0xFFFF4F9A), RoundedCornerShape(5.dp)))
            Spacer(Modifier.width(8.dp))
            Text("MESH LIVE · listening on Wi-Fi · ${s.alive.size} connected" + if (s.busy > 0) " · ${s.busy} running" else "",
                color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onStop) { Text("Disconnect", color = Color(0xFFFFB3D1)) }
        }
    }

    /** The phone is the mantle; each trusted device is an arm. Live arms sway, cut arms droop and fade, work travels as light. */
    @Composable private fun OctopusCanvas(s: MeshManager.State, modifier: Modifier) {
        val t by rememberInfiniteTransition(label = "sway").animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(6_000, easing = LinearEasing)), label = "t")
        val skin = if (s.running) Color(0xFFFF6FA8) else Color(0xFF6B6B7A)
        Canvas(modifier.background(Color(0xFF0D0B14))) {
            val c = Offset(size.width / 2, size.height * 0.42f)
            val arms = s.peers.ifEmpty { null }
            val count = maxOf(arms?.size ?: 0, 8)
            val now = System.currentTimeMillis()
            for (i in 0 until count) {
                val peer = arms?.getOrNull(i)
                val alive = peer != null && peer.id in s.alive
                val angle = (PI * (0.15 + 0.7 * (i + 0.5) / count)).toFloat()
                val len = if (peer != null) size.height * 0.5f else size.height * 0.22f
                val sway = sin(t + i * 0.9f) * (if (alive) 18f else 6f)
                val droop = if (peer != null && !alive) size.height * 0.15f else 0f
                val end = Offset(c.x + cos(angle) * len * 1.4f + sway, c.y + sin(angle) * len + droop)
                val ctrl = Offset(c.x + cos(angle) * len * 0.5f - sway, c.y + sin(angle) * len * 0.2f + 30f)
                val color = when { peer == null -> skin.copy(alpha = 0.18f); alive -> skin; else -> Color(0xFF55505E) }
                tentacle(c, ctrl, end, color, base = 16f + (peer?.capacity?.toFloat()?.coerceIn(0f, 30f) ?: 0f) * 0.4f)
                if (peer != null) {
                    drawCircle(color, 10f, end)
                    s.flows.filter { it.to == peer.id }.forEach { f ->
                        val k = ((now - f.at) / 900f).coerceIn(0f, 1f)
                        drawCircle(Color(0xFFFFF2A8).copy(alpha = 1 - k), 9f, bez(c, ctrl, end, k))
                    }
                }
            }
            val pulse = 1f + 0.04f * sin(t * 2)
            drawCircle(skin.copy(alpha = 0.25f), 70f * pulse, c)
            drawCircle(skin, 52f * pulse, c)
            if (s.flows.any { it.to == s.selfId }) drawCircle(Color(0xFFFFF2A8), 18f, c)
            drawCircle(Color(0xFF0D0B14), 7f, Offset(c.x - 18f, c.y - 10f)); drawCircle(Color(0xFF0D0B14), 7f, Offset(c.x + 18f, c.y - 10f))
        }
    }

    private fun bez(a: Offset, c: Offset, b: Offset, k: Float): Offset {
        val u = 1 - k
        return Offset(u * u * a.x + 2 * u * k * c.x + k * k * b.x, u * u * a.y + 2 * u * k * c.y + k * k * b.y)
    }

    /** A tapering arm drawn as overlapping discs: cheap, and reads as flesh rather than a network edge. */
    private fun DrawScope.tentacle(a: Offset, c: Offset, b: Offset, color: Color, base: Float) {
        val steps = 28
        for (j in 0..steps) {
            val k = j / steps.toFloat()
            drawCircle(color, base * (1 - k * 0.8f), bez(a, c, b, k))
        }
    }
}
