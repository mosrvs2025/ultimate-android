package app.ultimate.sshclient

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.UltimateApp
import app.ultimate.core.LibraryItem
import app.ultimate.core.ShellActions
import app.ultimate.core.Tool
import app.ultimate.core.ToolBackButton
import app.ultimate.core.ToolLaunch
import app.ultimate.core.pressScale
import app.ultimate.core.relativeTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/** Registered reflectively from main so offline editions have neither this code nor the SSH library. */
class HostsTool(context: Context) : Tool {
    private val app = context.applicationContext as UltimateApp
    val vault = VaultStore(File(context.filesDir, "ssh-client"), KeystoreSealer())
    val connector = Connector(app, vault)
    override val id = "hosts"
    override val title = "SSH hosts"
    override val tagline = "Connect to your servers"
    override val icon = Icons.Rounded.Dns
    private val _headline = MutableStateFlow(headlineText())
    override val headline: StateFlow<String> = _headline.asStateFlow()
    override val library: StateFlow<List<LibraryItem>> = MutableStateFlow<List<LibraryItem>>(emptyList()).asStateFlow()
    override val acceptedMimePrefixes = emptyList<String>()
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = false
    override suspend fun refresh() { _headline.value = headlineText() }
    private fun headlineText() = vault.hosts.value.size.let { if (it == 0) "Add your servers" else "$it host${if (it == 1) "" else "s"}" }
    @Composable override fun Screen(onExit: () -> Unit, launch: ToolLaunch) = HostsScreen(app, this, onExit)
}

private fun kindIcon(k: HostKind): ImageVector = when (k) {
    HostKind.Linux -> Icons.Rounded.Dns; HostKind.Mac -> Icons.Rounded.Laptop; HostKind.Windows -> Icons.Rounded.DesktopWindows
    HostKind.Pi -> Icons.Rounded.Memory; HostKind.Router -> Icons.Rounded.Router; HostKind.Other -> Icons.Rounded.Computer
}

private fun kindColor(k: HostKind): Color = when (k) {
    HostKind.Linux -> Color(0xFFE95420); HostKind.Mac -> Color(0xFF607D8B); HostKind.Windows -> Color(0xFF1E88E5)
    HostKind.Pi -> Color(0xFFD81B60); HostKind.Router -> Color(0xFF00897B); HostKind.Other -> Color(0xFF7E57C2)
}

private fun reachable(address: String, port: Int): Boolean = runCatching { Socket().use { it.connect(InetSocketAddress(address, port), 1200); true } }.getOrDefault(false)

