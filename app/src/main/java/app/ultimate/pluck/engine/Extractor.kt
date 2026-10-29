package app.ultimate.pluck.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import app.ultimate.core.JobLog
import app.ultimate.core.formatBytes
import app.ultimate.pluck.model.AppItem
import app.ultimate.pluck.model.FileNames
import app.ultimate.pluck.model.estimateCompact
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class Extracted(val uri: Uri, val displayName: String, val bytes: Long, val splitCount: Int, val sha256: String)

/** How an app's APKs are laid out on disk: bytes Android stores uncompressed versus bytes already compressed. */
data class ApkComposition(val storedBytes: Long, val compressedBytes: Long) {
    val estimatedCompact: Long get() = estimateCompact(storedBytes, compressedBytes)
}

/**
 * Copies an app's APKs into Downloads/Pluck.
 *
 * - Normal: a single APK is saved as-is; split APKs are bundled into one `.apks` archive for split installers.
 * - Compact: packed with maximum compression for sending. Android keeps code, native libraries and resources
 *   uncompressed inside an APK so apps start faster, and the signature covers every byte, so the APK itself cannot
 *   shrink and still install. Packed, it is roughly half the size; unpacked, it is byte-for-byte the original.
 *   A single APK becomes a `.zip`; split APKs become a compressed `.apks` that split installers read directly.
 *
 * The SHA-256 is of the base APK as installed.
 */
class Extractor(private val context: Context) {

    suspend fun extract(app: AppItem, compact: Boolean, nameTemplate: String, log: JobLog, onProgress: (done: Long, total: Long) -> Unit): Extracted = withContext(Dispatchers.IO) {
        val bundle = app.isSplit
        val ext = when {
            bundle -> ".apks"
            compact -> ".zip"
            else -> ".apk"
        }
        val name = FileNames.render(nameTemplate, app) + ext
        log.detail("  source  ${app.apkPaths.size} file${if (app.apkPaths.size == 1) "" else "s"} · ${formatBytes(app.apkBytes)}")
        app.apkPaths.forEach { log.detail("          ${File(it).name.padEnd(28)} ${formatBytes(File(it).length())}") }
        log.detail("  mode    " + if (compact) "compact (deflate level 9)" else if (bundle) "bundle (stored)" else "copy as-is")
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(
                MediaStore.Downloads.MIME_TYPE,
                when {
                    bundle -> "application/octet-stream"
                    compact -> "application/zip"
                    else -> "application/vnd.android.package-archive"
                },
            )
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Pluck")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("Could not create the output file")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val progress = Progress(app.apkBytes, onProgress)
            resolver.openOutputStream(uri)?.use { out ->
                when {
                    bundle || compact -> writeArchive(app, out, if (compact) Deflater.BEST_COMPRESSION else Deflater.NO_COMPRESSION, digest, progress, apkName = name.removeSuffix(ext) + ".apk")
                    else -> copy(File(app.apkPaths.first()), out, digest, progress)
                }
            } ?: throw IOException("Could not open the output file")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            val bytes = outputSize(uri)
            val sha = AppRepository.hex(digest.digest())
            log.detail("  sha256  ${sha.take(16)}…${sha.takeLast(8)}")
            if (compact) log.detail("  packed  ${formatBytes(app.apkBytes)} → ${formatBytes(bytes)} (−${((1 - bytes.toDouble() / app.apkBytes) * 100).toInt().coerceAtLeast(0)}%)")
            Extracted(uri, displayName(uri) ?: name, bytes, app.apkPaths.size, sha)
        } catch (e: Throwable) {
            // Includes cancellation: never leave a half-written file behind.
            withContext(NonCancellable) { runCatching { resolver.delete(uri, null, null) } }
            throw e
        }
    }

    private suspend fun writeArchive(app: AppItem, out: OutputStream, level: Int, baseDigest: MessageDigest, progress: Progress, apkName: String) {
        ZipOutputStream(out).use { zip ->
            zip.setLevel(level)
            app.apkPaths.forEachIndexed { i, path ->
                val file = File(path)
                // A lone APK gets a readable name inside the zip; split APKs keep the names installers expect.
                zip.putNextEntry(ZipEntry(if (app.isSplit) file.name else apkName))
                copy(file, zip, if (i == 0) baseDigest else null, progress)
                zip.closeEntry()
            }
        }
    }

    private suspend fun copy(file: File, out: OutputStream, digest: MessageDigest?, progress: Progress) {
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                digest?.update(buffer, 0, n)
                out.write(buffer, 0, n)
                progress.add(n)
            }
        }
    }

    private fun displayName(uri: Uri): String? = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }

    private fun outputSize(uri: Uri): Long = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)
        ?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L

    private class Progress(private val total: Long, private val report: (Long, Long) -> Unit) {
        private var done = 0L
        fun add(n: Int) {
            done += n
            report(done, total)
        }
    }

    companion object {
        /** "Label_1.2.3": keeps readable characters, replaces anything a filesystem or share target could choke on. */
        /** Reads only the zip directories, so it is fast enough to show estimates before extracting. */
        fun composition(paths: List<String>): ApkComposition {
            var stored = 0L
            var compressed = 0L
            for (path in paths) {
                runCatching {
                    ZipFile(path).use { zip ->
                        for (e in zip.entries()) {
                            if (e.method == ZipEntry.STORED) stored += e.compressedSize.coerceAtLeast(0) else compressed += e.compressedSize.coerceAtLeast(0)
                        }
                    }
                }.onFailure { compressed += File(path).length() }
            }
            return ApkComposition(stored, compressed)
        }
    }
}
