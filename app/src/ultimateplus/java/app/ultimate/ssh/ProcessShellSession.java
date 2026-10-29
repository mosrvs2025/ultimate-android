package app.ultimate.ssh;

import java.io.*;
import java.nio.file.Path;

/** App-UID shell, not root. Pipes deliberately make no claim to provide a PTY. */
public final class ProcessShellSession implements ShellSession {
    private final Process process;
    public ProcessShellSession(String executable, Path directory, String command) throws IOException {
        ProcessBuilder builder = command == null
            ? new ProcessBuilder(executable) : new ProcessBuilder(executable, "-c", command);
        builder.directory(directory.toFile());
        builder.environment().put("HOME", directory.toString());
        builder.environment().put("TERM", "dumb");
        process = builder.start();
    }
    public InputStream stdout() { return process.getInputStream(); }
    public InputStream stderr() { return process.getErrorStream(); }
    public OutputStream stdin() { return process.getOutputStream(); }
    public int awaitExit() throws InterruptedException { return process.waitFor(); }
    public void close() {
        try { stdin().close(); } catch (IOException ignored) { }
        process.destroy();
        if (process.isAlive()) process.destroyForcibly();
        try { stdout().close(); } catch (IOException ignored) { }
        try { stderr().close(); } catch (IOException ignored) { }
    }
}
