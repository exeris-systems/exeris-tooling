package eu.exeris.tooling.codegen.core;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Context for code generation operations.
 * <p>
 * Provides configuration and state for generators including:
 * <ul>
 *   <li>Output directory</li>
 *   <li>Target backend (Spring/Micronaut/Quarkus/Kernel)</li>
 *   <li>Generation options</li>
 * </ul>
 *
 * @param outputDir        Target directory for generated code
 * @param backend          Target runtime framework
 * @param generateTests    Whether to generate test stubs
 * @param packagePrefix    Optional package prefix override
 * @param generateComments Whether to include Javadoc comments
 *
 * @author Exeris Team
 * @since 0.1.0
 */
public record CodegenContext(
        Path outputDir,
        PluggableBackend backend,
        boolean generateTests,
        String packagePrefix,
        boolean generateComments
) {

    /**
     * Creates a new CodegenContext with validation.
     */
    public CodegenContext {
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Objects.requireNonNull(backend, "backend must not be null");
    }

    /**
     * Creates a default context for Spring backend.
     *
     * @param outputDir Target directory
     * @return Context configured for Spring
     */
    public static CodegenContext forSpring(Path outputDir) {
        return new CodegenContext(outputDir, PluggableBackend.SPRING, false, null, true);
    }

    /**
     * Creates a context for Exeris Kernel backend.
     *
     * @param outputDir Target directory
     * @return Context configured for Kernel
     */
    public static CodegenContext forKernel(Path outputDir) {
        return new CodegenContext(outputDir, PluggableBackend.KERNEL, false, null, true);
    }

    /**
     * Creates a builder for flexible context construction.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the effective package name, using prefix if set.
     *
     * @param basePackage Original package from metadata
     * @return Effective package name
     */
    public String effectivePackage(String basePackage) {
        if (packagePrefix != null && !packagePrefix.isBlank()) {
            return packagePrefix + "." + basePackage;
        }
        return basePackage;
    }

    /**
     * Builder for CodegenContext.
     */
    public static class Builder {
        private Path outputDir;
        private PluggableBackend backend = PluggableBackend.SPRING;
        private boolean generateTests = false;
        private String packagePrefix = null;
        private boolean generateComments = true;

        public Builder outputDir(Path outputDir) {
            this.outputDir = outputDir;
            return this;
        }

        public Builder backend(PluggableBackend backend) {
            this.backend = backend;
            return this;
        }

        public Builder generateTests(boolean generateTests) {
            this.generateTests = generateTests;
            return this;
        }

        public Builder packagePrefix(String packagePrefix) {
            this.packagePrefix = packagePrefix;
            return this;
        }

        public Builder generateComments(boolean generateComments) {
            this.generateComments = generateComments;
            return this;
        }

        public CodegenContext build() {
            return new CodegenContext(outputDir, backend, generateTests, packagePrefix, generateComments);
        }
    }
}

