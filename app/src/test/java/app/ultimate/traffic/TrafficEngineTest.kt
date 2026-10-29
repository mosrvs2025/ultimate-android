package app.ultimate.traffic

import app.ultimate.traffic.engine.Dns
import app.ultimate.traffic.engine.Hello
import app.ultimate.traffic.engine.Ip4Packet
import app.ultimate.traffic.engine.PacketBuilder
import app.ultimate.traffic.engine.Tcp
import app.ultimate.traffic.engine.TcpSegment
import app.ultimate.traffic.engine.UdpDatagram
import app.ultimate.traffic.engine.ip4
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drives the relay with real sockets on loopback: no phone and no internet involved. */
class TrafficEngineTest {
    private val servers = ArrayList<ServerSocket>()
    private val udp = ArrayList<DatagramSocket>()

    @After fun tearDown() {
        servers.forEach { runCatching { it.close() } }
        udp.forEach { runCatching { it.close() } }
    }

    private fun server(handler: (Socket) -> Unit): ServerSocket = ServerSocket(0).also {
        servers += it
        thread(isDaemon = true) { while (!it.isClosed) { val s = runCatching { it.accept() }.getOrNull() ?: break; thread(isDaemon = true) { runCatching { handler(s) } } } }
    }

    private fun sniHello(host: String): ByteArray {
        val name = host.toByteArray()
        val sni = byteArrayOf(0, 0, ((name.size + 5) shr 8).toByte(), (name.size + 5).toByte(), ((name.size + 3) shr 8).toByte(), (name.size + 3).toByte(), 0, (name.size shr 8).toByte(), name.size.toByte()) + name
        val ext = sni
        val body = byteArrayOf(3, 3) + ByteArray(32) + byteArrayOf(0) + byteArrayOf(0, 2, 0x13, 0x01) + byteArrayOf(1, 0) +
            byteArrayOf((ext.size shr 8).toByte(), ext.size.toByte()) + ext
        val hs = byteArrayOf(1, 0, (body.size shr 8).toByte(), body.size.toByte()) + body
        return byteArrayOf(0x16, 3, 1, (hs.size shr 8).toByte(), hs.size.toByte()) + hs
    }

    @Test fun relaysARequestAndReplyThroughARealSocket() {
        val srv = server { s -> val b = ByteArray(5); s.getInputStream().readNBytes(b, 0, 5); s.getOutputStream().write(String(b).uppercase().toByteArray()); s.close() }
        val tun = Tun(); val engine = newEngine(LoopbackNet({ srv.localPort }), tun)
        val app = AppSim(engine, tun)
        val synAck = app.connect()
        assertNotNull(synAck); assertTrue(synAck!!.syn && synAck.hasAck)
        assertEquals(1360, synAck.mss)
        app.write("hello")
        app.readUntil(5)
        assertEquals("HELLO", app.text())
        assertTrue("every packet the engine wrote must have valid checksums", tun.all.all { PacketBuilder.valid(Ip4Packet.parse(it, it.size)!!) })
        val rec = engine.records.values.single()
        assertEquals(5L, rec.up.get()); assertEquals(5L, rec.down.get()); assertEquals(10_123, rec.uid)
        assertEquals(1L, engine.attempts.get()); assertEquals(1L, engine.successes.get())
        engine.stop()
    }

