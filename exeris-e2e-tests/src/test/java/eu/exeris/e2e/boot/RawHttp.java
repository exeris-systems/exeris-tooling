package eu.exeris.e2e.boot;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * One HTTP/1.1 request over a plain socket, {@code Connection: close}, read to EOF.
 *
 * <p>A raw socket rather than {@code java.net.http.HttpClient} because what is under test is the
 * kernel's own wire behaviour — which handler it resolved, which status it wrote — and a client
 * that follows, retries or pools would sit between the assertion and the bytes.
 */
final class RawHttp {

    static final String LOOPBACK = "127.0.0.1";
    private static final int CONNECT_TIMEOUT_MILLIS = 2_000;
    private static final int READ_TIMEOUT_MILLIS = 10_000;

    private RawHttp() {
    }

    /**
     * Sends a body-less request and returns the whole response as text.
     *
     * @throws UncheckedIOException when the port refuses the connection or the read fails
     */
    static String request(int port, String method, String path) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(LOOPBACK, port), CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            String head = method + " " + path + " HTTP/1.1\r\n"
                    + "Host: " + LOOPBACK + "\r\n"
                    + "Content-Length: 0\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(method + " " + path + " on port " + port + " failed", e);
        }
    }
}
