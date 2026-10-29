package app.ultimate.traffic.engine

import app.ultimate.R
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import app.ultimate.core.JobLog
import app.ultimate.core.JsonFile
import app.ultimate.core.WorkSnapshot
import app.ultimate.core.WorkSource
import app.ultimate.core.formatBytes
import app.ultimate.traffic.model.Aggregator
import app.ultimate.traffic.model.AppTraffic
import app.ultimate.traffic.model.DnsView
import app.ultimate.traffic.model.DomainKind
import app.ultimate.traffic.model.FlowView
import app.ultimate.traffic.model.TrackerDomains
import app.ultimate.traffic.model.TrafficSnapshot
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** What the user chose before starting. */
data class CaptureConfig(val packages: List<String>, val showNames: Boolean, val blocked: Set<String>, val autoStopMinutes: Int = 30)

/** A capture that has ended; kept so the summary can be shown and a report saved. */
data class FinishedSession(val startedAt: Long, val endedAt: Long, val snapshot: TrafficSnapshot, val labels: List<String>, val reason: String?)

data class TrafficState(
    val running: Boolean = false,
    val startedAt: Long = 0,
    val captured: List<String> = emptyList(),
    val snapshot: TrafficSnapshot = TrafficSnapshot(),
    val error: String? = null,
    val finished: FinishedSession? = null,
    /** When the inspector will stop by itself, or 0 if it runs until stopped. */
    val autoStopAt: Long = 0,
)

/** A saved report, shown in the Library. */
data class TrafficReport(
    val id: String, val createdAt: Long, val durationMs: Long, val apps: List<String>, val up: Long, val down: Long,
    val hosts: Int, val trackerHosts: Int, val top: List<String>, val csvUri: String?,
)

