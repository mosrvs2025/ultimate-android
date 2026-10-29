package app.ultimate.studio

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Small frames for the timeline filmstrip, cached by source and time so scrolling and zooming do not decode again. */
object FrameCache {
    private const val BUCKET_MS = 250L
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    // Decoding many frames at once stalls the phone; a couple at a time keeps scrolling smooth.
    private val gate = Semaphore(2)

    fun peek(uri: String, atMs: Long): Bitmap? = cache.get(key(uri, atMs))

    suspend fun frame(context: Context, uri: String, atMs: Long, sizePx: Int): Bitmap? {
        val k = key(uri, atMs)
        cache.get(k)?.let { return it }
        return gate.withPermit {
            cache.get(k) ?: withContext(Dispatchers.IO) {
                runCatching {
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(context, Uri.parse(uri))
                        r.getScaledFrameAtTime(atMs / BUCKET_MS * BUCKET_MS * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, sizePx, sizePx)
                    } finally {
                        r.release()
                    }
                }.getOrNull()?.also { cache.put(k, it) }
            }
        }
    }

    private fun key(uri: String, atMs: Long) = "$uri#${atMs / BUCKET_MS}"
}
