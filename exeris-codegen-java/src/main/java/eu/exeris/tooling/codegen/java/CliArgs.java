package eu.exeris.tooling.codegen.java;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Parsed CLI arguments for {@link CodegenMain}.
 *
 * <p>Extracted from the CLI shell so the parse step is exercisable from unit
 * tests independently of {@code System.exit} / logging side effects.
 *
 * <p>Unknown {@code --foo=bar} switches are silently ignored (forward
 * compatibility with future flags). Positional args are also ignored.
 *
 * @param metadataDir  required — path to the {@code exeris-metadata} JSON dir
 * @param outputDir    required — path to write generated sources into
 * @param basePackage  optional — base package for application classes;
 *                     {@code null} means auto-detect from the first domain
 */
public record CliArgs(Path metadataDir, Path outputDir, String basePackage) {

    public CliArgs {
        Objects.requireNonNull(metadataDir, "metadataDir");
        Objects.requireNonNull(outputDir, "outputDir");
    }

    /**
     * Parses {@code --metadata-dir=}, {@code --output-dir=}, and the optional
     * {@code --base-package=} switches. Throws {@link IllegalArgumentException}
     * with a message naming the missing switch when either required arg is
     * absent — the CLI shell turns that into a usage hint + non-zero exit.
     */
    public static CliArgs parse(String[] args) {
        Path metadataDir = null;
        Path outputDir = null;
        String basePackage = null;

        for (String arg : args) {
            if (arg.startsWith("--metadata-dir=")) {
                metadataDir = Path.of(arg.substring("--metadata-dir=".length()));
            } else if (arg.startsWith("--output-dir=")) {
                outputDir = Path.of(arg.substring("--output-dir=".length()));
            } else if (arg.startsWith("--base-package=")) {
                basePackage = arg.substring("--base-package=".length());
            }
        }

        if (metadataDir == null) {
            throw new IllegalArgumentException("Missing required argument: --metadata-dir=<path>");
        }
        if (outputDir == null) {
            throw new IllegalArgumentException("Missing required argument: --output-dir=<path>");
        }

        return new CliArgs(metadataDir, outputDir, basePackage);
    }
}
