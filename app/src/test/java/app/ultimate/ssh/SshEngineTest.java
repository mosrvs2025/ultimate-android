package app.ultimate.ssh;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;

public class SshEngineTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private SshEngine server;
    private SshClient client;
    private KeyPair approved;
    private ApprovedKeys keys;
    private Path storage, host;
    private final AtomicInteger connections = new AtomicInteger();
    private final java.util.concurrent.CountDownLatch shellOpened = new java.util.concurrent.CountDownLatch(1);
    private final java.util.concurrent.CountDownLatch shellClosed = new java.util.concurrent.CountDownLatch(1);
    private static KeyPair key() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC"); generator.initialize(256); return generator.generateKeyPair();
    }
    private static String line(KeyPair key) throws Exception {
        return PublicKeyEntry.toString(key.getPublic());
    }
    @Before public void setup() throws Exception {
        org.apache.sshd.common.util.OsUtils.setAndroid(true);
        storage = temp.newFolder("shared").toPath(); host = temp.getRoot().toPath().resolve("hostkey");
        approved = key(); keys = new ApprovedKeys(); keys.replace(line(approved));
        server = new SshEngine(host, storage, keys,
            (command, cols, rows) -> {
                ShellSession actual = new ProcessShellSession("/bin/sh", storage, command);
                shellOpened.countDown();
                return new ShellSession() {
                    public InputStream stdout() { return actual.stdout(); }
                    public InputStream stderr() { return actual.stderr(); }
                    public OutputStream stdin() { return actual.stdin(); }
                    public int awaitExit() throws InterruptedException { return actual.awaitExit(); }
                    public void close() { actual.close(); shellClosed.countDown(); }
                };
            }, connections::set, "127.0.0.1", 0);
        server.start();
        String expected = SshEngine.hostFingerprint(host);
        client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier((session, address, publicKey) -> expected.equals(org.apache.sshd.common.config.keys.KeyUtils.getFingerPrint(publicKey)));
        client.start();
    }
    @After public void teardown() throws Exception { if (client != null) client.stop(); if (server != null) server.close(); org.apache.sshd.common.util.OsUtils.setAndroid(null); }
    private ClientSession connect(String user, KeyPair key) throws Exception {
        ClientSession session = client.connect(user, "127.0.0.1", server.port()).verify(Duration.ofSeconds(5)).getSession();
        session.addPublicKeyIdentity(key);
        try { session.auth().verify(Duration.ofSeconds(5)); return session; }
        catch (Exception failure) { session.close(true); throw failure; }
    }
    @Test public void rejectsUnapprovedKeysWrongUserAndPasswords() throws Exception {
        assertThrows(Exception.class, () -> connect("ultimate", key()));
        assertThrows(Exception.class, () -> connect("root", approved));
        try (ClientSession session = client.connect("ultimate", "127.0.0.1", server.port()).verify(Duration.ofSeconds(5)).getSession()) {
            session.addPasswordIdentity("anything");
            assertThrows(Exception.class, () -> session.auth().verify(Duration.ofSeconds(5)));
        }
        try (ClientSession session = connect("ultimate", approved)) { assertTrue(session.isAuthenticated()); }
    }
    @Test public void shellStreamsAndExitStatusWork() throws Exception {
        try (ClientSession session = connect("ultimate", approved);
             ClientChannel channel = session.createExecChannel("printf hello; printf problem >&2; exit 7")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
            channel.setOut(out); channel.setErr(err); channel.open().verify(Duration.ofSeconds(5));
            Set<ClientChannelEvent> result = channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 5000);
            assertFalse(result.contains(ClientChannelEvent.TIMEOUT));
            assertEquals("hello", out.toString("UTF-8")); assertEquals("problem", err.toString("UTF-8"));
            assertEquals(Integer.valueOf(7), channel.getExitStatus());
        }
    }
    @Test public void sftpReadsWritesAndCannotEscapeRoot() throws Exception {
        Path outside = temp.newFile("outside.txt").toPath(); Files.write(outside, "private".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(storage.resolve("escape"), outside);
        try (ClientSession session = connect("ultimate", approved); SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
            try (OutputStream out = sftp.write("/from-computer.txt")) { out.write("phone".getBytes(StandardCharsets.UTF_8)); }
            assertEquals("phone", new String(Files.readAllBytes(storage.resolve("from-computer.txt")), StandardCharsets.UTF_8));
            try (InputStream in = sftp.read("/from-computer.txt")) { assertEquals("phone", new String(in.readAllBytes(), StandardCharsets.UTF_8)); }
            assertThrows(IOException.class, () -> { try (InputStream in = sftp.read("/../outside.txt")) { in.readAllBytes(); } });
            assertThrows(IOException.class, () -> { try (InputStream in = sftp.read("/escape")) { in.readAllBytes(); } });
            assertThrows(IOException.class, () -> { try (OutputStream out = sftp.write("/../outside.txt")) { out.write(1); } });
            assertEquals("private", new String(Files.readAllBytes(outside), StandardCharsets.UTF_8));
        }
    }
    @Test public void disconnectClosesClientsButAllowsNewApprovedConnection() throws Exception {
        try (ClientSession session = connect("ultimate", approved)) {
            java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
            session.addSessionListener(new org.apache.sshd.common.session.SessionListener() {
                public void sessionClosed(org.apache.sshd.common.session.Session s) { closed.countDown(); }
            });
            server.disconnectAll(); assertTrue(closed.await(5, java.util.concurrent.TimeUnit.SECONDS));
        }
        try (ClientSession next = connect("ultimate", approved)) { assertTrue(next.isAuthenticated()); }
    }
    @Test public void disconnectClosesAnActiveShell() throws Exception {
        try (ClientSession session = connect("ultimate", approved); ClientChannel channel = session.createShellChannel()) {
            channel.setOut(new ByteArrayOutputStream()); channel.setErr(new ByteArrayOutputStream());
            channel.open().verify(Duration.ofSeconds(5));
            assertTrue(shellOpened.await(5, java.util.concurrent.TimeUnit.SECONDS));
            server.disconnectAll();
            assertTrue(shellClosed.await(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }
    @Test public void hostFingerprintSurvivesRestartAndForwardingIsRejected() throws Exception {
        String fingerprint = SshEngine.hostFingerprint(host);
        assertEquals(fingerprint, SshEngine.hostFingerprint(host));
        try (ClientSession session = connect("ultimate", approved)) {
            assertThrows(Exception.class, () -> session.startRemotePortForwarding(
                new org.apache.sshd.common.util.net.SshdSocketAddress("127.0.0.1", 0),
                new org.apache.sshd.common.util.net.SshdSocketAddress("127.0.0.1", 9)));
        }
        server.close(); server = new SshEngine(host, storage, keys,
            (cmd, cols, rows) -> new ProcessShellSession("/bin/sh", storage, cmd), connections::set, "127.0.0.1", 0);
        server.start(); assertEquals(fingerprint, SshEngine.hostFingerprint(host));
        try (ClientSession session = connect("ultimate", approved)) { assertTrue(session.isAuthenticated()); }
    }
    @Test public void ed25519KeyFromAndroidFallbackCanAuthenticate() throws Exception {
        net.i2p.crypto.eddsa.KeyPairGenerator generator = new net.i2p.crypto.eddsa.KeyPairGenerator();
        KeyPair ed25519 = generator.generateKeyPair();
        keys.replace(line(ed25519));
        try (ClientSession session = connect("ultimate", ed25519)) { assertTrue(session.isAuthenticated()); }
    }
    @Test public void damagedHostKeyFailsClosed() throws Exception {
        Files.write(host, new byte[] { 1, 2, 3 });
        assertThrows(Exception.class, () -> SshEngine.hostFingerprint(host));
        assertArrayEquals(new byte[] { 1, 2, 3 }, Files.readAllBytes(host));
    }
    @Test public void invalidApprovalDoesNotReplaceExistingKeys() throws Exception {
        assertThrows(Exception.class, () -> keys.replace("ssh-ed25519 malformed"));
        assertTrue(keys.accepts("ultimate", approved.getPublic()));
        assertThrows(Exception.class, () -> keys.replace("command=whoami " + line(approved)));
        keys.replace("");
        assertThrows(IllegalStateException.class, () -> new SshEngine(host, storage, keys,
            (cmd, cols, rows) -> null, count -> {}, "127.0.0.1", 0));
    }
}
