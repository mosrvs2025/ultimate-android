package app.ultimate.traffic.engine

import java.net.InetAddress

/** TCP flag bits. */
object Tcp {
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10
    const val MASK = 0xFFFFFFFFL

    /** True if sequence number [a] is after [b], allowing for wrap-around. */
    fun after(a: Long, b: Long): Boolean = ((a - b) and MASK).let { it in 1..0x7FFFFFFFL }
}

/** One IPv4 address as an int, the form the engine keys flows by. */
fun ip4(a: Int, b: Int, c: Int, d: Int): Int = (a shl 24) or (b shl 16) or (c shl 8) or d

fun Int.toInetAddress(): InetAddress = InetAddress.getByAddress(byteArrayOf((this ushr 24).toByte(), (this ushr 16).toByte(), (this ushr 8).toByte(), this.toByte()))

fun Int.ipString(): String = "${this ushr 24 and 255}.${this ushr 16 and 255}.${this ushr 8 and 255}.${this and 255}"

fun InetAddress.toIp4(): Int? = address.takeIf { it.size == 4 }?.let { ((it[0].toInt() and 255) shl 24) or ((it[1].toInt() and 255) shl 16) or ((it[2].toInt() and 255) shl 8) or (it[3].toInt() and 255) }

/** A parsed IPv4 packet. Offsets point into [data], which is shared, so copy before keeping it. */
class Ip4Packet(val data: ByteArray, val length: Int) {
    val headerLength: Int = (data[0].toInt() and 0x0F) * 4
    val protocol: Int = data[9].toInt() and 255
    val src: Int = int(12)
    val dst: Int = int(16)
    val totalLength: Int = ((data[2].toInt() and 255) shl 8) or (data[3].toInt() and 255)

    /** True for fragments, which the relay does not reassemble. */
    val fragmented: Boolean = (((data[6].toInt() and 0x1F) shl 8) or (data[7].toInt() and 255)) != 0 || (data[6].toInt() and 0x20) != 0

    val payloadOffset: Int get() = headerLength
    val payloadLength: Int get() = minOf(totalLength, length) - headerLength

    private fun int(at: Int) = ((data[at].toInt() and 255) shl 24) or ((data[at + 1].toInt() and 255) shl 16) or ((data[at + 2].toInt() and 255) shl 8) or (data[at + 3].toInt() and 255)

    companion object {
        const val ICMP = 1
        const val TCP = 6
        const val UDP = 17

        /** Null unless this is a well-formed IPv4 packet. */
        fun parse(data: ByteArray, length: Int): Ip4Packet? {
            if (length < 20 || (data[0].toInt() ushr 4) != 4) return null
            val ihl = (data[0].toInt() and 0x0F) * 4
            if (ihl < 20 || length < ihl) return null
            val total = ((data[2].toInt() and 255) shl 8) or (data[3].toInt() and 255)
            if (total < ihl || total > length) return null
            return Ip4Packet(data, length)
        }
    }
}

class TcpSegment(val ip: Ip4Packet) {
    private val d = ip.data
    private val o = ip.payloadOffset
    val srcPort: Int = u16(o)
    val dstPort: Int = u16(o + 2)
    val seq: Long = u32(o + 4)
    val ack: Long = u32(o + 8)
    private val dataOffset = ((d[o + 12].toInt() and 0xF0) ushr 4) * 4
    val flags: Int = d[o + 13].toInt() and 0x3F
    val window: Int = u16(o + 14)
    val payloadOffset: Int = o + dataOffset
    val payloadLength: Int = ip.payloadOffset + ip.payloadLength - payloadOffset
    val syn get() = flags and Tcp.SYN != 0
    val fin get() = flags and Tcp.FIN != 0
    val rst get() = flags and Tcp.RST != 0
    val hasAck get() = flags and Tcp.ACK != 0

    /** The sender's maximum segment size option, if present. */
    val mss: Int? = run {
        var i = o + 20
        val end = o + dataOffset
        var found: Int? = null
        while (i < end && i < d.size) {
            when (val kind = d[i].toInt() and 255) {
                0 -> break
                1 -> i++
                else -> {
                    val len = if (i + 1 < end) d[i + 1].toInt() and 255 else break
                    if (len < 2) break
                    if (kind == 2 && len == 4 && i + 3 < d.size) found = u16(i + 2)
                    i += len
                }
            }
        }
        found
    }

    private fun u16(at: Int) = ((d[at].toInt() and 255) shl 8) or (d[at + 1].toInt() and 255)
    private fun u32(at: Int) = ((d[at].toLong() and 255) shl 24) or ((d[at + 1].toLong() and 255) shl 16) or ((d[at + 2].toLong() and 255) shl 8) or (d[at + 3].toLong() and 255)

    companion object {
        /** Null unless the IP payload holds a complete TCP header. */
        fun parse(ip: Ip4Packet): TcpSegment? {
            if (ip.protocol != Ip4Packet.TCP || ip.payloadLength < 20) return null
            val off = ((ip.data[ip.payloadOffset + 12].toInt() and 0xF0) ushr 4) * 4
            if (off < 20 || off > ip.payloadLength) return null
            return TcpSegment(ip)
        }
    }
}

class UdpDatagram(val ip: Ip4Packet) {
    private val d = ip.data
    private val o = ip.payloadOffset
    val srcPort: Int = ((d[o].toInt() and 255) shl 8) or (d[o + 1].toInt() and 255)
    val dstPort: Int = ((d[o + 2].toInt() and 255) shl 8) or (d[o + 3].toInt() and 255)
    val payloadOffset: Int = o + 8
    val payloadLength: Int = ip.payloadLength - 8

