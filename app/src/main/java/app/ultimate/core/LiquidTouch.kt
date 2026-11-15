package app.ultimate.core

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.min

/**
 * How the whole app answers a touch: a faint ring of water spreads out from where the finger landed and fades, and the phone
 * gives the lightest tick. It replaces the stock Android ripple everywhere that follows the theme (buttons, cards, chips,
 * list rows), so no screen has to opt in.
 *
 * Kept deliberately quiet: the ring is at most about a fifth opaque, lives half a second, and the tick is the system's
 * lightest. With animations switched off in Android's settings there is no ring, only a very soft shade while pressed; and the
 * tick follows the phone's own "touch feedback" setting. Presses that become scrolls never show or buzz, because Compose only
 * reports a press once the finger has stayed put.
 */
object LiquidTouch : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = TouchNode(interactionSource)
    override fun equals(other: Any?) = other === LiquidTouch
    override fun hashCode() = -1

    /** Never more than one tick every [MIN_GAP_MS], so quick taps on a keypad don't turn into a buzz. */
    object Pace {
        const val MIN_GAP_MS = 70L
        fun allow(now: Long, last: Long) = now - last >= MIN_GAP_MS
    }

    /** The ring's size and strength at progress [p] (0..1) for an element [w] × [h] px, with [density] px per dp. Pure, tested. */
    class Ring(val radius: Float, val strokePx: Float, val alpha: Float)

    fun ring(p: Float, w: Float, h: Float, density: Float, delay: Float = 0f): Ring? {
        val q = ((p - delay) / (1f - delay)).coerceIn(0f, 1f)
        if (q <= 0f || q >= 1f) return null
        val reach = min(hypot(w, h), 240f * density) * 0.62f
        val e = EASE.transform(q)
        return Ring(8f * density + (reach - 8f * density) * e, (2.6f * (1f - q) + 0.6f) * density, MAX_ALPHA * (1f - q) * (1f - q))
    }

    private const val MAX_ALPHA = 0.32f
    private val EASE = CubicBezierEasing(0.2f, 0.8f, 0.3f, 1f)

    private class TouchNode(private val source: InteractionSource) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
        private val progress = Animatable(1f)
        private val held = Animatable(0f)
        private var at = Offset.Unspecified
        private var lastTick = 0L
        private var motion = true

        override fun onAttach() {
            motion = runCatching {
                MotionPrefs.on(Settings.Global.getFloat(currentValueOf(LocalContext).contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f))
            }.getOrDefault(true)
            coroutineScope.launch {
                source.interactions.collect { i ->
                    when (i) {
                        is PressInteraction.Press -> press(i.pressPosition)
                        is PressInteraction.Release, is PressInteraction.Cancel -> release()
                    }
                }
            }
        }

        private fun press(where: Offset) {
            at = where
            val now = System.currentTimeMillis()
            if (Pace.allow(now, lastTick)) { lastTick = now; runCatching { currentValueOf(LocalHapticFeedback).performHapticFeedback(HapticFeedbackType.TextHandleMove) } }
            coroutineScope.launch { held.animateTo(1f, tween(90)) }
            if (motion) coroutineScope.launch { progress.snapTo(0f); progress.animateTo(1f, tween(520)) }
        }

        private fun release() { coroutineScope.launch { held.animateTo(0f, tween(220)) } }

        override fun ContentDrawScope.draw() {
            drawContent()
            val tint = lerp(currentValueOf(LocalContentColor), TEAL, 0.45f)
            val h = held.value
            if (h > 0.001f) drawRect(tint.copy(alpha = 0.07f * h))
            val p = progress.value
            if (p < 1f && at != Offset.Unspecified) clipRect {
                for ((delay, weight) in listOf(0f to 1f, 0.16f to 0.55f)) {
                    val r = ring(p, size.width, size.height, density, delay) ?: continue
                    drawCircle(tint.copy(alpha = r.alpha * weight), r.radius, at, style = Stroke(r.strokePx))
                }
            }
        }
    }

    private val TEAL = Color(0xFF3DDCD0)
}
