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
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    fun dismissError() { _error.value = null }

    /** The on-screen terminal, while the screen is shown. */
    var view: TerminalView? = null
    val ctrl = MutableStateFlow(false)
    val alt = MutableStateFlow(false)

    val client = object : TerminalSessionClient {
        override fun onTextChanged(s: TerminalSession) { if (view?.currentSession == s) view?.onScreenUpdated() }
        override fun onTitleChanged(s: TerminalSession) { _changes.value++ }
        // Keep the transcript, including exec errors and exit status, until explicitly closed.
        override fun onSessionFinished(s: TerminalSession) { main.post { relays.remove(s)?.close(); _changes.value++; view?.onScreenUpdated() } }
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
    fun open(): TerminalSession? = try {
        _error.value = null
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
        val s = TerminalSession("/system/bin/sh", cwd, arrayOf("sh", "-i"), env.map { "${it.key}=${it.value}" }.toTypedArray(), 3000, client)
        // Start here, inside the error boundary, rather than later in AndroidView.onSizeChanged.
        s.updateSize(80, 24)
        _sessions.value = _sessions.value + s
        select(s)
        s
    } catch (e: Exception) {
        _error.value = "Could not start the terminal: ${e.message ?: e.javaClass.simpleName}"
        android.util.Log.e("UltimateTerminal", "Shell startup failed", e)
        null
    } catch (e: LinkageError) {
        _error.value = "The terminal library could not load on this device: ${e.message}"
        android.util.Log.e("UltimateTerminal", "Native terminal startup failed", e)
        null
    }

    private val relays = HashMap<TerminalSession, RelayHandle>()
    private val labels = HashMap<TerminalSession, String>()

    /** The name a tab shows when it is a connection rather than a shell on the phone. */
    fun labelOf(s: TerminalSession): String? = labels[s]

    /**
     * Opens a tab whose other end is [peer], such as an SSH connection. The tab is a local relay (raw mode, two pipes), so
     * everything the terminal view does, such as colors, selection, scrollback and the extra keys, works on it.
     */
    fun openRelay(label: String, peer: RelayPeer): RelayHandle {
        bridge.start()
        val dir = File(app.filesDir, "term/relay-${System.nanoTime()}").apply { mkdirs() }
        val handle = RelayHandle(dir, peer)
        val env = System.getenv().toMutableMap() + mapOf(
            "HOME" to File(bridge.dir, "home").path, "TERM" to "xterm-256color", "COLORTERM" to "truecolor", "LANG" to "en_US.UTF-8",
            "IN" to handle.inFifo.path, "OUT" to handle.outFifo.path,
            "PATH" to "/product/bin:/apex/com.android.runtime/bin:/system_ext/bin:/system/bin:/system/xbin:/vendor/bin",
        )
        // Raw and silent: every key goes to the far end untouched, and the far end does the echoing.
        val script = "stty raw -echo; cat < \"\$IN\" & exec cat > \"\$OUT\""
        val session = TerminalSession("/system/bin/sh", dir.path, arrayOf("sh", "-c", script), env.map { "${it.key}=${it.value}" }.toTypedArray(), 3000, client)
        handle.session = session
        relays[session] = handle; labels[session] = label
        _sessions.value = _sessions.value + session
        _current.value = session
        handle.start()
        return handle
    }

    fun select(s: TerminalSession) { ctrl.value = false; alt.value = false; _current.value = s }

    fun close(s: TerminalSession) {
        main.post {
            relays.remove(s)?.close()
            labels.remove(s)
            s.finishIfRunning()
            val left = _sessions.value - s
            _sessions.value = left
            if (_current.value == s) {
                ctrl.value = false; alt.value = false
                _current.value = left.lastOrNull()
            }
            _changes.value++
        }
    }

    /** Types [text] into the current shell; with [run] it also presses Enter. */
    fun type(text: String, run: Boolean) {
        val s = _current.value?.takeIf { it.isRunning } ?: open() ?: return
        s.write(if (run) "$text\r" else text)
    }
}
