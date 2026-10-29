package app.ultimate.traffic

import app.ultimate.traffic.engine.EngineConfig
import app.ultimate.traffic.engine.Net
import app.ultimate.traffic.engine.PacketBuilder
import app.ultimate.traffic.engine.PacketSink
import app.ultimate.traffic.engine.Ip4Packet
import app.ultimate.traffic.engine.Tcp
import app.ultimate.traffic.engine.TcpSegment
import app.ultimate.traffic.engine.TrafficEngine
import app.ultimate.traffic.engine.UdpDatagram
import app.ultimate.traffic.engine.ip4
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Everything the engine writes back into the "tunnel", in order, as copies. */
class Tun : PacketSink {
    val queue = LinkedBlockingQueue<ByteArray>()
    val all = java.util.Collections.synchronizedList(ArrayList<ByteArray>())

    override fun write(packet: ByteArray) {
        val copy = packet.copyOf()
        all += copy
        queue += copy
    }

    fun next(ms: Long = 3000): Ip4Packet? = queue.poll(ms, TimeUnit.MILLISECONDS)?.let { Ip4Packet.parse(it, it.size) }
    fun nextTcp(ms: Long = 3000): TcpSegment? = next(ms)?.let { TcpSegment.parse(it) }
}

/** The real "internet" is a local server; fake addresses map onto it so no test needs the network. */
class LoopbackNet(private val tcpPort: () -> Int, private val udpPort: () -> Int = { 0 }) : Net {
    var failConnect = false
    var lastConnectIp = 0
    override fun connect(ip: Int, port: Int, timeoutMs: Int): Socket {
        lastConnectIp = ip
        if (failConnect) throw java.io.IOException("connection refused")
        return Socket("127.0.0.1", tcpPort())
    }
    override fun datagram(): DatagramSocket = DatagramSocket()
    override fun address(ip: Int): InetAddress = InetAddress.getByName("127.0.0.1").also { }
    var udpTarget = 0
    override fun owner(protocol: Int, srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int) = 10_123
}

val PHONE = ip4(10, 215, 173, 1)
val FAKE_DNS = ip4(10, 215, 173, 2)
val SERVER = ip4(93, 184, 216, 34)

/** Behaves like an app's TCP stack, just enough to drive the engine. */
class AppSim(private val engine: TrafficEngine, val tun: Tun, private val port: Int = 40_000, private val server: Int = SERVER, private val serverPort: Int = 443, private val window: Int = 65535) {
    var seq = 1_000L
    var ackNum = 0L
    val received = java.io.ByteArrayOutputStream()
    var maxInflightSeen = 0L

    fun send(flags: Int, payload: ByteArray = ByteArray(0), win: Int = window, atSeq: Long = seq, mss: Int? = null) {
        val p = PacketBuilder.tcp(PHONE, server, port, serverPort, atSeq, ackNum, flags, win, payload, mss = mss)
        engine.handle(p, p.size)
    }

    /** SYN, wait for SYN-ACK, ACK. Returns the SYN-ACK, or the RST if the connection was refused. */
    fun connect(): TcpSegment? {
        send(Tcp.SYN, mss = 1460)
        seq += 1
        val synAck = tun.nextTcp() ?: return null
        if (synAck.rst) return synAck
        ackNum = (synAck.seq + 1) and Tcp.MASK
        send(Tcp.ACK)
        return synAck
    }

    fun write(text: String) = write(text.toByteArray())

    fun write(bytes: ByteArray) {
        send(Tcp.PSH or Tcp.ACK, bytes)
        seq += bytes.size
    }

    /** Receives until [bytes] have arrived (acknowledging as a real stack would) or a FIN/RST ends the stream. */
    fun readUntil(bytes: Int, ms: Long = 5000): Int {
        val deadline = System.currentTimeMillis() + ms
        var highestSent = 0L
        while (received.size() < bytes && System.currentTimeMillis() < deadline) {
            val seg = tun.nextTcp(300) ?: continue
            if (seg.rst) return -1
            if (seg.payloadLength > 0) {
                check(seg.seq == ackNum) { "out of order: got ${seg.seq}, wanted $ackNum" }
                received.write(seg.ip.data, seg.payloadOffset, seg.payloadLength)
                ackNum = (ackNum + seg.payloadLength) and Tcp.MASK
                highestSent = ackNum
                send(Tcp.ACK)
            }
            if (seg.fin) { ackNum = (ackNum + 1) and Tcp.MASK; send(Tcp.ACK); return received.size() }
        }
        return received.size()
    }

    fun text() = received.toString(Charsets.UTF_8)
}

fun newEngine(net: Net, tun: Tun, quic: Boolean = true) = TrafficEngine(net, EngineConfig(dnsIp = FAKE_DNS, upstreamDns = ip4(9, 9, 9, 9), blockQuic = quic), tun)

fun udpOf(p: Ip4Packet) = UdpDatagram.parse(p)
