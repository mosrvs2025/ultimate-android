package app.ultimate.inbox

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.UltimateApp
import app.ultimate.core.AppIcon
import app.ultimate.core.LibraryItem
import app.ultimate.core.ShellActions
import app.ultimate.core.Tool
import app.ultimate.core.ToolBackButton
import app.ultimate.core.ToolLaunch
import app.ultimate.core.copyText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Every notification, kept and searchable, including ones an app later took back. */
class InboxTool(private val app: UltimateApp) : Tool {
    override val id = "inbox"
    override val title = "Notification history"
    override val tagline = "Search every notification, even unsent ones"
    override val icon = Icons.Rounded.NotificationsActive
    override val acceptedMimePrefixes = emptyList<String>()
    override val headline: StateFlow<String> = MutableStateFlow("Never lose a message").asStateFlow()
    override val library: StateFlow<List<LibraryItem>> = MutableStateFlow<List<LibraryItem>>(emptyList()).asStateFlow()
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = true
    override suspend fun refresh() = Unit
    @Composable override fun Screen(onExit: () -> Unit, launch: ToolLaunch) = InboxScreen(app, onExit)
}

fun listenerEnabled(context: android.content.Context): Boolean {
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
    return flat.split(':').mapNotNull { ComponentName.unflattenFromString(it) }.any { it.packageName == context.packageName }
}

/** "Today", "Yesterday", or the date, for the day headers. */
fun dayLabel(at: Long, now: Long = System.currentTimeMillis()): String {
    fun start(t: Long) = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    return when ((start(now) - start(at)) / 86_400_000L) {
        0L -> "Today"; 1L -> "Yesterday"
        else -> SimpleDateFormat("EEEE, d MMM", Locale.getDefault()).format(at)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InboxScreen(app: UltimateApp, onExit: () -> Unit) {
    BackHandler(onBack = onExit)
    val context = LocalContext.current
    val log = app.notificationLog
    var enabled by remember { mutableStateOf(listenerEnabled(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { enabled = listenerEnabled(context) }
    val changed by log.changed.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var pkg by remember { mutableStateOf<String?>(null) }
    var onlyRemoved by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf<List<Logged>>(emptyList()) }
    var apps by remember { mutableStateOf<List<Triple<String, String, Int>>>(emptyList()) }
    var settings by remember { mutableStateOf(false) }
    LaunchedEffect(query, pkg, onlyRemoved, changed) {
        if (query.isNotEmpty()) delay(200)
        rows = withContext(Dispatchers.IO) { log.search(query, pkg, onlyRemoved) }
        apps = withContext(Dispatchers.IO) { log.apps() }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Notification history") }, navigationIcon = { ToolBackButton(onExit) },
            actions = { IconButton({ settings = true }) { Icon(Icons.Rounded.Settings, "Settings") }; ShellActions() })
    }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!enabled) item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Keep every notification", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Turn on notification access for Ultimate. It keeps the text of your notifications on this phone only, so you can search them later, even if a sender unsends a message.")
                    Button({ context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Turn on notification access") }
                }
            }
            if (enabled) item { ListenerStatus(log, changed) }
            item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(28.dp),
                    placeholder = { Text("Search notifications") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, "Clear") } })
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(pkg == null && !onlyRemoved, { pkg = null; onlyRemoved = false }, { Text("All") })
                    FilterChip(onlyRemoved, { onlyRemoved = !onlyRemoved }, { Text("Pulled back") })
                    apps.take(20).forEach { (p, label, n) ->
                        FilterChip(pkg == p, { pkg = if (pkg == p) null else p }, { Text("$label $n") }, leadingIcon = { AppIcon(p, size = 18.dp) })
                    }
                }
            }
            if (rows.isEmpty() && enabled) item {
                Text(if (query.isEmpty() && pkg == null && !onlyRemoved) "New notifications will appear here as they arrive." else "Nothing matches.", Modifier.padding(vertical = 32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            var lastDay = ""
            rows.forEach { n ->
                val day = dayLabel(n.at)
                if (day != lastDay) { lastDay = day; item(key = "day-$day") { Text(day, Modifier.padding(top = 12.dp, bottom = 2.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) } }
                item(key = n.id) { Row_(n, query, onCopy = { copyText(context, n.title, listOf(n.title, n.text).filter { it.isNotBlank() }.joinToString("\n")) }, onDelete = { log.delete(n.id) }) }
            }
        }
    }
    if (settings) InboxSettings(log, apps, onDismiss = { settings = false })
}

/** Is it actually listening? Green when Android has it connected; otherwise the one-tap fixes for a paused listener. */
@Composable
private fun ListenerStatus(log: NotificationLog, changed: Int) {
    val context = LocalContext.current
    val pm = context.getSystemService(android.os.PowerManager::class.java)
    var unrestricted by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(context.packageName)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { unrestricted = pm.isIgnoringBatteryOptimizations(context.packageName) }
    var today by remember { mutableStateOf(0) }
    LaunchedEffect(changed) { today = withContext(Dispatchers.IO) { log.today() } }
    val live = log.isConnected && !log.paused
    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "live").animateFloat(0.35f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1000), androidx.compose.animation.core.RepeatMode.Reverse), label = "dot")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(if (live) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (live) androidx.compose.ui.graphics.Color(0xFF2E9E5B).copy(alpha = pulse) else MaterialTheme.colorScheme.error))
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    log.paused -> "Paused by you"
                    live -> "Listening" + (if (log.lastCaught > 0) " · last caught ${app.ultimate.core.relativeTime(log.lastCaught)}" else "") + " · $today today"
                    else -> "Android has paused listening"
                },
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (!live && !log.paused) {
            Text("Notifications that arrive now won't be kept. Reconnect, and let Ultimate run in the background so it stays connected.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ android.service.notification.NotificationListenerService.requestRebind(ComponentName(context, NotificationCatcher::class.java)) }) { Text("Reconnect") }
                TextButton({ context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Check access") }
            }
        }
        if (!unrestricted) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Samsung may put Ultimate to sleep and miss notifications.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton({
                @android.annotation.SuppressLint("BatteryLife")
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${context.packageName}"))
                runCatching { context.startActivity(i) }.onFailure { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }) { Text("Keep awake") }
        }
    }
}

