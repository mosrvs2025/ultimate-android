package app.ultimate.worldscan

import app.ultimate.room.RoomMap
import app.ultimate.room.ScanIntegrator

/**
 * The room scanner's depth filters, set for a person walking. Its defaults assume someone standing in a room turning slowly: it drops
 * any depth picture taken while the phone moves faster than 1.2 m/s, and an ordinary walking pace (1.2-1.5 m/s) is above that, so reusing
 * the defaults would silently discard almost every frame of a walk (shown in the tests). Everything else about how a depth picture
 * becomes evidence is shared with the room scanner.
 */
object WalkSettings {
    /** Cube size: 10 cm. A street is hundreds of metres, and ARCore's depth is no better than this at the range a street needs. */
    const val VOXEL = 0.10f
    /** ARCore's raw depth is best between half a metre and about five. */
    const val MAX_RANGE = 5.0f
    /** Faster than this between two depth pictures is not a person walking (it is a swing of the arm). */
    const val MAX_SPEED = 2.8f
    const val MAX_TURN_DEG_PER_S = 120f
    /** A piece of the walk ends after this much path, so a later walk can be fitted piece by piece (motion tracking drifts). */
    const val PIECE_LENGTH_M = 15.0
    /** A piece also ends after this long, so a person standing still collects no endless piece. */
    const val PIECE_MAX_SECONDS = 90.0
    /** Motion tracking lost for this long ends the walk: when it comes back, ARCore may have started a new frame, and nothing before can be trusted to line up with what follows. */
    const val MAX_LOSS_SECONDS = 4.0

    fun newMap() = RoomMap(VOXEL)
    fun integrator(map: RoomMap) = ScanIntegrator(map, maxRange = MAX_RANGE, minConfidence = 50, maxTurnDegPerS = MAX_TURN_DEG_PER_S, maxSpeed = MAX_SPEED)
}
