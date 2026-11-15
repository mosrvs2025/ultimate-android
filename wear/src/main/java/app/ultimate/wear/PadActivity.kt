package app.ultimate.wear

import android.graphics.Bitmap
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The watch as a trackpad for the phone, and the face is water. Touch it and the surface dips under your finger and rings
 * spread out to the bezel; drag and you leave a wake; tap and it splashes, with a soft click on your wrist. The phone's
 * cursor, a drop of the same liquid, follows your finger. Turn the bezel or crown to scroll. Along the bottom: Back, Home,
 * Recents. Press the watch's back button to leave.
 */
class PadActivity : ComponentActivity() {
    private lateinit var link: PhoneLink

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        link = PhoneLink(this)
        setContent { Pad(link) }
    }

    override fun onStart() { super.onStart(); link.send(ON) }
    override fun onStop() { link.send(OFF); super.onStop() }

    companion object {
        // Must match the phone's WatchPad messages.
        const val ON = "pad:on"; const val OFF = "pad:off"
        fun move(dx: Float, dy: Float, ms: Int) = "pad:m:%.4f:%.4f:%d".format(java.util.Locale.US, dx, dy, ms)
    }
}

@Composable
private fun Pad(link: PhoneLink) {
    val context = LocalContext.current
    val vib = remember { context.getSystemService(Vibrator::class.java) }
    fun feel(effect: Int) { runCatching { vib?.vibrate(VibrationEffect.createPredefined(effect)) } }
    val water = remember { Ripple(72) }
    val pixels = remember { IntArray(72 * 72) }
    val bitmap = remember { Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888) }
    var frame by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("Connecting to your phone…") }
    var touching by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    // What the phone says: ready, or what it still needs.
    DisposableEffect(Unit) {
        val stop = link.listen { m ->
            status = when (m) {
                "pad:ready" -> ""
                "pad:need:enable" -> "On your phone: Ultimate+ › Watch › turn on Watch controls the phone"
                "pad:need:access" -> "On your phone: Settings › Accessibility › turn on Ultimate Air mode"
                else -> status
            }
        }
        onDispose { stop() }
    }
    LaunchedEffect(Unit) { focus.requestFocus(); kotlinx.coroutines.delay(4_000); if (status.startsWith("Connecting")) status = "No answer from the phone. Is Ultimate+ installed and the watch connected?" }

    // The water runs at the display's frame rate while it moves and goes still (no work) once calm.
    LaunchedEffect(Unit) {
        var quiet = 0
        while (true) {
            withFrameNanos { }
            if (touching || water.energy() > 1e-4f) { quiet = 0 } else if (++quiet > 30) { kotlinx.coroutines.delay(120); continue }
            water.step(); water.step()
            water.shade(pixels); bitmap.setPixels(pixels, 0, 72, 0, 0, 72, 72)
            frame++
        }
    }

    // Bezel or crown: scroll the phone, and a ring rolls in from the rim where the bezel turned.
    var turned by remember { mutableStateOf(0f) }
    var bezelAngle by remember { mutableStateOf(0.0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(90); val t = turned; if (abs(t) >= 1f) { turned = 0f; link.stream("pad:scroll:%.1f".format(java.util.Locale.US, t)) } } }

    Box(Modifier.fillMaxSize()
        .onRotaryScrollEvent { e ->
            val notches = e.verticalScrollPixels / 48f
            turned += notches
            bezelAngle += notches * 0.25
            water.drop(0.5f + 0.44f * kotlin.math.cos(bezelAngle).toFloat(), 0.5f + 0.44f * kotlin.math.sin(bezelAngle).toFloat(), 0.05f, 0.6f)
            feel(VibrationEffect.EFFECT_TICK); true
        }
        .focusRequester(focus).focusable()
        .pointerInput(Unit) {
            val w = size.width.toFloat(); val h = size.height.toFloat()
            awaitEachGesture {
                val down = awaitFirstDown()
                touching = true
                val start = down.position; var last = start; var lastT = down.uptimeMillis
                var travelled = 0f; var sinceTick = 0f; var pendX = 0f; var pendY = 0f; var lastSent = lastT
                var longDone = false
                water.drop(start.x / w, start.y / h, 0.07f, 0.9f); feel(VibrationEffect.EFFECT_TICK)
                while (true) {
                    val ev = withTimeoutOrNull(60) { awaitPointerEvent() }
                    val nowT = ev?.changes?.firstOrNull()?.uptimeMillis ?: (lastT + 60)
                    // Held still: a long press on the phone, with a deep, slow ring and a firm buzz.
                    if (!longDone && travelled < 0.04f * w && nowT - down.uptimeMillis > 480) {
                        longDone = true; link.send("pad:long"); feel(VibrationEffect.EFFECT_HEAVY_CLICK)
                        water.drop(last.x / w, last.y / h, 0.15f, 1.0f)
                    }
                    if (ev == null) continue
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) {
                        if (!longDone && travelled < 0.04f * w && c.uptimeMillis - down.uptimeMillis < 350) {
                            // A tap. Along the bottom it is Back / Home / Recents; anywhere else it taps the phone.
                            val p = c.position
                            val msg = if (p.y > h * 0.80f) when { p.x < w * 0.38f -> "pad:back"; p.x > w * 0.62f -> "pad:recents"; else -> "pad:home" } else "pad:tap"
                            link.send(msg); feel(VibrationEffect.EFFECT_CLICK)
                            water.drop(p.x / w, p.y / h, 0.10f, 0.9f)
                        }
                        if (pendX != 0f || pendY != 0f) link.stream(PadActivity.move(pendX, pendY, (c.uptimeMillis - lastSent).toInt().coerceAtLeast(1)))
                        c.consume(); break
                    }
                    val d = c.position - last
                    val dist = hypot(d.x, d.y)
                    travelled += dist; sinceTick += dist
                    if (!longDone) {
                        pendX += d.x / w; pendY += d.y / w
                        // A wake: small dips along the path, so the finger trails ripples through the water.
                        water.drop(c.position.x / w, c.position.y / h, 0.045f, 0.35f + (dist / w * 6f).coerceAtMost(0.5f))
                        // Little ticks as you move, about every eighth of the face: the feel of skimming the surface.
                        if (sinceTick > w / 8f) { sinceTick = 0f; feel(VibrationEffect.EFFECT_TICK) }
                        if (c.uptimeMillis - lastSent >= 25) { link.stream(PadActivity.move(pendX, pendY, (c.uptimeMillis - lastSent).toInt().coerceAtLeast(1))); pendX = 0f; pendY = 0f; lastSent = c.uptimeMillis }
                    }
                    last = c.position; lastT = c.uptimeMillis; c.consume()
                }
                touching = false
            }
        }) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION") frame // redraw each simulated frame
            drawImage(bitmap.asImageBitmap(), IntOffset.Zero, IntSize(72, 72), IntOffset.Zero, IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.High)
            // A thin glass rim, and the three keys etched faintly into the bottom of the water.
            drawCircle(Color.White.copy(alpha = 0.18f), size.minDimension / 2 - 2f, style = Stroke(2f))
            val y = size.height * 0.88f; val r = size.minDimension * 0.035f
            val ink = Color.White.copy(alpha = 0.55f)
            // Back: a small left-pointing chevron. Home: a ring. Recents: a rounded square outline.
            val bx = size.width * 0.30f
            drawLine(ink, Offset(bx + r * 0.5f, y - r), Offset(bx - r * 0.5f, y), 3f); drawLine(ink, Offset(bx - r * 0.5f, y), Offset(bx + r * 0.5f, y + r), 3f)
            drawCircle(ink, r, Offset(size.width * 0.5f, y), style = Stroke(3f))
            drawRect(ink, Offset(size.width * 0.70f - r * 0.85f, y - r * 0.85f), androidx.compose.ui.geometry.Size(r * 1.7f, r * 1.7f), style = Stroke(3f))
        }
        if (status.isNotEmpty()) Text(status, Modifier.align(Alignment.Center).padding(28.dp), textAlign = TextAlign.Center, fontSize = 13.sp, color = Color.White)
    }
}
