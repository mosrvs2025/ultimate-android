package app.ultimate.mesh

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Octopus Mesh wire format: one authenticated line per frame.
 *
 * `om1|TYPE|fromId|seq|sentAtMs|base64(payload)|hmacSha256Hex`
 *
 * Every frame is signed with a key derived from the pairing code both devices typed. A frame with a bad signature,
 * a timestamp outside [MAX_SKEW_MS], or a (from, seq) pair already seen is refused. This authenticates trusted
 * devices on a LAN; it does not encrypt payloads, so only non-secret work (benchmarks, status) travels over it.
 */
object MeshWire {
    const val VERSION = "om1"
    const val MAX_SKEW_MS = 60_000L
    const val MAX_LINE = 16 * 1024

    enum class Type { HELLO, BEAT, TASK, RESULT, ERROR }

    data class Frame(val type: Type, val from: String, val seq: Long, val sentAt: Long, val payload: Map<String, String>)

    /** PBKDF2 over the pairing code. The salt is fixed so every device with the same code derives the same key. */
    fun keyFromCode(code: String): ByteArray {
        require(code.length >= 6) { "Pairing code must be at least 6 characters" }
        val spec = PBEKeySpec(code.toCharArray(), "octopus-mesh-v1".toByteArray(), 20_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    /** Short public fingerprint of the key so people can compare devices without revealing the code. */
    fun fingerprint(key: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(key).take(4).joinToString("") { "%02x".format(it) }

    fun encodePayload(p: Map<String, String>): String =
        p.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }

    fun decodePayload(s: String): Map<String, String> =
        if (s.isEmpty()) emptyMap() else s.split('&').associate {
            val i = it.indexOf('=')
            require(i > 0) { "bad payload" }
            URLDecoder.decode(it.substring(0, i), "UTF-8") to URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }

    fun encode(f: Frame, key: ByteArray): String {
        require(!f.from.contains('|'))
        val body = listOf(VERSION, f.type.name, f.from, f.seq.toString(), f.sentAt.toString(),
            Base64.getEncoder().encodeToString(encodePayload(f.payload).toByteArray())).joinToString("|")
        return body + "|" + mac(key, body)
    }

    /** Parses and authenticates; throws [SecurityException] for anything not signed by this mesh's key. */
    fun decode(line: String, key: ByteArray, now: Long = System.currentTimeMillis()): Frame {
        if (line.length > MAX_LINE) throw SecurityException("frame too long")
        val cut = line.lastIndexOf('|')
        if (cut < 0) throw SecurityException("unsigned frame")
        val body = line.substring(0, cut)
        if (!MessageDigest.isEqual(mac(key, body).toByteArray(), line.substring(cut + 1).toByteArray())) throw SecurityException("bad signature")
        val parts = body.split('|')
        if (parts.size != 6 || parts[0] != VERSION) throw SecurityException("unknown version")
        val sentAt = parts[4].toLong()
        if (kotlin.math.abs(now - sentAt) > MAX_SKEW_MS) throw SecurityException("stale frame")
        return Frame(Type.valueOf(parts[1]), parts[2], parts[3].toLong(), sentAt,
            decodePayload(String(Base64.getDecoder().decode(parts[5]))))
    }

    private fun mac(key: ByteArray, body: String): String {
        val m = Mac.getInstance("HmacSHA256"); m.init(SecretKeySpec(key, "HmacSHA256"))
        return m.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** Remembers recent (sender, seq) pairs so a captured frame cannot be replayed inside the skew window. */
class ReplayGuard(private val capacity: Int = 4096) {
    private val seen = object : LinkedHashMap<String, Unit>(capacity, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > capacity
    }
    @Synchronized fun firstTime(from: String, seq: Long): Boolean = seen.put("$from#$seq", Unit) == null
}
