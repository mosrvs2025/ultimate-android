package app.ultimate.inbox

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ultimate.core.AppIcon
import app.ultimate.core.GooeyProgress
import app.ultimate.core.GooeyTabs
import app.ultimate.core.appear
import app.ultimate.core.motionOn
import app.ultimate.core.pressScale
import app.ultimate.footprint.AccessDonut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/** The Insights tab: what your notifications say about the apps and people in your life, worked out on the phone. */
@Composable
internal fun InboxInsights(log: NotificationLog, changed: Int, onShowPulledBack: () -> Unit, modifier: Modifier = Modifier) {
    var range by remember { mutableIntStateOf(1) }
    val spans = listOf(7, 30, 0)
    var report by remember { mutableStateOf<NotifyReport?>(null) }
    var app by remember { mutableStateOf<AppSignal?>(null) }
    LaunchedEffect(range, changed) {
        report = withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val from = if (spans[range] == 0) 0L else now - spans[range] * 86_400_000L
            NotifyMath.report(log.since(from), now)
        }
    }
    val seen = remember { mutableSetOf<Any>() }
    LazyColumn(modifier, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { GooeyTabs(listOf("7 days", "30 days", "Everything"), range, { range = it }) }
        val r = report
        if (r == null) { item { Text("Reading your notifications…", color = MaterialTheme.colorScheme.onSurfaceVariant) }; return@LazyColumn }
        if (r.total < 10) {
            item { Card { Text("Not enough yet", fontWeight = FontWeight.SemiBold); Text("Insights fill in as notifications arrive. Give it a day.", style = MaterialTheme.typography.bodyMedium) } }
            return@LazyColumn
        }
        item { Box(Modifier.appear("hero", seen, 0)) { Hero(r) } }
        if (r.sentences.isNotEmpty()) item {
            Card { r.sentences.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium) } }
        }

        item { Heading("Signal or noise", "How often you open each app's notifications. Tap one to quiet it.") }
        val ranked = r.apps.take(8)
        items(ranked, key = { "a-" + it.pkg }) { a -> SignalRow(a, ranked.first().count) { app = a } }
        if (r.apps.none { it.tapRate != null }) item { Note("Taps and swipes are counted from this version on, so verdicts appear after a few days.") }

        item { Heading("When it reaches you", "Every notification by hour of the day. Tap a bar.") }
        item { HourBars(r.hours) }

        if (r.people.isNotEmpty()) {
            item { Heading("Who reaches you", "People and chats, and how quickly you usually open them.") }
            items(r.people.take(6), key = { "p-" + it.name }) { p -> PersonRow(p, r.people.first().count) }
        }

        if (r.promoTotal > 0) {
            item { Heading("Adverts in disguise", "Notifications that are really trying to sell you something.") }
            item {
                Card {
                    Text("${r.promoTotal} adverts", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                    r.promos.take(4).forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically) { AppIcon(p.pkg, size = 24.dp); Text("  ${p.app}", Modifier.weight(1f), fontWeight = FontWeight.Medium); Text("${p.count}") }
                        Text("“${p.example.take(90)}”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        if (r.storms.isNotEmpty()) {
            item { Heading("Bursts", "One app pinging again and again within minutes.") }
            items(r.storms.take(3), key = { "s-${it.pkg}-${it.start}" }) { s ->
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(s.pkg, size = 28.dp); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${s.count} from ${s.app}", fontWeight = FontWeight.SemiBold)
                            Text("${SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(s.start)} · over ${((s.end - s.start) / 60_000).coerceAtLeast(1)} min", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        if (r.repeats.isNotEmpty()) {
            item { Heading("Said again and again", "The exact same notification, many times.") }
            items(r.repeats.take(3), key = { "r-${it.pkg}-${it.title}-${it.text.hashCode()}" }) { rp ->
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) { AppIcon(rp.pkg, size = 24.dp); Text("  ${rp.app}", Modifier.weight(1f), fontWeight = FontWeight.Medium); Text("×${rp.count}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
                    Text(listOf(rp.title, rp.text).filter { it.isNotBlank() }.joinToString(": "), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        if (r.night > 0) item {
            Card {
                Text("While you slept", fontWeight = FontWeight.SemiBold)
                Text("${r.night} notifications between midnight and 6am" + (r.nightApps.firstOrNull()?.let { ", most from ${it.first} (${it.second})" } ?: "") + ". Do Not Disturb at night would hold these until morning.", style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (r.codes > 0) item {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${r.codes} sign-in codes passed through", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text("From ${r.codeApps.take(3).joinToString()}. Every app you give notification access to can read these codes too. Keep that list short.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    val context = LocalContext.current
                    TextButton({ context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("See which apps have access") }
                }
            }
        }

        if (r.pulledBack > 0) item {
            Box(Modifier.pressScale(0.98f).clip(RoundedCornerShape(22.dp)).clickable(onClick = onShowPulledBack)) {
                Card { Text("${r.pulledBack} pulled back", fontWeight = FontWeight.SemiBold); Text("Notifications an app took back, often unsent messages. You kept them. Tap to read.", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    app?.let { a -> AppSheet(a, log) { app = null } }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

@Composable
private fun Heading(title: String, sub: String) = Column(Modifier.padding(top = 8.dp)) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Note(text: String) = Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)

/** The headline: how many, and a ring of what you did with them. */
@Composable
private fun Hero(r: NotifyReport) {
    val shown = remember(r.total) { Animatable(0f) }
    val motion = motionOn()
    LaunchedEffect(r.total) { if (motion) shown.animateTo(r.total.toFloat(), tween(900, easing = FastOutSlowInEasing)) else shown.snapTo(r.total.toFloat()) }
    var pick by remember { mutableIntStateOf(-1) }
    val slices = listOf(Fate.Tapped to "Opened", Fate.Swiped to "Swiped away", Fate.ClearedAll to "Cleared with the rest", Fate.Withdrawn to "Taken back by the app", Fate.Waiting to "Never touched")
        .map { (f, label) -> label to (r.fates[f] ?: 0) }.filter { it.second > 0 }
    val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.outlineVariant)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(20.dp)) {
            Text("%,d".format(Math.round(shown.value)), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text("notifications in ${r.days} day${if (r.days == 1) "" else "s"} · about ${Math.round(r.perDay)} a day", color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        if (slices.isNotEmpty()) AccessDonut(slices, colors, pick) { pick = it }
    }
}

/** One app: a bar for how many it sent, and how often you open them, with a verdict. */
@Composable
private fun SignalRow(a: AppSignal, max: Int, onClick: () -> Unit) {
    val verdictColor = a.verdict.tint
    Row(Modifier.fillMaxWidth().pressScale(0.98f).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(a.pkg, size = 36.dp); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.app, Modifier.weight(1f), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(a.verdict.label, Modifier.clip(RoundedCornerShape(50)).background(verdictColor.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = verdictColor, fontWeight = FontWeight.SemiBold)
            }
            GooeyProgress({ a.count / max.toFloat() }, Modifier.fillMaxWidth().padding(vertical = 4.dp).height(6.dp), live = false)
            Text("${a.count} sent" + (a.tapRate?.let { " · you open ${Math.round(it * 100)}%" } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PersonRow(p: Person, max: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(36.dp).height(36.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Text(p.name.trim().take(1).uppercase(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            GooeyProgress({ p.count / max.toFloat() }, Modifier.fillMaxWidth().padding(vertical = 4.dp).height(5.dp), color = MaterialTheme.colorScheme.secondary, live = false)
            Text("${p.count} messages · ${p.apps.joinToString()}" + (p.medianOpenMs?.let { " · you open in ~${openTime(it)}" } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun openTime(ms: Long) = when { ms < 60_000 -> "${(ms / 1000).coerceAtLeast(1)}s"; ms < 3_600_000 -> "${ms / 60_000} min"; else -> "${ms / 3_600_000}h" }

/** 24 bars that grow in; tap one for that hour's count. */
@Composable
private fun HourBars(hours: IntArray) {
    val grow = remember(hours.contentHashCode()) { Animatable(0f) }
    val motion = motionOn()
    LaunchedEffect(hours.contentHashCode()) { if (motion) grow.animateTo(1f, tween(800, easing = FastOutSlowInEasing)) else grow.snapTo(1f) }
    val peak = hours.indices.maxByOrNull { hours[it] } ?: 0
    var picked by remember { mutableIntStateOf(-1) }
    val shown = if (picked >= 0) picked else peak
    val haptics = LocalHapticFeedback.current
    val bar = MaterialTheme.colorScheme.primary; val hot = MaterialTheme.colorScheme.tertiary; val dim = MaterialTheme.colorScheme.surfaceVariant
    Card {
        Canvas(Modifier.fillMaxWidth().height(110.dp).pointerInput(Unit) {
            detectTapGestures { o -> val h = (o.x / (size.width / 24f)).toInt().coerceIn(0, 23); haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); picked = h }
        }) {
            val w = size.width / 24f; val max = (hours.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
            hours.forEachIndexed { h, n ->
                val hgt = (size.height * n / max * grow.value).coerceAtLeast(3.dp.toPx())
                drawRoundRect(if (h == shown) hot else if (n > 0) bar else dim, Offset(h * w + 2.dp.toPx(), size.height - hgt), Size(w - 4.dp.toPx(), hgt), CornerRadius(3.dp.toPx()))
            }
        }
        Row { listOf("12am", "6am", "12pm", "6pm", "").forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) } }
        Text("${NotifyMath.clock(shown)}: ${hours[shown]} notification${if (hours[shown] == 1) "" else "s"}" + if (shown == peak) " (the busiest hour)" else "", fontWeight = FontWeight.Medium)
    }
}

/** What to do about one app: quiet it in Android, or stop keeping its history. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppSheet(a: AppSignal, log: NotificationLog, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(a.pkg, size = 48.dp); Spacer(Modifier.width(14.dp))
                Column { Text(a.app, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(a.verdict.label, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text(buildString {
                append("${a.count} notifications. ")
                if (a.decided > 0) append("You opened ${a.tapped} and swiped away ${a.dismissed}.") else append("Opened or swiped ones are counted from now on.")
                if (a.verdict == Verdict.Noise) append(" Turning it off, or off for its noisiest kind, costs you almost nothing.")
            }, style = MaterialTheme.typography.bodyMedium)
            Button({
                val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, a.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(i) }
            }, Modifier.fillMaxWidth()) { Text("Quiet ${a.app} in Android") }
            OutlinedButton({ log.excluded = log.excluded + a.pkg; log.deleteApp(a.pkg); onDismiss() }, Modifier.fillMaxWidth()) { Text("Stop keeping its history") }
            Text("Android lets you turn off each kind of notification an app sends, so you can keep messages and drop the promotions.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

/** A colour that stays readable for a verdict chip in either theme. */
private val Verdict.tint: Color @Composable get() = when (this) { Verdict.Worth -> MaterialTheme.colorScheme.primary; Verdict.Noise -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.outline }