    @Test fun downloadsLargeDataInOrderAndRespectsTheClientWindow() {
        val payload = ByteArray(300_000) { (it % 251).toByte() }
        val srv = server { s -> s.getInputStream().readNBytes(2); s.getOutputStream().write(payload); s.getOutputStream().flush(); s.close() }
        val tun = Tun(); val engine = newEngine(LoopbackNet({ srv.localPort }), tun)
        val app = AppSim(engine, tun, window = 8_000)
        app.connect()
        app.write("go")
        // Walk the stream by hand so the largest amount in flight can be checked against the 8000-byte window.
        var inflightMax = 0L
        var sinceAck = 0
        var lastAcked = app.ackNum
        val deadline = System.currentTimeMillis() + 20_000
        while (app.received.size() < payload.size && System.currentTimeMillis() < deadline) {
            // Like a real stack: acknowledge every couple of segments, and on a short delayed-ACK timer when nothing more arrives.
            val seg = tun.nextTcp(40)
            if (seg == null) { app.send(Tcp.ACK); lastAcked = app.ackNum; continue }
            if (seg.payloadLength > 0) {
                assertEquals("segments must arrive in order", app.ackNum, seg.seq)
                inflightMax = maxOf(inflightMax, (seg.seq + seg.payloadLength - lastAcked) and Tcp.MASK)
                app.received.write(seg.ip.data, seg.payloadOffset, seg.payloadLength)
                app.ackNum = (app.ackNum + seg.payloadLength) and Tcp.MASK
                if (++sinceAck >= 2) { app.send(Tcp.ACK); lastAcked = app.ackNum; sinceAck = 0 }
            }
        }
        assertEquals(payload.size, app.received.size())
        assertArrayEquals(payload, app.received.toByteArray())
        assertTrue("in flight peaked at $inflightMax with an 8000-byte window", inflightMax <= 8_000 + 1360)
        engine.stop()
    }

