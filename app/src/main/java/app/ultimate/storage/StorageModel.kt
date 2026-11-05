package app.ultimate.storage

import app.ultimate.files.FileKind

/** A folder with the total size of everything inside it, and its biggest subfolders. */
class Folder(val name: String, val path: String) {
    var bytes = 0L
    var files = 0
    val children = HashMap<String, Folder>()
    /** Bytes held directly in this folder (not in subfolders), and how many files that is. */
    var own = 0L
    var ownFiles = 0
    /** The biggest files held directly in this folder (a bounded few), so the map can show what is inside. */
    private val largest = java.util.PriorityQueue<BigFile>(compareBy { it.bytes })
    fun addFile(f: BigFile) { largest.add(f); if (largest.size > KEEP_FILES) largest.poll() }
    fun filesBySize(): List<BigFile> = largest.sortedByDescending { it.bytes }
    fun sortedChildren(): List<Folder> = children.values.sortedByDescending { it.bytes }
    private companion object { const val KEEP_FILES = 40 }
}

data class BigFile(val path: String, val name: String, val bytes: Long, val modified: Long, val kind: FileKind)

/** One tile of a folder's map: a subfolder, a file, or one tile standing for [hidden] smaller things. */
data class MapItem(val key: String, val label: String, val bytes: Long, val folder: Folder? = null, val file: BigFile? = null, val hidden: Int = 0)

object MapItems {
    const val REST = "__rest"

    /**
     * What to draw for [folder]: its subfolders and its own biggest files, biggest first, at most [cap] tiles. Whatever does
     * not get a tile of its own (small files, files past the ones kept, folders past the cap) is one last tile, so the tiles
     * always add up to the folder's size.
     */
    fun of(folder: Folder, cap: Int = 28): List<MapItem> {
        val all = (folder.children.values.map { MapItem(it.path, it.name, it.bytes, folder = it) } + folder.filesBySize().map { MapItem(it.path, it.name, it.bytes, file = it) })
            .filter { it.bytes > 0 }.sortedByDescending { it.bytes }
        val shown = if (all.size > cap) all.take(cap - 1) else all
        val rest = folder.bytes - shown.sumOf { it.bytes }
        val hidden = folder.children.size + folder.ownFiles - shown.size
        return if (rest > 0 && hidden > 0) shown + MapItem(REST, if (hidden == 1) "1 smaller item" else "$hidden smaller items", rest, hidden = hidden) else shown
    }
}

/** One rectangle of the storage map, in 0..1 coordinates of the map area. */
data class Tile(val key: String, val label: String, val bytes: Long, val x: Float, val y: Float, val w: Float, val h: Float)

/**
 * Squarified treemap (Bruls, Huizing and van Wijk): lays out areas proportional to sizes, keeping tiles close to square so
 * they are easy to read and tap. Pure, so the layout is tested.
 */
object Treemap {
    fun layout(items: List<Triple<String, String, Long>>, width: Float = 1f, height: Float = 1f): List<Tile> {
        val sized = items.filter { it.third > 0 }.sortedByDescending { it.third }
        val total = sized.sumOf { it.third }.toDouble()
        if (total <= 0) return emptyList()
        val area = width * height
        val values = sized.map { it.third / total * area }
        val out = ArrayList<Tile>()
        var x = 0f; var y = 0f; var w = width; var h = height
        var i = 0
        while (i < values.size) {
            val side = minOf(w, h)
            val row = ArrayList<Int>()
            var best = Double.MAX_VALUE
            while (i + row.size < values.size) {
                val trial = row + (i + row.size)
                val ratio = worst(trial.map { values[it] }, side.toDouble())
                if (row.isNotEmpty() && ratio > best) break
                row.add(i + row.size); best = ratio
            }
            val sum = row.sumOf { values[it] }.toFloat()
            if (w >= h) {
                val colW = sum / h
                var yy = y
                row.forEach { k -> val th = (values[k] / colW).toFloat(); out += Tile(sized[k].first, sized[k].second, sized[k].third, x, yy, colW, th); yy += th }
                x += colW; w -= colW
            } else {
                val rowH = sum / w
                var xx = x
                row.forEach { k -> val tw = (values[k] / rowH).toFloat(); out += Tile(sized[k].first, sized[k].second, sized[k].third, xx, y, tw, rowH); xx += tw }
                y += rowH; h -= rowH
            }
            i += row.size
        }
        return out
    }

    /** The worst aspect ratio in a row of [areas] laid along a side of length [side]. */
    private fun worst(areas: List<Double>, side: Double): Double {
        val s = areas.sum(); val max = areas.max(); val min = areas.min()
        val s2 = s * s; val w2 = side * side
        return maxOf(w2 * max / s2, s2 / (w2 * min))
    }
}

/** Storage growth from daily records of used bytes. */
object Trend {
    /** Used bytes now minus about [days] ago, or null without a record that old. */
    fun change(records: List<Pair<Long, Long>>, now: Long, days: Int = 7): Long? {
        val target = now - days * 86_400_000L
        val past = records.filter { it.first <= target + 43_200_000L }.maxByOrNull { it.first } ?: return null
        val latest = records.maxByOrNull { it.first } ?: return null
        return latest.second - past.second
    }

    /** Keeps one record per day, the newest of that day, for the last [keep] days. */
    fun add(records: List<Pair<Long, Long>>, at: Long, used: Long, keep: Int = 120): List<Pair<Long, Long>> {
        val day = at / 86_400_000L
        return (records.filter { it.first / 86_400_000L != day } + (at to used)).sortedBy { it.first }.takeLast(keep)
    }
}
