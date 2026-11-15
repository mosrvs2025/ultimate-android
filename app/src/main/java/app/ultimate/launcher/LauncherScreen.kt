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
fun LauncherHome(repo: LauncherRepo, homePresses: Int, onOpenUltimate: () -> Unit, autoLoad: Boolean = true) {
    val apps by repo.apps.collectAsState()
    val favs by repo.favourites.collectAsState()
    LaunchedEffect(Unit) { if (autoLoad) repo.load() }
    val letters = remember(apps) { LauncherMath.letters(apps) }
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val motion = motionOn()

    var searching by remember { mutableStateOf(false) }
    var sticky by remember { mutableStateOf<Char?>(null) }
    // Pressing Home while already home closes whatever is open, like every launcher.
    LaunchedEffect(homePresses) { searching = false; sticky = null }
    BackHandler(enabled = searching || sticky != null) { searching = false; sticky = null }

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

    // Springs: the wave swells in and out with a bounce, and its centre follows the finger a moment behind, so a quick slide
    // overshoots and wobbles back like jelly. With animations off it simply snaps.
    val wave by animateFloatAsState(if (dragging) 1f else 0f, if (motion) spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow) else spring(1f, 10_000f), label = "wave")
    val follow by animateFloatAsState(fingerY, if (motion) spring(0.45f, Spring.StiffnessMediumLow) else spring(1f, 10_000f), label = "follow")
    val inset = with(density) { 22.dp.toPx() }
    val usable = (railHeight - 2 * inset).coerceAtLeast(1f)
    val index = LauncherMath.index(LauncherMath.position(fingerY, inset, usable, letters.size), letters.size)
    val current: Char? = if (dragging) letters.getOrNull(index) else sticky
    LaunchedEffect(index, dragging) { if (dragging) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    val pop = remember { Animatable(1f) }
    LaunchedEffect(current) { if (current != null && motion) { pop.snapTo(0.6f); pop.animateTo(1f, spring(Spring.DampingRatioHighBouncy, Spring.StiffnessMedium)) } }

    val shown = current?.let { LauncherMath.appsFor(apps, it) }.orEmpty()
    // The rail's touch handler runs for the whole drag, so it reads the newest values through these instead of restarting
    // (a restart in the middle of a drag would drop the finger).
    val lettersNow by androidx.compose.runtime.rememberUpdatedState(letters)
    val appsNow by androidx.compose.runtime.rememberUpdatedState(apps)
    val rowPx = with(density) { 56.dp.toPx() }

    val flingDrag = remember { FlingDrag() }
    androidx.compose.runtime.CompositionLocalProvider(LocalFlingDrag provides flingDrag) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF070A0F), Color(0xFF0E141C), Color(0xFF0A0E14))))
        .pointerInput(Unit) { detectVerticalDragGestures { _, dy -> if (dy < -18f && !dragging) searching = true } }) {

        // ---- left: clock, favourites, or the letter's apps
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(start = 28.dp, end = 96.dp, top = 48.dp)) {
            Clock(onOpenUltimate)
            Spacer(Modifier.height(36.dp))
            // What is worth offering right now: refreshed every half minute and whenever you come home.
            var nowItems by remember { mutableStateOf<List<Now>>(emptyList()) }
            LaunchedEffect(homePresses, apps.size) { while (true) { nowItems = repo.now(); delay(30_000) } }
            if (nowItems.isNotEmpty() && current == null) { NowRow(repo, nowItems); Spacer(Modifier.height(20.dp)) }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                val favAlpha = (1f - (if (current != null) 1f else 0f)).coerceIn(0f, 1f)
                Column(Modifier.alpha(favAlpha), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    favs.forEachIndexed { i, pkg ->
                        val a = apps.firstOrNull { it.pkg == pkg } ?: return@forEachIndexed
                        AppRow(repo, a, highlighted = false, delayIndex = i, onOpen = { repo.launch(a.pkg) }, big = true)
                    }
                    if (favs.isEmpty() && apps.isNotEmpty()) Text("Long-press any app to make it a favourite.", color = Liquid.dim, fontSize = 14.sp)
                }
            }
        }

        // ---- the letter's apps, springing in level with the finger
        if (current != null && shown.isNotEmpty()) {
            val panelH = rowPx * shown.size
            val wanted = railTop + (if (dragging) fingerY else railHeight / 2) - panelH / 2
            val screenH = with(density) { 800.dp.toPx() }
            val top = wanted.coerceIn(with(density) { 120.dp.toPx() }, (railTop + railHeight - panelH).coerceAtLeast(with(density) { 120.dp.toPx() }).coerceAtMost(screenH))
            Column(Modifier.offset { IntOffset(with(density) { 28.dp.roundToPx() }, top.roundToInt()) }.width(260.dp)
                .onGloballyPositioned { panelTop = it.positionInRoot().y }) {
                shown.forEachIndexed { i, a ->
                    AppRow(repo, a, highlighted = hovered == a.pkg, delayIndex = i, onOpen = { repo.launch(a.pkg); sticky = null }, big = false)
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
                            val l = lettersNow.getOrNull(LauncherMath.index(LauncherMath.position(fingerY, inset, (size.height - 2 * inset).coerceAtLeast(1f), lettersNow.size), lettersNow.size))
                            val list = l?.let { LauncherMath.appsFor(appsNow, it) }.orEmpty()
                            val row = ((railTop + fingerY - panelTop) / rowPx).toInt()
                            list.getOrNull(row)?.pkg
                        } else null
                    }
                    val open = hovered
                    val letter = lettersNow.getOrNull(LauncherMath.index(LauncherMath.position(fingerY, inset, (size.height - 2 * inset).coerceAtLeast(1f), lettersNow.size), lettersNow.size))
                    dragging = false; hovered = null
                    if (open != null) repo.launch(open) else sticky = letter
                }
            }) {
            RailVisual(letters, follow, wave, pop.value, current, dragging)
        }

        // ---- flinging an app to another device
        FlingLayer(repo, flingDrag)

        // ---- search
        if (searching) SearchSheet(repo, apps, onClose = { searching = false })
    }
    }
}

