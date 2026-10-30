package app.ultimate.rules

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import app.ultimate.BuildConfig
import app.ultimate.UltimateApp
import app.ultimate.core.BackBar
import app.ultimate.core.LibraryItem
import app.ultimate.core.Tool
import app.ultimate.core.ToolLaunch
import app.ultimate.core.pressScale
import app.ultimate.core.relativeTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class RulesTool(private val app: UltimateApp) : Tool {
    override val id = "rules"
    override val title = "Rules"
    override val tagline = "When this happens, do that"
    override val icon = Icons.Rounded.Bolt
    override val acceptedMimePrefixes = emptyList<String>()
    override val headline: StateFlow<String> = MutableStateFlow("When this, do that").asStateFlow()
    override val library: StateFlow<List<LibraryItem>> = MutableStateFlow<List<LibraryItem>>(emptyList()).asStateFlow()
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = true
    override suspend fun refresh() = Unit
    @Composable override fun Screen(onExit: () -> Unit, launch: ToolLaunch) = RulesScreen(app, onExit)
}

/** Sentences that become rules when tapped, through the same parser as anything you type. */
private val IDEAS = listOf(
    "Every night at 10pm, silence the phone and turn on do not disturb",
    "At 7am on weekdays, turn off do not disturb and set the volume to 50%",
    "When I connect my headphones, set the volume to 40 and put it back when I disconnect",
    "When the battery drops below 15%, show a notification saying \"Battery at %battery% - time to charge\"",
    "While I'm plugged in, turn on do not disturb",
    "When I copy a link, show a notification saying \"Link kept in your clipboard history\"",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RulesScreen(app: UltimateApp, onExit: () -> Unit) {
    val context = LocalContext.current
    val engine = app.rules
    val rules by engine.rules.collectAsStateWithLifecycle()
    val log by engine.log.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf<RuleParser.Result?>(null) }
    var editing by remember { mutableStateOf<Rule?>(null) }
    var perms by remember { mutableStateOf(Perms.read(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { perms = Perms.read(context) }
    val appName = remember { { pkg: String -> AppNames.label(context, pkg) } }

    fun ask(sentence: String) {
        answer = RuleParser.parse(sentence, { AppNames.find(context, it) })
        app.insights.count("rules.parse." + if (answer is RuleParser.Result.Ok) "ok" else "unclear")
        if (answer is RuleParser.Result.Unclear) app.insights.unmatched("rule: $sentence")
    }

    Scaffold(topBar = { BackBar(onExit, "Rules") }) { pad ->
        LazyColumn(Modifier.padding(pad).imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        text, { text = it; if (answer != null) answer = null }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
                        placeholder = { Text("Describe a rule, e.g. when I plug in the charger, turn on Do Not Disturb") }, minLines = 2,
                        trailingIcon = { IconButton({ ask(text) }, enabled = text.isNotBlank()) { Icon(Icons.Rounded.ArrowUpward, "Make a rule") } },
                    )
                    when (val a = answer) {
                        is RuleParser.Result.Unclear -> Text(a.why, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        is RuleParser.Result.Ok -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Is this right?", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            Text(Describe.rule(a.rule, appName), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            a.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button({ engine.add(a.rule); app.insights.count("rules.add.sentence"); text = ""; answer = null }) { Text("Add rule") }
                                OutlinedButton({ editing = a.rule; text = ""; answer = null }) { Text("Change it") }
                            }
                        }
                        null -> Unit
                    }
                }
            }
            if (rules.isEmpty() || text.isBlank()) item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (rules.isEmpty()) "Try one of these" else "More ideas", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IDEAS.forEach { idea -> androidx.compose.material3.AssistChip({ text = idea; ask(idea) }, { Text(idea.substringBefore(',').substringBefore(" and put").take(36), maxLines = 1) }) }
                    }
                }
            }
            val needs = perms.needs(rules)
            if (needs.isNotEmpty()) item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.tertiaryContainer).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Needs your OK", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    needs.forEach { n -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(n.why, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        TextButton({ context.startActivity(n.intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Allow") }
                    } }
                }
            }
            items(rules, key = { it.id }) { r -> RuleCard(r, engine.lastRun(r.id), appName, { engine.setEnabled(r.id, it) }, { engine.test(r) }, { editing = r }, { engine.delete(r.id) }) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ editing = Rule(UUID.randomUUID().toString(), "", true, Trigger(TriggerType.Charging), false, listOf(Action(ActionType.Notify, "Hello from a rule"))) }) {
                        Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Build a rule by hand")
                    }
                }
            }
            if (log.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("What happened", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        TextButton({ engine.clearLog() }) { Text("Clear") }
                    }
                }
                items(log.take(30), key = { "${it.at}-${it.ruleId}" }) { r ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text("${r.ruleName.take(40)} · ${relativeTime(r.at)}", style = MaterialTheme.typography.labelLarge)
                        Text("Because ${r.because}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(r.result, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    editing?.let { r ->
        RuleEditor(r, isNew = rules.none { it.id == r.id }, onSave = { saved ->
            if (rules.any { it.id == saved.id }) engine.update(saved) else engine.add(saved)
            app.insights.count("rules.add.editor"); editing = null
        }, onDismiss = { editing = null })
    }
}

