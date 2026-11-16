package app.ultimate.squeeze.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import app.ultimate.squeeze.model.FramePlan
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Pulls a handful of small frames spread across a whole video, for the squeeze playthrough animation. */
object FrameExtractor {
    /**
     * Up to [count] frames evenly spread over the clip, each downscaled so its longest side is [maxEdge]. Blocking work:
     * call it off the main thread. Frames that can't be decoded are skipped, so the result can be short or empty
     * (callers fall back to a single still frame). Cancelling stops early and recycles anything already decoded.
     * The caller owns the bitmaps and should recycle them when done.
     */
    suspend fun extract(
        context: Context, uri: Uri, durationMs: Long, count: Int = FramePlan.COUNT, maxEdge: Int = FramePlan.MAX_EDGE,
    ): List<Bitmap> {
        val frames = ArrayList<Bitmap>(count)
        var ok = false
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            fun meta(key: Int) = r.extractMetadata(key)?.toIntOrNull() ?: 0
            val length = if (durationMs > 0) durationMs else r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val size = FramePlan.scaledSize(
                meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
                meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION), maxEdge,
            )
            if (size != null) {
                for (at in FramePlan.timestampsUs(length, count)) {
                    currentCoroutineContext().ensureActive()
                    // The nearest keyframe is much cheaper than an exact seek, and plenty for a flipbook.
                    val frame = runCatching {
                        r.getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, size.first, size.second)
                    }.getOrNull()
                    if (frame != null) frames += frame
                }
            }
            ok = true
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Throwable) {
            // An unreadable file or a codec that refuses: report "no frames" and let the caller fall back.
        } finally {
            runCatching { r.release() }
            if (!ok) { frames.forEach { it.recycle() }; frames.clear() }
        }
        return frames
    }
}