    companion object {
        fun parse(ip: Ip4Packet): UdpDatagram? = if (ip.protocol == Ip4Packet.UDP && ip.payloadLength >= 8) UdpDatagram(ip) else null
    }
}

/** Builds IPv4 packets with correct checksums. */
object PacketBuilder {
    private var nextId = 1

    @Synchronized private fun id() = nextId++ and 0xFFFF

    fun tcp(
        src: Int, dst: Int, srcPort: Int, dstPort: Int, seq: Long, ack: Long, flags: Int, window: Int,
        payload: ByteArray = EMPTY, payloadOffset: Int = 0, payloadLength: Int = payload.size, mss: Int? = null,
    ): ByteArray {
        val tcpHeader = if (mss != null) 24 else 20
        val total = 20 + tcpHeader + payloadLength
        val p = ByteArray(total)
        ipHeader(p, total, Ip4Packet.TCP, src, dst)
        val o = 20
        put16(p, o, srcPort); put16(p, o + 2, dstPort)
        put32(p, o + 4, seq and Tcp.MASK); put32(p, o + 8, ack and Tcp.MASK)
        p[o + 12] = ((tcpHeader / 4) shl 4).toByte()
        p[o + 13] = flags.toByte()
        put16(p, o + 14, window.coerceIn(0, 65535))
        if (mss != null) { p[o + 20] = 2; p[o + 21] = 4; put16(p, o + 22, mss) }
        System.arraycopy(payload, payloadOffset, p, o + tcpHeader, payloadLength)
        put16(p, o + 16, transportChecksum(p, o, tcpHeader + payloadLength, src, dst, Ip4Packet.TCP))
        return p
    }

    fun udp(src: Int, dst: Int, srcPort: Int, dstPort: Int, payload: ByteArray, offset: Int = 0, length: Int = payload.size): ByteArray {
        val total = 20 + 8 + length
        val p = ByteArray(total)
        ipHeader(p, total, Ip4Packet.UDP, src, dst)
        put16(p, 20, srcPort); put16(p, 22, dstPort); put16(p, 24, 8 + length)
        System.arraycopy(payload, offset, p, 28, length)
        var sum = transportChecksum(p, 20, 8 + length, src, dst, Ip4Packet.UDP)
        if (sum == 0) sum = 0xFFFF
        put16(p, 26, sum)
        return p
    }

    /** ICMP "port unreachable", the polite way to refuse a UDP datagram so the sender gives up at once instead of timing out. */
    fun icmpPortUnreachable(original: Ip4Packet, from: Int): ByteArray {
        val quoted = minOf(original.length, original.headerLength + 8)
        val total = 20 + 8 + quoted
        val p = ByteArray(total)
        ipHeader(p, total, Ip4Packet.ICMP, from, original.src)
        p[20] = 3; p[21] = 3
        System.arraycopy(original.data, 0, p, 28, quoted)
        put16(p, 22, checksum(p, 20, 8 + quoted, 0))
        return p
    }

    private fun ipHeader(p: ByteArray, total: Int, protocol: Int, src: Int, dst: Int) {
        p[0] = 0x45
        put16(p, 2, total); put16(p, 4, id())
        put16(p, 6, 0x4000) // don't fragment
        p[8] = 64; p[9] = protocol.toByte()
        put32(p, 12, src.toLong() and Tcp.MASK); put32(p, 16, dst.toLong() and Tcp.MASK)
        put16(p, 10, checksum(p, 0, 20, 0))
    }

    private fun transportChecksum(p: ByteArray, off: Int, len: Int, src: Int, dst: Int, protocol: Int): Int {
        var sum = ((src ushr 16) and 0xFFFF) + (src and 0xFFFF) + ((dst ushr 16) and 0xFFFF) + (dst and 0xFFFF) + protocol + len
        return checksum(p, off, len, sum)
    }

    /** One's-complement sum of 16-bit words, starting from [initial]. */
    fun checksum(p: ByteArray, off: Int, len: Int, initial: Int): Int {
        var sum = initial.toLong()
        var i = off
        val end = off + len
        while (i + 1 < end) { sum += ((p[i].toInt() and 255) shl 8) or (p[i + 1].toInt() and 255); i += 2 }
        if (i < end) sum += (p[i].toInt() and 255) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    /** True if the header and transport checksums of [p] verify. Used by tests and to drop corrupted packets. */
    fun valid(p: Ip4Packet): Boolean {
        if (checksum(p.data, 0, p.headerLength, 0) != 0) return false
        if (p.protocol != Ip4Packet.TCP && p.protocol != Ip4Packet.UDP) return true
        val len = p.payloadLength
        val pseudo = ((p.src ushr 16) and 0xFFFF) + (p.src and 0xFFFF) + ((p.dst ushr 16) and 0xFFFF) + (p.dst and 0xFFFF) + p.protocol + len
        return checksum(p.data, p.payloadOffset, len, pseudo) == 0
    }

    private fun put16(p: ByteArray, at: Int, v: Int) { p[at] = (v ushr 8).toByte(); p[at + 1] = v.toByte() }
    private fun put32(p: ByteArray, at: Int, v: Long) { p[at] = (v ushr 24).toByte(); p[at + 1] = (v ushr 16).toByte(); p[at + 2] = (v ushr 8).toByte(); p[at + 3] = v.toByte() }
    private val EMPTY = ByteArray(0)
}
