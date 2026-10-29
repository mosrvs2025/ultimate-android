package app.ultimate.lan

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One scan of the local network at a time; results stay while the app is open. */
class LanManager(context: Context) {
    private val scanner = LanScanner(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    data class State(
        val info: NetInfo? = null,
        val scanning: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val devices: Map<String, Device> = emptyMap(),
        val gatewayMs: Long? = null,
        val internetMs: Long? = null,
        val finishedAt: Long = 0,
        val error: String? = null,
    ) {
        val sorted: List<Device> get() = devices.values.sortedWith(compareBy({ !it.isGateway }, { !it.isThisPhone }, { it.ip.substringAfterLast('.').toIntOrNull() ?: 0 }))
        val findings: List<LanFinding> get() = Lan.findings(devices.values.toList())
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun scan() {
        if (_state.value.scanning) return
        val info = scanner.netInfo()
        if (info == null) { _state.value = State(error = "Not connected to a network."); return }
        if (!info.wifi) { _state.value = State(info = info, error = "Connect to Wi-Fi to scan your home network. On mobile data there is no local network to look at."); return }
        _state.value = State(info = info, scanning = true)
        job = scope.launch {
            launch { _state.update { it.copy(internetMs = scanner.knock("1.1.1.1", 443, 2000)) } }
            info.gateway?.let { gw -> launch { _state.update { it.copy(gatewayMs = scanner.knock(gw, 53, 1000) ?: scanner.knock(gw, 80, 1000)) } } }
            scanner.scan(
                info,
                found = { d -> _state.update { s -> s.copy(devices = s.devices + (d.ip to (s.devices[d.ip]?.merge(d) ?: d))) } },
                progress = { done, total -> _state.update { it.copy(done = done, total = total) } },
            )
            _state.update { it.copy(scanning = false, finishedAt = System.currentTimeMillis()) }
        }
    }

    fun stop() {
        job?.cancel()
        _state.update { it.copy(scanning = false) }
    }
}
