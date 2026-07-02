package eu.exeris.tooling.codegen.maven;

import eu.exeris.tooling.codegen.core.capability.CapabilityGraphException;
import eu.exeris.tooling.codegen.java.CodegenPipeline;
import eu.exeris.tooling.codegen.java.EmptyMetadataException;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * {@code exeris:generate} — runs the kernel-target codegen pipeline against the
 * processor-emitted {@code DomainMetadata} and writes sources into
 * {@code src/main/generated/java} (L1).
 *
 * <p>Bound to {@code generate-sources}. The annotation processor emits
 * {@code DomainMetadata} during {@code compile}, which runs <em>after</em>
 * {@code generate-sources} — so first use is a <b>two-pass build</b>: run
 * {@code mvn compile} once so the processor writes
 * {@code target/classes/exeris-metadata/*.json}, after which every subsequent
 * {@code mvn compile} picks that metadata up and generates sources. (In the
 * steady state the generated tree is committed under {@code src/main/generated/},
 * per the L1/L2 detachment story, so the compile source root carries content
 * even on a from-scratch checkout.) A run that finds no metadata logs a warning
 * and writes nothing — it is not an error.
 *
 * <p><b>Capability validation (T18(a)).</b> The same two-pass reality means the
 * {@code capability_*.json} this goal reads is always the <em>previous</em>
 * build's output. Historically a capability-graph failure hard-failed here —
 * before the processor could refresh the metadata — so a {@code @Requires} fix
 * (e.g. adding {@code optional=true}) could never take effect: the build died
 * on the stale file it was about to replace. When this project also binds the
 * {@code verify-capabilities} goal of this plugin (the authoritative
 * post-{@code compile} gate) at its default {@code process-classes} phase or
 * later, a graph failure here degrades to a WARNING and the fail-closed
 * verdict is delivered by that gate against the metadata the processor emits
 * <em>this</em> build. Without that gate bound — or with it explicitly rebound
 * to a phase before {@code process-classes}, where it would itself see stale
 * input — the historical hard-fail at {@code generate-sources} is kept
 * (fail-closed, deadlock and all — bind the gate to escape it).
 *
 * <p><b>Safe-build recipe</b> (L1-committed output). Bind both goals:
 * <pre>{@code
 * <execution>
 *   <goals>
 *     <goal>generate</goal>            <!-- generate-sources -->
 *     <goal>verify-capabilities</goal> <!-- process-classes: fresh-metadata gate -->
 *   </goals>
 * </execution>
 * }</pre>
 * and never {@code mvn clean compile} in one shot on a metadata-less tree —
 * seed the metadata first with {@code mvn compile -Dexeris.codegen.skip=true},
 * then build normally (the T18(b) guard refuses the wipe otherwise).
 *
 * <p>This mojo is a thin Maven shell: all emission lives in
 * {@link CodegenPipeline} (in {@code exeris-codegen-java}). The
 * processor↔generator contract is unchanged — the plugin only chooses where to
 * read metadata and where to write sources.
 *
 * @since 0.3.0
 */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class GenerateMojo extends AbstractMojo {

    /** Directory holding processor-emitted {@code *.json} {@code DomainMetadata}. */
    @Parameter(property = "exeris.metadataDir",
            defaultValue = "${project.build.outputDirectory}/exeris-metadata")
    File metadataDir;

    /** Target directory for generated Java sources (L1). */
    @Parameter(property = "exeris.outputDir",
            defaultValue = "${project.basedir}/src/main/generated/java")
    File outputDir;

    /** Base package for the application-bootstrap classes; auto-detected from the
     *  first domain's package when unset. */
    @Parameter(property = "exeris.basePackage")
    String basePackage;

    /** Skip code generation entirely. */
    @Parameter(property = "exeris.codegen.skip", defaultValue = "false")
    boolean skip;

    /** Permit a run that loads zero {@code @ExerisDomain} entities to prune a
     *  previously-generated tree (the explicit "I removed every entity" teardown).
     *  Default {@code false} refuses that prune (T18): empty metadata is almost
     *  always a masked compile failure, and pruning on it silently wipes the
     *  committed generated tree. */
    @Parameter(property = "exeris.codegen.allowEmpty", defaultValue = "false")
    boolean allowEmpty;

    /** Register {@link #outputDir} as a compile source root so generated code
     *  compiles in the same build. Disable when the output is consumed elsewhere. */
    @Parameter(property = "exeris.addCompileSourceRoot", defaultValue = "true")
    boolean addCompileSourceRoot;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    /** This plugin's own descriptor — identifies "this plugin" when scanning the
     *  project's build plugins for a bound {@code verify-capabilities} execution. */
    @Parameter(defaultValue = "${plugin}", readonly = true, required = true)
    PluginDescriptor pluginDescriptor;

    /**
     * Pipeline seam — a functional indirection over {@link CodegenPipeline} so
     * the mojo's control flow (skip / source-root / error wrapping) is
     * unit-testable without running the real generators ({@code CodegenPipeline}
     * is {@code final}). The {@code int} return of {@code run} is discarded.
     */
    @FunctionalInterface
    interface PipelineRunner {
        void run(Path metadataDir, Path outputDir, String basePackage, boolean allowEmpty,
                 boolean deferCapabilityFailure) throws IOException;
    }

    PipelineRunner pipeline = CodegenPipeline.createDefault()::run;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("exeris:generate skipped (exeris.codegen.skip=true)");
            return;
        }

        // T18(a): defer a capability-graph failure to the post-compile gate ONLY
        // when that gate is provably bound in this project — leniency on the
        // stale generate-sources input is sound exactly when the fresh-input
        // hard-fail is guaranteed to run later in the same build.
        boolean deferCapabilityFailure = verifyCapabilitiesBound();
        if (deferCapabilityFailure) {
            getLog().debug("verify-capabilities is bound — a capability-graph failure at "
                    + "generate-sources defers to the post-compile fresh-metadata gate");
        }

        try {
            getLog().info("Generating kernel-target sources: metadata="
                    + metadataDir + " → output=" + outputDir);
            pipeline.run(metadataDir.toPath(), outputDir.toPath(), basePackage, allowEmpty,
                    deferCapabilityFailure);
        } catch (CapabilityGraphException | EmptyMetadataException e) {
            // A user-side condition (unsatisfied @Requires / version mismatch /
            // cycle; or empty metadata from a masked compile failure, T18) — not a
            // plugin bug. Surface the actionable message as a build FAILURE, not an
            // "unexpected error".
            throw new MojoFailureException(e.getMessage(), e);
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Code generation failed (metadataDir=" + metadataDir + ")", e);
        }

        // Registered unconditionally (not gated on a generated-file count): the
        // committed generated tree from prior runs must be on the compile path
        // even when THIS run wrote nothing (no new metadata, or steady-state
        // regen of unchanged sources). An absent/empty root is harmless to Maven.
        if (addCompileSourceRoot && project != null) {
            project.addCompileSourceRoot(outputDir.getAbsolutePath());
            getLog().debug("Registered compile source root: " + outputDir);
        }
    }

    /**
     * Default-lifecycle phases from {@code process-classes} onwards — the phases
     * at which a bound {@code verify-capabilities} execution is guaranteed to see
     * the metadata the processor (re)emits during {@code compile}. An execution
     * explicitly rebound to an earlier phase (or to an unknown/custom phase)
     * would itself validate stale input, so it does NOT count as the gate.
     */
    private static final java.util.Set<String> PHASES_AFTER_COMPILE = java.util.Set.of(
            "process-classes",
            "generate-test-sources", "process-test-sources",
            "generate-test-resources", "process-test-resources",
            "test-compile", "process-test-classes", "test",
            "prepare-package", "package",
            "pre-integration-test", "integration-test", "post-integration-test",
            "verify", "install", "deploy");

    /**
     * Whether this project's effective build binds the
     * {@code verify-capabilities} goal of <em>this</em> plugin at a phase that
     * runs <em>after</em> the {@code compile} phase in which the processor
     * refreshes {@code capability_*.json} — i.e. whether the fresh-input
     * hard-fail is genuinely guaranteed later in this build. An execution with
     * no explicit {@code <phase>} counts (the goal's default is
     * {@code process-classes}); an execution explicitly rebound earlier than
     * {@code process-classes}, to {@code none}, or to an unknown phase does not.
     * Conservative on any missing collaborator: no detectable gate → strict
     * validation.
     */
    boolean verifyCapabilitiesBound() {
        if (project == null || pluginDescriptor == null) {
            return false;
        }
        for (Plugin plugin : project.getBuildPlugins()) {
            if (!pluginDescriptor.getGroupId().equals(plugin.getGroupId())
                    || !pluginDescriptor.getArtifactId().equals(plugin.getArtifactId())) {
                continue;
            }
            for (PluginExecution execution : plugin.getExecutions()) {
                if (!execution.getGoals().contains(VerifyCapabilitiesMojo.GOAL)) {
                    continue;
                }
                String phase = execution.getPhase();
                if (phase == null || PHASES_AFTER_COMPILE.contains(phase)) {
                    return true;
                }
            }
        }
        return false;
    }
}
