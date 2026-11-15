package app.ultimate.mesh

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import app.ultimate.MainActivity
import app.ultimate.R
import app.ultimate.UltimateApp
import app.ultimate.core.Signal
import app.ultimate.movement.MovementStore
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
import kotlinx.coroutines.sync.withLock

/**
 * Runs this phone's side of the Octopus Mesh while the user has it switched on: finding the owner's other devices
 * (signed UDP broadcast, network-service discovery, Bluetooth presence), announcing new ones, answering their requests
 * if and only if the owner has chosen to lend this phone, and sending requests to them.
 * Off by default and never started on its own the first time. Turning it off closes every socket and forgets every peer.
 */
class MeshManager private constructor(private val app: Context) : DeviceFling.Provider {
    data class Flow(val from: String, val to: String, val at: Long, val ok: Boolean)

    /** A recording running on another device that this phone started. */
    data class RemoteRecording(
        val peerId: String, val peerName: String,
        val phase: Phase = Phase.Starting,
        val rows: Int = 0, val ms: Long = 0, val reps: Int = 0, val activeSeconds: Double = 0.0, val rms: Double = 0.0,
        val sets: List<RepCounter.EffortSet> = emptyList(),
        val fetched: Int = 0, val total: Int = 0, val keptInMovement: Boolean = false,
        val error: String? = null,
    ) { enum class Phase { Starting, Recording, Stopping, Fetching, Done, Failed } }

    sealed interface RangeState {
        data object Idle : RangeState
        data class Measuring(val peerName: String, val run: Int, val of: Int) : RangeState
        data class Result(val peerName: String, val metres: Double, val spreadMetres: Double, val runs: Int, val confidence: PeerRange.Confidence, val note: String) : RangeState
        data class Failed(val peerName: String, val why: String) : RangeState
    }

    /** The map of the owner's devices, built from the distances between every pair (see [HouseLayout]). */
    sealed interface HouseState {
        data object Idle : HouseState
        data class Measuring(val a: String, val b: String, val done: Int, val total: Int) : HouseState
        data class Done(val result: HouseLayout.Result, val names: Map<String, String>, val measured: List<HouseLayout.Measure>, val failed: List<String>, val at: Long) : HouseState
    }

    data class State(
        val running: Boolean = false,
        val selfId: String = "",
        val selfName: String = Build.MODEL,
        val profile: DeviceProfile = DeviceProfile.Unknown,
        val lends: Boolean = false,
        val accepts: Boolean = true,
        val recordingFor: String? = null,
        val code: String? = null,
        val port: Int = -1,
        val fingerprint: String = "",
        val capacity: Double = 0.0,
        val peers: List<MeshNode.Peer> = emptyList(),
        val alive: Set<String> = emptySet(),
        val known: List<Roster.Known> = emptyList(),
        val bleBands: Map<String, MeshBleCodec.Band> = emptyMap(),
        val strangersNearby: List<MeshBleCodec.Band> = emptyList(),
        val bleNote: String? = null,
        val notices: List<String> = emptyList(),
        val flows: List<Flow> = emptyList(),
        val log: List<String> = emptyList(),
        val stats: MeshNode.Stats? = null,
        val busy: Int = 0,
        val remote: RemoteRecording? = null,
        val range: RangeState = RangeState.Idle,
        val house: HouseState = HouseState.Idle,
        /** You on the device map, from the watch beacon (null while not tracking); and which devices could not hear it. */
        val you: WhereAmI.Estimate? = null,
        val youNote: String? = null,
        val error: String? = null,
    ) {
        /** How many times this phone's own speed the connected devices add, counting this one. */
        val meshPower: Double get() = if (capacity <= 0) 1.0 else 1.0 + peers.filter { it.id in alive }.sumOf { it.capacity } / capacity
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State(lends = MeshStore(app).lends, accepts = MeshStore(app).accepts))
    val state: StateFlow<State> = _state.asStateFlow()
    val store = MeshStore(app)
    private var roster = Roster.decode(store.roster)
    private var node: MeshNode? = null
    private var ble: MeshBle? = null
    private var nsd: MeshNsd? = null
    private var key: ByteArray? = null
    private var jobs = listOf<Job>()
    private var remoteJob: Job? = null
    private var udp: DatagramSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    private val host = MeshHost(app, lends = { store.lends }, onChange = ::onHostChange, notifyPing = ::notifyPing, accepts = { store.accepts }, rangeToPeer = { id -> rangeTo(id, runs = 2) })
    private val ranger = PeerRanger(app)
    private var rangeRecord: Job? = null

