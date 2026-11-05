package app.ultimate.echo

import android.content.Context
import kotlin.math.sqrt

/**
 * Turns Echo's raw distance into the distance you actually held, fitted from your own bench run (a straight-line least
 * squares fit, true = offset + gain × read). It corrects the phone's geometry (where its speaker and microphone are)
 * and any steady bias; it can't fix readings that jump about, so the fit reports how far it still misses.
 */
data class EchoCalibration(val offsetM: Double, val gain: Double, val rmsErrorCm: Double, val points: Int) {
    fun apply(rawM: Double): Double = if (rawM.isNaN()) rawM else (offsetM + gain * rawM).coerceAtLeast(0.0)

    companion object {
        /** Needs at least two heights that read differently; refuses a fit that runs backwards or is wildly steep. */
        fun fit(heldCm: List<Double>, readCm: List<Double>): EchoCalibration? {
            val pairs = heldCm.zip(readCm).filter { !it.second.isNaN() }
            if (pairs.size < 2) return null
            val mx = pairs.map { it.second }.average(); val my = pairs.map { it.first }.average()
            val sxx = pairs.sumOf { (it.second - mx) * (it.second - mx) }
            if (sxx < 0.25) return null // all read about the same: nothing to fit
            val gain = pairs.sumOf { (it.second - mx) * (it.first - my) } / sxx
            if (gain <= 0.2 || gain > 5) return null
            val offset = my - gain * mx
            val rms = sqrt(pairs.sumOf { val e = offset + gain * it.second - it.first; e * e } / pairs.size)
            return EchoCalibration(offset / 100, gain, rms, pairs.size)
        }

        private fun prefs(c: Context) = c.getSharedPreferences("echo_calibration", Context.MODE_PRIVATE)
        fun load(c: Context): EchoCalibration? = prefs(c).takeIf { it.contains("gain") }?.let {
            EchoCalibration(it.getFloat("offset", 0f).toDouble(), it.getFloat("gain", 1f).toDouble(), it.getFloat("rms", 0f).toDouble(), it.getInt("points", 0))
        }
        fun save(c: Context, cal: EchoCalibration?) = prefs(c).edit().apply {
            if (cal == null) clear() else { putFloat("offset", cal.offsetM.toFloat()); putFloat("gain", cal.gain.toFloat()); putFloat("rms", cal.rmsErrorCm.toFloat()); putInt("points", cal.points) }
        }.apply()
    }
}
