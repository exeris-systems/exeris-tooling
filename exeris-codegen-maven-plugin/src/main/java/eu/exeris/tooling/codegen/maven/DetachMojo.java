package eu.exeris.tooling.codegen.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import eu.exeris.tooling.codegen.maven.internal.DetachResult;
import eu.exeris.tooling.codegen.maven.internal.DetachService;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * {@code exeris:detach} — promotes generated sources from
 * {@code src/main/generated/java} (L1) into {@code src/main/java} (L2): the user
 * takes ownership of the code and regeneration stops overwriting it.
 *
 * <p>Manual goal (not lifecycle-bound). After running it, remove the
 * {@code exeris:generate} execution from your build and commit the promoted
 * sources. Re-running is safe: a missing/empty generated tree is a no-op, and a
 * file that already exists at the target is reported as a conflict and left in
 * place (never overwritten).
 *
 * <p>This is the L2 step of the detachment story (hard-constraint #6): generated
 * code is committed under {@code src/main/generated/} until a user app detaches
 * it. The inverse ({@code exeris:reattach}) is planned and depends on the SDK
 * source-model round-trip (SDK 0.3.0) to re-derive metadata from owned sources.
 *
 * @since 0.3.0
 */
@Mojo(name = "detach", threadSafe = true)
public class DetachMojo extends AbstractMojo {

    /** The L1 generated-source root to promote. */
    @Parameter(property = "exeris.generatedDir",
            defaultValue = "${project.basedir}/src/main/generated/java")
    File generatedDir;

    /** The owned-source root to promote into. */
    @Parameter(property = "exeris.targetDir",
            defaultValue = "${project.basedir}/src/main/java")
    File targetDir;

    /** The {@code .gitignore} to clean. */
    @Parameter(property = "exeris.gitignore",
            defaultValue = "${project.basedir}/.gitignore")
    File gitignore;

    /** The {@code .gitignore} entry to remove once the tree is owned. */
    @Parameter(property = "exeris.ignoreEntry", defaultValue = "src/main/generated/")
    String ignoreEntry;

    /** Fail the build when any file could not be promoted because the target
     *  already exists (otherwise conflicts are only logged). */
    @Parameter(property = "exeris.failOnConflict", defaultValue = "false")
    boolean failOnConflict;

    /** Logic seam (mirrors GenerateMojo's pipeline seam). */
    DetachService service = new DetachService();

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        DetachResult result;
        try {
            result = service.detach(
                    generatedDir.toPath(), targetDir.toPath(), gitignore.toPath(), ignoreEntry);
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Detach failed (generatedDir=" + generatedDir + ")", e);
        }

        if (result.isEmpty() && result.conflicts().isEmpty()) {
            getLog().info("Nothing to detach — no generated sources under " + generatedDir);
            return;
        }

        getLog().info("Detached " + result.moved().size() + " file(s) into " + targetDir);
        for (Path moved : result.moved()) {
            getLog().debug("  promoted: " + moved);
        }
        if (result.gitignoreUpdated()) {
            getLog().info("Removed '" + ignoreEntry + "' from " + gitignore);
        }
        if (!result.moved().isEmpty()) {
            getLog().info("Next: drop the exeris:generate execution and commit the promoted sources.");
        }

        if (!result.conflicts().isEmpty()) {
            getLog().warn(result.conflicts().size()
                    + " file(s) already existed at the target and were left in place:");
            for (Path conflict : result.conflicts()) {
                getLog().warn("  conflict: " + conflict);
            }
            if (failOnConflict) {
                throw new MojoFailureException(
                        "Detach left " + result.conflicts().size()
                                + " conflict(s); resolve them or set exeris.failOnConflict=false");
            }
        }
    }
}
