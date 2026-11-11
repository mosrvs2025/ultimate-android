package app.ultimate.room

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** A saved room: its name, when it was scanned, what was measured and what was found in it. The shape itself is in a file beside it. */
data class SavedRoom(val id: String, val name: String, val at: Long, val stats: RoomStats, val objects: List<RoomObject>)

/**
 * Rooms kept on this phone, in the app's private storage (other apps cannot read it, and nothing is uploaded). One folder per
 * room: a small description and the compressed shape. Deleting a room deletes both.
 */
class RoomStore(private val dir: File) {
    init { dir.mkdirs() }

    fun list(): List<SavedRoom> = dir.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { d -> runCatching { meta(File(d, "room.json").readText()) }.getOrNull() }.sortedByDescending { it.at }

    fun save(name: String, map: RoomMap, objects: List<RoomObject>, id: String = UUID.randomUUID().toString(), at: Long = System.currentTimeMillis()): SavedRoom? {
        val stats = map.stats() ?: return null
        val room = SavedRoom(id, name.trim().take(40).ifBlank { "Room" }, at, stats, objects)
        val d = File(dir, id).apply { mkdirs() }
        GZIPOutputStream(File(d, "map.bin.gz").outputStream()).use { map.write(it) }
        File(d, "room.json").writeText(json(room))
        return room
    }

    fun map(id: String): RoomMap? = runCatching { GZIPInputStream(File(File(dir, id), "map.bin.gz").inputStream()).use { RoomMap.read(it) } }.getOrNull()
    fun rename(id: String, name: String) { list().firstOrNull { it.id == id }?.let { File(File(dir, id), "room.json").writeText(json(it.copy(name = name.trim().take(40).ifBlank { it.name }))) } }
    fun delete(id: String) { File(dir, id).deleteRecursively() }
    fun bytes(id: String): Long = File(dir, id).listFiles().orEmpty().sumOf { it.length() }

    private fun json(r: SavedRoom) = JSONObject().put("id", r.id).put("name", r.name).put("at", r.at)
        .put("stats", JSONObject().put("floor", r.stats.floorY.toDouble()).put("w", r.stats.width.toDouble()).put("l", r.stats.length.toDouble()).put("area", r.stats.floorArea.toDouble()).put("voxels", r.stats.voxels).also { o -> r.stats.ceilingY?.let { o.put("ceil", it.toDouble()) } })
        .put("objects", JSONArray().also { a -> r.objects.forEach { o -> a.put(JSONObject().put("label", o.label).put("x", o.at.x.toDouble()).put("y", o.at.y.toDouble()).put("z", o.at.z.toDouble()).put("seen", o.seen).put("best", o.best.toDouble())) } }).toString()

    private fun meta(text: String): SavedRoom {
        val j = JSONObject(text); val s = j.getJSONObject("stats"); val a = j.getJSONArray("objects")
        return SavedRoom(j.getString("id"), j.getString("name"), j.getLong("at"),
            RoomStats(s.getDouble("floor").toFloat(), if (s.has("ceil")) s.getDouble("ceil").toFloat() else null, s.getDouble("w").toFloat(), s.getDouble("l").toFloat(), s.getDouble("area").toFloat(), s.getInt("voxels")),
            List(a.length()) { i -> a.getJSONObject(i).let { o -> RoomObject(o.getString("label"), P3(o.getDouble("x").toFloat(), o.getDouble("y").toFloat(), o.getDouble("z").toFloat()), o.getInt("seen"), o.getDouble("best").toFloat()) } })
    }
}
