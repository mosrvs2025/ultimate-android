package app.ultimate.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update

/**
 * Something a module noticed: Echo hearing a hand approach, a sensor tipping past a level, a model recognising a word.
 * [kind] is short and stable ("approach", "recede", "motion") so automations can match it; [label] is for people.
 * [source] is the module's id. Values in [data] are plain text, so any listener can use them without knowing the module.
 */
data class Signal(val source: String, val kind: String, val label: String, val data: Map<String, String> = emptyMap(), val at: Long = System.currentTimeMillis()) {
    /** "echo:approach", the form automations and logs use. */
    val key: String get() = "$source:$kind"
}

/**
 * The shared nervous system: modules publish [Signal]s here and anything may listen (Automations, the orb, a future local
 * model) without the modules knowing about each other. In memory only; nothing leaves the phone.
 */
class Senses {
    private val flow = MutableSharedFlow<Signal>(extraBufferCapacity = 64)
    val signals: SharedFlow<Signal> = flow.asSharedFlow()

    private val _recent = MutableStateFlow<List<Signal>>(emptyList())
    /** The last [KEEP] signals, newest first, for showing what the phone has sensed lately. */
    val recent: StateFlow<List<Signal>> = _recent

    /** Never blocks and never throws: a slow listener drops old signals rather than stalling the module that sensed them. */
    fun publish(s: Signal) {
        flow.tryEmit(s)
        _recent.update { (listOf(s) + it).take(KEEP) }
    }

    // ---- Live sessions: everything currently using a sensor, the mic, the network or the screen, each stoppable. ----

    /**
     * One running capability. [sensitive] marks sessions that reach outside the phone or listen continuously (remote
     * access, mic-based sensing); those are always shown with a Stop control.
     */
    data class Session(val id: String, val module: String, val label: String, val sensitive: Boolean, val startedAt: Long, val stop: () -> Unit)

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions

    fun begin(id: String, module: String, label: String, sensitive: Boolean, stop: () -> Unit) =
        _sessions.update { list -> list.filterNot { it.id == id } + Session(id, module, label, sensitive, System.currentTimeMillis(), stop) }

    fun end(id: String) = _sessions.update { list -> list.filterNot { it.id == id } }

    /** Stops one session; a module that fails to stop cleanly is still taken off the list, so the control never lies. */
    fun stop(id: String) { _sessions.value.firstOrNull { it.id == id }?.let { s -> runCatching { s.stop() } }; end(id) }

    fun stopAll() = _sessions.value.forEach { stop(it.id) }

    companion object { const val KEEP = 100 }
}
