package app.ultimate.social

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ultimate.core.GlassCard
import app.ultimate.core.GooeySwitch
import app.ultimate.core.LibraryItem
import app.ultimate.core.Liquid
import app.ultimate.core.LiquidButton
import app.ultimate.core.LiquidInput
import app.ultimate.core.Tool
import app.ultimate.core.ToolLaunch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Walk with friends: when your watch says you've started walking, tell the friends you choose, in a chat they are already in
 * (Discord, Telegram or ntfy), so they can walk with you from wherever they are. Off until you turn it on.
 */
class FriendsTool(context: Context) : Tool {
    private val app = context.applicationContext
    override val id = "friends"
    override val title = "Walk with friends"
    override val tagline = "Tell your friends in Discord or Telegram when you start walking"
    override val icon = Icons.Rounded.DirectionsWalk
    override val headline: StateFlow<String> = MutableStateFlow("Off · nothing is shared until you turn it on")
    override val library: StateFlow<List<LibraryItem>> = MutableStateFlow(emptyList())
    override val acceptedMimePrefixes: List<String> = emptyList()
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = true
    override suspend fun refresh() = Unit

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable
    override fun Screen(onExit: () -> Unit, launch: ToolLaunch) {
        val ctx = LocalContext.current
        val store = remember { FriendsStore(ctx) }
        var tick by remember { mutableIntStateOf(0) }
        val settings = remember(tick) { store.settings }
        val circles = remember(tick) { store.circles() }
        var name by remember { mutableStateOf(store.name) }
        val scope = rememberCoroutineScope()
        var note by remember { mutableStateOf<String?>(null) }
        fun save(s: AnnounceSettings) { store.settings = s; tick++ }

        Scaffold(containerColor = Liquid.bg, topBar = {
            TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = Liquid.bg, titleContentColor = Color.White, navigationIconContentColor = Color.White),
                title = { Text("Walk with friends") }, navigationIcon = { IconButton(onExit) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
        }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                GlassCard(title = "Tell friends when I start walking", live = settings.enabled, accent = Liquid.teal) {
                    Text("When your watch says you've started walking, a short message goes to the chats you pick below: \"Maurice is going for a walk. Join them!\". Friends tap it in Discord or Telegram and walk along, wherever they are.",
                        color = Liquid.dim, fontSize = 14.sp, lineHeight = 20.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (settings.enabled) "On" else "Off", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        GooeySwitch(settings.enabled, { on -> if (on && (circles.isEmpty() || store.name.isBlank())) note = "Add your name and a chat first." else save(settings.copy(enabled = on)) })
                    }
                    Text("Only a message with your name and \"walk\" or \"run\" is sent. Never where you are, how far, or your heart rate.", color = Liquid.dim, fontSize = 12.sp)
                    note?.let { Text(it, color = Color(0xFFFFB4A9), fontSize = 13.sp) }
                }

                GlassCard(title = "Your name in the message", accent = Liquid.violet) {
                    LiquidInput(name, { name = it }, "How friends know you", { store.name = name; name = store.name; note = null; tick++ }, sendLabel = "Save", sendEnabled = name.isNotBlank() && name != store.name)
                }

                GlassCard(title = "Chats to tell", accent = Liquid.teal) {
                    if (circles.isEmpty()) Text("None yet. Add one below.", color = Liquid.dim, fontSize = 13.sp)
                    circles.forEach { c ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x14FFFFFF)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${c.target.label} · ${kind(c.target)}", color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                GooeySwitch(c.id in settings.notify, { on -> save(settings.copy(notify = if (on) settings.notify + c.id else settings.notify - c.id)) })
                            }
                            Text(if (c.id in settings.notify) "Told when I walk" else "Not told", color = Liquid.dim, fontSize = 12.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text("Send a test", color = Liquid.teal, fontSize = 13.sp, modifier = Modifier.clickable {
                                    note = "Sending…"
                                    scope.launch { val r = withContext(Dispatchers.IO) { FriendsHub.send(c.target, "✅ Ultimate is connected" + (store.name.takeIf { it.isNotBlank() }?.let { " for ${Delivery.safe(it, 24)}" } ?: "") + ". Walk announcements will appear here.") }
                                        note = if (r.ok) "Sent. Check the chat." else r.error }
                                })
                                if (c.target is Target.Discord) Text("Share link with friends", color = Liquid.teal, fontSize = 13.sp, modifier = Modifier.clickable {
                                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,
                                        "To announce your walks to our Discord channel from Ultimate+, add this link under Walk with friends > Add a chat > Discord (keep it private, it can post to the channel):\n${(c.target as Target.Discord).url}"), "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
                                Text("Remove", color = Color(0xFFFFB4A9), fontSize = 13.sp, modifier = Modifier.clickable { store.remove(c.id); tick++ })
                            }
                        }
                    }
                }

                AddChat(onAdd = { t -> store.add(t); tick++; note = "Added. Send a test to check it works." }, bad = { note = it })

