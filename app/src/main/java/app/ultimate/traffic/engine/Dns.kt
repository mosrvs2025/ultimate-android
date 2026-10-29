package app.ultimate.traffic.engine

/** Just enough DNS to learn which names the apps ask for, and to refuse the ones the user blocks. */
object Dns {
    const val TYPE_A = 1
    const val TYPE_CNAME = 5
    const val TYPE_AAAA = 28

    data class Query(val id: Int, val name: String, val type: Int)
    data class Response(val id: Int, val name: String, val rcode: Int, val addresses: List<Int>)

    fun typeName(type: Int) = when (type) { TYPE_A -> "A"; TYPE_AAAA -> "AAAA"; TYPE_CNAME -> "CNAME"; 15 -> "MX"; 16 -> "TXT"; 33 -> "SRV"; 65 -> "HTTPS"; else -> "type $type" }

    fun parseQuery(d: ByteArray, off: Int, len: Int): Query? {
        if (len < 12) return null
        val flags = u16(d, off + 2)
        if (flags and 0x8000 != 0 || u16(d, off + 4) < 1) return null // a response, or no question
        val (name, next) = readName(d, off, len, off + 12) ?: return null
        if (next + 4 > off + len) return null
        return Query(u16(d, off), name, u16(d, next))
    }

    /** The first question's name plus every IPv4 answer; CNAMEs are skipped over so the address maps back to the name that was asked for. */
    fun parseResponse(d: ByteArray, off: Int, len: Int): Response? {
        if (len < 12) return null
        val flags = u16(d, off + 2)
        if (flags and 0x8000 == 0) return null
        val qd = u16(d, off + 4)
        val an = u16(d, off + 6)
        var pos = off + 12
        var name = ""
        repeat(qd) { i ->
            val (n, next) = readName(d, off, len, pos) ?: return null
            if (i == 0) name = n
            pos = next + 4
        }
        val addresses = ArrayList<Int>()
        repeat(an) {
            val (_, next) = readName(d, off, len, pos) ?: return@repeat
            if (next + 10 > off + len) return Response(u16(d, off), name, flags and 0xF, addresses)
            val type = u16(d, next)
            val rdlen = u16(d, next + 8)
            val rdata = next + 10
            if (rdata + rdlen > off + len) return Response(u16(d, off), name, flags and 0xF, addresses)
            if (type == TYPE_A && rdlen == 4) {
                addresses += ((d[rdata].toInt() and 255) shl 24) or ((d[rdata + 1].toInt() and 255) shl 16) or ((d[rdata + 2].toInt() and 255) shl 8) or (d[rdata + 3].toInt() and 255)
            }
            pos = rdata + rdlen
        }
        return Response(u16(d, off), name, flags and 0xF, addresses)
    }

    /** A reply that says the name does not exist (NXDOMAIN), built from the query so the app's resolver accepts it. */
    fun nxdomain(d: ByteArray, off: Int, len: Int): ByteArray {
        val out = d.copyOfRange(off, off + len)
        out[2] = (0x81).toByte() // QR, recursion desired
        out[3] = (0x83).toByte() // recursion available, RCODE 3
        out[6] = 0; out[7] = 0; out[8] = 0; out[9] = 0; out[10] = 0; out[11] = 0 // no answers, authority or additional records
        // Keep only the header and the question.
        val (_, next) = readName(out, 0, out.size, 12) ?: return out
        return out.copyOf(minOf(out.size, next + 4))
    }

    /** True if [host] is [rule] or a subdomain of it. */
    fun matches(host: String, rule: String): Boolean = host == rule || host.endsWith(".$rule")

    private fun readName(d: ByteArray, base: Int, len: Int, start: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var pos = start
        var next = -1
        var hops = 0
        while (true) {
            if (pos >= base + len) return null
            val l = d[pos].toInt() and 255
            when {
                l == 0 -> { pos++; break }
                l and 0xC0 == 0xC0 -> {
                    if (pos + 1 >= base + len || ++hops > 10) return null
                    if (next < 0) next = pos + 2
                    pos = base + (((l and 0x3F) shl 8) or (d[pos + 1].toInt() and 255))
                }
                else -> {
                    if (pos + 1 + l > base + len) return null
                    if (sb.isNotEmpty()) sb.append('.')
                    for (i in 1..l) sb.append((d[pos + i].toInt() and 255).toChar())
                    pos += 1 + l
                }
            }
        }
        return sb.toString().lowercase() to (if (next >= 0) next else pos)
    }

    private fun u16(d: ByteArray, at: Int) = ((d[at].toInt() and 255) shl 8) or (d[at + 1].toInt() and 255)
}

/** Finds a server name in the first bytes an app sends on a connection. */
object Hello {
    data class Seen(val host: String, val viaTls: Boolean, val request: String?)

    fun inspect(d: ByteArray, off: Int, len: Int): Seen? = tlsServerName(d, off, len)?.let { Seen(it, true, null) } ?: httpRequest(d, off, len)

    /** The SNI from a TLS ClientHello, which is sent in the clear even for HTTPS. */
    fun tlsServerName(d: ByteArray, off: Int, len: Int): String? {
        try {
            if (len < 45 || d[off].toInt() != 0x16 || d[off + 5].toInt() != 0x01) return null
            var p = off + 5 + 4 + 2 + 32 // record header, handshake header, version, random
            val end = off + len
            p += 1 + (d[p].toInt() and 255) // session id
            p += 2 + u16(d, p) // cipher suites
            p += 1 + (d[p].toInt() and 255) // compression
            if (p + 2 > end) return null
            val extEnd = minOf(end, p + 2 + u16(d, p))
            p += 2
            while (p + 4 <= extEnd) {
                val type = u16(d, p)
                val size = u16(d, p + 2)
                if (type == 0 && p + 4 + size <= extEnd) {
                    val listEnd = p + 4 + size
                    var q = p + 4 + 2
                    while (q + 3 <= listEnd) {
                        val nameType = d[q].toInt()
                        val nameLen = u16(d, q + 1)
                        if (nameType == 0 && q + 3 + nameLen <= listEnd) return String(d, q + 3, nameLen, Charsets.US_ASCII).lowercase()
                        q += 3 + nameLen
                    }
                }
                p += 4 + size
            }
        } catch (_: IndexOutOfBoundsException) {
        }
        return null
    }

    /** The host and request line of an unencrypted HTTP request. */
    fun httpRequest(d: ByteArray, off: Int, len: Int): Seen? {
        val head = String(d, off, minOf(len, 2048), Charsets.ISO_8859_1)
        val first = head.lineSequence().firstOrNull() ?: return null
        val methods = listOf("GET ", "POST ", "PUT ", "HEAD ", "DELETE ", "PATCH ", "OPTIONS ")
        if (methods.none { first.startsWith(it) }) return null
        val host = head.lineSequence().firstOrNull { it.startsWith("Host:", ignoreCase = true) }?.substringAfter(':')?.trim()?.substringBefore(':')?.lowercase() ?: return null
        return Seen(host, false, first.take(120))
    }

    private fun u16(d: ByteArray, at: Int) = ((d[at].toInt() and 255) shl 8) or (d[at + 1].toInt() and 255)
}
