package app.ultimate.photos

import kotlin.math.max
import kotlin.math.roundToInt

enum class PhotoFormat(val label: String, val mime: String, val ext: String) { Jpeg("JPEG", "image/jpeg", "jpg"), Webp("WebP", "image/webp", "webp") }

/** Longest side of the result in pixels; 0 keeps the original size. */
enum class PhotoSize(val label: String, val longSide: Int) { Original("Original", 0), K4("4K", 3840), Hd("1080p", 1920), Web("Web", 1280), Small("Small", 800) }

/** Width:height to crop to from the centre; null keeps the whole picture. */
enum class PhotoCrop(val label: String, val ratio: Float?) { None("Uncropped", null), Square("1:1", 1f), Portrait("4:5", 4f / 5), Story("9:16", 9f / 16), Wide("16:9", 16f / 9) }

data class PhotoSettings(
    val format: PhotoFormat = PhotoFormat.Jpeg,
    val size: PhotoSize = PhotoSize.Hd,
    val crop: PhotoCrop = PhotoCrop.None,
    val quality: Int = 82,
)

/** A centred crop rectangle and the size to scale it to. */
data class PhotoPlan(val cropLeft: Int, val cropTop: Int, val cropWidth: Int, val cropHeight: Int, val outWidth: Int, val outHeight: Int)

object PhotoPlanner {
    fun plan(width: Int, height: Int, s: PhotoSettings): PhotoPlan {
        var cw = width; var ch = height
        s.crop.ratio?.let { r ->
            if (width.toFloat() / height > r) cw = (height * r).roundToInt() else ch = (width / r).roundToInt()
        }
        cw = cw.coerceIn(1, width); ch = ch.coerceIn(1, height)
        val left = (width - cw) / 2; val top = (height - ch) / 2
        val limit = s.size.longSide
        val scale = if (limit == 0 || max(cw, ch) <= limit) 1f else limit.toFloat() / max(cw, ch)
        return PhotoPlan(left, top, cw, ch, (cw * scale).roundToInt().coerceAtLeast(1), (ch * scale).roundToInt().coerceAtLeast(1))
    }

    /** A rough size guess for the estimate card: bytes per pixel at this quality, from typical photos. */
    fun estimateBytes(plan: PhotoPlan, s: PhotoSettings): Long {
        val bpp = (if (s.format == PhotoFormat.Webp) 0.11 else 0.16) * (0.35 + s.quality / 100.0 * 1.4)
        return (plan.outWidth.toLong() * plan.outHeight * bpp).toLong()
    }
}
