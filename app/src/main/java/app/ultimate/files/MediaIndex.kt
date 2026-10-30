package app.ultimate.files

import android.content.Context
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns as c
import java.io.File

/**
 * Every file Android has indexed in shared storage, from one MediaStore query: seconds instead of the minutes it takes to
 * look at each file over the storage layer. Files Android does not index (some hidden folders) are missed; callers count
 * those as "other". Returns null when the index can't be read, so callers can fall back to walking the folders.
 */
object MediaIndex {
    fun load(context: Context, progress: (Int) -> Unit = {}): List<Item>? = runCatching {
        val projection = arrayOf(c._ID, c.DATA, c.SIZE, c.DATE_MODIFIED, c.MIME_TYPE)
        val out = ArrayList<Item>(50_000)
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        // "format" 0x3001 marks folders; it isn't public API, so fall back to telling folders apart by name if it's refused.
        val cursor = runCatching { context.contentResolver.query(uri, projection, "format != 12289", null, null) }.getOrNull()
            ?: context.contentResolver.query(uri, projection, null, null, null)
        cursor?.use { cur ->
            val iId = cur.getColumnIndexOrThrow(c._ID); val iPath = cur.getColumnIndexOrThrow(c.DATA); val iSize = cur.getColumnIndexOrThrow(c.SIZE)
            val iMod = cur.getColumnIndexOrThrow(c.DATE_MODIFIED); val iMime = cur.getColumnIndexOrThrow(c.MIME_TYPE)
            while (cur.moveToNext()) {
                val path = cur.getString(iPath) ?: continue
                if (MediaRules.skip(path)) continue
                val name = path.substringAfterLast('/')
                // Folders are rows too: no MIME type and no extension.
                if (cur.isNull(iMime) && '.' !in name) continue
                out += Item(File(path), cur.getLong(iSize), cur.getLong(iMod) * 1000, FileModel.kindOfName(name), cur.getLong(iId))
                if (out.size % 5000 == 0) progress(out.size)
            }
        } ?: return null
        out
    }.getOrNull()
}

/** Which indexed paths a storage scan leaves out. Pure, so it is tested. */
object MediaRules {
    fun skip(path: String) = "/.UltimateTrash/" in path || "/Android/data/" in path || "/Android/obb/" in path
}
