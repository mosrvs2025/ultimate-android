package app.ultimate.home

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.BuildConfig
import app.ultimate.UltimateApp
import app.ultimate.checkup.engine.Collector
import app.ultimate.files.UpdateInstallActivity
import app.ultimate.files.UpdateWatcher
import app.ultimate.inbox.listenerEnabled
import app.ultimate.screentime.ScreenTime
import app.ultimate.screentime.UsageMath
import app.ultimate.core.appear
import app.ultimate.core.copyText
import app.ultimate.core.formatBytes
import app.ultimate.core.pressScale
import app.ultimate.core.relativeTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Calendar

/** The phone's state as the dashboard sees it; anything unknown is null rather than zero. */
data class Facts(
    val battery: Int?, val charging: Boolean, val free: Long, val total: Long,
    val screenMsToday: Long?, val notificationsToday: Int?, val notificationAccess: Boolean?,
    val checkupScore: Int?, val checkupAt: Long?, val updateVersion: String?, val now: Long,
)

/** A suggestion on Home: something worth doing now, and the tool that does it. */
data class Nudge(val id: String, val text: String, val action: String, val toolId: String?)

/** Which suggestions to show, most useful first. Pure, so the rules are tested. */
object Nudges {
    fun build(f: Facts, tools: Set<String>): List<Nudge> = buildList {
        if (f.updateVersion != null) add(Nudge("update", "Ultimate ${f.updateVersion} is in your Downloads", "Install", null))
        if (f.total > 0 && f.free.toDouble() / f.total < 0.10 && "cleanup" in tools) add(Nudge("space", "Only ${formatBytes(f.free)} left on your phone", "Clean up", "cleanup"))
        if ("checkup" in tools && (f.checkupAt == null || f.now - f.checkupAt > 7 * 86_400_000L)) add(Nudge("checkup", if (f.checkupAt == null) "You haven't checked your phone yet" else "Your last checkup was ${(f.now - f.checkupAt) / 86_400_000L} days ago", "Check now", "checkup"))
        if ("inbox" in tools && f.notificationAccess == false) add(Nudge("inbox", "Keep every notification, even unsent messages", "Turn on", "inbox"))
        if ("screentime" in tools && f.screenMsToday != null && f.screenMsToday > 4 * 3_600_000L) add(Nudge("screen", "Over ${f.screenMsToday / 3_600_000L} hours on screen today", "See where", "screentime"))
    }

    fun greeting(at: Long): String = when (Calendar.getInstance().apply { timeInMillis = at }.get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..21 -> "Good evening"; else -> "Hello, night owl"
    }
}

private fun gather(app: UltimateApp, now: Long): Facts {
    val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = battery?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) }?.takeIf { it >= 0 }
    val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val stat = StatFs(Environment.getDataDirectory().path)
    val ids = app.tools.map { it.id }.toSet()
    val usage = "screentime" in ids && runCatching { Collector(app).hasUsageAccess() }.getOrDefault(false)
    val screen = if (usage) runCatching {
        val launchable = app.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).map { it.activityInfo.packageName }.toSet()
        val home = app.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        ScreenTime(app).days(1, now).last().second.filterKeys { it in launchable && it != home }.values.sum()
    }.getOrNull() else null
    val inbox = "inbox" in ids
    val last = if ("checkup" in ids) app.checkupManager.last.value else null
    return Facts(
        level, status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL, stat.availableBytes, stat.totalBytes,
        screen, if (inbox) runCatching { app.notificationLog.today(now) }.getOrNull() else null, if (inbox) listenerEnabled(app) else null,
        last?.first, last?.second, if (BuildConfig.HAS_FILES) runCatching { UpdateWatcher.find(app)?.versionName }.getOrNull() else null, now,
    )
}

