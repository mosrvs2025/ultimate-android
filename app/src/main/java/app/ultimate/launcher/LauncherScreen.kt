package app.ultimate.launcher

import kotlinx.coroutines.launch
import app.ultimate.mesh.DeviceFling
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ultimate.core.Liquid
import app.ultimate.core.LiquidInput
import app.ultimate.core.gooey
import app.ultimate.core.motionOn
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Ultimate's home screen: the time, a short list of favourites, and a liquid letter rail down the right edge. Put a thumb on the
 * rail and slide: the letters bulge out around it in a wave that wobbles and settles like jelly, the letter under the thumb pops
 * into a bubble that melts out of the rail, and that letter's apps spring in beside it. Slide onto one and let go to open it, or
 * let go on the rail and tap. Swipe up anywhere for search. Nothing else on the screen: no grid, no pages.
 */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
fun LauncherHome(repo: LauncherRepo, homePresses: Int, onOpenUltimate: () -> Unit, autoLoad: Boolean = true) {
    val allApps by repo.apps.collectAsState()
    // What is drawn is the owner's look with any living-look rule's look on top; the settings sheet edits the owner's own (repo.settings).
    val living by repo.living.collectAsState()
    val settings = living.settings
    val ownSettings by repo.settings.collectAsState()
    val favs by repo.favourites.collectAsState()
    // The rules look at the clock, a focus session and the step goal: check again every half minute and whenever you come home.
    LaunchedEffect(homePresses, settings.rules.size) { while (true) { repo.refreshSignals(); delay(30_000) } }
    // Apps the owner hid stay out of the rail, the lists and search.
    val apps = remember(allApps, settings.hidden) { LauncherMath.visible(allApps, settings.hidden) }
    var settingsOpen by remember { mutableStateOf(false) }
    var settingsPage by remember { mutableStateOf<SettingsPage?>(null) }
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (autoLoad) repo.load() }
    val letters = remember(apps) { LauncherMath.letters(apps) }
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val motion = motionOn()
    val look = settings.tokens()
    androidx.compose.runtime.SideEffect { WallpaperInk.backdropLight(look.light) }
    // A light look needs dark status and navigation bar icons, or they vanish into the page.
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        view.context.findActivity()?.window?.let { w ->
            androidx.core.view.WindowCompat.getInsetsController(w, view).apply { isAppearanceLightStatusBars = look.light; isAppearanceLightNavigationBars = look.light }
        }
    }

    var searching by remember { mutableStateOf(false) }
    var sticky by remember { mutableStateOf<Char?>(null) }
    // Pressing Home while already home closes whatever is open, like every launcher.
    LaunchedEffect(homePresses) { searching = false; sticky = null; settingsOpen = false; editing = false }
    BackHandler(enabled = settingsOpen) { settingsOpen = false; settingsPage = null }
    BackHandler(enabled = !settingsOpen && (searching || sticky != null)) { searching = false; sticky = null }
    fun openSettings(page: SettingsPage? = null) { editing = false; settingsPage = page; settingsOpen = true }
    // What a swipe, double tap or hold on an empty part of the screen does is the owner's choice (settings, Gestures).
    val settingsNow by androidx.compose.runtime.rememberUpdatedState(settings)
    fun perform(g: GestureAction) { when (g) {
        GestureAction.None -> Unit
        GestureAction.Search -> { sticky = null; searching = true }
        GestureAction.Notifications -> repo.pullShade(false)
        GestureAction.QuickSettings -> repo.pullShade(true)
        GestureAction.Ultimate -> onOpenUltimate()
        GestureAction.SystemSettings -> repo.openSystemSettings()
        GestureAction.EditPanel -> { sticky = null; editing = true }
    } }
    val performNow by androidx.compose.runtime.rememberUpdatedState<(GestureAction) -> Unit>(::perform)

    // The rail's drag: where the finger is (in the rail's own coordinates) and whether it is down.
    var dragging by remember { mutableStateOf(false) }
    var fingerY by remember { mutableFloatStateOf(0f) }
    var fingerX by remember { mutableFloatStateOf(0f) }
    var railTop by remember { mutableFloatStateOf(0f) }
    var railHeight by remember { mutableFloatStateOf(1f) }
    var railLeft by remember { mutableFloatStateOf(0f) }
    // The panel of apps for the current letter, measured so the finger can slide onto a row.
    var panelTop by remember { mutableFloatStateOf(0f) }
    var hovered by remember { mutableStateOf<String?>(null) }
    // How many rows of the letter's list are on screen (a long list is cut to fit and scrolls), so only those can be slid onto.
    var panelRows by remember { mutableIntStateOf(Int.MAX_VALUE) }

    // Springs: the wave swells in and out with a bounce, and its centre follows the finger a moment behind, so a quick slide
    // overshoots and wobbles back like jelly. With animations off it simply snaps.
    val wave by animateFloatAsState(if (dragging) 1f else 0f, if (motion) spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow) else spring(1f, 10_000f), label = "wave")
    val follow by animateFloatAsState(fingerY, if (motion) spring(0.45f, Spring.StiffnessMediumLow) else spring(1f, 10_000f), label = "follow")
    val inset = with(density) { 22.dp.toPx() }
    val usable = (railHeight - 2 * inset).coerceAtLeast(1f)
    // The letter stays put until the finger is clearly on the next one, so a wobbly slide doesn't flicker between two.
    var held by remember { mutableStateOf<Int?>(null) }
    val rawPos = LauncherMath.position(fingerY, inset, usable, letters.size)
    val index = if (dragging) LauncherMath.indexSticky(rawPos, held, letters.size) else LauncherMath.index(rawPos, letters.size)
    androidx.compose.runtime.SideEffect { held = if (dragging) index else null }
    val indexNow by androidx.compose.runtime.rememberUpdatedState(index)
    val current: Char? = if (dragging) letters.getOrNull(index) else sticky
    LaunchedEffect(index, dragging) { if (dragging && settings.railTicks) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    val pop = remember { Animatable(1f) }
    LaunchedEffect(current) { if (current != null && motion) { pop.snapTo(0.6f); pop.animateTo(1f, spring(Spring.DampingRatioHighBouncy, Spring.StiffnessMedium)) } }

    val shown = current?.let { LauncherMath.appsFor(apps, it, settings.drawerSort) }.orEmpty()
    // The rail's touch handler runs for the whole drag, so it reads the newest values through these instead of restarting
    // (a restart in the middle of a drag would drop the finger).
    val lettersNow by androidx.compose.runtime.rememberUpdatedState(letters)
    val appsNow by androidx.compose.runtime.rememberUpdatedState(apps)
    val stickyNow by androidx.compose.runtime.rememberUpdatedState(sticky)
    val rowPx = with(density) { 56.dp.toPx() }

    val flingDrag = remember { FlingDrag() }
    androidx.compose.runtime.CompositionLocalProvider(LocalFlingDrag provides flingDrag, LocalLauncherTheme provides settings.accent, LocalLook provides look) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(settings.backdrop.colors))
        // Swipe up for search: a real swipe (not the first few pixels of a scroll), and not while a letter's list is open, where
        // swiping up is how that list is scrolled.
        .pointerInput(Unit) {
            var total = 0f
            detectVerticalDragGestures(onDragStart = { total = 0f }, onDragEnd = { total = 0f }, onDragCancel = { total = 0f }) { _, dy ->
                if (!dragging && stickyNow == null) {
                    total += dy
                    if (total < -72.dp.toPx()) { performNow(settingsNow.swipeUp); total = 0f }
                    else if (total > 72.dp.toPx()) { performNow(settingsNow.swipeDown); total = 0f }
                }
            }
        }
        ) {
        // Double tap and press-and-hold on empty space: a layer behind everything, so an app row's own hold (its menu), the rail and the
        // buttons keep their touches and only the bare background reaches this.
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures(
                onDoubleTap = { performNow(settingsNow.doubleTap) },
                onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); performNow(settingsNow.longPress) },
            )
        })

        // ---- left: clock, favourites, or the letter's apps
        // A look with centred favourites centres everything, with the same room either side (the rail is out of the way, or hidden).
        val centred = settings.favLayout == FavLayout.Centered
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(start = 28.dp, end = if (centred) 28.dp else 96.dp, top = 48.dp),
            horizontalAlignment = if (centred) Alignment.CenterHorizontally else Alignment.Start) {
            ClockBlock(onOpenUltimate, settings)
            Spacer(Modifier.height(36.dp))
            // What is worth offering right now: refreshed every half minute and whenever you come home.
            var nowItems by remember { mutableStateOf<List<Now>>(emptyList()) }
            LaunchedEffect(homePresses, apps.size) { while (true) { nowItems = repo.now(); delay(30_000) } }
            if (settings.suggestions && nowItems.isNotEmpty() && current == null) { NowRow(repo, nowItems); Spacer(Modifier.height(20.dp)) }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                val favAlpha = (1f - (if (current != null) 1f else 0f)).coerceIn(0f, 1f)
                val dock = favs.mapNotNull { pkg -> apps.firstOrNull { it.pkg == pkg } }.take(settings.favouritesShown)
                FavouritesBlock(repo, dock, favs.isEmpty() && apps.isNotEmpty(), settings, Modifier.alpha(favAlpha))
            }
        }

        // ---- the letter's apps, springing in level with the finger
        if (current != null && shown.isNotEmpty()) {
            val topMin = with(density) { 196.dp.toPx() }   // below the clock and the Ultimate line, so a long list never covers them
            // A letter with more apps than fit is cut to the room there is and scrolls (a swipe up or down on it), instead of running off the screen.
            val availH = (railTop + railHeight - topMin).coerceAtLeast(rowPx)
            val panelH = kotlin.math.min(rowPx * shown.size, availH)
            val wanted = railTop + (if (dragging) fingerY else railHeight / 2) - panelH / 2
            val top = wanted.coerceIn(topMin, (railTop + railHeight - panelH).coerceAtLeast(topMin))
            val scroll = remember(current) { androidx.compose.foundation.ScrollState(0) }
            Column(Modifier.offset { IntOffset(with(density) { 28.dp.roundToPx() }, top.roundToInt()) }.width(260.dp).height(with(density) { panelH.toDp() })
                .verticalScroll(scroll)
                .onGloballyPositioned { panelTop = it.positionInRoot().y; panelRows = (it.size.height / rowPx).toInt().coerceAtLeast(1) }) {
                shown.forEachIndexed { i, a ->
                    AppRow(repo, a, highlighted = hovered == a.pkg, delayIndex = i, onOpen = { repo.launch(a.pkg); sticky = null }, big = false, iconScale = settings.drawerIcons.scale, showIcon = settings.drawerShowIcons)
                }
            }
        }

        // ---- the liquid letter rail
        Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight(0.74f).width(110.dp)
            .onGloballyPositioned { val p = it.positionInRoot(); railTop = p.y; railLeft = p.x; railHeight = it.size.height.toFloat() }
            .semantics { contentDescription = "Letter rail: slide to find an app" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    dragging = true; sticky = null; searching = false
                    fingerY = down.position.y; fingerX = down.position.x
                    var id = down.id
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == id } ?: break
                        if (!c.pressed) break
                        fingerY = c.position.y; fingerX = c.position.x
                        c.consume()
                        // Slid left onto the apps: the row under the finger is the one that opens on release.
                        hovered = if (fingerX < size.width * 0.15f) {
                            val l = lettersNow.getOrNull(indexNow)
                            val list = l?.let { LauncherMath.appsFor(appsNow, it, settingsNow.drawerSort) }.orEmpty()
                            val row = ((railTop + fingerY - panelTop) / rowPx).toInt()
                            if (row in 0 until panelRows) list.getOrNull(row)?.pkg else null
                        } else null
                    }
                    val open = hovered
                    val letter = lettersNow.getOrNull(indexNow)
                    dragging = false; hovered = null
                    if (open != null) repo.launch(open) else sticky = letter
                }
            }) {
            RailVisual(letters, follow, wave, pop.value, current, dragging)
        }

        // ---- flinging an app to another device
        FlingLayer(repo, flingDrag)

        // ---- settings: a small gear in the top right
        if (!searching && !settingsOpen) Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 6.dp, end = 8.dp).size(48.dp).clip(CircleShape)
            .clickable { openSettings() }.semantics { contentDescription = "Launcher settings" }, contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(Icons.Rounded.Settings, null, tint = look.fg.copy(alpha = 0.6f), modifier = Modifier.size(24.dp))
        }

        // ---- search
        if (searching) SearchSheet(repo, apps, settings, onClose = { searching = false })
        if (editing && !settingsOpen) EditHomePanel(settings.accent, onWallpaper = { editing = false; repo.changeWallpaper() }, onColours = { openSettings(SettingsPage.Colours) },
            onSettings = { openSettings() }, onClose = { editing = false })
        if (settingsOpen) LauncherSettingsSheet(repo, allApps, ownSettings, settingsPage, onClose = { settingsOpen = false; settingsPage = null })
    }
    }
}

