package eu.exeris.tooling.codegen.java;

import java.nio.file.Path;

/**
 * Thrown by {@link CodegenPipeline} when a generate run finds <b>zero</b>
 * {@code @ExerisDomain} entities but a previous run owns a committed generated
 * tree the orphan-prune (T13) would now delete (T18 guard).
 *
 * <p>Empty metadata is overwhelmingly a <em>masked compile failure</em> — the
 * annotation processor never ran (or ran on a broken source set), so it emitted
 * no {@code exeris-metadata/*.json} — not an intentional teardown. Pruning on
 * that signal silently wipes the committed {@code src/main/generated} tree (the
 * data-loss footgun this exception prevents; recovered only via {@code git
 * restore}). The pipeline refuses and surfaces this as an actionable build
 * failure; the genuine "I removed every {@code @ExerisDomain}" teardown opts in
 * via {@code -Dexeris.codegen.allowEmpty=true}.
 *
 * <p>The safe recipe the message recommends is a metadata-only pass that lets
 * the processor emit before generation runs:
 * {@code mvn compile -Dexeris.codegen.skip=true} then {@code exeris:generate}.
 *
 * @since 0.6.0
 */
public final class EmptyMetadataException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Not {@code transient}: this diagnostic count must survive serialization
     *  (a {@code transient} primitive would deserialize back to {@code 0}). */
    private final int orphanCount;

    public EmptyMetadataException(int orphanCount, Path metadataDir, Path outputDir) {
        super(buildMessage(orphanCount, metadataDir, outputDir));
        this.orphanCount = orphanCount;
    }

    /** The number of committed generated files the refused prune would have deleted. */
    public int orphanCount() {
        return orphanCount;
    }

    private static String buildMessage(int orphanCount, Path metadataDir, Path outputDir) {
        return "Refusing to wipe the committed generated tree: 0 @ExerisDomain entities were loaded from "
                + metadataDir + ", but the previous run owns " + orphanCount + " generated file(s) under "
                + outputDir + " that exeris:generate would now DELETE. Empty metadata is almost always a "
                + "masked compile failure (the annotation processor did not run), not an intentional teardown. "
                + "Verify the project compiles first — the safe recipe is a metadata-only pass:\n"
                + "  mvn compile -Dexeris.codegen.skip=true\n"
                + "then re-run exeris:generate. If you genuinely removed every @ExerisDomain type and want the "
                + "generated tree pruned, re-run with -Dexeris.codegen.allowEmpty=true.";
    }
}
