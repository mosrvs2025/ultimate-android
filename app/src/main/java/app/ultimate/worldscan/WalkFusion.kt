package app.ultimate.worldscan

import app.ultimate.room.Alignment
import app.ultimate.room.FuseReport
import app.ultimate.room.P3
import app.ultimate.room.RoomMap
import kotlin.math.atan2
import kotlin.math.hypot

/** A stretch of one walk mapped on its own (about every 15 m of path), so that a later walk can be fitted piece by piece: motion tracking drifts, and a street is not one rigid thing. */
class WalkChunk(val map: RoomMap, override val t0Ns: Long, override val t1Ns: Long) : PieceSource {
    override fun load(): RoomMap = map
}

/** What happened to one piece of a walk being added to a place: how it was placed, how well it fit, what it changed. */
class ChunkFit(val index: Int, val alignment: Alignment?, val meanDist: Float, val inlier: Float, val how: String, val report: FuseReport?, val t0Ns: Long = 0L, val t1Ns: Long = 0L)

/** The result of trying to add a walk to a place. [accepted] false means nothing was changed. */
class MergeOutcome(val accepted: Boolean, val reason: String, val global: Registration?, val dy: Float?, val chunks: List<ChunkFit>, val prior: String) {
    val added: Int get() = chunks.sumOf { it.report?.added ?: 0 }
    val reinforced: Int get() = chunks.sumOf { it.report?.reinforced ?: 0 }
    val removed: Int get() = chunks.sumOf { it.report?.removed ?: 0 }
    val used: Int get() = chunks.count { it.report != null }
    val refined: Int get() = chunks.count { it.how == "refined" }
    /** Average distance (cm) of the pieces that were used, weighted equally. */
    val meanCm: Float get() = chunks.filter { it.report != null }.map { it.meanDist * 100f }.average().toFloat()
    fun summary(): String = if (!accepted) "Not added: $reason" else "Added $used of ${chunks.size} pieces ($refined fitted individually); fit %.0f cm on average; +$added new, $reinforced confirmed, −$removed cleared".format(meanCm)
}

/**
 * Adds a later walk to a saved place without ever making it worse.
 *
 *  1. Rough placement of the whole walk. If both walks know where they are on the Earth (see [GeoFit]), the search is limited to the
 *     few degrees and metres that GPS leaves open; if not, every heading is tried near the middle of the saved map.
 *  2. The fit must be clearly the best one: a street that looks the same slid along itself is refused as ambiguous.
 *  3. Each ~15 m piece is then fitted separately, close to the whole-walk placement, because motion tracking drifts and one rigid move
 *     cannot be right for both ends of a long walk. A piece takes its own fit when that is better and unambiguous, the whole-walk
 *     placement when that is already good, and is left out when neither fits.
 *  4. A piece only *removes* what the saved map holds (something that moved, or noise) when its fit is tight (15 cm); a looser fit
 *     can only add and confirm. Pieces that do not fit are not fused at all.
 *
 * Raw pieces of the walk are never touched; the caller keeps them, so a better algorithm or another walk can redo the merge. Pure, so it is tested.
 */
object WalkFusion {
    const val MAX_GLOBAL_DIST = 0.50f
    const val MIN_GLOBAL_INLIER = 0.25f
    const val MAX_PIECE_DIST = 0.35f
    const val TIGHT_DIST = 0.15f

    /** The search window GPS allows, or null when either walk is not on the map well enough. */
    fun prior(fresh: GeoRegistration?, base: GeoRegistration?): Prior? {
        if (fresh == null || base == null || !fresh.usable || !base.usable) return null
        fun toBase(x: Float, z: Float): Pair<Float, Float> {
            val (lat, lon) = fresh.toLatLon(x, z)
            val enu = base.anchor.project(GeoFix(lat, lon, null, 1f, 0L))
            return base.toLocal(enu.eastMeters, enu.northMeters)
        }
        val q0 = toBase(0f, 0f); val q1 = toBase(1f, 0f)
        val yaw = Math.toDegrees(atan2((q1.second - q0.second).toDouble(), (q1.first - q0.first).toDouble())).toFloat()
        val yawR = (3.0 * hypot(fresh.yawSigmaDeg, base.yawSigmaDeg)).coerceIn(6.0, 25.0).toFloat()
        val posR = (3.0 * hypot(fresh.posSigmaM, base.posSigmaM) + 2.0).coerceIn(6.0, 30.0).toFloat()
        return Prior(yaw, yawR, q0.first, q0.second, posR)
    }

