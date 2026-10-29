package app.ultimate.recipes

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.UltimateApp
import app.ultimate.core.LibraryItem
import app.ultimate.core.ShellActions
import app.ultimate.core.Tool
import app.ultimate.core.ToolBackButton
import app.ultimate.core.ToolLaunch
import app.ultimate.core.appear
import app.ultimate.core.pressScale
import app.ultimate.core.rememberNotificationConsent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Chains of tools that run in one go, and things Ultimate does on its own. */
class RecipesTool(private val app: UltimateApp) : Tool {
    override val id = "recipes"
    override val title = "Recipes"
    override val tagline = "Chain tools together and automate them"
    override val icon = Icons.Rounded.AutoMode
    override val acceptedMimePrefixes = emptyList<String>()
    override val headline: StateFlow<String> = MutableStateFlow("Several tools, one tap").asStateFlow()
    override val library: StateFlow<List<LibraryItem>> = MutableStateFlow<List<LibraryItem>>(emptyList()).asStateFlow()
    override fun opensOwnScreen(item: LibraryItem) = false
    override suspend fun remove(id: String, deleteFile: Boolean) = true
    override suspend fun refresh() = Unit
    @Composable override fun Screen(onExit: () -> Unit, launch: ToolLaunch) = RecipesScreen(app, onExit)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecipesScreen(app: UltimateApp, onExit: () -> Unit) {
    BackHandler(onBack = onExit)
    val runner = app.recipes
    val state by runner.state.collectAsStateWithLifecycle()
    val custom by runner.custom.collectAsStateWithLifecycle()
    val toolIds = remember { app.tools.map { it.id }.toSet() }
    val recipes = (RecipeRules.builtIns + custom).filter { r -> r.steps.all { it.toolId.isEmpty() || it.toolId in toolIds } }
    var chosen by remember { mutableStateOf<Recipe?>(null) }
    var building by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> chosen?.let { r -> if (uris.isNotEmpty()) runner.run(r, uris) } }
    val seen = remember { mutableSetOf<Any>() }
    val withConsent = rememberNotificationConsent()

    Scaffold(topBar = { TopAppBar(title = { Text("Recipes") }, navigationIcon = { ToolBackButton(onExit) }, actions = { ShellActions() }) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                AnimatedContent(state, label = "run") { s ->
                    when (s) {
                        is RecipeRunner.State.Running -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), shape = RoundedCornerShape(20.dp)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(s.recipe.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("File ${s.file + 1} of ${s.files} · ${s.recipe.steps[s.step].label}")
                                LinearProgressIndicator(progress = { (s.file * s.recipe.steps.size + s.step + 0.5f) / (s.files * s.recipe.steps.size) }, Modifier.fillMaxWidth())
                                TextButton(runner::cancel) { Text("Stop") }
                            }
                        }
                        is RecipeRunner.State.Done -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(20.dp)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${s.recipe.name} · done", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                s.lines.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                                Row { TextButton(app.shell::showLibrary) { Text("Open Library") }; TextButton(runner::dismiss) { Text("OK") } }
                            }
                        }
                        RecipeRunner.State.Idle -> Text("Tap a recipe and choose files. Each step is done by the tool it comes from, one after another.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            itemsIndexed(recipes, key = { _, r -> r.id }) { i, r ->
                Row(
                    Modifier.fillMaxWidth().animateItem().appear(r.id, seen, i).pressScale(0.97f).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .clickable(enabled = state !is RecipeRunner.State.Running) {
                            chosen = r
                            val kinds = r.starts.map { when (it) { Kind.Video -> "video/*"; Kind.Audio -> "audio/*"; Kind.Image -> "image/*" } }
                            withConsent { picker.launch(kinds.toTypedArray()) }
                        }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.size(4.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                            r.steps.forEachIndexed { k, st ->
                                if (k > 0) Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(14.dp).padding(horizontal = 2.dp), tint = MaterialTheme.colorScheme.outline)
                                Text(st.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    if (!r.builtIn) IconButton({ runner.delete(r.id) }) { Icon(Icons.Rounded.Delete, "Delete recipe") }
                }
            }
            item { OutlinedButton({ building = true }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("New recipe") } }
            item { AutomationsCard(app) }
        }
    }
    if (building) Builder(toolIds, onSave = { n, s -> runner.save(n, s); building = false }, onDismiss = { building = false })
}

@Composable
private fun AutomationsCard(app: UltimateApp) {
    val context = LocalContext.current
    val auto = app.automations
    var weekly by remember { mutableStateOf(auto.weeklyCheckup) }
    var screen by remember { mutableStateOf(auto.screenRecordings) }
    val videoPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) { auto.screenRecordings = true; screen = true } }
    val toolIds = remember { app.tools.map { it.id }.toSet() }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("On its own", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if ("checkup" in toolIds) Toggle("Weekly checkup", "Checks your phone every week and tells you only what changed.", weekly) { v -> auto.weeklyCheckup = v; weekly = v }
            if ("squeeze" in toolIds) Toggle("New screen recordings", "Offers to make each new screen recording smaller. Needs access to your videos.", screen) { v ->
                if (!v) { auto.screenRecordings = false; screen = false }
                else if (ContextCompat.checkSelfPermission(context, videoPermission) == PackageManager.PERMISSION_GRANTED) { auto.screenRecordings = true; screen = true }
                else ask.launch(videoPermission)
            }
            Text("Share clean is always on: pick it in any app's share menu.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun Toggle(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.width(12.dp))
    Switch(on, onChange)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Builder(toolIds: Set<String>, onSave: (String, List<Step>) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var steps by remember { mutableStateOf(listOf<Step>()) }
    val problem = RecipeRules.problem(steps)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("New recipe", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
            Text(if (steps.isEmpty()) "Tap steps in the order they should happen." else steps.joinToString("  →  ") { it.label }, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Step.entries.filter { it.toolId.isEmpty() || it.toolId in toolIds }.forEach { s ->
                    val fits = RecipeRules.problem(steps + s) == null
                    FilterChip(false, { steps = steps + s }, { Text(s.label) }, enabled = fits)
                }
            }
            if (steps.isNotEmpty()) TextButton({ steps = steps.dropLast(1) }) { Text("Undo last step") }
            Button({ onSave(name, steps) }, Modifier.fillMaxWidth(), enabled = problem == null) { Text("Save recipe") }
        }
    }
}
