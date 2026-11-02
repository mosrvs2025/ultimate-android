package app.ultimate.core

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/** How the companion feels, shown in its eyes and mouth. */
enum class Mood { Happy, Calm, Worried, Sleepy, Proud }

/** Where the pupils sit, as a fraction of how far they can move, looking from [face] towards [target]. Pure, so it is tested. */
object FaceMath {
    fun gaze(face: Offset, target: Offset?, reach: Float): Offset {
        if (target == null) return Offset.Zero
        val d = target - face
        val len = d.getDistance()
        if (len < 1f) return Offset.Zero
        // Close by, the eyes follow exactly; far away they sit at the edge of the eye, looking that way.
        val k = (len / reach).coerceAtMost(1f) / len
        return Offset(d.x * k, d.y * k)
    }
}

/**
 * A little blueberry: a jelly body drawn with the gooey effect (its edge wobbles as small drops slide around inside
 * it), eyes that look at [lookAt] (a point in root coordinates, for example the finger) and blink, and a mouth for its
 * [mood]. Tapping it makes it squish. With animations off it holds still but still looks and changes mood.
 */
@Composable
fun BlobFace(mood: Mood, lookAt: Offset?, modifier: Modifier = Modifier, size: Dp = 180.dp, body: Color = Color(0xFF4F6BED), onTap: (() -> Unit)? = null) {
    val motion = motionOn()
    val haptics = LocalHapticFeedback.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val gaze = FaceMath.gaze(bounds.center, lookAt, bounds.width * 0.6f)
    val gx by animateFloatAsState(gaze.x, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow), label = "gx")
    val gy by animateFloatAsState(gaze.y, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow), label = "gy")
    val wobble = if (motion && mood != Mood.Sleepy) rememberInfiniteTransition(label = "wobble").animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(5200, easing = LinearEasing)), label = "w").value else 0f
    val blink = remember { Animatable(1f) }
    if (motion && mood != Mood.Sleepy) LaunchedEffect(Unit) { while (true) { delay(2600L + (Math.random() * 2600).toLong()); blink.animateTo(0.08f, tween(70)); blink.animateTo(1f, tween(110)) } }
    val squish = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val smile by animateFloatAsState(when (mood) { Mood.Happy -> 1f; Mood.Proud -> 1.4f; Mood.Calm -> 0.4f; Mood.Sleepy -> 0.2f; Mood.Worried -> -0.8f }, spring(Spring.DampingRatioMediumBouncy), label = "smile")
    val worry by animateFloatAsState(if (mood == Mood.Worried) 1f else 0f, label = "worry")
    val asleep by animateFloatAsState(if (mood == Mood.Sleepy) 1f else 0f, label = "asleep")
    val description = when (mood) { Mood.Happy -> "Your companion, happy"; Mood.Proud -> "Your companion, proud of you"; Mood.Calm -> "Your companion"; Mood.Worried -> "Your companion, a little worried"; Mood.Sleepy -> "Your companion, resting" }

    Box(
        modifier.size(size).onGloballyPositioned { bounds = it.boundsInRoot() }
            .semantics { contentDescription = description }
            .graphicsLayer { scaleX = 2f - squish.value; scaleY = squish.value }
            .then(if (onTap == null && !motion) Modifier else Modifier.clickable(remember { MutableInteractionSource() }, indication = null) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                if (motion) scope.launch { squish.snapTo(0.82f); squish.animateTo(1f, spring(Spring.DampingRatioHighBouncy, Spring.StiffnessMedium)) }
                onTap?.invoke()
            }),
    ) {
        // The body: one big drop and three smaller ones sliding around its rim, melted together.
        Canvas(Modifier.size(size).gooey(size.value / 14f)) {
            val c = center; val r = this.size.minDimension * 0.36f
            drawCircle(body, r, c)
            for (i in 0 until 3) {
                val a = wobble + i * 2.1f
                drawCircle(body, r * 0.42f, c + Offset(cos(a) * r * 0.68f, sin(a * 1.3f) * r * 0.62f))
            }
            // The blueberry's little crown on top.
            drawCircle(body.copy(red = body.red * 0.7f, green = body.green * 0.7f, blue = body.blue * 0.75f), r * 0.17f, c + Offset(0f, -r * 1.02f))
        }
        Canvas(Modifier.size(size)) {
            val c = center; val r = this.size.minDimension * 0.36f
            val eyeR = r * 0.2f; val eyeY = c.y - r * 0.12f
            val reach = eyeR * 0.45f
            for (side in listOf(-1f, 1f)) {
                val eye = Offset(c.x + side * r * 0.36f, eyeY)
                if (asleep > 0.5f) {
                    drawArc(Color.White, 20f, 140f, false, Offset(eye.x - eyeR, eye.y - eyeR * 0.6f), Size(eyeR * 2, eyeR * 1.2f), style = Stroke(r * 0.05f, cap = StrokeCap.Round))
                } else {
                    val h = eyeR * 2 * blink.value
                    drawOval(Color.White, Offset(eye.x - eyeR, eye.y - h / 2), Size(eyeR * 2, h))
                    if (blink.value > 0.3f) drawCircle(Color(0xFF1B1F3B), eyeR * 0.5f, eye + Offset(gx * reach, gy * reach * blink.value))
                    if (blink.value > 0.3f) drawCircle(Color.White, eyeR * 0.16f, eye + Offset(gx * reach - eyeR * 0.15f, gy * reach - eyeR * 0.18f))
                }
                // Worried brows tilt up in the middle.
                if (worry > 0.01f) {
                    val browY = eyeY - eyeR * 1.45f; val lift = eyeR * 0.45f
                    // The end nearer the middle of the face sits higher.
                    val leftEnd = Offset(eye.x - eyeR, browY - if (side > 0) lift else 0f)
                    val rightEnd = Offset(eye.x + eyeR, browY - if (side < 0) lift else 0f)
                    drawLine(Color(0xFF1B1F3B).copy(alpha = worry), leftEnd, rightEnd, strokeWidth = r * 0.05f, cap = StrokeCap.Round)
                }
            }
            // The mouth: a curve that smiles or frowns.
            val mw = r * 0.42f; val my = c.y + r * 0.33f
            val mouth = Path().apply { moveTo(c.x - mw / 2, my); quadraticBezierTo(c.x, my + r * 0.22f * smile, c.x + mw / 2, my) }
            drawPath(mouth, Color(0xFF1B1F3B), style = Stroke(r * 0.06f, cap = StrokeCap.Round))
            if (mood == Mood.Proud || mood == Mood.Happy) for (side in listOf(-1f, 1f)) drawCircle(Color(0xFFFF8FB1).copy(alpha = 0.45f), r * 0.1f, Offset(c.x + side * r * 0.6f, my - r * 0.05f))
        }
    }
}
