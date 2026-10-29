package app.ultimate.send

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.core.LibraryItem
import app.ultimate.core.ShellActions
import app.ultimate.core.Tool
import app.ultimate.core.ToolBackButton
import app.ultimate.core.ToolLaunch
import app.ultimate.core.copyText
import app.ultimate.core.formatBytes
import app.ultimate.core.relativeTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

/** Move files between this phone and any computer on the same Wi-Fi, through the computer's browser. */
class SendTool(private val server: SendServer) : Tool {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    override val id = "send"
    override val title = "Send to computer"
    override val tagline = "Files to and from any computer on your Wi-Fi"
    override val icon = Icons.Rounded.Computer
    override val acceptedMimePrefixes = listOf("video/", "image/", "audio/", "application/", "text/")
    override val headline: StateFlow<String> = server.state.map { if (it.running) "On · open the address on your computer" else "No cable, no cloud" }
        .stateIn(scope, SharingStarted.Eagerly, "No cable, no cloud")
    override val library: StateFlow<List<LibraryItem>> = MutableStateFlow<List<LibraryItem>>(emptyList()).asStateFlow()
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = true
    override suspend fun refresh() = Unit
    @Composable override fun Screen(onExit: () -> Unit, launch: ToolLaunch) = SendScreen(server, onExit, launch)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SendScreen(server: SendServer, onExit: () -> Unit, launch: ToolLaunch) {
    BackHandler(onBack = onExit)
    val state by server.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var front by remember { mutableStateOf(false) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) server.camera(true) }
    if (state.camera && state.running) CompanionCamera(server, front)
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) { server.camera(false); server.mic(false) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) server.mic(true) }
    state.playRequest?.let { request ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = server::dismissPlay,
            title = { Text("Play from your computer?") },
            text = { Text(request.name + " has been saved to Downloads/Ultimate.") },
            confirmButton = { TextButton({
                server.dismissPlay()
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse(request.uri), request.mime).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { context.startActivity(intent) }.onFailure { android.widget.Toast.makeText(context, "Saved to Downloads/Ultimate. Install a player to open this format.", android.widget.Toast.LENGTH_LONG).show() }
            }) { Text("Play") } },
            dismissButton = { TextButton(server::dismissPlay) { Text("Keep file") } },
        )
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { server.offer(it) }
    LaunchedEffect(launch) { if (!launch.consumed) { launch.consumed = true; if (launch.uris.isNotEmpty()) { server.offer(launch.uris); server.start() } } }
    // With background sharing off, leaving the screen turns sharing off, so nothing listens on the network unnoticed.
    DisposableEffect(Unit) { onDispose { if (!server.keepInBackground) server.stop() } }
    var renaming by remember { mutableStateOf(false) }
    if (renaming) {
        var name by remember { mutableStateOf(server.codeName) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renaming = false }, title = { Text("Name this address") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("e.g. maurice-phone") })
                Text("Letters, numbers and dashes. Anyone on your Wi-Fi who knows it can open the page, so pick something not easy to guess. Remembered computers are forgotten.", style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton({ if (server.rename(name) != null) renaming = false }, enabled = SendHttp.cleanCode(name) != null) { Text("Save") } },
            dismissButton = { Row { TextButton({ server.rename(null); renaming = false }) { Text("Random") }; TextButton({ renaming = false }) { Text("Cancel") } } },
        )
    }
    val pulse by rememberInfiniteTransition(label = "on").animateFloat(0.4f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dot")

    Scaffold(topBar = { TopAppBar(title = { Text("Send to computer") }, navigationIcon = { ToolBackButton(onExit) }, actions = { ShellActions() }) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = if (state.running) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(28.dp)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        AnimatedContent(state.address, label = "address") { address ->
                            if (address == null) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Rounded.Computer, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                                Text("Your computer's browser becomes the cable", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text("Both need to be on the same Wi-Fi. Nothing goes through the internet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Button(server::start, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) { Text("Turn on") }
                            } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF2E9E5B).copy(alpha = pulse)))
                                    Spacer(Modifier.width(8.dp)); Text("On. Open this on your computer:", style = MaterialTheme.typography.bodyMedium)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(address.removePrefix("http://"), Modifier.clickable { copyText(context, "Address", address) }, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                        Text("Tap to copy · bookmark it, it stays the same", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                    QrCode(address, Modifier.size(104.dp))
                                }
                                Text(
                                    if (server.rememberedCount() > 0) "${server.rememberedCount()} computer${if (server.rememberedCount() == 1) " is" else "s are"} remembered: there, just ${address.removePrefix("http://").substringBeforeLast('/')} opens it."
                                    else "After the first visit, that computer is remembered and the address without the name works too.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Row { TextButton({ renaming = true }) { Text("Change name") }; TextButton(server::stop) { Text("Turn off") } }
                            }
                        }
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            item { ComputerScreen(server) }
            if (state.running) item { SecureCard(server, state.secureAddress) }
            item { SharingOptions(server) }
            item {
                if (state.running) Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Live camera and microphone", fontWeight = FontWeight.Bold)
                        Text("Watch and listen from your computer's browser. Both stay under your control here and stop when you leave Ultimate.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button({ if (state.camera) server.camera(false) else cameraPermission.launch(android.Manifest.permission.CAMERA) }) { Text(if (state.camera) "Stop camera" else "Camera") }
                            if (state.camera) TextButton({ front = !front }) { Text("Flip") }
                            androidx.compose.material3.FilledTonalButton({ if (state.mic) server.mic(false) else micPermission.launch(android.Manifest.permission.RECORD_AUDIO) }) { Text(if (state.mic) "Stop mic" else "Microphone") }
                        }
                        if (state.mic) Text("● Live: anyone with your dashboard open can hear this phone.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Offered to the computer", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedButton({ picker.launch(arrayOf("*/*")) }) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(4.dp)); Text("Add files") }
                }
            }
            if (state.offered.isEmpty()) item { Text("Nothing yet. Add files, then download them from the browser.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(state.offered, key = { it.uri.toString() }) { f ->
                Row(Modifier.fillMaxWidth().animateItem().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                        Text(f.name, fontWeight = FontWeight.Medium, maxLines = 1)
                        Text(formatBytes(f.bytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton({ server.unoffer(f.uri) }) { Icon(Icons.Rounded.Close, "Stop offering") }
                }
            }
            if (state.received.isNotEmpty()) {
                item { Text("Received from the computer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                items(state.received, key = { it.at.toString() + it.name }) { r ->
                    Row(Modifier.animateItem(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Download, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp))
                        Column { Text(r.name, fontWeight = FontWeight.Medium); Text("${formatBytes(r.bytes)} · ${relativeTime(r.at)} · Downloads/Ultimate", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
    }
}

/** What the computer may see and whether sharing keeps going in the background. */
@Composable
private fun SharingOptions(server: SendServer) {
    val context = LocalContext.current
    var background by remember { mutableStateOf(server.keepInBackground) }
    var notes by remember { mutableStateOf(server.api.shareNotifications) }
    var files by remember { mutableStateOf(server.api.shareFiles) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("On the computer's dashboard", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("Library, clipboard both ways and phone status are always there.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Option("Keep sharing in the background", "Stays on with a notification; turns off after 2 hours with no visits", background) { background = it; server.keepInBackground = it }
            Option("Browse the phone's files", if (app.ultimate.files.hasAllFiles(context)) "Every folder, download and upload anywhere" else "Needs all files access (open Files once)", files) { files = it; server.api.shareFiles = it }
            Option("Show notifications", "Your notification history, searchable on the big screen", notes) { notes = it; server.api.shareNotifications = it }
            Text("The page is plain HTTP on your Wi-Fi: use it only on networks you trust.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun Option(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    androidx.compose.material3.Switch(on, onChange)
}

/** A QR code of [text], drawn with plain squares so it scales sharply. */
@Composable
fun QrCode(text: String, modifier: Modifier) {
    val matrix = remember(text) { runCatching { com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0, mapOf(com.google.zxing.EncodeHintType.MARGIN to 1)) }.getOrNull() } ?: return
    androidx.compose.foundation.Canvas(modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(6.dp)) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) if (matrix[x, y]) drawRect(Color.Black, androidx.compose.ui.geometry.Offset(x * cell, y * cell), androidx.compose.ui.geometry.Size(cell + 0.5f, cell + 0.5f))
    }
}

/** The computer's screen or camera, live; tap for full screen with pinch to zoom. */
@Composable
private fun ComputerScreen(server: SendServer) {
    val shot by server.actions.screen.collectAsStateWithLifecycle()
    var full by remember { mutableStateOf(false) }
    val s = shot ?: run { full = false; return }
    val bitmap = remember(s.at) { android.graphics.BitmapFactory.decodeByteArray(s.frame, 0, s.frame.size)?.asImageBitmap() }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFE53935))); Spacer(Modifier.width(8.dp))
                Text("${s.from} is sharing", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                TextButton({ full = true }) { Text("Full screen") }
            }
            bitmap?.let { androidx.compose.foundation.Image(it, "Computer screen", Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { full = true }, contentScale = androidx.compose.ui.layout.ContentScale.FillWidth) }
        }
    }
    if (full && bitmap != null) androidx.compose.ui.window.Dialog({ full = false }, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
            detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 6f); offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan }
        }) {
            androidx.compose.foundation.Image(bitmap, "Computer screen", Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }, contentScale = androidx.compose.ui.layout.ContentScale.Fit)
            TextButton({ full = false }, Modifier.align(Alignment.TopEnd).padding(16.dp)) { Text("Close", color = Color.White) }
        }
    }
}

/** Secure mode: the https address for screen sharing and desktop notifications, and the certificate to expect. */
@Composable
private fun SecureCard(server: SendServer, secure: String?) {
    val context = LocalContext.current
    val ringing = server.actions.ringing
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Secure mode", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (secure == null) Text("Starting…", style = MaterialTheme.typography.bodySmall)
            else {
                Text(secure.removePrefix("https://"), Modifier.clickable { copyText(context, "Secure address", secure) }, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                Text("Lets your computer share its screen and camera to this phone and show phone notifications on the desktop. The browser warns once because the phone made its own certificate: choose Advanced, then Proceed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                server.secureFingerprint()?.let { Text("Certificate starts $it", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.outline) }
            }
            if (ringing) TextButton(server.actions::stopRing) { Text("Stop ringing") }
        }
    }
}
