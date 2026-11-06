package app.ultimate.watch

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.UltimateApp
import app.ultimate.air.AirActions
import app.ultimate.air.WatchMessages
import app.ultimate.core.LibraryItem
import app.ultimate.core.Tool
import app.ultimate.core.ToolLaunch
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Watch: control the phone from a Wear OS watch (such as a Galaxy Watch) with wrist gestures and buttons, from further
 * away than the camera or Echo can reach. The watch app is a separate install; this screen shows the link, what each
 * gesture does, and how to install it.
 */
class WatchTool(private val app: UltimateApp) : Tool {
    override val id = "watch"
    override val title = "Watch"
    override val tagline = "Control the phone from your watch: flick, twist and shake, or tap its buttons"
    override val icon = Icons.Rounded.Watch
    override val acceptedMimePrefixes = emptyList<String>()
    override val headline: StateFlow<String> = MutableStateFlow("Your wrist as a remote")
    override val library: StateFlow<List<LibraryItem>> = MutableStateFlow(emptyList())
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = false
    override suspend fun refresh() = Unit

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Screen(onExit: () -> Unit, launch: ToolLaunch) {
        BackHandler(onBack = onExit)
        val context = LocalContext.current
        var enabled by remember { mutableStateOf(WatchLink.enabled(context)) }
        var watch by remember { mutableStateOf<String?>(null) }
        var checked by remember { mutableStateOf(false) }
        val last by WatchLink.last.collectAsStateWithLifecycle()
        val chosen = remember { mutableStateMapOf<String, String>().apply { WatchMessages.GESTURES.forEach { (k, _) -> put(k, WatchLink.action(context, k)) } } }
        LaunchedEffect(Unit) {
            while (true) {
                Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { watch = it.firstOrNull()?.displayName; checked = true }.addOnFailureListener { watch = null; checked = true }
                delay(5_000)
            }
        }

        Scaffold(topBar = { TopAppBar(title = { Text("Watch") }, navigationIcon = { IconButton(onExit) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }) }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 1. The link.
                Step(1, if (watch != null) "Linked to $watch" else if (checked) "No watch linked yet" else "Looking for your watch…",
                    if (watch != null) "Gestures reach the phone over Bluetooth, or over Wi-Fi when you're further away." else "Pair the watch with this phone in the Galaxy Wearable app first, then install Ultimate+ on the watch (step 3).",
                    done = watch != null)
                // 2. Permission to act.
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { enabled = !enabled; WatchLink.setEnabled(context, enabled) }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("2.  Watch controls the phone", fontWeight = FontWeight.SemiBold)
                        Text(if (enabled) "On: the watch's gestures and buttons act on this phone, and gestures can start Rules." else "Off: you'll see what the watch sends, but nothing happens.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(enabled, { enabled = it; WatchLink.setEnabled(context, it) })
                }
                // What arrives, live.
                Box(Modifier.fillMaxWidth().aspectRatio(3f).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                    val now = System.currentTimeMillis()
                    val shown = last?.takeIf { now - it.second < 2_000 }
                    val sc by animateFloatAsState(if (shown != null) 1f else 0.85f, label = "watch")
                    Text(shown?.let { WatchMessages.label(it.first) + if (!enabled) " (watching only)" else "" } ?: "Try a flick on your watch",
                        Modifier.graphicsLayer { scaleX = sc; scaleY = sc }, fontSize = if (shown != null) 26.sp else 16.sp, fontWeight = FontWeight.Bold)
                }
                Text("What each wrist gesture does (tap to change)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                WatchMessages.GESTURES.forEach { (kind, label) ->
                    val act = chosen[kind] ?: "none"
                    val name = AirActions.CHOICES.firstOrNull { it.first == act }?.second ?: "Nothing"
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).clickable {
                        val next = AirActions.CHOICES[(AirActions.CHOICES.indexOfFirst { it.first == act } + 1) % AirActions.CHOICES.size].first
                        chosen[kind] = next; WatchLink.setAction(context, kind, next)
                    }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(label, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        Text(name, color = if (act == "none") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                    }
                }
                // 3. Installing the watch app.
                Step(3, "Install Ultimate+ on the watch", """
                    It isn't on the Play Store, so it's installed once from a computer:
                    1. On the watch: Settings → About watch → Software information → tap "Software version" 5 times to turn on Developer options.
                    2. Settings → Developer options: turn on "ADB debugging" and "Wireless debugging", then "Pair new device" shows an address and a code.
                    3. On a computer with Android's platform-tools: adb pair <address> <code>, then adb connect <address shown under Wireless debugging>, then adb install Ultimate-Plus-Watch.apk.
                    Then open Ultimate+ on the watch, turn on "Wrist gestures", and flick.
                """.trimIndent(), done = false)
            }
        }
    }
}

@Composable
private fun Step(n: Int, title: String, text: String, done: Boolean) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (done) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh).padding(14.dp)) {
        Text("$n.  $title" + if (done) "  ✓" else "", fontWeight = FontWeight.SemiBold)
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    }
}

