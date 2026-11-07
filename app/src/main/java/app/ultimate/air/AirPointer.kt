package app.ultimate.air

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Air mode's pointer: turns where your index finger is in the camera picture into a cursor on the screen, and pinches
 * into taps, long presses and drags. Pure, so it is tested.
 *
 * - The middle [reach] of the camera picture covers the whole screen, so the edges are reachable without stretching.
 * - Smoothing follows the "1€" filter: steady when the hand is nearly still (no jitter while aiming), quick when it
 *   moves fast (no lag on a big move).
 * - A pinch (thumb and index together, measured against the hand's size so distance to the camera doesn't matter) is
 *   a tap if released quickly where it started, a long press if held still, and a drag if it moves.
 */
class AirPointer(
    private val screenW: Float, private val screenH: Float,
    private val reach: Float = 0.6f,
    private val minCutoff: Float = 0.6f, private val beta: Float = 0.02f,
) {
    sealed interface Action {
        data class Tap(val x: Float, val y: Float) : Action
        data class LongPress(val x: Float, val y: Float) : Action
        /** A drag from (x0, y0) to (x1, y1) over [ms]: scrolls lists, moves things. */
        data class Drag(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val ms: Long) : Action
    }

    var x = screenW / 2; private set
    var y = screenH / 2; private set
    /** True while thumb and index are pinched together. */
    var pinched = false; private set

    private var fx: OneEuro? = null
    private var fy: OneEuro? = null
    private var pinchAt = 0L
    private var pinchX = 0f
    private var pinchY = 0f
    private var longFired = false
    private var closeX = 0f
    private var closeY = 0f
    /** Where the cursor was over the last fraction of a second, to undo the drift a pinch itself causes. */
    private val trail = ArrayDeque<Triple<Long, Float, Float>>()

    /**
     * Feeds one hand: [tipX], [tipY] the index fingertip in the camera picture (0..1, already mirrored for the front
     * camera), [pinch] the thumb-to-index gap divided by the hand's size. Returns an action when one completes.
     */
    fun feed(tipX: Float, tipY: Float, pinch: Float, now: Long): Action? {
        val margin = (1 - reach) / 2
        val tx = ((tipX - margin) / reach).coerceIn(0f, 1f) * screenW
        val ty = ((tipY - margin) / reach).coerceIn(0f, 1f) * screenH
        val ex = fx ?: OneEuro(minCutoff, beta).also { fx = it }
        val ey = fy ?: OneEuro(minCutoff, beta).also { fy = it }
        // While pinched, the cursor holds steadier so the pinch itself doesn't nudge it off target.
        x = ex.filter(tx, now, if (pinched) 0.5f else 1f); y = ey.filter(ty, now, if (pinched) 0.5f else 1f)
        trail.addLast(Triple(now, x, y)); while (trail.size > 2 && now - trail.first().first > 400) trail.removeFirst()

        // Hysteresis: closing past 0.25 pinches, opening past 0.4 releases, so a pinch held near the edge doesn't flicker.
        if (!pinched && pinch < 0.25f) {
            // Closing a pinch pulls the fingertip toward the thumb, so by the time the pinch counts the cursor has
            // slid off what you aimed at. The tap lands where the cursor was just before the fingers started closing.
            val aim = trail.lastOrNull { now - it.first >= REWIND_MS } ?: trail.first()
            pinched = true; pinchAt = now; pinchX = aim.second; pinchY = aim.third; closeX = x; closeY = y; longFired = false; return null
        }
        if (pinched) {
            // Movement counts from where the cursor was when the pinch closed, so the closing drift isn't a drag.
            val moved = hypot(x - closeX, y - closeY)
            if (pinch > 0.4f) {
                pinched = false
                val held = now - pinchAt
                return when {
                    longFired -> null
                    moved > DRAG_PX -> Action.Drag(pinchX, pinchY, x, y, held.coerceIn(100, 1_000))
                    held <= TAP_MS -> Action.Tap(pinchX, pinchY)
                    else -> null
                }
            }
            if (!longFired && moved <= DRAG_PX && now - pinchAt >= LONG_MS) { longFired = true; return Action.LongPress(pinchX, pinchY) }
        }
        return null
    }

    /** The hand left the picture: forget smoothing (so it doesn't glide from the old place) and any pinch in progress. */
    fun lost() { fx = null; fy = null; pinched = false; trail.clear() }

    companion object {
        const val TAP_MS = 450L
        const val LONG_MS = 650L
        const val DRAG_PX = 60f
        /** How far back a pinch looks for where you were aiming. */
        const val REWIND_MS = 150L
    }
}

/** The "1€ filter" (Casiez, Roussel and Vogel, 2012): low-pass filtering whose cutoff rises with speed. */
class OneEuro(private val minCutoff: Float, private val beta: Float, private val dCutoff: Float = 1f) {
    private var last: Float? = null
    private var lastD = 0f
    private var lastT = 0L

    private fun alpha(cutoff: Float, dt: Float): Float { val tau = 1f / (2 * PI.toFloat() * cutoff); return 1f / (1f + tau / dt) }

    fun filter(v: Float, now: Long, steadiness: Float = 1f): Float {
        val prev = last
        if (prev == null) { last = v; lastT = now; return v }
        val dt = ((now - lastT).coerceAtLeast(1)) / 1000f
        lastT = now
        val d = (v - prev) / dt
        val ad = alpha(dCutoff, dt); lastD = ad * d + (1 - ad) * lastD
        val cutoff = (minCutoff + beta * abs(lastD)) * steadiness
        val a = alpha(cutoff, dt)
        val out = a * v + (1 - a) * prev
        last = out
        return out
    }

}

/**
 * Hand-shape shortcuts that only happen on purpose: the shape must be held still for [holdMs] while a ring fills
 * ([progress]), moving the hand more than [movePx] cancels it, and after one fires there's a pause. A relaxed hand that
 * briefly looks like a palm or a fist does nothing.
 */
class ShortcutHold(private val holdMs: Long = 1_000, private val movePx: Float = 80f, private val pauseMs: Long = 1_500) {
    /** The shape being held and how far along (0..1), or null. */
    var progress: Pair<String, Float>? = null; private set
    private var sign: String? = null
    private var since = 0L
    private var x0 = 0f
    private var y0 = 0f
    private var lastFired = Long.MIN_VALUE / 2

    /** Set when a hold that got at least a third of the way was abandoned by moving: you didn't mean it. Read and clear. */
    var abandoned: String? = null

    /**
     * Feeds the recognised shape ([sign], or null) and the cursor; returns the shape's name when its hold completes.
     * [hold] is how long this shape must be held right now (shorter once it has learned you mean it).
     */
    fun feed(sign: String?, x: Float, y: Float, now: Long, hold: Long = holdMs): String? {
        if (sign == null || now - lastFired < pauseMs) { this.sign = null; progress = null; return null }
        if (sign != this.sign || kotlin.math.hypot(x - x0, y - y0) > movePx) {
            val was = progress
            if (was != null && was.second >= 0.33f) abandoned = was.first
            this.sign = sign; since = now; x0 = x; y0 = y; progress = sign to 0f; return null
        }
        val p = ((now - since).toFloat() / hold.coerceAtLeast(1)).coerceIn(0f, 1f)
        progress = sign to p
        if (p >= 1f) { lastFired = now; this.sign = null; progress = null; return sign }
        return null
    }
}
