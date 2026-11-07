package app.ultimate.health

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.UltimateApp
import app.ultimate.core.Signal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * You, today, in one scroll: how you're doing (a read from sleep, heart, phone use and your mood, with every reason
 * shown), your body (live heart rate from the watch, steps, sleep, resting heart rate, HRV), your phone life (screen
 * time, late nights, notifications), and what Ultimate is learning about you. Everything stays on the phone.
 */
@Composable
fun YouToday(app: UltimateApp) {
    val c = LocalContext.current
    val today = remember { LocalDate.now().toEpochDay() }
    var days by remember { mutableStateOf<List<HealthDay>>(emptyList()) }
    var phone by remember { mutableStateOf<List<PhoneDay>>(emptyList()) }
    var healthOk by remember { mutableStateOf<Boolean?>(null) }
    var mood by remember { mutableStateOf(MoodLog.get(c, today)) }
    val live by LiveHeart.bpm.collectAsStateWithLifecycle()
    val notes = remember { runCatching { app.notificationLog.today() }.getOrNull() }
    // Money and health alerts read out of the last week's notifications.
    var facts by remember { mutableStateOf<List<NotificationFacts.Fact>>(emptyList()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val avail = HealthReader.available(c)
            val granted = if (avail) runCatching { HealthReader.granted(c) }.getOrDefault(emptySet()) else emptySet()
            healthOk = granted.isNotEmpty()
            if (granted.isNotEmpty()) days = runCatching { HealthReader.days(c, granted, 30) }.getOrDefault(emptyList())
            phone = phoneDays(c, 30)
            val weekAgo = System.currentTimeMillis() - 7 * 86_400_000L
            facts = runCatching { app.notificationLog.search("", null, false, limit = 2000) }.getOrDefault(emptyList())
                .filter { it.at >= weekAgo }.mapNotNull { NotificationFacts.read(it.pkg, it.app, it.title, it.text, it.at) }
        }
    }
    val t = days.firstOrNull { it.day == today }
    val y = days.firstOrNull { it.day == today - 1 }
    val sleep = t?.sleepMin ?: y?.sleepMin
    val past = days.filter { it.day in (today - 14) until today }
    val read = MindCheck.read(
        sleep, t?.hrvMs ?: y?.hrvMs, past.mapNotNull { it.hrvMs }.takeIf { it.size >= 3 }?.average(),
        t?.restingHr ?: y?.restingHr, past.mapNotNull { it.restingHr?.toDouble() }.takeIf { it.size >= 3 }?.average(),
        phone.firstOrNull { it.day == today }?.lateMin, notes, null, mood,
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card("How you're doing") {
            Text("How do you feel today?", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MindCheck.MOODS.forEachIndexed { i, (e, name) ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (mood == i) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable {
                            mood = i; MoodLog.set(c, today, i)
                            // Rules can react to how you feel ("when I feel low, …").
                            app.senses.publish(Signal("body", if (i <= 0) "mood_low" else if (i >= 2) "mood_good" else "mood_meh", "You feel ${name.lowercase()}"))
                        }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(e, fontSize = 26.sp); Text(name, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (read != null) {
                Text(read.words, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                LinearProgressIndicator({ read.load / 100f }, Modifier.fillMaxWidth(), color = if (read.load < 55) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                read.factors.forEach { Text((if (it.good) "＋ " else "－ ") + it.text, style = MaterialTheme.typography.bodySmall) }
                Text("An estimate from your own signals, to notice patterns with. Not a medical measure.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else Text("Log your mood, or connect your health data below, and a read on your day appears here.", style = MaterialTheme.typography.bodySmall)
        }
        Card("Your body") {
            val beat = live?.takeIf { System.currentTimeMillis() - it.second < 60_000 }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Stat(beat?.let { "❤️ ${it.first}" } ?: (t?.restingHr ?: y?.restingHr)?.let { "❤️ $it" } ?: "—", if (beat != null) "heart rate now (watch)" else "resting heart rate")
                Stat(t?.steps?.let { "%,d".format(it) } ?: "—", "steps today")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Stat(sleep?.let { HealthInsights.hm(it.toInt()) } ?: "—", "sleep last night")
                Stat((t?.hrvMs ?: y?.hrvMs)?.let { "%.0f ms".format(it) } ?: "—", "heart rate variability")
            }
            facts.filter { it.kind == NotificationFacts.Kind.Heart }.take(2).forEach { f ->
                Text("⌚ ${f.text}", style = MaterialTheme.typography.bodySmall)
            }
            if (healthOk == false) {
                Text("Connect Health Connect (from your Galaxy Watch through Samsung Health) to see steps, sleep, heart rate and more here.", style = MaterialTheme.typography.bodySmall)
                app.tools.firstOrNull { it.id == "health" }?.let { h -> FilledTonalButton({ app.shell.open(h) }) { Text("Connect health data") } }
            } else app.tools.firstOrNull { it.id == "health" }?.let { h -> TextButton({ app.shell.open(h) }) { Text("All your health →") } }
        }
        val money = remember(facts) { NotificationFacts.money(facts) }
        if (money.recent.isNotEmpty()) Card("Your money this week") {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Stat("$%,.2f".format(money.spent), "spent (from notifications)")
                Stat("$%,.2f".format(money.received), "received")
            }
            money.declined.firstOrNull()?.let { Text("⚠️ A payment didn't go through: ${it.text}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            money.bills.firstOrNull()?.let { Text("🧾 ${it.text}", style = MaterialTheme.typography.bodySmall) }
            money.recent.filter { it.kind == NotificationFacts.Kind.Spent || it.kind == NotificationFacts.Kind.Received }.take(3).forEach {
                Text("${if (it.kind == NotificationFacts.Kind.Received) "＋" else "－"} ${it.amount?.let { a -> "$%,.2f".format(a) } ?: ""} · ${it.app}", style = MaterialTheme.typography.bodySmall)
            }
            Text("Only what your banking and payment apps already told you in notifications. Nothing is sent anywhere.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Card("Your phone life") {
            val p = phone.firstOrNull { it.day == today }
            val week = phone.filter { it.day in (today - 7) until today }.map { it.screenMin }.takeIf { it.isNotEmpty() }?.average()
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Stat(p?.let { HealthInsights.hm(it.screenMin.toInt()) } ?: "—", "screen time today" + (week?.let { " · usually ${HealthInsights.hm(it.toInt())}" } ?: ""))
                Stat(notes?.toString() ?: "—", "notifications today")
            }
            p?.lateMin?.takeIf { it > 0 }?.let { Text("${HealthInsights.hm(it.toInt())} on the phone after midnight", style = MaterialTheme.typography.bodySmall) }
        }
        Card("What I'm learning about you") {
            val insights = remember(days, phone) { HealthInsights.find(days, phone).take(3) }
            val moods = MoodLog.all(c).size
            if (insights.isEmpty()) Text("Patterns between your sleep, heart, activity, phone use and mood show up here after about a week of data.", style = MaterialTheme.typography.bodySmall)
            insights.forEach { Column { Text(it.title, fontWeight = FontWeight.SemiBold); Text(it.text, style = MaterialTheme.typography.bodySmall) } }
            Text("$moods day${if (moods == 1) "" else "s"} of mood logged. The more days, the more it can tell you what lifts or drains you.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Card(title: String, body: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        body()
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Stat(value: String, label: String) {
    Column(Modifier.weight(1f)) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The body pulse at the top of Home: your watch's heart rate right now and how you said you feel, with a one-tap mood
 * check-in when you haven't given one today. Tap it for You → Today.
 */
@Composable
fun BodyPulse(app: UltimateApp) {
    val c = LocalContext.current
    val today = remember { LocalDate.now().toEpochDay() }
    var mood by remember { mutableStateOf(MoodLog.get(c, today)) }
    val live by LiveHeart.bpm.collectAsStateWithLifecycle()
    val beat = live?.takeIf { System.currentTimeMillis() - it.second < 60_000 }
    val you = app.tools.firstOrNull { it.id == "timeline" }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
        .clickable { you?.let { app.shell.open(it) } }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (beat != null) { Text("❤️ ${beat.first}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Text(" bpm  ", style = MaterialTheme.typography.labelSmall) }
        val m = mood
        if (m != null) Text("${MindCheck.MOODS[m].first} ${MindCheck.MOODS[m].second} today", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        else {
            Text("How do you feel?", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            MindCheck.MOODS.forEachIndexed { i, (e, name) ->
                Text(e, Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                    mood = i; MoodLog.set(c, today, i)
                    app.senses.publish(Signal("body", if (i <= 0) "mood_low" else if (i >= 2) "mood_good" else "mood_meh", "You feel ${name.lowercase()}"))
                }.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 24.sp)
            }
        }
        if (m != null) Text("You →", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}
