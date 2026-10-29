package app.ultimate.squeeze.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/**
 * Stream-copies a few seconds of a video into a small MP4 without re-encoding, so the original's exact quality
 * survives for side-by-side playback after the source file is gone.
 */
object ClipExtractor {
    const val CLIP_US = 4_000_000L
    private const val AAC = "audio/mp4a-latm"

    /** Where a clip should begin for a video of [durationMs]: a third of the way in, leaving room for the whole clip. */
    fun targetStartUs(durationMs: Long): Long = (durationMs * 300).coerceAtMost((durationMs * 1000 - CLIP_US - 500_000).coerceAtLeast(0))

    /**
     * Copies up to [CLIP_US] starting at the sync frame at or before [targetUs] into [out].
     * Returns the start of the copied segment on the original's timeline in microseconds, or null if the container or codec cannot be
     * remuxed. On null, [out] may hold a partial file; the caller removes it.
     */
    fun extract(context: Context, source: Uri, out: File, targetUs: Long): Long? {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            extractor.setDataSource(context, source, null)
            var videoIdx = -1
            var audioIdx = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                if (videoIdx < 0 && mime.startsWith("video/")) videoIdx = i
                if (audioIdx < 0 && mime == AAC) audioIdx = i
            }
            if (videoIdx < 0) return null

            // Find the video sync frame first; audio is then aligned to it.
            extractor.selectTrack(videoIdx)
            extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val baseUs = extractor.sampleTime.takeIf { it >= 0 } ?: return null
            if (audioIdx >= 0) {
                extractor.selectTrack(audioIdx)
                extractor.seekTo(baseUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }

            muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(rotation(context, source))
            val outIndex = HashMap<Int, Int>()
            outIndex[videoIdx] = muxer.addTrack(extractor.getTrackFormat(videoIdx))
            if (audioIdx >= 0) outIndex[audioIdx] = muxer.addTrack(extractor.getTrackFormat(audioIdx))
            muxer.start()
            started = true

            val buffer = ByteBuffer.allocate(maxSampleSize(extractor, videoIdx, audioIdx))
            val info = MediaCodec.BufferInfo()
            var wroteVideo = false
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val t = extractor.sampleTime
                val track = extractor.sampleTrackIndex
                if (t - baseUs > CLIP_US) break
                // Audio can start slightly before the video sync frame; drop it so both tracks share one start.
                if (t >= baseUs || track == videoIdx) {
                    val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    info.set(0, size, (t - baseUs).coerceAtLeast(0), flags)
                    muxer.writeSampleData(outIndex.getValue(track), buffer, info)
                    if (track == videoIdx) wroteVideo = true
                }
                extractor.advance()
            }
            if (!wroteVideo) return null
            return baseUs
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { if (started) muxer?.stop() }
            runCatching { muxer?.release() }
            extractor.release()
        }
    }

    private fun maxSampleSize(extractor: MediaExtractor, vararg tracks: Int): Int {
        val declared = tracks.filter { it >= 0 }.maxOf { i ->
            extractor.getTrackFormat(i).let { if (it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) it.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0 }
        }
        return declared.coerceAtLeast(4 * 1024 * 1024)
    }

    private fun rotation(context: Context, uri: Uri): Int {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
        } finally {
            r.release()
        }
    }
}
