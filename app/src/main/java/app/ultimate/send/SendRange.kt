package app.ultimate.send

/** One HTTP byte range. Unknown-length streams support full reads only. */
object SendRange {
    fun parse(header: String?, bytes: Long): LongRange? {
        if (header == null) return 0L..(bytes - 1)
        if (bytes <= 0 || !header.startsWith("bytes=") || ',' in header) return null
        val parts = header.removePrefix("bytes=").split('-')
        if (parts.size != 2) return null
        if (parts[0].isEmpty()) {
            val suffix = parts[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
            return (bytes - suffix.coerceAtMost(bytes))..(bytes - 1)
        }
        val first = parts[0].toLongOrNull()?.takeIf { it >= 0 && it < bytes } ?: return null
        val last = if (parts[1].isEmpty()) bytes - 1 else parts[1].toLongOrNull() ?: return null
        if (last < first) return null
        return first..last.coerceAtMost(bytes - 1)
    }
}
