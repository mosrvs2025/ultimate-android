package app.ultimate.files

import java.io.File

/** A file the cleanup looked at. [width]/[height] are known for photos. */
/** A file found by a scan; [mediaId] is its MediaStore row when it came from the index (0 otherwise), for fast thumbnails. */
data class Item(val file: File, val bytes: Long, val modified: Long, val kind: FileKind, val mediaId: Long = 0)

/** A group of files where all but [keep] could go. */
data class Group(val items: List<Item>, val keep: Item) { val spare: List<Item> get() = items - keep; val spareBytes get() = spare.sumOf { it.bytes } }

/** One cleanup idea with what it would free. */
data class Suggestion(val id: String, val title: String, val detail: String, val items: List<Item>, val groups: List<Group> = emptyList(), val squeezable: Boolean = false) {
    val bytes: Long get() = if (groups.isNotEmpty()) groups.sumOf { it.spareBytes } else items.sumOf { it.bytes }
    val count: Int get() = if (groups.isNotEmpty()) groups.sumOf { it.spare.size } else items.size
}

/** The pure decisions behind Clean up, so what it proposes to delete is tested. */
object CleanupRules {
    const val DAY = 86_400_000L

    /** Which copy of identical files to keep: camera originals over app copies, then the oldest, then the shortest path. */
    fun keeper(copies: List<Item>): Item = copies.minWith(
        compareBy<Item>({ if ("/DCIM/" in it.file.path) 0 else if ("/Pictures/" in it.file.path) 1 else 2 }, { "/WhatsApp/" in it.file.path || "/Telegram/" in it.file.path }, { it.modified }, { it.file.path.length }),
    )

    /** Same size, then same content fingerprint: groups of identical files with more than one member. */
    fun duplicates(items: List<Item>, fingerprint: (Item) -> String?): List<Group> =
        items.filter { it.bytes >= 32_768 }.groupBy { it.bytes }.values.filter { it.size > 1 }
            .flatMap { same -> same.groupBy { fingerprint(it) }.filterKeys { it != null }.values.filter { it.size > 1 } }
            .map { Group(it, keeper(it)) }.sortedByDescending { it.spareBytes }

    /** 64-bit difference hash of a 9×8 grayscale image (row-major, 72 values): which neighbours get brighter. */
    fun dHash(gray9x8: IntArray): Long {
        var h = 0L
        for (y in 0 until 8) for (x in 0 until 8) { h = h shl 1; if (gray9x8[y * 9 + x] < gray9x8[y * 9 + x + 1]) h = h or 1L }
        return h
    }

    fun hamming(a: Long, b: Long) = java.lang.Long.bitCount(a xor b)

    /** How sharp a grayscale image is: variance of its Laplacian. Blurry shots score low. */
    fun sharpness(gray: IntArray, w: Int, h: Int): Double {
        if (w < 3 || h < 3) return 0.0
        var sum = 0.0; var sq = 0.0; var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val l = (4 * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - w] - gray[i + w]).toDouble()
            sum += l; sq += l * l; n++
        }
        val mean = sum / n
        return sq / n - mean * mean
    }

    /**
     * Bursts and near-identical photos: taken within [windowMs] of each other and looking almost the same. In each group
     * the sharpest one stays.
     */
    fun similar(photos: List<Triple<Item, Long, Double>>, windowMs: Long = 10 * 60_000L, maxDistance: Int = 6): List<Group> {
        val sorted = photos.sortedBy { it.first.modified }
        val groups = mutableListOf<MutableList<Triple<Item, Long, Double>>>()
        for (p in sorted) {
            val last = groups.lastOrNull()
            val anchor = last?.last()
            if (anchor != null && p.first.modified - anchor.first.modified <= windowMs && last.any { hamming(it.second, p.second) <= maxDistance }) last += p
            else groups += mutableListOf(p)
        }
        return groups.filter { it.size > 1 }.map { g -> Group(g.map { it.first }, g.maxBy { it.third }.first) }.sortedByDescending { it.spareBytes }
    }

    fun isScreenshot(path: String) = path.contains("/Screenshots/", ignoreCase = true) || path.contains("/Screenshot", ignoreCase = true)
    fun isMessengerSent(path: String) = Regex("/(WhatsApp|WhatsApp Business)/Media/.+/Sent/", RegexOption.IGNORE_CASE).containsMatchIn(path)
    fun isDownload(path: String) = path.contains("/Download/", ignoreCase = true)

    /** Calendar month key like "2026-10" for grouping photos to swipe through. */
    fun month(at: Long): String = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).format(java.util.Date(at))
}