@Composable
private fun RuleCard(r: Rule, last: RunRecord?, appName: (String) -> String, onEnabled: (Boolean) -> Unit, onTest: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).clickable(onClick = onEdit).padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.name.ifBlank { "Rule" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Describe.rule(r, appName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(r.enabled, onEnabled)
            Column {
                IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Try it now") }, { menu = false; onTest() })
                    DropdownMenuItem({ Text("Change") }, { menu = false; onEdit() })
                    DropdownMenuItem({ Text("Delete") }, { menu = false; onDelete() })
                }
            }
        }
        Text(last?.let { "Last ran ${relativeTime(it.at)}: ${it.result}" } ?: "Hasn't run yet", Modifier.padding(top = 4.dp, end = 12.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleEditor(start: Rule, isNew: Boolean, onSave: (Rule) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(start.name) }
    var t by remember { mutableStateOf(start.trigger) }
    var appField by remember { mutableStateOf(if (start.trigger.app.isEmpty()) "" else AppNames.label(context, start.trigger.app)) }
    var undo by remember { mutableStateOf(start.undoWhenEnds) }
    var actions by remember { mutableStateOf(start.actions) }
    var actionApps by remember { mutableStateOf(start.actions.map { if (it.type == ActionType.OpenApp) AppNames.label(context, it.text) else "" }) }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    val types = TriggerType.values().filter { it != TriggerType.Wifi || BuildConfig.HAS_NETWORK }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (isNew) "New rule" else "Change rule", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name (optional)") }, singleLine = true)

            Text("When", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Pick(types.map { it.label }, types.indexOf(t.type)) { i -> t = Trigger(types[i]); undo = false }
            when (t.type) {
                TriggerType.Charging, TriggerType.Wifi, TriggerType.Headphones -> {
                    val (a, b) = when (t.type) { TriggerType.Charging -> "Plugs in" to "Unplugs"; TriggerType.Wifi -> "Connects" to "Disconnects"; else -> "Connect" to "Disconnect" }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(t.on, { t = t.copy(on = true) }, { Text(a) }); FilterChip(!t.on, { t = t.copy(on = false) }, { Text(b) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Put things back when it ends", Modifier.weight(1f)); Switch(undo, { undo = it })
                    }
                }
                TriggerType.BatteryBelow -> {
                    Text("Below ${t.percent}%")
                    Slider(t.percent.toFloat(), { t = t.copy(percent = it.toInt()) }, valueRange = 5f..50f, steps = 8)
                }
                TriggerType.AtTime -> {
                    OutlinedButton({ picking = true }) { Text(Describe.time(t.minutes)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        listOf(2 to "Mon", 3 to "Tue", 4 to "Wed", 5 to "Thu", 6 to "Fri", 7 to "Sat", 1 to "Sun").forEach { (d, label) ->
                            FilterChip(d in t.days, { t = t.copy(days = if (d in t.days) t.days - d else t.days + d) }, { Text(label) })
                        }
                    }
                    Text(if (t.days.isEmpty()) "Every day" else Describe.days(t.days).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Runs within a few minutes of the time, since Android saves battery by batching.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
                TriggerType.Notification -> {
                    OutlinedTextField(appField, { appField = it }, Modifier.fillMaxWidth(), label = { Text("From app (empty for any)") }, singleLine = true)
                    OutlinedTextField(t.contains, { t = t.copy(contains = it) }, Modifier.fillMaxWidth(), label = { Text("Containing (optional)") }, singleLine = true)
                }
                TriggerType.Copied -> OutlinedTextField(t.contains, { t = t.copy(contains = it) }, Modifier.fillMaxWidth(), label = { Text("Containing (e.g. http for links; empty for anything)") }, singleLine = true)
            }

            Text("Do", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            actions.forEachIndexed { i, a ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { Pick(ActionType.values().map { it.label }, a.type.ordinal) { k -> actions = actions.toMutableList().also { it[i] = Action(ActionType.values()[k], value = if (ActionType.values()[k] == ActionType.Volume) 50 else if (ActionType.values()[k] == ActionType.Ringer) 0 else 1) } } }
                        IconButton({ actions = actions.filterIndexed { j, _ -> j != i }; actionApps = actionApps.filterIndexed { j, _ -> j != i } }) { Icon(Icons.Rounded.Close, "Remove") }
                    }
                    ActionParams(a, actionApps.getOrElse(i) { "" }, { na -> actions = actions.toMutableList().also { it[i] = na } }, { v -> actionApps = actionApps.toMutableList().also { while (it.size <= i) it.add(""); it[i] = v } })
                }
            }
            TextButton({ actions = actions + Action(ActionType.Notify, "Hello"); actionApps = actionApps + "" }) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Add something to do") }
            Text("Words you can use in texts: " + Vars.NAMES.joinToString(" ") { "%$it" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({
                    var trigger = t
                    if (t.type == TriggerType.Notification) trigger = t.copy(app = if (appField.isBlank()) "" else AppNames.find(context, appField) ?: run { error = "I can't find an app called \"$appField\"."; return@Button })
                    if (actions.isEmpty()) { error = "Add at least one thing to do."; return@Button }
                    val fixed = actions.mapIndexed { i, a ->
                        when {
                            a.type == ActionType.OpenApp -> Action(a.type, AppNames.find(context, actionApps.getOrElse(i) { "" }) ?: run { error = "I can't find an app called \"${actionApps.getOrElse(i) { "" }}\"."; return@Button })
                            a.type in setOf(ActionType.Notify, ActionType.Speak, ActionType.Copy, ActionType.OpenLink) && a.text.isBlank() -> { error = "${a.type.label} needs some text."; return@Button }
                            a.type == ActionType.OpenLink && !a.text.startsWith("http") -> a.copy(text = "https://${a.text}")
                            else -> a
                        }
                    }
                    onSave(start.copy(name = name.ifBlank { Describe.trigger(trigger).replaceFirstChar { it.uppercase() } }, trigger = trigger, actions = fixed, undoWhenEnds = undo && trigger.type.stateful))
                }) { Text("Save") }
                TextButton(onDismiss) { Text("Cancel") }
            }
        }
    }
    if (picking) {
        val state = rememberTimePickerState(t.minutes / 60, t.minutes % 60, is24Hour = android.text.format.DateFormat.is24HourFormat(context))
        AlertDialog(onDismissRequest = { picking = false }, confirmButton = { TextButton({ t = t.copy(minutes = state.hour * 60 + state.minute); picking = false }) { Text("OK") } },
            dismissButton = { TextButton({ picking = false }) { Text("Cancel") } }, text = { TimePicker(state) })
    }
}

@Composable
private fun ActionParams(a: Action, appText: String, onChange: (Action) -> Unit, onApp: (String) -> Unit) {
    when (a.type) {
        ActionType.Notify, ActionType.Speak, ActionType.Copy -> OutlinedTextField(a.text, { onChange(a.copy(text = it)) }, Modifier.fillMaxWidth(), label = { Text("Text") })
        ActionType.OpenLink -> OutlinedTextField(a.text, { onChange(a.copy(text = it)) }, Modifier.fillMaxWidth(), label = { Text("Address") }, singleLine = true)
        ActionType.OpenApp -> OutlinedTextField(appText, onApp, Modifier.fillMaxWidth(), label = { Text("App name") }, singleLine = true)
        ActionType.Volume -> { Text("${a.value}%"); Slider(a.value.toFloat(), { onChange(a.copy(value = it.toInt())) }, valueRange = 0f..100f) }
        ActionType.Ringer -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "Silent", 1 to "Vibrate", 2 to "Normal").forEach { (v, l) -> FilterChip(a.value == v, { onChange(a.copy(value = v)) }, { Text(l) }) }
        }
        ActionType.Dnd, ActionType.Torch -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(a.value == 1, { onChange(a.copy(value = 1)) }, { Text("On") }); FilterChip(a.value == 0, { onChange(a.copy(value = 0)) }, { Text("Off") })
        }
        ActionType.Vibrate -> Unit
    }
}

/** A closed choice that opens a menu: used for the kinds of trigger and action. */
@Composable
private fun Pick(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        OutlinedButton({ open = true }, Modifier.fillMaxWidth()) { Text(options.getOrElse(selected) { "" }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        DropdownMenu(open, { open = false }) { options.forEachIndexed { i, o -> DropdownMenuItem({ Text(o) }, { open = false; onSelect(i) }) } }
    }
}

/** Things a rule needs the person to allow in Android's settings, shown only for rules that use them. */
private class Perms(val dnd: Boolean, val overlay: Boolean, val notifications: Boolean) {
    class Need(val why: String, val intent: (android.content.Context) -> Intent)

    fun needs(rules: List<Rule>): List<Need> {
        val on = rules.filter { it.enabled }
        return buildList {
            if (!dnd && on.any { r -> r.actions.any { it.type == ActionType.Dnd || (it.type == ActionType.Ringer && it.value == 0) } })
                add(Need("Do Not Disturb and silent mode need Do Not Disturb access.") { Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) })
            if (!overlay && on.any { r -> r.actions.any { it.type == ActionType.OpenApp || it.type == ActionType.OpenLink } })
                add(Need("To open apps and links by themselves, allow Display over other apps. Otherwise you get a notification to tap.") { Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${it.packageName}")) })
            if (!notifications && on.any { it.trigger.type == TriggerType.Notification })
                add(Need("Notification rules need notification access, the same one the Notifications tool uses.") { Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) })
        }
    }

    companion object {
        fun read(c: android.content.Context) = Perms(
            c.getSystemService(android.app.NotificationManager::class.java).isNotificationPolicyAccessGranted,
            Settings.canDrawOverlays(c),
            androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName),
        )
    }
}
