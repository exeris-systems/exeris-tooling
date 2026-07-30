package eu.exeris.tooling.codegen.maven;

import eu.exeris.tooling.codegen.core.capability.CapTierWallException;
import eu.exeris.tooling.codegen.core.capability.CapabilityGraphException;
import eu.exeris.tooling.codegen.core.capability.WallViolation;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
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
        mojo.classesDir = tmp.resolve("target/classes").toFile();
        mojo.validator = m -> {
            calls.add(m);
            return modules;
        };
        // Wall guard stubbed out by default so the graph-gate tests stay about the graph gate;
        // the Wall's own control flow is exercised in the CapTierWallGuard nested class below.
        mojo.wallVerifier = (c, m) -> 0;
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

    @Nested
    @DisplayName("cap-tier Wall guard (ADR-055)")
    class CapTierWallGuard {

        @Test
        @DisplayName("runs against the configured classes directory, after graph validation")
        void runsAfterGraphValidation(@TempDir Path tmp) throws Exception {
            List<String> order = new ArrayList<>();
            VerifyCapabilitiesMojo mojo = mojo(tmp, new ArrayList<>(), 2);
            mojo.validator = m -> {
                order.add("graph");
                return 2;
            };
            mojo.wallVerifier = (c, m) -> {
                order.add("wall:" + c);
                return 2;
            };

            mojo.execute();

            // Order is contractual: a broken graph is the more urgent verdict, so it reports first.
            assertThat(order).containsExactly("graph", "wall:" + mojo.classesDir.toPath());
        }

        @Test
        @DisplayName("exeris.wall.skip disables the Wall alone — graph validation still runs")
        void skipWallLeavesGraphGateArmed(@TempDir Path tmp) throws Exception {
            List<Path> graphCalls = new ArrayList<>();
            VerifyCapabilitiesMojo mojo = mojo(tmp, graphCalls, 2);
            mojo.skipWall = true;
            mojo.wallVerifier = (c, m) -> {
                throw new AssertionError("Wall must not run when exeris.wall.skip=true");
            };

            mojo.execute();

            assertThat(graphCalls).hasSize(1);
        }

        @Test
        @DisplayName("exeris.codegen.skip also short-circuits the Wall")
        void globalSkipCoversTheWall(@TempDir Path tmp) throws Exception {
            VerifyCapabilitiesMojo mojo = mojo(tmp, new ArrayList<>(), 2);
            mojo.skip = true;
            mojo.wallVerifier = (c, m) -> {
                throw new AssertionError("Wall must not run when the whole pipeline is skipped");
            };

            mojo.execute();
        }

        @Test
        @DisplayName("a Wall breach fails the build (MojoFailureException — a cap author's error)")
        void breachFailsTheBuild(@TempDir Path tmp) {
            VerifyCapabilitiesMojo mojo = mojo(tmp, new ArrayList<>(), 2);
            mojo.wallVerifier = (c, m) -> {
                throw new CapTierWallException(List.of(new WallViolation(
                        "eu.exeris.caps.billing.internal.Cap",
                        "org.springframework.context.ApplicationContext",
                        WallViolation.Rule.HOST_RUNTIME)));
            };

            assertThatThrownBy(mojo::execute)
                    .isInstanceOf(MojoFailureException.class)
                    .hasMessageContaining("Cap-tier Wall violated")
                    .hasMessageContaining("org.springframework.context.ApplicationContext");
        }

        @Test
        @DisplayName("an unreadable class file is an environment error, not a Wall verdict")
        void unreadableClassIsExecutionError(@TempDir Path tmp) {
            VerifyCapabilitiesMojo mojo = mojo(tmp, new ArrayList<>(), 2);
            mojo.wallVerifier = (c, m) -> {
                throw new UncheckedIOException(new IOException("truncated class file"));
            };

            assertThatThrownBy(mojo::execute)
                    .isInstanceOf(MojoExecutionException.class)
                    .hasMessageContaining("Cap-tier Wall scan failed")
                    .hasRootCauseMessage("truncated class file");
        }
    }
}