    @Test fun repeatedInternalFailuresEndTheRunSoTheServiceCanReleaseTheTunnel() {
        val throwing = object : app.ultimate.traffic.engine.Net by LoopbackNet({ 1 }) {
            override fun owner(protocol: Int, srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int): Int = throw IllegalStateException("boom")
        }
        val engine = newEngine(throwing, Tun())
        val packets = (0 until 40).map { PacketBuilder.tcp(PHONE, SERVER, 41_000 + it, 443, 1_000, 0, Tcp.SYN, 65535) }.iterator()
        val tun = object : java.io.InputStream() {
            override fun read() = -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (!packets.hasNext()) return -1
                val p = packets.next(); System.arraycopy(p, 0, b, off, p.size); return p.size
            }
        }
        engine.run(tun)
        assertNotNull("a run of failures must be reported, not swallowed", engine.failure)
    }

    @Test fun oneBadPacketDoesNotEndTheCapture() {
        val srv = server { s -> s.getOutputStream().write(1); s.close() }
        var calls = 0
        val flaky = object : app.ultimate.traffic.engine.Net by LoopbackNet({ srv.localPort }) {
            override fun owner(protocol: Int, srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int): Int { if (calls++ == 0) throw IllegalStateException("once"); return 1 }
        }
        val engine = newEngine(flaky, Tun())
        val packets = (0 until 3).map { PacketBuilder.tcp(PHONE, SERVER, 42_000 + it, 443, 1_000, 0, Tcp.SYN, 65535) }.iterator()
        val tun = object : java.io.InputStream() {
            override fun read() = -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (!packets.hasNext()) return -1
                val p = packets.next(); System.arraycopy(p, 0, b, off, p.size); return p.size
            }
        }
        engine.run(tun)
        assertNull(engine.failure)
        assertEquals(2L, engine.attempts.get())
    }

    @Test fun tcpLookupsToTheStandInResolverGoToTheRealOneAndAreNamed() {
        val srv = server { s -> val b = ByteArray(2); s.getInputStream().readNBytes(b, 0, 2); s.getOutputStream().write(b); s.close() }
        val net = LoopbackNet({ srv.localPort }); val tun = Tun(); val engine = newEngine(net, tun)
        val app = AppSim(engine, tun, server = FAKE_DNS, serverPort = 53)
        assertTrue(app.connect()!!.syn)
        app.write("hi"); app.readUntil(2)
        assertEquals("hi", app.text())
        assertEquals("never connect to the stand-in address itself", ip4(9, 9, 9, 9), net.lastConnectIp)
        val rec = engine.records.values.single()
        assertEquals("DNS lookups (system resolver)", rec.host)
        engine.stop()
    }

    @Test fun refusedConnectionsAreResetBackToTheApp() {
        val tun = Tun(); val net = LoopbackNet({ 1 }); net.failConnect = true
        val engine = newEngine(net, tun)
        val reply = AppSim(engine, tun).connect()
        assertNotNull(reply); assertTrue(reply!!.rst)
        assertNotNull(engine.records.values.single().error)
        engine.stop()
    }

    @Test fun halfCloseIsPassedBothWays() {
        val srv = server { s -> val got = s.getInputStream().readAllBytes(); s.getOutputStream().write("got ${got.size}".toByteArray()); s.close() }
        val tun = Tun(); val engine = newEngine(LoopbackNet({ srv.localPort }), tun)
        val app = AppSim(engine, tun)
        app.connect()
        app.write("12345678")
        app.send(Tcp.FIN or Tcp.ACK); app.seq += 1 // the app is done sending
        app.readUntil(Int.MAX_VALUE, 5000)
        assertEquals("got 8", app.text())
        engine.stop()
    }

    @Test fun readsTheSiteNameFromTheTlsHello() {
        val srv = server { s -> s.getInputStream().read(ByteArray(2000)); Thread.sleep(300); s.close() }
        val tun = Tun(); val engine = newEngine(LoopbackNet({ srv.localPort }), tun)
        val app = AppSim(engine, tun)
        app.connect()
        app.write(sniHello("graph.facebook.com"))
        Thread.sleep(300)
        val rec = engine.records.values.single()
        assertEquals("graph.facebook.com", rec.host); assertEquals("TLS", rec.hostSource)
        engine.stop()
    }

    @Test fun flagsUnencryptedHttpAndShowsTheRequestLine() {
        val srv = server { s -> s.getInputStream().read(ByteArray(2000)); Thread.sleep(300); s.close() }
        val tun = Tun(); val engine = newEngine(LoopbackNet({ srv.localPort }), tun)
        val app = AppSim(engine, tun, serverPort = 80)
        app.connect()
        app.write("GET /track?id=42 HTTP/1.1\r\nHost: ads.example.net\r\n\r\n")
        Thread.sleep(300)
        val rec = engine.records.values.single()
        assertEquals("ads.example.net", rec.host); assertEquals("HTTP", rec.hostSource)
        assertEquals("GET /track?id=42 HTTP/1.1", rec.cleartext)
        engine.stop()
    }

    @Test fun resetsConnectionsToNamesTheUserBlocked() {
        val srv = server { s -> s.getInputStream().read(); s.close() }
        val tun = Tun(); val engine = newEngine(LoopbackNet({ srv.localPort }), tun)
        val app = AppSim(engine, tun)
        app.connect()
        engine.blocked = setOf("facebook.com")
        app.write(sniHello("graph.facebook.com"))
        var sawRst = false
        repeat(10) { tun.nextTcp(300)?.let { if (it.rst) sawRst = true } }
        assertTrue("a connection whose hello names a blocked site is reset", sawRst)
        assertTrue(engine.records.values.single().blocked)
        engine.stop()
    }

    @Test fun answersStrayPacketsWithAReset() {
        val tun = Tun(); val engine = newEngine(LoopbackNet({ 1 }), tun)
        val p = PacketBuilder.tcp(PHONE, SERVER, 41_000, 443, 5, 9, Tcp.ACK, 1000, "late".toByteArray())
        engine.handle(p, p.size)
        assertTrue(tun.nextTcp()!!.rst)
        engine.stop()
    }

    // ─────────────────────────────── DNS ───────────────────────────────

    private fun dnsQuery(name: String, id: Int = 0x1234): ByteArray {
        val q = java.io.ByteArrayOutputStream()
        q.write(byteArrayOf((id shr 8).toByte(), id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        name.split('.').forEach { q.write(it.length); q.write(it.toByteArray()) }
        q.write(0); q.write(byteArrayOf(0, 1, 0, 1))
        return q.toByteArray()
    }

    private fun dnsAnswer(query: ByteArray, ip: Int): ByteArray {
        val r = query.copyOf(); r[2] = 0x81.toByte(); r[3] = 0x80.toByte(); r[7] = 1
        return r + byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, (ip shr 24).toByte(), (ip shr 16).toByte(), (ip shr 8).toByte(), ip.toByte())
    }

    private fun upstreamDns(answerIp: Int): Pair<DatagramSocket, java.util.concurrent.atomic.AtomicInteger> {
        val s = DatagramSocket(0, java.net.InetAddress.getByName("127.0.0.1")).also { udp += it }
        val hits = java.util.concurrent.atomic.AtomicInteger()
        thread(isDaemon = true) {
            val buf = ByteArray(512)
            while (!s.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                runCatching { s.receive(p) }.onFailure { return@thread }
                hits.incrementAndGet()
                val reply = dnsAnswer(buf.copyOf(p.length), answerIp)
                s.send(DatagramPacket(reply, reply.size, p.socketAddress))
            }
        }
        return s to hits
    }

    private fun askDns(engine: app.ultimate.traffic.engine.TrafficEngine, tun: Tun, name: String): UdpDatagram? {
        val q = PacketBuilder.udp(PHONE, FAKE_DNS, 50_000, 53, dnsQuery(name))
        engine.handle(q, q.size)
        return tun.next(3000)?.let { UdpDatagram.parse(it) }
    }

    @Test fun forwardsDnsAndRemembersWhichNameOwnsWhichAddress() {
        val (up, hits) = upstreamDns(ip4(1, 2, 3, 4))
        // The engine sends to port 53 of "upstream", so point the test net's datagrams at our fake resolver by listening there.
        val net = object : app.ultimate.traffic.engine.Net by LoopbackNet({ 1 }) {
            override fun datagram() = object : DatagramSocket() {
                override fun send(p: DatagramPacket) { p.address = java.net.InetAddress.getByName("127.0.0.1"); p.port = up.localPort; super.send(p) }
            }
        }
        val tun = Tun(); val engine = newEngine(net, tun)
        val reply = askDns(engine, tun, "ads.applovin.com")
        assertNotNull(reply)
        assertEquals(53, reply!!.srcPort); assertEquals(50_000, reply.dstPort)
        assertEquals(FAKE_DNS, reply.ip.src)
        val parsed = Dns.parseResponse(reply.ip.data, reply.payloadOffset, reply.payloadLength)!!
        assertEquals(listOf(ip4(1, 2, 3, 4)), parsed.addresses)
        assertEquals("ads.applovin.com", engine.nameOf(ip4(1, 2, 3, 4)))
        assertEquals(1, hits.get())
        assertEquals("ads.applovin.com", engine.dnsLog.single().name)
        engine.stop()
    }

    @Test fun blockedNamesGetNxdomainWithoutAskingAnyone() {
        val (up, hits) = upstreamDns(ip4(1, 2, 3, 4))
        val net = object : app.ultimate.traffic.engine.Net by LoopbackNet({ 1 }) {
            override fun datagram() = object : DatagramSocket() {
                override fun send(p: DatagramPacket) { p.address = java.net.InetAddress.getByName("127.0.0.1"); p.port = up.localPort; super.send(p) }
            }
        }
        val tun = Tun(); val engine = newEngine(net, tun)
        engine.blocked = setOf("applovin.com")
        val reply = askDns(engine, tun, "ms.applovin.com")!!
        val flags = ((reply.ip.data[reply.payloadOffset + 2].toInt() and 255) shl 8) or (reply.ip.data[reply.payloadOffset + 3].toInt() and 255)
        assertEquals("NXDOMAIN", 3, flags and 0xF)
        assertEquals(0, hits.get())
        assertTrue(engine.dnsLog.single().blocked)
        engine.stop()
    }

    @Test fun refusesQuicSoAppsFallBackToHttps() {
        val tun = Tun(); val engine = newEngine(LoopbackNet({ 1 }), tun)
        val q = PacketBuilder.udp(PHONE, SERVER, 51_000, 443, ByteArray(1200))
        engine.handle(q, q.size)
        val icmp = tun.next()!!
        assertEquals(Ip4Packet.ICMP, icmp.protocol)
        assertEquals(3, icmp.data[icmp.payloadOffset].toInt()); assertEquals(3, icmp.data[icmp.payloadOffset + 1].toInt())
        assertTrue(PacketBuilder.valid(icmp))
        engine.stop()
    }

    @Test fun relaysOtherUdpBothWays() {
        val echo = DatagramSocket(0, java.net.InetAddress.getByName("127.0.0.1")).also { udp += it }
        thread(isDaemon = true) { val b = ByteArray(100); while (!echo.isClosed) { val p = DatagramPacket(b, b.size); runCatching { echo.receive(p) }.onFailure { return@thread }; echo.send(DatagramPacket(b, p.length, p.socketAddress)) } }
        val net = object : app.ultimate.traffic.engine.Net by LoopbackNet({ 1 }) {
            override fun datagram() = object : DatagramSocket() {
                override fun send(p: DatagramPacket) { p.address = java.net.InetAddress.getByName("127.0.0.1"); p.port = echo.localPort; super.send(p) }
            }
        }
        val tun = Tun(); val engine = newEngine(net, tun)
        val q = PacketBuilder.udp(PHONE, SERVER, 52_000, 4500, "ping".toByteArray())
        engine.handle(q, q.size)
        val reply = UdpDatagram.parse(tun.next()!!)!!
        assertEquals("ping", String(reply.ip.data, reply.payloadOffset, reply.payloadLength))
        assertEquals(4500, reply.srcPort); assertEquals(52_000, reply.dstPort)
        assertTrue(PacketBuilder.valid(reply.ip))
        engine.stop()
    }

    // ───────────────────────── parsing and building ─────────────────────────

    @Test fun builtPacketsParseBackAndVerify() {
        val p = PacketBuilder.tcp(PHONE, SERVER, 1234, 443, 0xFFFFFFF0L, 77, Tcp.PSH or Tcp.ACK, 4096, "payload".toByteArray(), mss = 1360)
        val ip = Ip4Packet.parse(p, p.size)!!
        val t = TcpSegment.parse(ip)!!
        assertEquals(0xFFFFFFF0L, t.seq); assertEquals(77L, t.ack); assertEquals(1360, t.mss); assertEquals("payload", String(p, t.payloadOffset, t.payloadLength))
        assertTrue(PacketBuilder.valid(ip))
        p[p.size - 1] = (p[p.size - 1] + 1).toByte()
        assertTrue("a flipped bit must fail the checksum", !PacketBuilder.valid(Ip4Packet.parse(p, p.size)!!))
    }

    @Test fun sequenceMathSurvivesWrapAround() {
        assertTrue(Tcp.after(5, 0xFFFFFFFAL)); assertTrue(!Tcp.after(0xFFFFFFFAL, 5)); assertTrue(!Tcp.after(7, 7))
    }

    @Test fun rejectsFragmentsAndGarbage() {
        val p = PacketBuilder.udp(PHONE, SERVER, 1, 2, ByteArray(4))
        p[6] = 0x20 // more-fragments
        assertTrue(Ip4Packet.parse(p, p.size)!!.fragmented)
        assertNull(Ip4Packet.parse(ByteArray(10), 10))
        assertNull(Ip4Packet.parse(ByteArray(40) { 0x60 }, 40))
    }

    @Test fun parsesDnsNamesWithCompressionAndFindsHelloNames() {
        val q = dnsQuery("a.b.example.com")
        assertEquals("a.b.example.com", Dns.parseQuery(q, 0, q.size)!!.name)
        val r = dnsAnswer(q, ip4(5, 6, 7, 8))
        assertEquals(listOf(ip4(5, 6, 7, 8)), Dns.parseResponse(r, 0, r.size)!!.addresses)
        assertEquals("example.com", Hello.tlsServerName(sniHello("example.com"), 0, sniHello("example.com").size))
        assertNull(Hello.tlsServerName(ByteArray(100), 0, 100))
        assertTrue(Dns.matches("ms.applovin.com", "applovin.com") && !Dns.matches("notapplovin.com", "applovin.com"))
    }
}