                GlassCard(title = "How and when", accent = Liquid.violet) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("Ask me first", color = Color.White, fontSize = 15.sp); Text("A notification with Tell them / Not this time, instead of sending by itself.", color = Liquid.dim, fontSize = 12.sp) }
                        GooeySwitch(settings.askFirst, { save(settings.copy(askFirst = it)) })
                    }
                    Text("Only after I've walked steadily for", color = Color.White, fontSize = 14.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(2, 4, 8, 15).forEach { m -> Pill("$m min", settings.steadyMinutes == m) { save(settings.copy(steadyMinutes = m)) } } }
                    Text("At most once every", color = Color.White, fontSize = 14.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(60 to "1 hour", 180 to "3 hours", 720 to "12 hours").forEach { (m, l) -> Pill(l, settings.cooldownMinutes == m) { save(settings.copy(cooldownMinutes = m)) } } }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("Quiet hours", color = Color.White, fontSize = 15.sp); Text("Nothing sent from 10 pm to 7 am.", color = Liquid.dim, fontSize = 12.sp) }
                        GooeySwitch(settings.quietFromMin != settings.quietToMin, { on -> save(settings.copy(quietFromMin = if (on) 22 * 60 else 0, quietToMin = if (on) 7 * 60 else 0)) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("Say when I finish", color = Color.White, fontSize = 15.sp); Text("\"Maurice finished a walk: 31 min\" after you stop.", color = Liquid.dim, fontSize = 12.sp) }
                        GooeySwitch(store.summary, { store.summary = it; tick++ })
                    }
                    val priv = settings.privateUntil > System.currentTimeMillis()
                    Text(if (priv) "Private until ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(settings.privateUntil))}" else "Go private (pause for a while)", color = if (priv) Liquid.teal else Color.White, fontSize = 14.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (priv) Pill("Back on", false) { save(settings.copy(privateUntil = 0L)) }
                        else listOf(1 to "1 hour", 4 to "4 hours", 24 to "A day").forEach { (h, l) -> Pill(l, false) { save(settings.copy(privateUntil = System.currentTimeMillis() + h * 3_600_000L)) } }
                    }
                }
            }
        }
    }

    private fun kind(t: Target) = when (t) { is Target.Discord -> "webhook"; is Target.Ntfy -> "topic"; is Target.Telegram -> "bot" }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun AddChat(onAdd: (Target) -> Unit, bad: (String) -> Unit) {
        var type by remember { mutableStateOf("discord") }
        var a by remember { mutableStateOf("") }; var b by remember { mutableStateOf("") }; var label by remember { mutableStateOf("") }
        GlassCard(title = "Add a chat", accent = Liquid.violet) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Discord", type == "discord") { type = "discord"; a = ""; b = "" }; Pill("Telegram", type == "telegram") { type = "telegram"; a = ""; b = "" }; Pill("ntfy", type == "ntfy") { type = "ntfy"; a = ""; b = "" }
            }
            Text(when (type) {
                "discord" -> "In your Discord server: channel settings > Integrations > Webhooks > New Webhook > Copy Webhook URL. Paste it here. It can only post to that channel."
                "telegram" -> "Make your own bot with @BotFather, add it to your group, and paste its token and the group's chat id. The token is kept sealed on this phone."
                else -> "Pick a private topic name (like ultimate-walks-4f9c) and paste its link, https://ntfy.sh/<topic>. Friends subscribe to it in the ntfy app."
            }, color = Liquid.dim, fontSize = 13.sp, lineHeight = 19.sp)
            LiquidInput(a, { a = it }, when (type) { "discord" -> "Webhook link"; "telegram" -> "Bot token"; else -> "Topic link" }, {}, sendEnabled = false, sendLabel = "")
            if (type == "telegram") LiquidInput(b, { b = it }, "Chat id (like -1001234567890)", {}, sendEnabled = false, sendLabel = "")
            LiquidInput(label, { label = it }, "A name for this chat (optional)", {}, sendEnabled = false, sendLabel = "")
            LiquidButton("Add", {
                val l = Delivery.safe(label, 20).ifBlank { type.replaceFirstChar { it.uppercase() } }
                val t = when (type) { "discord" -> Target.Discord(a.trim(), l); "telegram" -> Target.Telegram(a.trim(), b.trim(), l); else -> Target.Ntfy(a.trim(), l) }
                val p = Delivery.problem(t)
                if (p != null) bad(p) else { onAdd(t); a = ""; b = ""; label = "" }
            }, Modifier.fillMaxWidth(), enabled = a.isNotBlank())
        }
    }

    @Composable
    private fun Pill(text: String, on: Boolean, onClick: () -> Unit) {
        Text(text, color = if (on) Color(0xFF0A0E14) else Color.White, fontSize = 14.sp,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Liquid.teal else Color(0x22FFFFFF)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp))
    }
}
