package app.ultimate.sshclient

import app.ultimate.UltimateApp
import app.ultimate.terminal.Console
import app.ultimate.terminal.RelayPeer
import java.security.KeyPair

/** The one-line job of putting a public key on a server, without ever adding it twice. */
object InstallKey {
    const val DONE = "KEY_INSTALLED"

    /** Null when the line could break out of the quotes (it is not a real key line then). */
    fun command(publicLine: String): String? {
        val line = publicLine.trim()
        if (line.isEmpty() || line.any { it == '\'' || it == '\n' || it == '\r' || it == '\\' || it == '`' || it == '$' }) return null
        return "mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch ~/.ssh/authorized_keys && " +
            "(grep -qxF '$line' ~/.ssh/authorized_keys || echo '$line' >> ~/.ssh/authorized_keys) && chmod 600 ~/.ssh/authorized_keys && echo $DONE"
    }
}

/** Opens hosts in terminal tabs. */
class Connector(private val app: UltimateApp, private val vault: VaultStore) {
    private val flow = ConnectFlow(vault)

    /** Opens the tab, shows the Terminal, and connects in the background. */
    fun connect(host: Host) {
        val current = arrayOfNulls<RemoteShell>(1)
        val peer = object : RelayPeer {
            override fun onKeys(bytes: ByteArray, length: Int) { current[0]?.write(bytes, 0, length) }
            override fun onResize(columns: Int, rows: Int) { current[0]?.resize(columns, rows) }
            override fun onClosed() { current[0]?.close() }
        }
        val handle = app.terminal.openRelay(host.name, peer)
        app.tools.firstOrNull { it.id == "terminal" }?.let { app.shell.open(it) }
        app.insights.count("ssh.connect")
        Thread({ flow.run(host, handle, current) }, "ssh-connect").apply { isDaemon = true }.start()
    }

    fun installKey(host: Host, identity: Identity, password: String, trustNew: (fingerprint: String) -> Boolean) = flow.installKey(host, identity, password, trustNew)
}

/**
 * Everything a connection does that is not the screen: choosing credentials, asking "trust this server?" and "password:" in the
 * console, the way OpenSSH asks, and trying again. Tested against a real server with a stand-in console.
 */
class ConnectFlow(private val vault: VaultStore) {

    fun run(host: Host, h: Console, current: Array<RemoteShell?>) {
        h.show("\u001b[1mConnecting to ${HostRules.label(host)}…\u001b[0m\r\n")
        val keys: List<KeyPair> = when (host.auth) {
            Auth.Key -> listOfNotNull(vault.keyPair(host.identityId)).ifEmpty { return h.end("The key for this host is missing. Edit the host and choose another.") }
            Auth.Password -> emptyList()
            Auth.Ask -> vault.identities.value.mapNotNull { vault.keyPair(it.id) }
        }
        var password: String? = if (host.auth == Auth.Password) vault.password(host.id) else null
        var ask = host.auth == Auth.Password && password == null
        var attempts = 0
        while (true) {
            val asking = ask
            val prompt = "${host.user}@${host.address}'s password: "
            val shell = RemoteShell(host.address, host.port, host.user, password, keys, { fp, type -> trust(host, h, fp, type) },
                { b, n -> h.show(b, n) }, { reason -> h.end(if (reason == null) "[Connection closed]" else "[$reason]") },
                askPassword = if (asking) ({ h.readLine(prompt, true) }) else null)
            current[0] = shell
            try {
                val (cols, rows) = h.size()
                shell.connect(cols, rows)
                vault.touch(host.id)
                if (host.startup.isNotBlank()) shell.write((host.startup + "\n").toByteArray())
                return
            } catch (e: AuthRejected) {
                if (++attempts >= 3) return h.end(e.message)
                h.show("\u001b[33m${e.message}\u001b[0m\r\n")
                // The next try asks for the password in the tab, after the server is trusted, as OpenSSH does.
                password = null; ask = true
            } catch (e: RemoteException) {
                return h.end(e.message)
            }
        }
    }

    /** Asked while the connection is being made, on its own thread, so it may wait for the person to type. */
    private fun trust(host: Host, h: Console, fingerprint: String, type: String): Boolean {
        val verdict = KnownHosts.check(vault.knownFingerprints(), host.address, host.port, fingerprint)
        if (verdict == KnownHosts.Verdict.Match) return true
        val prompt = if (verdict == KnownHosts.Verdict.New)
            "The server ${host.address} hasn't been seen before.\r\n$type key fingerprint:\r\n  $fingerprint\r\nTrust it and continue? (yes/no) "
        else "\u001b[1;31mWARNING: this server's key has changed!\u001b[0m\r\nSomeone may be intercepting the connection, or the server was reinstalled.\r\nNew $type key:\r\n  $fingerprint\r\nTrust the new key? (type yes to continue) "
        val yes = h.readLine(prompt, false)?.trim()?.lowercase() in setOf("yes", "y")
        if (yes) vault.trust(host.address, host.port, fingerprint)
        return yes
    }

    /**
     * Signs in with a password and adds [identity]'s public key to the server, so the next connection needs no password.
     * Blocks; call off the main thread. Returns null on success or a sentence about what went wrong.
     */
    fun installKey(host: Host, identity: Identity, password: String, trustNew: (fingerprint: String) -> Boolean): String? {
        val command = InstallKey.command(identity.publicKey) ?: return "That key can't be installed automatically."
        val shell = RemoteShell(host.address, host.port, host.user, password, emptyList(), { fp, _ ->
            when (KnownHosts.check(vault.knownFingerprints(), host.address, host.port, fp)) {
                KnownHosts.Verdict.Match -> true
                else -> trustNew(fp).also { if (it) vault.trust(host.address, host.port, fp) }
            }
        }, { _, _ -> }, { })
        return try {
            shell.connect(80, 24)
            val out = shell.run(command)
            if (InstallKey.DONE in out) null else "The server didn't confirm. Output: ${out.trim().take(120)}"
        } catch (e: RemoteException) { e.message } finally { shell.close() }
    }
}
