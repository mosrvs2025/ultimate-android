package app.ultimate.worldscan

import kotlin.math.hypot

/**
 * Decides when a walk can no longer be trusted to be one continuous piece. ARCore's motion tracking can lose its place (a dark corridor, a
 * blank wall, a fast turn) and, when it recovers, either carries on in the same frame or quietly starts a new one with the origin somewhere
 * else. Nothing recorded before and after a new frame can be put in one map, and the phone cannot tell us which happened. So: a loss longer
 * than [maxLossS] ends the walk, and so does a recovery that lands further from where tracking was lost than a person could have walked in
 * that time (plus [slackM] for the error of the last position). What was captured up to then is kept. Pure, so it is tested.
 */
class TrackingWatch(private val maxLossS: Double = WalkSettings.MAX_LOSS_SECONDS, private val maxSpeed: Double = 2.5, private val slackM: Double = 1.0) {
    private var lostSince = -1L; private var lostX = 0f; private var lostZ = 0f; private var lastX = Float.NaN; private var lastZ = Float.NaN
    /** True on the frame on which tracking was lost (so the loss can be written into the log once). */
    var lostJustNow = false; private set
    val lostForNs: Long get() = if (lostSince < 0) 0L else lastTs - lostSince
    private var lastTs = 0L

    /** Feed every frame. Returns why the walk must end, or null to carry on. */
    fun update(tsNs: Long, tracking: Boolean, x: Float, z: Float): String? {
        lastTs = tsNs; lostJustNow = false
        if (tracking) {
            if (lostSince >= 0) {
                val gapS = (tsNs - lostSince) / 1e9; val moved = hypot((x - lostX).toDouble(), (z - lostZ).toDouble()); lostSince = -1L
                if (moved > maxSpeed * gapS + slackM) return "Motion tracking lost its place and came back about ${"%.0f".format(moved)} m from where it left off, further than you could have walked. The walk so far is kept; start another for the rest."
            }
            lastX = x; lastZ = z
            return null
        }
        if (lostSince < 0) { lostSince = tsNs; lostX = if (lastX.isNaN()) x else lastX; lostZ = if (lastZ.isNaN()) z else lastZ; lostJustNow = true }
        val lostS = (tsNs - lostSince) / 1e9
        return if (lostS > maxLossS) "Motion tracking was lost for more than ${maxLossS.toInt()} seconds. The walk so far is kept; start another for the rest." else null
    }
}
