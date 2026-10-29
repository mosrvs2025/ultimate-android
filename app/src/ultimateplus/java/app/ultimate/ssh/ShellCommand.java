package app.ultimate.ssh;

import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;

final class ShellCommand implements Command {
    private final ShellSession.Factory factory;
    private final String command;
    private final ExecutorService workers = Executors.newFixedThreadPool(4);
    private final AtomicBoolean finished = new AtomicBoolean();
    private InputStream input;
    private OutputStream output, error;
    private ExitCallback callback;
    private ShellSession shell;
    ShellCommand(ShellSession.Factory factory, String command) { this.factory = factory; this.command = command; }
    public void setInputStream(InputStream value) { input = value; }
    public void setOutputStream(OutputStream value) { output = value; }
    public void setErrorStream(OutputStream value) { error = value; }
    public void setExitCallback(ExitCallback value) { callback = value; }
    public synchronized void start(ChannelSession channel, Environment env) throws IOException {
        if (finished.get()) throw new IOException("Channel closed");
        shell = factory.open(command, size(env, "COLUMNS", 80), size(env, "LINES", 24));
        ShellSession active = shell;
        workers.submit(() -> pump(input, active.stdin(), true));
        Future<?> out = workers.submit(() -> pump(active.stdout(), output, false));
        Future<?> err = workers.submit(() -> pump(active.stderr(), error, false));
        workers.submit(() -> {
            int code = 255;
            try { code = active.awaitExit(); out.get(); err.get(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (ExecutionException ignored) { }
            finally { finish(code); }
        });
    }
    private static int size(Environment env, String key, int fallback) {
        try { return Math.max(1, Math.min(1000, Integer.parseInt(env.getEnv().get(key)))); }
        catch (RuntimeException e) { return fallback; }
    }
    private void pump(InputStream from, OutputStream to, boolean close) {
        try {
            byte[] buffer = new byte[8192]; int count;
            while ((count = from.read(buffer)) != -1) { to.write(buffer, 0, count); to.flush(); }
        } catch (IOException ignored) { }
        finally { if (close) try { to.close(); } catch (IOException ignored) { } }
    }
    private synchronized void finish(int code) {
        if (!finished.compareAndSet(false, true)) return;
        if (shell != null) shell.close();
        try { if (input != null) input.close(); } catch (IOException ignored) { }
        if (callback != null) callback.onExit(code);
        workers.shutdownNow();
    }
    public void destroy(ChannelSession channel) { finish(255); }
}
