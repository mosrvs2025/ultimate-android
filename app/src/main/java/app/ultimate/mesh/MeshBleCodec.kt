package app.ultimate.mesh

import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Bluetooth presence for the mesh, the parts that need no Bluetooth hardware to check.
 *
 * Each phone advertises a service UUID derived from the pairing key plus a 4-byte tag derived from its own id. Only
 * a phone with the same pairing code can compute that UUID, so it is the only kind that recognises (or filters for)
 * the advertisement; to anyone else it is an opaque identifier. The tag lets a phone match a sighting to a device it
 * is already talking to over Wi-Fi.
 *
 * Bluetooth signal strength is a weak ruler: bodies, walls, cases and each phone's antenna move it by 10 dB or more,
 * so it only ever sorts a device into a coarse band ([Band]), is shown as an ESTIMATE, and is never turned into metres.
 * For metres the mesh uses sound ([PeerRange]).
 */
object MeshBleCodec {
    private fun hmac(key: ByteArray, text: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(text.toByteArray())

    fun serviceUuid(key: ByteArray): UUID {
        val b = hmac(key, "om-ble-uuid")
        b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte() // version 4 layout, so every stack accepts it
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        var hi = 0L; var lo = 0L
        for (i in 0 until 8) hi = (hi shl 8) or (b[i].toLong() and 0xff)
        for (i in 8 until 16) lo = (lo shl 8) or (b[i].toLong() and 0xff)
        return UUID(hi, lo)
    }

    fun tag(key: ByteArray, id: String): ByteArray = hmac(key, "om-ble-tag:$id").copyOf(4)
    fun tagHex(key: ByteArray, id: String): String = tag(key, id).joinToString("") { "%02x".format(it) }

    enum class Band(val label: String) {
        Touching("right next to this phone"), Near("within arm's reach"), Room("in the same room"), Far("far, or behind walls");
    }

    /** Coarse bands from a smoothed signal strength in dBm. */
    fun band(rssi: Double): Band = when {
        rssi >= -48 -> Band.Touching
        rssi >= -62 -> Band.Near
        rssi >= -80 -> Band.Room
        else -> Band.Far
    }

    /** Smooths the jumpy readings Bluetooth gives (each advert can differ by 10 dB) so the band does not flicker. */
    class Smoother(private val alpha: Double = 0.25) {
        private var value: Double? = null
        fun add(rssi: Int): Double { val v = value; return (if (v == null) rssi.toDouble() else v + alpha * (rssi - v)).also { value = it } }
    }
}
