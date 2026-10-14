package eu.exeris.tooling.initializr.cli;

import eu.exeris.tooling.diagnostics.DiagnosticId;
import eu.exeris.tooling.initializr.engine.ProjectGenerator;
import eu.exeris.tooling.initializr.engine.ProjectWriter;
import eu.exeris.tooling.initializr.engine.ScaffoldVersions;
import eu.exeris.tooling.initializr.engine.TargetExistsException;
import eu.exeris.tooling.initializr.model.ProjectScaffoldRequest;
import eu.exeris.tooling.initializr.model.ProjectTree;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;

/**
 * Command-line entry point of the initializr, and the {@code Main-Class} of the
 * {@code exeris-initializr} jar: writes a new Exeris project as a directory or a ZIP archive
 * (ADR-091 Amendment 5).
 *
 * <p>The exit status says how the run ended, as for the {@code exeris-codegen-cli} jar (ADR-097):
 * <ul>
 *   <li>{@code 0}: the project was written, or the version or usage was printed;</li>
 *   <li>{@code 1}: the write was refused, because the target exists, or failed;</li>
 *   <li>{@code 2}: the arguments were invalid.</li>
 * </ul>
 * Every non-zero exit prints a line carrying a {@link DiagnosticId} to {@code stderr}.
 *
 * <p>The version printed by {@code --version} and written as the generated parent's version is the
 * one this initializr was built as ({@link ScaffoldVersions#bundled()}).
 *
 * @since 0.10
 */
public final class InitializrCliMain {

    /** Status of a run that succeeded. */
    static final int EXIT_OK = 0;
    /** Status of a run whose write was refused or failed. */
    static final int EXIT_FAILED = 1;
    /** Status of a run whose arguments were invalid. */
    static final int EXIT_USAGE = 2;

    private InitializrCliMain() {
    }

    /**
     * Runs the initializr and exits the JVM with the run's status: {@code 0} on success, {@code 1}
     * when the write is refused or fails, {@code 2} when the arguments are invalid.
     *
     * @param args the command-line switches, as {@link InitializrArgs#parse(String[])} reads them
     * @since 0.10
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Runs the initializr without exiting the JVM.
     *
     * @param args the command-line switches
     * @param out  receives the version, the usage under {@code --help}, and the line naming what was
     *             written
     * @param err  receives every failure line, and the usage after an argument error
     * @return the exit status: {@link #EXIT_OK}, {@link #EXIT_FAILED} or {@link #EXIT_USAGE}
     */
    static int run(String[] args, PrintStream out, PrintStream err) {
        InitializrArgs parsed;
        try {
            parsed = InitializrArgs.parse(args);
        } catch (IllegalArgumentException badArgs) {
            err.println(DiagnosticId.INITIALIZR_ARGUMENTS_INVALID.format(badArgs.getMessage()));
            printUsage(err);
            return EXIT_USAGE;
        }
        return switch (parsed.action()) {
            case HELP -> {
                printUsage(out);
                yield EXIT_OK;
            }
            case VERSION -> {
                out.println("exeris-initializr " + ScaffoldVersions.bundled().tooling());
                yield EXIT_OK;
            }
            case GENERATE -> generate(parsed, out, err);
        };
    }

    private static int generate(InitializrArgs args, PrintStream out, PrintStream err) {
        ProjectScaffoldRequest request = args.request();
        Path target = args.zip() != null ? args.zip() : args.outputDir();
        try {
            ProjectTree tree = ProjectGenerator.generate(request, ScaffoldVersions.bundled());
            if (args.zip() != null) {
                ProjectWriter.writeArchive(tree, request.artifactId(), args.zip(), args.force());
            } else {
                ProjectWriter.writeDirectory(tree, args.outputDir(), args.force());
            }
            out.println("Wrote the " + request.topology().cliValue() + " project " + request.artifactId()
                    + " (" + tree.files().size() + " files) to " + target.toAbsolutePath().normalize());
            return EXIT_OK;
        } catch (TargetExistsException refused) {
            err.println(DiagnosticId.INITIALIZR_TARGET_NOT_EMPTY.format(refused.getMessage()
                    + "; nothing was written. Pass --force to write over it."));
            return EXIT_FAILED;
        } catch (IOException | RuntimeException e) {
            err.println(DiagnosticId.INITIALIZR_WRITE_FAILED.format("Writing " + target + " failed: " + e));
            return EXIT_FAILED;
        }
    }

    private static void printUsage(PrintStream stream) {
        stream.println("Usage: java -jar exeris-initializr-<version>.jar [options]");
        stream.println("Writes a new Exeris project built on eu.exeris:exeris-app-parent and exeris-app-starter.");
        stream.println("Options, each written --switch=value:");
        stream.println("  --topology=<t>        backend-only (default), or e2e (backend/ and frontend/)");
        stream.println("  --group-id=<id>       Maven groupId (default: com.example)");
        stream.println("  --artifact-id=<id>    Maven artifactId and npm package name (default: demo)");
        stream.println("  --package=<pkg>       Base package (default: derived from the groupId and artifactId)");
        stream.println("  --name=<text>         Display name (default: the artifactId)");
        stream.println("  --database=<db>       postgresql (default; with a docker-compose.yml), or h2, which");
        stream.println("                        builds and runs the generated tests but cannot run the application");
        stream.println("  --entity=<Name>       The first @ExerisDomain entity (default: Item)");
        stream.println("  --output-dir=<dir>    Directory to write (default: ./<artifactId>)");
        stream.println("  --zip=<file>          Write a ZIP archive instead of a directory");
        stream.println("  --force               Write into a non-empty directory, or over an existing archive");
        stream.println("  --version             Print the version and exit");
        stream.println("  --help                Print this text and exit");
        stream.println("Exit status: 0 success, 1 write refused or failed, 2 invalid arguments.");
    }
}
