package app.ultimate.studio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.MimeTypes

/** What a source video is made of: its video codec and overall bitrate (bits per second), and how much of it is used. */
data class SourceInfo(val mime: String?, val bitrate: Long?, val usedMs: Long)

/** Which video codec and bitrate an export should use. */
data class ExportPlan(val mime: String, val bitrate: Int, val why: String)

/**
 * Picks the export codec and bitrate from what is being edited, so a trimmed clip is never bigger than the file it came from.
 * A fixed 8 Mbps H.264 re-encode of a 4 Mbps HEVC original (which is what Squeeze makes) is roughly twice as large for the same
 * picture. Rules: keep HEVC when the sources are HEVC (it needs about half the bits); never ask for more than the sources
 * averaged; never go below a floor that would look blocky; and never above the old ceiling. Pure, so it is tested.
 */
object ExportPlanner {
    private const val CAP_1080 = 8_000_000; private const val CAP_720 = 3_500_000
    private const val FLOOR_1080 = 2_500_000; private const val FLOOR_720 = 1_200_000

    fun plan(sources: List<SourceInfo>, shortSide: Int): ExportPlan {
        val cap = if (shortSide == 1080) CAP_1080 else CAP_720
        val floor = if (shortSide == 1080) FLOOR_1080 else FLOOR_720
        val known = sources.filter { it.bitrate != null && it.bitrate > 0 && it.usedMs > 0 }
        val allHevc = sources.isNotEmpty() && sources.all { it.mime == MimeTypes.VIDEO_H265 }
        if (known.isEmpty()) return ExportPlan(MimeTypes.VIDEO_H264, cap, "source size unknown, standard quality")
        // Average of the sources, weighted by how much of each is used. Audio is in the overall figure, so take a little off.
        val avg = known.sumOf { it.bitrate!!.toDouble() * it.usedMs } / known.sumOf { it.usedMs } * 0.92
        val mime = if (allHevc) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264
        // H.264 needs about a third more bits than HEVC for the same picture, so converting HEVC to H.264 is allowed that much.
        val wanted = if (!allHevc && known.any { it.mime == MimeTypes.VIDEO_H265 }) avg * 1.35 else avg
        val bitrate = wanted.toInt().coerceIn(floor, cap)
        return ExportPlan(mime, bitrate, if (bitrate < cap) "matched to the source (${bitrate / 1000} kbps)" else "capped at the standard quality")
    }

    /** Reads one source's codec and bitrate. Anything unreadable comes back as unknown rather than failing the export. */
    fun probe(context: Context, c: StudioClip): SourceInfo = runCatching {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, Uri.parse(c.uri), null)
            var mime: String? = null; var bps: Long? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    mime = f.getString(MediaFormat.KEY_MIME)
                    if (f.containsKey(MediaFormat.KEY_BIT_RATE)) bps = f.getInteger(MediaFormat.KEY_BIT_RATE).toLong()
                }
            }
            if (bps == null && c.sourceMs > 0) {
                // No bitrate in the header: take it from the file's size.
                val size = context.contentResolver.openAssetFileDescriptor(Uri.parse(c.uri), "r")?.use { it.length } ?: -1L
                if (size > 0) bps = size * 8000 / c.sourceMs
            }
            SourceInfo(mime, bps, c.durationMs)
        } finally { ex.release() }
    }.getOrDefault(SourceInfo(null, null, c.durationMs))
}
