package eu.exeris.tooling.initializr.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Properties;

/**
 * The versions a generated project names: the tooling version of its parent and npm generator, and
 * the JDBC driver and test-library versions it declares itself.
 *
 * <p>{@link #bundled()} reads them from a resource the initializr's own build writes, so a released
 * initializr names its own release as the parent version, and the driver and test-library versions
 * are the ones the tooling reactor builds and tests with.
 *
 * @param tooling    the tooling version: of {@code exeris-app-parent}, which manages the starter,
 *                   and of {@code @exeris/codegen-ts}
 * @param h2         the {@code com.h2database:h2} version
 * @param postgresql the {@code org.postgresql:postgresql} version
 * @param junit      the {@code org.junit.jupiter:junit-jupiter} version the generated tests run on
 * @param assertj    the {@code org.assertj:assertj-core} version the generated tests assert with
 * @since 0.10
 */
public record ScaffoldVersions(String tooling, String h2, String postgresql, String junit, String assertj) {

    /**
     * The classpath resource {@link #bundled()} reads.
     *
     * @since 0.10
     */
    public static final String RESOURCE = "/META-INF/exeris/initializr/versions.properties";

    /**
     * Checks that every version is present.
     *
     * @throws NullPointerException     if a version is {@code null}
     * @throws IllegalArgumentException if a version is blank or an unresolved {@code ${…}}
     *                                  placeholder
     */
    public ScaffoldVersions {
        requireVersion("tooling", tooling);
        requireVersion("h2", h2);
        requireVersion("postgresql", postgresql);
        requireVersion("junit", junit);
        requireVersion("assertj", assertj);
    }

    /**
     * Reads the versions this initializr was built with.
     *
     * @return the versions in {@link #RESOURCE}
     * @throws IllegalStateException if the resource is missing or a version in it is missing or was
     *                               not filled in by the build
     * @since 0.10
     */
    public static ScaffoldVersions bundled() {
        Properties properties = new Properties();
        try (InputStream in = ScaffoldVersions.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is not on the classpath");
            }
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + RESOURCE + " failed", e);
        }
        try {
            return new ScaffoldVersions(properties.getProperty("tooling"), properties.getProperty("h2"),
                    properties.getProperty("postgresql"), properties.getProperty("junit"),
                    properties.getProperty("assertj"));
        } catch (NullPointerException | IllegalArgumentException e) {
            throw new IllegalStateException(RESOURCE + ": " + e.getMessage(), e);
        }
    }

    private static void requireVersion(String what, String version) {
        Objects.requireNonNull(version, what);
        if (version.isBlank() || version.contains("${")) {
            throw new IllegalArgumentException(what + " version is not set: '" + version + "'");
        }
    }
}