/** The "Now" offers as liquid pods: each says what and why, springs in, and is one tap away. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun NowRow(repo: LauncherRepo, items: List<Now>, onDone: () -> Unit = {}) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEachIndexed { i, n ->
            val motion = motionOn()
            val enter = remember(n) { Animatable(if (motion) 0f else 1f) }
            LaunchedEffect(n) { if (motion) { delay(70L * i); enter.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)) } }
            var icon by remember(n) { mutableStateOf((n as? Now.App)?.let { repo.icon(it.pkg) }) }
            LaunchedEffect(n) { if (n is Now.App && icon == null) icon = repo.loadIcon(n.pkg) }
            Row(Modifier.scale(0.6f + 0.4f * enter.value).alpha(enter.value.coerceIn(0f, 1f)).clip(RoundedCornerShape(22.dp))
                .background(Brush.linearGradient(listOf(Liquid.teal.copy(alpha = 0.22f), Liquid.violet.copy(alpha = 0.18f))))
                .androidxBorder()
                .combinedClickable(onClick = { when (n) { is Now.App -> repo.launch(n.pkg); is Now.Action -> repo.act(n.id) }; onDone() })
                .padding(start = 8.dp, end = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Liquid.teal, Liquid.violet))), contentAlignment = Alignment.Center) {
                    val ic = icon
                    if (ic != null) Image(ic, null, Modifier.fillMaxSize()) else Text(if (n is Now.Action) "●" else n.label.take(1), color = Color(0xFF0B0F14), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(n.label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(n.because, color = Liquid.dim, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }
}

private fun Modifier.androidxBorder(): Modifier = this.then(Modifier.border(1.dp, Brush.linearGradient(listOf(Liquid.teal.copy(alpha = 0.6f), Liquid.violet.copy(alpha = 0.4f))), RoundedCornerShape(22.dp)))

/**
 * The rail itself, drawing only: the letters, the wave they make around the finger, and the liquid drop that melts out of the
 * spine with the current letter in it. [follow] is where the finger is (lagging on a spring), [wave] how strongly the rail is
 * swollen (0 to 1, springing), [pop] the bubble's bounce. Shared by the home screen and the edge rail over other apps.
 */
