package eu.exeris.tooling.codegen.maven;

import eu.exeris.tooling.codegen.core.driver.RequiredDrivers;
import eu.exeris.tooling.codegen.core.driver.RuntimeDriverCheck;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("VerifyRuntimeMojo — the missing-driver gate (T50)")
class VerifyRuntimeMojoTest {

    private static final String SUBSYSTEM = RequiredDrivers.SUBSYSTEM_PROVIDER;
    private static final String PERSISTENCE = RequiredDrivers.PERSISTENCE_PROVIDER;

    private static VerifyRuntimeMojo mojo(Path tmp, RuntimeDriverCheck.Result verdict) {
        VerifyRuntimeMojo mojo = new VerifyRuntimeMojo();
        mojo.metadataDir = tmp.resolve("target/classes/exeris-metadata").toFile();
        mojo.runtimeClasspathElements = List.of(tmp.resolve("target/classes").toString());
        mojo.verifier = (m, cp) -> verdict;
        return mojo;
    }

    private static RuntimeDriverCheck.Result missing(String... spis) {
        return new RuntimeDriverCheck.Result(List.of(SUBSYSTEM, PERSISTENCE), List.of(spis), 3);
    }

    @Test
    @DisplayName("a satisfied classpath passes")
    void satisfiedPasses(@TempDir Path tmp) throws Exception {
        mojo(tmp, new RuntimeDriverCheck.Result(List.of(SUBSYSTEM), List.of(), 4)).execute();
    }

    @Test
    @DisplayName("no domain metadata is a pass, not a failure — nothing was emitted to run")
    void vacuousVerdictPasses(@TempDir Path tmp) throws Exception {
        mojo(tmp, new RuntimeDriverCheck.Result(List.of(), List.of(), 0)).execute();
    }

    @Test
    @DisplayName("a missing driver fails the build, naming the artefact to add rather than a subsystem")
    void missingDriverFailsTheBuild(@TempDir Path tmp) {
        VerifyRuntimeMojo mojo = mojo(tmp, missing(SUBSYSTEM, PERSISTENCE));

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoFailureException.class)
                // The whole point of the goal: T50's complaint was that the failure named a
                // subsystem where the missing thing is a jar.
                .hasMessageContaining(RequiredDrivers.suggestedArtifact())
                .hasMessageContaining(SUBSYSTEM)
                .hasMessageContaining(PERSISTENCE)
                .hasMessageContaining("2 of 2")
                .hasMessageContaining("exeris.verifyRuntime.skip");
    }

    @Test
    @DisplayName("a partially-satisfied classpath names only what is absent")
    void partialFailureNamesOnlyTheMissing(@TempDir Path tmp) {
        VerifyRuntimeMojo mojo = mojo(tmp, missing(PERSISTENCE));

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("1 of 2")
                .hasMessageContaining(PERSISTENCE);
    }

    @Test
    @DisplayName("the dedicated skip degrades the verdict to a warning, it does not silence it")
    void dedicatedSkipWarnsInsteadOfFailing(@TempDir Path tmp) throws Exception {
        VerifyRuntimeMojo mojo = mojo(tmp, missing(SUBSYSTEM));
        mojo.skipRuntimeCheck = true;

        mojo.execute();
    }

    @Test
    @DisplayName("the pipeline-wide skip runs nothing at all")
    void pipelineSkipDoesNotEvenCheck(@TempDir Path tmp) throws Exception {
        List<Path> calls = new ArrayList<>();
        VerifyRuntimeMojo mojo = new VerifyRuntimeMojo();
        mojo.metadataDir = tmp.toFile();
        mojo.runtimeClasspathElements = List.of();
        mojo.skip = true;
        mojo.verifier = (m, cp) -> {
            calls.add(m);
            return missing(SUBSYSTEM);
        };

        mojo.execute();

        assertThat(calls).isEmpty();
    }

    @Test
    @DisplayName("unreadable metadata is an execution error, not a build failure — the difference "
            + "is whose fault it is")
    void unreadableMetadataIsAnExecutionError(@TempDir Path tmp) {
        VerifyRuntimeMojo mojo = mojo(tmp, missing(SUBSYSTEM));
        mojo.verifier = (m, cp) -> {
            throw new IOException("disk gone");
        };

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("Could not read domain metadata");
    }

    @Test
    @DisplayName("the classpath handed to the check is the injected runtime one, verbatim")
    void passesTheInjectedRuntimeClasspath(@TempDir Path tmp) throws Exception {
        List<List<Path>> seen = new ArrayList<>();
        VerifyRuntimeMojo mojo = mojo(tmp, new RuntimeDriverCheck.Result(List.of(SUBSYSTEM), List.of(), 1));
        mojo.runtimeClasspathElements = List.of("/a/classes", "/b/driver.jar");
        mojo.verifier = (m, cp) -> {
            seen.add(cp);
            return new RuntimeDriverCheck.Result(List.of(SUBSYSTEM), List.of(), 1);
        };

        mojo.execute();

        assertThat(seen).containsExactly(List.of(Path.of("/a/classes"), Path.of("/b/driver.jar")));
    }
}
