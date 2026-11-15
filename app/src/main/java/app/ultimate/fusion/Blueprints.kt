package app.ultimate.fusion

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor

/**
 * Places you go back to. A finished walk becomes a [Footprint], the set of small squares of ground its route touched. When a later
 * walk covers most of the same squares in both directions (a big walk doesn't "match" a small yard it merely passes through), it is
 * the same place: after the second visit the phone offers to name it ("Backyard loop"), and from then on every visit is tagged with
 * how many times you've been and how its pace compares with your usual there.
 *
 * Everything is on the phone, and a place is only squares of ground plus a few totals: no photos, no times of day, nothing sent.
 * Pure and deterministic, so it is tested with made-up routes.
 */
object Blueprints {
    /** Side of one square of ground, in metres. About a GPS fix's worth of wobble, so a wandering line stays in its squares. */
    const val CELL = 6.0
    /** How much of each route must lie on the other for two walks to be the same place. */
    const val SAME = 0.6
    /** The most places kept; the oldest unnamed ones go first. */
    const val MAX_PLACES = 40
    private const val M_PER_DEG = 111_320.0

    /** The ground a walk covered, in squares counted from its own origin ([lat0], [lon0]). */
    class Footprint(val lat0: Double, val lon0: Double, val cells: Set<Long>, val turnsPerKm: Double)

    data class Run(val id: String, val metres: Double, val seconds: Double)
    data class Place(val id: String, val name: String, val footprint: Footprint, val runs: List<Run>, val declined: Boolean = false) {
        val named get() = name.isNotBlank()
    }

    sealed interface Outcome {
        object Nothing : Outcome
        /** This walk is a visit to [place]; it is visit number [visit] and its pace is [pace] against the earlier visits (null when unknown). */
        data class Visit(val place: Place, val visit: Int, val pace: Double?) : Outcome
        /** The same ground was walked twice and has no name yet: offer to name it. */
        data class Offer(val place: Place) : Outcome
    }

    fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) xor (cy.toLong() and 0xffffffffL)
    private fun cx(k: Long) = (k shr 32).toInt()
    private fun cy(k: Long) = k.toInt()

    /** The footprint of a fused path (needs it to sit on the map: [FusedPath.anchor]), or null for a walk too short to be a place. */
    fun footprint(path: FusedPath, turns: Int, metres: Double): Footprint? {
        val a = path.anchor ?: return null
        if (metres < 30 || path.size < 20) return null
        val cells = HashSet<Long>()
        for (i in 0 until path.size) cells += key(floor(path.e[i] / CELL).toInt(), floor(path.n[i] / CELL).toInt())
        if (cells.size < 6) return null
        return Footprint(a[0], a[1], cells, turns / (metres / 1000.0))
    }

    /** How alike two footprints are, 0 to 1: the smaller share of either route that lies on the other, after the best nudge (up to 12 m) for a day's GPS drift. */
    fun overlap(a: Footprint, b: Footprint): Double {
        // b's squares re-counted from a's origin.
        val mLat = M_PER_DEG; val mLon = M_PER_DEG * cos(Math.toRadians(a.lat0))
        val dE = (b.lon0 - a.lon0) * mLon; val dN = (b.lat0 - a.lat0) * mLat
        if (abs(dE) > 5_000 || abs(dN) > 5_000) return 0.0
        val bIn = b.cells.map { k -> floor(((cx(k) + 0.5) * CELL + dE) / CELL).toInt() to floor(((cy(k) + 0.5) * CELL + dN) / CELL).toInt() }
        var best = 0.0
        for (sx in -2..2) for (sy in -2..2) {
            val moved = bIn.map { (x, y) -> key(x + sx, y + sy) }.toSet()
            val inter = moved.count { it in a.cells }
            val s = minOf(inter.toDouble() / moved.size, inter.toDouble() / a.cells.size)
            if (s > best) best = s
        }
        return best
    }

    private fun same(a: Footprint, b: Footprint): Boolean {
        if (overlap(a, b) < SAME) return false
        // A back-and-forth yard and a straight street can share squares; their turning gives them away.
        val lo = minOf(a.turnsPerKm, b.turnsPerKm); val hi = maxOf(a.turnsPerKm, b.turnsPerKm)
        return !(hi >= 8 && hi > 4 * (lo + 1))
    }

    /**
     * Records that walk [id] (a footprint [f], [metres] long over [seconds]) happened, and says what it means. Asking again about
     * the same walk changes nothing, so reopening a review never counts a visit twice.
     */
    fun see(places: List<Place>, id: String, f: Footprint, metres: Double, seconds: Double): Pair<List<Place>, Outcome> {
        places.firstOrNull { p -> p.runs.any { it.id == id } }?.let { p -> return places to outcomeFor(p, id, offer = false) }
        val run = Run(id, metres, seconds)
        val hit = places.filter { same(it.footprint, f) }.maxWithOrNull(compareBy({ it.named }, { overlap(it.footprint, f) }))
        if (hit != null) {
            val updated = hit.copy(runs = (hit.runs + run).takeLast(50))
            val next = places.map { if (it.id == hit.id) updated else it }
            return next to outcomeFor(updated, id, offer = !hit.named && !hit.declined && updated.runs.size >= 2)
        }
        val fresh = Place("p" + System.currentTimeMillis().toString(36) + places.size, "", f, listOf(run))
        val all = places + fresh
        val trimmed = if (all.size <= MAX_PLACES) all else all.toMutableList().also { l -> l.firstOrNull { !it.named }?.let { l.remove(it) } }
        return trimmed to Outcome.Nothing
    }

    private fun outcomeFor(p: Place, id: String, offer: Boolean): Outcome {
        if (offer) return Outcome.Offer(p)
        if (!p.named) return Outcome.Nothing
        val i = p.runs.indexOfFirst { it.id == id }
        val before = p.runs.take(i)
        val speed = { r: Run -> if (r.seconds > 0) r.metres / r.seconds else 0.0 }
        val usual = before.filter { it.seconds > 0 }.map(speed).takeIf { it.isNotEmpty() }?.average()
        val now = speed(p.runs[i])
        return Outcome.Visit(p, i + 1, if (usual != null && usual > 0.05 && now > 0) now / usual - 1 else null)
    }

    fun name(places: List<Place>, placeId: String, name: String): List<Place> {
        val n = name.filter { it >= ' ' }.trim().take(30)
        return places.map { if (it.id == placeId) it.copy(name = n, declined = false) else it }
    }
    fun decline(places: List<Place>, placeId: String): List<Place> = places.map { if (it.id == placeId) it.copy(declined = true) else it }

    /** "Pace 8% faster than your usual here", or null when the difference is too small to mean anything. */
    fun paceWords(pace: Double?): String? = pace?.takeIf { abs(it) >= 0.05 }?.let { "Pace ${"%.0f".format(java.util.Locale.US, abs(it) * 100)}% ${if (it > 0) "faster" else "slower"} than your usual here." }

    fun ordinal(n: Int): String = when { n % 100 in 11..13 -> "${n}th"; n % 10 == 1 -> "${n}st"; n % 10 == 2 -> "${n}nd"; n % 10 == 3 -> "${n}rd"; else -> "${n}th" }

    // --- saving ---

    fun encode(places: List<Place>): String = JSONArray().also { a ->
        places.forEach { p ->
            a.put(JSONObject().put("id", p.id).put("name", p.name).put("declined", p.declined)
                .put("lat0", p.footprint.lat0).put("lon0", p.footprint.lon0).put("tpk", p.footprint.turnsPerKm)
                .put("cells", JSONArray().also { c -> p.footprint.cells.forEach { k -> c.put(cx(k)); c.put(cy(k)) } })
                .put("runs", JSONArray().also { r -> p.runs.forEach { r.put(JSONObject().put("id", it.id).put("m", it.metres).put("s", it.seconds)) } }))
        }
    }.toString()

    fun decode(text: String?): List<Place> = runCatching {
        val a = JSONArray(text ?: return emptyList())
        (0 until a.length()).mapNotNull { i -> runCatching {
            val o = a.getJSONObject(i); val c = o.getJSONArray("cells"); val r = o.getJSONArray("runs")
            val cells = HashSet<Long>(); var j = 0; while (j + 1 < c.length()) { cells += key(c.getInt(j), c.getInt(j + 1)); j += 2 }
            Place(o.getString("id"), o.optString("name", ""), Footprint(o.getDouble("lat0"), o.getDouble("lon0"), cells, o.optDouble("tpk", 0.0)),
                (0 until r.length()).map { k -> r.getJSONObject(k).let { Run(it.getString("id"), it.getDouble("m"), it.getDouble("s")) } }, o.optBoolean("declined", false))
        }.getOrNull() }.take(MAX_PLACES)
    }.getOrDefault(emptyList())
}
