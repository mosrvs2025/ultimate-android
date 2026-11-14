package app.ultimate.core

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * Liquid: the shared look for screens that show live, sensed or synced information. Dark glass surfaces, a slowly
 * turning teal-to-violet light along edges that are "alive" (a focused input, a syncing link), and pill shapes that
 * wrap rather than clip. Every piece sizes itself to the screen it is on: the narrowest phones in use (a folded Galaxy
 * Fold's outer screen is about 350 dp wide) get the same content, laid out to fit, never cut off.
 */
object Liquid {
    val bg = Color(0xFF0B0F14)
    val glass = Color(0xFF141B24)
    val glassTop = Color(0xFF1B2532)
    val line = Color(0xFF263040)
    val dim = Color(0xFF8A97A8)
    val teal = Color(0xFF3DDCD0)
    val violet = Color(0xFF9D8CFF)
    val green = Color(0xFF6CE59A)
    val amber = Color(0xFFFFC857)
    val red = Color(0xFFFF6B6B)
    val accent = listOf(teal, violet, Color(0xFF5AB8FF), teal)

    /** True on narrow screens (a folded Fold's outer screen, small phones), where layouts stack instead of sitting side by side. */
    @Composable fun compact(): Boolean = LocalConfiguration.current.screenWidthDp < 380

    /** Comfortable side padding for the screen's width. */
    @Composable fun gutter(): Dp = if (compact()) 12.dp else 16.dp
}

/** The angle of the light that travels around live edges, in degrees, turning once every [periodMs]. Still when animations are off. */
@Composable
fun liquidAngle(periodMs: Int = 6_000): Float {
    if (!motionOn()) return 45f
    val t by rememberInfiniteTransition(label = "liquid").animateFloat(0f, 360f, infiniteRepeatable(tween(periodMs, easing = LinearEasing)), label = "angle")
    return t
}

private fun sweep(angle: Float, colors: List<Color>, w: Float, h: Float): Brush {
    val r = Math.toRadians(angle.toDouble())
    val cx = w / 2; val cy = h / 2
    val dx = (cos(r) * w / 2).toFloat(); val dy = (sin(r) * h / 2).toFloat()
    return Brush.linearGradient(colors, Offset(cx - dx, cy - dy), Offset(cx + dx, cy + dy))
}

/** A dark glass card with a soft top light. [live] runs the moving light around its edge (for something syncing or recording). */
@Composable
fun GlassCard(modifier: Modifier = Modifier, title: String? = null, live: Boolean = false, accent: Color = Liquid.teal, content: @Composable ColumnScope.() -> Unit) {
    val angle = if (live) liquidAngle() else 0f
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier.fillMaxWidth().clip(shape)
            .background(Brush.verticalGradient(listOf(Liquid.glassTop, Liquid.glass)))
            .drawBehind {
                val stroke = 1.5.dp.toPx()
                val brush = if (live) sweep(angle, Liquid.accent, size.width, size.height) else Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.02f)))
                drawRoundRect(brush, Offset(stroke / 2, stroke / 2), size.copy(width = size.width - stroke, height = size.height - stroke), CornerRadius(22.dp.toPx()), style = Stroke(stroke))
            }
            .padding(horizontal = if (Liquid.compact()) 14.dp else 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (title != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(accent, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
        content()
    }
}

/**
 * A pill-shaped input that glows while you type in it, with a round send bubble that swells into view once there is something
 * to send. Replaces a plain outlined box plus a text button, which looked flat and wasted width on narrow screens.
 */
@Composable
fun LiquidInput(
    value: String, onValueChange: (String) -> Unit, placeholder: String, onSend: () -> Unit,
    modifier: Modifier = Modifier, numeric: Boolean = false, sendLabel: String = "Send", sendEnabled: Boolean = value.isNotBlank(),
) {
    val focus = LocalFocusManager.current
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val angle = if (focused) liquidAngle(3_000) else 0f
    val glow by animateFloatAsState(if (focused) 1f else 0f, spring(stiffness = Spring.StiffnessLow), label = "glow")
    val bubble by animateFloatAsState(if (sendEnabled) 1f else 0.82f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "bubble")
    val shape = RoundedCornerShape(28.dp)
    fun send() { if (sendEnabled) { focus.clearFocus(); onSend() } }
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape).background(Color(0xFF0F151D))
            .drawBehind {
                val stroke = (1.2f + 1.3f * glow).dp.toPx()
                val brush = if (glow > 0.01f) sweep(angle, Liquid.accent.map { it.copy(alpha = 0.35f + 0.65f * glow) }, size.width, size.height) else SolidColor(Liquid.line)
                drawRoundRect(brush, Offset(stroke / 2, stroke / 2), size.copy(width = size.width - stroke, height = size.height - stroke), CornerRadius(28.dp.toPx()), style = Stroke(stroke))
            }
            .padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, color = Liquid.dim, fontSize = 16.sp, maxLines = 2)
            BasicTextField(
                value, onValueChange, Modifier.fillMaxWidth(), singleLine = true, interactionSource = source,
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp), cursorBrush = SolidColor(Liquid.teal),
                keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(44.dp).scale(bubble).clip(CircleShape)
                .background(if (sendEnabled) Brush.linearGradient(listOf(Liquid.teal, Liquid.violet)) else SolidColor(Liquid.line))
                .clickable(enabled = sendEnabled) { send() }
                .semantics { contentDescription = sendLabel },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = if (sendEnabled) Color(0xFF0B0F14) else Liquid.dim) }
    }
}

