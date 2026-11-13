package app.ultimate.worldscan

import app.ultimate.room.Alignment
import app.ultimate.room.RoomMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** How the camera's clock compared with the sensors' for one walk (see [ClockSync]); stored so the number can be audited later. */
data class ClockInfo(val sameClock: Boolean?, val jitterMs: Double, val samples: Long, val driftMsPerHour: Double?)

/** One piece of a merged walk: its time span, how it was placed and what it changed. */
data class PieceRecord(val index: Int, val t0Ns: Long, val t1Ns: Long, val alignment: Alignment?, val how: String, val meanDist: Float, val inlier: Float, val added: Int, val reinforced: Int, val removed: Int)

/** How a walk was added to its place. */
data class MergeRecord(val at: Long, val global: Alignment, val dy: Float, val globalMeanDist: Float, val globalInlier: Float, val margin: Float, val prior: String, val pieces: List<PieceRecord>) {
    val used: Int get() = pieces.count { it.alignment != null }
}

/** A finished walk, as stored in its place. */
data class WalkMeta(
    val id: String, val name: String, val startedAt: Long, val endedAt: Long,
    val lengthM: Double, val durationS: Double, val trackedShare: Double, val longestLossS: Double, val jumps: Int, val speedP95: Double,
    val extentX: Double, val extentZ: Double, val cubes: Int, val keyFrames: Int, val diag: String,
    val geo: GeoRegistration?, val fixes: Int, val gpsMedianAccM: Double?, val clock: ClockInfo?,
    val logBytes: Long, val logTruncated: Boolean, val sensorCounts: Map<Int, Int>,
    val pieces: List<LongArray>,        // each [t0Ns, t1Ns], on the elapsed-realtime clock
    val merge: MergeRecord?, val unmergedReason: String?,
    /** Highest thermal status the phone reported during the walk (0 none .. 3 severe .. 6 shutdown; -1 unknown) and the battery percentage at the start and end (-1 unknown). */
    val thermalMax: Int = -1, val batteryStart: Int = -1, val batteryEnd: Int = -1,
)

/** A place: a first walk that defines the map's frame, and later walks added to it (or kept apart with the reason). */
data class Place(val id: String, val name: String, val createdAt: Long, val updatedAt: Long, val baseWalkId: String, val walks: List<WalkMeta>, val mergeOrder: List<String>,
    /** Where the place is on the Earth, fitted from every walk's GPS together ([PlaceGeo]); null when none of its walks had usable GPS. */
    val geo: GeoRegistration? = null, val geoWalks: Int = 0) {
    /** The best placement known: the combined one, else the first walk's own. */
    val bestGeo: GeoRegistration? get() = geo ?: walks.firstOrNull { it.id == baseWalkId }?.geo
}

/** Everything captured by one walk, ready to be stored. Times in [track] and [pieces] are on ARCore's frame clock; they are converted to the sensors' clock with [clock] when stored. */
class WalkInput(
    val id: String, val name: String, val startedAt: Long, val endedAt: Long, val track: WalkTrack, val pieces: List<WalkChunk>, val clock: ClockSync?, val fixes: List<GeoFix>,
    val recorder: RecorderSummary?, val rawLog: File?, val keyFrames: Int, val diag: String,
    val thermalMax: Int = -1, val batteryStart: Int = -1, val batteryEnd: Int = -1,
)

class SaveResult(val place: Place, val walk: WalkMeta, val merge: MergeOutcome?)

/**
 * Walks kept on this phone, in the app's private storage (no other app can read it, nothing is uploaded). Layout:
 * `places/<id>/place.json`, `base.bin.gz` (the merged map, in the first walk's frame), `base.prev.gz` (before the last merge, for undo) and
 * `walks/<id>/{walk.json, track.bin.gz, piece_NNN.bin.gz, sensors.jsonl.gz}`. Every walk keeps its own raw pieces, track and sensor log
 * forever, however it was merged: a merge only ever changes `base.bin.gz`, so it can be undone, redone with a better algorithm, or
 * refused without losing anything.
 */
