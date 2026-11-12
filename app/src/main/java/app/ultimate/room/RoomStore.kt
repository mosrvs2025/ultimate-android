package app.ultimate.room

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** One scan that went into a saved space: when, how well it lined up, and what it changed. The first scan has score 1 and only "added". */
data class ScanSession(val at: Long, val added: Int, val reinforced: Int, val removed: Int, val score: Float, val rmsCm: Float, val voxels: Int)

/**
 * A saved space: a room, or anything else with a shape worth remembering ([kind]: room, vehicle, shelving, other). [place] groups spaces
 * that belong together (the rooms of one home or building). [sessions] is the scan history: every scan that was folded in.
 */
data class SavedRoom(
    val id: String, val name: String, val at: Long, val stats: RoomStats, val objects: List<RoomObject>,
    val kind: String = "room", val place: String = "", val sessions: List<ScanSession> = emptyList(), val updatedAt: Long = at,
    /** A room height measured in an earlier scan (or marked by you), kept so a later scan that cannot see the ceiling still has it. */
    val heightM: Float? = null,
)

/**
 * Spaces kept on this phone, in the app's private storage (other apps cannot read it, and nothing is uploaded). One folder per space:
 * a small description, the compressed shape, and the shape as it was before the last update (so one update can be undone).
 */
class RoomStore(private val dir: File) {
    init { dir.mkdirs() }

    fun list(): List<SavedRoom> = dir.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { d -> runCatching { load(d) }.getOrNull() }.sortedByDescending { it.updatedAt }

    /** Rooms saved by the first release only stored the old, unreliable measurements: measure those again from their shape, once. */
    private fun load(d: File): SavedRoom {
        val j = JSONObject(File(d, "room.json").readText())
        if (j.optInt("v", 1) >= 2) return meta(j)
        val map = map(d.name) ?: error("no map")
        val stats = RoomGeometry.measure(map.cleaned()) ?: RoomStats(null, null, 0f, null, null, 0f, map.solid, Confidence.LOW, listOf("Scanned with an older version: scan again to measure it."))
        val a = j.getJSONArray("objects"); val at = j.getLong("at")
        val room = SavedRoom(j.getString("id"), j.getString("name"), at, stats,
            List(a.length()) { i -> a.getJSONObject(i).let { o -> RoomObject(o.getString("label"), P3(o.getDouble("x").toFloat(), o.getDouble("y").toFloat(), o.getDouble("z").toFloat()), o.getInt("seen"), o.getDouble("best").toFloat(), UUID.randomUUID().toString(), at, at) } },
            sessions = listOf(ScanSession(at, map.solid, 0, 0, 1f, 0f, map.solid)))
        File(d, "room.json").writeText(json(room))
        return room
    }

    /** Saves a space. Null when there is too little to measure anything. Updating an existing [id] keeps the previous shape for [undo]. */
    fun save(name: String, map: RoomMap, objects: List<RoomObject>, stats: RoomStats? = null, id: String = UUID.randomUUID().toString(), at: Long = System.currentTimeMillis(),
             kind: String = "room", place: String = "", sessions: List<ScanSession>? = null, updatedAt: Long = System.currentTimeMillis(),
             heightM: Float? = null, snapshot: Boolean = true, previousStats: RoomStats? = null): SavedRoom? {
        val s0 = (stats ?: RoomGeometry.measure(map.cleaned()) ?: return null).let { RoomGeometry.keepBest(it, previousStats) }
        val height = s0.trustedHeight ?: heightM
        val s = RoomGeometry.holdHeight(s0, height)
        val solid = map.solid
        val room = SavedRoom(id, name.trim().take(40).ifBlank { "Room" }, at, s.copy(voxels = solid), objects.map { if (it.id.isEmpty()) it.copy(id = UUID.randomUUID().toString(), firstAt = at, lastAt = at) else it },
            kind, place.trim().take(40), sessions ?: listOf(ScanSession(at, solid, 0, 0, 1f, 0f, solid)), updatedAt, height)
        val d = File(dir, id).apply { mkdirs() }
        val cur = File(d, "map.bin.gz"); val curMeta = File(d, "room.json")
        if (snapshot && cur.exists() && curMeta.exists()) { cur.copyTo(File(d, "map.prev.gz"), true); curMeta.copyTo(File(d, "room.prev.json"), true) }
        val tmp = File(d, "map.tmp"); GZIPOutputStream(tmp.outputStream()).use { map.write(it) }
        if (!tmp.renameTo(cur)) { tmp.copyTo(cur, true); tmp.delete() }
        curMeta.writeText(json(room))
        return room
    }

