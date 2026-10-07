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
 * @since 0.1
 */
public record CodegenContext(
        Path outputDir,
        boolean generateTests,
        String packagePrefix,
        boolean generateComments
) {

    /**
     * Creates a context.
     *
     * @throws NullPointerException if {@code outputDir} is {@code null}
     */
    public CodegenContext {
        Objects.requireNonNull(outputDir, "outputDir must not be null");
    }

    /**
     * Creates the default context for the Exeris kernel target: no test stubs, no package prefix,
     * Javadoc comments included.
     *
     * @param outputDir target directory for generated code
     * @return a context writing to {@code outputDir} with the default settings
     */
    public static CodegenContext forKernel(Path outputDir) {
        return new CodegenContext(outputDir, false, null, true);
    }

    /**
     * Starts a builder initialised with the same defaults as {@link #forKernel(Path)}; the output
     * directory has to be set before {@link Builder#build()}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the package generated code is placed in: {@code basePackage} under the package
     * prefix when one is set and not blank, otherwise {@code basePackage} unchanged.
     *
     * @param basePackage the package derived from the domain metadata
     * @return {@code packagePrefix + "." + basePackage}, or {@code basePackage} when no prefix is set
     */
    public String effectivePackage(String basePackage) {
        if (packagePrefix != null && !packagePrefix.isBlank()) {
            return packagePrefix + "." + basePackage;
        }
        return basePackage;
    }

    /**
     * Mutable builder for {@link CodegenContext}. Defaults: no test stubs, no package prefix,
     * Javadoc comments included; the output directory has no default.
     */
    public static class Builder {
        private Path outputDir;
        private boolean generateTests = false;
        private String packagePrefix = null;
        private boolean generateComments = true;

        /**
         * Creates a builder holding the defaults; {@link CodegenContext#builder()} is the usual
         * entry point.
         */
        public Builder() {
            // defaults are the field initialisers
        }

        /**
         * Sets the target directory for generated code.
         *
         * @param outputDir target directory; must be non-null by the time {@link #build()} runs
         * @return this builder
         */
        public Builder outputDir(Path outputDir) {
            this.outputDir = outputDir;
            return this;
        }

        /**
         * Sets whether test stubs are generated.
         *
         * @param generateTests {@code true} to generate test stubs
         * @return this builder
         */
        public Builder generateTests(boolean generateTests) {
            this.generateTests = generateTests;
            return this;
        }

        /**
         * Sets the package prefix placed in front of every derived package.
         *
         * @param packagePrefix the prefix, or {@code null} or blank for none
         * @return this builder
         * @see CodegenContext#effectivePackage(String)
         */
        public Builder packagePrefix(String packagePrefix) {
            this.packagePrefix = packagePrefix;
            return this;
        }

        /**
         * Sets whether generated code carries Javadoc comments.
         *
         * @param generateComments {@code true} to include Javadoc comments
         * @return this builder
         */
        public Builder generateComments(boolean generateComments) {
            this.generateComments = generateComments;
            return this;
        }

        /**
         * Creates a context from the values set so far.
         *
         * @return a new context
         * @throws NullPointerException if no output directory was set
         */
        public CodegenContext build() {
            return new CodegenContext(outputDir, generateTests, packagePrefix, generateComments);
        }
    }
}
