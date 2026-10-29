package app.ultimate.recorder

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.core.RecentResults
import app.ultimate.core.ShellActions
import app.ultimate.core.ToolBackButton
import app.ultimate.core.formatDuration
import app.ultimate.core.pressScale

private val RED = Color(0xFFE53935)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecorderScreen(tool: RecorderTool, onExit: () -> Unit) {
    BackHandler(onBack = onExit)
    val m = tool.manager
    val state by m.state.collectAsStateWithLifecycle()
    val error by m.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var quality by rememberSaveable { mutableStateOf(RecordQuality.Voice) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) m.start(quality) else m.error.value = "Recorder needs the microphone. Allow it in Settings to record." }
    val withConsent = app.ultimate.core.rememberNotificationConsent()
    val recording = state as? RecorderManager.State.Recording

    Scaffold(topBar = { TopAppBar(title = { Text("Recorder") }, navigationIcon = { ToolBackButton(onExit) }, actions = { ShellActions() }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(16.dp))
            LiveWave(recording?.levels.orEmpty(), recording?.paused == true, Modifier.fillMaxWidth().height(140.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                formatDuration(recording?.elapsedMs ?: 0), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold,
                color = if (recording != null && !recording.paused) RED else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                when {
                    state is RecorderManager.State.Saving -> "Saving…"
                    recording?.paused == true -> "Paused"
                    recording != null -> "Recording · keeps going with the screen off"
                    else -> "Tap the button to start"
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.animation.AnimatedVisibility(recording != null, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                        FilledTonalIconButton(m::togglePause, Modifier.size(56.dp)) {
                            Icon(if (recording?.paused == true) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (recording?.paused == true) "Resume" else "Pause")
                        }
                    }
                }
                RecordButton(recording != null) {
                    if (recording != null) m.stop()
                    else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) withConsent { m.start(quality) }
                    else ask.launch(Manifest.permission.RECORD_AUDIO)
                }
                Spacer(Modifier.size(56.dp))
            }
            Spacer(Modifier.height(24.dp))
            AnimatedVisibility(recording == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        RecordQuality.entries.forEachIndexed { i, q ->
                            SegmentedButton(quality == q, { quality = q }, SegmentedButtonDefaults.itemShape(i, RecordQuality.entries.size)) { Text(q.label) }
                        }
                    }
                    Text(quality.blurb, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            error?.let { Text(it, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            Spacer(Modifier.height(20.dp))
            RecentResults(tool, "Recordings", onOpenOwn = {}, limit = 8)
            Text(
                "Tap a recording to play it, or send it to Video to audio to trim, clean up and convert, or to Studio as a soundtrack.",
                Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center,
            )
        }
    }
}

/** The big button: a red circle that morphs into a rounded square while recording, with a breathing halo. */
@Composable
private fun RecordButton(recording: Boolean, onClick: () -> Unit) {
    val corner by animateDpAsState(if (recording) 12.dp else 40.dp, spring(Spring.DampingRatioMediumBouncy), label = "corner")
    val inner by animateDpAsState(if (recording) 34.dp else 64.dp, spring(Spring.DampingRatioMediumBouncy), label = "inner")
    val halo by rememberInfiniteTransition(label = "halo").animateFloat(1f, 1.18f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "breath")
    val ring by animateColorAsState(if (recording) RED.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant, label = "ring")
    Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(if (recording) 80.dp * halo else 80.dp).clip(CircleShape).background(ring))
        Box(Modifier.size(80.dp).pressScale(0.9f).clip(CircleShape).background(MaterialTheme.colorScheme.surface).clickable(onClickLabel = if (recording) "Stop and save" else "Record", onClick = onClick), contentAlignment = Alignment.Center) {
            Box(Modifier.size(inner).clip(RoundedCornerShape(corner)).background(RED))
        }
    }
}

/** Scrolling live levels: newest on the right, mirrored around the middle like a voice memo. */
@Composable
private fun LiveWave(levels: List<Float>, paused: Boolean, modifier: Modifier) {
    val color = if (paused) MaterialTheme.colorScheme.outline else RED
    val idle = MaterialTheme.colorScheme.surfaceVariant
    val pulse by rememberInfiniteTransition(label = "idle").animateFloat(0f, 1f, infiniteRepeatable(tween(2400), RepeatMode.Restart), label = "p")
    Canvas(modifier) {
        val bars = 80
        val slot = size.width / bars
        val w = slot * 0.6f
        val shown = levels.takeLast(bars)
        for (i in 0 until bars) {
            val v = shown.getOrNull(i - (bars - shown.size))
            val h = if (v == null) (0.03f + 0.03f * kotlin.math.sin((i / 6f + pulse * 6.28f)).let { it * it }) * size.height
            else (v.coerceAtLeast(0.03f) * size.height)
            drawRoundRect(if (v == null) idle else color, Offset(i * slot + (slot - w) / 2, (size.height - h) / 2), Size(w, h), CornerRadius(w / 2))
        }
    }
}
