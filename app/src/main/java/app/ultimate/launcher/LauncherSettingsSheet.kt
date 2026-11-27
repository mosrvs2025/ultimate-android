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
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Dock
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import app.ultimate.core.LiquidInput
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.withContext
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ultimate.core.GlassCard
import app.ultimate.core.GooeySwitch
import app.ultimate.core.Liquid
import app.ultimate.core.LiquidButton
import kotlinx.coroutines.launch
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

/**
 * The launcher's settings, opened from the gear in the top right or from the edit-home panel. A short list of pages, like a phone's own
 * settings (themes, wallpaper, home screen, favourites, app list, gestures, search), with a box on top to find one by typing and a shortcut
 * to the phone's settings. Each choice takes effect at once and is kept on the phone. Which page is open ([page], null for the list) is owned by the
 * caller, which also handles the system Back button (page to list, list to home), so there is exactly one Back handler for all of settings.
 */
@Composable
internal fun LauncherSettingsSheet(repo: LauncherRepo, allApps: List<HomeApp>, s: LauncherSettings, page: SettingsPage?, onPage: (SettingsPage?) -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color(0xFF080B10)).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ if (page != null) onPage(null) else onClose() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, if (page != null) "Back to launcher settings" else "Close settings", tint = Color.White) }
            Text(page?.title ?: "Launcher settings", color = Color.White, fontSize = 20.sp)
        }
        val current = page
        if (current == null) SettingsHub(repo, s) { onPage(it) }
        else LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
            when (current) {
                SettingsPage.Colours -> colourItems(repo, s)
                SettingsPage.Wallpaper -> wallpaperItems(repo, s)
                SettingsPage.Home -> homeItems(repo, s)
                SettingsPage.Dock -> dockItems(repo, s)
                SettingsPage.Drawer -> drawerItems(repo, allApps, s)
                SettingsPage.Gestures -> gestureItems(repo, s)
                SettingsPage.Search -> searchItems(repo, s)
            }
            item { Text("These settings are kept on this phone.", color = Liquid.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}

private fun pageIcon(p: SettingsPage) = when (p) {
    SettingsPage.Colours -> Icons.Rounded.Palette
    SettingsPage.Wallpaper -> Icons.Rounded.Image
    SettingsPage.Home -> Icons.Rounded.PhoneAndroid
    SettingsPage.Dock -> Icons.Rounded.Dock
    SettingsPage.Drawer -> Icons.Rounded.Apps
    SettingsPage.Gestures -> Icons.Rounded.Gesture
    SettingsPage.Search -> Icons.Rounded.Search
}

/** The first screen: find a setting by typing, open the phone's own settings, or pick a page. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.SettingsHub(repo: LauncherRepo, s: LauncherSettings, open: (SettingsPage) -> Unit) {
    var q by remember { mutableStateOf("") }
    val found = remember(q) { SettingsPage.matching(q) }
    Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { LiquidInput(q, { q = it }, "Search launcher settings", { found.firstOrNull()?.let(open) }, Modifier.fillMaxWidth(), sendLabel = "Open") }
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 28.dp)) {
        if (q.isBlank()) item { HubRow(Icons.Rounded.Settings, "System settings", "Tap to open your device settings", s.ink) { repo.openSystemSettings() } }
        items(found, key = { it.name }) { p -> HubRow(pageIcon(p), p.title, p.subtitle, if (p.ordinal % 2 == 0) s.ink else s.inkB) { open(p) } }
        if (found.isEmpty()) item { Text("Nothing matches \"$q\".", color = Liquid.dim, fontSize = 14.sp, modifier = Modifier.padding(20.dp)) }
    }
}

@Composable
private fun HubRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, tint: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(tint.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp)) }
        Column(Modifier.weight(1f).padding(start = 18.dp)) {
            Text(title, color = Color.White, fontSize = 17.sp)
            Text(sub, color = Liquid.dim, fontSize = 13.sp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
private fun androidx.compose.foundation.lazy.LazyListScope.colourItems(repo: LauncherRepo, s: LauncherSettings) {
    // Whole looks first: each is a set of parts, shown as a small picture of itself.
    item {
        GlassCard(title = "Looks", accent = s.ink) {
            val current = Looks.matching(s)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Looks.all.forEach { p -> LookCard(p, current == p, s.ink) { repo.update { p.apply(it) } } }
            }
            Text(if (current != null) "${current.name}: ${current.blurb}." else "Your own mix. Change any part below, or pick a look to start again from it.", color = Liquid.dim, fontSize = 12.sp)
        }
    }
    item {
        GlassCard(title = "Colours", accent = s.ink) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Accent.values().forEach { ac -> Swatch(ac, ac == s.accent) { repo.update { it.copy(accent = ac) } } }
            }
            Text(if (s.accent == Accent.Wallpaper) "These two come from your wallpaper and change when it does." else "Used for the glow, the letter rail, the suggestion pods and highlights. \"From wallpaper\" picks its two out of your picture.", color = Liquid.dim, fontSize = 12.sp)
        }
    }
    item { LivingLooksCard(repo, s) }
    // Each part on its own, so the looks can be mixed.
    item {
        GlassCard(title = "Make it yours", accent = s.ink) {
            PartRow("Background", Backdrop.entries, s.backdrop, { it.label }, s.ink) { v -> repo.update { it.copy(backdrop = v) } }
            PartRow("Typeface", Face.entries, s.face, { it.label }, s.ink) { v -> repo.update { it.copy(face = v) } }
            PartRow("Clock", ClockLayout.entries, s.clockLayout, { it.label }, s.ink) { v -> repo.update { it.copy(clockLayout = v) } }
            PartRow("Favourites", FavLayout.entries, s.favLayout, { it.label }, s.ink) { v -> repo.update { it.copy(favLayout = v) } }
            PartRow("Letter rail", RailStyle.entries, s.rail, { it.label }, s.ink) { v -> repo.update { it.copy(rail = v) } }
            PartRow("Icon shape", IconShape.entries, s.iconShape, { it.label }, s.ink) { v -> repo.update { it.copy(iconShape = v) } }
            Text("A hidden rail still works: put your thumb on the right edge and slide.", color = Liquid.dim, fontSize = 12.sp)
        }
    }
    item { YourLooksCard(repo, s) }
    // A look as a line of text: copy yours to share it, paste one to wear it.
    item {
        var pasted by remember { mutableStateOf("") }
        var note by remember { mutableStateOf<String?>(null) }
        val clipboard = LocalClipboardManager.current
        GlassCard(title = "Share a look", accent = s.ink) {
            val code = LookCode.encode(s)
            Text(code, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Chip("Copy this look", false, s.ink) { clipboard.setText(AnnotatedString(code)); note = "Copied. Paste it to a friend, or into another phone's Share a look box." }
            LiquidInput(pasted, { pasted = it }, "Paste a look code", {
                val applied = LookCode.apply(pasted, s)
                if (applied == null) note = "That isn't a look code. It starts with ULOOK1:" else { repo.update { LookCode.apply(pasted, it) ?: it }; note = "Applied."; pasted = "" }
            }, sendLabel = "Use")
            val scope = rememberCoroutineScope()
            val context = androidx.compose.ui.platform.LocalContext.current
            var qr by remember { mutableStateOf(false) }
            val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
                if (uri != null) scope.launch {
                    val found = withContext(kotlinx.coroutines.Dispatchers.IO) { readLookFromPicture(context, uri) }
                    if (found == null) note = "No look QR found in that picture." else { repo.update { LookCode.apply(found, it) ?: it }; note = "Applied the look from the picture." }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Show as a QR", false, s.ink) { qr = true }
                Chip("Read a QR picture", false, s.ink) { picker.launch("image/*") }
            }
            if (qr) LookQrDialog("Your look", code) { qr = false }
            note?.let { Text(it, color = Liquid.dim, fontSize = 12.sp) }
            Text("Only the look is in the code: colours, type and layout. Never your apps. To pass a look on, show the QR and let them photograph or screenshot it, then they pick that picture.", color = Liquid.dim, fontSize = 12.sp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> PartRow(title: String, options: List<T>, selected: T, label: (T) -> String, accent: Color, onPick: (T) -> Unit) {
    Text(title, color = Liquid.dim, fontSize = 12.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> Chip(label(o), o == selected, accent) { onPick(o) } }
    }
}

/** A look drawn small, from its own parts: the clock, three favourites and the rail, on its own background. */
@Composable
private fun LookCard(p: LookPreset, selected: Boolean, accent: Color, onClick: () -> Unit) = LookPreview(p.name, p.apply(LauncherSettings()), selected, accent, onClick)

/** Any look (a preset, one you saved, or the one you are wearing) drawn small from its own parts. */
@Composable
private fun LookPreview(name: String, s: LauncherSettings, selected: Boolean, accent: Color, onClick: () -> Unit) {
    val t = s.tokens()
    val names = listOf("Mail", "Phone", "Maps")
    // Icons "as they are" have no shape of their own; the preview draws them as softly rounded dots.
    val dot = if (s.iconShape == IconShape.AsIs) RoundedCornerShape(3.dp) else t.iconShape
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.width(104.dp).height(164.dp).clip(RoundedCornerShape(18.dp)).background(Brush.verticalGradient(
                    // The wallpaper itself isn't known here; its picked colours stand in for it.
                    if (s.backdrop == Backdrop.Wallpaper) listOf(s.accent.a.copy(alpha = 0.55f).compositeOver(Color(0xFF0A0E14)), s.accent.b.copy(alpha = 0.45f).compositeOver(Color(0xFF0A0E14))) else s.backdrop.colors))
                .border(if (selected) BorderStroke(3.dp, accent) else BorderStroke(1.dp, Color(0x33FFFFFF)), RoundedCornerShape(18.dp))
                .clickable(onClick = onClick).semantics { contentDescription = name + " look" + if (selected) ", selected" else "" },
        ) {
            Column(Modifier.padding(start = 10.dp, top = 12.dp, end = 24.dp)) {
                when (s.clockLayout) {
                    ClockLayout.Day -> Text("THU", style = TextStyle(brush = Brush.linearGradient(listOf(t.fg, s.accent.a, s.accent.b)), fontSize = 24.sp, fontWeight = FontWeight.Thin, letterSpacing = 3.sp, fontFamily = t.face))
                    ClockLayout.Digital -> Text("14:05", color = t.fg, fontSize = 26.sp, fontWeight = FontWeight.ExtraLight, fontFamily = t.face)
                    ClockLayout.Terminal -> { Text("\$ date", color = s.accent.a, fontSize = 8.sp, fontFamily = t.face); Text("14:05", color = t.fg, fontSize = 14.sp, fontFamily = t.face) }
                    ClockLayout.Editorial -> { Text("THURSDAY", color = t.dim, fontSize = 6.sp, letterSpacing = 1.5.sp, fontFamily = t.face); Text("27", color = t.fg, fontSize = 32.sp, fontWeight = FontWeight.Light, fontFamily = t.face) }
                    ClockLayout.Hidden -> Unit
                }
                Spacer(Modifier.height(10.dp))
                when (s.favLayout) {
                    FavLayout.List -> names.forEach { n ->
                        Row(Modifier.padding(bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(9.dp).clip(dot).background(s.accent.a.copy(alpha = 0.75f))); Spacer(Modifier.width(5.dp))
                            Text(n, color = t.fg, fontSize = 10.sp, fontFamily = t.face)
                        }
                    }
                    FavLayout.Numbered -> names.forEachIndexed { i, n -> Text("[${i + 1}] $n", color = t.fg, fontSize = 10.sp, fontFamily = t.face) }
                    FavLayout.Grid -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { repeat(2) { Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { repeat(3) { Box(Modifier.size(20.dp).clip(dot).background(s.accent.a.copy(alpha = 0.6f))) } } } }
                    FavLayout.Pills -> names.forEach { n -> Text(n, color = t.fg, fontSize = 9.sp, fontFamily = t.face, modifier = Modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(50)).background(s.accent.a.copy(alpha = 0.28f)).padding(horizontal = 9.dp, vertical = 2.dp)) }
                    FavLayout.Centered -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { names.forEach { n -> Text(n, color = t.fg, fontSize = 11.sp, fontWeight = FontWeight.Light, fontFamily = t.face) } }
                }
            }
            if (s.rail != RailStyle.Hidden) Column(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                listOf("A", "B", "C", "D", "E", "F").forEach { l -> Text(l, color = t.fg.copy(alpha = 0.5f), fontSize = 7.sp, fontFamily = t.face) }
            }
        }
        Text(name, color = if (selected) Color.White else Liquid.dim, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
private fun androidx.compose.foundation.lazy.LazyListScope.wallpaperItems(repo: LauncherRepo, s: LauncherSettings) {
    item {
        GlassCard(title = "Background", accent = s.inkB) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Backdrop.values().forEach { b -> Chip(b.label, b == s.backdrop, s.ink) { repo.update { it.copy(backdrop = b) } } }
            }
            Text(if (s.backdrop == Backdrop.Wallpaper) "Your phone's wallpaper shows through, under a dark veil so the text stays easy to read." else "Black saves battery on OLED screens. Pick Phone's wallpaper to see your own picture.", color = Liquid.dim, fontSize = 12.sp)
            LiquidButton("Change wallpaper", { repo.changeWallpaper() }, Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
private fun androidx.compose.foundation.lazy.LazyListScope.homeItems(repo: LauncherRepo, s: LauncherSettings) {
    item {
        GlassCard(title = "Clock", accent = s.inkB) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ClockStyle.values().forEach { c -> Chip(c.label, c == s.clock, s.ink) { repo.update { it.copy(clock = c) } } }
            }
            Toggle("Show the date", null, s.showDate) { v -> repo.update { it.copy(showDate = v) } }
        }
    }
    item {
        GlassCard(title = "Cards", accent = s.ink) {
            HomeCard.entries.forEach { c -> Toggle(c.label, c.sub, c in s.cards) { on -> repo.update { it.copy(cards = if (on) it.cards + c else it.cards - c) } } }
            Text("Cards sit under the clock in the look you are wearing, and a tap opens the matching page of Ultimate. Heart and Activity read only what Ultimate already may read from Health Connect: the launcher never asks. The cameras card lists only your own devices that chose to share a camera and never shows video.", color = Liquid.dim, fontSize = 12.sp)
        }
    }
    item {
        var reset by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        GlassCard(title = "Home screen", accent = s.ink) {
            Toggle("Suggestions", "Apps and actions offered for this moment, above your favourites.", s.suggestions) { v -> repo.update { it.copy(suggestions = v) } }
            Toggle("Tick on each letter", "A light tick under your thumb while you slide the letters.", s.railTicks) { v -> repo.update { it.copy(railTicks = v) } }
            Text("Press and hold any app to add it to your favourites or remove it.", color = Liquid.dim, fontSize = 12.sp)
            LiquidButton(if (reset) "Start over? Tap again" else "Start favourites over", {
                if (!reset) reset = true else { reset = false; scope.launch { repo.resetFavourites() } }
            }, Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
private fun androidx.compose.foundation.lazy.LazyListScope.dockItems(repo: LauncherRepo, s: LauncherSettings) {
    item {
        GlassCard(title = "Favourites", accent = s.ink) {
            Text("How many show", color = Color.White, fontSize = 15.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3, 4, 5, 6, 8).forEach { n -> Chip("$n", n == s.favouritesShown, s.ink) { repo.update { it.copy(favouritesShown = n) } } }
            }
            Text("Icon size", color = Color.White, fontSize = 15.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconSize.values().forEach { z -> Chip(z.label, z == s.dockIcons, s.ink) { repo.update { it.copy(dockIcons = z) } } }
            }
            Toggle("Show names", "Off gives a row of icons only.", s.dockLabels) { v -> repo.update { it.copy(dockLabels = v) } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
private fun androidx.compose.foundation.lazy.LazyListScope.drawerItems(repo: LauncherRepo, allApps: List<HomeApp>, s: LauncherSettings) {
    item {
        GlassCard(title = "App list", accent = s.inkB) {
            Text("Order inside each letter", color = Color.White, fontSize = 15.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppSort.values().forEach { o -> Chip(o.label, o == s.drawerSort, s.ink) { repo.update { it.copy(drawerSort = o) } } }
            }
            Toggle("Show icons", null, s.drawerShowIcons) { v -> repo.update { it.copy(drawerShowIcons = v) } }
            if (s.drawerShowIcons) {
                Text("Icon size", color = Color.White, fontSize = 15.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconSize.values().forEach { z -> Chip(z.label, z == s.drawerIcons, s.ink) { repo.update { it.copy(drawerIcons = z) } } }
                }
            }
        }
    }
    item {
        var hiding by remember { mutableStateOf(false) }
        GlassCard(title = "Hidden apps", accent = s.ink) {
            Text(if (s.hidden.isEmpty()) "Nothing hidden." else "${s.hidden.size} hidden from the letters, lists and search. They stay installed.", color = Liquid.dim, fontSize = 13.sp)
            LiquidButton(if (hiding) "Done" else "Choose apps to hide", { hiding = !hiding }, Modifier.fillMaxWidth())
            if (hiding) allApps.forEach { a ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(a.label, color = Color.White, fontSize = 16.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    GooeySwitch(a.pkg in s.hidden, { v -> repo.update { it.copy(hidden = if (v) it.hidden + a.pkg else it.hidden - a.pkg) } })
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.gestureItems(repo: LauncherRepo, s: LauncherSettings) {
    item {
        GlassCard(title = "On an empty part of the home screen", accent = s.ink) {
            GesturePicker("Swipe up", s.swipeUp, s.ink) { g -> repo.update { it.copy(swipeUp = g) } }
            GesturePicker("Swipe down", s.swipeDown, s.ink) { g -> repo.update { it.copy(swipeDown = g) } }
            GesturePicker("Double tap", s.doubleTap, s.ink) { g -> repo.update { it.copy(doubleTap = g) } }
            GesturePicker("Press and hold", s.longPress, s.ink) { g -> repo.update { it.copy(longPress = g) } }
            Text("The letter rail, the apps and the buttons keep their own touches.", color = Liquid.dim, fontSize = 12.sp)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.searchItems(repo: LauncherRepo, s: LauncherSettings) {
    item {
        GlassCard(title = "Search", accent = s.inkB) {
            Toggle("List every app first", "Off shows an empty list until you start typing.", s.searchShowsAll) { v -> repo.update { it.copy(searchShowsAll = v) } }
            Toggle("Offer a web search", "When you type something no app matches, a row sends it to your browser.", s.searchWeb) { v -> repo.update { it.copy(searchWeb = v) } }
        }
    }
}

/** A gesture's name with a small menu of what it can do. */
@Composable
private fun GesturePicker(title: String, value: GestureAction, accent: Color, onPick: (GestureAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Box {
            Text(value.label, color = accent, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x22FFFFFF)).clickable { open = true }.padding(horizontal = 14.dp, vertical = 8.dp))
            DropdownMenu(open, { open = false }) {
                GestureAction.values().forEach { g -> DropdownMenuItem({ Text(g.label) }, { onPick(g); open = false }) }
            }
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

/** Living looks: rules that change the look by themselves, with a one-tap start and a small builder for your own. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LivingLooksCard(repo: LauncherRepo, s: LauncherSettings) {
    val living by repo.living.collectAsState()
    var adding by remember { mutableStateOf(false) }
    val accent = s.ink
    GlassCard(title = "Living looks", accent = accent) {
        Text("Let the launcher change its look by itself. The look you pick stays your everyday one; a rule borrows another while it applies. The first rule from the top that applies wins.", color = Liquid.dim, fontSize = 12.sp)
        LivingLook.status(living)?.let { Text(it, color = accent, fontSize = 14.sp) }
        s.rules.forEachIndexed { i, r ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(LivingLook.describe(r.whenever), color = Color.White, fontSize = 15.sp)
                    Text("wears ${LivingLook.lookName(r.look, s.slots)}", color = Liquid.dim, fontSize = 12.sp)
                }
                RuleButton("Move up", "\u2191", i > 0) { repo.update { it.copy(rules = LivingLook.move(it.rules, i, -1)) } }
                RuleButton("Move down", "\u2193", i < s.rules.lastIndex) { repo.update { it.copy(rules = LivingLook.move(it.rules, i, 1)) } }
                RuleButton("Remove rule", "\u2715", true) { repo.update { it.copy(rules = it.rules.filterIndexed { j, _ -> j != i }) } }
            }
        }
        val fresh = LivingLook.suggestions.filter { it.second !in s.rules }
        if (s.rules.size < LivingLook.MAX_RULES && fresh.isNotEmpty()) {
            Text("Start with one", color = Liquid.dim, fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                fresh.forEach { (label, rule) -> Chip(label, false, accent) { repo.update { it.copy(rules = LivingLook.add(it.rules, rule)) }; repo.tick() } }
            }
        }
        if (s.rules.size < LivingLook.MAX_RULES) Chip(if (adding) "Close" else "Make your own rule", adding, accent) { adding = !adding }
        if (adding) RuleBuilder(accent, current = s) { rule -> repo.update { it.copy(rules = LivingLook.add(it.rules, rule)) }; repo.tick(); adding = false }
        if (s.rules.any { it.whenever == When.GoalReached }) Text("The step goal is read from Health Connect, only if you already allowed it in Ultimate's Activity.", color = Liquid.dim, fontSize = 12.sp)
    }
}

@Composable
private fun RuleButton(description: String, glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Text(glyph, color = if (enabled) Color.White else Color(0x33FFFFFF), fontSize = 18.sp,
        modifier = Modifier.clip(CircleShape).clickable(enabled = enabled, onClick = onClick).semantics { contentDescription = description }.padding(horizontal = 11.dp, vertical = 6.dp))
}

/** Pick when (a time window, while focusing, or once the step goal is reached) and which look to wear, then add it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleBuilder(accent: Color, current: LauncherSettings, onAdd: (LookRule) -> Unit) {
    var kind by remember { mutableStateOf(0) }
    var from by remember { mutableStateOf(22 * 60) }
    var to by remember { mutableStateOf(6 * 60) }
    var look by remember { mutableStateOf(Looks.zen.id) }
    Text("When", color = Liquid.dim, fontSize = 12.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("A time of day", "While focusing", "Step goal reached").forEachIndexed { i, t -> Chip(t, kind == i, accent) { kind = i } }
    }
    if (kind == 0) {
        TimeStepper("From", from, accent) { from = it }
        TimeStepper("Until", to, accent) { to = it }
    }
    Text("Wear", color = Liquid.dim, fontSize = 12.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Looks.all.forEach { p -> Chip(p.name, look == p.id, accent) { look = p.id } }
        current.slots.forEach { slot -> Chip(slot.name, look == slot.code, accent) { look = slot.code } }
    }
    val rule = LookRule(when (kind) { 0 -> When.Between(from, to); 1 -> When.Focusing; else -> When.GoalReached }, look)
    val problem = if (kind == 0 && from == to) "Pick two different times." else null
    problem?.let { Text(it, color = Liquid.dim, fontSize = 12.sp) }
    LiquidButton("Add this rule", { if (problem == null) onAdd(rule) }, Modifier.fillMaxWidth())
}

@Composable
private fun TimeStepper(label: String, minutes: Int, accent: Color, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        RuleButton("$label earlier", "\u2212", true) { onChange((minutes - 30).mod(LivingLook.DAY)) }
        Text(LivingLook.clock(minutes), color = accent, fontSize = 17.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 6.dp))
        RuleButton("$label later", "+", true) { onChange((minutes + 30).mod(LivingLook.DAY)) }
    }
}

/** The looks you saved: wear one, show its QR, delete it, or save the one you are wearing under a name. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun YourLooksCard(repo: LauncherRepo, s: LauncherSettings) {
    var name by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var qrFor by remember { mutableStateOf<LookSlot?>(null) }
    val accent = s.ink
    val suggestion = LookSlots.suggestName(s, s.slots)
    GlassCard(title = "Your looks", accent = accent) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            LookPreview("Right now", s, false, accent) {}
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Save what you are wearing so you can come back to it, give it to a rule, or share it.", color = Liquid.dim, fontSize = 12.sp)
                LiquidInput(name, { name = it.take(LookSlots.MAX_NAME) }, suggestion, {
                    saveSlot(repo, s, name.ifBlank { suggestion }) { note = it; if (it.startsWith("Saved")) name = "" }
                }, Modifier.fillMaxWidth(), sendLabel = "Save")
            }
        }
        note?.let { Text(it, color = Liquid.dim, fontSize = 12.sp) }
        if (s.slots.isEmpty()) Text("Nothing saved yet.", color = Liquid.dim, fontSize = 13.sp)
        s.slots.forEachIndexed { i, slot ->
            val look = LookCode.apply(slot.code, LauncherSettings())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(Brush.linearGradient(listOf(look?.accent?.a ?: Color.Gray, look?.accent?.b ?: Color.DarkGray))))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(slot.name, color = Color.White, fontSize = 15.sp, maxLines = 1)
                    Text(Looks.matching(look ?: LauncherSettings())?.name ?: "A mix of your own", color = Liquid.dim, fontSize = 12.sp)
                }
                RuleButton("Wear ${slot.name}", "\u2713", true) { repo.update { LookCode.apply(slot.code, it) ?: it }; note = "Wearing ${slot.name}." }
                RuleButton("QR for ${slot.name}", "\u25A6", true) { qrFor = slot }
                RuleButton("Delete ${slot.name}", "\u2715", true) { repo.update { it.copy(slots = LookSlots.delete(it.slots, i)) } }
            }
        }
        Text("\u2713 wears it, \u25A6 shows its QR, \u2715 deletes it. Up to ${LookSlots.MAX} looks. A rule under Living looks can wear any of them.", color = Liquid.dim, fontSize = 12.sp)
    }
    qrFor?.let { LookQrDialog(it.name, it.code) { qrFor = null } }
}

private fun saveSlot(repo: LauncherRepo, s: LauncherSettings, name: String, report: (String) -> Unit) {
    when (val r = LookSlots.save(s.slots, name, LookCode.encode(s))) {
        is LookSlots.Saved.Ok -> { repo.update { it.copy(slots = r.slots) }; report("Saved as ${LookSlots.cleanName(name)}.") }
        LookSlots.Saved.Full -> report("You have ${LookSlots.MAX} saved looks. Delete one first, or save under an existing name to replace it.")
        LookSlots.Saved.NoName -> report("Give it a name first.")
    }
}

/** A look's QR on white, big enough to photograph, with what it is and what it contains. */
@Composable
private fun LookQrDialog(name: String, code: String, onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(onClose) {
        Column(Modifier.clip(RoundedCornerShape(24.dp)).background(Color(0xFF101620)).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(name, color = Color.White, fontSize = 18.sp)
            app.ultimate.send.QrCode(code, Modifier.size(260.dp))
            Text("Let a friend photograph or screenshot this, then in their Themes page choose Read a QR picture. Only the look is in it: never your apps.", color = Liquid.dim, fontSize = 12.sp)
            LiquidButton("Done", onClose, Modifier.fillMaxWidth())
        }
    }
}

/** Looks for a look QR in a picture the owner picked. Done here, on the phone: the picture is never sent anywhere. */
private fun readLookFromPicture(context: android.content.Context, uri: android.net.Uri): String? = runCatching {
    val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
    val bitmap = android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        val scale = maxOf(info.size.width, info.size.height) / 2048.0
        if (scale > 1) decoder.setTargetSize((info.size.width / scale).toInt(), (info.size.height / scale).toInt())
    }
    try {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        LookQr.decode(pixels, bitmap.width, bitmap.height)
    } finally { bitmap.recycle() }
}.getOrNull()
