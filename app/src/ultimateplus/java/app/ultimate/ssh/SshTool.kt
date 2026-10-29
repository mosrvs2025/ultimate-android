package app.ultimate.ssh

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.ultimate.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/** Registered reflectively from main so offline editions have neither this code nor MINA dependencies. */
class SshTool(context: Context) : Tool {
    private val manager = SshServerManager.get(context)
    override val id = "ssh"
    override val title = "SSH and SFTP"
    override val tagline = "Approved computers only"
    override val icon = Icons.Rounded.Terminal
    override val headline = MutableStateFlow("Off by default · port 8022")
    override val library = MutableStateFlow<List<LibraryItem>>(emptyList())
    override val acceptedMimePrefixes = emptyList<String>()
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = false
    override suspend fun refresh() { headline.value = if (manager.state.value.running) "SSH is on" else "SSH is off" }
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable override fun Screen(onExit: () -> Unit, launch: ToolLaunch) {
        val state by manager.state.collectAsState()
        val saved by manager.keys.collectAsState()
        var text by remember(saved) { mutableStateOf(saved) }
        var message by remember { mutableStateOf<String?>(null) }
        var addresses by remember { mutableStateOf("PHONE_IP") }
        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        val legacyStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        val scan = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            result.data?.getStringExtra("SCAN_RESULT")?.let { text = text.trimEnd() + "\n" + it }
        }
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                runCatching { manager.loadFingerprint() }.onFailure { message = it.message }
                addresses = runCatching {
                    java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()).flatMap {
                        java.util.Collections.list(it.inetAddresses)
                    }.filter { it is java.net.Inet4Address && it.isSiteLocalAddress }.mapNotNull { it.hostAddress }.distinct().joinToString(", ")
                }.getOrDefault("").ifEmpty { "PHONE_IP" }
            }
        }
        Scaffold(topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { TextButton(onClick = onExit) { Text("Back") } }) }) { padding ->
            Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (state.running) "On · ${state.clients} connected" else if (state.starting) "Starting…" else "Off")
                Text("SSH grants command execution with Ultimate's app permissions. SFTP opens shared storage, subject to Android's restrictions. Only enable this on a trusted network.")
                Text("Host fingerprint: ${state.fingerprint.ifEmpty { "Loading…" }}")
                Text("Phone addresses: $addresses")
                Text("Connect: ssh -p 8022 ultimate@PHONE_IP\nFiles: sftp -P 8022 ultimate@PHONE_IP\nCompare the host fingerprint before accepting it on your computer.")
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Approved OpenSSH public keys") },
                    enabled = !state.running && !state.starting, modifier = Modifier.fillMaxWidth(), minLines = 4)
                Text("Paste an authorized public key (never a private key), or scan a QR containing one.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !state.running && !state.starting, onClick = {
                        runCatching { manager.approveKeys(text) }.onSuccess { message = "Approved keys saved" }.onFailure { message = it.message }
                    }) { Text("Approve keys") }
                    OutlinedButton(enabled = !state.running && !state.starting, onClick = {
                        runCatching { scan.launch(Intent(manager.context, SshKeyScanActivity::class.java)) }.onFailure { message = it.message }
                    }) { Text("Scan QR image") }
                }
                OutlinedButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 30) manager.context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${manager.context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    else legacyStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }) { Text("Allow shared-storage access") }
                OutlinedButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else manager.context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, manager.context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Allow connection notification") }
                Button(enabled = !state.starting, onClick = {
                    runCatching { if (state.running) manager.stop() else manager.start() }.onFailure { message = it.message }
                }) { Text(if (state.running) "Turn off SSH" else "Turn on SSH") }
                if (state.clients > 0) OutlinedButton(onClick = manager::disconnectClients) { Text("Disconnect all clients") }
                message?.let { Text(it) }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("Password login is disabled. Shells currently use pipes; full PTY support can be supplied through ShellSession.Factory. No root access or port forwarding.")
            }
        }
    }
}
