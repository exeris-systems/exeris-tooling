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
 * @author Exeris Team
 * @since 0.1.0
 */
public record GeneratedFile(
    String packageName,
    String className,
    String content,
    KernelArtifactGenerator.ArtifactType artifactType,
    String extension
) {
    /**
     * Constructor with default .java extension for backward compatibility.
     */
    public GeneratedFile(String packageName, String className, String content, KernelArtifactGenerator.ArtifactType artifactType) {
        this(packageName, className, content, artifactType, "java");
    }

    /**
     * Get the fully qualified class name.
     */
    public String fullyQualifiedName() {
        return packageName.isEmpty() ? className : packageName + "." + className;
    }

    /**
     * Get the file path relative to source root.
     */
    public String relativePath() {
        String dir = packageName.replace('.', '/');
        String ext = extension != null ? extension : "java";
        return dir.isEmpty() ? className + "." + ext : dir + "/" + className + "." + ext;
    }

    /**
     * Builder for GeneratedFile.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String packageName = "";
        private String className = "";
        private String content = "";
        private KernelArtifactGenerator.ArtifactType artifactType;
        private String extension = "java";

        public Builder packageName(String v) { this.packageName = v; return this; }
        public Builder className(String v) { this.className = v; return this; }
        public Builder content(String v) { this.content = v; return this; }
        public Builder artifactType(KernelArtifactGenerator.ArtifactType v) { this.artifactType = v; return this; }
        public Builder extension(String v) { this.extension = v; return this; }

        public GeneratedFile build() {
            return new GeneratedFile(packageName, className, content, artifactType, extension);
        }
    }
}

