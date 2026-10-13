package eu.exeris.tooling.initializr.engine;

import java.io.Serial;
import java.nio.file.Path;

/**
 * Raised when a project would be written into a directory that is not empty, or over a file that
 * exists, and writing over it was not asked for. Nothing has been written when it is raised.
 *
 * @since 0.10
 */
public final class TargetExistsException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The directory or archive that was not written. */
    private final transient Path target;

    /**
     * Creates the exception for a target that was not written.
     *
     * @param target the directory or archive file
     * @param reason what about it stopped the write
     * @since 0.10
     */
    public TargetExistsException(Path target, String reason) {
        super(target + " " + reason);
        this.target = target;
    }

    /**
     * Creates the exception for a target that was not written, keeping the failure that showed it
     * exists.
     *
     * @param target the directory or archive file
     * @param reason what about it stopped the write
     * @param cause  the file-system failure that showed it
     * @since 0.10
     */
    public TargetExistsException(Path target, String reason, Throwable cause) {
        super(target + " " + reason, cause);
        this.target = target;
    }

    /**
     * Returns the directory or archive file that was not written.
     *
     * @return the target path, as it was given
     * @since 0.10
     */
    public Path target() {
        return target;
    }
}
