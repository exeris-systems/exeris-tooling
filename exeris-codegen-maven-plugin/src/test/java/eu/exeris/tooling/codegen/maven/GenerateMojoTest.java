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
    @DisplayName("skip=true short-circuits — no generation, no source root")
    void skipShortCircuits(@TempDir Path tmp) throws Exception {
        List<Call> calls = new ArrayList<>();
        GenerateMojo mojo = mojo(tmp, calls);
        mojo.skip = true;

        mojo.execute();

        assertThat(calls).isEmpty();
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
}