class WalkStore(private val root: File) {
    private val placesDir get() = File(root, "places").also { it.mkdirs() }
    /** Where a walk's sensor log is written while it is being recorded, before it belongs to a place. */
    fun incomingLog(walkId: String): File = File(File(root, "incoming"), "$walkId.jsonl.gz")

    fun freeBytes(): Long = root.apply { mkdirs() }.usableSpace
    /** Walks are stored compressed, but a long one still needs room: refuse to start with less than this free. */
    fun hasRoomToStart(minFree: Long = 300L * 1024 * 1024): Boolean = freeBytes() >= minFree

    fun places(): List<Place> = placesDir.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { runCatching { loadPlace(it) }.getOrNull() }.sortedByDescending { it.updatedAt }
    fun place(id: String): Place? = File(placesDir, id).takeIf { it.isDirectory }?.let { runCatching { loadPlace(it) }.getOrNull() }
    fun bytes(placeId: String): Long = File(placesDir, placeId).walkTopDown().filter { it.isFile }.sumOf { it.length() }
    /** Removes logs of walks that were interrupted (the phone died or the app was killed) a week or more ago. */
    fun tidyIncoming(now: Long = System.currentTimeMillis()) { File(root, "incoming").listFiles().orEmpty().filter { now - it.lastModified() > 7L * 24 * 3600 * 1000 }.forEach { it.delete() } }

    fun baseMap(placeId: String): RoomMap? = runCatching { GZIPInputStream(File(File(placesDir, placeId), "base.bin.gz").inputStream()).use { RoomMap.read(it) } }.getOrNull()
    fun track(placeId: String, walkId: String): WalkTrack? = runCatching { GZIPInputStream(File(walkDir(placeId, walkId), "track.bin.gz").inputStream()).use { WalkTrack.read(it) } }.getOrNull()
    fun pieces(placeId: String, walkId: String): List<WalkChunk> {
        val w = place(placeId)?.walks?.firstOrNull { it.id == walkId } ?: return emptyList()
        return w.pieces.mapIndexedNotNull { i, t -> runCatching { GZIPInputStream(File(walkDir(placeId, walkId), pieceName(i)).inputStream()).use { WalkChunk(RoomMap.read(it), t[0], t[1]) } }.getOrNull() }
    }
    fun rawLog(placeId: String, walkId: String): File? = File(walkDir(placeId, walkId), "sensors.jsonl.gz").takeIf { it.exists() }
    private fun walkDir(placeId: String, walkId: String) = File(File(File(placesDir, placeId), "walks"), walkId)
    private fun pieceName(i: Int) = "piece_%03d.bin.gz".format(i)

    /**
     * Stores a walk. With [placeId] null it starts a new place, whose map frame is this walk's. With a place, the walk is added to it:
     * kept as its own walk either way, and merged into the place's map only if [WalkFusion] finds a placement it can stand behind.
     */
    fun save(input: WalkInput, placeId: String?, now: Long = System.currentTimeMillis()): SaveResult {
        val pid = placeId ?: UUID.randomUUID().toString()
        try { return saveInto(input, placeId, pid, now) }
        catch (e: Throwable) { if (placeId == null) File(placesDir, pid).deleteRecursively() else walkDir(pid, input.id).deleteRecursively(); throw e }
    }

