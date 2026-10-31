package app.ultimate.files

import java.io.File
import java.util.Locale

enum class FileSort(val label: String) { Name("Name"), Newest("Newest"), Largest("Largest"), Type("Type") }

/** What kind of thing a file is, for its icon and what tapping it does. */
enum class FileKind { Folder, Image, Video, Audio, Apk, Archive, Document, Other }

data class Entry(val file: File, val kind: FileKind, val bytes: Long, val modified: Long, val children: Int)

object FileModel {
    fun kindOf(f: File): FileKind = if (f.isDirectory) FileKind.Folder else kindOfName(f.name)

    /** The kind from the name alone, for files already known to be files (no disk access, so fast for long lists). */
    fun kindOfName(name: String): FileKind {
        return when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
            "jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "dng" -> FileKind.Image
            "mp4", "mkv", "webm", "mov", "3gp", "avi", "m4v" -> FileKind.Video
            "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "amr" -> FileKind.Audio
            "apk", "apks", "xapk", "apkm" -> FileKind.Apk
            "zip", "rar", "7z", "tar", "gz" -> FileKind.Archive
            "pdf", "txt", "md", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv", "json", "html" -> FileKind.Document
            else -> FileKind.Other
        }
    }

    fun mimeOf(f: File): String = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase(Locale.US))
        ?: when (kindOf(f)) { FileKind.Apk -> "application/vnd.android.package-archive"; else -> "application/octet-stream" }

    /** Folders first, then by the chosen order; hidden files only when asked. */
    fun sorted(entries: List<Entry>, sort: FileSort, showHidden: Boolean): List<Entry> {
        val visible = if (showHidden) entries else entries.filterNot { it.file.name.startsWith(".") }
        val within = when (sort) {
            FileSort.Name -> compareBy<Entry> { it.file.name.lowercase(Locale.ROOT) }
            FileSort.Newest -> compareByDescending { it.modified }
            FileSort.Largest -> compareByDescending { it.bytes }
            FileSort.Type -> compareBy<Entry>({ it.kind.ordinal }, { it.file.extension.lowercase(Locale.ROOT) }, { it.file.name.lowercase(Locale.ROOT) })
        }
        return visible.sortedWith(compareBy<Entry> { it.kind != FileKind.Folder }.then(within))
    }

    /** A name not yet taken in [dir]: "photo.jpg", then "photo (2).jpg", "photo (3).jpg"… */
    fun freeName(dir: File, name: String): String {
        if (!File(dir, name).exists()) return name
        val base = name.substringBeforeLast('.', name); val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
        var n = 2
        while (File(dir, "$base ($n)$ext").exists()) n++
        return "$base ($n)$ext"
    }

    /** A name the file system accepts, or null with nothing usable left. */
    fun validName(raw: String): String? = raw.trim().replace(Regex("[/\\\\:*?\"<>|\\u0000]"), "").takeIf { it.isNotEmpty() && it != "." && it != ".." }

    /** True when [child] is [parent] or inside it, so a folder is never moved into itself. */
    fun isInside(child: File, parent: File): Boolean = child.canonicalPath == parent.canonicalPath || child.canonicalPath.startsWith(parent.canonicalPath + File.separator)

    /** The folder path as crumbs from the storage root, e.g. Internal › DCIM › Camera. */
    fun crumbs(dir: File, root: File): List<Pair<String, File>> {
        val out = mutableListOf<Pair<String, File>>()
        var cur: File? = dir
        while (cur != null && isInside(cur, root)) { out += (if (cur.canonicalPath == root.canonicalPath) "Internal" else cur.name) to cur; cur = cur.parentFile }
        return out.reversed()
    }
}

/**
 * Ultimate's own recycle bin: deleted files move to a hidden folder for 30 days with a note of where they came from, so a
 * mistake is one tap to undo. Moving within the same storage is a rename, so it costs no time or space.
 */
class Trash(private val root: File) {
    val dir = File(root, ".UltimateTrash")
    data class Item(val file: File, val originalPath: String, val deletedAt: Long)

    fun put(f: File): Item {
        dir.mkdirs()
        val at = System.currentTimeMillis()
        val target = File(dir, "$at~${f.name}")
        check(f.renameTo(target) || (f.copyRecursively(target) && f.deleteRecursively())) { "couldn't move ${f.name} to the bin" }
        File(dir, "$at~${f.name}.from").writeText(f.absolutePath)
        return Item(target, f.absolutePath, at)
    }

    fun items(): List<Item> = dir.listFiles().orEmpty().filter { !it.name.endsWith(".from") && '~' in it.name }.map { f ->
        Item(f, runCatching { File(dir, f.name + ".from").readText() }.getOrDefault(File(root, f.name.substringAfter('~')).absolutePath), f.name.substringBefore('~').toLongOrNull() ?: f.lastModified())
    }.sortedByDescending { it.deletedAt }

    fun restore(item: Item): File {
        val original = File(item.originalPath)
        original.parentFile?.mkdirs()
        val target = File(original.parentFile ?: root, FileModel.freeName(original.parentFile ?: root, original.name))
        check(item.file.renameTo(target)) { "couldn't restore ${original.name}" }
        File(dir, item.file.name + ".from").delete()
        return target
    }

    fun purge(item: Item) { item.file.deleteRecursively(); File(dir, item.file.name + ".from").delete() }

    /** Empties anything older than [days]; returns bytes freed. */
    fun purgeOlderThan(days: Int, now: Long = System.currentTimeMillis()): Long =
        items().filter { now - it.deletedAt > days * 86_400_000L }.sumOf { i -> FileOps.size(i.file).also { purge(i) } }
}

object FileOps {
    fun size(f: File): Long = if (f.isFile) f.length() else f.walkTopDown().onFail { _, _ -> }.filter { it.isFile }.sumOf { it.length() }

    fun list(dir: File): List<Entry> = dir.listFiles().orEmpty().map { f ->
        Entry(f, FileModel.kindOf(f), if (f.isFile) f.length() else 0, f.lastModified(), if (f.isDirectory) f.list()?.size ?: 0 else 0)
    }

    /** Copies or moves into [dir] without overwriting anything there. */
    fun transfer(files: List<File>, dir: File, move: Boolean): List<File> = files.map { f ->
        require(!(f.isDirectory && FileModel.isInside(dir, f))) { "can't put ${f.name} inside itself" }
        val target = File(dir, FileModel.freeName(dir, f.name))
        if (move && f.renameTo(target)) return@map target
        check(f.copyRecursively(target)) { "couldn't copy ${f.name}" }
        if (move) f.deleteRecursively()
        target
    }

    /** Files under [dir] whose names contain [query], up to [limit], skipping hidden folders. */
    fun search(dir: File, query: String, limit: Int = 300): List<Entry> {
        val q = query.lowercase(Locale.ROOT)
        return dir.walkTopDown().onEnter { !it.name.startsWith(".") || it == dir }.onFail { _, _ -> }
            .filter { it != dir && it.name.lowercase(Locale.ROOT).contains(q) }.take(limit)
            .map { Entry(it, FileModel.kindOf(it), if (it.isFile) it.length() else 0, it.lastModified(), 0) }.toList()
    }
}
