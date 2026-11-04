package app.ultimate.echo

import kotlin.math.hypot

/**
 * Distance to a hand from one camera, by its known size: a palm that is W cm across and spans w pixels, seen through a
 * lens of focal length f pixels, is f·W/w away. The palm's width (index to little-finger knuckle) is personal, so it is
 * measured once with the Ruler; until then a typical 8 cm is used. It is an estimate: a tilted palm looks narrower and
 * reads farther, so samples are only kept when the hand is held flat and steady.
 */
object CameraRange {
    const val DEFAULT_PALM_CM = 8.0

    /** Focal length in pixels for an image [imageWidthPx] wide, from the lens's focal length and the sensor's width. */
    fun focalPx(focalMm: Float, sensorWidthMm: Float, imageWidthPx: Int): Double = focalMm.toDouble() / sensorWidthMm * imageWidthPx

    /** Distance from the camera in cm, or NaN when the palm is too small to trust (under 12 px). */
    fun distanceCm(palmPx: Double, focalPx: Double, palmCm: Double = DEFAULT_PALM_CM): Double =
        if (palmPx < 12) Double.NaN else focalPx * palmCm / palmPx

    /** Palm width in pixels: from the index knuckle (landmark 5) to the little-finger knuckle (17). */
    fun palmPx(x5: Double, y5: Double, x17: Double, y17: Double) = hypot(x5 - x17, y5 - y17)

    /**
     * A palm seen flat measures about the same across the knuckles as from wrist to middle knuckle (landmark 0 to 9)
     * times 0.8; when the two disagree by much, the hand is tilted and its width is not to be trusted.
     */
    fun isFlat(palmPx: Double, wristToMiddlePx: Double): Boolean = wristToMiddlePx > 0 && (palmPx / wristToMiddlePx) in 0.6..1.15

    /**
     * Pairs of (Echo's raw reading, the camera's distance) collected while both see the hand, reduced to one robust
     * point per 2 cm of camera distance (the median of each), so a burst of samples at one height doesn't outweigh the
     * rest. Returns the points as (held, read) lists ready for [EchoCalibration.fit].
     */
    fun binned(pairs: List<Pair<Double, Double>>, binCm: Double = 2.0, minPerBin: Int = 3): Pair<List<Double>, List<Double>> {
        val bins = pairs.filter { !it.first.isNaN() && !it.second.isNaN() }.groupBy { Math.floor(it.second / binCm).toInt() }.filterValues { it.size >= minPerBin }
        val sorted = bins.toSortedMap().values
        fun med(x: List<Double>) = x.sorted()[x.size / 2]
        return sorted.map { b -> med(b.map { it.second }) } to sorted.map { b -> med(b.map { it.first }) }
    }
}
