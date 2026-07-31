package eu.exeris.e2e.codegen.compile;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Compiles annotated fixture sources <b>on disk</b> with the real
 * {@code ExerisDomainProcessor} attached, producing both the class files and the
 * {@code exeris-metadata/} JSON the way a downstream {@code javac} run produces them.
 *
 * <p>The sibling {@link InMemoryJavaCompiler} deliberately keeps everything in memory — it exists
 * to answer "does the emitted source compile?". This one exists to answer "what does the processor
 * actually write?", which needs real files: the cap-tier Wall scans a directory, the pipeline reads
 * a metadata directory, and a class loader loads the classes.
 */
public final class ProcessorCompiler {

    /** The processor under test, named rather than discovered (see {@link #compile}). */
    private static final String PROCESSOR = "eu.exeris.tooling.processor.ExerisDomainProcessor";

    private ProcessorCompiler() {
    }

    /**
     * Writes {@code sources} under {@code srcRoot} and compiles them into {@code outputDir}.
     *
     * <p>The processor is named on the command line rather than left to service discovery:
     * {@code -processorpath} would also pick up whatever else the test classpath registers, and a
     * fixture must be processed by exactly the processor under test.
     *
     * @param srcRoot        directory the sources are written to (created as needed)
     * @param outputDir      {@code -d} target; receives {@code *.class} + {@code exeris-metadata/}
     * @param extraClasspath one extra classpath entry — the dependency-artefact stand-in a fixture
     *                       must see as a dependency rather than as its own output — or {@code null}
     * @param sources        relative source path → source text
     * @throws IOException           if a source file cannot be written
     * @throws IllegalStateException if no JDK compiler is available, or compilation fails
     */
    public static void compile(Path srcRoot, Path outputDir, Path extraClasspath,
                               Map<String, String> sources) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("A JDK (not a JRE) is required to compile fixtures");
        }
        Files.createDirectories(outputDir);

        String classpath = System.getProperty("java.class.path")
                + (extraClasspath != null ? File.pathSeparator + extraClasspath : "");
        List<String> args = new ArrayList<>(List.of(
                "-d", outputDir.toString(),
                "-classpath", classpath,
                "-processorpath", System.getProperty("java.class.path"),
                "-processor", PROCESSOR,
                "-nowarn"));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = srcRoot.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }

        int rc = compiler.run(null, null, null, args.toArray(String[]::new));
        if (rc != 0) {
            throw new IllegalStateException("Fixture compilation failed (javac exit " + rc
                    + ") — see the captured javac output above");
        }
    }
}
