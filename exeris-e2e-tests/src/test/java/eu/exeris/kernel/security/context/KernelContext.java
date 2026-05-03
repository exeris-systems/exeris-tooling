package eu.exeris.kernel.security.context;

import eu.exeris.kernel.config.KernelProfile;

import java.nio.file.Path;

/**
 * Test stub for the kernel composition context. Generated {@code Application}
 * builds an instance via the {@link #builder()} fluent API.
 */
public final class KernelContext {

    private KernelContext() {
    }

    public static Builder builder() {
        return new Builder();
    }

    public enum FailurePolicy {
        FAIL_FAST,
        DEGRADE
    }

    public static final class Builder {
        public Builder profile(KernelProfile profile) {
            return this;
        }

        public Builder failurePolicy(FailurePolicy policy) {
            return this;
        }

        public Builder configPath(Path path) {
            return this;
        }

        public KernelContext build() {
            return new KernelContext();
        }
    }
}
