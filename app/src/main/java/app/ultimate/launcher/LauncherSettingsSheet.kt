package app.ultimate.launcher

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ultimate.core.GlassCard
import app.ultimate.core.GooeySwitch
import app.ultimate.core.Liquid
import app.ultimate.core.LiquidButton
import kotlinx.coroutines.launch

/**
 * The launcher's settings, opened from the gear in the top right: colours and background, the clock, how many favourites show, the
 * "Now" suggestions, the tick under the thumb, which apps are hidden, and starting the favourites over. Each choice takes effect
 * at once and is kept on the phone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LauncherSettingsSheet(repo: LauncherRepo, allApps: List<HomeApp>, s: LauncherSettings, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var hiding by remember { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Color(0xF4080B10)).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close settings", tint = Color.White) }
            Text("Launcher settings", color = Color.White, fontSize = 20.sp)
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                GlassCard(title = "Look", accent = s.accent.a) {
                    Text("Colours", color = Liquid.dim, fontSize = 13.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Accent.values().forEach { ac -> Swatch(ac, ac == s.accent) { repo.update { it.copy(accent = ac) } } }
                    }
                    Text("Background", color = Liquid.dim, fontSize = 13.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Backdrop.values().forEach { b -> Chip(b.label, b == s.backdrop, s.accent.a) { repo.update { it.copy(backdrop = b) } } }
                    }
                    Text("Black saves battery on OLED screens.", color = Liquid.dim, fontSize = 12.sp)
                }
            }
            item {
                GlassCard(title = "Clock", accent = s.accent.b) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ClockStyle.values().forEach { c -> Chip(c.label, c == s.clock, s.accent.a) { repo.update { it.copy(clock = c) } } }
                    }
                    Toggle("Show the date", null, s.showDate) { v -> repo.update { it.copy(showDate = v) } }
                }
            }
            item {
                GlassCard(title = "Home screen", accent = s.accent.a) {
                    Text("Favourites shown", color = Color.White, fontSize = 15.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(3, 4, 5, 6, 8).forEach { n -> Chip("$n", n == s.favouritesShown, s.accent.a) { repo.update { it.copy(favouritesShown = n) } } }
                    }
                    Text("Press and hold any app to add it to your favourites or remove it.", color = Liquid.dim, fontSize = 12.sp)
                    Toggle("Suggestions", "Apps and actions offered for this moment, above your favourites.", s.suggestions) { v -> repo.update { it.copy(suggestions = v) } }
                    Toggle("Tick on each letter", "A light tick under your thumb while you slide the letters.", s.railTicks) { v -> repo.update { it.copy(railTicks = v) } }
                    LiquidButton(if (reset) "Start over? Tap again" else "Start favourites over", {
                        if (!reset) reset = true else { reset = false; scope.launch { repo.resetFavourites() } }
                    }, Modifier.fillMaxWidth())
                }
            }
            item {
                GlassCard(title = "Hidden apps", accent = s.accent.b) {
                    Text(if (s.hidden.isEmpty()) "Nothing hidden." else "${s.hidden.size} hidden from the letters, lists and search. They stay installed.", color = Liquid.dim, fontSize = 13.sp)
                    LiquidButton(if (hiding) "Done" else "Choose apps to hide", { hiding = !hiding }, Modifier.fillMaxWidth())
                }
            }
            if (hiding) items(allApps, key = { "hide-" + it.pkg }) { a ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(a.label, color = Color.White, fontSize = 16.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    GooeySwitch(a.pkg in s.hidden, { v -> repo.update { it.copy(hidden = if (v) it.hidden + a.pkg else it.hidden - a.pkg) } })
                }
            }
            item { Text("These settings are kept on this phone.", color = Liquid.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}

@Composable
private fun Toggle(title: String, sub: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp)
            if (sub != null) Text(sub, color = Liquid.dim, fontSize = 12.sp)
        }
        GooeySwitch(on, onChange)
    }
}

@Composable
private fun Chip(text: String, on: Boolean, accent: Color, onClick: () -> Unit) {
    Text(text, color = if (on) Color(0xFF0A0E14) else Color.White, fontSize = 14.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) accent else Color(0x22FFFFFF)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp))
}

/** One colour choice: the two colours as a small drop, ringed when it is the one in use. */
@Composable
private fun Swatch(accent: Accent, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Brush.linearGradient(listOf(accent.a, accent.b)))
            .border(if (selected) BorderStroke(3.dp, Color.White) else BorderStroke(1.dp, Color(0x33FFFFFF)), CircleShape)
            .clickable(onClick = onClick).semantics { contentDescription = accent.label + if (selected) ", selected" else "" })
        Text(accent.label, color = if (selected) Color.White else Liquid.dim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}
