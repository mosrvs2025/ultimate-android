package app.ultimate.mesh

import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Where you are on the device map, from how strongly each phone hears your watch's Bluetooth beacon.
 *
 * Signal strength is a poor ruler: a hand, a body or a wall can halve it. So this gives an area, not a point: a best guess
 * with a radius that grows as the readings disagree, and which device you are nearest (the part it gets right most often).
 * Each phone's own calibration in Spatial (watch touching the phone, then at arm's length) sets how its strength turns
 * into metres, since every phone's antenna differs.
 */
object WhereAmI {
    /** What one device heard: its smoothed strength (dBm), its calibration, and how long ago. */
    data class Heard(val device: String, val dbm: Double, val touch: Double = -45.0, val arm: Double = -70.0, val ageMs: Long = 0)

    data class Estimate(val at: HouseLayout.Point?, val radius: Double, val nearest: String?, val sure: Boolean, val heardBy: Int)

    const val TOUCH_M = 0.1
    const val ARM_M = 0.6

    /**
     * Strength to metres with the usual fall-off law, fitted through the two calibration points (touching = 0.1 m,
     * arm's length = 0.6 m). The fall-off is kept within what rooms really show (1.6 to 4), and the answer within 5 cm to 15 m.
     */
    fun metres(h: Heard): Double {
        val n = ((h.touch - h.arm) / (10 * log10(ARM_M / TOUCH_M))).coerceIn(1.6, 4.0)
        return (TOUCH_M * 10.0.pow((h.touch - h.dbm) / (10 * n))).coerceIn(0.05, 15.0)
    }

    fun estimate(map: Map<String, HouseLayout.Point>, heard: List<Heard>, maxAgeMs: Long = 10_000): Estimate {
        val fresh = heard.filter { it.device in map && it.ageMs <= maxAgeMs && it.dbm.isFinite() }
        if (fresh.isEmpty()) return Estimate(null, Double.NaN, null, false, 0)
        val d = fresh.associate { it.device to metres(it) }
        val ranked = d.entries.sortedBy { it.value }
        val nearest = ranked.first()
        // Sure of the nearest only when the next one is clearly further (half as close or less).
        val sure = ranked.size == 1 && nearest.value < 1.0 || ranked.size >= 2 && nearest.value <= 0.5 * ranked[1].value
        if (fresh.size < 3) return Estimate(map.getValue(nearest.key), max(0.5, nearest.value), nearest.key, sure, fresh.size)

        // Weighted least squares: nearby readings count far more (their error in metres is smaller).
        val wts = d.mapValues { 1.0 / (it.value * it.value + 0.25) }
        var x = d.keys.sumOf { map.getValue(it).x * wts.getValue(it) } / wts.values.sum()
        var y = d.keys.sumOf { map.getValue(it).y * wts.getValue(it) } / wts.values.sum()
        repeat(150) {
            var gx = 0.0; var gy = 0.0; var sw = 0.0
            for ((k, r) in d) {
                val p = map.getValue(k); val dx = x - p.x; val dy = y - p.y; val len = max(1e-6, hypot(dx, dy))
                val w = wts.getValue(k); gx += w * (len - r) * dx / len; gy += w * (len - r) * dy / len; sw += w
            }
            x -= 0.8 * gx / sw; y -= 0.8 * gy / sw
        }
        val res = sqrt(d.entries.sumOf { (k, r) -> val p = map.getValue(k); val e = hypot(x - p.x, y - p.y) - r; wts.getValue(k) * e * e } / wts.values.sum())
        return Estimate(HouseLayout.Point(x, y), max(0.5, 1.5 * res + 0.2 * nearest.value), nearest.key, sure, fresh.size)
    }
}
