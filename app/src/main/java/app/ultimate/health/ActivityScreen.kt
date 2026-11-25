package app.ultimate.health

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** The three ring colours, the same family Samsung Health uses so the two read alike side by side. */
object ActivityColors {
    val Steps = Color(0xFF6CD62E)
    val Active = Color(0xFF1EB4F5)
    val Kcal = Color(0xFFA855F7)
}

/** The activity numbers for a screen, kept fresh: on opening, on coming back to the app, and every two minutes while it is up. */
@Stable
class ActivityFeed {
    var data by mutableStateOf(ActivityRepo.latest())
    internal var tick by mutableIntStateOf(0)
    internal var forced = false
    /** [force] also re-reads the weeks behind today; leave it off for the routine refreshes. */
    fun refresh(force: Boolean = false) { if (force) forced = true; tick++ }
}

@Composable
fun rememberActivityFeed(): ActivityFeed {
    val c = LocalContext.current
    val feed = remember { ActivityFeed() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { feed.refresh() }
    LaunchedEffect(feed.tick) {
        // Today shows at once; the usual day and the week fill in behind it.
        val force = feed.forced; feed.forced = false
        feed.data = ActivityRepo.load(c, history = false)
        feed.data = ActivityRepo.load(c, history = true, force = force)
    }
    LaunchedEffect(Unit) { while (true) { delay(120_000); feed.refresh() } }
    return feed
}

/** Three rings, outermost first: steps, active time, active calories. Each fills to its goal (1.0); past that it laps in a lighter shade. */
@Composable
fun ActivityRings(steps: Float?, active: Float?, kcal: Float?, modifier: Modifier = Modifier, stroke: Dp = 14.dp, content: @Composable BoxScope.() -> Unit = {}) {
    // The rings sweep up from empty the first time they appear, and glide when a number changes.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val fills = listOf(steps, active, kcal).map { v -> animateFloatAsState(if (shown) (v ?: 0f) else 0f, tween(1000, easing = FastOutSlowInEasing), label = "ring").value }
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val colors = listOf(ActivityColors.Steps, ActivityColors.Active, ActivityColors.Kcal)
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx(); val gap = w * 0.35f
            for (i in 0 until 3) {
                val inset = w / 2 + i * (w + gap)
                val d = size.minDimension - inset * 2
                if (d <= 0f) continue
                val topLeft = Offset((size.width - d) / 2, (size.height - d) / 2); val box = Size(d, d)
                drawArc(track, 0f, 360f, false, topLeft, box, style = Stroke(w))
                val f = fills[i]; val base = colors[i]
                if (f > 0.002f) drawArc(base, -90f, minOf(f, 1f) * 360f, false, topLeft, box, style = Stroke(w, cap = StrokeCap.Round))
                if (f > 1f) drawArc(lerp(base, Color.White, 0.40f), -90f, (minOf(f, 2f) - 1f) * 360f, false, topLeft, box, style = Stroke(w, cap = StrokeCap.Round))
                if (f > 2f) drawArc(lerp(base, Color.White, 0.70f), -90f, (minOf(f, 3f) - 2f) * 360f, false, topLeft, box, style = Stroke(w, cap = StrokeCap.Round))
            }
        }
        content()
    }
}

/** The Activity page in You: today's rings against your goals, your day beside your usual day, the walks it was made of, and the week. */
@Composable
fun ActivityScreen(onOpenHealth: () -> Unit) {
    val c = LocalContext.current
    val feed = rememberActivityFeed()
    val ask = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { feed.refresh() }
    var editGoals by remember { mutableStateOf(false) }
    val d = feed.data
    ActivityContent(d, onAllow = { ask.launch(ActivityRepo.permissions) }, onGoals = { editGoals = true }, onRefresh = { feed.refresh(force = it) }, onOpenHealth = onOpenHealth)
    if (editGoals) d?.let { GoalsDialog(it.goals, { editGoals = false }) { g -> ActivityStore(c).setGoals(g); editGoals = false; feed.refresh() } }
}

