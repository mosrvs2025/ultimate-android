package app.ultimate.clean

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.nio.ByteBuffer

/** What was removed from one file, for the summary line. */
data class Cleaned(val file: File, val mime: String, val removed: List<String>)

/** The EXIF tags that say where, when and with what a photo was taken, or who took it. Pixels and orientation are kept. */
object CleanRules {
    val location = listOf(
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_SPEED_REF, ExifInterface.TAG_GPS_IMG_DIRECTION,
        ExifInterface.TAG_GPS_IMG_DIRECTION_REF, ExifInterface.TAG_GPS_DEST_BEARING, ExifInterface.TAG_GPS_TRACK, ExifInterface.TAG_GPS_AREA_INFORMATION,
    )
    val device = listOf(
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE, ExifInterface.TAG_BODY_SERIAL_NUMBER,
        ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_LENS_SERIAL_NUMBER, ExifInterface.TAG_CAMERA_OWNER_NAME,
        ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_IMAGE_UNIQUE_ID, ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_MAKER_NOTE,
    )
    val time = listOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
    )

    /** Plain names of what a set of present tags reveals, e.g. ["location", "camera"]. */
    fun describe(present: Collection<String>): List<String> = buildList {
        if (present.any { it in location }) add("location")
        if (present.any { it in device }) add("camera details")
        if (present.any { it in time }) add("time taken")
    }
}

/**
 * Makes copies of photos and videos with identifying details removed, without re-encoding: photos keep their exact pixels
 * (only EXIF changes) and videos are re-packaged track by track, which drops the location and recorder tags.
 */
class MetadataCleaner(private val context: Context) {
    private val dir = File(context.cacheDir, "clean")

    /** Clears the previous run's copies; called before a new batch, after the receiving app has had them. */
    fun reset() { dir.deleteRecursively(); dir.mkdirs() }

    fun clean(uri: Uri, mime: String?): Cleaned {
        val name = displayName(uri)
        val type = mime ?: context.contentResolver.getType(uri) ?: ""
        return if (type.startsWith("video/")) video(uri, name) else image(uri, name, type)
    }

    private fun image(uri: Uri, name: String, mime: String): Cleaned {
        val out = File(dir, name)
        context.contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
        val removed = runCatching {
            val exif = ExifInterface(out)
            val all = CleanRules.location + CleanRules.device + CleanRules.time
            val present = all.filter { exif.getAttribute(it) != null }
            all.forEach { exif.setAttribute(it, null) }
            exif.saveAttributes()
            CleanRules.describe(present)
        }.getOrDefault(emptyList()) // formats without EXIF (GIF, BMP) carry nothing to remove
        return Cleaned(out, mime.ifEmpty { "image/jpeg" }, removed)
    }

    private fun video(uri: Uri, name: String): Cleaned {
        val out = File(dir, name.substringBeforeLast('.') + ".mp4")
        val hadLocation = runCatching {
            MediaMetadataRetriever().run { try { setDataSource(context, uri); extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION) != null } finally { release() } }
        }.getOrDefault(false)
        val rotation = runCatching {
            MediaMetadataRetriever().run { try { setDataSource(context, uri); extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0 } finally { release() } }
        }.getOrDefault(0)
        val extractor = MediaExtractor()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            extractor.setDataSource(context, uri, null)
            val map = HashMap<Int, Int>()
            var maxInput = 1 shl 20
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (!m.startsWith("video/") && !m.startsWith("audio/")) continue // metadata tracks can carry location too
                if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxInput = maxOf(maxInput, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                map[i] = muxer.addTrack(f)
                extractor.selectTrack(i)
            }
            muxer.setOrientationHint(rotation)
            muxer.start()
            val buf = ByteBuffer.allocate(maxInput)
            val info = android.media.MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buf, 0)
                if (size < 0) break
                info.set(0, size, extractor.sampleTime, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(map.getValue(extractor.sampleTrackIndex), buf, info)
                extractor.advance()
            }
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
            extractor.release()
        }
        return Cleaned(out, "video/mp4", buildList { if (hadLocation) add("location"); add("recording details") })
    }

    private fun displayName(uri: Uri): String {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0)?.let { name = it } }
        }
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "file" }
    }
}
