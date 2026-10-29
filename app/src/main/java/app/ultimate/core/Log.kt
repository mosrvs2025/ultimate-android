package app.ultimate.core

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class LogLevel { Info, Detail, Ok, Warn, Error }

data class LogLine(val elapsedMs: Long, val level: LogLevel, val text: String)

/**
 * A run's technical log, shown in the terminal panel: what was decided, which codecs ran, how fast, what was written.
 * Safe to append from any thread. Keeps the most recent [capacity] lines.
 */
class JobLog(private val capacity: Int = 600) {
    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines.asStateFlow()
    @Volatile private var started = SystemClock.elapsedRealtime()

    /** Starts a fresh log for a new run. */
    fun reset() {
        started = SystemClock.elapsedRealtime()
        _lines.value = emptyList()
    }

    fun info(text: String) = add(LogLevel.Info, text)
    fun detail(text: String) = add(LogLevel.Detail, text)
    fun ok(text: String) = add(LogLevel.Ok, text)
    fun warn(text: String) = add(LogLevel.Warn, text)
    fun error(text: String) = add(LogLevel.Error, text)

    fun add(level: LogLevel, text: String) {
        val line = LogLine(SystemClock.elapsedRealtime() - started, level, text)
        _lines.update { (it + line).takeLast(capacity) }
    }
}

/** Logs progress at most once per [step] (0..1) so the terminal shows a steady heartbeat, not a flood. */
class ProgressTicker(private val log: JobLog, private val step: Float = 0.1f) {
    private var next = step

    fun reset() {
        next = step
    }

    fun tick(fraction: Float, describe: (Float) -> String) {
        if (fraction >= next && fraction < 1f) {
            log.detail(describe(fraction))
            while (next <= fraction) next += step
        }
    }
}
