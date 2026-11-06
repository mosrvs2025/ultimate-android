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

    override fun onServiceConnected() {
        val wm = getSystemService(WindowManager::class.java)
        val view = CursorView(this)
        val size = (44 * resources.displayMetrics.density).toInt()
        val lp = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
        view.visibility = View.GONE
        runCatching { wm.addView(view, lp) }
        dot = view
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate); scope = s
        s.launch {
            combine(AirBridge.running, AirBridge.cursor) { r, c -> r to c }.collect { (running, c) ->
                view.visibility = if (running && c.visible) View.VISIBLE else View.GONE
                view.pinched = c.pinched; view.invalidate()
                lp.x = (c.x - size / 2).toInt(); lp.y = (c.y - size / 2).toInt()
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
        super.onDestroy()
    }

    /** The cursor: a soft ring that fills in while you pinch. */
    private class CursorView(c: Context) : View(c) {
        var pinched = false
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * c.resources.displayMetrics.density; color = 0xFFFFFFFF.toInt() }
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6 * c.resources.displayMetrics.density; color = 0x55000000 }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC7FD3FF.toInt() }
        override fun onDraw(canvas: Canvas) {
            val r = width / 2f * 0.7f
            canvas.drawCircle(width / 2f, height / 2f, r, shadow)
            if (pinched) canvas.drawCircle(width / 2f, height / 2f, r, fill)
            canvas.drawCircle(width / 2f, height / 2f, r, ring)
        }
    }
}