/** The "Now" offers as liquid pods: each says what and why, springs in, and is one tap away. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun NowRow(repo: LauncherRepo, items: List<Now>, onDone: () -> Unit = {}) {
    val th = LocalLauncherTheme.current
    val look = LocalLook.current
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEachIndexed { i, n ->
            val motion = motionOn()
            val enter = remember(n) { Animatable(if (motion) 0f else 1f) }
            LaunchedEffect(n) { if (motion) { delay(70L * i); enter.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)) } }
            var icon by remember(n) { mutableStateOf((n as? Now.App)?.let { repo.icon(it.pkg) }) }
            LaunchedEffect(n) { if (n is Now.App && icon == null) icon = repo.loadIcon(n.pkg) }
            Row(Modifier.scale(0.6f + 0.4f * enter.value).alpha(enter.value.coerceIn(0f, 1f)).clip(RoundedCornerShape(22.dp))
                .background(Brush.linearGradient(listOf(th.a.copy(alpha = 0.22f), th.b.copy(alpha = 0.18f))))
                .androidxBorder(th.a, th.b)
                .combinedClickable(onClick = { when (n) { is Now.App -> repo.launch(n.pkg); is Now.Action -> repo.act(n.id) }; onDone() })
                .padding(start = 8.dp, end = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(Brush.linearGradient(listOf(th.a, th.b))), contentAlignment = Alignment.Center) {
                    val ic = icon
                    if (ic != null) Image(ic, null, Modifier.fillMaxSize()) else Text(if (n is Now.Action) "●" else n.label.take(1), color = Color(0xFF0B0F14), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(n.label, color = look.fg, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, fontFamily = look.face)
                    Text(n.because, color = look.dim, fontSize = 11.sp, maxLines = 1, fontFamily = look.face)
                }
            }
        }
    }
}

private fun Modifier.androidxBorder(a: Color, b: Color): Modifier = this.then(Modifier.border(1.dp, Brush.linearGradient(listOf(a.copy(alpha = 0.6f), b.copy(alpha = 0.4f))), RoundedCornerShape(22.dp)))

/**
 * The rail itself, drawing only: the letters, the wave they make around the finger, and the liquid drop that melts out of the
 * spine with the current letter in it. [follow] is where the finger is (lagging on a spring), [wave] how strongly the rail is
 * swollen (0 to 1, springing), [pop] the bubble's bounce. Shared by the home screen and the edge rail over other apps.
 */