/**
 * Suggestions as pills that wrap onto as many lines as they need. On a narrow screen a single scrolling row cut questions off
 * mid-word ("Which sensor disagree…"); wrapping shows every one whole.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SuggestionPills(items: List<String>, onPick: (String) -> Unit, modifier: Modifier = Modifier, selected: String? = null) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { s ->
            val on = s == selected
            Text(
                s, color = if (on) Color(0xFF0B0F14) else Color.White, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.clip(RoundedCornerShape(18.dp))
                    .background(if (on) Brush.linearGradient(listOf(Liquid.teal, Liquid.violet)) else Brush.linearGradient(listOf(Color(0xFF1C2734), Color(0xFF17202B))))
                    .border(1.dp, if (on) Color.Transparent else Liquid.line, RoundedCornerShape(18.dp))
                    .clickable { onPick(s) }
                    .heightIn(min = 36.dp)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}

/** A rounded pill button with a liquid gradient, for the one main action in a card. */
@Composable
fun LiquidButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    val colors = if (danger) listOf(Liquid.red, Color(0xFFFF8E6B)) else listOf(Liquid.teal, Liquid.violet)
    Box(
        modifier.heightIn(min = 52.dp).clip(RoundedCornerShape(26.dp))
            .background(if (enabled) Brush.horizontalGradient(colors) else SolidColor(Liquid.line))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 22.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) Color(0xFF0B0F14) else Liquid.dim, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
}

/**
 * Two devices as liquid drops joined by a stream of droplets while they sync, drawn with the gooey merge (Android 12 and newer)
 * so the drops melt into the stream. Idle, the drops sit apart and still. [left]/[right] name the devices under each drop.
 */
@Composable
fun LiquidSync(active: Boolean, left: String, right: String, modifier: Modifier = Modifier, status: String? = null) {
    val motion = motionOn()
    val phase = if (motion && active) rememberInfiniteTransition(label = "sync").animateFloat(0f, 1f, infiniteRepeatable(tween(1_800, easing = LinearEasing)), label = "p").value else 0f
    val joined by animateFloatAsState(if (active) 1f else 0f, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessVeryLow), label = "joined")
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(96.dp)) {
            Canvas(Modifier.fillMaxWidth().height(96.dp).gooey(12f)) {
                val cy = size.height / 2
                val r = 26.dp.toPx()
                val lx = size.width * 0.2f; val rx = size.width * 0.8f
                val brush = Brush.horizontalGradient(listOf(Liquid.teal, Liquid.violet), lx, rx)
                // Breathing drops.
                val breathe = 1f + 0.06f * sin(2 * PI.toFloat() * phase)
                drawCircle(brush, r * breathe, Offset(lx, cy))
                drawCircle(brush, r * (2f - breathe), Offset(rx, cy))
                if (joined > 0.01f) {
                    // Droplets flowing both ways along a gentle wave; the gooey merge fuses them into a stream near each drop.
                    for (i in 0 until 7) {
                        val f = ((phase + i / 7f) % 1f)
                        val back = i % 2 == 1
                        val x = if (back) rx - (rx - lx) * f else lx + (rx - lx) * f
                        val y = cy + 10.dp.toPx() * sin(2 * PI.toFloat() * (f + i * 0.13f)) * (if (back) -1 else 1)
                        val size = r * 0.34f * joined * (1f - 0.5f * kotlin.math.abs(f - 0.5f))
                        drawCircle(brush, size, Offset(x, y))
                    }
                    // A thin bridge that thickens as the link settles.
                    drawLine(brush, Offset(lx, cy), Offset(rx, cy), r * 0.22f * joined)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(left, color = Color.White, fontSize = 13.sp, modifier = Modifier.width(120.dp))
            Text(right, color = Color.White, fontSize = 13.sp, modifier = Modifier.width(120.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        status?.let { Spacer(Modifier.height(4.dp)); Text(it, color = if (active) Liquid.teal else Liquid.dim, fontSize = 13.sp) }
    }
}

/** A metric tile: label, a big value, and a small line under it. Shrinks its type a step on narrow screens rather than wrapping a number. */
@Composable
fun LiquidMetric(label: String, value: String, sub: String, modifier: Modifier = Modifier, accent: Color = Liquid.teal) {
    val compact = Liquid.compact()
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(Brush.verticalGradient(listOf(Liquid.glassTop, Liquid.glass)))
        .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(18.dp)).padding(if (compact) 12.dp else 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(6.dp).background(accent, CircleShape)); Spacer(Modifier.width(6.dp)); Text(label, color = Liquid.dim, fontSize = 12.sp) }
        Text(value, color = Color.White, fontSize = if (compact) 22.sp else 26.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        if (sub.isNotBlank()) Text(sub, color = Liquid.dim, fontSize = 12.sp, maxLines = 1)
    }
}
