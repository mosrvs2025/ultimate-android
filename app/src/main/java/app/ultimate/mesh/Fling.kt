package app.ultimate.mesh

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * Throwing an app, a link or a file from this phone to another of the owner's devices over the mesh. Everything rides the
 * mesh's own encrypted commands (each frame sealed with the pairing key), so nothing passes through any server.
 *
 * This file is the part that can be tested without a phone: how a file is cut into pieces, put back together and checked,
 * what a received file may be called, and what counts as a link.
 */
object Fling {
    /** The largest file one fling carries. Big enough for photos, short videos and documents; anything larger belongs in Files. */
    const val MAX_BYTES = 25L * 1024 * 1024
    /** Raw bytes per piece. Base64 makes each frame about 64 KB, which the mesh carries in well under its command timeout. */
    const val CHUNK = 48 * 1024

    fun pieces(size: Long): Int = if (size <= 0) 1 else ((size + CHUNK - 1) / CHUNK).toInt()

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)

    /** Why this file cannot be flung, or null when it can. */
    fun refuse(size: Long): String? = when {
        size < 0 -> "the size of that file could not be read"
        size > MAX_BYTES -> "that file is ${size / (1024 * 1024)} MB; a fling carries up to ${MAX_BYTES / (1024 * 1024)} MB"
        else -> null
    }

    /**
     * A name that is safe to save under: no folders (so a sender can never write outside Downloads/Ultimate), no control or
     * reserved characters, not hidden, and not absurdly long. The extension is kept so the file still opens in the right app.
     */
    fun safeName(raw: String?): String {
        val base = (raw ?: "").substringAfterLast('/').substringAfterLast('\\')
        val clean = base.filter { it >= ' ' && it !in "<>:\"|?*" }.trim().trimStart('.').trim()
        if (clean.isEmpty()) return "file"
        if (clean.length <= 80) return clean
        val dot = clean.lastIndexOf('.')
        val ext = if (dot > 0 && clean.length - dot <= 10) clean.substring(dot) else ""
        return clean.take(80 - ext.length) + ext
    }

    /** The first web link in [text], or null. Only http and https: a fling never opens anything else on its own. */
    fun link(text: String): String? = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE).find(text)?.value?.trimEnd('.', ',', ')', ';', '!', '?')

    /**
     * One file arriving. Pieces must come in order (the sender waits for each to be acknowledged before the next), so a
     * missing or repeated piece is caught at once instead of producing a corrupt file. [finish] checks the length and the
     * SHA-256 the sender announced before handing the bytes back.
     */
    class Inbox(val id: String, val name: String, val size: Long, val sha: String, val mime: String) {
        private val out = ByteArrayOutputStream(size.coerceIn(0, MAX_BYTES).toInt())
        var next = 0; private set
        val received: Long get() = out.size().toLong()

        init { refuse(size)?.let { throw IllegalArgumentException(it) } }

        fun accept(index: Int, data: String) {
            require(index == next) { "piece $index arrived when $next was expected" }
            val bytes = decode(data)
            require(received + bytes.size <= size) { "more data arrived than the file's size" }
            out.write(bytes); next++
        }

        fun finish(): ByteArray {
            val bytes = out.toByteArray()
            require(bytes.size.toLong() == size) { "the file arrived incomplete (${bytes.size} of $size bytes)" }
            require(sha256(bytes).equals(sha, ignoreCase = true)) { "the file arrived damaged (its checksum does not match)" }
            return bytes
        }
    }

    /** The commands, in order, that carry [bytes] to another device. Pure, so the whole exchange is testable end to end. */
    fun frames(id: String, name: String, mime: String, bytes: ByteArray): List<Pair<String, Map<String, String>>> {
        refuse(bytes.size.toLong())?.let { throw IllegalArgumentException(it) }
        val n = pieces(bytes.size.toLong())
        return buildList {
            add(Cmd.FLING_BEGIN to mapOf("id" to id, "name" to safeName(name), "size" to bytes.size.toString(), "sha" to sha256(bytes), "mime" to mime, "pieces" to n.toString()))
            for (i in 0 until n) {
                val from = i * CHUNK; val to = minOf(bytes.size, from + CHUNK)
                add(Cmd.FLING_CHUNK to mapOf("id" to id, "i" to i.toString(), "data" to encode(bytes.copyOfRange(from, to))))
            }
            add(Cmd.FLING_END to mapOf("id" to id))
        }
    }
}

/**
 * The bridge between screens that exist in every edition (the home screen) and the mesh, which only Ultimate+ has.
 * The mesh registers itself here when it is created; with no provider, nothing offers to fling.
 */
object DeviceFling {
    data class Target(val id: String, val name: String, val kind: String, val near: Boolean)

    interface Provider {
        /** The owner's devices connected right now. Empty when the mesh is off. */
        fun targets(): List<Target>
        /** Each returns null on success, or why it failed in words for the screen. */
        suspend fun app(targetId: String, pkg: String, label: String): String?
        suspend fun text(targetId: String, text: String): String?
        suspend fun file(targetId: String, name: String, mime: String, bytes: ByteArray, progress: (Float) -> Unit = {}): String?
    }

    @Volatile var provider: Provider? = null
    fun targets(): List<Target> = runCatching { provider?.targets() }.getOrNull().orEmpty()
}