    fun map(id: String): RoomMap? = runCatching { GZIPInputStream(File(File(dir, id), "map.bin.gz").inputStream()).use { RoomMap.read(it) } }.getOrNull()
    fun canUndo(id: String) = File(File(dir, id), "map.prev.gz").exists() && File(File(dir, id), "room.prev.json").exists()
    /** Puts the space back as it was before its last update. */
    fun undo(id: String): Boolean {
        val d = File(dir, id); val pm = File(d, "map.prev.gz"); val pj = File(d, "room.prev.json"); if (!pm.exists() || !pj.exists()) return false
        pm.copyTo(File(d, "map.bin.gz"), true); pj.copyTo(File(d, "room.json"), true); pm.delete(); pj.delete(); return true
    }
    private fun edit(id: String, f: (SavedRoom) -> SavedRoom) { list().firstOrNull { it.id == id }?.let { File(File(dir, id), "room.json").writeText(json(f(it))) } }
    fun rename(id: String, name: String) = edit(id) { it.copy(name = name.trim().take(40).ifBlank { it.name }) }
    fun describe(id: String, kind: String, place: String) = edit(id) { it.copy(kind = kind, place = place.trim().take(40)) }
    /** Changes the things in a space (relabel or remove one). */
    fun editObjects(id: String, f: (List<RoomObject>) -> List<RoomObject>) = edit(id) { it.copy(objects = f(it.objects)) }
    fun delete(id: String) { File(dir, id).deleteRecursively() }
    fun bytes(id: String): Long = File(dir, id).listFiles().orEmpty().sumOf { it.length() }

    private fun span(s: Span?) = s?.let { JSONObject().put("lo", it.lo.toDouble()).put("hi", it.hi.toDouble()).put("ok", it.complete) }
    private fun json(r: SavedRoom): String {
        val s = JSONObject().put("heading", r.stats.headingDeg.toDouble()).put("seen", r.stats.floorSeen.toDouble()).put("voxels", r.stats.voxels).put("conf", r.stats.confidence.name).put("src", r.stats.floorSource).put("csrc", r.stats.ceilingSource).put("notes", JSONArray(r.stats.notes)).put("walls", JSONArray().also { w -> r.stats.walls.forEach { x -> w.put(JSONArray().put(x.axis).put(x.pos.toDouble()).put(x.from.toDouble()).put(x.to.toDouble())) } })
        r.stats.floorY?.let { s.put("floor", it.toDouble()) }; r.stats.ceilingY?.let { s.put("ceil", it.toDouble()) }; span(r.stats.u)?.let { s.put("u", it) }; span(r.stats.v)?.let { s.put("v", it) }
        return JSONObject().put("v", 2).put("id", r.id).put("name", r.name).put("at", r.at).put("updated", r.updatedAt).put("kind", r.kind).put("place", r.place).put("stats", s).also { o -> r.heightM?.let { o.put("height", it.toDouble()) } }
            .put("objects", JSONArray().also { a -> r.objects.forEach { o -> a.put(JSONObject().put("id", o.id).put("label", o.label).put("x", o.at.x.toDouble()).put("y", o.at.y.toDouble()).put("z", o.at.z.toDouble()).put("seen", o.seen).put("best", o.best.toDouble()).put("first", o.firstAt).put("last", o.lastAt).put("state", o.state).put("missed", o.missed)) } })
            .put("sessions", JSONArray().also { a -> r.sessions.forEach { x -> a.put(JSONObject().put("at", x.at).put("added", x.added).put("reinforced", x.reinforced).put("removed", x.removed).put("score", x.score.toDouble()).put("rms", x.rmsCm.toDouble()).put("voxels", x.voxels)) } }).toString()
    }

