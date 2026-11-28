package eu.exeris.tooling.codegen.maven;

import eu.exeris.tooling.codegen.core.capability.CapabilityGraphException;
import eu.exeris.tooling.codegen.java.CodegenPipeline;
import eu.exeris.tooling.diagnostics.DiagnosticId;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * {@code exeris:contract} — publishes the backend's contract artifact inside its own jar
 * (ADR-048 Amendment 3).
 *
 * <p>Bound to {@code process-classes}, after the processor has (re)written
 * {@code exeris-metadata/} in the compile phase, so the contract is made from fresh metadata. It
 * writes {@code META-INF/exeris/contract/} under the class output: {@code cap-manifest.json} (the
 * graph of the empty composition when the build declares no {@code @CapabilityModule}) and the
 * entity and enum metadata files, copied byte for byte. The directory is deleted and written anew.
 * A capability graph that does not resolve fails the goal and leaves the previous contract in
 * place. A module with {@code pom} packaging writes nothing. A thin shell over
 * {@link CodegenPipeline#writeContract(Path, Path)}.
 *
 * @since 0.12
 */
@Mojo(name = ContractMojo.GOAL, defaultPhase = LifecyclePhase.PROCESS_CLASSES, threadSafe = true)
public class ContractMojo extends AbstractMojo {

    /** Goal name. */
    static final String GOAL = "contract";

    /** Directory holding processor-emitted metadata. */
    @Parameter(property = "exeris.metadataDir",
            defaultValue = "${project.build.outputDirectory}/exeris-metadata")
    File metadataDir;

    /** Directory the contract is written to; it lands in the jar with the class output. */
    @Parameter(property = "exeris.contractDir",
            defaultValue = "${project.build.outputDirectory}/META-INF/exeris/contract")
    File contractDir;

    /** Skip the whole codegen pipeline, this goal included. */
    @Parameter(property = "exeris.codegen.skip", defaultValue = "false")
    boolean skip;

    /** Skip publishing the contract alone. */
    @Parameter(property = "exeris.contract.skip", defaultValue = "false")
    boolean skipContract;

    /** Packaging of the project; a {@code pom} module has no jar to carry a contract. */
    @Parameter(defaultValue = "${project.packaging}", readonly = true)
    String packaging;

    /**
     * Writer seam over {@link CodegenPipeline#writeContract(Path, Path)}, so the mojo's control flow
     * is unit-testable ({@code CodegenPipeline} is {@code final}).
     */
    @FunctionalInterface
    interface ContractWriter {
        int write(Path metadataDir, Path contractDir) throws IOException;
    }

    private final CodegenPipeline pipeline = CodegenPipeline.createDefault();

    ContractWriter writer = pipeline::writeContract;

    /**
     * Creates the mojo. Maven constructs it reflectively and injects the {@code @Parameter}
     * fields before calling {@link #execute()}.
     */
    public ContractMojo() {
        // parameters are injected by Maven after construction
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("exeris:contract skipped (exeris.codegen.skip=true)");
            return;
        }
        if (skipContract) {
            getLog().info("exeris:contract skipped (exeris.contract.skip=true)");
            return;
        }
        if ("pom".equals(packaging)) {
            getLog().info("exeris:contract skipped (packaging pom: no jar carries a contract)");
            return;
        }
        try {
            int files = writer.write(metadataDir.toPath(), contractDir.toPath());
            getLog().info("Contract written: " + contractDir + " (" + files + " file(s))");
        } catch (CapabilityGraphException e) {
            throw new MojoFailureException(DiagnosticId.CONTRACT_NOT_BUILT.format(e.getMessage()), e);
        } catch (IOException e) {
            throw new MojoExecutionException(DiagnosticId.CONTRACT_NOT_BUILT.format(
                    "Contract not written (metadataDir=" + metadataDir + ", contractDir=" + contractDir
                            + "): " + e), e);
        }
    }
}