/** Owns the capture: starts the tunnel service, turns engine state into snapshots a few times a second, and keeps the terminal log. */
class TrafficManager(private val context: Context) : WorkSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(TrafficState())
    val state: StateFlow<TrafficState> = _state.asStateFlow()
    override val notifyIcon = R.drawable.ic_stat_traffic
    override val notifyColor = 0xFF5E35B1.toInt()
    override val workId = "traffic"
    override val workTitle = "Traffic inspector"
    override val log = JobLog()
    override val work: StateFlow<WorkSnapshot> = _state.map { s ->
        if (s.running) WorkSnapshot(active = true, title = "Inspecting ${s.captured.size} app${if (s.captured.size == 1) "" else "s"}", detail = "${formatBytes(s.snapshot.up + s.snapshot.down)} seen")
        else s.finished?.reason?.let { WorkSnapshot(doneTitle = "Traffic inspector stopped", doneText = it) } ?: WorkSnapshot()
    }.stateIn(scope, SharingStarted.Eagerly, WorkSnapshot())
    override val cancelLabel = "Stop"
    override fun cancelWork() = stop()

    @Volatile var pendingConfig: CaptureConfig? = null
    @Volatile private var engine: TrafficEngine? = null
    private var ticker: Job? = null
    private val labels = ConcurrentHashMap<Int, Pair<String?, String>>()
    private val announced = ConcurrentHashMap.newKeySet<String>()
    private val reports = JsonFile(File(context.filesDir, "traffic.json"))
    private val _reports = MutableStateFlow(loadReports())
    val savedReports: StateFlow<List<TrafficReport>> = _reports.asStateFlow()
    private var userBlocked: Set<String> = emptySet()

    private companion object {
        /** Attempts with no success at all for this long mean the tunnel is not carrying traffic. */
        const val STALL_MS = 40_000L
        const val STALL_MIN_ATTEMPTS = 8
    }

    /** Call after the user has approved Android's VPN dialog. */
    fun start(config: CaptureConfig) {
        if (_state.value.running) return
        pendingConfig = config
        userBlocked = config.blocked
        announced.clear()
        labels.clear()
        log.reset()
        log.info("Traffic inspector · ${config.packages.size} app${if (config.packages.size == 1) "" else "s"} · site names ${if (config.showNames) "on (HTTP/3 refused)" else "off"}")
        val now = System.currentTimeMillis()
        stopRequested = false
        _state.value = TrafficState(
            running = true, startedAt = now, captured = config.packages,
            autoStopAt = if (config.autoStopMinutes > 0) now + config.autoStopMinutes * 60_000L else 0,
        )
        context.startService(Intent(context, TrafficVpnService::class.java))
    }

    @Volatile private var stopRequested = false

    /** Stops the capture and gives the apps their normal network back. [reason] is shown to the user when the stop was not their own. */
    fun stop(reason: String? = null) {
        stopRequested = true
        context.startService(Intent(context, TrafficVpnService::class.java).setAction(TrafficVpnService.ACTION_STOP).putExtra(TrafficVpnService.EXTRA_REASON, reason))
    }

    /** Applies the user's blocked domains to the running capture right away. */
    fun setBlocked(domains: Set<String>) {
        userBlocked = domains
        engine?.blocked = domains
    }

    fun dismissSummary() = _state.update { it.copy(finished = null, error = null) }

    internal fun onEstablished(e: TrafficEngine) {
        engine = e
        e.blocked = userBlocked
        ticker?.cancel()
        var okAt = System.currentTimeMillis()
        var okSuccesses = 0L
        var okAttempts = 0L
        ticker = scope.launch {
            while (isActive) {
                val snap = snapshot(e)
                _state.update { if (it.running) it.copy(snapshot = snap) else it }
                narrate(snap)
                val now = System.currentTimeMillis()
                // Safety nets, so the chosen apps are never left offline by an inspector that is forgotten or not working.
                if (!stopRequested) {
                    val until = _state.value.autoStopAt
                    val successes = e.successes.get()
                    val attempts = e.attempts.get()
                    if (successes > okSuccesses) { okSuccesses = successes; okAttempts = attempts; okAt = now }
                    when {
                        until > 0 && now >= until -> stop("Stopped automatically after ${(until - _state.value.startedAt) / 60_000} minutes. Your apps are back to normal.")
                        attempts - okAttempts >= STALL_MIN_ATTEMPTS && now - okAt >= STALL_MS ->
                            stop("Nothing could reach the internet for ${STALL_MS / 1000} seconds, so the inspector stopped and your apps are back to normal.")
                    }
                }
                delay(500)
            }
        }
    }

    internal fun onEnded(reason: String?) {
        val e = engine
        engine = null
        ticker?.cancel()
        val snap = e?.let { snapshot(it) } ?: _state.value.snapshot
        val s = _state.value
        val names = s.captured.map { labelOf(it) }
        if (reason != null && reason != "Stopped") log.warn(reason) else log.ok("Stopped · ${formatBytes(snap.up + snap.down)} seen across ${snap.hosts.size} hosts")
        _state.value = TrafficState(
            finished = if (s.running || e != null) FinishedSession(s.startedAt, System.currentTimeMillis(), snap, names, reason?.takeIf { it != "Stopped" }) else null,
            error = reason?.takeIf { it != "Stopped" && snap.hosts.isEmpty() },
        )
        pendingConfig = null
    }

    private fun snapshot(e: TrafficEngine): TrafficSnapshot {
        val captured = _state.value.captured
        val flows = e.records.values.sortedByDescending { it.lastAt }.take(600).map { r ->
            val (pkg, label) = resolve(r.uid, captured)
            FlowView(
                r.id, pkg, label, r.protocol, r.remoteIp.ipString(), r.remotePort, r.host, r.hostSource, r.up.get(), r.down.get(), r.startedAt, r.lastAt,
                r.open, r.blocked, r.cleartext, r.error,
            )
        }
        val dns = e.dnsLog.toList().takeLast(400).reversed().map { d ->
            val (pkg, label) = resolve(d.uid, captured)
            DnsView(d.at, pkg, label, d.name, Dns.typeName(d.type), d.blocked, TrackerDomains.classify(d.name))
        }
        return Aggregator.build(flows, dns)
    }

    /** Maps an Android user id to an app. With a single captured app any unknown owner must be that app, since only its traffic enters the tunnel. */
    private fun resolve(uid: Int, captured: List<String>): Pair<String?, String> {
        if (uid < 0) return if (captured.size == 1) captured[0] to labelOf(captured[0]) else null to "Unknown app"
        return labels.getOrPut(uid) {
            val pkg = context.packageManager.getPackagesForUid(uid)?.firstOrNull { it in captured } ?: context.packageManager.getPackagesForUid(uid)?.firstOrNull()
            pkg to (pkg?.let(::labelOf) ?: "UID $uid")
        }
    }

    @Suppress("DEPRECATION")
    private fun labelOf(pkg: String): String = runCatching {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0)) else pm.getApplicationInfo(pkg, 0)
        pm.getApplicationLabel(info).toString()
    }.getOrDefault(pkg)

    /** Writes the interesting moments into the terminal: the first time each tracker is contacted, and any unencrypted request. */
    private fun narrate(s: TrafficSnapshot) {
        s.hosts.forEach { h ->
            val hit = h.hit
            if (hit != null && announced.add("t:${h.host}")) {
                val who = s.apps.filter { a -> a.hosts.any { it.host == h.host } }.joinToString(", ") { it.label }.ifEmpty { "an app" }
                val line = "${h.host} → ${hit.sdk} (${hit.kind.label.lowercase()}) from $who"
                if (hit.kind == DomainKind.Ads) log.warn(line) else log.detail(line)
            }
        }
        s.flows.forEach { f ->
            if (f.cleartext != null && announced.add("c:${f.id}")) log.warn("UNENCRYPTED ${f.label} → ${f.name}: ${f.cleartext}")
            if (f.blocked && announced.add("b:${f.id}")) log.detail("blocked ${f.name} for ${f.label}")
        }
        s.dns.firstOrNull { it.blocked }?.let { if (announced.add("d:${it.name}")) log.detail("refused DNS ${it.name}") }
    }

    // ───────────────────────────── saved reports ────────────────────────────

    /** Saves the finished session as a CSV in Downloads/Ultimate and adds it to the Library. */
    fun saveReport(session: FinishedSession): TrafficReport {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(session.startedAt))
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "traffic-$stamp.csv")
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Ultimate")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { it.write(Aggregator.csv(session.snapshot.apps).toByteArray()) }
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0); resolver.update(uri, values, null, null)
        }
        val snap = session.snapshot
        val report = TrafficReport(
            UUID.randomUUID().toString(), System.currentTimeMillis(), session.endedAt - session.startedAt, session.labels, snap.up, snap.down,
            snap.hosts.size, snap.trackerHosts.size, snap.hosts.take(8).map { "${it.host}|${it.hit?.sdk.orEmpty()}|${it.total}" }, uri?.toString(),
        )
        _reports.update { listOf(report) + it }
        scope.launch { persistReports() }
        return report
    }

    suspend fun removeReport(id: String, deleteFile: Boolean): Boolean {
        val r = _reports.value.firstOrNull { it.id == id } ?: return true
        if (deleteFile && r.csvUri != null) {
            val ok = runCatching { context.contentResolver.delete(android.net.Uri.parse(r.csvUri), null, null) > 0 }.getOrDefault(false)
            if (!ok) return false
        }
        _reports.update { list -> list.filterNot { it.id == id } }
        persistReports()
        return true
    }

    private suspend fun persistReports() = reports.write(
        JSONArray().apply {
            _reports.value.forEach {
                put(
                    JSONObject().put("id", it.id).put("at", it.createdAt).put("dur", it.durationMs).put("apps", JSONArray(it.apps)).put("up", it.up).put("down", it.down)
                        .put("hosts", it.hosts).put("trackers", it.trackerHosts).put("top", JSONArray(it.top)).apply { it.csvUri?.let { u -> put("csv", u) } },
                )
            }
        },
    )

    private fun loadReports(): List<TrafficReport> {
        val a = reports.read()
        return (0 until a.length()).mapNotNull {
            runCatching {
                val o = a.getJSONObject(it)
                fun strings(k: String) = o.getJSONArray(k).let { arr -> (0 until arr.length()).map { i -> arr.getString(i) } }
                TrafficReport(o.getString("id"), o.getLong("at"), o.getLong("dur"), strings("apps"), o.getLong("up"), o.getLong("down"), o.getInt("hosts"), o.getInt("trackers"), strings("top"), o.optString("csv").ifEmpty { null })
            }.getOrNull()
        }
    }
}