    private fun saveInto(input: WalkInput, placeId: String?, pid: String, now: Long): SaveResult {
        val dir = File(placesDir, pid); val wdir = walkDir(pid, input.id).apply { mkdirs() }
        // 1. Camera clock -> sensor clock
        val clock = input.clock?.takeIf { it.ready }
        val convert: (Long) -> Long? = { t -> if (clock == null) t else clock.toElapsed(t) }
        val track = input.track.withTimes(convert)
        val pieces = input.pieces.mapNotNull { c -> val a = convert(c.t0Ns); val b = convert(c.t1Ns); if (a == null || b == null) null else WalkChunk(c.map, a, b) }
        // 2. Where on Earth
        val firstFix = input.fixes.firstOrNull { it.isUsable() }
        val geo = firstFix?.let { GeoFit.fit(track, input.fixes, GeoAnchor(it.latitudeDeg, it.longitudeDeg, it.altitudeMeters)) }
        // 3. Files: track, pieces, sensor log
        GZIPOutputStream(File(wdir, "track.bin.gz").outputStream()).use { track.write(it) }
        pieces.forEachIndexed { i, c -> GZIPOutputStream(File(wdir, pieceName(i)).outputStream()).use { c.map.write(it) } }
        File(wdir, "fixes.json").writeText(fixesJson(input.fixes))
        input.rawLog?.takeIf { it.exists() }?.let { if (!it.renameTo(File(wdir, "sensors.jsonl.gz"))) { it.copyTo(File(wdir, "sensors.jsonl.gz"), true); it.delete() } }
        val cubes = pieces.sumOf { it.map.solid }
        val (ex, ez) = track.extent()
        var meta = WalkMeta(input.id, input.name.trim().take(40).ifBlank { "Walk" }, input.startedAt, input.endedAt, track.lengthM, track.durationS, track.trackedShare, track.longestLossS, track.jumps, track.speedP95,
            ex, ez, cubes, input.keyFrames, input.diag, geo, input.fixes.size, input.recorder?.medianAccuracyM,
            clock?.let { ClockInfo(it.sameClock, it.jitterNs / 1e6, it.samples, it.driftMsPerHour) },
            input.recorder?.bytes ?: 0L, input.recorder?.truncated ?: false, input.recorder?.sensorCounts ?: emptyMap(), pieces.map { longArrayOf(it.t0Ns, it.t1Ns) }, null, null,
            input.thermalMax, input.batteryStart, input.batteryEnd)
        var merge: MergeOutcome? = null
        val existing = if (placeId != null) place(pid) else null
        if (existing == null) {
            // A new place: its map is the union of this walk's pieces.
            val union = RoomMap(0.10f); for (c in pieces) union.absorb(c.map)
            writeMap(File(dir, "base.bin.gz"), union)
            writeWalk(wdir, meta)
            writePlace(dir, pid, input.name.trim().take(40).ifBlank { "Place" }, now, now, input.id, emptyList())
        } else {
            val base = baseMap(pid) ?: RoomMap(0.10f)
            val baseMeta = existing.walks.firstOrNull { it.id == existing.baseWalkId }
            val prevSnapshot = File(dir, "base.bin.gz").takeIf { it.exists() }
            merge = WalkFusion.merge(base, pieces, geo, existing.bestGeo ?: baseMeta?.geo)
            if (merge.accepted) {
                prevSnapshot?.copyTo(File(dir, "base.prev.gz"), true)
                writeMap(File(dir, "base.bin.gz"), base)
                meta = meta.copy(merge = toRecord(merge, now))
            } else meta = meta.copy(unmergedReason = merge.reason)
            writeWalk(wdir, meta)
            writePlace(dir, pid, existing.name, existing.createdAt, now, existing.baseWalkId, if (merge.accepted) existing.mergeOrder + input.id else existing.mergeOrder)
        }
        refreshPlaceGeo(pid)
        return SaveResult(place(pid)!!, meta, merge)
    }

    /** Tries again to add a walk that was kept apart (for instance after more of the place has been walked). */
    fun retryMerge(placeId: String, walkId: String, now: Long = System.currentTimeMillis()): MergeOutcome? {
        val p = place(placeId) ?: return null; val w = p.walks.firstOrNull { it.id == walkId } ?: return null
        if (w.merge != null || walkId == p.baseWalkId) return null
        val base = baseMap(placeId) ?: return null; val dir = File(placesDir, placeId)
        val outcome = WalkFusion.merge(base, pieces(placeId, walkId), w.geo, p.bestGeo)
        if (outcome.accepted) {
            File(dir, "base.bin.gz").takeIf { it.exists() }?.copyTo(File(dir, "base.prev.gz"), true)
            writeMap(File(dir, "base.bin.gz"), base)
            writeWalk(walkDir(placeId, walkId), w.copy(merge = toRecord(outcome, now), unmergedReason = null))
            writePlace(dir, placeId, p.name, p.createdAt, now, p.baseWalkId, p.mergeOrder + walkId)
        } else writeWalk(walkDir(placeId, walkId), w.copy(unmergedReason = outcome.reason))
        refreshPlaceGeo(placeId)
        return outcome
    }

