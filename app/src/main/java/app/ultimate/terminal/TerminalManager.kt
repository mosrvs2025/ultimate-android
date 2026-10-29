package app.ultimate.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import app.ultimate.UltimateApp
import app.ultimate.files.hasAllFiles
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** The terminal's shell sessions. They live with the app, so leaving the screen keeps them (and anything running) going. */
class TerminalManager(private val app: UltimateApp) {
    val bridge = TerminalBridge(app)
    private val main = Handler(Looper.getMainLooper())
    private val _sessions = MutableStateFlow<List<TerminalSession>>(emptyList())
    val sessions: StateFlow<List<TerminalSession>> = _sessions.asStateFlow()
    private val _current = MutableStateFlow<TerminalSession?>(null)
    val current: StateFlow<TerminalSession?> = _current.asStateFlow()
    /** Bumped when a title changes or a session ends, so the tabs redraw. */
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    /** The on-screen terminal, while the screen is shown. */
    var view: TerminalView? = null
    val ctrl = MutableStateFlow(false)
    val alt = MutableStateFlow(false)

    val client = object : TerminalSessionClient {
        override fun onTextChanged(s: TerminalSession) { if (view?.currentSession == s) view?.onScreenUpdated() }
        override fun onTitleChanged(s: TerminalSession) { _changes.value++ }
        override fun onSessionFinished(s: TerminalSession) { close(s) }
        override fun onCopyTextToClipboard(s: TerminalSession, text: String) {
            app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Terminal", text))
            app.clips.add(text, "Terminal")
        }
        override fun onPasteTextFromClipboard(s: TerminalSession) {
            app.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(app)?.toString()?.let { s.emulator?.paste(it) }
        }
        override fun onBell(s: TerminalSession) = Unit
        override fun onColorsChanged(s: TerminalSession) { view?.invalidate() }
        override fun onTerminalCursorStateChange(state: Boolean) { view?.setTerminalCursorBlinkerState(state, true) }
        override fun getTerminalCursorStyle(): Int = 0
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }

    /** Starts a new shell, in shared storage when Ultimate may see it, else in its own home folder. */
    fun open(): TerminalSession {
        bridge.start()
        val home = File(bridge.dir, "home").apply { mkdirs() }
        val tmp = File(app.cacheDir, "term").apply { mkdirs() }
        val cwd = if (hasAllFiles(app)) Environment.getExternalStorageDirectory().path else home.path
        // Keep Android's own variables (am, pm and cmd need them), then add the terminal's.
        val env = System.getenv().toMutableMap() + mapOf(
            "HOME" to home.path, "TMPDIR" to tmp.path, "ENV" to bridge.rcFile().path, "TERM" to "xterm-256color", "COLORTERM" to "truecolor",
            "LANG" to "en_US.UTF-8", "SHELL" to "/system/bin/sh",
            "PATH" to "/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:/system_ext/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin:/vendor/xbin",
        )
        val s = TerminalSession("/system/bin/sh", cwd, arrayOf("sh"), env.map { "${it.key}=${it.value}" }.toTypedArray(), 3000, client)
        _sessions.value = _sessions.value + s
        _current.value = s
        return s
    }

    fun select(s: TerminalSession) { _current.value = s }

    fun close(s: TerminalSession) {
        main.post {
            s.finishIfRunning()
            val left = _sessions.value - s
            _sessions.value = left
            if (_current.value == s) _current.value = left.lastOrNull()
            _changes.value++
        }
    }

    /** Types [text] into the current shell; with [run] it also presses Enter. */
    fun type(text: String, run: Boolean) {
        val s = _current.value ?: open()
        s.write(if (run) "$text\r" else text)
    }
}
