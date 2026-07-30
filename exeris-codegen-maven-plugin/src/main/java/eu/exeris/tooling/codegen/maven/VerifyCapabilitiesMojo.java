package eu.exeris.tooling.codegen.maven;

import eu.exeris.tooling.codegen.core.capability.CapTierWallException;
import eu.exeris.tooling.codegen.core.capability.CapabilityGraphException;
import eu.exeris.tooling.codegen.java.CodegenPipeline;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * {@code exeris:verify-capabilities} — the authoritative capability-graph gate
 * (T18(a)), validated against <b>fresh</b> processor output.
 *
 * <p>Bound to {@code process-classes}, i.e. immediately <em>after</em> the
 * {@code compile} phase in which the annotation processor (re)emits
 * {@code capability_*.json}. Contrast with {@code exeris:generate} at
 * {@code generate-sources}, where the capability metadata on disk is by
 * construction the <em>previous</em> build's output: hard-failing there
 * deadlocks the build on its own stale input (a {@code @Requires} fix can
 * never take effect because the build dies before the processor refreshes the
 * metadata). This goal closes that loop — when it is bound,
 * {@code exeris:generate} degrades a capability-graph failure to a WARNING and
 * this gate delivers the fail-closed verdict on the metadata emitted
 * <em>this</em> build.
 *
 * <p>Validation only — nothing is written. The {@code cap-manifest.json}
 * emission stays in {@code exeris:generate}; after a deferred failure it is
 * refreshed by the next successful generate pass (the same freshness contract
 * the domain two-pass has always had). A run that finds no capability metadata
 * logs and passes — there is nothing to validate.
 *
 * @since 0.6.0
 */
@Mojo(name = VerifyCapabilitiesMojo.GOAL, defaultPhase = LifecyclePhase.PROCESS_CLASSES, threadSafe = true)
public class VerifyCapabilitiesMojo extends AbstractMojo {

    /** Goal name — referenced by {@link GenerateMojo} to detect that this gate is bound. */
    static final String GOAL = "verify-capabilities";

    /** Directory holding processor-emitted {@code capability_*.json} metadata. */
    @Parameter(property = "exeris.metadataDir",
            defaultValue = "${project.build.outputDirectory}/exeris-metadata")
    File metadataDir;

    /**
     * Compiled-output root scanned by the cap-tier Wall guard (ADR-055). Declared
     * explicitly rather than derived from {@link #metadataDir}'s parent: the metadata
     * directory is user-overridable, so deriving one from the other would silently point
     * the scan somewhere else the moment someone relocates the metadata.
     */
    @Parameter(property = "exeris.classesDir", defaultValue = "${project.build.outputDirectory}")
    File classesDir;

    /** Skip the whole codegen pipeline, this gate included. */
    @Parameter(property = "exeris.codegen.skip", defaultValue = "false")
    boolean skip;

    /**
     * Opt out of the cap-tier Wall guard alone, keeping graph validation. An escape hatch
     * for a cap that must breach the Wall knowingly during migration — ADR-024 obligation 3
     * makes disabling it a registry violation surfaced by audit, so it is deliberately loud
     * (a WARNING every build) and deliberately separate from {@code exeris.codegen.skip}.
     */
    @Parameter(property = "exeris.wall.skip", defaultValue = "false")
    boolean skipWall;

    /**
     * Validator seam — a functional indirection over
     * {@link CodegenPipeline#validateCapabilities(Path)} so the mojo's control
     * flow (skip / error wrapping) is unit-testable without loading real
     * metadata ({@code CodegenPipeline} is {@code final}).
     */
    @FunctionalInterface
    interface CapabilityValidator {
        int validate(Path metadataDir) throws IOException;
    }

    /**
     * Wall-guard seam, mirroring {@link CapabilityValidator} — same reason: keep the mojo's
     * control flow (skip toggles, error wrapping) unit-testable without compiled fixtures.
     */
    @FunctionalInterface
    interface WallVerifier {
        int verify(Path classesDir, Path metadataDir) throws IOException;
    }

    CapabilityValidator validator = CodegenPipeline.createDefault()::validateCapabilities;

    WallVerifier wallVerifier = CodegenPipeline.createDefault()::verifyCapTierWall;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("exeris:verify-capabilities skipped (exeris.codegen.skip=true)");
            return;
        }

        try {
            int modules = validator.validate(metadataDir.toPath());
            if (modules == 0) {
                getLog().info("No capability metadata under " + metadataDir + " — nothing to verify");
            } else {
                getLog().info("Capability graph valid (" + modules + " module(s), fresh metadata)");
            }
            verifyWall();
        } catch (CapabilityGraphException e) {
            // A user-side composition error on FRESH metadata (unsatisfied
            // @Requires / version mismatch / cycle) — the genuine, actionable
            // verdict the generate-sources pass deferred to. A build FAILURE,
            // not a plugin bug.
            throw new MojoFailureException(e.getMessage(), e);
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Capability verification failed (metadataDir=" + metadataDir + ")", e);
        }
    }

    /**
     * ADR-024 predicate 4. Runs after graph validation so the cheaper, more common failure
     * (an unsatisfied {@code @Requires}) is reported first; a cap whose graph is broken has
     * a more urgent problem than its import hygiene.
     */
    private void verifyWall() throws MojoExecutionException, MojoFailureException, IOException {
        if (skipWall) {
            getLog().warn("Cap-tier Wall guard DISABLED (exeris.wall.skip=true) — "
                    + "ADR-024 obligation 3 treats this as a registry violation");
            return;
        }
        try {
            int gated = wallVerifier.verify(classesDir.toPath(), metadataDir.toPath());
            if (gated > 0) {
                getLog().info("Cap-tier Wall clean (" + gated + " module(s), " + classesDir + ")");
            }
        } catch (CapTierWallException e) {
            // A cap author's boundary breach — user-side and actionable, so a FAILURE.
            throw new MojoFailureException(e.getMessage(), e);
        } catch (UncheckedIOException e) {
            // An unreadable/corrupt class file is an environment problem, not a Wall verdict.
            throw new MojoExecutionException(
                    "Cap-tier Wall scan failed (classesDir=" + classesDir + ")", e);
        }
    }
}
