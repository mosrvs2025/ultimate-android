package app.ultimate.air

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The watch as a trackpad for the phone. The watch sends what the finger did on its face; this turns that into where the
 * cursor is on the phone and what to do there. Pure (no Android), so the feel of it is tested here.
 *
 * Messages, watch → phone (all start "pad:"):
 *   pad:on / pad:off            the watch opened or closed its trackpad
 *   pad:m:<dx>:<dy>:<ms>        the finger moved dx, dy (shares of the watch face's width) over ms milliseconds
 *   pad:tap  pad:long           tap or long press where the cursor is
 *   pad:scroll:<amount>         the bezel or crown turned (positive scrolls down the page)
 *   pad:back  pad:home  pad:recents
 * Phone → watch: pad:ready, pad:need:<what> (the phone cannot act yet, and why), pad:hit (a tap landed: the watch ripples).
 */
object WatchPad {
    sealed interface Msg {
        data object On : Msg; data object Off : Msg
        data class Move(val dx: Float, val dy: Float, val ms: Int) : Msg
        data object Tap : Msg; data object Long : Msg
        data class Scroll(val amount: Float) : Msg
        data object Back : Msg; data object Home : Msg; data object Recents : Msg
    }

    fun parse(text: String): Msg? {
        if (!text.startsWith("pad:")) return null
        val p = text.split(':')
        return when (p.getOrNull(1)) {
            "on" -> Msg.On; "off" -> Msg.Off; "tap" -> Msg.Tap; "long" -> Msg.Long
            "back" -> Msg.Back; "home" -> Msg.Home; "recents" -> Msg.Recents
            "m" -> {
                val dx = p.getOrNull(2)?.toFloatOrNull(); val dy = p.getOrNull(3)?.toFloatOrNull(); val ms = p.getOrNull(4)?.toIntOrNull() ?: 16
                if (dx == null || dy == null || !dx.isFinite() || !dy.isFinite()) null else Msg.Move(dx.coerceIn(-2f, 2f), dy.coerceIn(-2f, 2f), ms.coerceIn(1, 1_000))
            }
            "scroll" -> p.getOrNull(2)?.toFloatOrNull()?.takeIf { it.isFinite() }?.let { Msg.Scroll(it.coerceIn(-20f, 20f)) }
            else -> null
        }
    }

    fun move(dx: Float, dy: Float, ms: Int) = "pad:m:%.4f:%.4f:%d".format(java.util.Locale.US, dx, dy, ms)

    /**
     * Where the cursor is on a [width] × [height] screen. Slow finger movement moves it about one and a half times the
     * finger (precise, for small targets); a quick swipe moves it up to five times further, so one stroke across the
     * small watch face can reach any corner of the phone. Like a laptop trackpad's acceleration.
     */
    class Cursor(var width: Float, var height: Float) {
        var x = width / 2; private set
        var y = height / 2; private set
        /** Screen pixels per second, for stretching the liquid cursor in the direction it moves. */
        var vx = 0f; private set
        var vy = 0f; private set

        fun resize(w: Float, h: Float) { width = w; height = h; x = x.coerceIn(0f, w - 1); y = y.coerceIn(0f, h - 1) }
        fun centre() { x = width / 2; y = height / 2; vx = 0f; vy = 0f }

        fun move(dx: Float, dy: Float, ms: Int) {
            val base = min(width, height) // one full swipe across the watch at slow speed moves about 1.5 phone widths
            val speed = hypot(dx, dy) / max(1, ms) * 1000f // watch-faces per second
            val gain = gain(speed)
            val mx = dx * base * gain; val my = dy * base * gain
            x = (x + mx).coerceIn(0f, width - 1); y = (y + my).coerceIn(0f, height - 1)
            vx = mx / max(1, ms) * 1000f; vy = my / max(1, ms) * 1000f
        }

        /** The cursor coasting to rest between messages (call with the time since the last move). */
        fun settle(ms: Int) { val k = kotlin.math.exp(-ms / 90f); vx *= k; vy *= k }
    }

    /** Pointer acceleration: 1.5× when slow, rising smoothly to 5× for a fast flick (faces per second). */
    fun gain(speed: Float): Float = 1.5f + 3.5f * ((speed - 0.4f) / 2.6f).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }

    /**
     * A scroll as a finger drag on the phone: from the cursor, upward to scroll down the page. [amount] is in bezel
     * notches; each is about a ninth of the screen, and the drag stays on screen.
     */
    data class Stroke(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val ms: Long)

    fun scroll(c: Cursor, amount: Float): Stroke {
        val dist = (-amount * c.height / 9f)
        val y0 = (c.y).coerceIn(c.height * 0.15f, c.height * 0.85f)
        val y1 = (y0 + dist).coerceIn(c.height * 0.05f, c.height * 0.95f)
        return Stroke(c.x, y0, c.x, y1, (120 + 30 * kotlin.math.abs(amount)).toLong().coerceAtMost(400))
    }
}
