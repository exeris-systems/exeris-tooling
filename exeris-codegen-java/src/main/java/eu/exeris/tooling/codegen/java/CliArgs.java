package eu.exeris.tooling.codegen.java;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Parsed command-line arguments for {@link CodegenMain} (ADR-097 obligation 11).
 *
 * <p>Extracted from the CLI shell so the parse step is exercisable from unit
 * tests independently of {@code System.exit} / logging side effects.
 *
 * <p>Every argument must be one of the switches below. An unknown {@code --} switch, a
 * positional argument, a value switch given without {@code =<value>} and a flag given with
 * one are argument errors: a switch a given release does not know fails the run, so a script
 * never runs with an option silently dropped.
 *
 * <pre>
 * --metadata-dir=&lt;path&gt;       required, unless --print-catalogue
 * --output-dir=&lt;path&gt;         required, unless --print-catalogue
 * --base-package=&lt;pkg&gt;        optional; absent means the first domain by qualified name
 * --tests                      also write the generated tests (ADR-058)
 * --test-output-dir=&lt;path&gt;    the generated-test root; required by --tests, read only with it
 * --allow-empty                permit the teardown of an owned tree when no @ExerisDomain is found
 * --print-catalogue            write the bundled generator catalogue to stdout; needs no other switch
 * </pre>
 *
 * @param metadataDir    path to the {@code exeris-metadata} JSON directory; {@code null} only
 *                       when {@code printCatalogue}
 * @param outputDir      path the generated main sources are written to; {@code null} only when
 *                       {@code printCatalogue}
 * @param basePackage    base package for application classes; {@code null} means auto-detect
 *                       from the first domain by qualified name
 * @param tests          whether the generated tests are written too
 * @param testOutputDir  root the generated tests are written to; non-{@code null} exactly when
 *                       {@code tests}
 * @param allowEmpty     whether a run that loads no {@code @ExerisDomain} may prune a tree a
 *                       previous run owns
 * @param printCatalogue whether the run prints the generator catalogue instead of generating
 */
public record CliArgs(Path metadataDir, Path outputDir, String basePackage, boolean tests,
                      Path testOutputDir, boolean allowEmpty, boolean printCatalogue) {

    private static final String METADATA_DIR = "--metadata-dir";
    private static final String OUTPUT_DIR = "--output-dir";
    private static final String BASE_PACKAGE = "--base-package";
    private static final String TEST_OUTPUT_DIR = "--test-output-dir";
    private static final String TESTS = "--tests";
    private static final String ALLOW_EMPTY = "--allow-empty";
    private static final String PRINT_CATALOGUE = "--print-catalogue";

    /**
     * Creates the parsed arguments.
     *
     * @throws NullPointerException     if {@code metadataDir} or {@code outputDir} is {@code null}
     *                                  and {@code printCatalogue} is not set, or if {@code tests}
     *                                  is set and {@code testOutputDir} is {@code null}
     * @throws IllegalArgumentException if {@code testOutputDir} is set without {@code tests}
     */
    public CliArgs {
        if (!printCatalogue) {
            Objects.requireNonNull(metadataDir, "metadataDir");
            Objects.requireNonNull(outputDir, "outputDir");
        }
        if (tests) {
            Objects.requireNonNull(testOutputDir, "testOutputDir");
        } else if (testOutputDir != null) {
            throw new IllegalArgumentException("testOutputDir is set without tests");
        }
    }

    /**
     * A generation run over the main root only, with the empty-metadata guard on.
     *
     * @param metadataDir path to the {@code exeris-metadata} JSON directory
     * @param outputDir   path the generated main sources are written to
     * @param basePackage base package for application classes, or {@code null} to auto-detect
     */
    public CliArgs(Path metadataDir, Path outputDir, String basePackage) {
        this(metadataDir, outputDir, basePackage, false, null, false, false);
    }

    /**
     * Parses {@code args}.
     *
     * @param args the command-line arguments, as passed to {@code main}
     * @return the parsed arguments
     * @throws IllegalArgumentException with a message naming the offending argument when an
     *         argument is not one of the switches, a required switch is missing, or
     *         {@code --tests} and {@code --test-output-dir} are not given together. The CLI
     *         shell turns it into a usage hint and exit status 2.
     */
    public static CliArgs parse(String[] args) {
        Path metadataDir = null;
        Path outputDir = null;
        String basePackage = null;
        Path testOutputDir = null;
        boolean tests = false;
        boolean allowEmpty = false;
        boolean printCatalogue = false;

        for (String arg : args) {
            int eq = arg.indexOf('=');
            String name = eq < 0 ? arg : arg.substring(0, eq);
            String value = eq < 0 ? null : arg.substring(eq + 1);
            switch (name) {
                case METADATA_DIR -> metadataDir = Path.of(requireValue(name, value));
                case OUTPUT_DIR -> outputDir = Path.of(requireValue(name, value));
                case BASE_PACKAGE -> basePackage = requireValue(name, value);
                case TEST_OUTPUT_DIR -> testOutputDir = Path.of(requireValue(name, value));
                case TESTS -> tests = flag(name, value);
                case ALLOW_EMPTY -> allowEmpty = flag(name, value);
                case PRINT_CATALOGUE -> printCatalogue = flag(name, value);
                default -> throw new IllegalArgumentException(arg.startsWith("--")
                        ? "Unknown switch: " + name
                        : "Unexpected argument: " + arg + " (every argument is a --switch)");
            }
        }

        if (tests && testOutputDir == null) {
            throw new IllegalArgumentException(TESTS + " requires " + TEST_OUTPUT_DIR + "=<path>");
        }
        if (!tests && testOutputDir != null) {
            throw new IllegalArgumentException(TEST_OUTPUT_DIR + " is read only with " + TESTS);
        }
        if (!printCatalogue) {
            if (metadataDir == null) {
                throw new IllegalArgumentException("Missing required argument: " + METADATA_DIR + "=<path>");
            }
            if (outputDir == null) {
                throw new IllegalArgumentException("Missing required argument: " + OUTPUT_DIR + "=<path>");
            }
        }

        return new CliArgs(metadataDir, outputDir, basePackage, tests, testOutputDir, allowEmpty,
                printCatalogue);
    }

    private static String requireValue(String name, String value) {
        if (value == null) {
            throw new IllegalArgumentException(name + " takes a value: " + name + "=<value>");
        }
        return value;
    }

    private static boolean flag(String name, String value) {
        if (value != null) {
            throw new IllegalArgumentException(name + " takes no value");
        }
        return true;
    }
}
