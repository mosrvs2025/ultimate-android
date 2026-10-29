package app.ultimate.traffic.engine

import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Socket
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Where finished packets go: back into the phone's tunnel. */
fun interface PacketSink {
    fun write(packet: ByteArray)
}

/** The engine's only contact with the outside world, so tests can use plain sockets and the app can use protected ones. */
interface Net {
    /** A connected socket to the real server that does NOT loop back through the tunnel. */
    @Throws(IOException::class)
    fun connect(ip: Int, port: Int, timeoutMs: Int): Socket

    /** A datagram socket that does not loop back through the tunnel. */
    @Throws(IOException::class)
    fun datagram(): DatagramSocket

    /** Where a datagram for [ip] really goes. Always [ip] itself in the app; tests redirect it. */
    fun address(ip: Int): java.net.InetAddress = ip.toInetAddress()

    /** The Android user id that owns a connection, or -1 if unknown. */
    fun owner(protocol: Int, srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int): Int
}

class EngineConfig(
    /** The fake DNS server apps are told to use. Queries to it are forwarded to [upstreamDns]. */
    val dnsIp: Int,
    val upstreamDns: Int,
    /** Refuse HTTP/3 so apps fall back to TCP, where the site name is visible. */
    val blockQuic: Boolean = true,
)

/** What the engine knows about one connection. Fields are written by relay threads and read by the UI. */
class FlowRecord(val id: Int, val protocol: String, val clientIp: Int, val clientPort: Int, val remoteIp: Int, val remotePort: Int, val startedAt: Long) {
    @Volatile var uid: Int = -1
    @Volatile var host: String? = null
    /** "TLS", "HTTP" or "DNS": how the host name was learned. */
    @Volatile var hostSource: String? = null
    /** The request line of an unencrypted HTTP request, which is readable by anyone on the path. */
    @Volatile var cleartext: String? = null
    val up = AtomicLong()
    val down = AtomicLong()
    @Volatile var lastAt: Long = startedAt
    @Volatile var open = true
    @Volatile var blocked = false
    @Volatile var error: String? = null
}

class DnsEvent(val at: Long, val uid: Int, val name: String, val type: Int, val blocked: Boolean)

/**
 * A user-space network stack that sits between the phone's tunnel and the internet. Apps' TCP and UDP traffic arrives as raw IPv4
 * packets; each connection is replayed over an ordinary socket to the real server and the replies are wrapped back into packets.
 * Along the way it records who talks to whom, how much, and which names were asked for. It cannot read encrypted content.
 */
