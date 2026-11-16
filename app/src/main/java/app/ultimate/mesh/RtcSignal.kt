package app.ultimate.mesh

import java.util.Base64

/**
 * Setting up a live video call between two of the owner's devices over the mesh. The mesh's messages are short (16 KB at most) and
 * a WebRTC description (SDP) can be longer, so it is cut into pieces and put back together, in both directions:
 *  - `rtc.offer`: the viewer sends its offer, piece by piece (`sid`, `part`, `parts`, `data`); the camera device answers each piece
 *    with `ok`, and with `complete` once the last one is in.
 *  - `rtc.answer`: the viewer then asks for the camera device's answer, piece by piece (`sid`, `part`); until it is ready the reply
 *    is `wait`. A reply of `fail` carries the reason.
 *  - `rtc.bye`: either side ends the call.
 * Pure (no network, no WebRTC), so the whole exchange is tested exactly.
 */
object RtcSignal {
    /** Characters of SDP per message (base64 adds a third), well inside the mesh line. */
    const val PIECE = 7_000
    /** A description needing more pieces than this is refused. */
    const val MAX_PIECES = 12
    /** A call that has had no word for this long is closed. */
    const val IDLE_MS = 45_000L

    fun split(sdp: String): List<String> = sdp.toByteArray(Charsets.UTF_8).let { b ->
        (0 until maxOf(1, (b.size + PIECE - 1) / PIECE)).map { i -> Base64.getEncoder().encodeToString(b.copyOfRange(i * PIECE, minOf(b.size, (i + 1) * PIECE))) }
    }

    /** One piece as the arguments of a command. */
    fun piece(sid: String, parts: List<String>, index: Int): Map<String, String> =
        mapOf("sid" to sid, "part" to index.toString(), "parts" to parts.size.toString(), "data" to parts[index])

    /** Puts a description back together from its pieces; pieces must arrive in order, and anything odd throws [IllegalArgumentException]. */
    class Assembler {
        private val pieces = ArrayList<ByteArray>()
        private var sid = ""
        private var parts = 0

        /** Adds a piece; returns the whole description when the last one is in, else null. */
        fun add(args: Map<String, String>): String? {
            val s = args["sid"].orEmpty(); val n = args["parts"]?.toIntOrNull(); val p = args["part"]?.toIntOrNull()
            val data = runCatching { Base64.getDecoder().decode(args["data"] ?: "") }.getOrNull()
            require(s.isNotBlank() && s.length <= 32 && n != null && p != null && data != null && n in 1..MAX_PIECES && p in 0 until n) { "bad piece" }
            if (p == 0) { pieces.clear(); sid = s; parts = n }
            require(s == sid && n == parts && p == pieces.size) { "piece out of order" }
            pieces += data
            if (pieces.size < parts) return null
            val whole = ByteArray(pieces.sumOf { it.size }); var at = 0
            for (b in pieces) { b.copyInto(whole, at); at += b.size }
            pieces.clear()
            return String(whole, Charsets.UTF_8)
        }
    }

    /** What the camera device is doing for one viewer's call. */
    sealed interface Call {
        data object Receiving : Call
        data object Working : Call
        data class Ready(val answer: List<String>) : Call
        data class Failed(val why: String) : Call
    }

    /**
     * The camera device's side: one call per viewer at most ([MAX_CALLS] in all), each with the offer being collected, then the answer.
     * Time is passed in, so idle calls are closed exactly. The caller does the WebRTC work between [offerComplete] and [answered].
     */
    class Calls(private val clock: () -> Long = System::currentTimeMillis) {
        private class State(var call: Call, val assembler: Assembler = Assembler(), var at: Long)
        private val calls = LinkedHashMap<String, State>()

        companion object { const val MAX_CALLS = 3 }

        /** Takes one piece of an offer. Returns the whole offer once complete (the caller then makes the answer), else null. Throws to refuse. */
        @Synchronized fun offerPiece(viewer: String, args: Map<String, String>): String? {
            prune()
            val st = calls.getOrPut(viewer) { require(calls.size < MAX_CALLS) { "this device is already showing its camera to $MAX_CALLS devices" }; State(Call.Receiving, at = clock()) }
            st.at = clock()
            val whole = st.assembler.add(args)
            if (whole != null) st.call = Call.Working
            return whole
        }

        @Synchronized fun answered(viewer: String, sdp: String) { calls[viewer]?.let { it.call = Call.Ready(split(sdp)); it.at = clock() } }
        @Synchronized fun failed(viewer: String, why: String) { calls[viewer]?.let { it.call = Call.Failed(why); it.at = clock() } }

        /** The reply to "give me piece [index] of the answer". */
        @Synchronized fun answerPiece(viewer: String, sid: String, index: Int): Map<String, String> {
            prune()
            val st = calls[viewer] ?: return mapOf("fail" to "no call from you")
            st.at = clock()
            return when (val c = st.call) {
                Call.Receiving, Call.Working -> mapOf("wait" to "1")
                is Call.Failed -> mapOf("fail" to c.why)
                is Call.Ready -> if (index in c.answer.indices) piece(sid, c.answer, index) else mapOf("fail" to "no such piece")
            }
        }

        /** Ends [viewer]'s call; returns whether there was one. */
        @Synchronized fun bye(viewer: String): Boolean = calls.remove(viewer) != null
        @Synchronized fun active(): Set<String> { prune(); return calls.keys.toSet() }

        /** Calls that have gone quiet, to be closed by the caller. */
        @Synchronized fun expired(): List<String> { val now = clock(); return calls.filter { it.value.call !is Call.Ready && now - it.value.at > IDLE_MS }.keys.toList() }
        /** A call still being set up that has been quiet for twice the limit is forgotten; one that is connected stays until it ends. */
        private fun prune() { val now = clock(); calls.entries.removeAll { it.value.call !is Call.Ready && now - it.value.at > IDLE_MS * 2 } }
    }
}