    fun canUndo(placeId: String): Boolean = File(File(placesDir, placeId), "base.prev.gz").exists() && (place(placeId)?.mergeOrder?.isNotEmpty() == true)
    /** Puts the place's map back as it was before its last merge; that walk stays stored, as a separate walk. */
    fun undoLastMerge(placeId: String, now: Long = System.currentTimeMillis()): Boolean {
        val p = place(placeId) ?: return false; val last = p.mergeOrder.lastOrNull() ?: return false
        val dir = File(placesDir, placeId); val prev = File(dir, "base.prev.gz"); if (!prev.exists()) return false
        prev.copyTo(File(dir, "base.bin.gz"), true); prev.delete()
        p.walks.firstOrNull { it.id == last }?.let { writeWalk(walkDir(placeId, last), it.copy(merge = null, unmergedReason = "merge undone by you")) }
        writePlace(dir, placeId, p.name, p.createdAt, now, p.baseWalkId, p.mergeOrder.dropLast(1))
        refreshPlaceGeo(placeId)
        return true
    }

    fun renamePlace(placeId: String, name: String) { val p = place(placeId) ?: return; writePlace(File(placesDir, placeId), placeId, name.trim().take(40).ifBlank { p.name }, p.createdAt, p.updatedAt, p.baseWalkId, p.mergeOrder) }
    fun deletePlace(placeId: String) { File(placesDir, placeId).deleteRecursively() }

    /** The GPS fixes of a walk (kept apart from the big sensor log, so they can be read at once). */
    fun fixes(placeId: String, walkId: String): List<GeoFix> = runCatching {
        val a = JSONArray(File(walkDir(placeId, walkId), "fixes.json").readText())
        List(a.length()) { i -> a.getJSONArray(i).let { GeoFix(it.getDouble(0), it.getDouble(1), if (it.isNull(4)) null else it.getDouble(4), it.getDouble(2).toFloat(), it.getLong(3)) } }
    }.getOrDefault(emptyList())
    private fun fixesJson(f: List<GeoFix>) = JSONArray().also { a -> f.forEach { a.put(JSONArray().put(it.latitudeDeg).put(it.longitudeDeg).put(it.horizontalAccuracyMeters.toDouble()).put(it.elapsedRealtimeNanos).put(it.altitudeMeters ?: JSONObject.NULL)) } }.toString()

    /** Refits where the place is on the Earth from the GPS of every walk in it that was added (and the first). Called whenever the set of merged walks changes. */
    internal fun refreshPlaceGeo(placeId: String) {
        val p = place(placeId) ?: return; val dir = File(placesDir, placeId)
        val inputs = ArrayList<PlaceGeoInput>()
        for (id in listOf(p.baseWalkId) + p.mergeOrder) {
            val w = p.walks.firstOrNull { it.id == id } ?: continue
            val t = track(placeId, id) ?: continue
            inputs += PlaceGeoInput(t, fixes(placeId, id), if (id == p.baseWalkId) null else w.merge)
        }
        val base = p.walks.firstOrNull { it.id == p.baseWalkId }?.geo?.anchor
        val g = PlaceGeo.fit(inputs, base)
        val file = File(dir, "place.json"); val j = JSONObject(file.readText())
        if (g == null) { j.remove("geo"); j.remove("geoWalks") } else { j.put("geo", geoJson(g)); j.put("geoWalks", inputs.count { it.fixes.isNotEmpty() }) }
        file.writeText(j.toString())
    }

    // ---- JSON
    private fun toRecord(o: MergeOutcome, at: Long) = MergeRecord(at, o.global!!.alignment.copy(dy = o.dy ?: 0f), o.dy ?: 0f, o.global.meanDist, o.global.inlier, o.global.margin, o.prior,
        o.chunks.map { PieceRecord(it.index, it.t0Ns, it.t1Ns, it.alignment, it.how, it.meanDist, it.inlier, it.report?.added ?: 0, it.report?.reinforced ?: 0, it.report?.removed ?: 0) })

