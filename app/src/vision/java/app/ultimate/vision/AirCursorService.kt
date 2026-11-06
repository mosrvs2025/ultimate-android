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
        val w = (240 * d).toInt(); val h = (96 * d).toInt(); val ringY = (24 * d).toInt()
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
            combine(AirBridge.running, AirBridge.cursor, AirBridge.hold, AirBridge.hint) { r, c, hold, hint -> listOf(r, c, hold, hint) }.collect { v ->
                val running = v[0] as Boolean; val c = v[1] as AirBridge.Cursor
                @Suppress("UNCHECKED_CAST") val hold = v[2] as Pair<String, Float>?
                view.visibility = if (running && c.visible) View.VISIBLE else View.GONE
                view.pinched = c.pinched; view.hold = hold; view.hint = v[3] as String?; view.invalidate()
                lp.x = (c.x - w / 2).toInt(); lp.y = (c.y - ringY).toInt()
                runCatching { wm.updateViewLayout(view, lp) }
            }
        }
        s.launch {
            AirBridge.actions.collect { a ->
                if (!AirBridge.running.value) return@collect
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
     * The cursor: a soft ring that fills in while you pinch. Below it, a tip while you're learning, or, while a shortcut
     * shape is held, what it will do and a ring that fills until it happens.
     */
    private class CursorView(c: Context) : View(c) {
        var pinched = false
        var hold: Pair<String, Float>? = null
        var hint: String? = null
        private val d = c.resources.displayMetrics.density
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d; color = 0xFFFFFFFF.toInt() }
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6 * d; color = 0x55000000 }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC7FD3FF.toInt() }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * d; strokeCap = Paint.Cap.ROUND; color = 0xFFFFC857.toInt() }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 13 * d; textAlign = Paint.Align.CENTER }
        private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt() }
        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = 24 * d; val r = 15 * d
            canvas.drawCircle(cx, cy, r, shadow)
            if (pinched) canvas.drawCircle(cx, cy, r, fill)
            canvas.drawCircle(cx, cy, r, ring)
            val h = hold
            if (h != null) canvas.drawArc(cx - r - 5 * d, cy - r - 5 * d, cx + r + 5 * d, cy + r + 5 * d, -90f, 360f * h.second, false, arc)
            val label = h?.let { "Keep holding: ${it.first}" } ?: hint ?: return
            val tw = text.measureText(label)
            val top = cy + r + 10 * d
            canvas.drawRoundRect(cx - tw / 2 - 10 * d, top, cx + tw / 2 + 10 * d, top + 26 * d, 13 * d, 13 * d, pill)
            canvas.drawText(label, cx, top + 18 * d, text)
        }
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
