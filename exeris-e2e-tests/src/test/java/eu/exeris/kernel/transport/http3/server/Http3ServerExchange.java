package eu.exeris.kernel.transport.http3.server;

import java.net.URI;

/**
 * Test stub for the kernel HTTP/3 server exchange. Mirrors the surface that
 * generated handlers and the router config call against. Only the shape that
 * generated code touches needs to be preserved here.
 */
public interface Http3ServerExchange {

    Request request();

    Response response();

    interface Request {
        URI uri();

        byte[] bodyAsBytes();
    }

    interface Response {
        void sendHeaders(int status, Object headers);

        void sendText(String body);
    }
}
