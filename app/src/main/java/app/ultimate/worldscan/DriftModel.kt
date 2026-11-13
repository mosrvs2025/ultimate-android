package app.ultimate.worldscan

import app.ultimate.room.Alignment
import app.ultimate.room.P3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** What fitting a drift model to a walk found. [applicable] is the app's own verdict on whether correcting is worth offering; [why] says why not, or what the gain is. */
class DriftFit(val aDegPerM: Double, val b: Double, val pathM: Double, val rmsBeforeM: Double, val rmsAfterM: Double, val pairs: Int, val applicable: Boolean, val why: String) {
    val improvement: Double get() = if (rmsBeforeM > 0) 1.0 - rmsAfterM / rmsBeforeM else 0.0
    fun summary(): String = if (applicable) "GPS suggests the walk's heading drifted %.2f° over every 100 m and its length is %+.1f%%; correcting would cut the walk-versus-GPS disagreement from %.1f m to %.1f m".format(aDegPerM * 100, b * 100, rmsBeforeM, rmsAfterM) else why
}

/**
 * Motion tracking drifts: its heading slowly goes wrong and its steps are a little long or short, so a long walk bends away from where it
 * really went. GPS is noisy but does not drift, so over a long walk the two disagree by more and more. This fits the simplest model of that
 * drift to the disagreement: the walk is re-integrated step by step with its heading turned a further [a] degrees for every metre walked
 * and every step scaled by (1 + [b]); [a] and [b] are searched for the pair that brings the walk closest to GPS (each pair is scored after
 * the best turn and shift onto the Earth is found for it, so the fit cannot use drift to hide a wrong start heading).
 *
 * Offered only when the evidence is strong, because a GPS bias (an urban canyon) can look like drift and bending a correct map toward a wrong
 * track would make it worse: at least 150 m walked, 20 fixes, a walk that already fits GPS well enough to be placed, plausible values
 * (heading drift within 0.04 degrees a metre, scale within 3%), a statistically significant gain (chi-square over 13.8, allowing for GPS error that persists in time) that moves the walk by at least 2 m, and no worse in either half of
 * the walk. Pure, so it is tested.
 */
object DriftModel {
    const val MIN_PATH_M = 150.0
    const val MIN_PAIRS = 20
    const val MAX_A = 0.04
    const val MAX_B = 0.03

    /** Planar positions (x, then -z) of every pose after the drift model: two numbers per pose. */
    fun integrate(track: WalkTrack, a: Double, b: Double): DoubleArray {
        val p = track.points; val out = DoubleArray(p.size * 2)
        if (p.isEmpty()) return out
        out[0] = p[0].x.toDouble(); out[1] = -p[0].z.toDouble()
        var s = 0.0
        for (k in 1 until p.size) {
            val dx = (p[k].x - p[k - 1].x).toDouble(); val dy = -(p[k].z - p[k - 1].z).toDouble()
            val step = hypot(dx, dy)
            if (p[k].tracking == TrackingQuality.TRACKING && p[k - 1].tracking == TrackingQuality.TRACKING && step < 3.0) s += step
            val ang = Math.toRadians(a * s); val c = cos(ang); val sn = sin(ang)
            out[2 * k] = out[2 * k - 2] + (1 + b) * (c * dx - sn * dy); out[2 * k + 1] = out[2 * k - 1] + (1 + b) * (sn * dx + c * dy)
        }
        return out
    }

    /** The walk with the drift model applied: same times, heights and tracking states, corrected horizontal positions. */
    fun apply(track: WalkTrack, a: Double, b: Double): WalkTrack {
        val pos = integrate(track, a, b); val out = WalkTrack()
        track.points.forEachIndexed { i, q -> out.add(PosePoint(q.tNs, pos[2 * i].toFloat(), q.y, (-pos[2 * i + 1]).toFloat(), q.qx, q.qy, q.qz, q.qw, q.tracking)) }
        return out
    }

    /** Where each GPS fix falls on the walk (pose before it, how far to the next), once, so every candidate drift only re-reads positions. */
    private class Hit(val lo: Int, val f: Double, val e: Double, val n: Double, val sigma: Double, val t: Long)

