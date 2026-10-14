package eu.exeris.tooling.initializr.cli;

import eu.exeris.tooling.initializr.model.DatabaseType;
import eu.exeris.tooling.initializr.model.ProjectScaffoldRequest;
import eu.exeris.tooling.initializr.model.ProjectTopology;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Parsed command-line arguments of {@link InitializrCliMain}.
 *
 * <p>Every argument is one of the switches below, a value switch written {@code --switch=value}.
 * An unknown switch, a positional argument, a switch given twice, a value switch without its value,
 * a flag with one, and a value the request does not accept are argument errors, so a run never
 * goes ahead with an option silently dropped.
 *
 * <pre>
 * --topology=backend-only|e2e                default backend-only
 * --group-id=&lt;groupId&gt;                        default com.example
 * --artifact-id=&lt;artifactId&gt;                  default demo
 * --package=&lt;package&gt;                         default derived from the groupId and the artifactId
 * --name=&lt;text&gt;                               default the artifactId
 * --database=postgresql|h2                    default postgresql
 * --entity=&lt;TypeName&gt;                         default Item
 * --output-dir=&lt;dir&gt;                          default ./&lt;artifactId&gt;
 * --zip=&lt;file&gt;                                write a ZIP archive instead of a directory
 * --force                                     write into a non-empty directory or over an archive
 * --version                                   print the version and exit
 * --help                                      print the usage and exit
 * </pre>
 *
 * @param action    what the run does
 * @param request   the project to write; {@code null} unless {@code action} is
 *                  {@link Action#GENERATE}
 * @param outputDir the directory to write the project into; {@code null} when {@code zip} is set
 *                  or {@code action} is not {@link Action#GENERATE}
 * @param zip       the archive to write instead of a directory, or {@code null}
 * @param force     whether to write into a non-empty directory or over an existing archive
 * @since 0.10
 */
public record InitializrArgs(Action action, ProjectScaffoldRequest request, Path outputDir, Path zip,
                             boolean force) {

    private static final String TOPOLOGY = "--topology";
    private static final String GROUP_ID = "--group-id";
    private static final String ARTIFACT_ID = "--artifact-id";
    private static final String PACKAGE = "--package";
    private static final String NAME = "--name";
    private static final String DATABASE = "--database";
    private static final String ENTITY = "--entity";
    private static final String OUTPUT_DIR = "--output-dir";
    private static final String ZIP_FLAG = "--zip";
    private static final String FORCE_FLAG = "--force";
    private static final String VERSION = "--version";
    private static final String HELP = "--help";

    private static final Set<String> VALUE_SWITCHES =
            Set.of(TOPOLOGY, GROUP_ID, ARTIFACT_ID, PACKAGE, NAME, DATABASE, ENTITY, OUTPUT_DIR, ZIP_FLAG);
    private static final Set<String> FLAGS = Set.of(FORCE_FLAG, VERSION, HELP);

    /**
     * What a run of the command line does.
     *
     * @since 0.10
     */
    public enum Action {
        /** Writes the project the request describes. */
        GENERATE,
        /** Prints the initializr's version. */
        VERSION,
        /** Prints the usage text. */
        HELP
    }

    /**
     * Parses the command line.
     *
     * @param args the arguments, as passed to {@code main}
     * @return the parsed arguments
     * @throws IllegalArgumentException with a message naming the offending argument when an
     *                                  argument is not one of the switches, is given twice or
     *                                  without its value, or holds a value the request does not
     *                                  accept; the command line turns it into exit status 2
     * @since 0.10
     */
    public static InitializrArgs parse(String[] args) {
        Map<String, String> values = new HashMap<>();
        for (String arg : args) {
            record(values, arg);
        }
        if (values.containsKey(HELP)) {
            return new InitializrArgs(Action.HELP, null, null, null, false);
        }
        if (values.containsKey(VERSION)) {
            return new InitializrArgs(Action.VERSION, null, null, null, false);
        }
        if (values.containsKey(OUTPUT_DIR) && values.containsKey(ZIP_FLAG)) {
            throw new IllegalArgumentException(OUTPUT_DIR + " and " + ZIP_FLAG + " are alternatives; pass one");
        }

        ProjectScaffoldRequest request = new ProjectScaffoldRequest(
                values.getOrDefault(GROUP_ID, "com.example"),
                values.getOrDefault(ARTIFACT_ID, "demo"),
                values.get(PACKAGE),
                values.get(NAME),
                topology(values),
                database(values),
                values.get(ENTITY));
        Path zip = values.containsKey(ZIP_FLAG) ? Path.of(values.get(ZIP_FLAG)) : null;
        Path outputDir = zip != null ? null : Path.of(values.getOrDefault(OUTPUT_DIR, request.artifactId()));
        return new InitializrArgs(Action.GENERATE, request, outputDir, zip, values.containsKey(FORCE_FLAG));
    }

    private static void record(Map<String, String> values, String arg) {
        int eq = arg.indexOf('=');
        String name = eq < 0 ? arg : arg.substring(0, eq);
        String value = eq < 0 ? null : arg.substring(eq + 1);
        requireKnownShape(arg, name, value);
        if (values.putIfAbsent(name, value == null ? "" : value) != null) {
            throw new IllegalArgumentException(name + " is given more than once");
        }
    }

    private static void requireKnownShape(String arg, String name, String value) {
        if (VALUE_SWITCHES.contains(name)) {
            if (value == null || value.isEmpty()) {
                throw new IllegalArgumentException(name + " takes a value: " + name + "=<value>");
            }
        } else if (FLAGS.contains(name)) {
            if (value != null) {
                throw new IllegalArgumentException(name + " takes no value");
            }
        } else {
            throw new IllegalArgumentException(arg.startsWith("--")
                    ? "Unknown switch: " + name
                    : "Unexpected argument: " + arg + " (every argument is a --switch)");
        }
    }

    private static ProjectTopology topology(Map<String, String> values) {
        String given = values.get(TOPOLOGY);
        if (given == null) {
            return ProjectTopology.BACKEND_ONLY;
        }
        return ProjectTopology.fromCliValue(given).orElseThrow(() ->
                new IllegalArgumentException(TOPOLOGY + " is one of backend-only, e2e: " + given));
    }

    private static DatabaseType database(Map<String, String> values) {
        String given = values.get(DATABASE);
        if (given == null) {
            return DatabaseType.POSTGRESQL;
        }
        return DatabaseType.fromCliValue(given).orElseThrow(() ->
                new IllegalArgumentException(DATABASE + " is one of postgresql, h2: " + given));
    }
}