    private fun meta(j: JSONObject): SavedRoom {
        val s = j.getJSONObject("stats"); val a = j.getJSONArray("objects"); val ss = j.getJSONArray("sessions"); val n = s.optJSONArray("notes") ?: JSONArray()
        fun span(k: String) = s.optJSONObject(k)?.let { Span(it.getDouble("lo").toFloat(), it.getDouble("hi").toFloat(), it.getBoolean("ok")) }
        return SavedRoom(j.getString("id"), j.getString("name"), j.getLong("at"),
            RoomStats(if (s.has("floor")) s.getDouble("floor").toFloat() else null, if (s.has("ceil")) s.getDouble("ceil").toFloat() else null, s.getDouble("heading").toFloat(), span("u"), span("v"), s.getDouble("seen").toFloat(), s.getInt("voxels"),
                runCatching { Confidence.valueOf(s.getString("conf")) }.getOrDefault(Confidence.LOW), List(n.length()) { n.getString(it) }, s.optString("src"), s.optString("csrc"),
                (s.optJSONArray("walls") ?: JSONArray()).let { w -> List(w.length()) { i -> w.getJSONArray(i).let { x -> WallSeg(x.getInt(0), x.getDouble(1).toFloat(), x.getDouble(2).toFloat(), x.getDouble(3).toFloat()) } } }),
            List(a.length()) { i -> a.getJSONObject(i).let { o -> RoomObject(o.getString("label"), P3(o.getDouble("x").toFloat(), o.getDouble("y").toFloat(), o.getDouble("z").toFloat()), o.getInt("seen"), o.getDouble("best").toFloat(), o.optString("id"), o.optLong("first"), o.optLong("last"), o.optString("state", "here"), o.optInt("missed")) } },
            j.optString("kind", "room"), j.optString("place"),
            List(ss.length()) { i -> ss.getJSONObject(i).let { x -> ScanSession(x.getLong("at"), x.getInt("added"), x.getInt("reinforced"), x.getInt("removed"), x.getDouble("score").toFloat(), x.getDouble("rms").toFloat(), x.getInt("voxels")) } },
            j.optLong("updated", j.getLong("at")), if (j.has("height")) j.getDouble("height").toFloat() else null)
    }
}

/** Folding a new scan into a saved space: the one place that does it, so the screen and the tests use the same steps. */
object Fusion {
    class Result(val map: RoomMap, val objects: List<RoomObject>, val report: FuseReport, val stats: RoomStats?, val heightM: Float?)

    /** [fresh] and [freshObjects] are in the new session's coordinates; [a] takes them to the saved space's. [heightM] is a height kept from before; [freshHeightM] one this scan measured. */
    fun update(saved: RoomMap, savedObjects: List<RoomObject>, fresh: RoomMap, freshObjects: List<RoomObject>, a: Alignment, now: Long, heightM: Float? = null, freshHeightM: Float? = null, previous: RoomStats? = null, clear: Boolean = true, newId: () -> String): Result {
        val moved = fresh.mapped { a.apply(it) }
        val report = saved.fuse(moved, clear = clear)
        val objects = ObjectIdentity.merge(savedObjects, freshObjects.map { it.copy(at = a.apply(it.at)) }, now, looked = { moved.freeAt(it.x, it.y, it.z) >= 4 || moved.isSolidNear(it, 0.3f) }, newId = newId)
        val measured = RoomGeometry.measure(saved.cleaned())
        val height = measured?.trustedHeight ?: freshHeightM ?: heightM
        return Result(saved, objects, report, measured?.let { RoomGeometry.holdHeight(RoomGeometry.keepBest(it, previous), height) }, height)
    }
}
