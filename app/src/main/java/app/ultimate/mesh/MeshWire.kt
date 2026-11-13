package app.ultimate.mesh

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Octopus Mesh wire format: one sealed line per frame.
 *
 * `om2|base64(nonce)|base64(AES-256-GCM(TYPE|fromId|seq|sentAtMs|payload))`
 *
 * Every frame is encrypted and authenticated with a key derived from the pairing code both devices typed, so what crosses the
 * network (sensor recordings, device names, commands) is unreadable and unforgeable to anyone without the code. A frame that does
 * not decrypt, whose timestamp is outside [MAX_SKEW_MS], or whose (from, seq) pair was already seen is refused. The first version
 * (`om1`) only signed frames and left them readable; it is no longer accepted.
 *
 * What this does NOT give: forward secrecy (someone who records traffic and later learns the code can read it), or protection
 * against a short code being guessed offline from one captured frame. The code is stretched with PBKDF2 to slow guessing; a
 * longer code is the real defence, and the screen says so.
 */
object MeshWire {
    const val VERSION = "om2"
    const val MAX_SKEW_MS = 60_000L
    const val MAX_LINE = 16 * 1024
    private const val PBKDF2_ROUNDS = 120_000

    enum class Type { HELLO, BEAT, TASK, RESULT, ERROR, CMD, ACK }

    data class Frame(val type: Type, val from: String, val seq: Long, val sentAt: Long, val payload: Map<String, String>)

    /** PBKDF2 over the pairing code. The salt is fixed so every device with the same code derives the same key. */
    fun keyFromCode(code: String): ByteArray {
        require(code.length >= 6) { "Pairing code must be at least 6 characters" }
        val spec = PBEKeySpec(code.toCharArray(), "octopus-mesh-v2".toByteArray(), PBKDF2_ROUNDS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    /** Rough strength of a pairing code, for the screen: bits of guessing work, assuming it was chosen at random from the kinds of character it uses. */
    fun codeBits(code: String): Int {
        var alphabet = 0
        if (code.any { it.isDigit() }) alphabet += 10
        if (code.any { it.isLowerCase() }) alphabet += 26
        if (code.any { it.isUpperCase() }) alphabet += 26
        if (code.any { !it.isLetterOrDigit() }) alphabet += 20
        return if (alphabet == 0) 0 else (code.length * (Math.log(alphabet.toDouble()) / Math.log(2.0))).toInt()
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

    private val random = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    /** The sealing key is separate from the master key, which is also used for fingerprints and Bluetooth identifiers. */
    private fun sealKey(key: ByteArray) = SecretKeySpec(Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal("om2-seal".toByteArray()), "AES")

    fun encode(f: Frame, key: ByteArray): String {
        require(!f.from.contains('|'))
        val body = listOf(f.type.name, f.from, f.seq.toString(), f.sentAt.toString(), encodePayload(f.payload)).joinToString("|")
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, sealKey(key), GCMParameterSpec(128, nonce)); updateAAD(VERSION.toByteArray()) }
        return VERSION + "|" + b64.encodeToString(nonce) + "|" + b64.encodeToString(c.doFinal(body.toByteArray()))
    }

    /** Opens and authenticates a frame; throws [SecurityException] for anything not sealed with this mesh's key. */
    fun decode(line: String, key: ByteArray, now: Long = System.currentTimeMillis()): Frame {
        if (line.length > MAX_LINE) throw SecurityException("frame too long")
        val parts = line.split('|')
        if (parts.size != 3 || parts[0] != VERSION) throw SecurityException("unknown version")
        val body = try {
            val nonce = Base64.getUrlDecoder().decode(parts[1])
            if (nonce.size != 12) throw SecurityException("bad nonce")
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, sealKey(key), GCMParameterSpec(128, nonce)); updateAAD(VERSION.toByteArray()) }
            String(c.doFinal(Base64.getUrlDecoder().decode(parts[2])))
        } catch (e: SecurityException) { throw e } catch (e: Exception) { throw SecurityException("not sealed with this mesh's key") }
        val f = body.split('|', limit = 5)
        if (f.size != 5) throw SecurityException("malformed frame")
        val sentAt = f[3].toLongOrNull() ?: throw SecurityException("malformed frame")
        if (kotlin.math.abs(now - sentAt) > MAX_SKEW_MS) throw SecurityException("stale frame")
        val type = runCatching { Type.valueOf(f[0]) }.getOrElse { throw SecurityException("unknown frame type") }
        return Frame(type, f[1], f[2].toLongOrNull() ?: throw SecurityException("malformed frame"), sentAt, runCatching { decodePayload(f[4]) }.getOrElse { throw SecurityException("malformed payload") })
    }
}

/** Remembers recent (sender, seq) pairs so a captured frame cannot be replayed inside the skew window. */
class ReplayGuard(private val capacity: Int = 4096) {
    private val seen = object : LinkedHashMap<String, Unit>(capacity, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > capacity
    }
    @Synchronized fun firstTime(from: String, seq: Long): Boolean = seen.put("$from#$seq", Unit) == null
}
