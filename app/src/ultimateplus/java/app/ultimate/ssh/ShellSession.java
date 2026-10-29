package app.ultimate.ssh;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;

/** Bridge for the terminal's future PTY. One instance belongs to one SSH channel. */
public interface ShellSession extends AutoCloseable {
    InputStream stdout();
    InputStream stderr();
    OutputStream stdin();
    int awaitExit() throws InterruptedException;
    void close();
    interface Factory {
        /** command is null for an interactive shell; never prepend user text to a shell invocation. */
        ShellSession open(String command, int columns, int rows) throws IOException;
    }
}
