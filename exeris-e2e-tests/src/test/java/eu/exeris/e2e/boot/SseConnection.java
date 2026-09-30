package eu.exeris.e2e.boot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * An open SSE stream over a plain socket: the response head, then its lines as they arrive.
 *
 * <p>Lines are read on a virtual thread into a queue, so the test thread can wait for a frame
 * with a deadline while it does something else — publish — without a socket timeout interrupting
 * a line half-read. The kernel's SSE framing is connection-delimited (no chunked encoding), so a
 * line on the wire is a line of the event stream.
 */
final class SseConnection implements AutoCloseable {

    /** Queued when the server closes the connection. Compared by identity. */
    private static final String EOF = new String("<eof>");

    private final Socket socket;
    private final String head;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();

    private SseConnection(Socket socket, String head, BufferedReader reader) {
        this.socket = socket;
        this.head = head;
        Thread.ofVirtual().name("sse-reader").start(() -> {
            try {
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    lines.add(line);
                }
            } catch (IOException ignored) {
                // closed from our side, or reset by the peer — either way the stream is over
            }
            lines.add(EOF);
        });
    }

    /** Opens {@code GET path} as an event stream and reads the response head. */
    static SseConnection open(int port, String path) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(RawHttp.LOOPBACK, port), 2_000);
            socket.setSoTimeout(0);
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + RawHttp.LOOPBACK + "\r\n"
                    + "Accept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder head = new StringBuilder();
            socket.setSoTimeout(10_000);
            for (String line = reader.readLine(); line != null && !line.isEmpty(); line = reader.readLine()) {
                head.append(line).append('\n');
            }
            socket.setSoTimeout(0);
            return new SseConnection(socket, head.toString(), reader);
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw new UncheckedIOException("opening stream " + path + " on port " + port + " failed", e);
        }
    }

    /** The response head: status line and headers, without the terminating blank line. */
    String head() {
        return head;
    }

    /**
     * Waits for the server to close the stream and returns every line received before it did.
     *
     * @throws AssertionError when the stream is still open at the deadline
     */
    List<String> awaitServerClose(Duration timeout) throws InterruptedException {
        List<String> received = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            String line = lines.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (line == null) {
                throw new AssertionError("stream still open after " + timeout + "; received " + received);
            }
            if (line == EOF) {
                return received;
            }
            received.add(line);
        }
    }

    /**
     * Runs {@code trigger} until a complete event arrives, and returns that event's lines.
     *
     * <p>An event is the non-empty lines up to a blank line; it counts only if it carries an
     * {@code event:} field, so a comment or keep-alive line alone does not end the wait.
     *
     * @throws AssertionError when no event arrives by the deadline, or the server closes first
     */
    List<String> awaitFrame(Runnable trigger, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<String> event = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            trigger.run();
            long pollUntil = Math.min(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250));
            String line;
            while ((line = lines.poll(Math.max(0, pollUntil - System.nanoTime()), TimeUnit.NANOSECONDS)) != null) {
                if (line == EOF) {
                    throw new AssertionError("stream closed by the server before a frame arrived; partial: " + event);
                }
                if (!line.isEmpty()) {
                    event.add(line);
                } else if (event.stream().anyMatch(l -> l.startsWith("event:"))) {
                    return event;
                } else {
                    event.clear();
                }
            }
        }
        throw new AssertionError("no SSE frame within " + timeout + "; partial: " + event);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
