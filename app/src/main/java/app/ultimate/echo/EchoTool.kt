package app.ultimate.echo

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.UltimateApp
import app.ultimate.core.LibraryItem
import app.ultimate.core.Signal
import app.ultimate.core.Tool
import app.ultimate.core.ToolLaunch
import app.ultimate.core.motionOn
import app.ultimate.rules.TriggerType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Echo: the phone's sonar. It senses a hand or a body moving near it, and which way, with no camera and no touch. */
class EchoTool(private val app: UltimateApp) : Tool {
    override val id = "echo"
    override val title = "Echo"
    override val tagline = "Feel movement near the phone, with sound you can't hear"
    override val icon = Icons.Rounded.Waves
    override val acceptedMimePrefixes = emptyList<String>()
    override val headline: StateFlow<String> = MutableStateFlow("Sonar for your phone")
    override val library: StateFlow<List<LibraryItem>> = MutableStateFlow(emptyList())
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = false
    override suspend fun refresh() = Unit

    val engine by lazy { EchoEngine(app, app.senses) }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Screen(onExit: () -> Unit, launch: ToolLaunch) {
        BackHandler(onBack = onExit)
        val state by engine.state.collectAsStateWithLifecycle()
        val recent by app.senses.recent.collectAsStateWithLifecycle()
        val echoes = recent.filter { it.source == "echo" }
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) engine.start() }
        val warning = remember { engine.support() }
        // Echo runs while you look at it unless an automation is listening; leaving the screen stops it.
        DisposableEffect(Unit) { onDispose { if (!app.rules.rules.value.any { it.enabled && it.trigger.type == TriggerType.Sensed }) engine.stop() } }
        Scaffold(topBar = {
            TopAppBar(title = { Text("Echo") }, navigationIcon = { IconButton(onExit) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
        }) { pad ->
            LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item { Sonar(state, Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(28.dp))) }
                item {
                    Text(
                        when (val s = state) {
                            EchoState.Off -> "Echo plays a tone too high for most people to hear and listens for it bouncing back. Wave a hand toward the phone, then away."
                            EchoState.Calibrating -> "Listening to the room for a second. Keep still…"
                            is EchoState.Sensing -> when (s.reading.direction) {
                                EchoReading.Direction.Approach -> "Something is coming closer"
                                EchoReading.Direction.Recede -> "Something is moving away"
                                EchoReading.Direction.Still -> "Still. Move a hand near the top of the phone"
                            }
                            is EchoState.Failed -> s.why
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (state is EchoState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (engine.running) FilledTonalButton({ engine.stop() }) { Text("Stop") }
                        else Button({ ask.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Start sensing") }
                        TextButton({ app.tools.firstOrNull { it.id == "rules" }?.let { app.shell.open(it, ToolLaunch(query = "When something comes closer to the phone, ")) } }) { Text("Make it do something") }
                    }
                }
                warning?.let { item { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) } }
                item {
                    Text(
                        "Private by design: nothing is recorded or kept. The mic is used only while Echo runs, and it stops when you leave unless a rule is listening. You can stop it from the notification shade at any time. Works best with the media volume at half or more.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (echoes.isNotEmpty()) {
                    item { Text("Just sensed", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
                    items(echoes.take(12), key = { "${it.at}${it.kind}" }) { SignalRow(it) }
                }
            }
        }
    }
}

@Composable
private fun SignalRow(s: Signal) {
    val ago = ((System.currentTimeMillis() - s.at) / 1000).coerceAtLeast(0)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (s.kind == "approach") "↓" else "↑", style = MaterialTheme.typography.titleLarge, color = if (s.kind == "approach") ApproachColor else RecedeColor, modifier = Modifier.padding(end = 12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.label, style = MaterialTheme.typography.bodyMedium)
            Text(if (ago < 60) "${ago}s ago" else "${ago / 60} min ago", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val ApproachColor = Color(0xFFFF8A5C)
private val RecedeColor = Color(0xFF5CC8FF)

/**
 * The sonar itself: rings leave the top of the screen (where the speaker is) like the real tone. Movement swells them;
 * something approaching warms and squeezes them together, something leaving cools and spreads them out.
 */
@Composable
private fun Sonar(state: EchoState, modifier: Modifier) {
    val reading = (state as? EchoState.Sensing)?.reading
    val on = state is EchoState.Sensing || state is EchoState.Calibrating
    val motion by animateFloatAsState(reading?.motion ?: 0f, tween(180), label = "m")
    val dir = reading?.direction ?: EchoReading.Direction.Still
    val warm by animateFloatAsState(when (dir) { EchoReading.Direction.Approach -> 1f; EchoReading.Direction.Recede -> -1f; else -> 0f }, tween(300), label = "w")
    val t = if (on && motionOn()) rememberInfiniteTransition(label = "sonar").animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "t").value else 0f
    val base = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.surfaceContainerHigh
    Canvas(modifier) {
        drawRect(bg)
        val origin = Offset(size.width / 2, 0f)
        val reach = size.height * 1.1f
        val tint = when {
            warm > 0 -> androidx.compose.ui.graphics.lerp(base, ApproachColor, warm)
            warm < 0 -> androidx.compose.ui.graphics.lerp(base, RecedeColor, -warm)
            else -> base
        }
        val rings = 6
        for (i in 0 until rings) {
            // Approaching: rings bunch up; receding: they spread.
            val phase = ((i.toFloat() / rings) + t) % 1f
            val r = reach * phase * (1f - 0.25f * warm.coerceAtLeast(0f)) + reach * 0.15f * (-warm).coerceAtLeast(0f) * phase
            val alpha = if (on) (1f - phase) * (0.35f + 0.65f * motion) else 0.12f * (1f - i / rings.toFloat())
            drawCircle(tint.copy(alpha = alpha.coerceIn(0f, 1f)), r.coerceAtLeast(1f), origin, style = Stroke(width = (2f + 10f * motion) * density))
        }
        drawCircle(tint, 10.dp.toPx() * (1f + motion), origin)
    }
}
