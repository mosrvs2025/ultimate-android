package app.ultimate.core

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Sinks a little under the finger and springs back on release. It only watches the touch, so the element's own click,
 * long-press and scrolling work as before.
 */
fun Modifier.pressScale(pressed: Float = 0.95f): Modifier = composed {
    var down by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (down) pressed else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "press")
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                down = true
                waitForUpOrCancellation(PointerEventPass.Initial)
                down = false
            }
        }
}

/**
 * Rises and fades in the first time [key] is shown on this screen, a beat after the item before it, so lists cascade in.
 * Keys already in [seen] (scrolled back into view) appear at once.
 */
fun Modifier.appear(key: Any, seen: MutableSet<Any>, index: Int = 0): Modifier = composed {
    val first = remember(key) { seen.add(key) }
    val progress = remember(key) { Animatable(if (first) 0f else 1f) }
    LaunchedEffect(key) {
        if (progress.value < 1f) {
            delay(minOf(index, 10) * 45L)
            progress.animateTo(1f, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow))
        }
    }
    val lift = with(LocalDensity.current) { 28.dp.toPx() }
    graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * lift
        val s = 0.94f + 0.06f * p
        scaleX = s; scaleY = s
    }
}
