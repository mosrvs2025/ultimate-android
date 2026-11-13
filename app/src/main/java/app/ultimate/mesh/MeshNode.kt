package app.ultimate.mesh

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.DoubleAdder

/**
 * One arm of the Octopus Mesh: a TCP endpoint that gossips its state, accepts signed tasks, and routes its own tasks
 * to whichever trusted node is expected to finish them first. Plain java.net, so the same class runs in the Android
 * app, in JVM tests, and on a computer.
 *
 * Nothing listens until [start] is called; [stop] closes the socket and forgets every peer immediately.
 */
class MeshNode(
    val name: String,
    private val key: ByteArray,
    val id: String = UUID.randomUUID().toString().take(12),
    private val bindHost: String = "0.0.0.0",
    private val port: Int = 0,
    /** Work units/ms. Measured with [Kernels.calibrate] unless a test pins it. */
    val capacity: Double = Kernels.calibrate(),
    private val battery: () -> Double? = { null },
    private val heartbeatMs: Long = 1_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    /** Test hook to make a node artificially slow: extra ms per work unit. */
    private val slowdownMsPerUnit: Double = 0.0,
    /** What this device is and can do, read fresh each heartbeat so a change (the owner starts lending) reaches the others at once. */
    private val profile: () -> DeviceProfile = { DeviceProfile.Unknown },
) {
    data class Peer(
        val id: String, val name: String, val host: String, val port: Int,
        val capacity: Double, val queuedWork: Double, val battery: Double?,
        val lastSeen: Long, val rttMs: Double, val failures: Int,
        val profile: DeviceProfile = DeviceProfile.Unknown,
    )

    /** A device appearing, vanishing or returning. Delivered on a mesh thread: hand off before touching UI. */
    sealed interface PeerEvent {
        val peer: Peer
        data class Joined(override val peer: Peer) : PeerEvent
        data class Returned(override val peer: Peer) : PeerEvent
        data class Lost(override val peer: Peer) : PeerEvent
    }

    /** Handles an authenticated command from [from]. Throw to refuse; the message goes back to the sender as the reason. */
    fun interface CommandHandler { fun handle(from: Peer, cmd: String, args: Map<String, String>): Map<String, String> }

    data class Outcome(val taskId: String, val value: Long, val executor: String, val attempts: Int, val totalMs: Long, val computeMs: Long)

    data class Stats(val ran: Long, val sent: Long, val received: Long, val bytesOut: Long, val bytesIn: Long, val rejected: Long, val failovers: Long)

    @Volatile var commands: CommandHandler? = null
    @Volatile var onEvent: (PeerEvent) -> Unit = {}
    private val wasAlive = ConcurrentHashMap<String, Boolean>()
    private val peers = ConcurrentHashMap<String, Peer>()
    private val seeds = ConcurrentHashMap.newKeySet<Pair<String, Int>>()
    private val replay = ReplayGuard()
    private val seq = AtomicLong(clock())
    private val queued = DoubleAdder()
    private val ran = AtomicLong(); private val sent = AtomicLong(); private val received = AtomicLong()
    private val bytesOut = AtomicLong(); private val bytesIn = AtomicLong(); private val rejected = AtomicLong(); private val failovers = AtomicLong()

    private var server: ServerSocket? = null
    private var io = Executors.newCachedThreadPool()
    private var worker = Executors.newSingleThreadExecutor()
    private var beats = Executors.newSingleThreadScheduledExecutor()
    @Volatile var running = false; private set

    val boundPort: Int get() = server?.localPort ?: -1
    val selfView: PeerView get() = PeerView(id, capacity, queued.sum(), 0.0, true, battery())
    fun peers(): List<Peer> = peers.values.sortedBy { it.name }
    fun stats() = Stats(ran.get(), sent.get(), received.get(), bytesOut.get(), bytesIn.get(), rejected.get(), failovers.get())
    fun alive(p: Peer) = clock() - p.lastSeen < heartbeatMs * 3 + 500

    @Synchronized fun start(): MeshNode {
        if (running) return this
        io = Executors.newCachedThreadPool(); worker = Executors.newSingleThreadExecutor(); beats = Executors.newSingleThreadScheduledExecutor()
        val s = ServerSocket(); s.reuseAddress = true; s.bind(InetSocketAddress(InetAddress.getByName(bindHost), port))
        server = s; running = true
        io.execute { acceptLoop(s) }
        beats.scheduleWithFixedDelay({ runCatching { heartbeat() } }, 0, heartbeatMs, TimeUnit.MILLISECONDS)
        log("listening on $bindHost:${s.localPort} as $name ($id)")
        return this
    }

    /** Revokes everything at once: closes the listener, drops peers, abandons queued work. */
    @Synchronized fun stop() {
        if (!running) return
        running = false
        runCatching { server?.close() }
        beats.shutdownNow(); worker.shutdownNow(); io.shutdownNow()
        peers.clear(); seeds.clear(); wasAlive.clear()
        log("stopped; all peers forgotten")
    }

    /** Adds an address to gossip with (from discovery, a QR code, or typed in). */
    fun addSeed(host: String, port: Int) { seeds += host to port; if (running) io.execute { runCatching { beatTo(host, port) } } }

    // ---- tasks ----

    /** Runs a primes(n) task wherever it is expected to finish first, failing over to other nodes and finally to itself. */
    fun submit(n: Int, timeoutMs: Long = 10_000): Outcome {
        val taskId = UUID.randomUUID().toString().take(8)
        val work = Kernels.workOf(n)
        val start = clock()
        val excluded = mutableSetOf<String>()
        var attempts = 0
        while (true) {
            attempts++
            val target = Routing.choose(work, selfView, peerViews(), excluded)
            if (target == id) {
                val (v, ms) = runLocal(n)
                return Outcome(taskId, v, id, attempts, clock() - start, ms)
            }
            val p = peers[target]
            if (p == null) { excluded += target; continue }
            try {
                val reply = request(p.host, p.port, MeshWire.Type.TASK, mapOf("task" to taskId, "kernel" to "primes", "n" to n.toString()), timeoutMs)
                if (reply.type != MeshWire.Type.RESULT) throw IllegalStateException(reply.payload["error"] ?: "refused")
                val value = reply.payload.getValue("value").toLong()
                // Cheap tasks are re-checked locally, so a misbehaving node is caught rather than trusted.
                if (n <= 200_000 && value != Kernels.primes(n)) throw SecurityException("wrong answer from ${p.name}")
                return Outcome(taskId, value, target, attempts, clock() - start, reply.payload["computeMs"]?.toLong() ?: -1)
            } catch (e: Exception) {
                log("task $taskId on ${p.name} failed (${e.message}); failing over")
                failovers.incrementAndGet(); excluded += target
                peers.computeIfPresent(target) { _, old -> old.copy(failures = old.failures + 1, lastSeen = 0) }
            }
        }
    }

    private fun runLocal(n: Int): Pair<Long, Long> {
        val work = Kernels.workOf(n)
        queued.add(work)
        try {
            val f = worker.submit<Pair<Long, Long>> {
                val t = System.nanoTime()
                val v = Kernels.primes(n)
                if (slowdownMsPerUnit > 0) Thread.sleep((work * slowdownMsPerUnit).toLong())
                ran.incrementAndGet()
                v to (System.nanoTime() - t) / 1_000_000
            }
            return f.get()
        } finally { queued.add(-work) }
    }

    private fun peerViews() = peers.values.map { PeerView(it.id, it.capacity, it.queuedWork, it.rttMs, alive(it) && it.failures < 3, it.battery) }

    // ---- gossip ----

    private fun heartbeat() {
        val targets = peers.values.map { it.host to it.port }.toSet() + seeds
        for ((h, p) in targets) io.execute { runCatching { beatTo(h, p) } }
        // A device that has stopped answering is announced once, not on every beat.
        for (p in peers.values) if (!alive(p) && wasAlive.replace(p.id, true, false)) emit(PeerEvent.Lost(p))
    }

    private fun emit(e: PeerEvent) { runCatching { onEvent(e) } }

    /**
     * Sends [cmd] to a connected device and returns its answer. Throws if the device is unknown, does not answer, or
     * refuses; the refusal reason (for instance "not lending") is the exception's message.
     */
    fun command(peerId: String, cmd: String, args: Map<String, String> = emptyMap(), timeoutMs: Long = 5_000): Map<String, String> {
        val p = peers[peerId] ?: throw IllegalStateException("that device is not connected")
        val r = request(p.host, p.port, MeshWire.Type.CMD, args + mapOf("cmd" to cmd), timeoutMs)
        if (r.type == MeshWire.Type.ERROR) throw IllegalStateException(r.payload["error"] ?: "refused")
        if (r.type != MeshWire.Type.ACK) throw IllegalStateException("unexpected reply ${r.type}")
        return r.payload
    }

    private fun beatTo(host: String, port: Int) {
        val t0 = System.nanoTime()
        val r = request(host, port, MeshWire.Type.BEAT, beatPayload(), 2_000)
        val rtt = (System.nanoTime() - t0) / 1e6
        learn(r, host, rtt)
        // Second-hand peers let a mesh form from a single seed (octopus arms talk without the brain).
        r.payload["knows"]?.split(',')?.filter { it.isNotBlank() }?.forEach { entry ->
            val (pid, addr) = entry.split('@'); val (h, pt) = addr.split(':')
            if (pid != id && !peers.containsKey(pid)) seeds += h to pt.toInt()
        }
    }

    private fun beatPayload() = buildMap {
        putAll(profile().toPayload())
        put("name", name); put("port", boundPort.toString()); put("cap", capacity.toString()); put("q", queued.sum().toString())
        battery()?.let { put("bat", it.toString()) }
        put("knows", peers.values.filter(::alive).joinToString(",") { "${it.id}@${it.host}:${it.port}" })
    }

    private fun learn(f: MeshWire.Frame, host: String, rtt: Double?) {
        if (f.from == id) return
        val p = f.payload
        val port = p["port"]?.toIntOrNull() ?: return
        var fresh = false
        val updated = peers.compute(f.from) { _, old ->
            val smoothed = when { rtt == null -> old?.rttMs ?: 5.0; old == null -> rtt; else -> old.rttMs * 0.7 + rtt * 0.3 }
            if (old == null) { fresh = true; log("joined: ${p["name"]} at $host:$port") }
            Peer(f.from, p["name"] ?: "?", host, port, p["cap"]?.toDoubleOrNull() ?: 1.0, p["q"]?.toDoubleOrNull() ?: 0.0,
                p["bat"]?.toDoubleOrNull(), clock(), smoothed, 0, DeviceProfile.fromPayload(p))
        }!!
        val before = wasAlive.put(f.from, true)
        if (fresh) emit(PeerEvent.Joined(updated)) else if (before == false) emit(PeerEvent.Returned(updated))
    }

    // ---- transport ----

    private fun frame(type: MeshWire.Type, payload: Map<String, String>) =
        MeshWire.encode(MeshWire.Frame(type, id, seq.incrementAndGet(), clock(), payload), key)

    private fun request(host: String, port: Int, type: MeshWire.Type, payload: Map<String, String>, timeoutMs: Long): MeshWire.Frame {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), minOf(timeoutMs, 3_000).toInt())
            s.soTimeout = timeoutMs.toInt()
            val out = PrintWriter(s.getOutputStream(), true)
            val line = frame(type, payload)
            out.println(line); sent.incrementAndGet(); bytesOut.addAndGet(line.length + 1L)
            val reply = BufferedReader(InputStreamReader(s.getInputStream())).readLine() ?: throw java.io.EOFException("no reply")
            bytesIn.addAndGet(reply.length + 1L)
            val f = MeshWire.decode(reply, key, clock())
            if (!replay.firstTime(f.from, f.seq)) throw SecurityException("replayed reply")
            return f
        }
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            val c = try { s.accept() } catch (_: Exception) { break }
            io.execute { c.use { handle(it) } }
        }
    }

    private fun handle(c: Socket) {
        c.soTimeout = 5_000
        val line = runCatching { BufferedReader(InputStreamReader(c.getInputStream())).readLine() }.getOrNull() ?: return
        bytesIn.addAndGet(line.length + 1L)
        val f = try { MeshWire.decode(line, key, clock()).also { if (!replay.firstTime(it.from, it.seq)) throw SecurityException("replay") } }
        catch (e: Exception) { rejected.incrementAndGet(); log("refused frame from ${c.inetAddress.hostAddress}: ${e.message}"); return }
        received.incrementAndGet()
        val host = c.inetAddress.hostAddress ?: return
        val reply = when (f.type) {
            MeshWire.Type.BEAT, MeshWire.Type.HELLO -> { learn(f, host, null); frame(MeshWire.Type.BEAT, beatPayload()) }
            MeshWire.Type.TASK -> {
                val n = f.payload["n"]?.toIntOrNull()
                if (f.payload["kernel"] != "primes" || n == null || n !in 2..50_000_000) frame(MeshWire.Type.ERROR, mapOf("error" to "unsupported task"))
                else try {
                    val (v, ms) = runLocal(n)
                    frame(MeshWire.Type.RESULT, mapOf("task" to (f.payload["task"] ?: ""), "value" to v.toString(), "computeMs" to ms.toString()))
                } catch (e: Exception) { frame(MeshWire.Type.ERROR, mapOf("error" to (e.message ?: "failed"))) }
            }
            MeshWire.Type.CMD -> {
                val from = peers[f.from]; val h = commands; val cmd = f.payload["cmd"]
                when {
                    h == null || from == null || cmd == null -> frame(MeshWire.Type.ERROR, mapOf("error" to "this device is not accepting commands"))
                    else -> try { frame(MeshWire.Type.ACK, h.handle(from, cmd, f.payload - "cmd")) }
                    catch (e: Exception) { frame(MeshWire.Type.ERROR, mapOf("error" to (e.message ?: "refused"))) }
                }
            }
            else -> frame(MeshWire.Type.ERROR, mapOf("error" to "unexpected ${f.type}"))
        }
        runCatching { PrintWriter(c.getOutputStream(), true).println(reply); bytesOut.addAndGet(reply.length + 1L) }
    }
}
