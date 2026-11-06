package app.ultimate.watch

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Sends the watch app that Ultimate+ carries to the watch over the watch link, where the watch installs it with one
 * tap. Needs the watch app from 1.2.0 on (the first version that can receive updates); older ones update once over
 * wireless debugging.
 */
object WatchUpdater {
    /** The version of the watch app inside this Ultimate+ (code, name), or null when this build doesn't carry it. */
    fun bundled(c: Context): Pair<Long, String>? = runCatching {
        val f = extract(c)
        val info = c.packageManager.getPackageArchiveInfo(f.path, 0) ?: return null
        @Suppress("DEPRECATION") val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        code to (info.versionName ?: "?")
    }.getOrNull()

    private fun extract(c: Context): File {
        val f = File(c.cacheDir, "watch-update.apk")
        val size = runCatching { c.assets.openFd(WatchInstaller.BUNDLED).use { it.length } }.getOrDefault(-1L)
        if (!f.exists() || f.length() != size) c.assets.open(WatchInstaller.BUNDLED).use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    /** Asks the watch which version it has; it answers with "hello:" (see WatchLink.watchVersion). */
    fun ask(c: Context) = WatchLink.sendToWatch(c, "version?")

    /** Streams the bundled watch app to the first connected watch; returns null when sent, or why it couldn't. */
    suspend fun send(c: Context): String? = withContext(Dispatchers.IO) {
        runCatching {
            val file = extract(c)
            val node = Tasks.await(Wearable.getNodeClient(c).connectedNodes, 10, TimeUnit.SECONDS).firstOrNull() ?: return@withContext "No watch is connected."
            val channels = Wearable.getChannelClient(c)
            val channel = Tasks.await(channels.openChannel(node.id, UPDATE_PATH), 20, TimeUnit.SECONDS)
            Tasks.await(channels.sendFile(channel, Uri.fromFile(file)), 3, TimeUnit.MINUTES)
            null
        }.getOrElse { "Couldn't send it: ${it.message ?: it.javaClass.simpleName}" }
    }

    /** Must match the watch's WatchUpdateService.UPDATE_PATH. */
    const val UPDATE_PATH = "/ultimate/update"
}
