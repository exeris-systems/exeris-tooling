package eu.exeris.e2e.codegen.compile;

import eu.exeris.tooling.codegen.java.CodegenPipeline;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * One emitted application, built the way a consumer's build produces it: annotated sources
 * through {@code javac} + the processor, the metadata through {@link CodegenPipeline#run}, and the
 * emitted tree (plus any hand-written sources beside it) through {@code javac} again — into class
 * files a class loader can run.
 *
 * <p>{@link ProcessorCompiler} answers "what does the processor write?", {@link InMemoryJavaCompiler}
 * "does the emitted source compile?". This answers "what does the emitted application do?", which
 * needs the classes on disk and loadable.
 */
public final class GeneratedTree implements AutoCloseable {

    private final Path generatedRoot;
    private final URLClassLoader loader;

    private GeneratedTree(Path generatedRoot, URLClassLoader loader) {
        this.generatedRoot = generatedRoot;
        this.loader = loader;
    }

    /**
     * Builds the application under {@code workspace}.
     *
     * @param workspace       a fresh directory; everything is written beneath it
     * @param basePackage     the pipeline's base package
     * @param domainSources   annotated entity sources, relative path → text
     * @param harnessSources  hand-written sources compiled against the emitted tree, relative
     *                        path → text; may be empty
     * @return the built tree; close it to release its class loader
     * @throws IOException if any file cannot be written
     */
    public static GeneratedTree build(Path workspace, String basePackage,
                                      Map<String, String> domainSources,
                                      Map<String, String> harnessSources) throws IOException {
        Path entityClasses = workspace.resolve("target/classes");
        Path generated = workspace.resolve("src/main/generated/java");
        Path harness = workspace.resolve("src/test/java");
        Path appClasses = workspace.resolve("target/app-classes");

        ProcessorCompiler.compile(workspace.resolve("src/main/java"), entityClasses, null, domainSources);
        CodegenPipeline.createDefault().run(entityClasses.resolve("exeris-metadata"), generated, basePackage);

        List<String> files = new ArrayList<>(javaSourcesUnder(generated));
        for (Map.Entry<String, String> source : harnessSources.entrySet()) {
            Path file = harness.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            files.add(file.toString());
        }
        compile(files, appClasses, entityClasses);

        URLClassLoader loader = new URLClassLoader(
                new URL[]{appClasses.toUri().toURL(), entityClasses.toUri().toURL()},
                GeneratedTree.class.getClassLoader());
        return new GeneratedTree(generated, loader);
    }

    /** The emitted main tree ({@code src/main/generated/java}). */
    public Path generatedRoot() {
        return generatedRoot;
    }

    /** Loads the emitted application's classes (and the harness's), parent-first. */
    public ClassLoader loader() {
        return loader;
    }

    /** Every emitted {@code .java} file, sorted. */
    public List<Path> javaSources() throws IOException {
        try (Stream<Path> tree = Files.walk(generatedRoot)) {
            return tree.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    @Override
    public void close() throws IOException {
        loader.close();
    }

    /**
     * Compiles against the test classpath plus the processor-compiled entities. The ADR-058
     * contract classpath is {@code GeneratedTestsE2ETest}'s job; what runs here needs the kernel's
     * runtime providers on the classpath anyway.
     */
    private static void compile(List<String> files, Path outputDir, Path entityClasses) throws IOException {
        EmittedJavac.Result result = EmittedJavac.compile(files, outputDir,
                System.getProperty("java.class.path") + File.pathSeparator + entityClasses);
        if (!result.clean()) {
            throw new IllegalStateException("the emitted tree did not compile cleanly:\n" + result.render());
        }
    }

    private static List<String> javaSourcesUnder(Path root) throws IOException {
        try (Stream<Path> tree = Files.walk(root)) {
            return tree.filter(p -> p.toString().endsWith(".java")).map(Path::toString).sorted().toList();
        }
    }
}
