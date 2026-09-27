package eu.exeris.e2e.boot;

import eu.exeris.kernel.community.testkit.FixtureBootLock;
import eu.exeris.kernel.community.testkit.SystemPropertySnapshot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The emitted {@code Application.run()}, running on its own thread against a real kernel.
 *
 * <p>{@code run()} does not return while the application serves: the emitted
 * {@code RuntimeLifecycle.run()} parks on a shutdown latch inside the boot callback. So it runs on a
 * dedicated platform thread, and {@link #close()} interrupts it — the emitted latch wait restores the
 * interrupt and returns, the boot callback returns, and the kernel shuts its subsystems down.
 *
 * <p>Configuration travels the way the kernel testkit's own fixtures send it: JVM-global system
 * properties, set under {@link FixtureBootLock} and restored once the boot has read them. Readiness
 * is observed on the wire, not inferred: {@code GET /probe} answers {@code 503} from the edge router
 * until the lifecycle has published the composed router, and {@code 200} after.
 */
final class BootedApplication implements AutoCloseable {

    private static final Duration BOOT_TIMEOUT = Duration.ofSeconds(60);
    /** Past the kernel's own 60-second drain deadline (PaqsScheduler), so a stream that is slow to
     *  unwind makes the test slow rather than wrong. */
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(75);

    private final Thread thread;
    private final int port;
    private final AtomicReference<Throwable> failure;

    private BootedApplication(Thread thread, int port, AtomicReference<Throwable> failure) {
        this.thread = thread;
        this.port = port;
        this.failure = failure;
    }

    /**
     * Boots {@code applicationClass} (a subclass of the emitted {@code Application}) and returns
     * once its composed router answers {@code GET /probe}.
     */
    static BootedApplication start(ClassLoader loader, String applicationClass) {
        int port = reserveLoopbackPort();
        String jdbcUrl = "jdbc:h2:mem:generated_app_" + Long.toUnsignedString(System.nanoTime(), 36)
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Thread> started = new AtomicReference<>();

        FixtureBootLock.bootExclusively(() -> {
            SystemPropertySnapshot snapshot = SystemPropertySnapshot.capture(
                    "exeris.http.mode", "exeris.http.bindHost", "exeris.http.port",
                    "http.mode", "http.bindHost", "http.port",
                    "exeris.persistence.jdbcUrl", "exeris.persistence.runMigrations");
            try {
                System.setProperty("exeris.http.mode", "SERVER");
                System.setProperty("exeris.http.bindHost", RawHttp.LOOPBACK);
                System.setProperty("exeris.http.port", Integer.toString(port));
                System.setProperty("http.mode", "SERVER");
                System.setProperty("http.bindHost", RawHttp.LOOPBACK);
                System.setProperty("http.port", Integer.toString(port));
                System.setProperty("exeris.persistence.jdbcUrl", jdbcUrl);
                System.setProperty("exeris.persistence.runMigrations", "true");

                Thread thread = Thread.ofPlatform()
                        .name("emitted-application")
                        .start(() -> runApplication(loader, applicationClass, failure));
                started.set(thread);
                awaitComposed(thread, port, failure);
            } finally {
                snapshot.restore();
            }
        });
        return new BootedApplication(started.get(), port, failure);
    }

    int port() {
        return port;
    }

    @Override
    public void close() throws InterruptedException {
        thread.interrupt();
        thread.join(STOP_TIMEOUT);
        if (thread.isAlive()) {
            StringBuilder stack = new StringBuilder();
            for (StackTraceElement frame : thread.getStackTrace()) {
                stack.append("\n\tat ").append(frame);
            }
            throw new AssertionError("the emitted application did not stop within " + STOP_TIMEOUT
                    + "; its thread is at:" + stack);
        }
    }

    private static void runApplication(ClassLoader loader, String applicationClass,
                                       AtomicReference<Throwable> failure) {
        try {
            Object application = loader.loadClass(applicationClass).getDeclaredConstructor().newInstance();
            application.getClass().getMethod("run").invoke(application);
        } catch (InvocationTargetException e) {
            failure.set(e.getCause());
        } catch (ReflectiveOperationException | RuntimeException e) {
            failure.set(e);
        }
    }

    private static void awaitComposed(Thread thread, int port, AtomicReference<Throwable> failure) {
        long deadline = System.nanoTime() + BOOT_TIMEOUT.toNanos();
        String last = "no response yet";
        while (System.nanoTime() < deadline) {
            if (!thread.isAlive()) {
                throw new AssertionError("the emitted application exited during boot", failure.get());
            }
            try {
                last = RawHttp.request(port, "GET", "/probe");
                if (last.startsWith("HTTP/1.1 200")) {
                    return;
                }
            } catch (UncheckedIOException notListeningYet) {
                last = notListeningYet.toString();
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for the application", e);
            }
        }
        thread.interrupt();
        throw new AssertionError("the emitted application did not compose within " + BOOT_TIMEOUT
                + "; last probe: " + last, failure.get());
    }

    private static int reserveLoopbackPort() {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(RawHttp.LOOPBACK, 0));
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("unable to reserve a loopback port", e);
        }
    }
}