    private fun hits(track: WalkTrack, fixes: List<GeoFix>, anchor: GeoAnchor): List<Hit> {
        val p = track.points; val out = ArrayList<Hit>()
        for (fx in fixes) {
            if (!fx.isUsable(25f) || p.size < 2 || fx.elapsedRealtimeNanos < p.first().tNs || fx.elapsedRealtimeNanos > p.last().tNs) continue
            var lo = 0; var hi = p.size - 1
            while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (p[mid].tNs <= fx.elapsedRealtimeNanos) lo = mid else hi = mid }
            if (p[lo].tracking != TrackingQuality.TRACKING || p[hi].tracking != TrackingQuality.TRACKING || p[hi].tNs - p[lo].tNs > 1_500_000_000L) continue
            val f = if (p[hi].tNs == p[lo].tNs) 0.0 else (fx.elapsedRealtimeNanos - p[lo].tNs).toDouble() / (p[hi].tNs - p[lo].tNs)
            val enu = anchor.project(fx)
            out += Hit(lo, f, enu.eastMeters, enu.northMeters, maxOf(GeoFit.SIGMA_FLOOR_M, fx.horizontalAccuracyMeters / 1.5), fx.elapsedRealtimeNanos)
        }
        return out
    }

    private class Score(val rms: Double, val halfA: Double, val halfB: Double, val reg: GeoRegistration)

    private fun score(pos: DoubleArray, h: List<Hit>, anchor: GeoAnchor): Score {
        val pairs = h.map { x -> GeoPair(x.t, pos[2 * x.lo] + (pos[2 * x.lo + 2] - pos[2 * x.lo]) * x.f, pos[2 * x.lo + 1] + (pos[2 * x.lo + 3] - pos[2 * x.lo + 1]) * x.f, x.e, x.n, x.sigma) }
        val reg = GeoFit.fit(pairs, anchor)
        val c = cos(reg.thetaRad); val s = sin(reg.thetaRad)
        // Squared residual capped at three sigma, so one wild fix cannot decide the fit.
        val r = pairs.map { q -> val dx = c * q.ax - s * q.ay + reg.tx - q.e; val dy = s * q.ax + c * q.ay + reg.ty - q.n; min(dx * dx + dy * dy, 9 * q.sigma * q.sigma) }
        val mid = r.size / 2
        return Score(sqrt(r.average()), sqrt(r.take(mid).average()), sqrt(r.drop(mid).average()), reg)
    }

    fun fit(track: WalkTrack, fixes: List<GeoFix>, anchor: GeoAnchor): DriftFit {
        val h = hits(track, fixes, anchor); val path = track.lengthM
        fun no(why: String) = DriftFit(0.0, 0.0, path, Double.NaN, Double.NaN, h.size, false, why)
        if (path < MIN_PATH_M) return no("A walk of at least ${MIN_PATH_M.toInt()} m is needed to tell drift from GPS noise (this one is ${path.toInt()} m).")
        if (h.size < MIN_PAIRS) return no("Only ${h.size} GPS fixes line up with the walk (needs $MIN_PAIRS).")
        val base = score(integrate(track, 0.0, 0.0), h, anchor)
        if (!base.reg.usable) return no("The walk does not fit GPS well enough to be placed, so drift can't be measured against it.")
        var bestA = 0.0; var bestB = 0.0; var best = base
        fun tryAB(a: Double, b: Double) { if (Math.abs(a) > MAX_A || Math.abs(b) > MAX_B) return; val s = score(integrate(track, a, b), h, anchor); if (s.rms < best.rms) { best = s; bestA = a; bestB = b } }
        var a = -MAX_A; while (a <= MAX_A + 1e-9) { var b = -MAX_B; while (b <= MAX_B + 1e-9) { tryAB(a, b); b += 0.006 }; a += 0.01 }
        var da = 0.005; var db = 0.003
        repeat(4) { val ca = bestA; val cb = bestB; for (i in -1..1) for (j in -1..1) if (i != 0 || j != 0) tryAB(ca + i * da, cb + j * db); da /= 2; db /= 2 }
        // Is the improvement real, or GPS noise being fitted? The two extra parameters buy a drop in squared disagreement; measured in units of the noise that is left
        // (and divided by (1+rho)/(1-rho), because GPS error that persists over time makes n fixes worth far fewer independent ones), a real effect gives a large chi-square
        // for two degrees of freedom (13.8 is p = 0.001). A significant but tiny correction is not worth the risk either, so the correction must also move the walk by metres.
        val n = h.size; val rho = best.reg.rho
        val chi2 = if (best.rms > 0) n * (base.rms * base.rms - best.rms * best.rms) / (best.rms * best.rms * ((1 + rho) / (1 - rho))) else 0.0
        val corrected = integrate(track, bestA, bestB); val raw = integrate(track, 0.0, 0.0)
        val shifts = (0 until track.points.size).map { hypot(corrected[2 * it] - raw[2 * it], corrected[2 * it + 1] - raw[2 * it + 1]) }.sorted()
        val effect = if (shifts.isEmpty()) 0.0 else shifts[(shifts.size * 0.9).toInt().coerceAtMost(shifts.size - 1)]
        val fitOut = { ok: Boolean, why: String -> DriftFit(bestA, bestB, path, base.rms, best.rms, n, ok, why) }
        return when {
            bestA == 0.0 && bestB == 0.0 -> fitOut(false, "No drift worth correcting: the walk already agrees with GPS as well as a drifting model can make it.")
            chi2 < 13.8 -> fitOut(false, "GPS disagreement would fall from %.1f m to %.1f m, which GPS noise alone could produce (significance %.0f, needs 14), so the walk is left as it is.".format(base.rms, best.rms, chi2))
            effect < 2.0 -> fitOut(false, "The drift is real but small: the correction would move the walk by under 2 m, so it is not worth changing.")
            best.halfA > base.halfA * 1.05 || best.halfB > base.halfB * 1.05 -> fitOut(false, "The correction helps one half of the walk and hurts the other, which looks like fitting noise, so it is not offered.")
            else -> fitOut(true, "ok")
        }
    }

    /** A piece's turn, shift and stretch: the rigid [alignment] to the corrected path, then stretched by [scale] about the corrected start of the piece ([cx], [cz]). */
    class PieceMove(val alignment: Alignment, val scale: Float, val cx: Float, val cz: Float) {
        fun apply(p: P3): P3 { val q = alignment.apply(p); return P3(cx + scale * (q.x - cx), q.y, cz + scale * (q.z - cz)) }
        companion object { val IDENTITY = PieceMove(Alignment.IDENTITY, 1f, 0f, 0f) }
    }

    /**
     * For each piece of the walk (its time span), how its map is carried from the raw walk to the corrected walk: the piece's start goes where the
     * correction puts it, the piece is turned by the difference in direction between its start and end and stretched by the difference in length.
     * Within a piece of about 15 m the bend that such a move cannot follow is a few centimetres at realistic drift.
     */
    fun pieceMoves(raw: WalkTrack, corrected: WalkTrack, spans: List<LongArray>): List<PieceMove> = spans.map { sp ->
        val r0 = raw.at(sp[0]); val r1 = raw.at(sp[1]); val c0 = corrected.at(sp[0]); val c1 = corrected.at(sp[1])
        if (r0 == null || r1 == null || c0 == null || c1 == null) return@map PieceMove.IDENTITY
        val rdx = (r1.x - r0.x).toDouble(); val rdy = -(r1.z - r0.z).toDouble(); val cdx = (c1.x - c0.x).toDouble(); val cdy = -(c1.z - c0.z).toDouble()
        val usable = hypot(rdx, rdy) >= 1.0 && hypot(cdx, cdy) >= 1.0
        val phi = if (usable) atan2(cdy, cdx) - atan2(rdy, rdx) else 0.0              // turn of the piece in the (x, -z) plane
        val scale = if (usable) (hypot(cdx, cdy) / hypot(rdx, rdy)).toFloat().coerceIn(0.9f, 1.1f) else 1f
        val yawDeg = -Math.toDegrees(phi)                                              // Alignment turns (x, z), which is the mirror of (x, -z)
        val rad = Math.toRadians(yawDeg); val cc = cos(rad).toFloat(); val ss = sin(rad).toFloat()
        val dx = c0.x - (r0.x * cc - r0.z * ss); val dz = c0.z - (r0.x * ss + r0.z * cc)
        PieceMove(Alignment(yawDeg.toFloat(), dx, 0f, dz), scale, c0.x, c0.z)
    }
}
