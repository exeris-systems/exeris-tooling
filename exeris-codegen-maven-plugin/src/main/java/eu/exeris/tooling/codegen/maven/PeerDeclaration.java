package eu.exeris.tooling.codegen.maven;

import java.io.File;

/**
 * One entry of the {@code peers} parameter: a name the consumer chooses, and exactly one of a
 * {@code path} or a {@code coordinate} that leads to the peer's contract (ADR-048 Amendment 3).
 *
 * {@snippet lang="xml" :
 * <peers>
 *   <peer><name>billing</name><coordinate>com.acme:billing-service:1.4.0</coordinate></peer>
 *   <peer><name>legacy</name><path>/contracts/legacy.jar</path></peer>
 * </peers>
 * }
 *
 * @since 0.12
 */
public class PeerDeclaration {

    /** The name the peer is known by in this build. */
    private String name;

    /** A contract directory, a directory holding {@code META-INF/exeris/contract/}, or a jar. */
    private File path;

    /** {@code groupId:artifactId:version} of the backend's main jar. */
    private String coordinate;

    /**
     * Creates an empty declaration; Maven fills the fields from the plugin configuration.
     */
    public PeerDeclaration() {
        // configured by Maven
    }

    /**
     * Creates a declaration.
     *
     * @param name       the peer name
     * @param path       the path, or {@code null}
     * @param coordinate the coordinate, or {@code null}
     */
    public PeerDeclaration(String name, File path, String coordinate) {
        this.name = name;
        this.path = path;
        this.coordinate = coordinate;
    }

    /**
     * Returns the peer name.
     *
     * @return the name, possibly {@code null} when unconfigured
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the path.
     *
     * @return the path, or {@code null}
     */
    public File getPath() {
        return path;
    }

    /**
     * Returns the coordinate.
     *
     * @return the coordinate, or {@code null}
     */
    public String getCoordinate() {
        return coordinate;
    }
}
