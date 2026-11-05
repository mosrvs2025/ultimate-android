package app.ultimate.terminal

import android.os.Handler
import android.os.Looper
import android.system.Os
import com.termux.terminal.TerminalSession
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/** What sits at the far end of a relay tab: it gets the keys typed, and is told when the screen size changes or the tab is closed. */
interface RelayPeer {
    fun onKeys(bytes: ByteArray, length: Int)
    fun onResize(columns: Int, rows: Int)
    fun onClosed()
}

/** What a connection needs from the screen it is shown on: print, ask a question, know the size, and say it ended. */
interface Console {
    fun show(bytes: ByteArray, length: Int = bytes.size)
    fun show(text: String) = show(text.toByteArray(Charsets.UTF_8))
    /** Asks in the console and waits for the answer; null when cancelled. Call off the main thread. */
    fun readLine(prompt: String, secret: Boolean): String?
    fun size(): Pair<Int, Int>
    fun end(message: String?)
}

/**
 * A terminal tab whose other end is a connection rather than a program on the phone. The tab runs a tiny local relay (cat on
 * two pipes, with the terminal in raw mode), so the Termux terminal view, selection, colors and extra keys all work on it, and the
 * connection's bytes pass through unchanged. While a question is being asked in the tab ("trust this server?", "password:"),
 * the keys go to [readLine] instead of the peer.
 */
class RelayHandle internal constructor(private val dir: File, private val peer: RelayPeer) : Console {
    lateinit var session: TerminalSession
        internal set
    internal val inFifo = File(dir, "in")
    internal val outFifo = File(dir, "out")
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "relay-out").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var question: LineInput? = null
    @Volatile private var answer: CompletableFuture<String?>? = null
    @Volatile private var stream: FileOutputStream? = null
    @Volatile private var alive = true
    private var size = 0 to 0

    init {
        Os.mkfifo(inFifo.path, "600".toInt(8)); Os.mkfifo(outFifo.path, "600".toInt(8))
    }

    /** Starts the threads; call once the session exists. */
    internal fun start() {
        Thread({
            // Opening for read waits until the relay's cat opens the pipe for write, then keys flow until it closes.
            runCatching {
                FileInputStream(outFifo).use { input ->
                    val buf = ByteArray(4096)
                    while (alive) { val n = input.read(buf); if (n < 0) break; route(buf, n) }
                }
            }
        }, "relay-in").apply { isDaemon = true }.start()
        poll()
    }

    /** Shows bytes from the far end on screen, in order. */
    override fun show(bytes: ByteArray, length: Int) {
        if (!alive) return
        val copy = bytes.copyOf(length)
        writer.execute {
            runCatching {
                val s = stream ?: FileOutputStream(inFifo).also { stream = it }
                s.write(copy); s.flush()
            }
        }
    }

    /** Asks a question in the tab and waits for the answer (null if cancelled with Ctrl+C or the tab closes). Call off the main thread. */
    override fun readLine(prompt: String, secret: Boolean): String? {
        val f = CompletableFuture<String?>()
        question = LineInput(secret); answer = f
        show(prompt)
        return try { f.get() } finally { question = null; answer = null }
    }

    private fun route(bytes: ByteArray, length: Int) {
        val q = question
        if (q != null) {
            val r = q.feed(bytes, length)
            if (r.echo.isNotEmpty()) show(r.echo)
            if (r.cancelled) answer?.complete(null) else if (r.line != null) answer?.complete(r.line)
        } else peer.onKeys(bytes.copyOf(length), length)
    }

    /** The terminal view tells the local relay about size changes, not us, so look for them and pass them on. */
    private fun poll() {
        if (!alive) return
        val e = runCatching { session.emulator }.getOrNull()
        if (e != null && e.mColumns > 0 && (e.mColumns to e.mRows) != size) { size = e.mColumns to e.mRows; peer.onResize(e.mColumns, e.mRows) }
        main.postDelayed({ poll() }, 400)
    }

    /** The current size, once the tab has been drawn. */
    override fun size(): Pair<Int, Int> = runCatching { session.emulator.let { it.mColumns to it.mRows } }.getOrDefault(80 to 24).let { if (it.first > 0) it else 80 to 24 }

    /** The far end is gone: say so on screen and stop passing keys. The tab stays so the message can be read. */
    override fun end(message: String?) {
        message?.let { show("\r\n\u001b[33m$it\u001b[0m\r\n") }
        answer?.complete(null)
    }

    /** The tab was closed. */
    internal fun close() {
        if (!alive) return
        alive = false
        answer?.complete(null)
        runCatching { stream?.close() }
        writer.shutdown()
        main.removeCallbacksAndMessages(null)
        peer.onClosed()
        runCatching { inFifo.delete(); outFifo.delete(); dir.delete() }
    }
}