/** Home's top: greeting, the computer link, glanceable cards and what is worth doing now. */
@Composable
fun TodayDashboard(app: UltimateApp) {
    val context = LocalContext.current
    var facts by remember { mutableStateOf<Facts?>(null) }
    var tick by remember { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    LaunchedEffect(tick) { facts = withContext(Dispatchers.IO) { gather(app, System.currentTimeMillis()) } }
    LaunchedEffect(Unit) { while (true) { delay(60_000); tick++ } }
    val ids = remember { app.tools.map { it.id }.toSet() }
    val seen = remember { mutableSetOf<Any>() }
    fun open(id: String) = app.tools.firstOrNull { it.id == id }?.let { app.insights.count("home.card.$id"); app.shell.open(it) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(Nudges.greeting(System.currentTimeMillis()), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
        if ("send" in ids) ComputerCard(app)
        val f = facts
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
            if (f != null) {
                item("battery") {
                    Glance(Modifier.appear("g-bat", seen, 0), if (f.charging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryStd, "Phone",
                        (f.battery?.let { "$it%" } ?: "–"), "${formatBytes(f.free)} free", ring = f.battery?.let { it / 100f },
                        onClick = { if ("storage" in ids) open("storage") else if ("cleanup" in ids) open("cleanup") })
                }
                if ("screentime" in ids) item("screen") {
                    Glance(Modifier.appear("g-st", seen, 1), Icons.Rounded.HourglassBottom, "Screen today", f.screenMsToday?.let { UsageMath.hm(it) } ?: "Set up", if (f.screenMsToday == null) "Tap to allow" else "across your apps") { open("screentime") }
                }
                if ("inbox" in ids) item("inbox") {
                    Glance(Modifier.appear("g-in", seen, 2), Icons.Rounded.NotificationsActive, "Notifications", if (f.notificationAccess == true) "${f.notificationsToday ?: 0}" else "Off", if (f.notificationAccess == true) "kept today" else "Tap to turn on") { open("inbox") }
                }
                if ("checkup" in ids) item("checkup") {
                    Glance(Modifier.appear("g-ch", seen, 3), Icons.Rounded.HealthAndSafety, "Phone health", f.checkupScore?.let { "$it/100" } ?: "–", f.checkupAt?.let { relativeTime(it) } ?: "Not checked yet",
                        ring = f.checkupScore?.let { it / 100f }) { open("checkup") }
                }
                if ("clips" in ids) item("clip") {
                    val clips by app.clips.clips.collectAsStateWithLifecycle()
                    val last = clips.firstOrNull()
                    Glance(Modifier.appear("g-cl", seen, 4), Icons.Rounded.ContentPaste, "Last copied", last?.text?.lines()?.first()?.take(28) ?: "Nothing yet", if (last != null) "Tap to copy again" else "Copy something, then open Ultimate",
                        small = true) { if (last != null) copyText(context, "Clip", last.text) else open("clips") }
                }
            }
        }
        f?.let { Nudges.build(it, ids) }?.take(3)?.forEachIndexed { i, n ->
            Row(
                Modifier.fillMaxWidth().appear("n-${n.id}", seen, i + 5).pressScale(0.98f).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.tertiaryContainer)
                    .clickable {
                        app.insights.count("home.nudge.${n.id}")
                        if (n.id == "update") UpdateWatcher.find(app)?.let { context.startActivity(Intent(context, UpdateInstallActivity::class.java).putExtra("path", it.file.path)) }
                        else n.toolId?.let(::open)
                    }.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (n.id == "update") Icons.Rounded.SystemUpdate else Icons.Rounded.Lightbulb, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                Spacer(Modifier.width(12.dp))
                Text(n.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(n.action, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }
}

@Composable
private fun Glance(modifier: Modifier, icon: ImageVector, label: String, value: String, detail: String, ring: Float? = null, small: Boolean = false, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(
        modifier.width(150.dp).height(132.dp).pressScale(0.95f).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).clickable(onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                ring?.let { r -> Canvas(Modifier.size(30.dp)) { drawArc(track, -90f, 360f, false, style = Stroke(3.dp.toPx())); drawArc(primary, -90f, 360f * r.coerceIn(0f, 1f), false, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round)) } }
                Icon(icon, null, Modifier.size(if (ring != null) 16.dp else 22.dp), tint = primary)
            }
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Column {
            Text(value, style = if (small) MaterialTheme.typography.titleSmall else MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The computer link, one tap from Home: off, on and waiting, or connected (with which browser). */
@Composable
private fun ComputerCard(app: UltimateApp) {
    val server = app.sendServer
    val state by server.state.collectAsStateWithLifecycle()
    val visitors by server.visitors.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Re-checked every few seconds, so a computer that closed the page stops showing as connected.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.running) { while (state.running) { delay(5_000); now = System.currentTimeMillis() } }
    val live = visitors.firstOrNull { now - it.lastSeen < 20_000 }
    val pulse by rememberInfiniteTransition(label = "link").animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dot")
    val bg = when { live != null -> MaterialTheme.colorScheme.primaryContainer; state.running -> MaterialTheme.colorScheme.secondaryContainer; else -> MaterialTheme.colorScheme.surfaceContainerLow }
    Row(
        Modifier.fillMaxWidth().pressScale(0.98f).clip(RoundedCornerShape(24.dp)).background(bg)
            .clickable { app.tools.firstOrNull { it.id == "send" }?.let { app.shell.open(it) } }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Computer, null, tint = MaterialTheme.colorScheme.primary)
            if (state.running) Canvas(Modifier.size(44.dp)) { drawCircle(Color(0xFF2E9E5B).copy(alpha = pulse), 5.dp.toPx(), Offset(size.width - 6.dp.toPx(), 6.dp.toPx())) }
        }
        Spacer(Modifier.width(14.dp))
        AnimatedContent(Triple(state.running, state.address, live?.label), Modifier.weight(1f), label = "link") { (on, address, who) ->
            Column {
                Text(
                    when { who != null -> "$who is connected"; on -> "Ready for your computer"; else -> "Use this phone from your computer" },
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                )
                Text(
                    when { on && address != null -> address.removePrefix("http://"); else -> "Files, clipboard, notifications and camera in your browser" },
                    style = MaterialTheme.typography.bodySmall, fontFamily = if (on) FontFamily.Monospace else null, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!state.running) Button({ server.start(); app.insights.count("home.link.on") }) { Text("Turn on") }
        else if (live == null) TextButton({ state.address?.let { copyText(context, "Address", it) } }) { Text("Copy") }
        else Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
    }
}
