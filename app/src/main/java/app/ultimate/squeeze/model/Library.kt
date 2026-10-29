package app.ultimate.squeeze.model

/** One finished compression, kept so the result can be revisited and compared. Stats are measured, not planned. */
data class LibraryEntry(
    val id: String,
    val createdAt: Long,
    val name: String,
    val outputUri: String,
    val before: Source,
    val after: Source,
    val encodeMs: Long,
    val plannedBytes: Long,
    /** Human-readable settings used, e.g. "Small · HEVC". */
    val label: String,
    val hasFrames: Boolean,
    /** Where the saved original clip starts on the video's timeline, or -1 when no clip was saved. */
    val clipStartMs: Long = -1,
) {
    val hasClip: Boolean get() = clipStartMs >= 0

    val savedBytes: Long get() = (before.sizeBytes - after.sizeBytes).coerceAtLeast(0)
}

/** Bits per pixel per frame: the codec-independent measure of how much detail each pixel is given. */
fun Source.bitsPerPixel(): Double {
    val px = width.toDouble() * height * fps
    return if (px > 0 && videoBitrate > 0) videoBitrate / px else 0.0
}
