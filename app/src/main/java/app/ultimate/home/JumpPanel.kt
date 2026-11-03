package app.ultimate.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.ultimate.UltimateApp
import app.ultimate.core.GooeyMath
import app.ultimate.core.Tool
import app.ultimate.core.appear
import app.ultimate.core.gooey
import app.ultimate.core.motionOn
import app.ultimate.core.pressScale
import kotlin.math.roundToInt

/** Geometry of the bubble turning into the panel, kept pure so it is tested. */
object JumpMath {
    /** The shape at [t] (0 the bubble, 1 the panel): a rounded rectangle growing from one to the other. */
    fun morph(bubble: Rect, panel: Rect, t: Float): Pair<Rect, Float> {
        val k = t.coerceIn(0f, 1.05f)
        fun l(a: Float, b: Float) = GooeyMath.lerp(a, b, k)
        val r = Rect(l(bubble.left, panel.left), l(bubble.top, panel.top), l(bubble.right, panel.right), l(bubble.bottom, panel.bottom))
        val corner = l(bubble.width / 2, 28f * (panel.width / 360f).coerceIn(1f, 4f))
        return r to corner
    }

    /** Tools matching what was typed, by name or description; everything when nothing is typed. */
    fun search(tools: List<Tool>, query: String): List<Tool> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return tools
        return tools.filter { q in it.title.lowercase() || q in it.tagline.lowercase() || q in it.id }.sortedBy { if (it.title.lowercase().startsWith(q)) 0 else 1 }
    }
}

/**
 * The jump panel: the floating bubble melts and stretches into a panel next to it (with drops trailing from where it
 * sat), then the panel's contents rise in. Search, the tools you use most, every tool by family, and Home, Library
 * and Activity. Closing pours it back into the bubble.
 */
@Composable
internal fun JumpPanel(app: UltimateApp, current: Tool, running: Set<String>, open: Boolean, bubble: Rect, onClose: () -> Unit) {
    val motion = motionOn()
    val t = remember { Animatable(0f) }
    LaunchedEffect(open) {
        if (!motion) t.snapTo(if (open) 1f else 0f)
        else if (open) t.animateTo(1f, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow)) else t.animateTo(0f, tween(220))
    }
    if (!open && t.value <= 0.001f) return
    val density = LocalDensity.current
    val fill = MaterialTheme.colorScheme.surfaceContainerHigh
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        val margin = with(density) { 12.dp.toPx() }
        val panelH = minOf(h * 0.74f, with(density) { 600.dp.toPx() })
        val panel = Rect(margin, h - panelH - margin, w - margin, h - margin)
        // The dim behind, and a tap outside to close.
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.4f * t.value.coerceIn(0f, 1f)))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClose))
        // The liquid: the bubble's drop stretching into the panel, three droplets trailing behind on a neck.
        Canvas(Modifier.fillMaxSize().gooey(14f)) {
            val (r, corner) = JumpMath.morph(bubble, panel, t.value)
            drawRoundRect(fill, Offset(r.left, r.top), Size(r.width, r.height), CornerRadius(corner))
            val neck = (1f - t.value).coerceIn(0f, 1f)
            if (neck > 0.02f) {
                val near = Offset(if (bubble.center.x > w / 2) r.right - corner else r.left + corner, r.top + corner)
                for (k in 1..3) { val f = k / 4f; drawCircle(fill, bubble.width * 0.32f * neck, bubble.center + (near - bubble.center) * f) }
            }
        }
        val inside = ((t.value - 0.55f) / 0.45f).coerceIn(0f, 1f)
        if (inside > 0f) Box(
            Modifier.offset { IntOffset(panel.left.roundToInt(), panel.top.roundToInt()) }.size(with(density) { panel.width.toDp() }, with(density) { panel.height.toDp() })
                .alpha(inside).clickable(remember { MutableInteractionSource() }, null) {},
        ) { Contents(app, current, running) { go -> onClose(); go() } }
    }
}

@Composable
private fun Contents(app: UltimateApp, current: Tool, running: Set<String>, go: (() -> Unit) -> Unit) {
    val shell = app.shell
    var query by remember { mutableStateOf("") }
    val seen = remember { mutableSetOf<Any>() }
    val now = remember { System.currentTimeMillis() }
    val recent = remember { HomeLayout.favorites(app.tools.map { it.id }, app.insights::opens, app.insights::lastAt, running, now).mapNotNull { id -> app.tools.firstOrNull { it.id == id } } }
    fun open(t: Tool) = go { if (t.id != current.id) shell.open(t) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Search, as a soft pill.
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Jump to a tool", color = MaterialTheme.colorScheme.onSurfaceVariant)
                BasicTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary))
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (query.isBlank()) {
                if (recent.isNotEmpty()) {
                    Label("You use most")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        recent.forEachIndexed { i, t -> Bigger(t, t.id == current.id, t.id in running, Modifier.appear("r-${t.id}", seen, i)) { open(t) } }
                    }
                }
                var n = recent.size
                toolSections(app.tools).filter { it.second.isNotEmpty() }.forEach { (family, tools) ->
                    Label(family)
                    tools.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { t -> Line(t, t.id == current.id, t.id in running, Modifier.weight(1f).appear("t-${t.id}", seen, n++)) { open(t) } }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            } else {
                val found = JumpMath.search(app.tools, query)
                if (found.isEmpty()) Text("No tool called that.", Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                found.forEach { t -> Line(t, t.id == current.id, t.id in running, Modifier.fillMaxWidth()) { open(t) } }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(Icons.Rounded.Home, "Home", Modifier.weight(1f)) { go { shell.showHome() } }
            Pill(Icons.Rounded.VideoLibrary, "Library", Modifier.weight(1f)) { go { shell.showLibrary() } }
            Pill(Icons.Rounded.History, "Activity", Modifier.weight(1f), busy = running.isNotEmpty()) { go { shell.showActivity() } }
        }
    }
}

@Composable private fun Label(text: String) = Text(text, Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)

/** A tool as a compact row: round icon and name. The current tool is filled in. */
@Composable
private fun Line(t: Tool, current: Boolean, busy: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.pressScale(0.96f).clip(RoundedCornerShape(18.dp)).background(if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(t.icon, busy, 34)
        Spacer(Modifier.width(10.dp))
        Text(t.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A most-used tool: a bigger round icon with its name under it. */
@Composable
private fun Bigger(t: Tool, current: Boolean, busy: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.width(72.dp).pressScale(0.94f).clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Dot(t.icon, busy, 48, filled = current)
        Text(t.title, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Dot(icon: ImageVector, busy: Boolean, size: Int, filled: Boolean = false) = Box {
    Box(Modifier.size(size.dp).clip(CircleShape).background(if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size((size * 0.52f).dp), tint = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer)
    }
    if (busy) Box(Modifier.align(Alignment.TopEnd).size(9.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
}

@Composable
private fun Pill(icon: ImageVector, label: String, modifier: Modifier, busy: Boolean = false, onClick: () -> Unit) {
    Row(modifier.pressScale(0.95f).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerLowest).clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(label, style = MaterialTheme.typography.labelLarge)
        if (busy) { Spacer(Modifier.width(6.dp)); Box(Modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error)) }
    }
}
