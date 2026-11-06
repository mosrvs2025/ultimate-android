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
import androidx.compose.foundation.layout.size
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
import kotlinx.coroutines.launch
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

        val version by WatchLink.watchVersion.collectAsStateWithLifecycle()
        LaunchedEffect(watch) { if (watch != null) WatchUpdater.ask(context) }
        // Setup first until the watch app answers; after that, straight to the gestures.
        var tab by remember { mutableStateOf(-1) }
        val shownTab = if (tab >= 0) tab else if (watch == null || version == null) 3 else 0

        Scaffold(topBar = { TopAppBar(title = { Text("Watch") }, navigationIcon = { IconButton(onExit) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }) }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                // Always in view: the link, the switch, and what the watch just did.
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh).padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (watch != null) androidx.compose.ui.graphics.Color(0xFF3DDC97) else MaterialTheme.colorScheme.outline))
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(watch?.let { "Linked to $it" } ?: if (checked) "No watch linked" else "Looking for your watch…", fontWeight = FontWeight.SemiBold)
                            Text(if (enabled) "Watch controls the phone" else "Watching only: nothing happens on the phone", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(enabled, { enabled = it; WatchLink.setEnabled(context, it) })
                    }
                    val now = System.currentTimeMillis()
                    val shown = last?.takeIf { now - it.second < 2_000 }
                    val sc by animateFloatAsState(if (shown != null) 1f else 0.85f, label = "watch")
                    Text(shown?.let { WatchMessages.label(it.first) } ?: if (watch != null) "Try a flick" else "",
                        Modifier.padding(top = 8.dp).graphicsLayer { scaleX = sc; scaleY = sc }, fontSize = if (shown != null) 24.sp else 15.sp, fontWeight = FontWeight.Bold)
                }
                androidx.compose.material3.PrimaryTabRow(shownTab) {
                    listOf("Gestures", "Live", "Calibrate", "Setup").forEachIndexed { i, label ->
                        androidx.compose.material3.Tab(shownTab == i, { tab = i }, text = { Text(label) })
                    }
                }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (shownTab) {
                        0 -> {
                            Text("What each wrist gesture does (tap to change)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            WatchMessages.GESTURES.forEach { (kind, label) ->
                                val act = chosen[kind] ?: "none"
                                val name = AirActions.CHOICES.firstOrNull { it.first == act }?.second ?: "Nothing"
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).clickable {
                                    val next = AirActions.CHOICES[(AirActions.CHOICES.indexOfFirst { it.first == act } + 1) % AirActions.CHOICES.size].first
                                    chosen[kind] = next; WatchLink.setAction(context, kind, next)
                                }.padding(horizontal = 14.dp, vertical = 12.dp)) {
                                    Text(label, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                                    Text(name, color = if (act == "none") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                                }
                            }
                            Text("Gestures that misfire? Calibrate them to your wrist in the Calibrate tab.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        1 -> LiveSensorsCard()
                        2 -> { CalibrateCard(); LiveSensorsCard() }
                        else -> { UpdateCard(version); InstallCard() }
                    }
                }
            }
        }
    }
}