    private fun log(line: String) = _state.update { it.copy(log = (listOf("%tT  %s".format(System.currentTimeMillis(), line)) + it.log).take(80)) }
    private fun notice(text: String) = _state.update { it.copy(notices = (listOf(text) + it.notices).take(6)) }
    fun dismissNotice(text: String) = _state.update { it.copy(notices = it.notices - text) }

    private fun battery(): Double? {
        val bm = app.getSystemService(BatteryManager::class.java) ?: return null
        if (bm.isCharging) return 1.0
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }?.div(100.0)
    }

    private fun profile() = MeshProfiles.local(app, store.lends)

    /** Brings the mesh back if it was on when the app last closed. Quiet if it was not, or if Android will not allow it from here. */
    fun resumeIfWasOn() {
        if (_state.value.running || !store.wasOn) return
        store.loadCode()?.let { start(it, resumed = true) }
    }

    fun start(code: String, resumed: Boolean = false) {
        if (_state.value.running) return
        scope.launch {
            try {
                val k = MeshWire.keyFromCode(code.trim())
                key = k
                log("measuring this phone…")
                val cap = Kernels.calibrate()
                val n = MeshNode(Build.MODEL, k, port = PORT, capacity = cap, battery = ::battery, log = ::log, profile = ::profile).start()
                node = n
                n.commands = MeshNode.CommandHandler { from, cmd, args -> host.handle(from, cmd, args) }
                n.onEvent = ::onPeerEvent
                lock = (app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).createMulticastLock("ultimate-mesh").apply { setReferenceCounted(false); acquire() }
                val sock = DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(BEACON_PORT)) }
                udp = sock
                store.saveCode(code.trim()); store.wasOn = true
                val fp = MeshWire.fingerprint(k)
                _state.update { State(running = true, selfId = n.id, port = n.boundPort, fingerprint = fp, capacity = cap, profile = profile(), lends = store.lends, accepts = store.accepts, code = code.trim(), known = roster.known, log = it.log) }
                nsd = MeshNsd(app, fp, n.id, n.boundPort, ::log) { h, p -> if (n.peers().none { it.host == h && it.port == p }) n.addSeed(h, p) }.also { it.start() }
                ble = MeshBle(app, k, n.id) { }.also { it.start() }
                if (!refreshService() && resumed) {
                    log("could not resume in the background: open Octopus Mesh and press Start")
                    stop(); return@launch
                }
                jobs = listOf(
                    scope.launch { // beacon
                        var seq = 0L
                        while (isActive) {
                            val line = MeshWire.encode(MeshWire.Frame(MeshWire.Type.HELLO, n.id, System.currentTimeMillis() * 1000 + seq++, System.currentTimeMillis(), mapOf("port" to n.boundPort.toString())), k).toByteArray()
                            runCatching { sock.send(DatagramPacket(line, line.size, InetAddress.getByName("255.255.255.255"), BEACON_PORT)) }
                            delay(2_000)
                        }
                    },
                    scope.launch { // listen for beacons
                        val buf = ByteArray(2048); val replay = ReplayGuard()
                        while (isActive) {
                            val p = DatagramPacket(buf, buf.size)
                            runCatching { sock.receive(p) }.onFailure { return@launch }
                            val f = runCatching { MeshWire.decode(String(p.data, 0, p.length), k) }.getOrNull() ?: continue
                            if (f.from == n.id || !replay.firstTime(f.from, f.seq)) continue
                            val port = f.payload["port"]?.toIntOrNull() ?: continue
                            if (n.peers().none { it.id == f.from }) n.addSeed(p.address.hostAddress ?: continue, port)
                        }
                    },
                    scope.launch { // mirror node state for the UI
                        while (isActive) {
                            val peers = n.peers()
                            val near = ble?.nearby().orEmpty()
                            val byPeer = peers.mapNotNull { p -> near[MeshBleCodec.tagHex(k, p.id)]?.let { p.id to it.band } }.toMap()
                            nearness(peers, byPeer)
                            val matched = peers.map { MeshBleCodec.tagHex(k, it.id) }.toSet()
                            _state.update { s -> s.copy(peers = peers, alive = peers.filter(n::alive).map { it.id }.toSet(), stats = n.stats(), profile = profile(), lends = store.lends,
                                bleBands = byPeer, strangersNearby = near.filterKeys { it !in matched }.values.map { it.band }, bleNote = ble?.note,
                                recordingFor = host.recordingFor, known = roster.known,
                                flows = s.flows.filter { System.currentTimeMillis() - it.at < 2_500 }) }
                            delay(250)
                        }
                    },
                )
                if (resumed) log("mesh resumed")
            } catch (e: Exception) {
                log("could not start: ${e.message}"); stop(); _state.update { it.copy(error = e.message) }
            }
        }
    }

    // ---- who is here ----

    private fun onPeerEvent(e: MeshNode.PeerEvent) {
        val p = e.peer
        when (e) {
            is MeshNode.PeerEvent.Joined, is MeshNode.PeerEvent.Returned -> {
                val s = roster.seen(p.id, p.name, System.currentTimeMillis())
                store.roster = roster.encode()
                when (s) {
                    is Roster.Sighting.New -> {
                        senses()?.publish(Signal("mesh", "device_joined", "${p.name} joined your mesh", mapOf("device" to p.name)))
                        val text = "New device joined your mesh: ${p.name} (${p.profile.describe()})"
                        log(text); notice(text); announce("New device on your mesh", "${p.name} · ${p.profile.describe()}")
                    }
                    is Roster.Sighting.Back -> { log("${p.name} is back after ${s.awayMs / 60_000} min"); senses()?.publish(Signal("mesh", "device_back", "${p.name} is back in reach", mapOf("device" to p.name))) }
                    Roster.Sighting.Same -> log("${p.name} reconnected")
                }
            }
            is MeshNode.PeerEvent.Lost -> { log("${p.name} went out of reach"); senses()?.publish(Signal("mesh", "device_lost", "${p.name} went out of reach", mapOf("device" to p.name))) }
        }
    }

    private fun senses() = (app as? UltimateApp)?.senses

    /** Tells the senses bus when a known device crosses between "within arm's reach" and further away, once per crossing. */
    private val wasNear = HashMap<String, Boolean>()
    private fun nearness(peers: List<MeshNode.Peer>, bands: Map<String, MeshBleCodec.Band>) {
        for (p in peers) {
            val band = bands[p.id] ?: continue
            val near = band == MeshBleCodec.Band.Touching || band == MeshBleCodec.Band.Near
            val before = wasNear.put(p.id, near)
            if (before != null && before != near) senses()?.publish(Signal("mesh", if (near) "device_near" else "device_away", if (near) "${p.name} is within arm's reach" else "${p.name} moved away", mapOf("device" to p.name, "band" to band.name)))
        }
    }

    private fun announce(title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(ALERTS, "Mesh devices", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(app, 43, Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_TOOL, "mesh").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(title.hashCode() xor text.hashCode(), NotificationCompat.Builder(app, ALERTS).setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true).build())
    }

    private fun notifyPing(fromName: String) = announce("$fromName is looking for this phone", "Sent from Octopus Mesh")

    private fun onHostChange() { _state.update { it.copy(recordingFor = host.recordingFor) }; refreshService() }

    /** (Re)posts the mesh's ongoing notification, which carries the current status. */
    private fun refreshService(): Boolean = runCatching { app.startForegroundService(Intent(app, MeshService::class.java)) }.isSuccess

    fun forgetDevice(id: String) { roster.forget(id); store.roster = roster.encode() }

    /** Removes the saved code and every remembered device, and stops the mesh. */
    fun forgetEverything() { stop(); roster.forgetAll(); store.forgetAll(); _state.update { it.copy(code = null, known = emptyList()) } }

    fun setLends(on: Boolean) {
        store.lends = on
        if (!on) host.stopRecording()
        _state.update { it.copy(lends = on, profile = profile()) }
        log(if (on) "this phone is now lending its sensors and speaker to your devices" else "this phone stopped lending")
    }

    fun stopLocalRecording() = host.stopRecording()

    fun addAddress(text: String) {
        val (h, p) = text.trim().split(':').let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: PORT) }
        node?.addSeed(h, p)
    }

    // ---- flinging things to the owner's other devices ----

    init { DeviceFling.provider = this }

    fun setAccepts(on: Boolean) { store.accepts = on; _state.update { it.copy(accepts = on) } }

    override fun targets(): List<DeviceFling.Target> {
        val s = _state.value
        if (!s.running) return emptyList()
        // The device you are standing by (from the device map) comes first, then those this phone can feel nearby.
        val byYou = s.you?.nearest
        return s.peers.filter { it.id in s.alive }.map { p ->
            val band = s.bleBands[p.id]
            DeviceFling.Target(p.id, p.name, p.profile.kind.key, p.id == byYou || band == MeshBleCodec.Band.Touching || band == MeshBleCodec.Band.Near)
        }.sortedWith(compareByDescending<DeviceFling.Target> { it.id == byYou }.thenByDescending { it.near })
    }

    private suspend fun fling(targetId: String, what: String, block: (MeshNode) -> Unit): String? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val n = node ?: return@withContext "Octopus Mesh is off"
        val name = peerName(targetId)
        try {
            block(n); log("sent $what to $name")
            _state.update { it.copy(flows = it.flows + Flow(it.selfId, targetId, System.currentTimeMillis(), true)) }
            null
        } catch (e: Exception) { log("could not send $what to $name: ${e.message}"); e.message ?: "it did not answer" }
    }

    override suspend fun app(targetId: String, pkg: String, label: String) = fling(targetId, label) { it.command(targetId, Cmd.FLING_APP, mapOf("pkg" to pkg, "label" to label)) }

    override suspend fun text(targetId: String, text: String) = fling(targetId, "a link") { it.command(targetId, Cmd.FLING_TEXT, mapOf("text" to text.take(4_000))) }

    override suspend fun file(targetId: String, name: String, mime: String, bytes: ByteArray, progress: (Float) -> Unit) = fling(targetId, Fling.safeName(name)) { n ->
        val frames = Fling.frames(java.util.UUID.randomUUID().toString(), name, mime, bytes)
        frames.forEachIndexed { i, (cmd, args) -> n.command(targetId, cmd, args, timeoutMs = 15_000); progress((i + 1f) / frames.size) }
    }

    fun startBluetooth(): Boolean = ble?.start() ?: false

    fun ping(peerId: String) = scope.launch {
        try { node?.command(peerId, Cmd.PING); log("pinged ${peerName(peerId)}") } catch (e: Exception) { log("ping failed: ${e.message}") }
    }

    private fun peerName(id: String) = node?.peers()?.firstOrNull { it.id == id }?.name ?: id

    // ---- remote recording ----

    fun recordOn(peerId: String) {
        val n = node ?: return
        remoteJob?.cancel()
        val name = peerName(peerId)
        _state.update { it.copy(remote = RemoteRecording(peerId, name)) }
        remoteJob = scope.launch {
            try {
                n.command(peerId, Cmd.REC_START, mapOf("rate" to "50"))
                setRemote { it.copy(phase = RemoteRecording.Phase.Recording) }
                log("recording motion on $name")
                while (isActive && _state.value.remote?.phase == RemoteRecording.Phase.Recording) {
                    delay(1_500)
                    runCatching { n.command(peerId, Cmd.REC_STATUS) }.onSuccess { r -> applyStatus(r) }
                        .onFailure { setRemote { s -> s.copy(error = "lost contact with $name; it keeps recording and you can reconnect") } }
                }
            } catch (e: Exception) { setRemote { it.copy(phase = RemoteRecording.Phase.Failed, error = e.message) } }
        }
    }

    private fun applyStatus(r: Map<String, String>) = setRemote { s ->
        s.copy(error = null, rows = r["rows"]?.toIntOrNull() ?: s.rows, ms = r["ms"]?.toLongOrNull() ?: s.ms, reps = r["reps"]?.toIntOrNull() ?: s.reps,
            activeSeconds = r["active"]?.toDoubleOrNull() ?: s.activeSeconds, rms = r["rms"]?.toDoubleOrNull() ?: s.rms,
            sets = (r["setsDetail"] ?: "").split(';').filter { it.isNotBlank() }.mapNotNull { d ->
                val x = d.split(':'); runCatching { RepCounter.EffortSet(x[0].toDouble(), x[1].toDouble(), x[2].toInt(), x[3].toDouble(), x[4].toDouble()) }.getOrNull()
            })
    }

    private fun setRemote(f: (RemoteRecording) -> RemoteRecording) = _state.update { s -> s.remote?.let { s.copy(remote = f(it)) } ?: s }

    /** Stops the recording on the other device, then pulls the whole recording across in small pieces. */
    fun stopRemoteRecording() {
        val n = node ?: return; val r = _state.value.remote ?: return
        remoteJob?.cancel()
        setRemote { it.copy(phase = RemoteRecording.Phase.Stopping) }
        remoteJob = scope.launch {
            try {
                applyStatus(n.command(r.peerId, Cmd.REC_STOP))
                val total = _state.value.remote?.rows ?: 0
                val copy = MotionLog()
                setRemote { it.copy(phase = RemoteRecording.Phase.Fetching, total = total, fetched = 0) }
                var from = 0
                while (from < total && isActive) {
                    val chunk = n.command(r.peerId, Cmd.REC_FETCH, mapOf("from" to from.toString()), timeoutMs = 8_000)
                    val got = MotionLog.decodeInto(copy, chunk["data"] ?: break)
                    if (got <= 0) break
                    from += got
                    setRemote { it.copy(fetched = from) }
                }
                lastFetched = copy
                val kept = runCatching { MovementStore(app).importMotion(copy, r.peerName) != null }.getOrDefault(false)
                setRemote { it.copy(phase = RemoteRecording.Phase.Done, keptInMovement = kept) }
                log("received ${copy.rows} rows from ${r.peerName}")
            } catch (e: Exception) { setRemote { it.copy(phase = RemoteRecording.Phase.Failed, error = "could not finish: ${e.message}") } }
        }
    }

    fun dismissRemote() { remoteJob?.cancel(); lastFetched = null; _state.update { it.copy(remote = null) } }

    @Volatile private var lastFetched: MotionLog? = null

    /** Saves the pulled recording as CSV in Downloads/Ultimate; returns the file name or null. */
    fun saveRecordingCsv(): String? {
        val log = lastFetched ?: return null
        val name = "motion-${_state.value.remote?.peerName?.filter { it.isLetterOrDigit() } ?: "device"}-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())}.csv"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, "text/csv"); put(MediaStore.Downloads.RELATIVE_PATH, "Download/Ultimate")
        }
        return runCatching {
            app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!.also { u -> app.contentResolver.openOutputStream(u)!!.use { it.write(log.csv().toByteArray()) } }
            name
        }.getOrNull()
    }

    // ---- distance by sound ----

    /** Measures the distance to [peerId] [runs] times and reports the median and how much the runs disagreed. */
    /** One distance between this phone and [peerId]: the median of [runs] sound measurements, or why it failed. */
    sealed interface Ranged {
        data class Ok(val metres: Double, val spread: Double, val runs: Int, val confidence: PeerRange.Confidence, val note: String) : Ranged
        data class Failed(val why: String) : Ranged
    }

    private val rangeLock = kotlinx.coroutines.sync.Mutex()

    /** Measures by sound to [peerId] (blocking the caller for a few seconds per run). One measurement at a time per phone. */
    suspend fun rangeTo(peerId: String, runs: Int = 3, progress: (Int) -> Unit = {}): Ranged = rangeLock.withLock {
        val n = node ?: return@withLock Ranged.Failed("Octopus Mesh is off")
        val name = peerName(peerId)
        val results = mutableListOf<PeerRange.Result>()
        var lastFail: String? = null
        for (i in 1..runs) {
            progress(i)
            try {
                n.command(peerId, Cmd.RANGE_ARM, timeoutMs = 4_000)
                val mine = ranger.run(PeerRange.Role.Caller)
                if (mine is PeerRanger.Outcome.Failed) { lastFail = "this phone: ${mine.why}"; if (i == 1) break else continue }
                val side = (mine as PeerRanger.Outcome.Done).side
                var theirs: Map<String, String>? = null
                for (attempt in 0 until 10) {
                    val r = n.command(peerId, Cmd.RANGE_RESULT)
                    if (r["state"] == "pending") { delay(400); continue }
                    theirs = r; break
                }
                if (theirs == null) { lastFail = "$name did not report in time"; continue }
                if (theirs["state"] != "done") { lastFail = "$name: ${theirs["why"] ?: "no result"}"; continue }
                val other = MeshHost.decodeSide(theirs) ?: run { lastFail = "$name sent an unreadable result"; null } ?: continue
                val r = PeerRange.distance(side, other)
                if (r.confidence == PeerRange.Confidence.None) lastFail = r.note else results += r
            } catch (e: Exception) { lastFail = e.message; if (i == 1) break }
            delay(500)
        }
        val usable = results.map { it.metres }.sorted()
        if (usable.isEmpty()) return@withLock Ranged.Failed(lastFail ?: "no measurement")
        val median = usable[usable.size / 2]
        val spread = usable.last() - usable.first()
        val conf = if (results.all { r -> r.confidence == PeerRange.Confidence.Good } && spread < 0.10) PeerRange.Confidence.Good else PeerRange.Confidence.Doubtful
        Ranged.Ok(median, spread, usable.size, conf,
            if (usable.size < runs) "${usable.size} of $runs runs worked" else if (spread >= 0.10) "the runs disagreed by ${(spread * 100).toInt()} cm: a reflective room, or something in the way" else "the runs agree")
    }

    fun measureDistance(peerId: String, runs: Int = 3) {
        if (node == null || _state.value.range is RangeState.Measuring) return
        val name = peerName(peerId)
        rangeRecord = scope.launch {
            val r = rangeTo(peerId, runs) { i -> _state.update { it.copy(range = RangeState.Measuring(name, i, runs)) } }
            _state.update { it.copy(range = when (r) {
                is Ranged.Failed -> RangeState.Failed(name, r.why)
                is Ranged.Ok -> RangeState.Result(name, r.metres, r.spread, r.runs, r.confidence, r.note)
            }) }
        }
    }

    // ---- the map of all the owner's devices ----

    private var houseJob: Job? = null

    /**
     * Measures every pair among this phone and the connected devices that lend themselves, one pair at a time (two chirps
     * at once would confuse each other), then solves the map. Pairs that do not involve this phone are measured by one of
     * the two devices, asked over the mesh.
     */
    fun mapHouse() {
        val n = node ?: return
        if (_state.value.house is HouseState.Measuring) return
        houseJob = scope.launch {
            val me = n.id
            val peers = n.peers().filter { n.alive(it) }
            val names = mapOf(me to "${Build.MODEL} (this phone)") + peers.associate { it.id to it.name }
            val ids = listOf(me) + peers.map { it.id }
            val pairs = ids.flatMapIndexed { i, a -> ids.drop(i + 1).map { b -> a to b } }
            val measured = ArrayList<HouseLayout.Measure>(); val failed = ArrayList<String>()
            pairs.forEachIndexed { k, (a, b) ->
                _state.update { it.copy(house = HouseState.Measuring(names.getValue(a), names.getValue(b), k, pairs.size)) }
                val r: Ranged = try {
                    if (a == me) rangeTo(b, runs = 2) else {
                        n.command(a, Cmd.RANGE_PAIR, mapOf("to" to b), timeoutMs = 4_000)
                        var res: Map<String, String>? = null
                        for (t in 0 until 60) { delay(500); val q = n.command(a, Cmd.RANGE_PAIR_RESULT); if (q["state"] != "pending") { res = q; break } }
                        when (res?.get("state")) {
                            "done" -> Ranged.Ok(res.getValue("metres").toDouble(), 0.0, 2, if (res["good"] == "1") PeerRange.Confidence.Good else PeerRange.Confidence.Doubtful, "")
                            null -> Ranged.Failed("no answer in time")
                            else -> Ranged.Failed(res["why"] ?: "no result")
                        }
                    }
                } catch (e: Exception) { Ranged.Failed(e.message ?: "failed") }
                when (r) {
                    is Ranged.Ok -> measured += HouseLayout.Measure(a, b, r.metres, if (r.confidence == PeerRange.Confidence.Good) 1.0 else 0.35)
                    is Ranged.Failed -> failed += "${names.getValue(a)} ↔ ${names.getValue(b)}: ${r.why}"
                }
                delay(300)
            }
            store.house = HouseStore.encode(names, measured)
            val result = HouseLayout.solve(ids, measured)
            _state.update { it.copy(house = HouseState.Done(result, names, measured, failed, System.currentTimeMillis())) }
            log("device map: ${measured.size} of ${pairs.size} distances measured")
            senses()?.publish(Signal("mesh", "house_mapped", "Mapped ${ids.size} devices", mapOf("devices" to ids.size.toString())))
        }
    }

    private var youJob: Job? = null
    private var lastNearest: String? = null

    /** Keeps placing you on the map every few seconds while [on] (the map card is showing and tracking is switched on). */
    fun trackYou(on: Boolean) {
        youJob?.cancel(); youJob = null
        if (!on) { _state.update { it.copy(you = null, youNote = null) }; return }
        youJob = scope.launch {
            while (isActive) {
                val n = node
                val map = ((_state.value.house as? HouseState.Done)?.result as? HouseLayout.Result.Map)?.at
                if (n == null || map == null) { _state.update { it.copy(you = null, youNote = "Map your devices first.") }; delay(3_000); continue }
                fun parse(id: String, r: Map<String, String>): WhereAmI.Heard? {
                    val dbm = r["dbm"]?.toDoubleOrNull() ?: return null
                    return WhereAmI.Heard(id, dbm, r["touch"]?.toDoubleOrNull() ?: -45.0, r["arm"]?.toDoubleOrNull() ?: -70.0, r["age"]?.toLongOrNull() ?: 0)
                }
                val deaf = ArrayList<String>()
                val heard = ArrayList<WhereAmI.Heard>()
                val mine = host.heard()
                parse(n.id, mine)?.let { heard += it } ?: deaf.add(if (mine["running"] == "1") "this phone (not hearing the watch)" else "this phone (Spatial sensing is off)")
                for (p in n.peers().filter { it.id in map && n.alive(it) }) {
                    val r = runCatching { n.command(p.id, Cmd.WATCH_HEARD, timeoutMs = 2_000) }.getOrNull()
                    parse(p.id, r ?: emptyMap())?.let { heard += it } ?: deaf.add(p.name + if (r?.get("running") == "0") " (Spatial sensing is off)" else "")
                }
                val est = WhereAmI.estimate(map, heard)
                // Rules can react to which device you walk up to ("When I'm near the S21…"): once per change, and only
                // when the estimate is sure, so standing between two phones does not flicker back and forth.
                if (est.sure && est.nearest != null && est.nearest != lastNearest) {
                    lastNearest = est.nearest
                    val name = if (est.nearest == n.id) Build.MODEL else peerName(est.nearest)
                    senses()?.publish(Signal("mesh", "you_near", "You're by the $name", mapOf("device" to name)))
                }
                _state.update { it.copy(you = est, youNote = if (deaf.isEmpty()) null else "Not hearing your watch: " + deaf.joinToString()) }
                delay(2_500)
            }
        }
    }

    /** The last map, solved again from the distances kept from last time (shown before measuring anew). */
    fun loadHouse() {
        if (_state.value.house !is HouseState.Idle) return
        val (names, measured) = HouseStore.decode(store.house) ?: return
        if (measured.isEmpty()) return
        _state.update { it.copy(house = HouseState.Done(HouseLayout.solve(names.keys.toList(), measured), names, measured, emptyList(), 0L)) }
    }

    fun clearRange() = _state.update { it.copy(range = RangeState.Idle) }

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
        remoteJob?.cancel(); rangeRecord?.cancel()
        host.stopRecording()
        runCatching { nsd?.stop() }; nsd = null
        runCatching { ble?.stop() }; ble = null
        runCatching { udp?.close() }; udp = null
        runCatching { lock?.release() }; lock = null
        node?.stop(); node = null; key = null
        store.wasOn = false
        runCatching { app.stopService(Intent(app, MeshService::class.java)) }
        _state.update { State(lends = store.lends, accepts = store.accepts, log = it.log, known = roster.known, code = store.loadCode()) }
        log("mesh off: sockets closed, peers forgotten")
    }

    companion object {
        const val PORT = 47801
        const val BEACON_PORT = 47800
        private const val ALERTS = "mesh-devices"
        @Volatile private var instance: MeshManager? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: MeshManager(context.applicationContext).also { instance = it } }
    }
}
