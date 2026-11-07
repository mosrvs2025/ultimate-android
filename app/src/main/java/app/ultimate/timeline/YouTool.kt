package app.ultimate.timeline

import app.ultimate.health.YouToday
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ultimate.BuildConfig
import app.ultimate.UltimateApp
import app.ultimate.core.GooeyTabs
import app.ultimate.core.LibraryItem
import app.ultimate.core.ShellActions
import app.ultimate.core.Tool
import app.ultimate.core.ToolBackButton
import app.ultimate.core.ToolLaunch
import app.ultimate.core.copyText
import app.ultimate.footprint.FootprintPart
import app.ultimate.footprint.FootprintReaders
import app.ultimate.footprint.FootprintScreen
import app.ultimate.footprint.Habits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * You: one place for everything the app knows about you. Days (every record, searchable, day by day), Patterns (your
 * week, body, viewing, places) and Privacy (who can see what), plus a summary you can hand to an AI.
 * It keeps the old Timeline id so links into it still work.
 */
class YouTool(private val app: UltimateApp) : Tool {
    override val id = "timeline"
    override val title = "You"
    override val tagline = "How you're doing: body, mind, days and what your phone knows"
    override val icon = Icons.Rounded.Face
    override val acceptedMimePrefixes = emptyList<String>()
    override val headline: StateFlow<String> = MutableStateFlow("Everything about you, searchable").asStateFlow()
    override val library: StateFlow<List<LibraryItem>> = MutableStateFlow<List<LibraryItem>>(emptyList()).asStateFlow()
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = true
    override suspend fun refresh() = Unit
    @Composable override fun Screen(onExit: () -> Unit, launch: ToolLaunch) = YouScreen(app, launch.query, onExit)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun YouScreen(app: UltimateApp, query: String?, onExit: () -> Unit) {
    // Today first: body, mind and phone life together.
    val tabs = if (BuildConfig.HAS_FOOTPRINT) listOf("Today", "Days", "Patterns", "Privacy") else listOf("Today", "Days")
    // A search lands on Days; otherwise open on Today.
    var tab by remember { mutableIntStateOf(if (query.isNullOrBlank()) 0 else 1) }
    var aboutMe by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("You") }, navigationIcon = { ToolBackButton(onExit) }, actions = {
            TextButton({ aboutMe = true }) { Icon(Icons.Rounded.AutoAwesome, null); Text("  For your AI") }
            ShellActions()
        })
    }) { pad ->
        Column(Modifier.padding(pad)) {
            if (tabs.size > 1) GooeyTabs(tabs, tab, { tab = it }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            when (tabs[tab]) {
                "Today" -> YouToday(app)
                "Days" -> TimelineScreen(app, query.orEmpty(), onExit, embedded = true)
                "Patterns" -> FootprintScreen(app, onExit, part = FootprintPart.Patterns)
                else -> FootprintScreen(app, onExit, part = FootprintPart.Privacy)
            }
        }
    }
    if (aboutMe) AboutMeSheet(app) { aboutMe = false }
}

/** The "About me" summary: read it, then copy it or share it with an AI app. Nothing is sent unless you do. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutMeSheet(app: UltimateApp, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        text = withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val all = TimelineReader.read(app, now - 30 * 86_400_000L)
            val usage = if (BuildConfig.HAS_FOOTPRINT) runCatching { FootprintReaders.usage(app) }.getOrNull() else null
            val label = { pkg: String -> FootprintReaders.label(app, pkg) }
            val rhythm = usage?.let { Habits.sentences(it, label) }.orEmpty()
            val top = usage?.opens?.entries?.sortedByDescending { it.value }?.take(6)?.map { label(it.key) }.orEmpty()
            AboutMe.build(all, rhythm, top, now)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("About me, for your AI", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Give this to ChatGPT, Claude, Gemini or Ultimate's own Ask AI so it knows how you live. It's made on your phone from your own records, and goes nowhere unless you share it. Read it first.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text ?: "Writing it from your records…", Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).verticalScroll(rememberScrollState()).padding(14.dp),
                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            val t = text
            if (t != null) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button({ copyText(context, "About me", t) }, Modifier.weight(1f)) { Text("Copy") }
                OutlinedButton({ context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), "Share with")) }, Modifier.weight(1f)) { Text("Share") }
            }
        }
    }
}
