package eu.exeris.tooling.codegen.java;

import eu.exeris.tooling.codegen.core.capability.CapabilityGraphException;
import eu.exeris.tooling.codegen.java.kernel.UnpersistableFieldTypeException;
import eu.exeris.tooling.diagnostics.DiagnosticId;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * Command-line entry point for the Exeris Java code generator, and the {@code Main-Class} of
 * the {@code exeris-codegen-cli} jar (ADR-097).
 *
 * <p>A thin shell: generation happens in {@link CodegenPipeline} and argument parsing in
 * {@link CliArgs}. The shell runs the pipeline the way {@code exeris:generate} does, main root
 * first and then, with {@code --tests}, the generated-test root, and translates the outcome into
 * an exit status:
 * <ul>
 *   <li>{@code 0}: the run succeeded, including a run that found no metadata and owned no tree;</li>
 *   <li>{@code 1}: generation was refused or failed;</li>
 *   <li>{@code 2}: the arguments were invalid.</li>
 * </ul>
 * Every non-zero exit prints a line carrying a {@link DiagnosticId} to {@code stderr}. The
 * capability graph is validated fail-fast: the command line has no later gate to defer to.
 *
 * <p>{@link #main(String[])} only translates the status of
 * {@link #runOrPrintError(String[], PrintStream, PrintStream)} into {@code System.exit}; the
 * testable surface is that method.
 *
 * <h2>Usage</h2>
 * <pre>
 * java -jar exeris-codegen-cli-&lt;version&gt;.jar \
 *     --metadata-dir=target/classes/exeris-metadata \
 *     --output-dir=src/main/generated/java \
 *     --base-package=com.example.shop \
 *     [--tests --test-output-dir=src/test/generated/java] [--allow-empty]
 *
 * java -jar exeris-codegen-cli-&lt;version&gt;.jar --print-catalogue
 * </pre>
 *
 * <h2>Version</h2>
 * The version logged at start is the {@code Implementation-Version} of the jar this class was
 * loaded from, or {@code unknown} without one. It appears in log output only, never in a
 * generated file.
 *
 * <h2>Logging</h2>
 * Progress goes through {@link System.Logger}; no third-party logging dependency is pulled in.
 *
 * @since 0.1
 */
public final class CodegenMain {

    /** Status of a run that succeeded. */
    static final int EXIT_OK = 0;
    /** Status of a run whose generation was refused or failed. */
    static final int EXIT_FAILED = 1;
    /** Status of a run whose arguments were invalid. */
    static final int EXIT_USAGE = 2;

    /** The generator catalogue (ADR-097), as {@code exeris-codegen-java} carries it. */
    static final String CATALOGUE_RESOURCE = "/META-INF/exeris/generator-catalogue.json";

    private static final Logger LOG = System.getLogger(CodegenMain.class.getName());

    private CodegenMain() {
        // CLI entry point — not instantiable.
    }

    /**
     * Runs the generator from the command line and exits the JVM with the run's status:
     * {@code 0} on success, {@code 1} on malformed arguments or a pipeline failure.
     *
     * @param args {@code --metadata-dir=<path>}, {@code --output-dir=<path>} and an optional
     *             {@code --base-package=<package>}
     */
    public static void main(String[] args) {
        System.exit(runOrPrintError(args, System.out, System.err));
    }

    /**
     * Body of {@link #main(String[])} hoisted to a normal method so it is unit-testable without
     * {@code System.exit}. Both streams are injected so tests can capture them.
     *
     * @param out receives the catalogue under {@code --print-catalogue}, and nothing otherwise
     * @param err receives the usage hint and every failure line
     * @return the process exit status: {@link #EXIT_OK}, {@link #EXIT_FAILED} or
     *         {@link #EXIT_USAGE}
     */
    static int runOrPrintError(String[] args, PrintStream out, PrintStream err) {
        CliArgs parsed;
        try {
            parsed = CliArgs.parse(args);
        } catch (IllegalArgumentException badArgs) {
            err.println(DiagnosticId.CLI_ARGUMENTS_INVALID.format(badArgs.getMessage()));
            printUsage(err);
            return EXIT_USAGE;
        }

        if (parsed.printCatalogue()) {
            return printCatalogue(out, err);
        }

        LOG.log(Level.INFO, "Exeris Java Code Generator " + version() + " starting");
        return generate(parsed, CodegenPipeline.createDefault(), err);
    }

    /**
     * Runs the pipeline as {@code exeris:generate} does: {@code allowEmpty} passed through, the
     * capability graph validated fail-fast, and the tests written after the main root.
     */
    static int generate(CliArgs args, CodegenPipeline pipeline, PrintStream err) {
        try {
            pipeline.run(args.metadataDir(), args.outputDir(), args.basePackage(), args.allowEmpty(), false);
        } catch (IOException e) {
            err.println(DiagnosticId.CLI_GENERATION_FAILED.format(
                    "Code generation failed (metadataDir=" + args.metadataDir() + "): " + e));
            return EXIT_FAILED;
        } catch (RuntimeException e) {
            return refusedOrFailed(e, err);
        }

        if (!args.tests()) {
            return EXIT_OK;
        }
        try {
            pipeline.runTests(args.metadataDir(), args.testOutputDir(), args.basePackage());
            return EXIT_OK;
        } catch (IOException e) {
            err.println(DiagnosticId.CLI_GENERATION_FAILED.format(
                    "Test generation failed (testOutputDir=" + args.testOutputDir() + "): " + e));
            return EXIT_FAILED;
        } catch (RuntimeException e) {
            return refusedOrFailed(e, err);
        }
    }

    /**
     * A refusal of the input prints its message under the identifier of the event, as
     * {@code exeris:generate} reports it; anything else is a generator failure and keeps its stack
     * trace.
     */
    private static int refusedOrFailed(RuntimeException e, PrintStream err) {
        switch (e) {
            case UnpersistableFieldTypeException refused ->
                    // The message carries its own identifier (EXT-GEN-3003).
                    err.println(refused.getMessage());
            case EmptyMetadataException refused -> {
                err.println(DiagnosticId.EMPTY_METADATA_REFUSED.format(refused.getMessage()));
                err.println("On the command line, pass --allow-empty for the teardown.");
            }
            case CapabilityGraphException refused ->
                    err.println(DiagnosticId.CAPABILITY_GRAPH_UNRESOLVED.format(refused.getMessage()));
            default -> {
                err.println(DiagnosticId.CLI_GENERATION_FAILED.format("Code generation failed: " + e));
                e.printStackTrace(err);
            }
        }
        return EXIT_FAILED;
    }

    /** Copies the catalogue byte for byte; it is committed in canonical form, so nothing is re-encoded. */
    static int printCatalogue(PrintStream out, PrintStream err) {
        try (InputStream in = CodegenMain.class.getResourceAsStream(CATALOGUE_RESOURCE)) {
            if (in == null) {
                err.println(DiagnosticId.CLI_CATALOGUE_UNREADABLE.format(
                        CATALOGUE_RESOURCE + " is not on the classpath"));
                return EXIT_FAILED;
            }
            in.transferTo(out);
            out.flush();
            return EXIT_OK;
        } catch (IOException e) {
            err.println(DiagnosticId.CLI_CATALOGUE_UNREADABLE.format(
                    "Reading " + CATALOGUE_RESOURCE + " failed: " + e));
            return EXIT_FAILED;
        }
    }

    /** The {@code Implementation-Version} of the jar this class came from, or {@code unknown}. */
    static String version() {
        return versionOf(CodegenMain.class.getPackage());
    }

    static String versionOf(Package pkg) {
        String version = pkg == null ? null : pkg.getImplementationVersion();
        return version == null || version.isBlank() ? "unknown" : version;
    }

    private static void printUsage(PrintStream err) {
        err.println("Usage: java -jar exeris-codegen-cli-<version>.jar <options>");
        err.println("Required:");
        err.println("  --metadata-dir=<path>     Directory holding the processor's exeris-metadata JSON");
        err.println("  --output-dir=<path>       Root the generated main sources are written to");
        err.println("Optional:");
        err.println("  --base-package=<pkg>      Base package for application classes");
        err.println("                            (default: the package of the first domain by");
        err.println("                            qualified name, without .domain)");
        err.println("  --tests                   Also write the generated tests (requires --test-output-dir)");
        err.println("  --test-output-dir=<path>  Root the generated tests are written to");
        err.println("  --allow-empty             Permit pruning a previously generated tree when no");
        err.println("                            @ExerisDomain is found");
        err.println("  --print-catalogue         Write the generator catalogue to stdout and exit;");
        err.println("                            needs no other option");
        err.println("Exit status: 0 success, 1 generation refused or failed, 2 invalid arguments.");
    }
}