class TrafficEngine(private val net: Net, private val config: EngineConfig, private val sink: PacketSink, private val log: (String) -> Unit = {}) {
    val records = ConcurrentHashMap<Int, FlowRecord>()
    val dnsLog = ConcurrentLinkedQueue<DnsEvent>()
    private val ipNames = ConcurrentHashMap<Int, String>()
    private val tcpFlows = ConcurrentHashMap<Long, TcpFlow>()
    private val udpFlows = ConcurrentHashMap<Long, UdpFlow>()
    private val nextId = AtomicInteger(1)
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "traffic-relay").apply { isDaemon = true } }
    private val sweeper: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "traffic-sweep").apply { isDaemon = true } }
    private val random = Random()
    @Volatile private var stopped = false

    /** Connections and lookups the apps tried that were allowed through, and how many of them reached a server. A tunnel that keeps trying and never reaches anyone is leaving its apps offline. */
    val attempts = AtomicLong()
    val successes = AtomicLong()
    /** Why [run] gave up, if it did because of a failure rather than the tunnel closing. */
    @Volatile var failure: String? = null

    /** Domains (and their subdomains) refused at the DNS step, and connections to addresses learned from them. */
    @Volatile var blocked: Set<String> = emptySet()

    init {
        sweeper.scheduleWithFixedDelay(::sweep, 15, 15, TimeUnit.SECONDS)
    }

    /** Name for an address, as learned from DNS answers. */
    fun nameOf(ip: Int): String? = ipNames[ip]

    fun isBlocked(host: String?): Boolean = host != null && blocked.any { Dns.matches(host, it) }

    /** Reads packets from the tunnel until it closes. */
    fun run(tun: InputStream) {
        val buf = ByteArray(32 * 1024)
        var consecutiveErrors = 0
        try {
            while (!stopped) {
                val n = tun.read(buf)
                if (n < 0) break
                if (n > 0) {
                    // One odd packet, or a moment of thread exhaustion, must not end the capture; a run of failures must, so the apps get their network back.
                    try {
                        handle(buf, n)
                        consecutiveErrors = 0
                    } catch (t: Throwable) {
                        if (++consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                            failure = "The inspector hit repeated internal errors (${t.javaClass.simpleName}) and stopped to keep your apps online"
                            break
                        }
                    }
                }
            }
        } catch (_: IOException) {
        } finally {
            stop()
        }
    }

    fun stop() {
        if (stopped) return
        stopped = true
        sweeper.shutdownNow()
        tcpFlows.values.forEach { it.abort(false) }
        udpFlows.values.forEach { it.close() }
        pool.shutdownNow()
    }

    /** Handles one packet read from the tunnel. [buf] is reused after this returns, so nothing may keep it. */
    fun handle(buf: ByteArray, length: Int) {
        val ip = Ip4Packet.parse(buf, length) ?: return
        if (ip.fragmented) return
        when (ip.protocol) {
            Ip4Packet.TCP -> TcpSegment.parse(ip)?.let(::onTcp)
            Ip4Packet.UDP -> UdpDatagram.parse(ip)?.let(::onUdp)
            else -> Unit
        }
    }

    // ───────────────────────────────── TCP ─────────────────────────────────

    private fun key(srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int): Long =
        ((srcIp.toLong() and 0xFFFFFFFFL) * 31 + (dstIp.toLong() and 0xFFFFFFFFL)) * 1_000_003L + srcPort * 65_599L + dstPort

    private fun onTcp(seg: TcpSegment) {
        val ip = seg.ip
        val k = key(ip.src, seg.srcPort, ip.dst, seg.dstPort)
        val flow = tcpFlows[k]
        if (flow != null) {
            flow.onSegment(seg)
            return
        }
        if (seg.rst) return
        if (!seg.syn || seg.hasAck) {
            // A packet for a connection we do not know, e.g. from before capture started: tell the app to give up on it.
            sink.write(PacketBuilder.tcp(ip.dst, ip.src, seg.dstPort, seg.srcPort, if (seg.hasAck) seg.ack else 0, (seg.seq + seg.payloadLength + if (seg.fin) 1 else 0) and Tcp.MASK, Tcp.RST or Tcp.ACK, 0))
            return
        }
        if (tcpFlows.size >= MAX_FLOWS) evictOldest()
        val rec = FlowRecord(nextId.getAndIncrement(), "TCP", ip.src, seg.srcPort, ip.dst, seg.dstPort, System.currentTimeMillis())
        rec.uid = net.owner(Ip4Packet.TCP, ip.src, seg.srcPort, ip.dst, seg.dstPort)
        if (ip.dst == config.dnsIp) {
            // Apps that look names up over TCP talk to our stand-in resolver; it is relayed to the real one, so say what it is.
            rec.host = "DNS lookups (system resolver)"
            rec.hostSource = "DNS"
        } else ipNames[ip.dst]?.let { rec.host = it; rec.hostSource = "DNS" }
        records[rec.id] = rec
        if (isBlocked(rec.host)) {
            rec.blocked = true
            rec.open = false
            sink.write(PacketBuilder.tcp(ip.dst, ip.src, seg.dstPort, seg.srcPort, 0, (seg.seq + 1) and Tcp.MASK, Tcp.RST or Tcp.ACK, 0))
            return
        }
        val created = TcpFlow(k, rec, seg)
        tcpFlows[k] = created
        attempts.incrementAndGet()
        created.start()
    }

    private inner class TcpFlow(private val key: Long, val rec: FlowRecord, syn: TcpSegment) {
        private val client = syn.ip.src
        private val server = syn.ip.dst
        private val clientPort = syn.srcPort
        private val serverPort = syn.dstPort
        private val serverIsn = random.nextInt().toLong() and Tcp.MASK
        private val mss = minOf(syn.mss ?: 536, OUR_MSS)
        private val lock = java.lang.Object()
        private val outbound = LinkedBlockingQueue<ByteArray>()
        private val queuedBytes = AtomicInteger()
        private var rcvNxt = (syn.seq + 1) and Tcp.MASK
        private var sndNxt = serverIsn
        private var sndUna = serverIsn
        private var clientWindow = syn.window
        private var established = false
        private var clientFin = false
        private var serverFin = false
        private var inspected = false
        @Volatile var closed = false
        @Volatile private var socket: Socket? = null
        @Volatile var lastActive = System.currentTimeMillis()

        fun start() {
            pool.execute {
                try {
                    val s = net.connect(if (server == config.dnsIp) config.upstreamDns else server, serverPort, 10_000)
                    s.tcpNoDelay = true
                    successes.incrementAndGet()
                    socket = s
                    synchronized(lock) {
                        if (closed) { s.close(); return@execute }
                        sndNxt = (serverIsn + 1) and Tcp.MASK
                        established = true
                        sink.write(PacketBuilder.tcp(server, client, serverPort, clientPort, serverIsn, rcvNxt, Tcp.SYN or Tcp.ACK, WINDOW, mss = OUR_MSS))
                    }
                    pool.execute(::writeLoop)
                    readLoop(s.getInputStream())
                } catch (e: Exception) {
                    rec.error = e.message ?: e.javaClass.simpleName
                    synchronized(lock) { if (!closed) { sink.write(PacketBuilder.tcp(server, client, serverPort, clientPort, 0, rcvNxt, Tcp.RST or Tcp.ACK, 0)) } }
                    abort(false)
                }
            }
        }

        fun onSegment(seg: TcpSegment) = synchronized(lock) {
            if (closed) return@synchronized
            lastActive = System.currentTimeMillis()
            rec.lastAt = lastActive
            if (seg.rst) { abort(false); return@synchronized }
            if (!established) return@synchronized // a retransmitted SYN while still connecting
            if (seg.hasAck) {
                if (Tcp.after(seg.ack, sndUna) && !Tcp.after(seg.ack, sndNxt)) sndUna = seg.ack
                clientWindow = seg.window
                lock.notifyAll()
            }
            var needAck = false
            var start = seg.payloadOffset
            var len = seg.payloadLength
            var at = seg.seq
            if (len > 0) {
                if (Tcp.after(rcvNxt, at)) { // part or all of this was already received
                    val skip = ((rcvNxt - at) and Tcp.MASK).toInt()
                    if (skip >= len) { len = 0; needAck = true } else { start += skip; len -= skip; at = rcvNxt }
                }
                if (len > 0) {
                    if (at == rcvNxt) {
                        if (queuedBytes.get() + len > MAX_QUEUED) return@synchronized // slow server: let the app retransmit later
                        val copy = seg.ip.data.copyOfRange(start, start + len)
                        if (!inspected) { inspected = true; inspect(copy) }
                        outbound.add(copy)
                        queuedBytes.addAndGet(len)
                        rcvNxt = (rcvNxt + len) and Tcp.MASK
                        rec.up.addAndGet(len.toLong())
                    }
                    needAck = true
                }
            }
            if (seg.fin) {
                if (((seg.seq + seg.payloadLength) and Tcp.MASK) == rcvNxt && !clientFin) {
                    clientFin = true
                    rcvNxt = (rcvNxt + 1) and Tcp.MASK
                    outbound.add(FIN)
                }
                needAck = true
            }
            if (needAck) ack()
            if (clientFin && serverFin && sndUna == sndNxt) finish()
        }

        private fun inspect(first: ByteArray) {
            val seen = Hello.inspect(first, 0, first.size) ?: return
            rec.host = seen.host
            rec.hostSource = if (seen.viaTls) "TLS" else "HTTP"
            rec.cleartext = seen.request
            if (isBlocked(seen.host)) { rec.blocked = true; abortWithReset() }
        }

        private fun ack() = sink.write(PacketBuilder.tcp(server, client, serverPort, clientPort, sndNxt, rcvNxt, Tcp.ACK, WINDOW))

        private fun writeLoop() {
            try {
                val out = socket!!.getOutputStream()
                while (true) {
                    val chunk = outbound.take()
                    if (closed || chunk === POISON) return
                    if (chunk === FIN) { runCatching { socket?.shutdownOutput() }; return }
                    out.write(chunk)
                    out.flush()
                    queuedBytes.addAndGet(-chunk.size)
                }
            } catch (_: InterruptedException) {
            } catch (_: IOException) {
                abortWithReset()
            }
        }

        private fun readLoop(input: InputStream) {
            val buf = ByteArray(mss)
            while (!closed) {
                var allow = 0
                synchronized(lock) {
                    var waited = 0
                    while (!closed) {
                        allow = minOf(mss, clientWindow - ((sndNxt - sndUna) and Tcp.MASK).toInt())
                        if (allow > 0) break
                        lock.wait(200)
                        waited += 200
                        if (waited > 60_000) { abortWithReset(); return }
                    }
                }
                if (closed) return
                val n = try { input.read(buf, 0, allow) } catch (_: IOException) { abortWithReset(); return }
                synchronized(lock) {
                    if (closed) return
                    lastActive = System.currentTimeMillis()
                    rec.lastAt = lastActive
                    if (n < 0) {
                        sink.write(PacketBuilder.tcp(server, client, serverPort, clientPort, sndNxt, rcvNxt, Tcp.FIN or Tcp.ACK, WINDOW))
                        sndNxt = (sndNxt + 1) and Tcp.MASK
                        serverFin = true
                        if (clientFin && sndUna == sndNxt) finish()
                        return
                    }
                    sink.write(PacketBuilder.tcp(server, client, serverPort, clientPort, sndNxt, rcvNxt, Tcp.PSH or Tcp.ACK, WINDOW, buf, 0, n))
                    sndNxt = (sndNxt + n) and Tcp.MASK
                    rec.down.addAndGet(n.toLong())
                }
            }
        }

        private fun abortWithReset() {
            synchronized(lock) { if (!closed && established) sink.write(PacketBuilder.tcp(server, client, serverPort, clientPort, sndNxt, rcvNxt, Tcp.RST or Tcp.ACK, 0)) }
            abort(false)
        }

        fun abort(unused: Boolean) {
            synchronized(lock) {
                if (closed) return
                closed = true
                lock.notifyAll()
            }
            outbound.offer(POISON)
            runCatching { socket?.close() }
            finish()
        }

        private fun finish() {
            closed = true
            rec.open = false
            tcpFlows.remove(key, this)
            runCatching { socket?.close() }
            outbound.offer(POISON)
        }
    }

    // ───────────────────────────────── UDP ─────────────────────────────────

    private fun onUdp(dg: UdpDatagram) {
        val ip = dg.ip
        if (dg.dstPort == 53) { onDns(dg); return }
        if (dg.dstPort == 443 && config.blockQuic) {
            val rec = FlowRecord(nextId.getAndIncrement(), "UDP", ip.src, dg.srcPort, ip.dst, 443, System.currentTimeMillis())
            rec.uid = net.owner(Ip4Packet.UDP, ip.src, dg.srcPort, ip.dst, 443)
            rec.host = ipNames[ip.dst]
            rec.hostSource = rec.host?.let { "DNS" }
            rec.blocked = true
            rec.open = false
            rec.error = "HTTP/3 (QUIC) refused so the app falls back to HTTPS"
            val key = key(ip.src, dg.srcPort, ip.dst, 443)
            if (udpFlows[key] == null && records.values.count { it.protocol == "UDP" && it.blocked && it.remoteIp == ip.dst } < 3) records[rec.id] = rec
            sink.write(PacketBuilder.icmpPortUnreachable(ip, ip.dst))
            return
        }
        val k = key(ip.src, dg.srcPort, ip.dst, dg.dstPort)
        val flow = udpFlows.getOrPut(k) {
            val rec = FlowRecord(nextId.getAndIncrement(), "UDP", ip.src, dg.srcPort, ip.dst, dg.dstPort, System.currentTimeMillis())
            rec.uid = net.owner(Ip4Packet.UDP, ip.src, dg.srcPort, ip.dst, dg.dstPort)
            ipNames[ip.dst]?.let { rec.host = it; rec.hostSource = "DNS" }
            records[rec.id] = rec
            attempts.incrementAndGet()
            UdpFlow(k, rec, ip.src, dg.srcPort, ip.dst, dg.dstPort).also { it.open() }
        }
        flow.send(dg.ip.data, dg.payloadOffset, dg.payloadLength)
    }

    private inner class UdpFlow(private val key: Long, val rec: FlowRecord, private val client: Int, private val clientPort: Int, private val server: Int, private val serverPort: Int) {
        private var socket: DatagramSocket? = null
        @Volatile var lastActive = System.currentTimeMillis()

        fun open() {
            try {
                val s = net.datagram()
                s.soTimeout = 1000
                socket = s
                pool.execute(::receiveLoop)
            } catch (e: IOException) {
                rec.error = e.message
                close()
            }
        }

        fun send(d: ByteArray, off: Int, len: Int) {
            val s = socket ?: return
            lastActive = System.currentTimeMillis()
            rec.lastAt = lastActive
            if (isBlocked(rec.host)) { rec.blocked = true; return }
            try {
                s.send(DatagramPacket(d.copyOfRange(off, off + len), len, net.address(server), serverPort))
                rec.up.addAndGet(len.toLong())
            } catch (e: IOException) {
                rec.error = e.message
            }
        }

        private fun receiveLoop() {
            val buf = ByteArray(65535)
            val s = socket ?: return
            try {
                while (rec.open) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        lastActive = System.currentTimeMillis()
                        rec.lastAt = lastActive
                        rec.down.addAndGet(p.length.toLong())
                        successes.incrementAndGet()
                        sink.write(PacketBuilder.udp(server, client, serverPort, clientPort, buf, 0, p.length))
                    } catch (_: java.net.SocketTimeoutException) {
                        if (System.currentTimeMillis() - lastActive > UDP_IDLE_MS) break
                    }
                }
            } catch (_: IOException) {
            } finally {
                close()
            }
        }

        fun close() {
            rec.open = false
            udpFlows.remove(key, this)
            runCatching { socket?.close() }
        }
    }

    // ───────────────────────────────── DNS ─────────────────────────────────

    private fun onDns(dg: UdpDatagram) {
        val ip = dg.ip
        val query = Dns.parseQuery(ip.data, dg.payloadOffset, dg.payloadLength)
        val uid = net.owner(Ip4Packet.UDP, ip.src, dg.srcPort, ip.dst, 53)
        val client = ip.src
        val clientPort = dg.srcPort
        val asked = ip.dst
        if (query != null) {
            val refuse = isBlocked(query.name)
            record(DnsEvent(System.currentTimeMillis(), uid, query.name, query.type, refuse))
            if (refuse) {
                val reply = Dns.nxdomain(ip.data, dg.payloadOffset, dg.payloadLength)
                sink.write(PacketBuilder.udp(asked, client, 53, clientPort, reply))
                return
            }
        }
        val payload = ip.data.copyOfRange(dg.payloadOffset, dg.payloadOffset + dg.payloadLength)
        val upstream = if (asked == config.dnsIp) config.upstreamDns else asked
        attempts.incrementAndGet()
        pool.execute {
            try {
                val s = net.datagram()
                try {
                    s.soTimeout = 4000
                    s.send(DatagramPacket(payload, payload.size, net.address(upstream), 53))
                    val buf = ByteArray(4096)
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    successes.incrementAndGet()
                    Dns.parseResponse(buf, 0, p.length)?.let { r -> r.addresses.forEach { addr -> if (r.name.isNotEmpty()) ipNames[addr] = r.name } }
                    sink.write(PacketBuilder.udp(asked, client, 53, clientPort, buf, 0, p.length))
                } finally {
                    s.close()
                }
            } catch (_: IOException) {
            }
        }
    }

    private fun record(e: DnsEvent) {
        dnsLog.add(e)
        while (dnsLog.size > MAX_DNS_LOG) dnsLog.poll()
    }

    // ───────────────────────────── housekeeping ────────────────────────────

    private fun sweep() {
        val now = System.currentTimeMillis()
        tcpFlows.values.filter { now - it.lastActive > TCP_IDLE_MS }.forEach { it.abort(false) }
        udpFlows.values.filter { now - it.lastActive > UDP_IDLE_MS }.forEach { it.close() }
        // Keep the history bounded: drop the oldest finished connections once there are many.
        if (records.size > MAX_RECORDS) records.values.filter { !it.open }.sortedBy { it.lastAt }.take(records.size - MAX_RECORDS).forEach { records.remove(it.id) }
    }

    private fun evictOldest() {
        tcpFlows.values.minByOrNull { it.lastActive }?.abort(false)
    }

    companion object {
        private const val OUR_MSS = 1360
        private const val WINDOW = 65535
        private const val MAX_QUEUED = 4 * 1024 * 1024
        private const val MAX_FLOWS = 400
        private const val MAX_RECORDS = 2000
        private const val MAX_DNS_LOG = 1500
        private const val MAX_CONSECUTIVE_ERRORS = 25
        private const val TCP_IDLE_MS = 120_000L
        private const val UDP_IDLE_MS = 60_000L
        private val FIN = ByteArray(0)
        private val POISON = ByteArray(0)
    }
}
