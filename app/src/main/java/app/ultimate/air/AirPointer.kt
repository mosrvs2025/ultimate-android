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

        // Hysteresis: closing past 0.25 pinches, opening past 0.4 releases, so a pinch held near the edge doesn't flicker.
        if (!pinched && pinch < 0.25f) { pinched = true; pinchAt = now; pinchX = x; pinchY = y; longFired = false; return null }
        if (pinched) {
            val moved = hypot(x - pinchX, y - pinchY)
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
    fun lost() { fx = null; fy = null; pinched = false }

    companion object {
        const val TAP_MS = 450L
        const val LONG_MS = 650L
        const val DRAG_PX = 60f
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