    private fun writeMap(f: File, m: RoomMap) { val tmp = File(f.parentFile, f.name + ".tmp"); GZIPOutputStream(tmp.outputStream()).use { m.write(it) }; if (!tmp.renameTo(f)) { tmp.copyTo(f, true); tmp.delete() } }
    private fun writePlace(dir: File, id: String, name: String, created: Long, updated: Long, base: String, order: List<String>) {
        dir.mkdirs(); val file = File(dir, "place.json")
        val keep = runCatching { JSONObject(file.readText()) }.getOrNull()      // the combined placement is refitted separately; rewriting the place must not lose it
        val j = JSONObject().put("v", 1).put("id", id).put("name", name).put("created", created).put("updated", updated).put("base", base).put("order", JSONArray(order))
        keep?.optJSONObject("geo")?.let { j.put("geo", it); j.put("geoWalks", keep.optInt("geoWalks", 0)) }
        file.writeText(j.toString())
    }
    private fun writeWalk(dir: File, m: WalkMeta) { dir.mkdirs(); File(dir, "walk.json").writeText(walkJson(m).toString()) }

    private fun loadPlace(dir: File): Place {
        val j = JSONObject(File(dir, "place.json").readText())
        val walks = File(dir, "walks").listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { w -> runCatching { walkFrom(JSONObject(File(w, "walk.json").readText())) }.getOrNull() }.sortedBy { it.startedAt }
        val order = j.optJSONArray("order") ?: JSONArray()
        return Place(j.getString("id"), j.getString("name"), j.getLong("created"), j.getLong("updated"), j.getString("base"), walks, List(order.length()) { order.getString(it) }, j.optJSONObject("geo")?.let(::geoFrom), j.optInt("geoWalks", 0))
    }

