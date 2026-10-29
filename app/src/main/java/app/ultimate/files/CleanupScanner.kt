package app.ultimate.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Walks shared storage once and turns what it finds into cleanup suggestions. */
class CleanupScanner(private val context: Context) {
    data class Overview(val byKind: Map<FileKind, Long>, val total: Long, val free: Long, val suggestions: List<Suggestion>, val photos: List<Item>)

    private val root = Environment.getExternalStorageDirectory()

    fun scan(progress: (String) -> Unit): Overview {
        progress("Looking through your storage…")
        val items = ArrayList<Item>()
        val emptyDirs = ArrayList<File>()
        root.walkTopDown()
            .onEnter { d -> d == root || (!d.name.startsWith(".") && d.absolutePath != File(root, "Android/data").absolutePath && d.absolutePath != File(root, "Android/obb").absolutePath) }
            .onFail { _, _ -> }
            .forEach { f ->
                if (items.size > 250_000) return@forEach
                if (f.isFile) items += Item(f, f.length(), f.lastModified(), FileModel.kindOf(f))
                else if (f != root && f.list()?.isEmpty() == true) emptyDirs += f
            }
        val byKind = items.groupBy { it.kind }.mapValues { (_, v) -> v.sumOf { it.bytes } }
        val now = System.currentTimeMillis()
        val out = mutableListOf<Suggestion>()

        progress("Finding duplicates…")
        val dupes = CleanupRules.duplicates(items) { fingerprint(it.file) }
        if (dupes.isNotEmpty()) out += Suggestion("dupes", "Duplicate files", "Exact copies; the original stays", emptyList(), dupes)

        val photos = items.filter { it.kind == FileKind.Image && ("/DCIM/" in it.file.path || "/Pictures/" in it.file.path) && !CleanupRules.isScreenshot(it.file.path) }
        progress("Comparing similar photos…")
        val recent = photos.sortedByDescending { it.modified }.take(3000)
        val hashed = recent.mapNotNull { p -> look(p.file)?.let { (hash, sharp) -> Triple(p, hash, sharp) } }
        val similar = CleanupRules.similar(hashed)
        if (similar.isNotEmpty()) out += Suggestion("similar", "Similar shots", "Bursts and near-repeats; the sharpest stays", emptyList(), similar)

        items.filter { CleanupRules.isScreenshot(it.file.path) && now - it.modified > 30 * CleanupRules.DAY }.takeIf { it.isNotEmpty() }
            ?.let { out += Suggestion("screenshots", "Old screenshots", "Older than 30 days", it.sortedByDescending { i -> i.bytes }) }
        items.filter { it.kind == FileKind.Video && it.bytes > 150_000_000 }.takeIf { it.isNotEmpty() }
            ?.let { out += Suggestion("bigvideos", "Big videos", "Over 150 MB; Squeeze can shrink them instead", it.sortedByDescending { i -> i.bytes }, squeezable = true) }
        items.filter { it.kind == FileKind.Apk }.takeIf { it.isNotEmpty() }
            ?.let { out += Suggestion("apks", "Installer files", "Apps you've downloaded; installed apps don't need them", it) }
        items.filter { CleanupRules.isMessengerSent(it.file.path) }.takeIf { it.isNotEmpty() }
            ?.let { out += Suggestion("sent", "Copies of media you sent", "WhatsApp keeps a copy of everything you send", it) }
        items.filter { CleanupRules.isDownload(it.file.path) && now - it.modified > 90 * CleanupRules.DAY }.takeIf { it.isNotEmpty() }
            ?.let { out += Suggestion("downloads", "Old downloads", "Untouched for 3 months", it.sortedByDescending { i -> i.bytes }) }
        if (emptyDirs.isNotEmpty()) out += Suggestion("empty", "Empty folders", "Nothing inside", emptyDirs.map { Item(it, 0, it.lastModified(), FileKind.Folder) })

        val stat = android.os.StatFs(root.path)
        return Overview(byKind, stat.totalBytes, stat.availableBytes, out.sortedByDescending { it.bytes }, photos.sortedByDescending { it.modified })
    }

    /** First and last 64 KB plus the size: enough to tell different files apart, then confirmed by a full hash. */
    private fun fingerprint(f: File): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(f, "r").use { raf ->
            val buf = ByteArray(65_536)
            val n = raf.read(buf); if (n > 0) md.update(buf, 0, n)
            if (raf.length() > buf.size * 2) { raf.seek(raf.length() - buf.size); val m = raf.read(buf); if (m > 0) md.update(buf, 0, m) }
        }
        val quick = md.digest().joinToString("") { "%02x".format(it) }
        // Same quick print is rare for different files, but deleting needs certainty, so confirm with every byte.
        quick + ":" + full(f)
    }.getOrNull()

    private val fullCache = HashMap<String, String>()
    private fun full(f: File): String = fullCache.getOrPut(f.path) {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input -> val buf = ByteArray(256 * 1024); while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    /** A photo's look (difference hash) and sharpness, from a small decode. */
    private fun look(f: File): Pair<Long, Double>? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 128 && bounds.outHeight / (sample * 2) >= 128) sample *= 2
        val small = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val tiny = Bitmap.createScaledBitmap(small, 9, 8, true)
        val grid = Bitmap.createScaledBitmap(small, 64, 64, true)
        fun gray(b: Bitmap): IntArray { val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height); return IntArray(px.size) { val c = px[it]; ((c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114) / 1000 } }
        val result = CleanupRules.dHash(gray(tiny)) to CleanupRules.sharpness(gray(grid), 64, 64)
        small.recycle(); tiny.recycle(); grid.recycle()
        result
    }.getOrNull()
}
