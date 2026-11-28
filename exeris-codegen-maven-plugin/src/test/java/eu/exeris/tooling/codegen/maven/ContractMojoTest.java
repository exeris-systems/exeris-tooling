package eu.exeris.tooling.codegen.maven;

import eu.exeris.tooling.codegen.core.capability.CapabilityGraphException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ContractMojo — thin shell over CodegenPipeline.writeContract")
class ContractMojoTest {

    private static ContractMojo mojo(Path tmp, List<Path[]> calls) {
        ContractMojo mojo = new ContractMojo();
        mojo.metadataDir = tmp.resolve("target/classes/exeris-metadata").toFile();
        mojo.contractDir = tmp.resolve("target/classes/META-INF/exeris/contract").toFile();
        mojo.writer = (m, c) -> {
            calls.add(new Path[] {m, c});
            return 3;
        };
        return mojo;
    }

    @Test
    @DisplayName("writes the contract from the configured metadata directory to the configured contract directory")
    void writes(@TempDir Path tmp) throws Exception {
        List<Path[]> calls = new ArrayList<>();
        ContractMojo mojo = mojo(tmp, calls);

        mojo.execute();

        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call[0]).isEqualTo(mojo.metadataDir.toPath());
            assertThat(call[1]).isEqualTo(mojo.contractDir.toPath());
        });
    }

    @Test
    @DisplayName("a module with pom packaging writes no contract")
    void pomPackaging(@TempDir Path tmp) throws Exception {
        List<Path[]> calls = new ArrayList<>();
        ContractMojo mojo = mojo(tmp, calls);
        mojo.packaging = "pom";

        mojo.execute();

        assertThat(calls).isEmpty();
        assertThat(mojo.contractDir).doesNotExist();
    }

    @Test
    @DisplayName("exeris.codegen.skip and exeris.contract.skip each write nothing")
    void skips(@TempDir Path tmp) throws Exception {
        List<Path[]> calls = new ArrayList<>();
        ContractMojo whole = mojo(tmp, calls);
        whole.skip = true;
        ContractMojo own = mojo(tmp, calls);
        own.skipContract = true;

        whole.execute();
        own.execute();

        assertThat(calls).isEmpty();
    }

    @Test
    @DisplayName("a graph that does not resolve is a build failure carrying EXT-PLUG-2401")
    void unresolvedGraphFails(@TempDir Path tmp) {
        ContractMojo mojo = mojo(tmp, new ArrayList<>());
        mojo.writer = (m, c) -> {
            throw new CapabilityGraphException(List.of("com.app.A requires com.api.X, which nothing provides"));
        };

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("EXT-PLUG-2401")
                .hasMessageContaining("com.api.X");
    }

    @Test
    @DisplayName("an I/O failure is an execution error carrying EXT-PLUG-2401 and its cause")
    void ioFailure(@TempDir Path tmp) {
        ContractMojo mojo = mojo(tmp, new ArrayList<>());
        mojo.writer = (m, c) -> {
            throw new IOException("disk full");
        };

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("EXT-PLUG-2401")
                .hasMessageContaining("disk full");
    }

    @Test
    @DisplayName("the default writer is the pipeline's, and writes the empty composition for a build with no metadata")
    void defaultWriter(@TempDir Path tmp) throws Exception {
        ContractMojo mojo = new ContractMojo();
        mojo.metadataDir = tmp.resolve("absent").toFile();
        mojo.contractDir = tmp.resolve("META-INF/exeris/contract").toFile();

        mojo.execute();

        assertThat(Files.readString(mojo.contractDir.toPath().resolve("cap-manifest.json")))
                .contains("\"schemaVersion\" : 2");
    }
}
