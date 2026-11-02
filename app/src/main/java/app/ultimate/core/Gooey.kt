package app.ultimate.core

import android.os.Build
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * Gooey building blocks. Each one draws its moving shapes in a single Canvas with Modifier.gooey(), so shapes that
 * come close melt together. Below Android 12 gooey does nothing and the same shapes are simply drawn plain, which
 * still looks right: every shape here makes sense on its own. When the person has turned animations off in Android's
 * settings, nothing loops and every change snaps.
 */

/** Whether animations are on, from Android's "Animator duration scale" (0 means the person turned them off). */
object MotionPrefs {
    fun on(scale: Float): Boolean = scale > 0f
}

/** True unless animations are turned off on this phone. Read once per screen. */
@Composable
fun motionOn(): Boolean {
    val context = LocalContext.current
    return remember { MotionPrefs.on(runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)) }
}

/** Whether this phone can draw the gooey merge at all (Android 12 and newer). */
val gooeySupported: Boolean get() = Build.VERSION.SDK_INT >= 31

/** The arithmetic behind the gooey pieces, kept apart from drawing so it can be tested. */
object GooeyMath {
    fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** Centre of a switch thumb travelling from [inset] to width - [inset] as [pos] goes 0 to 1. */
    fun thumbX(pos: Float, width: Float, inset: Float) = lerp(inset, width - inset, pos.coerceIn(0f, 1f))

    /** Where droplet [i] of [n] sits when [t] of the way from a ring of radius [r] into the centre. */
    fun converge(i: Int, n: Int, t: Float, r: Float): Offset {
        val a = (2 * Math.PI * i / n - Math.PI / 2).toFloat()
        val d = r * (1f - t.coerceIn(0f, 1f))
        return Offset(kotlin.math.cos(a) * d, kotlin.math.sin(a) * d)
    }

    /** The blobs of a progress bar that does not know how far along it is: x centres, as fractions of the width. */
    fun flowing(phase: Float): List<Float> = listOf(0f to 1f, 0.35f to 1.6f, 0.7f to 0.8f).map { (offset, speed) -> ((phase * speed + offset) % 1f) * 1.3f - 0.15f }
}

private fun <T> moveSpec(motion: Boolean, stiff: Float, bounce: Float = Spring.DampingRatioNoBouncy): AnimationSpec<T> = if (motion) spring(bounce, stiff) else snap()

/**
 * A row of choices with a pill that slides to the selected one. A second, slower pill follows it, so on the way the
 * pill stretches like a drop and snaps back when it lands. With [scrollable] the row scrolls sideways and keeps the
 * selected choice in view; otherwise every choice gets an equal share of the width.
 */
@Composable
fun GooeyTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, scrollable: Boolean = false) {
    val motion = motionOn()
    val haptics = LocalHapticFeedback.current
    val spots = remember(labels) { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val target = spots[selected]
    val left by animateFloatAsState(target?.first ?: 0f, moveSpec(motion, Spring.StiffnessMedium), label = "tabL")
    val width by animateFloatAsState(target?.second ?: 0f, moveSpec(motion, Spring.StiffnessMedium), label = "tabW")
    val left2 by animateFloatAsState(target?.first ?: 0f, moveSpec(motion, Spring.StiffnessLow), label = "tabL2")
    val width2 by animateFloatAsState(target?.second ?: 0f, moveSpec(motion, Spring.StiffnessLow), label = "tabW2")
    val scroll = rememberScrollState()
    if (scrollable) LaunchedEffect(selected, target) { target?.let { scroll.animateScrollTo((it.first - 48f).toInt().coerceAtLeast(0)) } }
    val pill = MaterialTheme.colorScheme.primary
    val outer = if (scrollable) modifier.fillMaxWidth().horizontalScroll(scroll) else modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
    Box(outer.padding(3.dp)) {
        Canvas(Modifier.matchParentSize().gooey(6f)) {
            if (target == null) return@Canvas
            val h = size.height
            drawRoundRect(pill, Offset(left, 0f), Size(width, h), CornerRadius(h / 2))
            drawRoundRect(pill, Offset(left2, h * 0.12f), Size(width2, h * 0.76f), CornerRadius(h / 2))
        }
        Row(if (scrollable) Modifier else Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (scrollable) 4.dp else 0.dp)) {
            labels.forEachIndexed { i, text ->
                val color by animateColorAsState(if (i == selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, label = "tabC")
                Box(
                    (if (scrollable) Modifier else Modifier.weight(1f))
                        .onGloballyPositioned { spots[i] = it.positionInParent().x to it.size.width.toFloat() }
                        .clip(RoundedCornerShape(50))
                        .selectable(i == selected, role = Role.Tab) { if (i != selected) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onSelect(i) }
                        .heightIn(min = 48.dp).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(text, color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) }
            }
        }
    }
}

/**
 * An on/off switch whose thumb is a drop: it springs across the track and a smaller drop trails it, so the thumb
 * stretches while it moves and gathers itself back up when it stops. Same shape of call as Material's Switch.
 */
@Composable
fun GooeySwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val motion = motionOn()
    val haptics = LocalHapticFeedback.current
    val goal = if (checked) 1f else 0f
    val pos by animateFloatAsState(goal, moveSpec(motion, Spring.StiffnessMedium, Spring.DampingRatioMediumBouncy), label = "thumb")
    val lag by animateFloatAsState(goal, moveSpec(motion, Spring.StiffnessLow), label = "thumbLag")
    val track by animateColorAsState(if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest, label = "track")
    val thumb by animateColorAsState(if (checked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.outline, label = "thumbC")
    val edge = MaterialTheme.colorScheme.outline
    val toggle = if (onCheckedChange != null) Modifier.toggleable(checked, enabled = enabled, role = Role.Switch) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onCheckedChange(it) } else Modifier
    Box(modifier.minimumInteractiveComponentSize().then(toggle).alpha(if (enabled) 1f else 0.38f), contentAlignment = Alignment.Center) {
        Box(Modifier.size(52.dp, 32.dp).drawBehind {
            drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
            if (pos < 1f) drawRoundRect(edge.copy(alpha = 1f - pos), topLeft = Offset(1.dp.toPx(), 1.dp.toPx()), size = Size(size.width - 2.dp.toPx(), size.height - 2.dp.toPx()), cornerRadius = CornerRadius(size.height / 2), style = Stroke(2.dp.toPx()))
        }) {
            Canvas(Modifier.matchParentSize().gooey(4f)) {
                val r = GooeyMath.lerp(8.dp.toPx(), 12.dp.toPx(), pos.coerceIn(0f, 1.2f))
                val inset = 16.dp.toPx()
                drawCircle(thumb, r, Offset(GooeyMath.thumbX(pos, size.width, inset), size.height / 2))
                drawCircle(thumb, r * 0.75f, Offset(GooeyMath.thumbX(lag, size.width, inset), size.height / 2))
            }
        }
    }
}