/** Updates from this phone, over the watch link: no wireless debugging once the watch app is 1.2.0 or newer. */
@Composable
private fun UpdateCard(version: Pair<Long, String>?) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val bundled = remember { WatchUpdater.bundled(context) }
    val status by WatchLink.updateStatus.collectAsStateWithLifecycle()
    var sending by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Watch app", fontWeight = FontWeight.SemiBold)
        Text("On the watch: ${version?.second ?: "not answering (open Ultimate+ on the watch)"} · In this phone: ${bundled?.second ?: "not included in this build"}", style = MaterialTheme.typography.bodySmall)
        val canUpdate = version != null && bundled != null && bundled.first > version.first
        when {
            version != null && version.first < 3 -> Text("This watch version can't take updates from the phone yet. Update it once with \"Install on my watch\" below; after that, updates come from here.", style = MaterialTheme.typography.bodySmall)
            canUpdate -> androidx.compose.material3.Button({
                sending = true; problem = null; WatchLink.updateStatus.value = null
                scope.launch { problem = WatchUpdater.send(context); sending = false }
            }, enabled = !sending) { Text(if (sending) "Sending…" else "Send update to watch") }
            version != null && bundled != null -> Text("Up to date ✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        status?.let { st ->
            Text(when {
                st == "receiving" -> "The watch is receiving it…"
                st == "installing" -> "Installing…"
                st == "confirm" -> "Tap Install on the watch."
                st == "installed" -> "Installed on the watch ✓"
                st == "needs-permission" -> "On the watch, allow Ultimate+ to install apps, then tap Send update again."
                st.startsWith("unsupported:") -> st.removePrefix("unsupported:").replaceFirstChar { it.uppercase() } + "."
                st.startsWith("failed:") -> "It didn't install: " + st.removePrefix("failed:")
                else -> st
            }, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * "Install on my watch", one step at a time: find the watch (or type the address it shows), type its code, install.
 * Some routers block the network announcements the automatic search relies on, so typing it in is always offered.
 */
@Composable
private fun InstallCard() {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val installer = remember { WatchInstaller(context) }
    val prefs = remember { context.getSharedPreferences("watch_link", android.content.Context.MODE_PRIVATE) }
    var stage by remember { mutableStateOf("idle") } // idle, failed, looking, code, port, working, done
    var host by remember { mutableStateOf(prefs.getString("adb_host", null)) }
    var savedPort by remember { mutableStateOf(prefs.getInt("adb_port", 0).takeIf { it > 0 }) }
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(0f) }
    var showComputer by remember { mutableStateOf(false) }
    val digits = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)

    fun install(h: String, port: Int?) {
        stage = "working"; message = "Installing on your watch…"; progress = 0f
        scope.launch {
            when (val r = installer.install(h, port) { progress = it }) {
                is WatchInstaller.Outcome.Done -> { port?.let { prefs.edit().putInt("adb_port", it).apply(); savedPort = it }; stage = "done"; message = r.message }
                is WatchInstaller.Outcome.Failed -> if (r.why == WatchInstaller.NEED_PORT) { portText = ""; stage = "port" } else { stage = "failed"; message = r.why }
            }
        }
    }
    fun look() {
        stage = "looking"; message = ""
        scope.launch {
            val found = installer.findPairing(10_000)
            address = found?.let { "${it.first}:${it.second}" } ?: WatchInstaller.networkPrefix()
            code = ""; stage = "code"
            if (found == null) message = "Couldn't find the watch by itself (some routers block that). Type what the watch shows instead."
        }
    }

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (stage == "done") MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("3.  Install Ultimate+ on the watch" + if (stage == "done") "  ✓" else "", fontWeight = FontWeight.SemiBold)
        when (stage) {
            "idle", "failed" -> {
                if (stage == "failed") Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text("Right from this phone, over Wi-Fi. On the watch, open Settings → Developer options → Wireless debugging, turn it on, and tap \"Pair new device\". Then:",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.Button({ look() }) { Text("Install on my watch") }
                    // Paired before: the watch remembers this phone, so updating skips the code.
                    host?.let { h -> androidx.compose.material3.OutlinedButton({ install(h, savedPort) }) { Text("Update it") } }
                }
            }
            "looking" -> {
                Text("Looking for your watch on this Wi-Fi… Keep \"Pair new device\" open on the watch.", style = MaterialTheme.typography.bodySmall)
                androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                androidx.compose.material3.TextButton({ address = WatchInstaller.networkPrefix(); code = ""; message = ""; stage = "code" }) { Text("Type it in instead") }
            }
            "code" -> {
                if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Text("From the watch's \"Pair with device\" screen:", style = MaterialTheme.typography.bodySmall)
                androidx.compose.material3.OutlinedTextField(address, { address = it.filter { c -> c.isDigit() || c == '.' || c == ':' } }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("IP address & Port") }, placeholder = { Text("192.168.1.239:37581") },
                    isError = address.isNotEmpty() && WatchInstaller.parseAddress(address) == null,
                    // Say what's wrong, so a missing piece is obvious (the button stays off until it's right).
                    supportingText = { WatchInstaller.addressProblem(address)?.let { Text(it) } },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                androidx.compose.material3.OutlinedTextField(code, { v -> code = v.filter(Char::isDigit).take(6) }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Wi-Fi pairing code") }, keyboardOptions = digits)
                val target = WatchInstaller.parseAddress(address)
                androidx.compose.material3.Button({
                    val (h, p) = target ?: return@Button
                    stage = "working"; message = "Pairing…"
                    scope.launch {
                        when (val r = installer.pair(h, p, code)) {
                            is WatchInstaller.Outcome.Done -> { prefs.edit().putString("adb_host", h).apply(); host = h; install(h, null) }
                            is WatchInstaller.Outcome.Failed -> { stage = "failed"; message = r.why }
                        }
                    }
                }, enabled = code.length == 6 && target != null) { Text("Pair and install") }
            }
            "port" -> {
                Text("Paired. One more number: go back one screen on the watch to Wireless debugging, and type the port under \"IP address & Port\" (the number after the colon; it isn't the pairing one).",
                    style = MaterialTheme.typography.bodySmall)
                androidx.compose.material3.OutlinedTextField(portText, { portText = it.filter(Char::isDigit).take(5) }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Port") }, keyboardOptions = digits)
                androidx.compose.material3.Button({ host?.let { h -> install(h, portText.toInt()) } }, enabled = portText.toIntOrNull()?.let { it in 1..65535 } == true) { Text("Install") }
            }
            "working" -> {
                Text(message, style = MaterialTheme.typography.bodySmall)
                if (progress > 0f) androidx.compose.material3.LinearProgressIndicator({ progress }, Modifier.fillMaxWidth()) else androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            "done" -> {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                Text("You can turn Wireless debugging off on the watch now. The watch and phone keep talking over Bluetooth.", style = MaterialTheme.typography.bodySmall)
            }
        }
        androidx.compose.material3.TextButton({ showComputer = !showComputer }) { Text(if (showComputer) "Hide the computer method" else "Or install from a computer") }
        if (showComputer) Text("""
            1. On the watch: Settings → Developer options → turn on "ADB debugging" and "Wireless debugging"; "Pair new device" shows an address and a code.
            2. On a computer with Android's platform-tools: adb pair <address> <code>, then adb connect <address under Wireless debugging>, then adb install Ultimate-Plus-Watch-<version>.apk (from the release page).
        """.trimIndent(), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Step(n: Int, title: String, text: String, done: Boolean) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (done) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh).padding(14.dp)) {
        Text("$n.  $title" + if (done) "  ✓" else "", fontWeight = FontWeight.SemiBold)
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    }
}