/** The page itself, from numbers already read: no Health Connect in here, so it can be drawn from made-up days. */
@Composable
fun ActivityContent(d: ActivityLoaded?, onAllow: () -> Unit, onGoals: () -> Unit, onRefresh: (force: Boolean) -> Unit, onOpenHealth: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            d == null -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Reading your steps from Health Connect…", style = MaterialTheme.typography.bodySmall) }
            d.status == ActivityLoaded.Status.Unavailable -> Panel("Health Connect isn't available") {
                Text("Your watch's steps reach this phone through Health Connect (built into Android 14 and later; on older phones, install it from the Play Store).", style = MaterialTheme.typography.bodyMedium)
            }
            d.status == ActivityLoaded.Status.NeedsPermission -> Panel("See your day of movement") {
                Text("Allow Ultimate to read your steps, distance, calories and workouts from Health Connect (where Samsung Health puts what your watch records). It only reads, and nothing leaves this phone.", style = MaterialTheme.typography.bodyMedium)
                Button(onAllow, Modifier.fillMaxWidth()) { Text("Allow") }
            }
            d.day == null -> Panel("No steps today yet") {
                Text("Health Connect has no steps for today. If your watch records them, open Samsung Health so it can pass them on, then come back.", style = MaterialTheme.typography.bodyMedium)
                TextButton({ onRefresh(false) }) { Text("Refresh") }
            }
            else -> {
                val day = d.day
                Hero(d, day)
                PaceCard(d, day)
                HoursCard(day, nowMin(d) / 60)
                Walks(d, day)
                d.week?.let { WeekCard(it, d) }
                if (!d.canWorkouts || !d.canTotal) Panel("Add more") {
                    Text("Workouts name your walks, and total calories gives the whole day's burn. Both are optional.", style = MaterialTheme.typography.bodySmall)
                    Button(onAllow) { Text("Allow workouts and total calories") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onGoals) { Text("Goals") }
                    TextButton({ onRefresh(true) }) { Text("Refresh") }
                    TextButton(onOpenHealth) { Text("All your health →") }
                }
                Freshness(d, day)
            }
        }
    }
}

/** The minute of the day the numbers were read at: what "now" means for everything drawn from them. */
private fun nowMin(d: ActivityLoaded): Int = Instant.ofEpochMilli(d.readAt).atZone(ZoneId.systemDefault()).let { it.hour * 60 + it.minute }

// --- parts --------------------------------------------------------------------------------------------------------------------

@Composable
private fun Panel(title: String?, body: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        body()
    }
}

