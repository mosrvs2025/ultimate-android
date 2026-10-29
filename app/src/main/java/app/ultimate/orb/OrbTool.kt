package app.ultimate.orb

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BubbleChart
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.ultimate.core.LibraryItem
import app.ultimate.core.Tool
import app.ultimate.core.ToolLaunch
import app.ultimate.core.BackBar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class OrbTool : Tool {
    override val id = "orb"
    override val title = "Floating orb"
    override val tagline = "Ultimate on top of every app"
    override val icon = Icons.Rounded.BubbleChart
    override val acceptedMimePrefixes = emptyList<String>()
    override val headline: StateFlow<String> = MutableStateFlow("Tools over any app").asStateFlow()
    override val library: StateFlow<List<LibraryItem>> = MutableStateFlow<List<LibraryItem>>(emptyList()).asStateFlow()
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = true
    override suspend fun refresh() = Unit
    @Composable override fun Screen(onExit: () -> Unit, launch: ToolLaunch) = OrbScreen(onExit)
}

@Composable
private fun OrbScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var on by remember { mutableStateOf(OrbService.on(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        allowed = Settings.canDrawOverlays(context); on = OrbService.on(context)
        // Coming back from the permission screen with it granted finishes what the switch started.
        if (allowed && on) OrbService.resume(context)
    }
    Scaffold(topBar = { BackBar(onExit, "Floating orb") }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow), contentAlignment = Alignment.Center) {
                val t = rememberInfiniteTransition(label = "demo")
                val y by t.animateFloat(-10f, 10f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "y")
                Box(Modifier.graphicsLayer { translationY = y * density }.size(72.dp).shadow(14.dp, CircleShape).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color(0xFF8E7CFF), Color(0xFF4FC3F7)))), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(30.dp), tint = Color.White)
                }
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Show the orb", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(if (allowed) "Floats over every app" else "Needs “Display over other apps”", style = MaterialTheme.typography.bodySmall)
                }
                Switch(on && allowed, { want ->
                    on = want
                    if (want && !Settings.canDrawOverlays(context)) {
                        context.getSharedPreferences("orb", android.content.Context.MODE_PRIVATE).edit().putBoolean("on", true).apply()
                        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                    } else OrbService.set(context, want)
                })
            }
            if (!allowed) Button({ context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }) { Text("Allow display over other apps") }
            Text("How it works", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            listOf(
                "Tap" to "Your tools bloom out around it. The ones you use most come first.",
                "Drag" to "Put it anywhere; it springs to the nearest edge.",
                "Drag to ✕" to "Puts it away. Turn it back on here.",
                "Drop text on it" to "Keeps it in your clipboard history and copies it.",
                "While working" to "It breathes, with a ring showing progress. A card peeks out when the job is done; tap the card to open it.",
            ).forEach { (k, v) ->
                Row { Text(k, Modifier.weight(0.35f), fontWeight = FontWeight.SemiBold); Text(v, Modifier.weight(0.65f), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
