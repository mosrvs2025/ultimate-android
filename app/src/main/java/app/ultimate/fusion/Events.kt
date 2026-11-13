package app.ultimate.fusion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class Turn(val span: Span, val degrees: Double, val uTurn: Boolean) { val left: Boolean get() = degrees < 0 }
data class Stop(val span: Span)
data class Lap(val index: Int, val span: Span, val metres: Double, val seconds: Double)
data class GaitSegment(val span: Span, val gait: Gait, val cadenceHz: Double?, val confidence: Double)
data class SpeedPoint(val t: Double, val metresPerSecond: Double)

/**
 * Events read off the fused path: where the person stopped, turned, and came back round. All deterministic arithmetic on the path (no
 * model), so the same recording always gives the same list, and each event carries the span of the recording it came from.
 */
object Events {
    /** Speed over a 5 s window centred on each second, from the path. */
    fun speeds(p: FusedPath): List<SpeedPoint> {
        if (p.size < 3) return emptyList()
        return (0 until p.size).map { i ->
            val a = max(0, i - 2); val b = min(p.size - 1, i + 2)
            val dt = (p.t[b] - p.t[a]).coerceAtLeast(1e-6)
            SpeedPoint(p.t[i], p.lengthBetween(a, b) / dt)
        }
    }

    /** Stretches of at least 5 s where the person hardly moved. */
    fun stops(sp: List<SpeedPoint>, minS: Double = 5.0, below: Double = 0.4): List<Stop> {
        val out = ArrayList<Stop>(); var from: Double? = null; var last = 0.0
        for (s in sp) {
            if (s.metresPerSecond < below) { if (from == null) from = s.t; last = s.t }
            else { if (from != null && last - from >= minS) out += Stop(Span(from, last)); from = null }
        }
        if (from != null && last - from >= minS) out += Stop(Span(from, last))
        return out
    }

    /** Direction changes of at least 50° within about four seconds of movement; 150° or more is a U-turn. */
    fun turns(p: FusedPath, sp: List<SpeedPoint>): List<Turn> {
        if (p.size < 10) return emptyList()
        // Bearing from where the path was 1.5 s ago to where it is 1.5 s ahead, only where it is really moving.
        val b = DoubleArray(p.size) { Double.NaN }
        for (i in 0 until p.size) {
            val a = max(0, i - 2); val c = min(p.size - 1, i + 2)
            val dx = p.e[c] - p.e[a]; val dy = p.n[c] - p.n[a]
            if (hypot(dx, dy) >= 2.0 && sp[i].metresPerSecond > 0.6) b[i] = atan2(dx, dy) // compass: clockwise from north
        }
        // Unwrap across the valid stretches.
        val u = DoubleArray(p.size) { Double.NaN }
        var prev = Double.NaN; var acc = 0.0
        for (i in b.indices) { val x = b[i]; if (x.isNaN()) { prev = Double.NaN; continue }; if (!prev.isNaN()) { var d = x - prev; while (d > PI) d -= 2 * PI; while (d < -PI) d += 2 * PI; acc += d } else acc = x; u[i] = acc; prev = x }
        val turns = ArrayList<Turn>()
        var i = 0
        val w = 4
        while (i < p.size - w) {
            val a = u[i]; val c = u[i + w]
            if (!a.isNaN() && !c.isNaN() && abs(Math.toDegrees(c - a)) >= 50.0) {
                // Extend while it keeps turning the same way.
                var end = i + w; val sign = if (c > a) 1 else -1
                while (end + 1 < p.size && !u[end + 1].isNaN() && (u[end + 1] - u[end]) * sign > 0.01) end++
                var start = i; while (start - 1 >= 0 && !u[start - 1].isNaN() && (u[start] - u[start - 1]) * sign > 0.01) start--
                val deg = Math.toDegrees(u[end] - u[start])
                if (abs(deg) >= 50.0) turns += Turn(Span(p.t[start], p.t[end]), deg, abs(deg) >= 150.0)
                i = end + 1
            } else i++
        }
        return turns
    }

    /**
     * Laps: returns to where the recording began. A lap is counted each time the path comes back within [gate] metres of the start after
     * having been at least [away] metres from it, and at least 15 s after the last one. The gate is wider when the path came from step
     * counting alone, which cannot return exactly to its start because its own errors have accumulated.
     */
    fun laps(p: FusedPath, gate: Double = 12.0, away: Double = 30.0): List<Lap> {
        if (p.size < 20) return emptyList()
        val out = ArrayList<Lap>()
        val x0 = p.e[0]; val y0 = p.n[0]
        var left = false; var lapStart = 0; var lastCross = -1e9
        for (i in 1 until p.size) {
            val d = hypot(p.e[i] - x0, p.n[i] - y0)
            if (d >= away) left = true
            if (left && d <= gate && p.t[i] - lastCross >= 15.0 && p.t[i] - p.t[lapStart] >= 15.0) {
                out += Lap(out.size + 1, Span(p.t[lapStart], p.t[i]), p.lengthBetween(lapStart, i), p.t[i] - p.t[lapStart])
                lapStart = i; lastCross = p.t[i]; left = false
            }
        }
        return out
    }

    /** Merges the inertial gait windows into runs of the same gait. */
    fun gaitSegments(windows: List<Pdr.Window>): List<GaitSegment> {
        val out = ArrayList<GaitSegment>()
        for (w in windows) {
            val l = out.lastOrNull()
            if (l != null && l.gait == w.gait && w.span.from <= l.span.to + 1e-9) {
                out[out.size - 1] = GaitSegment(Span(l.span.from, w.span.to), l.gait, listOfNotNull(l.cadenceHz, w.cadenceHz).average().takeIf { !it.isNaN() }, min(l.confidence, w.confidence))
            } else out += GaitSegment(w.span, w.gait, w.cadenceHz, w.confidence)
        }
        return out
    }

    /** Spread of lap lengths, as a fraction: how steady the person's route was. */
    fun lapConsistency(laps: List<Lap>): Double? {
        if (laps.size < 2) return null
        val m = laps.map { it.metres }.average()
        val sd = sqrt(laps.sumOf { (it.metres - m) * (it.metres - m) } / laps.size)
        return if (m > 0) sd / m else null
    }
}