/**
 * A progress bar whose fill is liquid: it flows forward on a spring with a slower body following it, and a droplet
 * buds off the leading edge and falls back in while work runs. Same shape of call as Material's LinearProgressIndicator.
 * Pass [live] = false for bars inside lists or bars that only show a proportion: they still flow when the value
 * changes, but no droplet loops.
 */
@Composable
fun GooeyProgress(progress: () -> Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest, live: Boolean = true) {
    val motion = motionOn()
    val p = progress().coerceIn(0f, 1f).let { if (it.isNaN()) 0f else it }
    val head by animateFloatAsState(p, moveSpec(motion, Spring.StiffnessMediumLow), label = "head")
    val body by animateFloatAsState(p, moveSpec(motion, Spring.StiffnessVeryLow), label = "body")
    val working = live && motion && p > 0.005f && p < 0.995f
    val bob = if (working) rememberInfiniteTransition(label = "bob").animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "bob").value else 0f
    Box(modifier.defaultMinSize(minHeight = 6.dp).progressSemantics(p).drawBehind { drawRoundRect(trackColor, cornerRadius = CornerRadius(size.height / 2)) }) {
        Spacer(Modifier.matchParentSize().gooey(2f).drawBehind {
            val h = size.height
            if (head > 0.001f) drawRoundRect(color, size = Size((size.width * head).coerceAtLeast(h), h), cornerRadius = CornerRadius(h / 2))
            if (body > 0.001f) drawRoundRect(color, topLeft = Offset(0f, h * 0.2f), size = Size((size.width * body).coerceAtLeast(h), h * 0.6f), cornerRadius = CornerRadius(h / 2))
            if (working) drawCircle(color, h * 0.42f, Offset(size.width * head + h * 0.2f + bob * h * 1.1f, h / 2))
        })
    }
}

/** A progress bar for work of unknown length: three drops flow along the track at different speeds, merging as they pass. */
@Composable
fun GooeyProgress(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest) {
    val motion = motionOn()
    val phase = if (motion) rememberInfiniteTransition(label = "flow").animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "flow").value else 0f
    Box(modifier.defaultMinSize(minHeight = 6.dp).progressSemantics().drawBehind { drawRoundRect(trackColor, cornerRadius = CornerRadius(size.height / 2)) }) {
        Spacer(Modifier.matchParentSize().gooey(2f).drawBehind {
            val h = size.height
            if (!motion) { drawRoundRect(color, size = Size(size.width * 0.3f, h), cornerRadius = CornerRadius(h / 2)); return@drawBehind }
            GooeyMath.flowing(phase).forEachIndexed { i, x -> val w = size.width * (0.12f + 0.05f * i); drawRoundRect(color, Offset(size.width * x - w / 2, 0f), Size(w, h), CornerRadius(h / 2)) }
        })
    }
}

/** Shorthand used by the Footprint page and others: a gooey pill row sized for a section bar. */
@Composable
fun GooeyTabsBar(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) =
    GooeyTabs(labels, selected, onSelect, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), scrollable = true)
