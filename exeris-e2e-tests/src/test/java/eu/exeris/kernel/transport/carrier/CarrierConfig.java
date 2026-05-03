package eu.exeris.kernel.transport.carrier;

/**
 * Test stub for the kernel transport carrier configuration. Generated
 * {@code Application} populates this via the fluent builder.
 */
public final class CarrierConfig {

    private CarrierConfig() {
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        public Builder port(int port) {
            return this;
        }

        public Builder certPath(String path) {
            return this;
        }

        public Builder keyPath(String path) {
            return this;
        }

        public CarrierConfig build() {
            return new CarrierConfig();
        }
    }
}
