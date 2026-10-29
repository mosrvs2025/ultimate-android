package app.ultimate.ssh;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.server.forward.RejectAllForwardingFilter;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionListener;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

/** Pure Java server; the Android manager owns foreground-service and permission lifecycle. */
public final class SshEngine implements AutoCloseable {
    private final SshServer server;
    private final Set<Session> clients = ConcurrentHashMap.newKeySet();
    public SshEngine(Path hostKey, Path storage, ApprovedKeys approved, ShellSession.Factory shells,
                     IntConsumer connected, String bindHost, int port) throws Exception {
        if (approved.isEmpty()) throw new IllegalStateException("Approve a public key first");
        server = SshServer.setUpDefaultServer();
        server.setHost(bindHost);
        server.setPort(port);
        java.security.KeyPair pair = StoredHostKey.load(hostKey);
        server.setKeyPairProvider(session -> Collections.singletonList(pair));
        server.setUserAuthFactories(Collections.singletonList(UserAuthPublicKeyFactory.INSTANCE));
        server.setPublickeyAuthenticator((username, key, session) -> approved.accepts(username, key));
        server.setPasswordAuthenticator((u, p, s) -> false);
        server.setForwardingFilter(RejectAllForwardingFilter.INSTANCE);
        server.setFileSystemFactory(new VirtualFileSystemFactory(storage));
        server.setSubsystemFactories(Collections.singletonList(new SftpSubsystemFactory.Builder().withFileSystemAccessor(new SharedStorageAccessor(storage)).build()));
        server.setShellFactory(channel -> new ShellCommand(shells, null));
        server.setCommandFactory((channel, command) -> new ShellCommand(shells, command));
        server.addSessionListener(new SessionListener() {
            public void sessionEvent(Session session, Event event) {
                if (event == Event.Authenticated) { clients.add(session); connected.accept(clients.size()); }
            }
            public void sessionClosed(Session session) { clients.remove(session); connected.accept(clients.size()); }
        });
        // Bound unauthenticated work and inactive sessions; do not enable keyboard-interactive/none auth.
        server.getProperties().put("auth-timeout", 30000L);
        server.getProperties().put("idle-timeout", 900000L);
        server.getProperties().put("max-auth-requests", 3);
    }
    public static String hostFingerprint(Path path) throws Exception {
        return KeyUtils.getFingerPrint(StoredHostKey.load(path).getPublic());
    }
    public void start() throws IOException { server.start(); }
    public int port() { return server.getPort(); }
    public void disconnectAll() { for (Session client : clients) client.close(true); }
    public void close() throws IOException { server.stop(true); }
}