@Composable
internal fun RailVisual(letters: List<Char>, follow: Float, wave: Float, pop: Float, current: Char?, dragging: Boolean, modifier: Modifier = Modifier.fillMaxSize()) {
    val measurer = rememberTextMeasurer()
    Box(modifier) {
        // The liquid: a spine, a drop under the finger, and two beads joining them, melted together by the gooey effect.
        Canvas(Modifier.fillMaxSize().gooey(14f)) {
            val spineX = size.width - 18.dp.toPx()
            if (wave > 0.02f) {
                val y = follow.coerceIn(0f, size.height)
                val reach = 64.dp.toPx() * wave
                val brush = Brush.verticalGradient(listOf(Liquid.teal, Liquid.violet), y - 120.dp.toPx(), y + 120.dp.toPx())
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
                val b = LauncherMath.bulge(i, pos) * wave
                val selected = current == ch && dragging
                val x = spineX - b * 44.dp.toPx()
                val style = TextStyle(color = if (selected) Color(0xFF0B0F14) else Color.White.copy(alpha = 0.45f + 0.55f * b), fontSize = (11f + 9f * b).sp, fontWeight = if (selected || b > 0.6f) FontWeight.Bold else FontWeight.Medium)
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Clock(onOpenUltimate: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(60_000 - now % 60_000 + 50) } }
    val d = Date(now)
    val day = SimpleDateFormat("EEE", Locale.getDefault()).format(d).uppercase().take(3)
    Row(verticalAlignment = Alignment.Bottom) {
        Text(day, style = TextStyle(brush = Brush.linearGradient(listOf(Color.White, Liquid.teal, Liquid.violet)), fontSize = 64.sp, fontWeight = FontWeight.Thin, letterSpacing = 10.sp))
    }
    Text(SimpleDateFormat("HH:mm · d MMMM", Locale.getDefault()).format(d), color = Color.White.copy(alpha = 0.75f), fontSize = 16.sp, letterSpacing = 1.sp)
    // A quiet way into Ultimate itself.
    Row(Modifier.padding(top = 10.dp).clip(RoundedCornerShape(14.dp)).combinedClickable(onClick = onOpenUltimate).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(Brush.linearGradient(listOf(Liquid.teal, Liquid.violet)), CircleShape))
        Spacer(Modifier.width(8.dp))
        Text("Ultimate", color = Liquid.dim, fontSize = 13.sp)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun AppRow(repo: LauncherRepo, a: HomeApp, highlighted: Boolean, delayIndex: Int, onOpen: () -> Unit, big: Boolean) {
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
    // Tap opens; a long-press that stays put opens the menu; a long-press that pulls away carries the app to another device.
    val gesture = if (fling == null) Modifier.combinedClickable(onClick = onOpen, onLongClick = { menu = true })
        else Modifier.onGloballyPositioned { origin = it.positionInRoot() }.pointerInput(a.pkg) {
            awaitEachGesture {
                val down = awaitFirstDown()
                var timedOut = true
                val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation().also { timedOut = false } }
                if (!timedOut) { if (up != null) { up.consume(); openNow() }; return@awaitEachGesture }
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                down.consume()
                if (!fling.start(a, origin + down.position)) { menu = true; return@awaitEachGesture }
                var moved = 0f
                while (true) {
                    val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) { c.consume(); break }
                    val d = c.position - c.previousPosition
                    moved += d.getDistance(); fling.pos += d; c.consume()
                }
                if (moved < viewConfiguration.touchSlop * 2) { fling.cancel(); menu = true; return@awaitEachGesture }
                val hit = fling.release() ?: return@awaitEachGesture
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                scope.launch { val err = DeviceFling.provider?.app(hit.second.id, hit.first.pkg, hit.first.label)
                    fling.toast = if (err == null) "Sent ${hit.first.label} to ${hit.second.name}" else "Couldn't send to ${hit.second.name}: $err" }
            }
        }
    Box {
        Row(Modifier.fillMaxWidth().height(if (big) 52.dp else 56.dp)
            .offset { IntOffset(((1f - enter.value) * 120).roundToInt(), 0) }.alpha(enter.value.coerceIn(0f, 1f)).scale(lift)
            .clip(RoundedCornerShape(16.dp))
            .background(if (highlighted) Brush.horizontalGradient(listOf(Liquid.teal.copy(alpha = 0.35f), Liquid.violet.copy(alpha = 0.18f))) else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)))
            .then(gesture).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(if (big) 30.dp else 34.dp)) { icon?.let { Image(it, null, Modifier.fillMaxSize()) } }
            Spacer(Modifier.width(14.dp))
            Text(a.label, color = Color.White, fontSize = if (big) 20.sp else 17.sp, fontWeight = if (big) FontWeight.Light else FontWeight.Normal, maxLines = 1)
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
private fun SearchSheet(repo: LauncherRepo, apps: List<HomeApp>, onClose: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val results = remember(q, apps) { if (q.isBlank()) apps else LauncherMath.search(apps, q) }
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow)) }
    Column(Modifier.fillMaxSize().background(Color(0xF00A0E14)).statusBarsPadding().navigationBarsPadding().imePadding()
        .offset { IntOffset(0, ((1f - enter.value) * 400).roundToInt()) }.padding(horizontal = 20.dp)) {
        // Results above, the box at the bottom where the thumb already is; the closest match sits right above the box.
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true) {
            items(results.take(60), key = { it.pkg }) { a -> AppRow(repo, a, highlighted = a == results.firstOrNull() && q.isNotBlank(), delayIndex = 0, onOpen = { repo.launch(a.pkg); onClose() }, big = false) }
        }
        LiquidInput(q, { q = it }, "Search apps", { results.firstOrNull()?.let { repo.launch(it.pkg); onClose() } }, Modifier.padding(vertical = 12.dp), sendLabel = "Open")
    }
}
