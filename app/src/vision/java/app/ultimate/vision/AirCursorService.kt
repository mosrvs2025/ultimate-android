package app.ultimate.vision

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import app.ultimate.air.AirPointer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Air mode's hands: draws the cursor over every app and does what the pointer decides (tap, long press, drag, Back,
 * Home, Recents) through Android's accessibility gestures. It does nothing unless Air mode is running, and reads no
 * screen content.
 */
class AirCursorService : AccessibilityService() {
    private var scope: CoroutineScope? = null
    private var dot: CursorView? = null
    private var askPill: View? = null

    override fun onServiceConnected() {
        val wm = getSystemService(WindowManager::class.java)
        val view = CursorView(this)
        val d = resources.displayMetrics.density
        val w = (240 * d).toInt(); val h = (170 * d).toInt(); val ringY = (CursorView.CY * d).toInt()
        val lp = WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
        view.visibility = View.GONE
        runCatching { wm.addView(view, lp) }
        dot = view
        // The Ask pill: point at it and pinch, then say what you want.
        val pill = AskView(this)
        val pw = (150 * d).toInt(); val ph = (40 * d).toInt(); val top = (36 * d).toInt()
        val plp = WindowManager.LayoutParams(pw, ph, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = top }
        pill.visibility = View.GONE
        runCatching { wm.addView(pill, plp) }
        askPill = pill
        val sw = resources.displayMetrics.widthPixels
        // A little larger than drawn, so it's easy to hit with the cursor.
        AirBridge.askRect = floatArrayOf((sw - pw) / 2f - 12 * d, top - 12 * d, (sw + pw) / 2f + 12 * d, top + ph + 12 * d)
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate); scope = s
        s.launch {
            combine(AirBridge.running, AirBridge.paused, AirBridge.ask, AirBridge.cursor) { r, p, a, c -> listOf(r, p, a, c) }.collect { v ->
                val c = v[3] as AirBridge.Cursor; val rect = AirBridge.askRect
                pill.visibility = if (v[0] as Boolean && !(v[1] as Boolean)) View.VISIBLE else View.GONE
                pill.label = v[2] as String
                pill.hover = c.visible && c.x in rect[0]..rect[2] && c.y in rect[1]..rect[3]
                // Longer messages need a wider pill.
                val want = maxOf(pw, (pill.textWidth() + 32 * d).toInt()).coerceAtMost(sw - (32 * d).toInt())
                if (plp.width != want) { plp.width = want; runCatching { wm.updateViewLayout(pill, plp) } }
                pill.invalidate()
            }
        }
        s.launch {
            combine(combine(AirBridge.running, AirBridge.pad) { r, p -> r || p }, AirBridge.cursor, AirBridge.hold, AirBridge.hint) { r, c, hold, hint -> listOf(r, c, hold, hint) }.collect { v ->
                val running = v[0] as Boolean; val c = v[1] as AirBridge.Cursor
                view.push(c.vx, c.vy)
                @Suppress("UNCHECKED_CAST") val hold = v[2] as Pair<String, Float>?
                view.visibility = if (running && c.visible) View.VISIBLE else View.GONE
                view.pinched = c.pinched; view.hold = hold; view.hint = v[3] as String?; view.invalidate()
                lp.x = (c.x - w / 2).toInt(); lp.y = (c.y - ringY).toInt()
                runCatching { wm.updateViewLayout(view, lp) }
            }
        }
        splashes()
        s.launch {
            AirBridge.actions.collect { a ->
                if (!AirBridge.running.value && !AirBridge.pad.value) return@collect
                when (a) {
                    is AirPointer.Action.Tap -> stroke(a.x, a.y, a.x, a.y, 50)
                    is AirPointer.Action.LongPress -> stroke(a.x, a.y, a.x, a.y, 800)
                    is AirPointer.Action.Drag -> stroke(a.x0, a.y0, a.x1, a.y1, a.ms)
                    AirBridge.Back -> performGlobalAction(GLOBAL_ACTION_BACK)
                    AirBridge.Home -> performGlobalAction(GLOBAL_ACTION_HOME)
                    AirBridge.Recents -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                    is AirBridge.Launch -> runCatching { packageManager.getLaunchIntentForPackage(a.pkg)?.let { startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                }
            }
        }
    }

    private fun splashes() = scope?.launch { AirBridge.splash.collect { dot?.splash() } }

    /** One finger touching at (x0, y0), moving to (x1, y1) over [ms]: a tap, a long press or a drag. */
    private fun stroke(x0: Float, y0: Float, x1: Float, y1: Float, ms: Long) {
        val path = Path().apply { moveTo(x0, y0); if (x1 != x0 || y1 != y0) lineTo(x1, y1) }
        runCatching { dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms.coerceAtLeast(1))).build(), null, null) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        scope?.cancel(); scope = null
        dot?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }; dot = null
        askPill?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }; askPill = null
        super.onDestroy()
    }

    /**
     * The cursor, as a drop of liquid. At rest it is a round, softly lit bead that breathes; moving, it stretches into a
     * teardrop along its path and wobbles back to round when it stops, like jelly on a spring; a tap sends rings out from
     * it like a finger touching water. It fills in while you pinch. Below it, a tip while you're learning, or, while a
     * shortcut shape is held, what it will do and a ring that fills until it happens.
     */
    private class CursorView(c: Context) : View(c) {
        var pinched = false
        var hold: Pair<String, Float>? = null
        var hint: String? = null
        private val d = c.resources.displayMetrics.density
        private val motion = android.provider.Settings.Global.getFloat(c.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x44000000 }
        private val body = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d; color = 0xDDFFFFFF.toInt() }
        private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAAFFFFFF.toInt() }
        private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * d; strokeCap = Paint.Cap.ROUND; color = 0xFFFFC857.toInt() }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 13 * d; textAlign = Paint.Align.CENTER }
        private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt() }
        private val path = android.graphics.Path()

        // A damped spring pulls the drop's stretch towards how fast it is moving; overshoot is the wobble.
        private var sx = 0f; private var sy = 0f; private var svx = 0f; private var svy = 0f
        private var tx = 0f; private var ty = 0f
        private var lastFrame = 0L
        private val rings = ArrayList<Long>()

        /** The cursor's speed in screen pixels per second, from the latest update. */
        fun push(vx: Float, vy: Float) { tx = vx; ty = vy; postInvalidateOnAnimation() }
        fun splash() { if (motion) { rings += android.os.SystemClock.uptimeMillis(); postInvalidateOnAnimation() } }

        override fun onDraw(canvas: Canvas) {
            val now = android.os.SystemClock.uptimeMillis()
            val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceIn(0.001f, 0.05f); lastFrame = now
            // Target stretch: proportional to speed, capped; the target itself fades so the drop rounds off when updates stop.
            val k = 1.6f; tx *= kotlin.math.exp(-dt * 9f); ty *= kotlin.math.exp(-dt * 9f)
            val gx = (tx / 1000f).coerceIn(-k, k); val gy = (ty / 1000f).coerceIn(-k, k)
            if (motion) {
                svx += ((gx - sx) * 220f - svx * 14f) * dt; svy += ((gy - sy) * 220f - svy * 14f) * dt
                sx += svx * dt; sy += svy * dt
            } else { sx = 0f; sy = 0f }
            val cx = width / 2f; val cy = CY * d
            val r = 14 * d * (if (pinched) 0.86f else 1f)
            val stretch = kotlin.math.hypot(sx, sy).coerceAtMost(k)
            val angle = Math.toDegrees(kotlin.math.atan2(sy, sx).toDouble()).toFloat()

            // Rings: expand and fade over 700 ms, three per splash a beat apart.
            val it = rings.iterator()
            while (it.hasNext()) {
                val age = now - it.next(); if (age > 1_000) { it.remove(); continue }
                for (j in 0..2) {
                    val a = (age - j * 110) / 700f; if (a <= 0f || a >= 1f) continue
                    wave.strokeWidth = (3.5f - 2.5f * a) * d
                    wave.color = android.graphics.Color.argb((170 * (1 - a)).toInt(), 0x7F, 0xE8, 0xFF)
                    canvas.drawCircle(cx, cy, r + a * 46 * d, wave)
                }
            }

            canvas.save()
            canvas.rotate(angle, cx, cy)
            // A teardrop: round head leading, tail trailing behind, its length set by the stretch.
            val tail = r * (1f + stretch * 1.1f); val head = r * (1f - stretch * 0.08f); val waist = r * (1f - stretch * 0.22f)
            path.reset()
            path.moveTo(cx + head, cy)
            path.cubicTo(cx + head, cy + waist * 1.1f, cx - tail * 0.35f, cy + waist, cx - tail, cy)
            path.cubicTo(cx - tail * 0.35f, cy - waist, cx + head, cy - waist * 1.1f, cx + head, cy)
            canvas.translate(0f, 2 * d); canvas.drawPath(path, shadow); canvas.translate(0f, -2 * d)
            body.shader = android.graphics.RadialGradient(cx + head * 0.3f, cy - waist * 0.4f, tail * 1.4f,
                intArrayOf(if (pinched) 0xF27FE8FF.toInt() else 0xD07FE8FF.toInt(), 0xC88E7CFF.toInt()), null, android.graphics.Shader.TileMode.CLAMP)
            canvas.drawPath(path, body)
            canvas.drawPath(path, rim)
            canvas.restore()
            canvas.drawCircle(cx - r * 0.35f, cy - r * 0.4f, r * 0.22f, shine)

            val h = hold
            if (h != null) canvas.drawArc(cx - r - 6 * d, cy - r - 6 * d, cx + r + 6 * d, cy + r + 6 * d, -90f, 360f * h.second, false, arc)
            val label = h?.let { "Keep holding: ${it.first}" } ?: hint
            if (label != null) {
                val tw = text.measureText(label)
                val top = cy + r + 12 * d
                canvas.drawRoundRect(cx - tw / 2 - 10 * d, top, cx + tw / 2 + 10 * d, top + 26 * d, 13 * d, 13 * d, pill)
                canvas.drawText(label, cx, top + 18 * d, text)
            }
            // Keep animating while the drop is still wobbling or a ring is spreading.
            if (motion && (rings.isNotEmpty() || kotlin.math.abs(sx) + kotlin.math.abs(sy) + kotlin.math.abs(svx) + kotlin.math.abs(svy) > 0.01f)) postInvalidateOnAnimation()
            else lastFrame = 0L
        }

        companion object { const val CY = 66f }
    }

    /** The Ask pill: a mic and what it's doing; it lights up when the cursor is over it. */
    private class AskView(c: Context) : View(c) {
        var label = "Ask"
        var hover = false
        private val d = c.resources.displayMetrics.density
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 14 * d; textAlign = Paint.Align.CENTER }
        fun textWidth() = text.measureText("🎤  $label")
        override fun onDraw(canvas: Canvas) {
            bg.color = if (hover) 0xEE2E7DFF.toInt() else 0xCC000000.toInt()
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), height / 2f, height / 2f, bg)
            canvas.drawText("🎤  $label", width / 2f, height / 2f + 5 * d, text)
        }
    }
}