@Composable
private fun Hero(d: ActivityLoaded, day: ActivityMath.Day) {
    val g = d.goals
    val (s, a, k) = day.rings(g)
    Panel(null) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ActivityRings(s, a, k, Modifier.size(210.dp), stroke = 15.dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("%,d".format(day.steps), fontSize = 34.sp, fontWeight = FontWeight.Black)
                    Text("steps", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(ActivityMath.headline(day, g), Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RingTile("Steps", "%,d".format(day.steps), "/%,d".format(g.steps), ActivityColors.Steps, s)
            RingTile("Active time", day.activeMin?.let { ActivityMath.minutesWords(it) } ?: "—", "/${g.activeMin} min", ActivityColors.Active, a)
            RingTile("Active calories", day.activeKcal?.let { "%,d".format(it.roundToInt()) + " Cal" } ?: "—", "/${g.kcal}", ActivityColors.Kcal, k)
        }
        day.totalKcal?.let { Line("Total burned calories", "%,d Cal".format(it.roundToInt())) }
        day.metres?.let { Line("Distance while active", ActivityMath.distance(it, d.miles)) }
        day.floors?.takeIf { it > 0 }?.let { Line("Floors", "$it") }
        if (day.activeMin != null) Text(
            "Active time is worked out from your steps (a minute with ${ActivityMath.ACTIVE_STEPS_PER_MIN} or more) and your workouts, so it can differ a little from Samsung Health's own count.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RowScope.RingTile(label: String, value: String, goal: String, color: Color, fill: Float?) {
    Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(color.copy(alpha = 0.10f)).padding(horizontal = 10.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(if (fill != null && fill >= 1f) "✓ done" else goal, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** How today is going against your usual day, and where it is likely to end. */
@Composable
private fun PaceCard(d: ActivityLoaded, day: ActivityMath.Day) {
    val now = nowMin(d)
    val p = day.pace ?: return
    val words = ActivityMath.paceWords(p, now) ?: return
    Panel("Against your usual day") {
        Text(words, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        val left = p.projected - day.steps
        if (now < 21 * 60 && left >= 500) Text("At your usual rate you'll finish near ${"%,d".format(p.projected)} steps.", style = MaterialTheme.typography.bodyMedium)
        p.goalEtaMin?.let { Text("You'd likely reach ${"%,d".format(d.goals.steps)} around ${ActivityMath.clock(it)}.", style = MaterialTheme.typography.bodyMedium) }
        day.usual?.let { u ->
            Text("Your usual is the middle of your last ${u.basedOn} ${if (u.sameWeekday) "${LocalDate.ofEpochDay(day.day).dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())}s" else "days"}.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Steps hour by hour, with your usual day drawn over it as a dashed line. Tap an hour to read it. */
@Composable
private fun HoursCard(day: ActivityMath.Day, nowHour: Int) {
    var picked by remember { mutableStateOf<Int?>(null) }
    Panel("Your day by the hour") {
        day.peak?.let { Text("Most active period: ${ActivityMath.hourRange(it)}", style = MaterialTheme.typography.bodyMedium) }
        HourChart(day.hourSteps, day.usual?.hours, nowHour, day.peak, picked) { picked = it }
        Row(Modifier.fillMaxWidth()) { listOf("12 AM", "6 AM", "12 PM", "6 PM").forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        val h = picked
        if (h != null) Text(
            "${ActivityMath.hourRange(h)}: ${"%,d".format(day.hourSteps[h])} steps" + (day.usual?.let { " (usually ${"%,d".format(it.hours[h].roundToInt())})" } ?: ""),
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
        )
        else Text(if (day.usual != null) "Dashed line: your usual day. Tap an hour for its steps." else "After about a week, your usual day is drawn over this so you can see where today differs.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HourChart(hours: IntArray, usual: DoubleArray?, upToHour: Int, peak: Int?, picked: Int?, onPick: (Int?) -> Unit) {
    val bar = ActivityColors.Steps
    val ghost = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val max = maxOf(hours.max(), usual?.max()?.toInt() ?: 0, 200) * 1.12f
    Canvas(
        Modifier.fillMaxWidth().height(150.dp).pointerInput(picked) {
            detectTapGestures { o -> val h = (o.x / (size.width / 24f)).toInt().coerceIn(0, 23); onPick(if (h == picked) null else h) }
        },
    ) {
        val cw = size.width / 24f
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 2f)
        if (picked != null) drawRoundRect(grid, Offset(picked * cw, 0f), Size(cw, size.height), CornerRadius(cw * 0.3f))
        for (h in 0..23) {
            if (h > upToHour) break
            val bh = hours[h] / max * size.height
            if (bh < 1f) continue
            drawRoundRect(if (h == peak) bar else bar.copy(alpha = 0.7f), Offset(h * cw + cw * 0.18f, size.height - bh), Size(cw * 0.64f, bh), CornerRadius(cw * 0.3f))
        }
        if (usual != null) {
            val path = Path()
            for (h in 0..23) { val x = (h + 0.5f) * cw; val y = size.height - (usual[h] / max * size.height).toFloat(); if (h == 0) path.moveTo(x, y) else path.lineTo(x, y) }
            drawPath(path, ghost, style = Stroke(3f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 9f))))
        }
    }
}

/** The day as the walks it was made of. */
@Composable
private fun Walks(d: ActivityLoaded, day: ActivityMath.Day) {
    Panel("Your day in walks") {
        if (day.bursts.isEmpty()) Text("No walk of ${ActivityMath.MIN_BURST_MIN} minutes or more yet today.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        day.bursts.forEach { b ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(b.label, fontWeight = FontWeight.SemiBold)
                    Text("${ActivityMath.clock(b.startMin)} – ${ActivityMath.clock(b.endMin)} · ${ActivityMath.minutesWords(b.minutes)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("%,d steps".format(b.steps), fontWeight = FontWeight.SemiBold)
                    Text(listOfNotNull(b.metres?.let { ActivityMath.distance(it, d.miles) }, "${b.cadence}/min").joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (day.bursts.isNotEmpty()) Text("Found from your steps: stretches of walking with short stops joined up. Distance uses your own stride today.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WeekCard(w: ActivityMath.Week, d: ActivityLoaded) {
    Panel("This week") {
        val top = maxOf(d.goals.steps.toLong(), w.days.maxOf { it.steps ?: 0L }).toFloat()
        Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            w.days.forEach { day ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    val frac = ((day.steps ?: 0L) / top).coerceIn(0f, 1f)
                    Box(
                        Modifier.fillMaxWidth().height((74 * frac).dp.coerceAtLeast(if (day.steps != null) 3.dp else 0.dp)).clip(RoundedCornerShape(6.dp))
                            .background(if (day.closed == true) ActivityColors.Steps else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)),
                    )
                    Text(LocalDate.ofEpochDay(day.day).dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Text("${w.closed} of 7 days at ${"%,d".format(d.goals.steps)} steps" + if (w.streak >= 2) " · ${w.streak} in a row" else "", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        w.best?.takeIf { w.days.count { it.steps != null } >= 3 }?.let { b ->
            Text("Best: ${"%,d".format(b.steps)} on ${LocalDate.ofEpochDay(b.day).dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())}" + (w.avgSteps?.let { " · average ${"%,d".format(it)}" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** How fresh the numbers are, because Samsung Health hands data to Health Connect in batches rather than live. */
@Composable
private fun Freshness(d: ActivityLoaded, day: ActivityMath.Day) {
    val c = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val now = d.readAt
    val up = day.updatedAt
    val src = day.sources.firstOrNull()?.let { sourceName(c, it) }
    if (up == null) { Text("Counted by Health Connect" + (src?.let { ", from $it" } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); return }
    val z = Instant.ofEpochMilli(up).atZone(zone)
    val sameDay = z.toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val age = ActivityMath.ageMin(up, now)
    Text(
        "Steps counted up to ${if (sameDay) "" else "yesterday "}${ActivityMath.clock(z.hour * 60 + z.minute)}" + (src?.let { " · from $it" } ?: "") + ". Merged across your phone and watch, so nothing is counted twice.",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (age >= 120 && nowMin(d) / 60 in 8..22) Text(
        "That's ${ActivityMath.minutesWords(age.toInt())} ago. Your watch passes steps on in batches: open Samsung Health to sync, then Refresh here.",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
    )
}

private fun sourceName(c: android.content.Context, pkg: String): String =
    if (pkg == "com.sec.android.app.shealth") "Samsung Health"
    else runCatching { c.packageManager.getApplicationInfo(pkg, 0).let { c.packageManager.getApplicationLabel(it).toString() } }.getOrDefault(pkg)

@Composable
private fun GoalsDialog(now: ActivityMath.Goals, onDismiss: () -> Unit, onSave: (ActivityMath.Goals) -> Unit) {
    var steps by remember { mutableStateOf(now.steps.toString()) }
    var active by remember { mutableStateOf(now.activeMin.toString()) }
    var kcal by remember { mutableStateOf(now.kcal.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Your goals") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val num = KeyboardOptions(keyboardType = KeyboardType.Number)
                OutlinedTextField(steps, { steps = it.filter(Char::isDigit).take(6) }, label = { Text("Steps a day") }, keyboardOptions = num, singleLine = true)
                OutlinedTextField(active, { active = it.filter(Char::isDigit).take(3) }, label = { Text("Active minutes a day") }, keyboardOptions = num, singleLine = true)
                OutlinedTextField(kcal, { kcal = it.filter(Char::isDigit).take(4) }, label = { Text("Active calories a day") }, keyboardOptions = num, singleLine = true)
                Text("Samsung Health's usual goals are ${"%,d".format(ActivityMath.DEFAULT_STEPS)}, ${ActivityMath.DEFAULT_ACTIVE_MIN} and ${ActivityMath.DEFAULT_KCAL}.", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { TextButton({ onSave(ActivityMath.Goals(steps.toIntOrNull() ?: now.steps, active.toIntOrNull() ?: now.activeMin, kcal.toIntOrNull() ?: now.kcal)) }) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

/** A compact card for Home: the rings, today's steps, and how the day is going against your usual. Tap for the whole page. */
@Composable
fun ActivityHomeCard(d: ActivityLoaded, onOpen: () -> Unit) {
    val day = d.day ?: return
    val (s, a, k) = day.rings(d.goals)
    val now = nowMin(d)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).clickable { onOpen() }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ActivityRings(s, a, k, Modifier.size(68.dp), stroke = 7.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("%,d steps".format(day.steps), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                listOfNotNull(day.metres?.let { ActivityMath.distance(it, d.miles) }, day.activeMin?.let { "${ActivityMath.minutesWords(it)} active" }, day.activeKcal?.let { "${"%,d".format(it.roundToInt())} Cal" }).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
            (ActivityMath.paceWords(day.pace, now) ?: ActivityMath.headline(day, d.goals)).let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 2) }
        }
        Text("→", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}
