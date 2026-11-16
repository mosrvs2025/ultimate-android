package app.ultimate.send

/**
 * The cameras (and screens) currently being shared to this phone, one latest picture each, so a dashboard can show several at once.
 * A device that stops sending drops off the list a few seconds later. Pictures are kept only in memory, only the newest per device,
 * and are never written anywhere. Pure apart from the clock, so it is tested.
 */
class CamRegistry(private val clock: () -> Long = System::currentTimeMillis) {
    class Cam(val id: String, val name: String, val frame: ByteArray, val at: Long, val frames: Int)

    private val cams = LinkedHashMap<String, Cam>()

    @Synchronized fun put(id: String, name: String, frame: ByteArray) {
        val prev = cams[id]
        cams[id] = Cam(id, cleanName(name), frame, clock(), (prev?.frames ?: 0) + 1)
        // Past the limit, the one that has been quiet longest goes.
        while (cams.size > MAX) cams.remove(cams.values.minByOrNull { it.at }!!.id)
    }

    @Synchronized fun stop(id: String) { cams.remove(id) }
    @Synchronized fun get(id: String): Cam? { prune(); return cams[id] }
    @Synchronized fun list(): List<Cam> { prune(); return cams.values.toList() }
    private fun prune() { val now = clock(); cams.values.removeAll { now - it.at > TTL_MS } }

    companion object {
        const val TTL_MS = 6_000L
        const val MAX = 8

        /** An id safe to put in an address: letters, digits, dash and underscore, at most 24 long. */
        fun cleanId(raw: String?, fallback: String = "cam"): String = raw.orEmpty().filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(24).ifBlank { fallback }

        fun cleanName(raw: String): String = raw.filter { it >= ' ' && it != '\u007f' }.trim().take(40).ifBlank { "Camera" }
    }
}
