package eu.exeris.kernel.transport.http3.client;

/**
 * Test stub for the kernel HTTP/3 web client used by generated {@code *Client}
 * artifacts. Methods return {@code null} / no-op; only the signatures matter
 * for the compile-test gate.
 */
public class ExerisWebClient {

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
