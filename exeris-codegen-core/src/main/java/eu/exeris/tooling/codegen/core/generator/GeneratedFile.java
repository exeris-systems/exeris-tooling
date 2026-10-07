package eu.exeris.tooling.codegen.core.generator;

/**
 * Represents a generated source file.
 *
 * @param packageName  the package/directory for the generated file
 * @param className    the simple file name (without extension)
 * @param content      the full source code content
 * @param artifactType the type of artifact
 * @param extension    the file extension (e.g., "java", "sql", "ts")
 *
 * @since 0.1
 */
public record GeneratedFile(
    String packageName,
    String className,
    String content,
    KernelArtifactGenerator.ArtifactType artifactType,
    String extension
) {
    /**
     * Creates a generated file with the {@code java} extension.
     *
     * @param packageName  the package/directory for the generated file
     * @param className    the simple file name (without extension)
     * @param content      the full source code content
     * @param artifactType the type of artifact
     */
    public GeneratedFile(String packageName, String className, String content, KernelArtifactGenerator.ArtifactType artifactType) {
        this(packageName, className, content, artifactType, "java");
    }

    /**
     * Returns the dot-separated qualified name of the file's top-level type.
     *
     * @return {@code packageName.className}, or {@code className} alone in the default package
     */
    public String fullyQualifiedName() {
        return packageName.isEmpty() ? className : packageName + "." + className;
    }

    /**
     * Returns the file's path relative to the source root, with the package mapped to directories
     * and {@code java} used when the extension is {@code null}.
     *
     * @return a {@code /}-separated relative path ending in {@code className.extension}
     */
    public String relativePath() {
        String dir = packageName.replace('.', '/');
        String ext = extension != null ? extension : "java";
        return dir.isEmpty() ? className + "." + ext : dir + "/" + className + "." + ext;
    }

    /**
     * Starts a builder with an empty package and class name, empty content, no artifact type and
     * the {@code java} extension.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Mutable builder for {@link GeneratedFile}; every setter returns this builder.
     */
    public static class Builder {
        private String packageName = "";
        private String className = "";
        private String content = "";
        private KernelArtifactGenerator.ArtifactType artifactType;
        private String extension = "java";

        /**
         * Creates a builder holding the defaults described on {@link GeneratedFile#builder()}.
         */
        public Builder() {
            // defaults are the field initialisers
        }

        /**
         * Sets the package, which also gives the file's directory.
         *
         * @param v the dot-separated package, or empty for the default package
         * @return this builder
         */
        public Builder packageName(String v) { this.packageName = v; return this; }

        /**
         * Sets the simple file name.
         *
         * @param v the file name without extension
         * @return this builder
         */
        public Builder className(String v) { this.className = v; return this; }

        /**
         * Sets the file's full text.
         *
         * @param v the complete file content
         * @return this builder
         */
        public Builder content(String v) { this.content = v; return this; }

        /**
         * Sets the kind of artefact the file is.
         *
         * @param v the artefact type
         * @return this builder
         */
        public Builder artifactType(KernelArtifactGenerator.ArtifactType v) { this.artifactType = v; return this; }

        /**
         * Sets the file extension.
         *
         * @param v the extension without the leading dot, e.g. {@code java}, {@code sql}, {@code ts}
         * @return this builder
         */
        public Builder extension(String v) { this.extension = v; return this; }

        /**
         * Creates the file from the values set so far.
         *
         * @return a new generated file
         */
        public GeneratedFile build() {
            return new GeneratedFile(packageName, className, content, artifactType, extension);
        }
    }
}

