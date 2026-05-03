package eu.exeris.kernel.transport.http3.server;

/**
 * Test stub for the kernel HTTP/3 router. Generated {@code RouterConfig}
 * registers handlers via this surface.
 */
public final class Http3Router {

    public void register(String method, String path, Http3Handler handler) {
        // no-op stub
    }

    @FunctionalInterface
    public interface Http3Handler {
        void accept(Http3ServerExchange exchange) throws Exception;
    }
}
