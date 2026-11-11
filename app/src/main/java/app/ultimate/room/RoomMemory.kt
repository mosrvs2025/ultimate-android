package app.ultimate.room

import kotlin.math.cos
import kotlin.math.sin

/** Looking at a saved room from outside: orbit round its middle and project to the screen. Pure, so it is tested. */
object Orbit {
    /** Screen position (pixels from the centre, y down) and depth for sorting, for a point seen from [yawDeg] round and [pitchDeg] above, [zoom] pixels per metre. */
    fun project(p: P3, centre: P3, yawDeg: Float, pitchDeg: Float, zoom: Float): Triple<Float, Float, Float> {
        val d = p - centre
        val y = Math.toRadians(yawDeg.toDouble()); val cy = cos(y).toFloat(); val sy = sin(y).toFloat()
        val x1 = d.x * cy - d.z * sy; val z1 = d.x * sy + d.z * cy
        val t = Math.toRadians(pitchDeg.toDouble()); val ct = cos(t).toFloat(); val st = sin(t).toFloat()
        val y2 = d.y * ct - z1 * st; val z2 = d.y * st + z1 * ct
        return Triple(x1 * zoom, -y2 * zoom, z2)
    }
}

/** Turning a camera picture upright and back. [rot] is how far the sensor picture must be turned clockwise (0, 90, 180, 270) to look upright. Pure, so it is tested. */
object Upright {
    /** Which turn makes the picture upright, from where the sensor picture's x axis points on screen ([dx], [dy] in view coordinates, y down). */
    fun rotation(dx: Float, dy: Float): Int = if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) (if (dx >= 0) 0 else 180) else (if (dy >= 0) 90 else 270)

    /** A pixel of the upright picture, back in the sensor picture ([w] by [h] is the sensor picture's size). */
    fun toSensor(ux: Float, uy: Float, rot: Int, w: Int, h: Int): Pair<Float, Float> = when (rot) {
        90 -> uy to (h - 1 - ux); 180 -> (w - 1 - ux) to (h - 1 - uy); 270 -> (w - 1 - uy) to ux; else -> ux to uy
    }
}
