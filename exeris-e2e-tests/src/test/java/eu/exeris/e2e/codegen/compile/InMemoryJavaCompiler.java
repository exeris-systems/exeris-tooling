package eu.exeris.e2e.codegen.compile;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * In-memory wrapper around {@link JavaCompiler} so the e2e suite can verify
 * that generated kernel artifacts (and the source domain entity) compile
 * together against the kernel SPI stubs on the test classpath.
 */
public final class InMemoryJavaCompiler {

    private final Map<String, String> sources = new LinkedHashMap<>();

    public InMemoryJavaCompiler addSource(String fullyQualifiedName, String content) {
        sources.put(fullyQualifiedName, content);
        return this;
    }

    public Result compile() {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                    "No JavaCompiler available — run tests on a JDK, not a JRE.");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager standardFileManager =
                compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);

        // Forward the surefire-supplied test classpath to javac explicitly.
        // The default StandardJavaFileManager search path does not always
        // inherit java.class.path, so kernel SPI + Jackson 3 jars would be
        // invisible to the compile-gate otherwise.
        try {
            String classpath = System.getProperty("java.class.path");
            if (classpath != null && !classpath.isEmpty()) {
                List<File> cpEntries = Arrays.stream(classpath.split(File.pathSeparator))
                        .map(File::new)
                        .filter(File::exists)
                        .toList();
                standardFileManager.setLocation(StandardLocation.CLASS_PATH, cpEntries);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to seed compile-test classpath", e);
        }

        ClassCollector fileManager = new ClassCollector(standardFileManager);

        List<JavaFileObject> compilationUnits = new ArrayList<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            compilationUnits.add(new SourceFileObject(entry.getKey(), entry.getValue()));
        }

        boolean success = compiler
                .getTask(null, fileManager, diagnostics, List.of(), null, compilationUnits)
                .call();

        List<Diagnostic<? extends JavaFileObject>> errors = diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .toList();

        return new Result(success && errors.isEmpty(), diagnostics.getDiagnostics(), errors);
    }

    public record Result(
            boolean success,
            List<Diagnostic<? extends JavaFileObject>> diagnostics,
            List<Diagnostic<? extends JavaFileObject>> errors) {

        public String renderErrors() {
            StringBuilder sb = new StringBuilder();
            for (Diagnostic<? extends JavaFileObject> d : errors) {
                sb.append(d.getKind()).append(' ');
                if (d.getSource() != null) {
                    sb.append(d.getSource().getName()).append(':').append(d.getLineNumber()).append(": ");
                }
                sb.append(d.getMessage(null)).append(System.lineSeparator());
            }
            return sb.toString();
        }
    }

    private static final class SourceFileObject extends SimpleJavaFileObject {
        private final String content;

        SourceFileObject(String fullyQualifiedName, String content) {
            super(URI.create("mem:///" + fullyQualifiedName.replace('.', '/') + Kind.SOURCE.extension),
                    Kind.SOURCE);
            this.content = content;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return content;
        }
    }

    private static final class ClassByteObject extends SimpleJavaFileObject {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        ClassByteObject(String fullyQualifiedName) {
            super(URI.create("mem:///" + fullyQualifiedName.replace('.', '/') + Kind.CLASS.extension),
                    Kind.CLASS);
        }

        @Override
        public OutputStream openOutputStream() {
            return bytes;
        }
    }

    /**
     * Discards generated bytecode rather than writing it to disk; the only
     * thing the test cares about is whether {@code javac} accepted the input.
     */
    private static final class ClassCollector
            extends ForwardingJavaFileManager<JavaFileManager> {

        private final Map<String, ClassByteObject> emitted = new HashMap<>();

        ClassCollector(JavaFileManager fileManager) {
            super(fileManager);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location,
                                                   String className,
                                                   JavaFileObject.Kind kind,
                                                   FileObject sibling) {
            if (location == StandardLocation.CLASS_OUTPUT && kind == JavaFileObject.Kind.CLASS) {
                ClassByteObject obj = new ClassByteObject(className);
                emitted.put(className, obj);
                return obj;
            }
            throw new IllegalStateException("Unexpected output: " + location + " " + kind);
        }

        @SuppressWarnings("unused")
        Collection<ClassByteObject> emitted() {
            return emitted.values();
        }
    }
}