private fun copy(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostsScreen(app: UltimateApp, tool: HostsTool, onExit: () -> Unit) {
    val vault = tool.vault
    val context = LocalContext.current
    val hosts by vault.hosts.collectAsStateWithLifecycle()
    val identities by vault.identities.collectAsStateWithLifecycle()
    val snippets by vault.snippets.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editHost by remember { mutableStateOf<Host?>(null) }
    var editSnippet by remember { mutableStateOf<Snippet?>(null) }
    var generate by remember { mutableStateOf(false) }
    var import by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf<Identity?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("SSH hosts") }, navigationIcon = { ToolBackButton(onExit) }, actions = { ShellActions() }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    when (tab) {
                        0 -> editHost = Host(vault.newHostId(), "", "", user = "")
                        1 -> editSnippet = Snippet(vault.newHostId(), "", "", "")
                        else -> generate = true
                    }
                },
                icon = { Icon(Icons.Rounded.Add, null) }, text = { Text(listOf("Add host", "Add snippet", "New key")[tab]) },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            TabRow(selectedTabIndex = tab) { listOf("Hosts", "Snippets", "Keys").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) } }
            when (tab) {
                0 -> HostsTab(tool, hosts, onEdit = { editHost = it }, onAdd = { editHost = Host(vault.newHostId(), "", "", user = "") })
                1 -> SnippetsTab(app, snippets, onEdit = { editSnippet = it })
                else -> KeysTab(identities, onGenerate = { generate = true }, onImport = { import = true }, onInstall = { installing = it }, onDelete = { vault.deleteIdentity(it.id) })
            }
        }
    }

    editHost?.let { h ->
        HostEditor(h, isNew = hosts.none { it.id == h.id }, identities = identities, groups = hosts.map { it.group }.filter { it.isNotBlank() }.distinct(), hasPassword = vault.hasPassword(h.id), onDismiss = { editHost = null },
            onSave = { saved, pw, connect ->
                vault.saveHost(saved)
                if (saved.auth == Auth.Password && pw != null) vault.savePassword(saved.id, pw) else if (saved.auth != Auth.Password) vault.forgetPassword(saved.id)
                editHost = null
                if (connect) tool.connector.connect(saved)
            })
    }
    editSnippet?.let { s -> SnippetEditor(s, isNew = snippets.none { it.id == s.id }, folders = snippets.map { it.folder }.filter { it.isNotBlank() }.distinct(), onDismiss = { editSnippet = null }, onSave = { vault.saveSnippet(it); editSnippet = null }, onDelete = { vault.deleteSnippet(it.id); editSnippet = null }) }
    if (generate) NameDialog("New key", "Name it after the phone or the job, e.g. “Pixel”.", onDismiss = { generate = false }) { name ->
        generate = false
        Thread { runCatching { vault.generateIdentity(name) } }.start()
        Toast.makeText(context, "Key created. Copy its public key to a server, or install it from here.", Toast.LENGTH_LONG).show()
    }
    if (import) ImportDialog(onDismiss = { import = false }) { name, text -> val ok = vault.importIdentity(name, text, null) != null; if (ok) import = false; ok }
    installing?.let { id -> InstallDialog(tool, id, hosts, onDismiss = { installing = null }) }
}