    /** Tries to add [chunks] (a walk) to [base]. Changes [base] only when the outcome is accepted. */
    fun merge(base: RoomMap, chunks: List<WalkChunk>, fresh: GeoRegistration?, baseGeo: GeoRegistration?): MergeOutcome {
        if (chunks.isEmpty()) return refuse("the walk has no map", null)
        val union = RoomMap(base.size); for (c in chunks) union.absorb(c.map)
        val sFresh = Structure.of(union); val sBase = Structure.of(base)
        if (sFresh.size < 60 || sBase.size < 60) return refuse("not enough upright structure (walls, buildings) to match on: ${sFresh.size} columns here, ${sBase.size} saved (needs 60+)", null)
        val prior = prior(fresh, baseGeo)
        val how = if (prior != null) "GPS-limited search (±%.0f°, ±%.0f m)".format(prior.yawRadius, prior.radius) else "full search (no usable GPS on both walks)"
        val field = WalkAligner.field(sBase)
        val reg = WalkAligner.register(sFresh, sBase, prior, prebuilt = field) ?: return refuse("no placement found", null, how)
        if (reg.ambiguous) return refuse("the match is not unique: another placement fits almost as well (a long straight wall can slide along itself). Walk a route with corners, or start from a recognisable place", reg, how)
        if (reg.meanDist > MAX_GLOBAL_DIST || reg.inlier < MIN_GLOBAL_INLIER) return refuse("the walks do not overlap enough or differ too much (%.0f cm average, %.0f%% of walls within 30 cm)".format(reg.meanDist * 100, reg.inlier * 100), reg, how)
        val dy = WalkAligner.groundShift(union, base, reg.alignment) ?: return refuse("no ground in common to set the height", reg, how)
        val a0 = reg.alignment.copy(dy = dy)
        // --- fit the pieces
        val plans = ArrayList<ChunkFit>(); val moves = ArrayList<Pair<Alignment, Boolean>?>()
        for ((i, c) in chunks.withIndex()) {
            val sC = Structure.of(c.map)
            if (sC.size < 40) { plans += ChunkFit(i, a0, Float.NaN, Float.NaN, "global (too little structure to check)", null, c.t0Ns, c.t1Ns); moves += a0 to false; continue }
            val (m0, i0) = WalkAligner.evaluate(field, sC, a0)
            val r = WalkAligner.register(sC, sBase, Prior(a0.yawDeg, 4f, a0.dx, a0.dz, 1.5f), minCells = 40, prebuilt = field)
            when {
                r != null && !r.ambiguous && r.meanDist <= MAX_PIECE_DIST && r.meanDist < m0 - 0.01f -> {
                    val a = r.alignment.copy(dy = dy); plans += ChunkFit(i, a, r.meanDist, r.inlier, "refined", null, c.t0Ns, c.t1Ns); moves += a to (r.meanDist <= TIGHT_DIST && r.inlier >= 0.5f)
                }
                m0 <= MAX_PIECE_DIST -> { plans += ChunkFit(i, a0, m0, i0, "global", null, c.t0Ns, c.t1Ns); moves += a0 to (m0 <= TIGHT_DIST && i0 >= 0.5f) }
                else -> { plans += ChunkFit(i, null, m0, i0, "left out (fits ${"%.0f".format(m0 * 100)} cm)", null, c.t0Ns, c.t1Ns); moves += null }
            }
        }
        if (moves.none { it != null }) return refuse("no piece of the walk fits the saved map closely enough", reg, how)
        // --- fuse (only now does the saved map change)
        val done = ArrayList<ChunkFit>()
        for ((i, p) in plans.withIndex()) {
            val mv = moves[i]
            if (mv == null) { done += p; continue }
            val (a, clear) = mv
            val report = base.fuse(chunks[i].map.mapped { q -> a.apply(q) }, clear = clear)
            done += ChunkFit(p.index, a, p.meanDist, p.inlier, p.how, report, p.t0Ns, p.t1Ns)
        }
        return MergeOutcome(true, "ok", reg, dy, done, how)
    }

    private fun refuse(why: String, reg: Registration?, how: String = "") = MergeOutcome(false, why, reg, null, emptyList(), how)

    /** Where each pose of a merged walk lies in the saved map's frame: the piece's own placement, else the whole walk's. */
    fun placeTrack(track: WalkTrack, fits: List<ChunkFit>, fallback: Alignment?): List<P3> {
        val out = ArrayList<P3>(track.points.size)
        for (p in track.points) {
            val a = fits.firstOrNull { it.alignment != null && p.tNs in it.t0Ns..it.t1Ns }?.alignment ?: fallback ?: continue
            out += a.apply(P3(p.x, p.y, p.z))
        }
        return out
    }
}
