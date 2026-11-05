package app.ultimate.sshclient

import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellCommandFactory
import org.apache.sshd.server.shell.ProcessShellFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.KeyPair
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Connects the real client to a real SSH server on this computer, so the connection, sign-in and shell are tested end to end. */
class RemoteShellTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var server: SshServer
    private val allowed: KeyPair = Keys.generate()

    @Before fun start() {
        org.apache.sshd.common.util.OsUtils.setAndroid(true)
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"; port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(temp.newFile("host").toPath().also { it.toFile().delete() })
            setPasswordAuthenticator { user, pass, _ -> user == "dev" && pass == "secret" }
            setPublickeyAuthenticator { user, key, _ -> user == "dev" && key == allowed.public }
            shellFactory = ProcessShellFactory("/bin/sh", "/bin/sh", "-s")
            commandFactory = ProcessShellCommandFactory.INSTANCE
            start()
        }
    }

    @After fun stop() { server.stop(true); org.apache.sshd.common.util.OsUtils.setAndroid(null) }

    private class Run(val shell: RemoteShell, val out: StringBuffer, val closed: CountDownLatch, val reason: AtomicReference<String?>, val fingerprints: MutableList<String>)

    private fun shell(password: String? = null, key: KeyPair? = null, user: String = "dev", port: Int = server.port, trust: Boolean = true): Run {
        val out = StringBuffer(); val closed = CountDownLatch(1); val reason = AtomicReference<String?>("unset"); val fps = ArrayList<String>()
        val s = RemoteShell("127.0.0.1", port, user, password, listOfNotNull(key), { fp, _ -> fps += fp; trust }, { b, n -> out.append(String(b, 0, n)) }, { reason.set(it); closed.countDown() })
        return Run(s, out, closed, reason, fps)
    }

    private fun waitFor(out: StringBuffer, text: String) {
        val end = System.currentTimeMillis() + 8_000
        while (!out.contains(text)) { check(System.currentTimeMillis() < end) { "never saw \"$text\" in: $out" }; Thread.sleep(20) }
    }

    @Test fun passwordSignInGivesAWorkingShell() {
        val r = shell(password = "secret")
        r.shell.connect(80, 24)
        val line = "echo hello-\$((1+2))\n".toByteArray()
        r.shell.write(line)
        waitFor(r.out, "hello-3")
        assertEquals(1, r.fingerprints.size)
        assertTrue(r.fingerprints[0].startsWith("SHA256:"))
        r.shell.close()
        assertTrue(r.closed.await(5, TimeUnit.SECONDS))
        assertNull(r.reason.get()) // we closed it ourselves, so it is not reported as a problem
    }

    @Test fun keySignInWorks() {
        val r = shell(key = allowed)
        r.shell.connect(80, 24)
        r.shell.write("echo via-key\n".toByteArray())
        waitFor(r.out, "via-key")
        r.shell.close()
    }

    @Test fun oneOffCommandsReturnTheirOutput() {
        val r = shell(password = "secret")
        r.shell.connect(80, 24)
        assertEquals("pong\n", r.shell.run("echo pong"))
        r.shell.close()
    }

    @Test fun aWrongPasswordIsSaidPlainly() {
        val e = runCatching { shell(password = "nope").shell.connect(80, 24) }.exceptionOrNull() as RemoteException
        assertEquals("The password or key was not accepted.", e.message)
        assertTrue(e is AuthRejected)
    }

    @Test fun anUnapprovedKeyPointsAtAuthorizedKeys() {
        val e = runCatching { shell(key = Keys.generate()).shell.connect(80, 24) }.exceptionOrNull() as RemoteException
        assertTrue(e.message, e.message!!.contains("authorized_keys"))
    }

    @Test fun notTrustingTheServerStopsBeforeSigningIn() {
        val r = shell(password = "secret", trust = false)
        val e = runCatching { r.shell.connect(80, 24) }.exceptionOrNull() as RemoteException
        assertTrue(e.message, e.message!!.contains("trust"))
        assertTrue(e !is AuthRejected)
        assertEquals(1, r.fingerprints.size)
    }

    @Test fun aClosedPortIsExplained() {
        val free = java.net.ServerSocket(0).use { it.localPort }
        val e = runCatching { shell(password = "x", port = free).shell.connect(80, 24) }.exceptionOrNull() as RemoteException
        assertTrue(e.message, e.message!!.contains("refused"))
    }

    @Test fun theRemoteEndingIsReported() {
        val r = shell(password = "secret")
        r.shell.connect(80, 24)
        r.shell.write("exit\n".toByteArray())
        assertTrue(r.closed.await(8, TimeUnit.SECONDS))
        assertTrue(r.reason.get(), r.reason.get() != null)
    }
}
