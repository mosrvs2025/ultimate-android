package app.ultimate.audio.engine

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import app.ultimate.audio.model.AudioSource

/** A picked video and what its audio track looks like; [audio] is null when it has none or cannot be read. */
data class PickedVideo(val uri: Uri, val name: String, val sizeBytes: Long, val audio: AudioSource?)

object AudioProbe {
    fun probe(context: Context, uri: Uri): PickedVideo {
        var name = uri.lastPathSegment ?: "video"
        var size = 0L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        return PickedVideo(uri, name, size, runCatching { readAudio(context, uri) }.getOrNull())
    }

    private fun readAudio(context: Context, uri: Uri): AudioSource? {
        val retriever = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        try {
            retriever.setDataSource(context, uri)
            extractor.setDataSource(context, uri, null)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (!mime.startsWith("audio/")) continue
                return AudioSource(
                    durationMs = duration,
                    mime = mime,
                    sampleRate = f.intOr(MediaFormat.KEY_SAMPLE_RATE, 44100),
                    channels = f.intOr(MediaFormat.KEY_CHANNEL_COUNT, 2),
                    bitrate = f.intOr(MediaFormat.KEY_BIT_RATE, 0),
                )
            }
            return null
        } finally {
            runCatching { retriever.release() }
            extractor.release()
        }
    }

    private fun MediaFormat.intOr(key: String, default: Int) = if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(default) else default
}