    companion object {
        private fun al(a: Alignment) = JSONArray().put(a.yawDeg.toDouble()).put(a.dx.toDouble()).put(a.dy.toDouble()).put(a.dz.toDouble())
        private fun alFrom(a: JSONArray) = Alignment(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getDouble(3).toFloat())
        private fun dbl(v: Double?): Any = if (v == null || !v.isFinite()) JSONObject.NULL else v

        fun geoJson(g: GeoRegistration) = JSONObject().put("lat", g.anchor.latitudeDeg).put("lon", g.anchor.longitudeDeg).put("alt", g.anchor.altitudeMeters ?: JSONObject.NULL)
            .put("theta", g.thetaRad).put("tx", g.tx).put("ty", g.ty).put("yawSigma", g.yawSigmaDeg).put("posSigma", dbl(g.posSigmaM)).put("rms", dbl(g.rmsM)).put("max", dbl(g.maxResidualM))
            .put("used", g.used).put("total", g.total).put("scale", g.scale).put("chi", dbl(g.chi)).put("level", g.level.name).put("reason", g.reason)
        fun geoFrom(j: JSONObject) = GeoRegistration(GeoAnchor(j.getDouble("lat"), j.getDouble("lon"), if (j.isNull("alt")) null else j.getDouble("alt")), j.getDouble("theta"), j.getDouble("tx"), j.getDouble("ty"), j.getDouble("yawSigma"),
            j.optDouble("posSigma", Double.NaN), j.optDouble("rms", Double.NaN), j.optDouble("max", Double.NaN), j.getInt("used"), j.getInt("total"), j.getDouble("scale"), j.optDouble("chi", Double.NaN),
            runCatching { GeoLevel.valueOf(j.getString("level")) }.getOrDefault(GeoLevel.NONE), j.optString("reason"))

        fun walkJson(m: WalkMeta): JSONObject {
            val o = JSONObject().put("v", 1).put("id", m.id).put("name", m.name).put("start", m.startedAt).put("end", m.endedAt).put("length", m.lengthM).put("duration", m.durationS).put("tracked", m.trackedShare)
                .put("loss", m.longestLossS).put("jumps", m.jumps).put("speedP95", m.speedP95).put("ex", m.extentX).put("ez", m.extentZ).put("cubes", m.cubes).put("keyFrames", m.keyFrames).put("diag", m.diag)
                .put("fixes", m.fixes).put("gpsMed", dbl(m.gpsMedianAccM)).put("logBytes", m.logBytes).put("logTruncated", m.logTruncated)
                .put("thermal", m.thermalMax).put("battery0", m.batteryStart).put("battery1", m.batteryEnd)
                .put("sensors", JSONObject().also { s -> m.sensorCounts.forEach { (k, v) -> s.put(k.toString(), v) } })
                .put("pieces", JSONArray().also { a -> m.pieces.forEach { a.put(JSONArray().put(it[0]).put(it[1])) } })
            m.geo?.let { o.put("geo", geoJson(it)) }
            m.clock?.let { o.put("clock", JSONObject().put("same", it.sameClock ?: JSONObject.NULL).put("jitterMs", it.jitterMs).put("samples", it.samples).put("drift", dbl(it.driftMsPerHour))) }
            m.unmergedReason?.let { o.put("unmerged", it) }
            m.merge?.let { r -> o.put("merge", JSONObject().put("at", r.at).put("global", al(r.global)).put("dy", r.dy.toDouble()).put("mean", r.globalMeanDist.toDouble()).put("inlier", r.globalInlier.toDouble()).put("margin", r.margin.toDouble()).put("prior", r.prior)
                .put("pieces", JSONArray().also { a -> r.pieces.forEach { p -> a.put(JSONObject().put("i", p.index).put("t0", p.t0Ns).put("t1", p.t1Ns).put("how", p.how).put("mean", dbl(p.meanDist.toDouble())).put("inlier", dbl(p.inlier.toDouble())).put("add", p.added).put("rein", p.reinforced).put("rem", p.removed).also { x -> p.alignment?.let { x.put("a", al(it)) } }) } })) }
            return o
        }

        fun walkFrom(j: JSONObject): WalkMeta {
            val pcs = j.getJSONArray("pieces"); val sc = j.optJSONObject("sensors")
            val clock = j.optJSONObject("clock")?.let { ClockInfo(if (it.isNull("same")) null else it.getBoolean("same"), it.getDouble("jitterMs"), it.getLong("samples"), it.optDouble("drift").takeIf { d -> !d.isNaN() }) }
            val merge = j.optJSONObject("merge")?.let { r ->
                val ps = r.getJSONArray("pieces")
                MergeRecord(r.getLong("at"), alFrom(r.getJSONArray("global")), r.getDouble("dy").toFloat(), r.getDouble("mean").toFloat(), r.getDouble("inlier").toFloat(), r.getDouble("margin").toFloat(), r.optString("prior"),
                    List(ps.length()) { i -> ps.getJSONObject(i).let { p -> PieceRecord(p.getInt("i"), p.getLong("t0"), p.getLong("t1"), p.optJSONArray("a")?.let(::alFrom), p.getString("how"), p.optDouble("mean", Double.NaN).toFloat(), p.optDouble("inlier", Double.NaN).toFloat(), p.getInt("add"), p.getInt("rein"), p.getInt("rem")) } })
            }
            return WalkMeta(j.getString("id"), j.getString("name"), j.getLong("start"), j.getLong("end"), j.getDouble("length"), j.getDouble("duration"), j.getDouble("tracked"), j.getDouble("loss"), j.getInt("jumps"), j.getDouble("speedP95"),
                j.getDouble("ex"), j.getDouble("ez"), j.getInt("cubes"), j.getInt("keyFrames"), j.optString("diag"), j.optJSONObject("geo")?.let(::geoFrom), j.getInt("fixes"), j.optDouble("gpsMed", Double.NaN).takeIf { !it.isNaN() }, clock,
                j.optLong("logBytes"), j.optBoolean("logTruncated"), sc?.keys()?.asSequence()?.associate { it.toInt() to sc.getInt(it) } ?: emptyMap(), List(pcs.length()) { i -> pcs.getJSONArray(i).let { longArrayOf(it.getLong(0), it.getLong(1)) } },
                merge, j.optString("unmerged").ifBlank { null }, j.optInt("thermal", -1), j.optInt("battery0", -1), j.optInt("battery1", -1))
        }
    }
}
