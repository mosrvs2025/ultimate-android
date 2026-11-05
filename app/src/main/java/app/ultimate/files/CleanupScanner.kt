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

    /**
     * Finds everything, then hands over a first overview at once (sizes, photos to swipe, simple suggestions) through
     * [partial], and finishes the slow parts (exact duplicates, similar shots) with progress before returning the full one.
     */
    fun scan(progress: (String) -> Unit, partial: (Overview) -> Unit = {}): Overview {
        progress("Reading your storage…")
        val emptyDirs = ArrayList<File>()
        val items = MediaIndex.load(context) { n -> progress("Reading your storage… $n files") } ?: walk(emptyDirs, progress)
        val byKind = items.groupBy { it.kind }.mapValues { (_, v) -> v.sumOf { it.bytes } }
        val now = System.currentTimeMillis()
        val quick = mutableListOf<Suggestion>()
        items.filter { CleanupRules.isScreenshot(it.file.path) && now - it.modified > 30 * CleanupRules.DAY }.takeIf { it.isNotEmpty() }
            ?.let { quick += Suggestion("screenshots", "Old screenshots", "Older than 30 days", it.sortedByDescending { i -> i.bytes }) }
        items.filter { it.kind == FileKind.Video && it.bytes > 150_000_000 }.takeIf { it.isNotEmpty() }
            ?.let { quick += Suggestion("bigvideos", "Big videos", "Over 150 MB; Squeeze can shrink them instead", it.sortedByDescending { i -> i.bytes }, squeezable = true) }
        items.filter { it.kind == FileKind.Apk }.takeIf { it.isNotEmpty() }
            ?.let { quick += Suggestion("apks", "Installer files", "Apps you've downloaded; installed apps don't need them", it) }
        items.filter { CleanupRules.isMessengerSent(it.file.path) }.takeIf { it.isNotEmpty() }
            ?.let { quick += Suggestion("sent", "Copies of media you sent", "WhatsApp keeps a copy of everything you send", it) }
        items.filter { CleanupRules.isDownload(it.file.path) && now - it.modified > 90 * CleanupRules.DAY }.takeIf { it.isNotEmpty() }
            ?.let { quick += Suggestion("downloads", "Old downloads", "Untouched for 3 months", it.sortedByDescending { i -> i.bytes }) }
        if (emptyDirs.isNotEmpty()) quick += Suggestion("empty", "Empty folders", "Nothing inside", emptyDirs.map { Item(it, 0, it.lastModified(), FileKind.Folder) })
        val photos = items.filter { it.kind == FileKind.Image && ("/DCIM/" in it.file.path || "/Pictures/" in it.file.path) && !CleanupRules.isScreenshot(it.file.path) }
            .sortedByDescending { it.modified }
        val stat = android.os.StatFs(root.path)
        fun overview(extra: List<Suggestion>) = Overview(byKind, stat.totalBytes, stat.availableBytes, (quick + extra).sortedByDescending { it.bytes }, photos)
        partial(overview(emptyList()))

        val slow = mutableListOf<Suggestion>()
        val sameSize = items.filter { it.bytes >= 32_768 }.groupBy { it.bytes }.values.filter { it.size > 1 }.sumOf { it.size }
        var checked = 0
        progress("Finding duplicates…")
        val dupes = CleanupRules.duplicates(items) { item -> fingerprint(item.file).also { if (++checked % 25 == 0) progress("Finding duplicates… $checked of $sameSize") } }
        if (dupes.isNotEmpty()) { slow += Suggestion("dupes", "Duplicate files", "Exact copies; the original stays", emptyList(), dupes); partial(overview(slow)) }

        val recent = photos.take(2000)
        val done = java.util.concurrent.atomic.AtomicInteger()
        progress("Comparing similar photos…")
        val say = { m: String -> synchronized(done) { progress(m) } }
        // Small thumbnails Android already keeps, read four at a time.
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        val hashed = try {
            pool.invokeAll(recent.map { p -> java.util.concurrent.Callable {
                look(p).also { val n = done.incrementAndGet(); if (n % 50 == 0) say("Comparing similar photos… $n of ${recent.size}") }?.let { (hash, sharp) -> Triple(p, hash, sharp) }
            } }).mapNotNull { it.get() }
        } finally { pool.shutdown() }
        val similar = CleanupRules.similar(hashed)
        if (similar.isNotEmpty()) slow += Suggestion("similar", "Similar shots", "Bursts and near-repeats; the sharpest stays", emptyList(), similar)
        return overview(slow)
    }

    /** The slow way, when Android's index can't be read: look at every file. */
    private fun walk(emptyDirs: MutableList<File>, progress: (String) -> Unit): List<Item> {
        val items = ArrayList<Item>()
        root.walkTopDown()
            .onEnter { d -> d == root || (!d.name.startsWith(".") && d.absolutePath != File(root, "Android/data").absolutePath && d.absolutePath != File(root, "Android/obb").absolutePath) }
            .onFail { _, _ -> }
            .forEach { f ->
                if (items.size > 250_000) return@forEach
                if (f.isFile) { items += Item(f, f.length(), f.lastModified(), FileModel.kindOfName(f.name)); if (items.size % 1000 == 0) progress("Looking through your storage… ${items.size} files") }
                else if (f != root && f.list()?.isEmpty() == true) emptyDirs += f
            }
        return items
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
    private fun look(item: Item): Pair<Long, Double>? {
        // Android's own cached thumbnail when the photo is indexed: much faster than decoding the full picture.
        if (item.mediaId > 0) runCatching {
            val uri = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, item.mediaId)
            measure(context.contentResolver.loadThumbnail(uri, android.util.Size(96, 96), null))
        }.getOrNull()?.let { return it }
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(item.file.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 128 && bounds.outHeight / (sample * 2) >= 128) sample *= 2
            BitmapFactory.decodeFile(item.file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.let(::measure)
        }.getOrNull()
    }

    private fun measure(small: Bitmap): Pair<Long, Double> {
        val tiny = Bitmap.createScaledBitmap(small, 9, 8, true)
        val grid = Bitmap.createScaledBitmap(small, 64, 64, true)
        fun gray(b: Bitmap): IntArray { val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height); return IntArray(px.size) { val c = px[it]; ((c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114) / 1000 } }
        val result = CleanupRules.dHash(gray(tiny)) to CleanupRules.sharpness(gray(grid), 64, 64)
        small.recycle(); tiny.recycle(); grid.recycle()
        return result
    }
}
