package app.ultimate.send

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Makes a self-signed X.509 certificate for the phone's own https page, so browsers allow screen sharing and desktop
 * notifications. Written as plain DER because Android has no public certificate builder; a test parses it back.
 */
object SelfSigned {
    /** One DER element: tag, length, contents. */
    fun der(tag: Int, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = body.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> { out.write(0x81); out.write(n) }
            n < 0x10000 -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xFF) }
            else -> { out.write(0x83); out.write(n shr 16); out.write((n shr 8) and 0xFF); out.write(n and 0xFF) }
        }
        out.write(body)
        return out.toByteArray()
    }
    private fun seq(vararg parts: ByteArray) = der(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
    private fun set(vararg parts: ByteArray) = der(0x31, parts.fold(ByteArray(0)) { a, b -> a + b })
    private fun int(v: BigInteger) = der(0x02, v.toByteArray())
    private fun oid(dotted: String): ByteArray {
        val p = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((p[0] * 40 + p[1]).toInt())
        p.drop(2).forEach { v ->
            val bytes = mutableListOf<Int>(); var x = v
            do { bytes.add(0, (x and 0x7F).toInt()); x = x shr 7 } while (x > 0)
            bytes.forEachIndexed { i, b -> out.write(if (i < bytes.lastIndex) b or 0x80 else b) }
        }
        return der(0x06, out.toByteArray())
    }
    private fun time(d: Date): ByteArray {
        val utc = TimeZone.getTimeZone("UTC")
        val year = java.util.Calendar.getInstance(utc).apply { time = d }.get(java.util.Calendar.YEAR)
        return if (year < 2050) der(0x17, SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = utc }.format(d).toByteArray())
        else der(0x18, SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US).apply { timeZone = utc }.format(d).toByteArray())
    }
    private fun name(cn: String) = seq(set(seq(oid("2.5.4.3"), der(0x0C, cn.toByteArray()))))

    /** A new RSA key and a certificate for [ip] (and ultimate.local), valid for [days]. Returns (key pair, DER certificate). */
    fun create(ip: String?, days: Int = 825, now: Date = Date()): Pair<KeyPair, ByteArray> {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
        val algorithm = seq(oid("1.2.840.113549.1.1.11"), byteArrayOf(0x05, 0x00))
        val names = buildList {
            ip?.let { a -> runCatching { java.net.InetAddress.getByName(a).address }.getOrNull()?.let { add(der(0x87, it)) } }
            add(der(0x82, "ultimate.local".toByteArray()))
        }
        val san = seq(oid("2.5.29.17"), der(0x04, seq(*names.toTypedArray())))
        val tbs = seq(
            der(0xA0, int(BigInteger.valueOf(2))),
            int(BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE)),
            algorithm,
            name("Ultimate on your phone"),
            seq(time(Date(now.time - 86_400_000L)), time(Date(now.time + days * 86_400_000L))),
            name("Ultimate on your phone"),
            keys.public.encoded,
            der(0xA3, seq(san)),
        )
        val signature = Signature.getInstance("SHA256withRSA").apply { initSign(keys.private); update(tbs) }.sign()
        return keys to seq(tbs, algorithm, der(0x03, byteArrayOf(0) + signature))
    }
}