@Composable
private fun Row_(n: Logged, query: String, onCopy: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).clickable { open = !open }
            .animateContentSize().padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            AppIcon(n.pkg, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(n.app, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text("  " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(n.at), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (n.removedByApp) Text("  Pulled back", Modifier.padding(start = 6.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 6.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
                if (n.title.isNotBlank()) Text(n.title, fontWeight = FontWeight.SemiBold, maxLines = if (open) 4 else 1, overflow = TextOverflow.Ellipsis)
                if (n.text.isNotBlank()) Text(n.text, style = MaterialTheme.typography.bodyMedium, maxLines = if (open) 40 else 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (open) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onCopy) { Text("Copy") }
            TextButton({ context.packageManager.getLaunchIntentForPackage(n.pkg)?.let { context.startActivity(it) } }) { Text("Open ${n.app}") }
            TextButton(onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        }
        if (open && n.removedByApp) Text("${n.app} withdrew this notification itself. That usually means the message was unsent or deleted, or read on another device.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InboxSettings(log: NotificationLog, apps: List<Triple<String, String, Int>>, onDismiss: () -> Unit) {
    var days by remember { mutableStateOf(log.keepDays) }
    var paused by remember { mutableStateOf(log.paused) }
    var excluded by remember { mutableStateOf(log.excluded) }
    var confirm by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Notification history", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Stored only on this phone, never in reports or backups. ${log.count()} kept.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Pause recording", Modifier.weight(1f)); Switch(paused, { paused = it; log.paused = it }) }
            Text("Keep for", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7, 30, 90, 365).forEach { d -> FilterChip(days == d, { days = d; log.keepDays = d; log.trim() }, { Text(if (d == 365) "1 year" else "$d days") }) }
            }
            if (apps.isNotEmpty()) {
                Text("Don't keep from", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    apps.forEach { (p, label, _) ->
                        val off = p in excluded
                        FilterChip(off, { excluded = if (off) excluded - p else excluded + p; log.excluded = excluded; if (!off) log.deleteApp(p) }, { Text(label) }, leadingIcon = { AppIcon(p, size = 18.dp) })
                    }
                }
                Text("Picking an app also deletes what was kept from it.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            TextButton({ confirm = true }) { Text("Delete all history", color = MaterialTheme.colorScheme.error) }
            if (confirm) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Delete everything kept?", Modifier.weight(1f))
                TextButton({ confirm = false }) { Text("No") }
                TextButton({ log.clear(); confirm = false; onDismiss() }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