@Composable
private fun HostsTab(tool: HostsTool, hosts: List<Host>, onEdit: (Host) -> Unit, onAdd: () -> Unit) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var folded by remember { mutableStateOf(setOf<String>()) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<Host?>(null) }
    var alive by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    LaunchedEffect(hosts.map { Triple(it.id, it.address, it.port) }) {
        while (true) {
            alive = hosts.take(40).map { h -> async(Dispatchers.IO) { h.id to reachable(h.address, h.port) } }.awaitAll().toMap()
            delay(60_000)
        }
    }
    val results = HostRules.search(hosts, query)
    val target = HostRules.parseTarget(query)?.takeIf { t -> query.isNotBlank() && hosts.none { it.address.equals(t.host, true) && (t.port ?: 22) == it.port && (t.user == null || t.user == it.user) } && (query.contains('@') || query.contains('.') || query.contains(':') || results.isEmpty()) }

    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(28.dp),
                placeholder = { Text(if (hosts.isEmpty()) "user@host to connect right now" else "Search, or type user@host:port") },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, "Clear") } })
        }
        target?.let { t ->
            item("quick") {
                val h = HostRules.fromTarget(t, tool.vault.newHostId(), defaultUser = t.user ?: "root")
                Row(Modifier.fillMaxWidth().pressScale(0.98f).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.primaryContainer).clickable { tool.connector.connect(h); query = "" }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Bolt, null, tint = MaterialTheme.colorScheme.onPrimaryContainer); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Connect to ${HostRules.label(h)}", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("Once, without saving", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    TextButton({ onEdit(h); query = "" }) { Text("Save") }
                }
            }
        }
        if (hosts.isEmpty() && target == null) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.Dns, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Your servers, one tap away", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Save a computer, server or Raspberry Pi once. Then tap it to open a terminal on it. Keys and passwords stay locked on this phone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onAdd) { Text("Add a host") }
            }
        } else if (results.isEmpty() && target == null && hosts.isNotEmpty()) item { Text("No host matches “$query”.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
        HostRules.grouped(results).forEach { (group, list) ->
            val name = group.ifEmpty { if (results.any { it.group.isNotBlank() }) "Other" else "" }
            if (name.isNotEmpty()) item("g-$group") {
                val isFolded = group in folded
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { folded = if (isFolded) folded - group else folded + group }.padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    Text("  ${list.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Rounded.ExpandMore, null, Modifier.size(20.dp).rotate(if (isFolded) -90f else 0f), tint = MaterialTheme.colorScheme.outline)
                }
            }
            if (group !in folded) items(list, key = { it.id }) { h ->
                HostRow(h, alive[h.id], onClick = { tool.connector.connect(h) }, onMenu = { menuFor = h.id }) {
                    DropdownMenu(menuFor == h.id, { menuFor = null }) {
                        DropdownMenuItem({ Text("Connect") }, { menuFor = null; tool.connector.connect(h) })
                        DropdownMenuItem({ Text("Edit") }, { menuFor = null; onEdit(h) })
                        DropdownMenuItem({ Text("Duplicate") }, { menuFor = null; onEdit(h.copy(id = tool.vault.newHostId(), name = h.name + " copy", lastConnected = 0)) })
                        DropdownMenuItem({ Text("Copy ssh command") }, { menuFor = null; copy(context, "ssh", HostRules.command(h)) })
                        DropdownMenuItem({ Text("Delete") }, { menuFor = null; confirmDelete = h })
                    }
                }
            }
        }
    }
    confirmDelete?.let { h -> AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text("Delete ${h.name}?") }, text = { Text("Its saved password goes too. Keys stay.") },
        confirmButton = { TextButton({ tool.vault.deleteHost(h.id); confirmDelete = null }) { Text("Delete") } }, dismissButton = { TextButton({ confirmDelete = null }) { Text("Cancel") } }) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HostRow(h: Host, alive: Boolean?, onClick: () -> Unit, onMenu: () -> Unit, menu: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().pressScale(0.98f).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).combinedClickable(onClick = onClick, onLongClick = onMenu).padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(kindColor(h.kind)), contentAlignment = Alignment.Center) { Icon(kindIcon(h.kind), null, tint = Color.White) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(h.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(8.dp).clip(CircleShape).background(when (alive) { true -> Color(0xFF2E7D32); false -> MaterialTheme.colorScheme.outlineVariant; null -> Color.Transparent }))
            }
            Text(HostRules.label(h) + if (h.lastConnected > 0) " · ${relativeTime(h.lastConnected)}" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (h.tags.isNotEmpty()) Text(h.tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box { IconButton(onMenu) { Icon(Icons.Rounded.MoreVert, "More") }; menu() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostEditor(start: Host, isNew: Boolean, identities: List<Identity>, groups: List<String>, hasPassword: Boolean, onDismiss: () -> Unit, onSave: (Host, String?, Boolean) -> Unit) {
    var name by remember { mutableStateOf(start.name) }
    var address by remember { mutableStateOf(start.address) }
    var port by remember { mutableStateOf(start.port.toString()) }
    var user by remember { mutableStateOf(start.user) }
    var auth by remember { mutableStateOf(start.auth) }
    var key by remember { mutableStateOf(start.identityId.ifEmpty { identities.firstOrNull()?.id.orEmpty() }) }
    var password by remember { mutableStateOf("") }
    var group by remember { mutableStateOf(start.group) }
    var tags by remember { mutableStateOf(start.tags.joinToString(", ")) }
    var kind by remember { mutableStateOf(start.kind) }
    var startup by remember { mutableStateOf(start.startup) }
    var notes by remember { mutableStateOf(start.notes) }
    var error by remember { mutableStateOf<String?>(null) }

    fun build(): Host? {
        val t = HostRules.parseTarget(address.trim())
        val addr = t?.host ?: run { error = "That doesn't look like an address."; return null }
        val p = (if (address.contains(':') && t.port != null) t.port else port.toIntOrNull()) ?: run { error = "Port must be a number."; return null }
        if (p !in 1..65535) { error = "Port must be between 1 and 65535."; return null }
        val u = (t.user ?: user).trim().ifEmpty { error = "Who do you sign in as? Enter a user name."; return null }
        val n = name.trim().ifEmpty { addr }
        return start.copy(name = n, address = addr, port = p, user = u, auth = auth, identityId = if (auth == Auth.Key) key else "", group = group.trim(),
            tags = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }, kind = if (kind == HostKind.Other) HostRules.guessKind(n, addr) else kind, startup = startup, notes = notes)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (isNew) "New host" else "Edit host", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(address, { address = it }, Modifier.weight(1f), label = { Text("Address") }, singleLine = true)
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, Modifier.width(92.dp), label = { Text("Port") }, singleLine = true)
            }
            OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), label = { Text("User") }, singleLine = true)
            Text("Sign in with", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Auth.values().forEach { a -> FilterChip(auth == a, { auth = a }, { Text(if (a == Auth.Ask) "Try keys, then ask" else a.label) }) } }
            when (auth) {
                Auth.Key -> if (identities.isEmpty()) Text("You have no keys yet. Make one on the Keys tab, then pick it here.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { identities.forEach { i -> FilterChip(key == i.id, { key = i.id }, { Text(i.name) }, leadingIcon = { Icon(Icons.Rounded.Key, null, Modifier.size(16.dp)) }) } }
                Auth.Password -> OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text(if (hasPassword) "New password (leave empty to keep the saved one)" else "Password (saved locked on this phone; empty to be asked)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                Auth.Ask -> Text("Tries your saved keys, then asks for a password in the terminal. Nothing is stored.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(group, { group = it }, Modifier.fillMaxWidth(), label = { Text("Group (optional)") }, singleLine = true)
            if (groups.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { groups.forEach { g -> FilterChip(group == g, { group = g }, { Text(g) }) } }
            OutlinedTextField(tags, { tags = it }, Modifier.fillMaxWidth(), label = { Text("Tags, separated by commas") }, singleLine = true)
            Text("Looks like", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val shown = if (kind == HostKind.Other) HostRules.guessKind(name, address) else kind
                HostKind.values().forEach { k -> FilterChip(shown == k, { kind = k }, { Text(k.label) }, leadingIcon = { Icon(kindIcon(k), null, Modifier.size(16.dp)) }) }
            }
            OutlinedTextField(startup, { startup = it }, Modifier.fillMaxWidth(), label = { Text("Run after connecting (optional)") }, singleLine = true)
            OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("Notes (optional)") }, minLines = 2)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ build()?.let { onSave(it, password.ifEmpty { null }, true) } }) { Text("Save and connect") }
                OutlinedButton({ build()?.let { onSave(it, password.ifEmpty { null }, false) } }) { Text("Save") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SnippetsTab(app: UltimateApp, snippets: List<Snippet>, onEdit: (Snippet) -> Unit) {
    val context = LocalContext.current
    val byFolder = snippets.groupBy { it.folder.trim() }.toSortedMap(compareBy { it.lowercase() })
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Tap a snippet to run it in your open terminal tab. Long-press to edit. {{clip}} becomes your last copied text.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        byFolder.forEach { (folder, list) ->
            item("f-$folder") { Text(folder.ifEmpty { "Snippets" }, Modifier.padding(top = 6.dp, start = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
            items(list, key = { it.id }) { s ->
                Row(Modifier.fillMaxWidth().pressScale(0.98f).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .combinedClickable(onClick = {
                        val clip = app.clips.clips.value.firstOrNull()?.text.orEmpty()
                        app.terminal.type(Snippets.fill(s.command, mapOf("clip" to clip)), true)
                        app.tools.firstOrNull { it.id == "terminal" }?.let { app.shell.open(it) }
                        app.insights.count("ssh.snippet")
                    }, onLongClick = { onEdit(s) }).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.DataObject, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(s.command, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton({ copy(context, "snippet", s.command) }) { Icon(Icons.Rounded.ContentCopy, "Copy") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SnippetEditor(start: Snippet, isNew: Boolean, folders: List<String>, onDismiss: () -> Unit, onSave: (Snippet) -> Unit, onDelete: (Snippet) -> Unit) {
    var name by remember { mutableStateOf(start.name) }
    var command by remember { mutableStateOf(start.command) }
    var folder by remember { mutableStateOf(start.folder) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (isNew) "New snippet" else "Edit snippet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
            OutlinedTextField(command, { command = it }, Modifier.fillMaxWidth(), label = { Text("Command") }, minLines = 2, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
            OutlinedTextField(folder, { folder = it }, Modifier.fillMaxWidth(), label = { Text("Folder (optional)") }, singleLine = true)
            if (folders.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { folders.forEach { f -> FilterChip(folder == f, { folder = f }, { Text(f) }) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ if (name.isNotBlank() && command.isNotBlank()) onSave(start.copy(name = name.trim(), command = command.trim(), folder = folder.trim())) }, enabled = name.isNotBlank() && command.isNotBlank()) { Text("Save") }
                if (!isNew) TextButton({ onDelete(start) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun KeysTab(identities: List<Identity>, onGenerate: () -> Unit, onImport: () -> Unit, onInstall: (Identity) -> Unit, onDelete: (Identity) -> Unit) {
    val context = LocalContext.current
    var confirm by remember { mutableStateOf<Identity?>(null) }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("A key lets this phone sign in without a password. The private half never leaves the phone, and is stored locked.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (identities.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.Key, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
                Text("No keys yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Button(onGenerate) { Text("Make a key") }
            }
        }
        items(identities, key = { it.id }) { i ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Key, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp))
                    Text(i.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(relativeTime(i.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
                Text(i.fingerprint, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton({ copy(context, "public key", i.publicKey + " ${i.name.replace(' ', '-')}") }) { Text("Copy public key") }
                    TextButton({ onInstall(i) }) { Text("Install on a server") }
                    TextButton({ confirm = i }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        item { TextButton(onImport) { Text("Import an existing key") } }
    }
    confirm?.let { i -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Delete ${i.name}?") }, text = { Text("Servers that trust this key will no longer accept this phone with it. Hosts using it switch to “try keys, then ask”.") },
        confirmButton = { TextButton({ onDelete(i); confirm = null }) { Text("Delete") } }, dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } }) }
}

@Composable
private fun NameDialog(title: String, hint: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Column { Text(hint, style = MaterialTheme.typography.bodySmall); OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) } },
        confirmButton = { TextButton({ onOk(name.ifBlank { "Phone" }) }) { Text("Create") } }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
private fun ImportDialog(onDismiss: () -> Unit, onOk: (String, String) -> Boolean) {
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Import a key") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Paste a private key (the text starting “-----BEGIN …”). Keys protected by a passphrase can't be read yet; remove it first with ssh-keygen -p, or make a new key here.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") })
            OutlinedTextField(text, { text = it }, minLines = 4, label = { Text("Private key") }, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton({ if (!onOk(name.ifBlank { "Imported key" }, text.trim() + "\n")) error = "I can't read that as a key. If it has a passphrase, remove it first." }) { Text("Import") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

/** Signs in once with a password and puts the key on the server, then the host uses the key from then on. */
@Composable
private fun InstallDialog(tool: HostsTool, identity: Identity, hosts: List<Host>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var host by remember { mutableStateOf(hosts.firstOrNull()) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Install “${identity.name}”") },
        text = {
            if (hosts.isEmpty()) Text("Add a host first.") else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Signs in once with the password and adds this key to the server. After that, no password.", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { hosts.forEach { h -> FilterChip(host?.id == h.id, { host = h }, { Text(h.name) }) } }
                OutlinedTextField(password, { password = it }, singleLine = true, label = { Text("Password for ${host?.user.orEmpty()}") }, visualTransformation = PasswordVisualTransformation())
                message?.let { Text(it, color = if (it.startsWith("Done")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton({
                val h = host ?: return@TextButton
                if (KnownHosts.check(tool.vault.knownFingerprints(), h.address, h.port, "x") == KnownHosts.Verdict.New) { message = "Connect to ${h.name} once first, so you can check its fingerprint."; return@TextButton }
                busy = true; message = "Working…"
                Thread {
                    val err = tool.connector.installKey(h, identity, password) { false }
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        busy = false
                        if (err == null) { tool.vault.saveHost(h.copy(auth = Auth.Key, identityId = identity.id)); message = "Done. ${h.name} now uses this key."; Toast.makeText(context, "Key installed on ${h.name}", Toast.LENGTH_LONG).show() } else message = err
                    }
                }.start()
            }, enabled = !busy && host != null && password.isNotEmpty()) { Text("Install") }
        },
        dismissButton = { TextButton(onDismiss, enabled = !busy) { Text(if (message?.startsWith("Done") == true) "Close" else "Cancel") } })
}
