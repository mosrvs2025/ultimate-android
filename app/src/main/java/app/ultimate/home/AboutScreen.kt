package app.ultimate.home

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.ultimate.BuildConfig
import app.ultimate.UltimateApp
import app.ultimate.checkup.engine.Collector
import app.ultimate.core.relativeTime
import app.ultimate.files.UpdateInstallActivity
import app.ultimate.files.UpdateWatcher
import app.ultimate.files.allFilesIntent
import app.ultimate.files.hasAllFiles
import app.ultimate.insights.InsightsSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class Setup(val title: String, val why: String, val granted: Boolean, val open: Intent)

/** Version, what changed, what the app is allowed to do, crashes and how to send them: everything about the build in your hand. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(app: UltimateApp, onClose: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    var showReport by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<app.ultimate.files.FoundUpdate?>(null) }
    LaunchedEffect(tick) { if (BuildConfig.HAS_FILES) update = withContext(Dispatchers.IO) { runCatching { UpdateWatcher.find(context) }.getOrNull() } }
    val setup = remember(tick) { setupList(context) }
    val crashes = remember(tick) { app.insights.crashes().asReversed() }
    val info = remember { packageInfo(context) }
    val edition = AboutInfo.editionName(BuildConfig.FLAVOR)
    val internet = context.packageManager.checkPermission(Manifest.permission.INTERNET, context.packageName) == PackageManager.PERMISSION_GRANTED
    val details = AboutInfo.details(edition, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong(), BuildConfig.BUILD_TYPE, Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
        "${Build.MANUFACTURER} ${Build.MODEL}", app.tools.map { it.title }, internet, crashes.take(3).map { AboutInfo.crashLine(it, relativeTime(it.at)) })

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(onBack = onClose)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(topBar = { TopAppBar(title = { Text("About and settings") }, navigationIcon = { IconButton(onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }) }) { pad ->
                LazyColumn(Modifier.padding(pad), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(edition, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("Build ${BuildConfig.VERSION_CODE} · ${BuildConfig.BUILD_TYPE}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            info?.let { Text("Installed ${relativeTime(it.first)}" + if (it.second > it.first + 60_000) " · updated ${relativeTime(it.second)}" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                        }
                    }
                    item {
                        update?.let { u ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.tertiaryContainer).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Version ${u.versionName} is downloaded", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                                    Text("Ready to install over this one. Your data stays.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                                }
                                Button({ context.startActivity(Intent(context, UpdateInstallActivity::class.java).putExtra("path", u.file.path)) }) { Text("Install") }
                            }
                        } ?: if (BuildConfig.HAS_FILES) Text("I look for newer builds you've downloaded and offer to install them here and in a notification.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) else Unit
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ copy(context, "Ultimate details", details); Toast.makeText(context, "Copied. Paste it into a message.", Toast.LENGTH_SHORT).show() }, Modifier.weight(1f)) {
                                Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Copy details")
                            }
                            OutlinedButton({ showReport = true }, Modifier.weight(1f)) { Text("Usage report") }
                        }
                    }
                    item { Section("What's new") }
                    items(Changelog.recent(BuildConfig.VERSION_NAME)) { e ->
                        Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                            Text(e.version + if (e.version == BuildConfig.VERSION_NAME) "  ·  this one" else "", fontWeight = FontWeight.SemiBold)
                            e.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                    if (setup.isNotEmpty()) {
                        item { Section("Setup · ${setup.count { it.granted }} of ${setup.size} allowed") }
                        items(setup) { s ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(enabled = !s.granted) { runCatching { context.startActivity(s.open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (s.granted) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, if (s.granted) "Allowed" else "Not allowed", tint = if (s.granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) { Text(s.title, fontWeight = FontWeight.Medium); Text(s.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                if (!s.granted) TextButton({ runCatching { context.startActivity(s.open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Allow") }
                            }
                        }
                    }
                    item { Section("Privacy") }
                    item {
                        Text(
                            if (internet) "This edition is allowed to use the internet, only for what you turn on: sharing with your computer, SSH, network tools and AI with your own key. No ads, no analytics, no accounts."
                            else "This edition has no internet permission, so it cannot send anything anywhere. Everything stays on this phone. No ads, no analytics, no accounts.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item { Section("Tools in this edition · ${app.tools.size}") }
                    item { Text(app.tools.joinToString(" · ") { it.title }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    item { Section(if (crashes.isEmpty()) "Crashes · none" else "Recent crashes") }
                    if (crashes.isNotEmpty()) {
                        items(crashes.take(5)) { c -> Text(AboutInfo.crashLine(c, relativeTime(c.at)) + "  (v${c.version})", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.error) }
                        item { TextButton({ app.insights.clearCrashes(); tick++ }) { Text("Clear the list") } }
                    }
                    item { Section("Open-source parts") }
                    item {
                        Text(
                            "Jetpack Compose, AndroidX, Media3 and CameraX (Apache 2.0) · Termux terminal view and emulator (Apache 2.0) · Apache MINA SSHD (Apache 2.0, Ultimate+) · ZXing (Apache 2.0) · jump3r (LGPL)",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    if (showReport) InsightsSheet(app) { showReport = false }
}

@Composable
private fun Section(title: String) = Text(title, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)

private fun copy(context: Context, label: String, text: String) { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text)) }

/** When Ultimate was first installed and last updated, in milliseconds; null if Android won't say. */
private fun packageInfo(context: Context): Pair<Long, Long>? = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).let { it.firstInstallTime to it.lastUpdateTime } }.getOrNull()

/** The permissions this edition can use, whether each is allowed now, and where to allow it. Only what the edition's tools need. */
private fun setupList(c: Context): List<Setup> = buildList {
    val pkg = c.packageName
    val nm = c.getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= 33) add(Setup("Notifications", "So finished jobs and reminders can tell you.", c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)))
    if (BuildConfig.HAS_FILES) {
        add(Setup("All files access", "Storage map, Clean up, Files and the terminal need to see your folders.", hasAllFiles(c), allFilesIntent(c)))
        add(Setup("Install apps", "To install an update you downloaded.", c.packageManager.canRequestPackageInstalls(), Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$pkg"))))
    }
    if (BuildConfig.HAS_INBOX) add(Setup("Notification access", "To keep your notification history, including deleted messages.", NotificationManagerCompat.getEnabledListenerPackages(c).contains(pkg), Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)))
    if (BuildConfig.HAS_SCREENTIME || BuildConfig.HAS_CHECKUP) add(Setup("Usage access", "Screen time, app sizes and the phone checkup.", Collector(c).hasUsageAccess(), Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)))
    if (BuildConfig.HAS_ORB) add(Setup("Display over other apps", "The floating orb and opening things by themselves.", Settings.canDrawOverlays(c), Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$pkg"))))
    if (BuildConfig.HAS_RULES) add(Setup("Do Not Disturb access", "So rules can silence the phone.", nm.isNotificationPolicyAccessGranted, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)))
    if (BuildConfig.HAS_INBOX || BuildConfig.HAS_SEND || BuildConfig.HAS_RULES) add(Setup("Keep running in the background", "So notifications, rules and sharing aren't stopped to save battery.",
        c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(pkg), Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg"))))
}
