package eu.exeris.tooling.codegen.java;

import java.io.PrintStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * CLI entry point for the Exeris Java Code Generator.
 *
 * <p>This class is intentionally a thin shell. All real work happens in
 * {@link CodegenPipeline}; argument parsing lives in {@link CliArgs}. The
 * shell only:
 * <ul>
 *   <li>parses {@code args} via {@link CliArgs#parse(String[])}</li>
 *   <li>on parse failure: prints the message + a usage hint to {@code stderr}
 *       and returns a non-zero exit code</li>
 *   <li>on pipeline failure: logs the exception and returns a non-zero
 *       exit code</li>
 *   <li>otherwise returns 0</li>
 * </ul>
 *
 * <p>{@link #main(String[])} is a one-liner that translates the exit code
 * from {@link #runOrPrintError(String[], PrintStream)} into a
 * {@code System.exit} call — so the exit translation is the ONLY uncovered
 * line (the testable surface lives on {@code runOrPrintError}, exercised by
 * {@code CodegenMainTest}). JaCoCo measures this class normally; the
 * earlier {@code CodegenMain.class} plugin exclude is removed.
 *
 * <h2>Usage:</h2>
 * <pre>
 * java -cp ... eu.exeris.tooling.codegen.java.CodegenMain \
 *     --metadata-dir=target/exeris-metadata \
 *     --output-dir=target/generated-sources/exeris \
 *     --base-package=eu.exeris.foundation
 * </pre>
 *
 * <h2>Generated artifact set:</h2>
 * See {@link CodegenPipeline} and {@link
 * eu.exeris.tooling.codegen.java.kernel.KernelGeneratorStrategy} for the
 * per-entity roster and the application-bootstrap pair.
 *
 * <h2>Logging:</h2>
 * Uses {@link System.Logger} (JDK-standard, JSR 264). No third-party
 * logging dependency is pulled in.
 *
 * @author Exeris Team
 * @since 0.1.0
 */
public final class CodegenMain {

    private static final Logger LOG = System.getLogger(CodegenMain.class.getName());

    private CodegenMain() {
        // CLI entry point — not instantiable.
    }

    public static void main(String[] args) {
        System.exit(runOrPrintError(args, System.err));
    }

    /**
     * Body of {@link #main(String[])} hoisted to a normal method so it is
     * unit-testable without {@code System.exit}. {@code stderr} is injected
     * so tests can capture the usage hint without resorting to
     * {@code System.setErr}.
     *
     * @return process exit code: {@code 0} on success, {@code 1} on either
     *         malformed arguments or pipeline failure.
     */
    static int runOrPrintError(String[] args, PrintStream err) {
        LOG.log(Level.INFO, "Exeris Java Code Generator v0.1.0 starting");

        CliArgs parsed;
        try {
            parsed = CliArgs.parse(args);
        } catch (IllegalArgumentException badArgs) {
            err.println(badArgs.getMessage());
            printUsage(err);
            return 1;
        }

        try {
            CodegenPipeline.createDefault().run(
                    parsed.metadataDir(),
                    parsed.outputDir(),
                    parsed.basePackage()
            );
            return 0;
        } catch (Exception e) {
            LOG.log(Level.ERROR, "Code generation failed", e);
            return 1;
        }
    }

    private static void printUsage(PrintStream err) {
        err.println("Usage: CodegenMain <options>");
        err.println("Required:");
        err.println("  --metadata-dir=<path>   Path to exeris-metadata JSON files");
        err.println("  --output-dir=<path>     Path for generated Java sources");
        err.println("Optional:");
        err.println("  --base-package=<pkg>    Base package for application classes");
    }
}
