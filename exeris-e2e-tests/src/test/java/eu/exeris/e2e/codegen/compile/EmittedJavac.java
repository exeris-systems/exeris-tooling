package eu.exeris.e2e.codegen.compile;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The one {@code javac} invocation every e2e gate uses to compile an emitted tree, so that the
 * compiler options, and the verdict on what the compiler says about emitted code, are stated once.
 *
 * <p>A warning in emitted source is a defect in the emitter: the consumer compiles the same text
 * with its own flags, and a consumer that builds with {@code -Werror} would be unable to build the
 * application at all. A diagnostic of kind {@code WARNING} or {@code MANDATORY_WARNING} therefore
 * fails the compile unless its javac diagnostic code is in {@link #ACCEPTED} with the reason the
 * emitted text cannot avoid it. The compiler is run with every lint category on
 * ({@code -Xlint:all}); a category is excluded only by an {@link #ACCEPTED} entry, never by a
 * flag, so the exclusion stays next to its reason.
 */
public final class EmittedJavac {

    /** The language level a consumer gets; equal to the reactor's {@code maven.compiler.release}. */
    public static final String RELEASE = "25";

    /**
     * One warning the emitted text is allowed to produce.
     *
     * @param code   the javac diagnostic code
     * @param file   the simple name of the one emitted file that may produce it
     * @param reason the invariant that makes the warning part of the emitted contract
     */
    public record Accepted(String code, String file, String reason) {
    }

    /**
     * The warnings the emitted text may produce; anything else javac reports fails the gate. An
     * entry is scoped to one code in one file, so a second occurrence elsewhere is still a defect.
     */
    public static final List<Accepted> ACCEPTED = List.of();

    private EmittedJavac() {
    }

    /** The outcome of one compile: whether it produced class files, and everything javac said. */
    public record Result(boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics) {

        /** Errors and un-accepted warnings, one per line; empty when the tree is clean. */
        public String render() {
            StringBuilder sb = new StringBuilder();
            for (Diagnostic<? extends JavaFileObject> d : diagnostics) {
                if (isDefect(d)) {
                    sb.append(d.getKind()).append(' ');
                    if (d.getSource() != null) {
                        sb.append(d.getSource().getName()).append(':').append(d.getLineNumber()).append(": ");
                    }
                    sb.append('[').append(d.getCode()).append("] ").append(d.getMessage(null))
                            .append(System.lineSeparator());
                }
            }
            return sb.toString();
        }

        /** True when javac accepted the tree and said nothing outside {@link #ACCEPTED}. */
        public boolean clean() {
            return success && diagnostics.stream().noneMatch(EmittedJavac::isDefect);
        }
    }

    static boolean isDefect(Diagnostic<? extends JavaFileObject> d) {
        return switch (d.getKind()) {
            case ERROR -> true;
            case WARNING, MANDATORY_WARNING -> ACCEPTED.stream().noneMatch(a -> a.code().equals(d.getCode())
                    && d.getSource() != null && d.getSource().getName().endsWith('/' + a.file()));
            default -> false;
        };
    }

    /**
     * Compiles {@code files} into {@code outputDir}.
     *
     * @param files     the {@code .java} files of the emitted tree and anything compiled beside it
     * @param outputDir where class files go; created
     * @param classpath the classpath, path-separator joined
     * @return the outcome
     * @throws IOException if {@code outputDir} cannot be created
     */
    public static Result compile(List<String> files, Path outputDir, String classpath) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("A JDK (not a JRE) is required to compile the emitted tree");
        }
        Files.createDirectories(outputDir);
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            List<File> sources = new ArrayList<>();
            files.forEach(f -> sources.add(new File(f)));
            boolean ok = compiler.getTask(new StringWriter(), fm, diagnostics, options(outputDir, classpath), null,
                    fm.getJavaFileObjectsFromFiles(sources)).call();
            return new Result(ok, List.copyOf(diagnostics.getDiagnostics()));
        }
    }

    /** The options shared by every gate; {@code outputDir} and {@code classpath} are per call. */
    static List<String> options(Path outputDir, String classpath) {
        return List.of("-d", outputDir.toString(), "-classpath", classpath, "--release", RELEASE, "-Xlint:all");
    }
}
