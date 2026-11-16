package app.ultimate.mesh

import java.util.Base64

/**
 * How one device shows another's camera over the mesh. The mesh carries short messages (16 KB at most), so a picture is cut into
 * small parts that the viewer asks for one by one and puts back together. The viewer always pulls ("give me the newest picture
 * if it is not the one I have"), so a slow connection simply shows older pictures instead of building up a queue, and a device
 * that nobody is watching does no work for anyone. Pure, so every step is tested without a camera or a network.
 *
 * A request carries `have` (the newest picture the viewer already shows), `part` (which piece it wants) and, for pieces after the
 * first, `seq` (which picture it is putting together). The reply says what came of that:
 *  - `none`: no picture yet.   - `same`: nothing newer than `have`.
 *  - `seq`, `parts`, `part`, `data`: one piece (data is base64) of picture `seq`.
 *  - `gone`: that picture has been replaced; start again from the first piece.
 */
object CamFrames {
    /** Bytes of picture per message: about 10.7 KB once encoded, well under the mesh's 16 KB line. */
    const val CHUNK = 8_000
    /** A picture needing more pieces than this is too big for a live view (the sender shrinks it). */
    const val MAX_PARTS = 8

    /** The newest picture on the device that has the camera, numbered so a viewer can tell a new one from the one it has. */
    class Latest {
        private var seq = 0
        private var jpeg: ByteArray? = null

        @Synchronized fun put(bytes: ByteArray): Int { seq++; jpeg = bytes; return seq }
        @Synchronized fun get(): Pair<Int, ByteArray>? = jpeg?.let { seq to it }
        @Synchronized fun clear() { jpeg = null }
    }

    /** The answer to one request, from the device that has the camera. */
    fun reply(latest: Pair<Int, ByteArray>?, have: Int, part: Int, wantSeq: Int): Map<String, String> {
        val (seq, bytes) = latest ?: return mapOf("none" to "1")
        val parts = (bytes.size + CHUNK - 1) / CHUNK
        if (parts > MAX_PARTS) return mapOf("none" to "1", "big" to "1")
        if (part == 0 && seq == have) return mapOf("same" to "1", "seq" to seq.toString())
        if (part > 0 && seq != wantSeq) return mapOf("gone" to "1", "seq" to seq.toString())
        if (part !in 0 until parts) return mapOf("gone" to "1", "seq" to seq.toString())
        val piece = bytes.copyOfRange(part * CHUNK, minOf(bytes.size, (part + 1) * CHUNK))
        return mapOf("seq" to seq.toString(), "parts" to parts.toString(), "part" to part.toString(), "data" to Base64.getEncoder().encodeToString(piece))
    }

    sealed interface Step {
        data object Same : Step
        data object None : Step
        data object Gone : Step
        /** Ask for piece [next] of picture [seq]. */
        data class More(val next: Int, val seq: Int) : Step
        data class Done(val seq: Int, val jpeg: ByteArray) : Step
    }

    /** Puts a picture back together from the replies, one at a time. Anything unexpected just ends in [Step.Gone] and the viewer starts again. */
    class Assembler {
        private var seq = -1
        private var parts = 0
        private val got = ArrayList<ByteArray>()

        fun accept(r: Map<String, String>): Step {
            if (r["none"] == "1") return Step.None
            if (r["same"] == "1") return Step.Same
            if (r["gone"] == "1") { reset(); return Step.Gone }
            val s = r["seq"]?.toIntOrNull(); val n = r["parts"]?.toIntOrNull(); val p = r["part"]?.toIntOrNull()
            val data = runCatching { Base64.getDecoder().decode(r["data"] ?: return Step.Gone) }.getOrNull()
            if (s == null || n == null || p == null || data == null || n !in 1..MAX_PARTS || p !in 0 until n) { reset(); return Step.Gone }
            if (p == 0) { seq = s; parts = n; got.clear() }
            else if (s != seq || n != parts || p != got.size) { reset(); return Step.Gone }
            got += data
            if (got.size < parts) return Step.More(got.size, seq)
            val whole = ByteArray(got.sumOf { it.size }); var at = 0
            for (g in got) { g.copyInto(whole, at); at += g.size }
            reset()
            return Step.Done(s, whole)
        }

        private fun reset() { seq = -1; parts = 0; got.clear() }
    }
}
