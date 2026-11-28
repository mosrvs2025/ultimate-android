package app.ultimate.launcher

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** A pointy-top hexagon, for the Hive. */
internal val HexShape: Shape = GenericShape { size, _ ->
    val w = size.width; val h = size.height
    moveTo(w / 2f, 0f); lineTo(w, h * 0.25f); lineTo(w, h * 0.75f); lineTo(w / 2f, h); lineTo(0f, h * 0.75f); lineTo(0f, h * 0.25f); close()
}

/** Dark text on a light colour, light text on a dark one. */
internal fun inkOn(c: Color): Color = if (c.luminance() > 0.5f) Color(0xFF10141A) else Color.White

/**
 * One app as a cell of the home styles: its icon (in [shape], or its first letter in a coloured disc until it loads), optionally its name under it.
 * A tap opens it; holding gives the menu (favourite, app info). The Lists style has the richer row with carrying an app to another device.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AppCell(repo: LauncherRepo, a: HomeApp, size: Dp, shape: Shape, modifier: Modifier = Modifier, label: Boolean = true, background: Color? = null, onOpen: () -> Unit = { repo.launch(a.pkg) }) {
    val th = LocalLauncherTheme.current
    val look = LocalLook.current
    var icon by remember(a.pkg) { mutableStateOf(repo.icon(a.pkg)) }
    LaunchedEffect(a.pkg) { if (icon == null) icon = repo.loadIcon(a.pkg) }
    var menu by remember { mutableStateOf(false) }
    val favs by repo.favourites.collectAsState()
    Column(modifier.combinedClickable(onClick = onOpen, onLongClick = { menu = true }).semantics { contentDescription = a.label }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size).let { if (background != null) it.clip(shape).background(background) else it }, contentAlignment = Alignment.Center) {
            AppIcon(icon, if (background != null) size * 0.66f else size, shape, a.label, th)
        }
        if (label) Text(a.label, color = look.fg, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, fontFamily = look.face, modifier = Modifier.padding(top = 4.dp).width(size + 14.dp))
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text(if (a.pkg in favs) "Remove from favourites" else "Add to favourites") }, { repo.toggleFavourite(a.pkg); menu = false })
            DropdownMenuItem({ Text("App info") }, { repo.appInfo(a.pkg); menu = false })
        }
    }
}

/** The whole home screen below the clock, for every style but the original. [dock] is the favourites to show; [onSearch] opens the search panel. */
@Composable
internal fun HomeStyleBlock(style: HomeStyle, repo: LauncherRepo, apps: List<HomeApp>, dock: List<HomeApp>, settings: LauncherSettings, onSearch: () -> Unit, modifier: Modifier = Modifier) {
    val sorted = remember(apps, settings.drawerSort) { LauncherMath.sorted(apps, settings.drawerSort) }
    when (style) {
        HomeStyle.Lists -> Unit
        HomeStyle.Pages -> PagesStyle(repo, sorted, dock, settings, modifier)
        HomeStyle.Hive -> HiveStyle(repo, sorted, settings, modifier)
        HomeStyle.Dial -> DialStyle(repo, sorted, settings, modifier)
        HomeStyle.Tiles -> TilesStyle(repo, dock, settings, onSearch, modifier)
        HomeStyle.Prompt -> PromptStyle(repo, apps, dock, modifier)
    }
}

// ---- Pages: every app in a grid, pages to swipe between, a dock of favourites at the bottom -------------------------------------------

@Composable
private fun PagesStyle(repo: LauncherRepo, apps: List<HomeApp>, dock: List<HomeApp>, settings: LauncherSettings, modifier: Modifier) {
    val th = LocalLauncherTheme.current
    val look = LocalLook.current
    BoxWithConstraints(modifier) {
        val dockH = 84.dp; val dotsH = 22.dp
        val cellW = 76.dp; val cellH = 92.dp
        val (cols, rows) = StyleMath.gridFit(maxWidth.value, (maxHeight - dockH - dotsH).value, cellW.value, cellH.value)
        val perPage = cols * rows
        val count = StyleMath.pages(apps.size, perPage)
        val pager = rememberPagerState(pageCount = { count })
        Column(Modifier.fillMaxSize()) {
            HorizontalPager(pager, Modifier.weight(1f)) { page ->
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Top) {
                    StyleMath.pageSlice(apps, page, perPage).chunked(cols).forEach { row ->
                        Row(Modifier.fillMaxWidth().height(cellH), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
                            row.forEach { a -> AppCell(repo, a, 52.dp * settings.drawerIcons.scale, look.iconShape, Modifier.width(cellW).padding(top = 6.dp)) }
                            repeat(cols - row.size) { Spacer(Modifier.width(cellW)) }
                        }
                    }
                }
            }
            if (count > 1) Row(Modifier.fillMaxWidth().height(dotsH), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                repeat(count) { i -> Box(Modifier.padding(horizontal = 3.dp).size(if (i == pager.currentPage) 8.dp else 6.dp).clip(CircleShape).background(if (i == pager.currentPage) th.a else look.fg.copy(alpha = 0.3f))) }
            }
            Row(Modifier.fillMaxWidth().height(dockH).clip(RoundedCornerShape(28.dp)).background(look.fg.copy(alpha = 0.08f)).padding(horizontal = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                dock.take(cols).forEach { a -> AppCell(repo, a, 52.dp * settings.dockIcons.scale, look.iconShape, label = false) }
                if (dock.isEmpty()) Text("Hold any app to put it in the dock.", color = look.dim, fontSize = 13.sp, fontFamily = look.face)
            }
        }
    }
}

