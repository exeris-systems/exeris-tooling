package eu.exeris.kernel.core.http.client;

/**
 * Test stub for the kernel HTTP web client facade used by generated
 * {@code *Client} artifacts. Methods return {@code null} / no-op; only the
 * signatures matter for the compile-test gate.
 *
 * <p>Mirrors the kernel-side facade introduced by ADR-034 (Client-Side
 * Body Codec SPI + {@code KernelWebClient} facade). This module does not
 * depend on the real kernel artifact, so the stub stands in at the same
 * FQN that {@code KernelClientGenerator} emits.
 */
public class KernelWebClient {

    public <T> T get(String path, Class<T> type) {
        return null;
    }

    public <T> T post(String path, Object body, Class<T> type) {
        return null;
    }

    public <T> T patch(String path, Object body, Class<T> type) {
        return null;
    }

    public <T> T delete(String path, Class<T> type) {
        return null;
    }

    public static class WebClientException extends RuntimeException {

        private final boolean notFound;

        public WebClientException(String message, boolean notFound) {
            super(message);
            this.notFound = notFound;
        }

        public boolean isNotFound() {
            return notFound;
        }
    }
}
