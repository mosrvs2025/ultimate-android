package app.ultimate.mesh

import android.content.Context
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs this phone's [MeshNode] while the user has the mesh switched on. Off by default and never started on its own.
 * Discovery: a signed UDP beacon broadcast on the local Wi-Fi every 2 s; only devices with the same pairing code can
 * verify it. Turning the mesh off closes every socket and forgets every peer.
 */
class MeshManager private constructor(private val app: Context) {
    data class Flow(val from: String, val to: String, val at: Long, val ok: Boolean)
    data class State(
        val running: Boolean = false,
        val selfId: String = "",
        val selfName: String = Build.MODEL,
        val port: Int = -1,
        val fingerprint: String = "",
        val capacity: Double = 0.0,
        val peers: List<MeshNode.Peer> = emptyList(),
        val alive: Set<String> = emptySet(),
        val flows: List<Flow> = emptyList(),
        val log: List<String> = emptyList(),
        val stats: MeshNode.Stats? = null,
        val busy: Int = 0,
        val error: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var node: MeshNode? = null
    private var jobs = listOf<Job>()
    private var udp: DatagramSocket? = null
    private var lock: WifiManager.MulticastLock? = null

    private fun log(line: String) = _state.update { it.copy(log = (listOf("%tT  %s".format(System.currentTimeMillis(), line)) + it.log).take(80)) }

    private fun battery(): Double? {
        val bm = app.getSystemService(BatteryManager::class.java) ?: return null
        if (bm.isCharging) return 1.0
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }?.div(100.0)
    }

    fun start(code: String) {
        if (_state.value.running) return
        scope.launch {
            try {
                val key = MeshWire.keyFromCode(code.trim())
                log("measuring this phone…")
                val cap = Kernels.calibrate()
                val n = MeshNode(Build.MODEL, key, port = PORT, capacity = cap, battery = ::battery, log = ::log).start()
                node = n
                lock = (app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).createMulticastLock("ultimate-mesh").apply { setReferenceCounted(false); acquire() }
                val sock = DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(BEACON_PORT)) }
                udp = sock
                _state.value = State(running = true, selfId = n.id, port = n.boundPort, fingerprint = MeshWire.fingerprint(key), capacity = cap, log = _state.value.log)
                jobs = listOf(
                    scope.launch { // beacon
                        var seq = 0L
                        while (isActive) {
                            val line = MeshWire.encode(MeshWire.Frame(MeshWire.Type.HELLO, n.id, System.currentTimeMillis() * 1000 + seq++, System.currentTimeMillis(), mapOf("port" to n.boundPort.toString())), key).toByteArray()
                            runCatching { sock.send(DatagramPacket(line, line.size, InetAddress.getByName("255.255.255.255"), BEACON_PORT)) }
                            delay(2_000)
                        }
                    },
                    scope.launch { // listen for beacons
                        val buf = ByteArray(2048); val replay = ReplayGuard()
                        while (isActive) {
                            val p = DatagramPacket(buf, buf.size)
                            runCatching { sock.receive(p) }.onFailure { return@launch }
                            val f = runCatching { MeshWire.decode(String(p.data, 0, p.length), key) }.getOrNull() ?: continue
                            if (f.from == n.id || !replay.firstTime(f.from, f.seq)) continue
                            val port = f.payload["port"]?.toIntOrNull() ?: continue
                            if (n.peers().none { it.id == f.from }) n.addSeed(p.address.hostAddress ?: continue, port)
                        }
                    },
                    scope.launch { // mirror node state for the UI
                        while (isActive) {
                            val peers = n.peers()
                            _state.update { s -> s.copy(peers = peers, alive = peers.filter(n::alive).map { it.id }.toSet(), stats = n.stats(),
                                flows = s.flows.filter { System.currentTimeMillis() - it.at < 2_500 }) }
                            delay(250)
                        }
                    },
                )
            } catch (e: Exception) {
                log("could not start: ${e.message}"); stop(); _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun addAddress(text: String) {
        val (h, p) = text.trim().split(':').let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: PORT) }
        node?.addSeed(h, p)
    }

    /** Sends [count] primes tasks through the mesh; each routes itself. */
    fun run(nValue: Int, count: Int = 1) {
        val n = node ?: return
        repeat(count) {
            scope.launch {
                _state.update { it.copy(busy = it.busy + 1) }
                try {
                    val o = n.submit(nValue)
                    _state.update { it.copy(flows = it.flows + Flow(n.id, o.executor, System.currentTimeMillis(), true)) }
                    val where = if (o.executor == n.id) "here (reflex)" else n.peers().firstOrNull { it.id == o.executor }?.name ?: o.executor
                    log("primes<$nValue = ${o.value} on $where in ${o.totalMs} ms" + if (o.attempts > 1) " after ${o.attempts - 1} failover(s)" else "")
                } catch (e: Exception) { log("task failed: ${e.message}") }
                finally { _state.update { it.copy(busy = it.busy - 1) } }
            }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }; jobs = emptyList()
        runCatching { udp?.close() }; udp = null
        runCatching { lock?.release() }; lock = null
        node?.stop(); node = null
        _state.update { State(log = it.log) }
        log("mesh off: sockets closed, peers forgotten")
    }

    companion object {
        const val PORT = 47801
        const val BEACON_PORT = 47800
        @Volatile private var instance: MeshManager? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: MeshManager(context.applicationContext).also { instance = it } }
    }
}
