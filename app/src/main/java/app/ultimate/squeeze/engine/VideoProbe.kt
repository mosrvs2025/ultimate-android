package app.ultimate.squeeze.engine

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import app.ultimate.squeeze.model.Source

data class Probed(val uri: Uri, val name: String, val sizeBytes: Long, val source: Source?)

object VideoProbe {
    fun probe(context: Context, uri: Uri): Probed {
        val (name, size) = nameAndSize(context, uri)
        return Probed(uri, name, size, runCatching { readSource(context, uri, size) }.getOrNull())
    }

    private fun nameAndSize(context: Context, uri: Uri): Pair<String, Long> {
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
        if (size <= 0) {
            size = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: 0L
        }
        return name to size
    }

    private fun readSource(context: Context, uri: Uri, size: Long): Source? {
        val retriever = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        try {
            retriever.setDataSource(context, uri)
            extractor.setDataSource(context, uri, null)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0

            var video: MediaFormat? = null
            var audio: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (video == null && mime.startsWith("video/")) video = f
                if (audio == null && mime.startsWith("audio/")) audio = f
            }
            video ?: return null

            val rawW = video.getInteger(MediaFormat.KEY_WIDTH)
            val rawH = video.getInteger(MediaFormat.KEY_HEIGHT)
            val turned = rotation == 90 || rotation == 270
            val audioBps = audio?.intOrZero(MediaFormat.KEY_BIT_RATE) ?: 0
            val overall = if (durationMs > 0 && size > 0) (size * 8000 / durationMs).toInt() else 0
            val declared = video.intOrZero(MediaFormat.KEY_BIT_RATE)
            val videoBps = if (declared > 0) declared else (overall - audioBps).coerceAtLeast(0)
            val transfer = video.intOrZero(MediaFormat.KEY_COLOR_TRANSFER)

            return Source(
                sizeBytes = size,
                durationMs = durationMs,
                width = if (turned) rawH else rawW,
                height = if (turned) rawW else rawH,
                fps = video.floatOrZero(MediaFormat.KEY_FRAME_RATE).takeIf { it >= 1f } ?: 30f,
                videoBitrate = videoBps,
                audioBitrate = audioBps,
                hasAudio = audio != null,
                isHdr = transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG,
                mime = video.getString(MediaFormat.KEY_MIME).orEmpty(),
            )
        } finally {
            runCatching { retriever.release() }
            extractor.release()
        }
    }

    private fun MediaFormat.intOrZero(key: String) = if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(0) else 0

    private fun MediaFormat.floatOrZero(key: String): Float {
        if (!containsKey(key)) return 0f
        return runCatching { getFloat(key) }.getOrElse { runCatching { getInteger(key).toFloat() }.getOrDefault(0f) }
    }
}
