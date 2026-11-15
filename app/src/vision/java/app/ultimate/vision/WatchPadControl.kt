package app.ultimate.vision

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import app.ultimate.air.AirPointer
import app.ultimate.air.WatchPad

/**
 * The phone's end of the watch trackpad: moves the liquid cursor and does taps, presses, scrolls and Back/Home/Recents
 * through the same accessibility service Air mode uses. It does nothing unless the watch has opened its trackpad, the
 * owner has turned on "Watch controls the phone", and the accessibility service is on. It turns itself off if the watch
 * goes quiet for a minute (closed, out of range, or out of battery), so a cursor is never left on screen by accident.
 */
object WatchPadControl {
    private val main = Handler(Looper.getMainLooper())
    private var cursor: WatchPad.Cursor? = null
    private var lastHeard = 0L
    private val idle = object : Runnable {
        override fun run() {
            if (!AirBridge.pad.value) return
            if (System.currentTimeMillis() - lastHeard > 60_000) off() else main.postDelayed(this, 10_000)
        }
    }

    /** Handles one message; returns what to tell the watch, if anything. [allowed] is "Watch controls the phone". */
    fun receive(c: Context, m: WatchPad.Msg, allowed: Boolean): String? {
        lastHeard = System.currentTimeMillis()
        if (m == WatchPad.Msg.Off) { main.post { off() }; return null }
        if (!allowed) return "pad:need:enable"
        if (!AirBridge.accessibilityOn(c)) return "pad:need:access"
        val cur = cursor ?: WatchPad.Cursor(1f, 1f).also { cursor = it }
        if (m == WatchPad.Msg.On || !AirBridge.pad.value) {
            val (w, h) = screen(c); cur.resize(w, h)
            if (m == WatchPad.Msg.On) cur.centre()
            main.post { AirBridge.pad.value = true; show(cur); main.removeCallbacks(idle); main.postDelayed(idle, 10_000) }
            if (m == WatchPad.Msg.On) return "pad:ready"
        }
        main.post {
            when (m) {
                is WatchPad.Msg.Move -> { cur.move(m.dx, m.dy, m.ms); show(cur) }
                WatchPad.Msg.Tap -> { AirBridge.actions.tryEmit(AirPointer.Action.Tap(cur.x, cur.y)); AirBridge.splash.tryEmit(Unit) }
                WatchPad.Msg.Long -> { AirBridge.actions.tryEmit(AirPointer.Action.LongPress(cur.x, cur.y)); AirBridge.splash.tryEmit(Unit) }
                is WatchPad.Msg.Scroll -> { val s = WatchPad.scroll(cur, m.amount); AirBridge.actions.tryEmit(AirPointer.Action.Drag(s.x0, s.y0, s.x1, s.y1, s.ms)) }
                WatchPad.Msg.Back -> AirBridge.actions.tryEmit(AirBridge.Back)
                WatchPad.Msg.Home -> AirBridge.actions.tryEmit(AirBridge.Home)
                WatchPad.Msg.Recents -> AirBridge.actions.tryEmit(AirBridge.Recents)
                else -> Unit
            }
        }
        return if (m == WatchPad.Msg.Tap || m == WatchPad.Msg.Long) "pad:hit" else null
    }

    private fun show(c: WatchPad.Cursor) { AirBridge.cursor.value = AirBridge.Cursor(c.x, c.y, true, false, c.vx, c.vy) }

    fun off() {
        AirBridge.pad.value = false
        if (!AirBridge.running.value) AirBridge.cursor.value = AirBridge.Cursor(0f, 0f, false, false)
        main.removeCallbacks(idle)
    }

    private fun screen(c: Context): Pair<Float, Float> {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = c.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            return b.width().toFloat() to b.height().toFloat()
        }
        val m = c.resources.displayMetrics; return m.widthPixels.toFloat() to m.heightPixels.toFloat()
    }
}
