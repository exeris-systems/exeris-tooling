package eu.exeris.tooling.codegen.maven;

import eu.exeris.tooling.codegen.core.capability.CapabilityGraphException;
import eu.exeris.tooling.codegen.java.EmptyMetadataException;
import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GenerateMojo — thin shell over CodegenPipeline")
class GenerateMojoTest {

    private static final String PLUGIN_GROUP_ID = "eu.exeris.tooling";
    private static final String PLUGIN_ARTIFACT_ID = "exeris-codegen-maven-plugin";

    /** Records pipeline invocations so we assert control flow without real generation. */
    private record Call(Path metadataDir, Path outputDir, String basePackage, boolean allowEmpty,
                        boolean deferCapabilityFailure) { }

    private static GenerateMojo mojo(Path tmp, List<Call> calls) {
        GenerateMojo mojo = new GenerateMojo();
        mojo.metadataDir = tmp.resolve("target/classes/exeris-metadata").toFile();
        mojo.outputDir = tmp.resolve("src/main/generated/java").toFile();
        mojo.basePackage = "com.shop";
        mojo.addCompileSourceRoot = true;
        mojo.project = new MavenProject();
        mojo.pluginDescriptor = new PluginDescriptor();
        mojo.pluginDescriptor.setGroupId(PLUGIN_GROUP_ID);
        mojo.pluginDescriptor.setArtifactId(PLUGIN_ARTIFACT_ID);
        mojo.pipeline = (m, o, b, ae, defer) -> calls.add(new Call(m, o, b, ae, defer));
        mojo.testOutputDir = tmp.resolve("src/test/generated/java").toFile();
        // Test emission is stubbed out by default so the existing tests stay about main emission;
        // the T2 channel's own control flow is exercised in the GeneratedTests nested class.
        mojo.testPipeline = (m, o, b) -> { };
        return mojo;
    }

    /** Binds an execution of {@code artifactId} with the given goal (+ optional phase) in the project. */
    private static void bindGoal(GenerateMojo mojo, String artifactId, String goal, String phase) {
        Plugin plugin = new Plugin();
        plugin.setGroupId(PLUGIN_GROUP_ID);
        plugin.setArtifactId(artifactId);
        PluginExecution execution = new PluginExecution();
        execution.addGoal(goal);
        if (phase != null) {
            execution.setPhase(phase);
        }
        plugin.addExecution(execution);
        Build build = mojo.project.getBuild() != null ? mojo.project.getBuild() : new Build();
        build.addPlugin(plugin);
        mojo.project.getModel().setBuild(build);
    }