@Composable
internal fun RailVisual(letters: List<Char>, follow: Float, wave: Float, pop: Float, current: Char?, dragging: Boolean, modifier: Modifier = Modifier.fillMaxSize()) {
    val th = LocalLauncherTheme.current
    val look = LocalLook.current
    val style = look.rail
    val measurer = rememberTextMeasurer()
    Box(modifier) {
        // The liquid: a spine, a drop under the finger, and two beads joining them, melted together by the gooey effect.
        if (style == RailStyle.Liquid) Canvas(Modifier.fillMaxSize().gooey(14f)) {
            val spineX = size.width - 18.dp.toPx()
            if (wave > 0.02f) {
                val y = follow.coerceIn(0f, size.height)
                val reach = 64.dp.toPx() * wave
                val brush = Brush.verticalGradient(listOf(th.a, th.b), y - 120.dp.toPx(), y + 120.dp.toPx())
                drawLine(brush, Offset(spineX, (y - 140.dp.toPx() * wave).coerceAtLeast(0f)), Offset(spineX, (y + 140.dp.toPx() * wave).coerceAtMost(size.height)), 6.dp.toPx() * wave)
                drawCircle(brush, 10.dp.toPx() * wave, Offset(spineX - reach * 0.35f, y))
                drawCircle(brush, 14.dp.toPx() * wave, Offset(spineX - reach * 0.68f, y))
                drawCircle(brush, 24.dp.toPx() * wave * pop, Offset(spineX - reach, y))
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val n = letters.size
            if (n == 0) return@Canvas
            val spineX = size.width - 18.dp.toPx()
            val inset = 22.dp.toPx(); val usable = (size.height - 2 * inset).coerceAtLeast(1f)
            val pos = LauncherMath.position(follow, inset, usable, n)
            for ((i, ch) in letters.withIndex()) {
                val y = inset + if (n == 1) 0f else usable * i / (n - 1)
                val selected = current == ch && dragging
                if (style != RailStyle.Liquid) {
                    // Plain letters stand still in a column; the one under the thumb grows and takes the accent colour. The hidden rail shows
                    // nothing until touched, and then only the letter under the thumb, floating beside it.
                    if (style == RailStyle.Hidden) {
                        if (selected) {
                            val big = measurer.measure(ch.toString(), TextStyle(color = th.a, fontSize = 40.sp, fontWeight = FontWeight.Light, fontFamily = look.face))
                            drawText(big, topLeft = Offset(spineX - 40.dp.toPx() - big.size.width / 2f, follow - big.size.height / 2f))
                        }
                    } else {
                        val t = TextStyle(color = if (selected) th.a else look.fg.copy(alpha = 0.5f), fontSize = if (selected) 22.sp else 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, fontFamily = look.face)
                        val layout = measurer.measure(ch.toString(), t)
                        drawText(layout, topLeft = Offset(spineX - layout.size.width / 2f - if (selected) 6.dp.toPx() else 0f, y - layout.size.height / 2f))
                    }
                    continue
                }
                val b = LauncherMath.bulge(i, pos) * wave
                val x = spineX - b * 44.dp.toPx()
                val style = TextStyle(color = if (selected) Color(0xFF0B0F14) else look.fg.copy(alpha = 0.45f + 0.55f * b), fontSize = (11f + 9f * b).sp, fontWeight = if (selected || b > 0.6f) FontWeight.Bold else FontWeight.Medium)
                if (selected) {
                    // The bubble: the letter the finger is on, inside the drop that melted out of the rail.
                    val bx = spineX - 64.dp.toPx() * wave
                    val big = measurer.measure(ch.toString(), style.copy(fontSize = 22.sp, color = Color(0xFF0B0F14)))
                    drawText(big, topLeft = Offset(bx - big.size.width / 2f, follow - big.size.height / 2f))
                } else {
                    val layout = measurer.measure(ch.toString(), style)
                    drawText(layout, topLeft = Offset(x - layout.size.width / 2f, y - layout.size.height / 2f))
                }
            }
        }
    }
}

/** The ways an app can be drawn in a list: the usual row, or one of the look's favourite layouts. */
internal enum class RowVariant { Plain, Numbered, Tile, Pill, Centered }

/** An app's icon cut to the look's shape, or its first letter in a coloured disc until it has loaded. */
@Composable
private fun AppIcon(icon: androidx.compose.ui.graphics.ImageBitmap?, size: androidx.compose.ui.unit.Dp, shape: androidx.compose.ui.graphics.Shape, label: String, th: Palette) {
    Box(Modifier.size(size).clip(shape), contentAlignment = Alignment.Center) {
        if (icon != null) Image(icon, null, Modifier.fillMaxSize())
        else Box(Modifier.fillMaxSize().clip(if (shape == androidx.compose.ui.graphics.RectangleShape) CircleShape else shape).background(Brush.linearGradient(listOf(th.a.copy(alpha = 0.5f), th.b.copy(alpha = 0.4f)))), contentAlignment = Alignment.Center) { Text(label.take(1), color = LocalLook.current.fg, fontSize = 15.sp) }
    }
}

private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The favourites, laid out the way the look asks for: a list, a numbered list, tiles, pills, or centred. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun FavouritesBlock(repo: LauncherRepo, dock: List<HomeApp>, showHint: Boolean, settings: LauncherSettings, modifier: Modifier) {
    val look = LocalLook.current
    val scale = settings.dockIcons.scale
    @Composable fun hint() { if (showHint) Text("Long-press any app to make it a favourite.", color = look.dim, fontSize = 14.sp, fontFamily = look.face) }
    when (settings.favLayout) {
        FavLayout.List -> if (settings.dockLabels) Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            dock.forEachIndexed { i, a -> AppRow(repo, a, highlighted = false, delayIndex = i, onOpen = { repo.launch(a.pkg) }, big = true, iconScale = scale) }
            hint()
        } else androidx.compose.foundation.layout.FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Icons only: a wrapping row, each icon still opens on tap and gives the menu on hold.
            dock.forEachIndexed { i, a -> AppRow(repo, a, highlighted = false, delayIndex = i, onOpen = { repo.launch(a.pkg) }, big = true, iconScale = scale, compact = true) }
            hint()
        }
        FavLayout.Numbered -> Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            dock.forEachIndexed { i, a -> AppRow(repo, a, highlighted = false, delayIndex = i, onOpen = { repo.launch(a.pkg) }, big = true, iconScale = scale, variant = RowVariant.Numbered, number = i + 1) }
            hint()
        }
        FavLayout.Grid -> androidx.compose.foundation.layout.FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            dock.forEachIndexed { i, a -> AppRow(repo, a, highlighted = false, delayIndex = i, onOpen = { repo.launch(a.pkg) }, big = true, iconScale = scale, variant = RowVariant.Tile) }
            hint()
        }
        FavLayout.Pills -> androidx.compose.foundation.layout.FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            dock.forEachIndexed { i, a -> AppRow(repo, a, highlighted = false, delayIndex = i, onOpen = { repo.launch(a.pkg) }, big = true, iconScale = scale, variant = RowVariant.Pill) }
            hint()
        }
        FavLayout.Centered -> Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            dock.forEachIndexed { i, a -> AppRow(repo, a, highlighted = false, delayIndex = i, onOpen = { repo.launch(a.pkg) }, big = true, iconScale = scale, variant = RowVariant.Centered) }
            hint()
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun AppRow(
    repo: LauncherRepo, a: HomeApp, highlighted: Boolean, delayIndex: Int, onOpen: () -> Unit, big: Boolean,
    /** Multiplies the icon's usual size (see [IconSize]). */
    iconScale: Float = 1f,
    showIcon: Boolean = true,
    /** Just the icon, no name, as wide as the icon: for a row of favourites. */
    compact: Boolean = false,
    /** How the row is drawn: the usual row, or one of the look's favourite layouts. [number] is the place in a numbered list. */
    variant: RowVariant = RowVariant.Plain,
    number: Int = 0,
) {
    val th = LocalLauncherTheme.current
    val look = LocalLook.current
    var icon by remember(a.pkg) { mutableStateOf(repo.icon(a.pkg)) }
    LaunchedEffect(a.pkg) { if (icon == null) icon = repo.loadIcon(a.pkg) }
    val motion = motionOn()
    // Each row springs in from the right a beat after the one above, overshoots a little, and settles.
    val enter = remember(a.pkg) { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(a.pkg) { if (motion) { delay(28L * delayIndex); enter.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)) } }
    val lift by animateFloatAsState(if (highlighted) 1.12f else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "lift")
    var menu by remember { mutableStateOf(false) }
    val favs by repo.favourites.collectAsState()
    val fling = LocalFlingDrag.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var origin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val openNow by androidx.compose.runtime.rememberUpdatedState(onOpen)
    // Tap opens. Holding opens the menu (favourite, app info, send to a device) straight away. If the finger then pulls away without
    // letting go, the menu gives way and the app is carried to another device instead; letting go in place leaves the menu open.
    val gesture = if (fling == null) Modifier.combinedClickable(onClick = onOpen, onLongClick = { menu = true })
        else Modifier.onGloballyPositioned { origin = it.positionInRoot() }.pointerInput(a.pkg) {
            awaitEachGesture {
                val down = awaitFirstDown()
                var timedOut = true
                val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation().also { timedOut = false } }
                if (!timedOut) { if (up != null) { up.consume(); openNow() }; return@awaitEachGesture }
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                down.consume()
                menu = true
                var moved = 0f; var carrying = false
                while (true) {
                    val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) { c.consume(); break }
                    val d = c.position - c.previousPosition
                    moved += d.getDistance()
                    // Pulled well away from where it was held: carry it, if there is somewhere to carry it to (else the menu stays).
                    if (!carrying && moved > viewConfiguration.touchSlop * 3 && fling.start(a, origin + c.position)) { carrying = true; menu = false }
                    if (carrying) { fling.pos += d; c.consume() }
                }
                if (!carrying) return@awaitEachGesture
                val hit = fling.release() ?: return@awaitEachGesture
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                scope.launch { val err = DeviceFling.provider?.app(hit.second.id, hit.first.pkg, hit.first.label)
                    fling.toast = if (err == null) "Sent ${hit.first.label} to ${hit.second.name}" else "Couldn't send to ${hit.second.name}: $err" }
            }
        }
    val common = Modifier.offset { IntOffset(((1f - enter.value) * 120).roundToInt(), 0) }.alpha(enter.value.coerceIn(0f, 1f)).scale(lift)
    val highlight = if (highlighted) Brush.horizontalGradient(listOf(th.a.copy(alpha = 0.35f), th.b.copy(alpha = 0.18f))) else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
    Box {
        when (variant) {
            RowVariant.Plain -> Row((if (compact) Modifier else Modifier.fillMaxWidth()).height(((if (big) 52.dp else 56.dp) * iconScale.coerceAtLeast(1f)))
                .then(common).clip(RoundedCornerShape(16.dp)).background(highlight)
                .then(gesture).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (showIcon || compact) Box(Modifier.size((if (big) 30.dp else 34.dp) * iconScale).clip(look.iconShape).semantics { if (compact) contentDescription = a.label }, contentAlignment = Alignment.Center) {
                    val ic = icon
                    if (ic != null) Image(ic, null, Modifier.fillMaxSize())
                    // Icons-only favourites would be invisible without their icon, so until it loads (or if it can't) show the app's first letter.
                    else if (compact) Box(Modifier.fillMaxSize().clip(CircleShape).background(Brush.linearGradient(listOf(th.a.copy(alpha = 0.5f), th.b.copy(alpha = 0.4f)))), contentAlignment = Alignment.Center) { Text(a.label.take(1), color = look.fg, fontSize = 15.sp) }
                }
                if (!compact) {
                    if (showIcon) Spacer(Modifier.width(14.dp))
                    Text(a.label, color = look.fg, fontSize = if (big) 20.sp else 17.sp, fontWeight = if (big) FontWeight.Light else FontWeight.Normal, maxLines = 1, fontFamily = look.face)
                }
            }
            // A numbered list, like a menu in a terminal: no icon, the number in the accent colour.
            RowVariant.Numbered -> Row(Modifier.fillMaxWidth().height(40.dp).then(common).clip(RoundedCornerShape(10.dp)).background(highlight).then(gesture).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("[$number]", color = th.a, fontSize = 15.sp, fontFamily = look.face)
                Spacer(Modifier.width(12.dp))
                Text(a.label, color = look.fg, fontSize = if (big) 18.sp else 16.sp, maxLines = 1, fontFamily = look.face)
            }
            // A tile: the icon over its name, several to a row.
            RowVariant.Tile -> Column(Modifier.width(68.dp).then(common).clip(RoundedCornerShape(16.dp)).background(highlight).then(gesture).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AppIcon(icon, 52.dp * iconScale, look.iconShape, a.label, th)
                Spacer(Modifier.height(4.dp))
                Text(a.label, color = look.fg, fontSize = 11.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontFamily = look.face)
            }
            // A soft pill with the accent colours.
            RowVariant.Pill -> Row(Modifier.then(common).clip(RoundedCornerShape(26.dp))
                .background(Brush.horizontalGradient(listOf(th.a.copy(alpha = if (highlighted) 0.45f else 0.22f), th.b.copy(alpha = 0.14f))))
                .border(1.dp, th.a.copy(alpha = 0.35f), RoundedCornerShape(26.dp)).then(gesture).padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(icon, 30.dp * iconScale, look.iconShape, a.label, th)
                Spacer(Modifier.width(10.dp))
                Text(a.label, color = look.fg, fontSize = 16.sp, maxLines = 1, fontFamily = look.face)
            }
            // In the middle of the screen, large and light.
            RowVariant.Centered -> Row(Modifier.fillMaxWidth().height(52.dp).then(common).clip(RoundedCornerShape(16.dp)).background(highlight).then(gesture), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                AppIcon(icon, 30.dp * iconScale, look.iconShape, a.label, th)
                Spacer(Modifier.width(14.dp))
                Text(a.label, color = look.fg, fontSize = 22.sp, fontWeight = FontWeight.Light, maxLines = 1, fontFamily = look.face)
            }
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text(if (a.pkg in favs) "Remove from favourites" else "Add to favourites") }, { repo.toggleFavourite(a.pkg); menu = false })
            DropdownMenuItem({ Text("App info") }, { repo.appInfo(a.pkg); menu = false })
            // The same as dragging it onto a drop, for when a drag is awkward.
            if (fling != null) DeviceFling.targets().forEach { t ->
                DropdownMenuItem({ Text("Send to ${t.name}") }, { menu = false
                    scope.launch { val err = DeviceFling.provider?.app(t.id, a.pkg, a.label); fling.toast = if (err == null) "Sent ${a.label} to ${t.name}" else "Couldn't send to ${t.name}: $err" } })
            }
        }
    }
}

