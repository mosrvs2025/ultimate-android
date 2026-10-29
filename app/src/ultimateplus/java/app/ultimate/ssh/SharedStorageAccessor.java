package app.ultimate.ssh;

import java.io.IOException;
import java.nio.file.*;
import org.apache.sshd.sftp.server.SftpFileSystemAccessor;
import org.apache.sshd.sftp.server.SftpSubsystemProxy;

/** Shared storage has no useful symlink workflow. Reject links instead of following them outside its view. */
final class SharedStorageAccessor implements SftpFileSystemAccessor {
    private final Path storage;
    SharedStorageAccessor(Path storage) { this.storage = storage.toAbsolutePath().normalize(); }
    @Override public Path resolveLocalFilePath(SftpSubsystemProxy subsystem, Path root, String remote) throws IOException {
        String relative = remote;
        while (relative.startsWith("/")) relative = relative.substring(1);
        Path actual = storage.resolve(relative).normalize();
        if (!actual.startsWith(storage)) throw new AccessDeniedException(remote);
        Path cursor = storage;
        for (Path part : storage.relativize(actual)) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new AccessDeniedException("SFTP does not follow symbolic links");
        }
        return SftpFileSystemAccessor.super.resolveLocalFilePath(subsystem, root, remote);
    }
    @Override public void createLink(SftpSubsystemProxy subsystem, Path link, Path existing, boolean symbolic) throws IOException {
        throw new AccessDeniedException("SFTP link creation is disabled");
    }
}