    @Test
    @DisplayName("runs the pipeline with the configured paths and registers the output as a compile source root")
    void runsPipelineAndAddsSourceRoot(@TempDir Path tmp) throws Exception {
        List<Call> calls = new ArrayList<>();
        GenerateMojo mojo = mojo(tmp, calls);

        mojo.execute();

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).metadataDir()).isEqualTo(mojo.metadataDir.toPath());
        assertThat(calls.get(0).outputDir()).isEqualTo(mojo.outputDir.toPath());
        assertThat(calls.get(0).basePackage()).isEqualTo("com.shop");
        // T18: the masked-compile-failure guard is ON by default (allowEmpty=false)
        assertThat(calls.get(0).allowEmpty()).isFalse();
        // T18(a): no verify-capabilities gate bound → strict capability validation
        assertThat(calls.get(0).deferCapabilityFailure()).isFalse();
        assertThat(mojo.project.getCompileSourceRoots())
                .contains(mojo.outputDir.getAbsolutePath());
    }

    @Test
    @DisplayName("threads exeris.codegen.allowEmpty through to the pipeline")
    void threadsAllowEmpty(@TempDir Path tmp) throws Exception {
        List<Call> calls = new ArrayList<>();
        GenerateMojo mojo = mojo(tmp, calls);
        mojo.allowEmpty = true;

        mojo.execute();

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).allowEmpty()).isTrue();
    }

    @Test
    @DisplayName("surfaces an EmptyMetadataException as MojoFailureException (T18 masked-compile guard, not a plugin bug)")
    void surfacesEmptyMetadataGuard(@TempDir Path tmp) {
        GenerateMojo mojo = mojo(tmp, new ArrayList<>());
        mojo.pipeline = (m, o, b, ae, defer) -> {
            throw new EmptyMetadataException(7, m, o);
        };

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("Refusing to wipe")
                .hasMessageContaining("allowEmpty=true");
        // failure occurs before the compile source root is registered
        assertThat(mojo.project.getCompileSourceRoots())
                .doesNotContain(mojo.outputDir.getAbsolutePath());
    }

    @Test
    @DisplayName("skip=true suppresses generation but still registers the committed tree")
    void skipShortCircuits(@TempDir Path tmp) throws Exception {
        List<Call> calls = new ArrayList<>();
        GenerateMojo mojo = mojo(tmp, calls);
        mojo.skip = true;

        mojo.execute();

        assertThat(calls).isEmpty();
        // Skipping generation must not un-register the committed L1 tree: hand-written
        // code compiles against it, and the documented T18 recipe
        // (`mvn compile -Dexeris.codegen.skip=true`) is exactly this path.
        assertThat(mojo.project.getCompileSourceRoots())
                .contains(mojo.outputDir.getAbsolutePath());
    }

    @Test
    @DisplayName("skip=true leaves the generated-test tree on the test-compile path too")
    void skipStillRegistersTestRoot(@TempDir Path tmp) throws Exception {
        GenerateMojo mojo = mojo(tmp, new ArrayList<>());
        mojo.skip = true;
        mojo.generateTests = true;
        mojo.testPipeline = (m, o, b) -> {
            throw new AssertionError("nothing runs when the whole pipeline is skipped");
        };

        mojo.execute();

        assertThat(mojo.project.getTestCompileSourceRoots())
                .contains(mojo.testOutputDir.getAbsolutePath());
    }

    @Test
    @DisplayName("skip=true honours addCompileSourceRoot=false")
    void skipRespectsSourceRootOptOut(@TempDir Path tmp) throws Exception {
        GenerateMojo mojo = mojo(tmp, new ArrayList<>());
        mojo.skip = true;
        mojo.addCompileSourceRoot = false;

        mojo.execute();

        assertThat(mojo.project.getCompileSourceRoots())
                .doesNotContain(mojo.outputDir.getAbsolutePath());
    }

    @Test
    @DisplayName("addCompileSourceRoot=false generates but does not touch the source roots")
    void noSourceRootWhenDisabled(@TempDir Path tmp) throws Exception {
        List<Call> calls = new ArrayList<>();
        GenerateMojo mojo = mojo(tmp, calls);
        mojo.addCompileSourceRoot = false;

        mojo.execute();

        assertThat(calls).hasSize(1);
        assertThat(mojo.project.getCompileSourceRoots())
                .doesNotContain(mojo.outputDir.getAbsolutePath());
    }

    @Test
    @DisplayName("wraps a pipeline IOException as MojoExecutionException")
    void wrapsIoFailure(@TempDir Path tmp) {
        GenerateMojo mojo = mojo(tmp, new ArrayList<>());
        mojo.pipeline = (m, o, b, ae, defer) -> {
            throw new IOException("disk full");
        };

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("Code generation failed")
                .hasRootCauseMessage("disk full");
    }

    @Test
    @DisplayName("surfaces a CapabilityGraphException as MojoFailureException (user error, not plugin bug)")
    void surfacesCapabilityFailure(@TempDir Path tmp) {
        GenerateMojo mojo = mojo(tmp, new ArrayList<>());
        mojo.pipeline = (m, o, b, ae, defer) -> {
            throw new CapabilityGraphException(List.of(
                    "module com.app.Checkout @Requires service com.api.PaymentApi but no @CapabilityModule provides it"));
        };

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("com.api.PaymentApi");
        // failure occurs before the compile source root is registered
        assertThat(mojo.project.getCompileSourceRoots())
                .doesNotContain(mojo.outputDir.getAbsolutePath());
    }

    // --- T18(a): defer capability failure iff the verify-capabilities gate is bound ---

    @Test
    @DisplayName("verify-capabilities bound in this plugin → deferCapabilityFailure=true")
    void defersWhenVerifyCapabilitiesBound(@TempDir Path tmp) throws Exception {
        List<Call> calls = new ArrayList<>();
        GenerateMojo mojo = mojo(tmp, calls);
        bindGoal(mojo, PLUGIN_ARTIFACT_ID, "verify-capabilities", null);

        mojo.execute();

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).deferCapabilityFailure()).isTrue();
    }

    @Test
    @DisplayName("verify-capabilities execution unbound via <phase>none</phase> → stays strict")
    void phaseNoneDoesNotDefer(@TempDir Path tmp) throws Exception {
        List<Call> calls = new ArrayList<>();
        GenerateMojo mojo = mojo(tmp, calls);
        bindGoal(mojo, PLUGIN_ARTIFACT_ID, "verify-capabilities", "none");

        mojo.execute();

        assertThat(calls.get(0).deferCapabilityFailure()).isFalse();
    }

    @Test
    @DisplayName("a verify-capabilities goal on a DIFFERENT plugin does not defer")
    void otherPluginGoalDoesNotDefer(@TempDir Path tmp) throws Exception {
        List<Call> calls = new ArrayList<>();
        GenerateMojo mojo = mojo(tmp, calls);
        bindGoal(mojo, "some-other-plugin", "verify-capabilities", null);

        mojo.execute();

        assertThat(calls.get(0).deferCapabilityFailure()).isFalse();
    }

    @Test
    @DisplayName("this plugin bound with only OTHER goals does not defer")
    void otherGoalOfThisPluginDoesNotDefer(@TempDir Path tmp) throws Exception {
        List<Call> calls = new ArrayList<>();
        GenerateMojo mojo = mojo(tmp, calls);
        bindGoal(mojo, PLUGIN_ARTIFACT_ID, "generate", null);

        mojo.execute();

        assertThat(calls.get(0).deferCapabilityFailure()).isFalse();
    }

    @Test
    @DisplayName("missing plugin descriptor (defensive) → stays strict")
    void nullPluginDescriptorStaysStrict(@TempDir Path tmp) throws Exception {
        List<Call> calls = new ArrayList<>();
        GenerateMojo mojo = mojo(tmp, calls);
        bindGoal(mojo, PLUGIN_ARTIFACT_ID, "verify-capabilities", null);
        mojo.pluginDescriptor = null;

        mojo.execute();

        assertThat(calls.get(0).deferCapabilityFailure()).isFalse();
    }

    @Test
    @DisplayName("verify-capabilities explicitly rebound to a PRE-compile phase → stays strict (the gate would itself see stale metadata)")
    void earlyPhaseDoesNotDefer(@TempDir Path tmp) throws Exception {
        List<Call> calls = new ArrayList<>();
        GenerateMojo mojo = mojo(tmp, calls);
        bindGoal(mojo, PLUGIN_ARTIFACT_ID, "verify-capabilities", "validate");

        mojo.execute();

        assertThat(calls.get(0).deferCapabilityFailure()).isFalse();
    }

    @Test
    @DisplayName("verify-capabilities explicitly rebound to a post-compile phase (verify) → defers")
    void latePhaseDefers(@TempDir Path tmp) throws Exception {
        List<Call> calls = new ArrayList<>();
        GenerateMojo mojo = mojo(tmp, calls);
        bindGoal(mojo, PLUGIN_ARTIFACT_ID, "verify-capabilities", "verify");

        mojo.execute();

        assertThat(calls.get(0).deferCapabilityFailure()).isTrue();
    }

    @Test
    @DisplayName("verify-capabilities on an unknown/custom phase → stays strict (conservative)")
    void unknownPhaseDoesNotDefer(@TempDir Path tmp) throws Exception {
        List<Call> calls = new ArrayList<>();
        GenerateMojo mojo = mojo(tmp, calls);
        bindGoal(mojo, PLUGIN_ARTIFACT_ID, "verify-capabilities", "some-custom-phase");

        mojo.execute();

        assertThat(calls.get(0).deferCapabilityFailure()).isFalse();
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("generated tests (T2 / ADR-058)")
    class GeneratedTests {

        @Test
        @DisplayName("off by default — opting in is what accepts the JUnit + AssertJ contract")
        void offByDefault(@TempDir Path tmp) throws Exception {
            GenerateMojo mojo = mojo(tmp, new ArrayList<>());
            mojo.testPipeline = (m, o, b) -> {
                throw new AssertionError("tests must not be generated unless exeris.tests=true");
            };

            mojo.execute();

            assertThat(mojo.project.getTestCompileSourceRoots())
                    .doesNotContain(mojo.testOutputDir.getAbsolutePath());
        }

        @Test
        @DisplayName("exeris.tests=true emits into the test root and registers it for test-compile")
        void emitsAndRegistersTheTestRoot(@TempDir Path tmp) throws Exception {
            List<Path> testCalls = new ArrayList<>();
            GenerateMojo mojo = mojo(tmp, new ArrayList<>());
            mojo.generateTests = true;
            mojo.testPipeline = (m, o, b) -> testCalls.add(o);

            mojo.execute();

            assertThat(testCalls).containsExactly(mojo.testOutputDir.toPath());
            // addTestCompileSourceRoot, never addCompileSourceRoot: a generated test on the MAIN
            // path would compile into the application artefact and put JUnit on its runtime path.
            assertThat(mojo.project.getTestCompileSourceRoots())
                    .contains(mojo.testOutputDir.getAbsolutePath());
            assertThat(mojo.project.getCompileSourceRoots())
                    .doesNotContain(mojo.testOutputDir.getAbsolutePath());
        }

        @Test
        @DisplayName("main emission still runs first — tests are generated from the same metadata")
        void mainEmissionStillRuns(@TempDir Path tmp) throws Exception {
            List<Call> calls = new ArrayList<>();
            GenerateMojo mojo = mojo(tmp, calls);
            mojo.generateTests = true;

            mojo.execute();

            assertThat(calls).hasSize(1);
            assertThat(calls.getFirst().outputDir()).isEqualTo(mojo.outputDir.toPath());
        }

        @Test
        @DisplayName("exeris.codegen.skip short-circuits test emission too")
        void globalSkipCoversTestEmission(@TempDir Path tmp) throws Exception {
            GenerateMojo mojo = mojo(tmp, new ArrayList<>());
            mojo.generateTests = true;
            mojo.skip = true;
            mojo.testPipeline = (m, o, b) -> {
                throw new AssertionError("nothing runs when the whole pipeline is skipped");
            };

            mojo.execute();
        }

        @Test
        @DisplayName("an IO failure during test emission is an execution error, not a build failure")
        void ioFailureIsAnExecutionError(@TempDir Path tmp) {
            GenerateMojo mojo = mojo(tmp, new ArrayList<>());
            mojo.generateTests = true;
            mojo.testPipeline = (m, o, b) -> {
                throw new IOException("disk full");
            };

            assertThatThrownBy(mojo::execute)
                    .isInstanceOf(MojoExecutionException.class)
                    .hasMessageContaining("Test generation failed")
                    .hasRootCauseMessage("disk full");
        }
    }
}
