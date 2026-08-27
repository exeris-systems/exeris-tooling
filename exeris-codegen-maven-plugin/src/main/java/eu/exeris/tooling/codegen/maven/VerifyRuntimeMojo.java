package eu.exeris.tooling.codegen.maven;

import eu.exeris.tooling.codegen.core.driver.RequiredDrivers;
import eu.exeris.tooling.codegen.core.driver.RuntimeDriverCheck;
import eu.exeris.tooling.codegen.java.CodegenPipeline;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code exeris:verify-runtime} — fails the build when the emitted application has no kernel
 * driver to run on (T50, ADR-078).
 *
 * <p>{@code Application.main()} boots subsystems <em>by name</em>, and every provider behind
 * those names arrives from a runtime driver artefact. Tooling emits no {@code pom.xml}, so
 * nothing in the consumer's build declares that dependency and nothing checked it — the first
 * report was a bootstrap error at start-up naming a subsystem, which is the wrong noun: the
 * missing thing is a jar.
 *
 * <p>Bound to {@code process-classes}, like {@code exeris:verify-capabilities} and for the same
 * reason: the metadata this reads is the output of the annotation processor that ran in
 * {@code compile}, so at {@code generate-sources} it would be the previous build's.
 *
 * <p>The check is a {@code META-INF/services} resource scan over the resolved runtime
 * classpath. It loads no consumer class and starts nothing; see {@link RuntimeDriverCheck} for
 * what a pass does and does not prove, and {@link RequiredDrivers} for why each required SPI
 * is derived from an emitted artefact rather than from the emitted subsystem name list.
 *
 * @since 0.8.0
 */
@Mojo(name = VerifyRuntimeMojo.GOAL,
        defaultPhase = LifecyclePhase.PROCESS_CLASSES,
        requiresDependencyResolution = ResolutionScope.RUNTIME,
        threadSafe = true)
public class VerifyRuntimeMojo extends AbstractMojo {

    static final String GOAL = "verify-runtime";

    /** Directory holding processor-emitted domain metadata — the same default the other goals use. */
    @Parameter(property = "exeris.metadataDir",
            defaultValue = "${project.build.outputDirectory}/exeris-metadata")
    File metadataDir;

    /** Skip the whole codegen pipeline, this gate included. */
    @Parameter(property = "exeris.codegen.skip", defaultValue = "false")
    boolean skip;

    /**
     * Opt out of this gate alone. Separate from {@code exeris.codegen.skip} because the
     * legitimate reason to want it is narrow and worth naming: a module that generates code
     * for <em>another</em> module to run, and so has no driver on its own runtime classpath by
     * design. Degrades the verdict to a WARNING rather than removing it, so the build still
     * says what it found.
     */
    @Parameter(property = "exeris.verifyRuntime.skip", defaultValue = "false")
    boolean skipRuntimeCheck;

    /**
     * The resolved runtime classpath, injected by Maven because of
     * {@code requiresDependencyResolution = RUNTIME}. This is the set the application will
     * actually start with — deliberately not the compile classpath, on which a driver may be
     * {@code provided} and absent at run time, nor the test classpath, which can carry one the
     * application will not have.
     */
    @Parameter(defaultValue = "${project.runtimeClasspathElements}", readonly = true, required = true)
    List<String> runtimeClasspathElements;

    /**
     * Driver-check seam, mirroring {@code VerifyCapabilitiesMojo}'s validator and Wall seams —
     * same reason: keep the mojo's control flow (skips, message shape, failure wrapping)
     * unit-testable without writing real processor output to disk ({@code CodegenPipeline} is
     * {@code final}).
     */
    @FunctionalInterface
    interface DriverVerifier {
        RuntimeDriverCheck.Result verify(Path metadataDir, List<Path> runtimeClasspath)
                throws IOException;
    }

    /**
     * Per-mojo, not static — same rationale as the sibling gate: {@code createDefault()} builds
     * a whole registry graph, the entry point carries no state across calls, and parallel module
     * builds keep their own instance to match {@code threadSafe = true}.
     */
    private final CodegenPipeline pipeline = CodegenPipeline.createDefault();

    DriverVerifier verifier = pipeline::verifyRuntimeDrivers;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("exeris:verify-runtime skipped (exeris.codegen.skip=true)");
            return;
        }

        RuntimeDriverCheck.Result result;
        try {
            result = verifier.verify(metadataDir.toPath(), classpath());
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Could not read domain metadata (metadataDir=" + metadataDir + ")", e);
        }

        if (result.vacuous()) {
            getLog().info("No domain metadata under " + metadataDir
                    + " — no emitted application, so no runtime driver to require");
            return;
        }
        if (result.satisfied()) {
            getLog().info("Runtime drivers present for all " + result.required().size()
                    + " required SPI(s) across " + result.scanned() + " classpath element(s)");
            return;
        }

        String message = describe(result);
        if (skipRuntimeCheck) {
            getLog().warn(message);
            getLog().warn("exeris.verifyRuntime.skip=true — reported, not enforced");
            return;
        }
        throw new MojoFailureException(message);
    }

    private List<Path> classpath() {
        List<Path> paths = new ArrayList<>();
        for (String element : runtimeClasspathElements) {
            paths.add(Path.of(element));
        }
        return paths;
    }

    /**
     * The message is the whole point of the goal, so it names the artefact to add rather than
     * the subsystem that would not start, and it says which SPIs are missing rather than only
     * that something is.
     */
    private String describe(RuntimeDriverCheck.Result result) {
        StringBuilder message = new StringBuilder()
                .append("The generated application has no kernel driver on its runtime classpath.")
                .append(System.lineSeparator())
                .append("Missing a registered provider for ")
                .append(result.missing().size()).append(" of ").append(result.required().size())
                .append(" required SPI(s):").append(System.lineSeparator());
        for (String spi : result.missing()) {
            message.append("  - ").append(spi).append(System.lineSeparator());
        }
        return message
                .append("Add a runtime driver, e.g. ").append(RequiredDrivers.suggestedArtifact())
                .append(", to this module's dependencies.").append(System.lineSeparator())
                .append("Without one, Application.main() fails at boot with a subsystem name ")
                .append("rather than a missing dependency (T50).").append(System.lineSeparator())
                .append("Scanned ").append(result.scanned()).append(" runtime classpath element(s). ")
                .append("Set -Dexeris.verifyRuntime.skip=true if this module generates code for ")
                .append("another module to run.")
                .toString();
    }
}
