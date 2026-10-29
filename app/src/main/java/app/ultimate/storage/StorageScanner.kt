package app.ultimate.storage

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import app.ultimate.files.FileKind
import app.ultimate.files.FileModel
import org.json.JSONArray
import java.io.File
import java.util.PriorityQueue

data class AppSize(val pkg: String, val label: String, val app: Long, val data: Long, val cache: Long) { val total get() = app + data + cache }

data class StorageReport(
    val at: Long, val total: Long, val free: Long, val root: Folder,
    val byKind: Map<FileKind, Long>, val biggest: List<BigFile>, val forgotten: List<BigFile>,
    val apps: List<AppSize>?, val trend: List<Pair<Long, Long>>,
) {
    val used get() = total - free
    val appsBytes get() = apps?.sumOf { it.total }
    /** What the files and apps don't explain: Android itself, caches of other apps, and space reserved by the system. */
    val system get() = (used - byKind.values.sum() - (appsBytes ?: 0)).coerceAtLeast(0)
}

/** Walks shared storage once and measures everything: folders, kinds of files, the biggest files, and app sizes. */
class StorageScanner(private val context: Context) {
    private val prefs = context.getSharedPreferences("storage", Context.MODE_PRIVATE)

    fun scan(progress: (Int) -> Unit = {}): StorageReport {
        val rootDir = Environment.getExternalStorageDirectory()
        val root = Folder("Internal storage", rootDir.path)
        val byKind = HashMap<FileKind, Long>()
        val biggest = PriorityQueue<BigFile>(compareBy { it.bytes })
        val forgotten = PriorityQueue<BigFile>(compareBy { it.bytes })
        val now = System.currentTimeMillis()
        var count = 0
        val skip = setOf(File(rootDir, "Android/data").path, File(rootDir, "Android/obb").path)
        rootDir.walkTopDown().onEnter { it.path !in skip }.onFail { _, _ -> }.forEach { f ->
            if (!f.isFile) return@forEach
            val bytes = f.length(); val kind = FileModel.kindOf(f)
            byKind[kind] = (byKind[kind] ?: 0) + bytes
            // Add the size to every folder on the way down from the root.
            val rel = f.parentFile?.relativeTo(rootDir)?.path.orEmpty()
            var node = root
            node.bytes += bytes; node.files++
            if (rel.isNotEmpty() && rel != ".") rel.split(File.separatorChar).forEach { part ->
                node = node.children.getOrPut(part) { Folder(part, node.path + File.separator + part) }
                node.bytes += bytes; node.files++
            }
            node.own += bytes
            val big = BigFile(f.path, f.name, bytes, f.lastModified(), kind)
            biggest.add(big); if (biggest.size > 60) biggest.poll()
            if (bytes > 50_000_000 && now - f.lastModified() > 365 * 86_400_000L) { forgotten.add(big); if (forgotten.size > 40) forgotten.poll() }
            if (++count % 2000 == 0) progress(count)
        }
        val stat = StatFs(Environment.getDataDirectory().path)
        val apps = runCatching { apps() }.getOrNull()
        val trend = Trend.add(load(), now, stat.totalBytes - stat.availableBytes).also(::save)
        return StorageReport(now, stat.totalBytes, stat.availableBytes, root, byKind, biggest.sortedByDescending { it.bytes }, forgotten.sortedByDescending { it.bytes }, apps, trend)
    }

    /** App, data and cache sizes per launchable app; needs usage access, otherwise null. */
    private fun apps(): List<AppSize>? {
        if (!app.ultimate.checkup.engine.Collector(context).hasUsageAccess()) return null
        val ssm = context.getSystemService(StorageStatsManager::class.java)
        val pm = context.packageManager
        val user = Process.myUserHandle()
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).map { it.activityInfo.packageName }.distinct().mapNotNull { pkg ->
            runCatching {
                val s = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, user)
                AppSize(pkg, pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(), s.appBytes, s.dataBytes - s.cacheBytes, s.cacheBytes)
            }.getOrNull()
        }.sortedByDescending { it.total }
    }

    private fun load(): List<Pair<Long, Long>> = runCatching {
        val a = JSONArray(prefs.getString("trend", "[]"))
        List(a.length()) { a.getJSONArray(it).let { p -> p.getLong(0) to p.getLong(1) } }
    }.getOrDefault(emptyList())

    private fun save(list: List<Pair<Long, Long>>) = prefs.edit().putString("trend", JSONArray().apply { list.forEach { put(JSONArray(listOf(it.first, it.second))) } }.toString()).apply()
}
