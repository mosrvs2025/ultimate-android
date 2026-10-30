package app.ultimate.sshclient

import org.apache.sshd.client.SshClient
import org.apache.sshd.client.channel.ChannelShell
import org.apache.sshd.client.channel.ClientChannelEvent
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.config.keys.KeyUtils
import java.io.OutputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.KeyPair
import java.time.Duration
import java.util.EnumSet

/** A connection that failed, with a sentence a person can act on. */
open class RemoteException(message: String) : Exception(message)

/** The server was reached but did not accept the keys or password offered, so asking for a password may help. */
class AuthRejected(message: String) : RemoteException(message)

/**
 * One SSH connection with a shell on it, for the terminal. [trusted] is asked about a server's key the first time and when it
 * changes, and a false answer ends the attempt; it may block while the person decides. Output of the remote shell, in order,
 * goes to [output] on a background thread; [closed] runs once when the connection ends, with a reason when it was not asked for.
 */
class RemoteShell(
    private val address: String,
    private val port: Int,
    private val user: String,
    private val password: String?,
    private val keyPairs: List<KeyPair>,
    private val trusted: (fingerprint: String, keyType: String) -> Boolean,
    private val output: (ByteArray, Int) -> Unit,
    private val closed: (reason: String?) -> Unit,
    /** Asked for the password only after the server's key has been accepted, so a password is never typed to an untrusted server. Null answer cancels. */
    private val askPassword: (() -> String?)? = null,
) {
    private var client: SshClient? = null
    private var session: ClientSession? = null
    private var shell: ChannelShell? = null
    @Volatile private var ending = false
    @Volatile private var declined = false
    private val reported = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Connects, signs in and opens the shell; blocks, so call it off the main thread. */
    fun connect(cols: Int, rows: Int) {
        org.apache.sshd.common.util.OsUtils.setAndroid(true)
        val c = SshClient.setUpDefaultClient()
        c.serverKeyVerifier = org.apache.sshd.client.keyverifier.ServerKeyVerifier { _, _, key -> trusted(KeyUtils.getFingerPrint(key), KeyUtils.getKeyType(key)).also { ok -> if (!ok) declined = true } }
        c.start()
        client = c
        try {
            val s = c.connect(user, address, port).verify(Duration.ofSeconds(12)).session
            session = s
            // "Connected" only means the network link is up. Wait for the key exchange, where the server's key is checked, so a password
            // is never asked for (or sent) before the server has been trusted. The wait is long because a person may be deciding.
            val events = s.waitFor(EnumSet.of(ClientSession.ClientSessionEvent.WAIT_AUTH, ClientSession.ClientSessionEvent.CLOSED, ClientSession.ClientSessionEvent.TIMEOUT), 300_000L)
            if (declined) throw RemoteException("You didn't trust the server's key, so I stopped.")
            if (ClientSession.ClientSessionEvent.WAIT_AUTH !in events) throw RemoteException("$address closed the connection before sign-in.")
            keyPairs.forEach { s.addPublicKeyIdentity(it) }
            val pw = password ?: askPassword?.let { ask -> ask() ?: throw RemoteException("Cancelled.") }
            pw?.let { s.addPasswordIdentity(it) }
            s.auth().verify(Duration.ofSeconds(25))
            // Keep idle connections alive through routers that forget quiet ones.
            runCatching { s.setSessionHeartbeat(org.apache.sshd.common.session.SessionHeartbeatController.HeartbeatType.IGNORE, Duration.ofSeconds(20)) }
            val sink = object : OutputStream() {
                override fun write(b: Int) = output(byteArrayOf(b.toByte()), 1)
                override fun write(b: ByteArray, off: Int, len: Int) = output(b.copyOfRange(off, off + len), len)
            }
            val ch = s.createShellChannel()
            ch.ptyType = "xterm-256color"; ch.ptyColumns = cols; ch.ptyLines = rows
            ch.setOut(sink); ch.setErr(sink)
            ch.open().verify(Duration.ofSeconds(10))
            ch.addCloseFutureListener { finish(null) }
            s.addCloseFutureListener { finish(null) }
            shell = ch
        } catch (e: RemoteException) {
            stop(); throw e
        } catch (e: Throwable) {
            val (message, auth) = explain(e)
            stop()
            throw if (auth) AuthRejected(message) else RemoteException(message)
        }
    }

    fun write(bytes: ByteArray, off: Int = 0, len: Int = bytes.size) {
        runCatching { shell?.invertedIn?.apply { write(bytes, off, len); flush() } }.onFailure { finish("The connection dropped.") }
    }

    fun resize(cols: Int, rows: Int) { runCatching { shell?.sendWindowChange(cols, rows) } }

    /** Runs one command on its own channel and returns what it printed; for small jobs such as installing a key. */
    fun run(command: String, seconds: Long = 20): String {
        val s = session ?: throw RemoteException("Not connected.")
        val out = java.io.ByteArrayOutputStream()
        s.createExecChannel(command).use { ch ->
            ch.setOut(out); ch.setErr(out)
            ch.open().verify(Duration.ofSeconds(10))
            ch.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), seconds * 1000)
        }
        return out.toString()
    }

    fun close() { ending = true; stop(); finish(null) }

    private fun finish(reason: String?) {
        if (!reported.compareAndSet(false, true)) return
        stop()
        closed(if (ending) null else reason ?: "The server closed the connection.")
    }

    private fun stop() {
        runCatching { shell?.close(false) }; runCatching { session?.close(false) }; runCatching { client?.stop() }
        shell = null; session = null; client = null
    }

    /** A sentence for the person, and whether the cause was the server refusing the credentials. */
    private fun explain(e: Throwable): Pair<String, Boolean> {
        val chain = generateSequence(e) { it.cause }.toList()
        val text = chain.mapNotNull { it.message }.joinToString(" | ")
        val lower = text.lowercase()
        return when {
            chain.any { it is UnknownHostException } -> "I can't find \"$address\". Check the address, or that you are on the right network." to false
            chain.any { it is ConnectException } -> "$address:$port refused the connection. Is SSH running there, and is the port right?" to false
            chain.any { it is NoRouteToHostException } -> "There is no route to $address. Is it on the same network, or is a VPN needed?" to false
            "validate" in lower && "key" in lower || "serverkeyverifier" in lower || "rejected" in lower && "key" in lower -> "You didn't trust the server's key, so I stopped." to false
            chain.any { it is SocketTimeoutException || it is java.util.concurrent.TimeoutException } || "timeout" in lower || "timed out" in lower -> "$address didn't answer in time." to false
            "no more authentication methods" in lower || "authentication failed" in lower || "auth" in lower ->
                (if (keyPairs.isNotEmpty() && password == null && askPassword == null) "$address didn't accept the key. Add the public key to the server's authorized_keys, or use a password." else "The password or key was not accepted.") to true
            else -> (chain.lastOrNull()?.message?.take(160) ?: "The connection failed.") to false
        }
    }
}
