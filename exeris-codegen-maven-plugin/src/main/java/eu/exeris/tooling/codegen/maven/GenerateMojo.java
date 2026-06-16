package eu.exeris.tooling.codegen.maven;

import eu.exeris.tooling.codegen.core.capability.CapabilityGraphException;
import eu.exeris.tooling.codegen.java.CodegenPipeline;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
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

    /** Register {@link #outputDir} as a compile source root so generated code
     *  compiles in the same build. Disable when the output is consumed elsewhere. */
    @Parameter(property = "exeris.addCompileSourceRoot", defaultValue = "true")
    boolean addCompileSourceRoot;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    /**
     * Pipeline seam — a functional indirection over {@link CodegenPipeline} so
     * the mojo's control flow (skip / source-root / error wrapping) is
     * unit-testable without running the real generators ({@code CodegenPipeline}
     * is {@code final}). The {@code int} return of {@code run} is discarded.
     */
    @FunctionalInterface
    interface PipelineRunner {
        void run(Path metadataDir, Path outputDir, String basePackage) throws IOException;
    }

    PipelineRunner pipeline = CodegenPipeline.createDefault()::run;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("exeris:generate skipped (exeris.codegen.skip=true)");
            return;
        }

        try {
            getLog().info("Generating kernel-target sources: metadata="
                    + metadataDir + " → output=" + outputDir);
            pipeline.run(metadataDir.toPath(), outputDir.toPath(), basePackage);
        } catch (CapabilityGraphException e) {
            // A user-side composition error (unsatisfied @Requires / version
            // mismatch / cycle), not a plugin bug — surface the actionable
            // message as a build FAILURE, not an "unexpected error".
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
}