@Composable
private fun SearchSheet(repo: LauncherRepo, apps: List<HomeApp>, settings: LauncherSettings, onClose: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val results = remember(q, apps, settings.drawerSort, settings.searchShowsAll) {
        if (q.isBlank()) (if (settings.searchShowsAll) LauncherMath.sorted(apps, settings.drawerSort) else emptyList()) else LauncherMath.search(apps, q)
    }
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow)) }
    // The panel is dark whatever the look, so a light look draws its text light here, and its dark accent as a warm amber.
    val look = LocalLook.current
    val accent = if (look.light) Accent.Amber else LocalLauncherTheme.current
    androidx.compose.runtime.CompositionLocalProvider(LocalLook provides look.onSheet(), LocalLauncherTheme provides accent) {
    Column(Modifier.fillMaxSize().background(look.sheet).statusBarsPadding().navigationBarsPadding().imePadding()
        .offset { IntOffset(0, ((1f - enter.value) * 400).roundToInt()) }.padding(horizontal = 20.dp)) {
        // Results above, the box at the bottom where the thumb already is; the closest match sits right above the box.
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true) {
            // With reverseLayout the first item sits at the bottom, nearest the box: a web search for what is typed goes there when asked for.
            if (settings.searchWeb && q.isNotBlank()) item(key = "web") {
                Text("Search the web for \"${q.trim()}\"", color = LocalLauncherTheme.current.a, fontSize = 16.sp, maxLines = 1,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { if (repo.webSearch(q.trim())) onClose() }.padding(horizontal = 6.dp, vertical = 14.dp))
            }
            items(results, key = { it.pkg }) { a -> AppRow(repo, a, highlighted = a == results.firstOrNull() && q.isNotBlank(), delayIndex = 0, onOpen = { repo.launch(a.pkg); onClose() }, big = false, iconScale = settings.drawerIcons.scale, showIcon = settings.drawerShowIcons) }
        }
        LiquidInput(q, { q = it }, "Search apps", { results.firstOrNull()?.let { repo.launch(it.pkg); onClose() } }, Modifier.padding(vertical = 12.dp), sendLabel = "Open")
    }
    }
}
