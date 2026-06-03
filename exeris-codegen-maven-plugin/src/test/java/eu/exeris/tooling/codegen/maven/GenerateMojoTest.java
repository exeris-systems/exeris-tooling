package eu.exeris.tooling.codegen.maven;

import org.apache.maven.plugin.MojoExecutionException;
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

    /** Records pipeline invocations so we assert control flow without real generation. */
    private record Call(Path metadataDir, Path outputDir, String basePackage) { }

    private static GenerateMojo mojo(Path tmp, List<Call> calls) {
        GenerateMojo mojo = new GenerateMojo();
        mojo.metadataDir = tmp.resolve("target/classes/exeris-metadata").toFile();
        mojo.outputDir = tmp.resolve("src/main/generated/java").toFile();
        mojo.basePackage = "com.shop";
        mojo.addCompileSourceRoot = true;
        mojo.project = new MavenProject();
        mojo.pipeline = (m, o, b) -> calls.add(new Call(m, o, b));
        return mojo;
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
        assertThat(mojo.project.getCompileSourceRoots())
                .contains(mojo.outputDir.getAbsolutePath());
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
        mojo.pipeline = (m, o, b) -> {
            throw new IOException("disk full");
        };

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("Code generation failed")
                .hasRootCauseMessage("disk full");
    }
}
