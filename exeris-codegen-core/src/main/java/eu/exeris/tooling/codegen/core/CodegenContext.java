package eu.exeris.tooling.codegen.core;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Context for code generation operations targeting the Exeris kernel.
 *
 * @param outputDir        target directory for generated code
 * @param generateTests    whether to generate test stubs
 * @param packagePrefix    optional package prefix override
 * @param generateComments whether to include Javadoc comments
 *
 * @since 0.1.0
 */
public record CodegenContext(
        Path outputDir,
        boolean generateTests,
        String packagePrefix,
        boolean generateComments
) {

    public CodegenContext {
        Objects.requireNonNull(outputDir, "outputDir must not be null");
    }

    /**
     * Creates a default context for the Exeris kernel target.
     */
    public static CodegenContext forKernel(Path outputDir) {
        return new CodegenContext(outputDir, false, null, true);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the effective package name, using prefix if set.
     */
    public String effectivePackage(String basePackage) {
        if (packagePrefix != null && !packagePrefix.isBlank()) {
            return packagePrefix + "." + basePackage;
        }
        return basePackage;
    }

    public static class Builder {
        private Path outputDir;
        private boolean generateTests = false;
        private String packagePrefix = null;
        private boolean generateComments = true;

        public Builder outputDir(Path outputDir) {
            this.outputDir = outputDir;
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
            return new CodegenContext(outputDir, generateTests, packagePrefix, generateComments);
        }
    }
}
