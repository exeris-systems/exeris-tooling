package eu.exeris.tooling.codegen.maven;

import eu.exeris.tooling.codegen.core.capability.CapabilityGraphException;
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

@DisplayName("VerifyCapabilitiesMojo — the post-compile fresh-metadata gate (T18a)")
class VerifyCapabilitiesMojoTest {

    private static VerifyCapabilitiesMojo mojo(Path tmp, List<Path> calls, int modules) {
        VerifyCapabilitiesMojo mojo = new VerifyCapabilitiesMojo();
        mojo.metadataDir = tmp.resolve("target/classes/exeris-metadata").toFile();
        mojo.validator = m -> {
            calls.add(m);
            return modules;
        };
        return mojo;
    }

    @Test
    @DisplayName("runs the validator against the configured metadata directory")
    void runsValidator(@TempDir Path tmp) throws Exception {
        List<Path> calls = new ArrayList<>();
        VerifyCapabilitiesMojo mojo = mojo(tmp, calls, 2);

        mojo.execute();

        assertThat(calls).containsExactly(mojo.metadataDir.toPath());
    }

    @Test
    @DisplayName("no capability metadata (0 modules) is a pass — nothing to verify")
    void zeroModulesPasses(@TempDir Path tmp) throws Exception {
        List<Path> calls = new ArrayList<>();
        VerifyCapabilitiesMojo mojo = mojo(tmp, calls, 0);

        mojo.execute();

        assertThat(calls).hasSize(1);
    }

    @Test
    @DisplayName("skip=true short-circuits — the validator never runs")
    void skipShortCircuits(@TempDir Path tmp) throws Exception {
        List<Path> calls = new ArrayList<>();
        VerifyCapabilitiesMojo mojo = mojo(tmp, calls, 2);
        mojo.skip = true;

        mojo.execute();

        assertThat(calls).isEmpty();
    }

    @Test
    @DisplayName("surfaces a CapabilityGraphException as MojoFailureException (genuine fresh-metadata verdict)")
    void surfacesGraphFailure(@TempDir Path tmp) {
        VerifyCapabilitiesMojo mojo = mojo(tmp, new ArrayList<>(), 0);
        mojo.validator = m -> {
            throw new CapabilityGraphException(List.of(
                    "module com.app.Checkout @Requires service com.api.PaymentApi but no @CapabilityModule provides it"));
        };

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("com.api.PaymentApi");
    }

    @Test
    @DisplayName("wraps a validator IOException as MojoExecutionException")
    void wrapsIoFailure(@TempDir Path tmp) {
        VerifyCapabilitiesMojo mojo = mojo(tmp, new ArrayList<>(), 0);
        mojo.validator = m -> {
            throw new IOException("disk full");
        };

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("Capability verification failed")
                .hasRootCauseMessage("disk full");
    }
}