// ---- Hive: hexagons packed like a honeycomb, scrolling down -------------------------------------------------------------------------

@Composable
private fun HiveStyle(repo: LauncherRepo, apps: List<HomeApp>, settings: LauncherSettings, modifier: Modifier) {
    val th = LocalLauncherTheme.current
    val look = LocalLook.current
    val hexW = 64.dp; val hexH = 74.dp; val gap = 3.dp
    val pitchX = hexW + gap
    val pitchY = hexH * 0.75f + gap
    BoxWithConstraints(modifier) {
        val cols = StyleMath.hiveCols(maxWidth.value, pitchX.value)
        val rows = StyleMath.hiveRows(apps, cols)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
            itemsIndexed(rows) { r, row ->
                // Every other row is pushed half a cell across, and each row's box is only three quarters of a hexagon tall so the next one tucks in.
                Row(Modifier.fillMaxWidth().height(pitchY).wrapContentHeight(Alignment.Top, unbounded = true).padding(start = if (r % 2 == 1) pitchX / 2 else 0.dp)) {
                    row.forEachIndexed { i, a ->
                        val tint = if ((r + i) % 2 == 0) th.a else th.b
                        AppCell(repo, a, hexW, HexShape, Modifier.padding(end = gap).height(hexH), label = false, background = tint.copy(alpha = 0.30f))
                    }
                }
            }
        }
    }
}

// ---- Dial: a ring of apps round the corner the thumb rests in --------------------------------------------------------------------------

@Composable
private fun DialStyle(repo: LauncherRepo, apps: List<HomeApp>, settings: LauncherSettings, modifier: Modifier) {
    val th = LocalLauncherTheme.current
    val look = LocalLook.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = with(density) { maxWidth.toPx() }; val h = with(density) { maxHeight.toPx() }
        // The ring's centre is half an icon in from the corner, so the icons at either end of the arc are whole, not cut by the screen's edge.
        val half = with(density) { 28.dp.toPx() }
        val centre = Offset(w - half, h - half)
        val radius = min(w, h) * 0.86f
        var offset by remember { mutableFloatStateOf(0f) }
        var last by remember { mutableFloatStateOf(0f) }
        val selected = StyleMath.dialSelected(offset, apps.size)
        LaunchedEffect(selected) { if (selected >= 0) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        Box(Modifier.fillMaxSize().pointerInput(apps.size) {
            detectDragGestures(
                onDragStart = { p -> last = StyleMath.angleOf(p.x, p.y, centre.x, centre.y) },
                onDrag = { change, _ ->
                    val a = StyleMath.angleOf(change.position.x, change.position.y, centre.x, centre.y)
                    offset = StyleMath.dialClamp(offset - StyleMath.turn(last, a), apps.size); last = a; change.consume()
                },
            )
        }) {
            // The ring, faint, so you can see what you are spinning.
            Canvas(Modifier.fillMaxSize()) { drawArc(look.fg.copy(alpha = 0.12f), 180f, 90f, false, Offset(centre.x - radius, centre.y - radius), Size(radius * 2, radius * 2), style = Stroke(1.5.dp.toPx())) }
            val iconPx = with(density) { 48.dp.toPx() }
            apps.indices.filter { StyleMath.dialVisible(StyleMath.dialAngle(it, offset)) }.forEach { i ->
                val ang = Math.toRadians(StyleMath.dialAngle(i, offset).toDouble())
                val x = centre.x + radius * cos(ang).toFloat(); val y = centre.y + radius * sin(ang).toFloat()
                val on = i == selected
                AppCell(repo, apps[i], 48.dp * settings.drawerIcons.scale, look.iconShape, Modifier.offset { IntOffset((x - iconPx / 2f).roundToInt(), (y - iconPx / 2f).roundToInt()) }.scale(if (on) 1.35f else 1f), label = false)
            }
            // The name of the app in the middle of the arc, in the corner under it.
            apps.getOrNull(selected)?.let { a ->
                Column(Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 6.dp), horizontalAlignment = Alignment.End) {
                    Text(a.label, color = look.fg, fontSize = 24.sp, fontWeight = FontWeight.Light, maxLines = 1, fontFamily = look.face)
                    Text("${selected + 1} of ${apps.size}", color = th.a, fontSize = 12.sp, fontFamily = look.face)
                }
            }
        }
    }
}

