package app.ultimate.sensors

import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sqrt

/** Pure maths behind the sensor views, so the numbers on screen are tested. */
object SensorMath {
    const val G = 9.80665f

    /** Altitude in metres from air pressure, using the standard atmosphere (sea level 1013.25 hPa). */
    fun altitude(hPa: Float, seaLevel: Float = 1013.25f): Float = (44330.0 * (1 - (hPa / seaLevel).toDouble().pow(1 / 5.255))).toFloat()

    fun magnitude(v: FloatArray): Float = sqrt(v.fold(0f) { a, x -> a + x * x })

    fun gForce(accel: FloatArray): Float = magnitude(accel) / G

    /** Tilt in degrees around the phone's x (pitch) and y (roll) axes from gravity; 0,0 is lying flat. */
    fun tilt(accel: FloatArray): Pair<Float, Float> {
        val (x, y, z) = Triple(accel[0], accel[1], accel[2])
        val pitch = Math.toDegrees(atan2(y.toDouble(), sqrt((x * x + z * z).toDouble()))).toFloat()
        val roll = Math.toDegrees(atan2(-x.toDouble(), sqrt((y * y + z * z).toDouble()))).toFloat()
        return pitch to roll
    }

    /** 0–360 degrees to a compass point. */
    fun compassPoint(deg: Float): String {
        val points = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return points[(((deg % 360 + 360) % 360 + 22.5f) / 45f).toInt() % 8]
    }

    /** Rough everyday description of light level. */
    fun lightDescription(lux: Float) = when {
        lux < 10 -> "Dark"
        lux < 200 -> "Dim room"
        lux < 1000 -> "Bright room"
        lux < 10_000 -> "Overcast daylight"
        else -> "Direct sunlight"
    }

    /**
     * What to say about a sensor that has not reported yet. Sensors that report only on change stay quiet until something
     * happens, so they get a hint for how to wake them; continuous ones are explained only once a wait is unusual.
     */
    fun waitingHint(type: Int, onChangeOnly: Boolean, waitedMs: Long): String = when {
        waitedMs < 1500 -> "Listening…"
        type == 8 -> "Wave a hand over the top of the screen"            // proximity
        type == 5 -> "Point the phone at a lamp or window"               // light
        type == 18 || type == 19 -> "Take a few steps"                   // step detector / counter
        type == 21 || type == 31 -> "Needs a watch or a finger on the sensor" // heart rate / heart beat
        onChangeOnly -> "Reports only when something changes"
        waitedMs < 5000 -> "Listening…"
        else -> "Quiet: the system may be using it, or it only works in special modes"
    }

    /** One CSV row: milliseconds since recording started, sensor name, then the values. */
    fun csvRow(ms: Long, name: String, values: FloatArray): String =
        buildString { append(ms); append(','); append('"').append(name.replace("\"", "'")).append('"'); values.forEach { append(','); append(it) } }
}
