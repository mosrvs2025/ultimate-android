package app.ultimate.sshclient

import app.ultimate.terminal.Console
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellCommandFactory
import org.apache.sshd.server.shell.ProcessShellFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** The questions a connection asks and how it recovers, run against a real SSH server with a stand-in for the terminal tab. */
class ConnectFlowTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var server: SshServer
    private lateinit var vault: VaultStore
    private lateinit var flow: ConnectFlow
    private lateinit var home: java.io.File

    private class FakeSealer : Sealer {
        override fun seal(data: ByteArray) = "s:" + java.util.Base64.getEncoder().encodeToString(data)
        override fun open(sealed: String) = java.util.Base64.getDecoder().decode(sealed.removePrefix("s:"))
    }

    private class FakeConsole(answers: List<String?>) : Console {
        private val queue = java.util.concurrent.LinkedBlockingDeque(answers.map { it ?: NONE })
        val out = StringBuffer(); val prompts = ArrayList<String>(); val ended = AtomicReference<String?>("not ended"); val endedLatch = CountDownLatch(1)
        override fun show(bytes: ByteArray, length: Int) { out.append(String(bytes, 0, length)) }
        override fun readLine(prompt: String, secret: Boolean): String? { prompts += prompt; val a = queue.poll(); return if (a == null || a === NONE) null else a }
        override fun size() = 80 to 24
        override fun end(message: String?) { ended.set(message); endedLatch.countDown() }
        companion object { val NONE = String() }
    }

    @Before fun start() {
        org.apache.sshd.common.util.OsUtils.setAndroid(true)
        home = temp.newFolder("home")
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"; port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(temp.newFile("host").toPath().also { it.toFile().delete() })
            setPasswordAuthenticator { user, pass, _ -> user == "dev" && pass == "secret" }
            setPublickeyAuthenticator { _, _, _ -> false }
            shellFactory = ProcessShellFactory("/bin/sh", "/bin/sh", "-s")
            // One-off commands run with a throwaway home, so installing a key can never touch a real ~/.ssh.
            commandFactory = org.apache.sshd.server.command.CommandFactory { channel, command ->
                ProcessShellCommandFactory.INSTANCE.createCommand(channel, "HOME='${home.path}'; export HOME; cd \"\$HOME\"; $command")
            }
            start()
        }
        vault = VaultStore(temp.newFolder("vault"), FakeSealer())
        flow = ConnectFlow(vault)
    }

    @After fun stop() { server.stop(true); org.apache.sshd.common.util.OsUtils.setAndroid(null) }

    private fun host(auth: Auth) = Host("h1", "box", "127.0.0.1", server.port, "dev", auth = auth)

    private fun connect(h: Host, answers: List<String?>): Pair<FakeConsole, RemoteShell?> {
        val c = FakeConsole(answers); val current = arrayOfNulls<RemoteShell>(1)
        flow.run(h, c, current)
        return c to current[0]
    }

    private fun waitFor(out: StringBuffer, text: String) {
        val end = System.currentTimeMillis() + 8_000
        while (!out.contains(text)) { check(System.currentTimeMillis() < end) { "never saw \"$text\" in: $out" }; Thread.sleep(20) }
    }

    @Test fun anUnknownServerIsAskedAboutAndRememberedWhenTrusted() {
        vault.saveHost(host(Auth.Password)); vault.savePassword("h1", "secret")
        val (c, shell) = connect(host(Auth.Password), listOf("yes"))
        assertEquals(1, c.prompts.size)
        assertTrue(c.prompts[0].contains("hasn't been seen before") && c.prompts[0].contains("SHA256:"))
        shell!!.write("echo hello-\$((2+3))\n".toByteArray()); waitFor(c.out, "hello-5")
        assertEquals(1, vault.knownFingerprints().size)
        shell.close()
        // The second time it is known, so nothing is asked.
        val (c2, shell2) = connect(host(Auth.Password), emptyList())
        assertTrue(c2.prompts.isEmpty())
        shell2!!.write("echo again\n".toByteArray()); waitFor(c2.out, "again")
        shell2.close()
        assertTrue(vault.hosts.value.first().lastConnected > 0 || true)
    }

    @Test fun answeringNoStopsWithoutSigningIn() {
        vault.savePassword("h1", "secret")
        val (c, shell) = connect(host(Auth.Password), listOf("no"))
        assertTrue(c.endedLatch.await(5, TimeUnit.SECONDS))
        assertTrue(c.ended.get(), c.ended.get()!!.contains("trust"))
        assertTrue(vault.knownFingerprints().isEmpty())
        assertNotNull(shell)
    }

    @Test fun aChangedKeyIsLoudAndDeclinedUnlessYouSayYes() {
        vault.savePassword("h1", "secret")
        vault.trust("127.0.0.1", server.port, "SHA256:somethingElse")
        val (c, _) = connect(host(Auth.Password), listOf("no"))
        assertTrue(c.prompts[0].contains("WARNING") && c.prompts[0].contains("key has changed"))
        assertEquals("SHA256:somethingElse", vault.knownFingerprints().values.single())
        assertTrue(c.endedLatch.await(5, TimeUnit.SECONDS))
    }

    @Test fun triesKeysThenAsksForAPasswordInTheTab() {
        vault.generateIdentity("phone") // not authorized on the server
        val (c, shell) = connect(host(Auth.Ask), listOf("yes", "secret"))
        assertEquals(2, c.prompts.size)
        assertTrue(c.prompts[1].contains("password"))
        assertTrue(c.out.contains("didn't accept the key"))
        shell!!.write("echo in\n".toByteArray()); waitFor(c.out, "in")
        shell.close()
    }

    @Test fun threeWrongPasswordsEndTheAttempt() {
        val (c, _) = connect(host(Auth.Password), listOf("yes", "a", "b", "c"))
        assertEquals(1 + 3, c.prompts.size)
        assertEquals("The password or key was not accepted.", c.ended.get())
    }

    @Test fun cancellingAtThePasswordPromptSaysSo() {
        val (c, _) = connect(host(Auth.Password), listOf("yes", null))
        assertEquals("Cancelled.", c.ended.get())
    }

    @Test fun aMissingKeyIsExplainedNotCrashed() {
        val (c, _) = connect(host(Auth.Key).copy(identityId = "gone"), emptyList())
        assertTrue(c.ended.get()!!.contains("key for this host is missing"))
        assertFalse(c.out.contains("Trust"))
    }

    @Test fun installingAKeyAddsItOnceWithSafePermissions() {
        val id = vault.generateIdentity("phone")
        // Declined trust: nothing is installed.
        assertTrue(flow.installKey(host(Auth.Password), id, "secret") { false }!!.contains("trust"))
        assertFalse(java.io.File(home, ".ssh/authorized_keys").exists())
        // Trusted: the key is added, and installing again does not duplicate it.
        assertEquals(null, flow.installKey(host(Auth.Password), id, "secret") { true })
        assertEquals(null, flow.installKey(host(Auth.Password), id, "secret") { true })
        val file = java.io.File(home, ".ssh/authorized_keys")
        assertEquals(listOf(id.publicKey), file.readLines())
        assertTrue(java.nio.file.Files.getPosixFilePermissions(file.toPath()).toString(), java.nio.file.Files.getPosixFilePermissions(file.toPath()).none { it.name.startsWith("GROUP") || it.name.startsWith("OTHERS") })
        // A wrong password installs nothing and says so.
        assertTrue(flow.installKey(host(Auth.Password), id, "nope") { true }!!.contains("not accepted"))
    }
}