// ---- Tiles: a wall of coloured tiles -------------------------------------------------------------------------------------------------------

@Composable
private fun TilesStyle(repo: LauncherRepo, dock: List<HomeApp>, settings: LauncherSettings, onSearch: () -> Unit, modifier: Modifier) {
    val th = LocalLauncherTheme.current
    val look = LocalLook.current
    val plan = remember(dock.size) { StyleMath.tilePlan(dock.size) }
    LazyVerticalGrid(GridCells.Fixed(2), modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(dock, span = { i, _ -> GridItemSpan(plan[i].span) }) { i, a ->
            val kind = plan[i]
            val colour = (if (i % 2 == 0) th.a else th.b).copy(alpha = if (kind == StyleMath.Tile.Large) 0.85f else 0.65f)
            val ink = inkOn(colour)
            val shape = RoundedCornerShape(14.dp)
            Box(Modifier.fillMaxWidth().height(kind.tallDp.dp).clip(shape).background(Brush.linearGradient(listOf(colour, colour.copy(alpha = colour.alpha * 0.7f)))).clickable { repo.launch(a.pkg) }.semantics { contentDescription = a.label }) {
                TileIcon(repo, a, if (kind == StyleMath.Tile.Large) 56.dp else 36.dp, Modifier.align(Alignment.TopStart).padding(10.dp))
                Text(a.label, color = ink, fontSize = if (kind == StyleMath.Tile.Large) 20.sp else 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = look.face, modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 8.dp, bottom = 8.dp))
            }
        }
        item(span = { GridItemSpan(2) }) {
            Box(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, th.a.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).clickable { onSearch() }, contentAlignment = Alignment.Center) {
                Text("All apps", color = th.a, fontSize = 18.sp, fontFamily = look.face)
            }
        }
        if (dock.isEmpty()) item(span = { GridItemSpan(2) }) { Text("Hold any app to make it a tile.", color = look.dim, fontSize = 14.sp, fontFamily = look.face) }
    }
}

@Composable
private fun TileIcon(repo: LauncherRepo, a: HomeApp, size: Dp, modifier: Modifier) {
    val th = LocalLauncherTheme.current
    var icon by remember(a.pkg) { mutableStateOf(repo.icon(a.pkg)) }
    LaunchedEffect(a.pkg) { if (icon == null) icon = repo.loadIcon(a.pkg) }
    Box(modifier) { AppIcon(icon, size, LocalLook.current.iconShape, a.label, th) }
}

// ---- Prompt: a command line --------------------------------------------------------------------------------------------------------------

@Composable
private fun PromptStyle(repo: LauncherRepo, apps: List<HomeApp>, dock: List<HomeApp>, modifier: Modifier) {
    val th = LocalLauncherTheme.current
    val look = LocalLook.current
    var q by remember { mutableStateOf("") }
    val listed = remember(q, apps, dock) { StyleMath.promptList(apps, dock, q) }
    fun enter() { StyleMath.promptEnter(apps, dock, q)?.let { repo.launch(it.pkg); q = "" } }
    Column(modifier.fillMaxSize().imePadding()) {
        // The output sits at the bottom, nearest the line you type on, where the thumb is.
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Bottom) {
            Text(if (q.isBlank()) "$ ls favourites" else "$ grep \"${q.trim()}\"", color = th.a, fontSize = 13.sp, fontFamily = look.face, modifier = Modifier.padding(bottom = 6.dp))
            listed.forEachIndexed { i, a ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { repo.launch(a.pkg); q = "" }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("[${i + 1}]", color = th.a, fontSize = 15.sp, fontFamily = look.face)
                    Spacer(Modifier.width(12.dp))
                    Text(a.label, color = look.fg, fontSize = 18.sp, maxLines = 1, fontFamily = look.face)
                }
            }
            if (listed.isEmpty()) Text(if (q.isBlank()) "no favourites yet: hold an app to add one" else "no match", color = look.dim, fontSize = 14.sp, fontFamily = look.face)
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("> ", color = th.a, fontSize = 20.sp, fontFamily = look.face)
            BasicTextField(
                q, { q = it }, Modifier.weight(1f), singleLine = true,
                textStyle = TextStyle(color = look.fg, fontSize = 20.sp, fontFamily = look.face), cursorBrush = SolidColor(th.a),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { enter() }),
                decorationBox = { inner -> Box { if (q.isEmpty()) Text("type to find an app", color = look.dim, fontSize = 20.sp, fontFamily = look.face); inner() } },
            )
        }
    }
}
