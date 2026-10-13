package eu.exeris.tooling.initializr.model;

import javax.lang.model.SourceVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What a new project is: its Maven coordinates, its base package, its topology, its database and
 * the name of the one entity it starts with.
 *
 * <p>Every value is validated on construction, so a request that exists can be written: an invalid
 * value raises {@link IllegalArgumentException} with a message naming it. {@code packageName},
 * {@code name} and {@code entityName} may be passed as {@code null} and are then derived.
 *
 * @param groupId     the Maven groupId of the generated project
 * @param artifactId  the Maven artifactId of the generated project; also its npm package name, in
 *                    lower case
 * @param packageName the base package; the entity is written to {@code <packageName>.domain}, and
 *                    the generated {@code Application} to {@code <packageName>}. {@code null}
 *                    derives it from {@code groupId} and {@code artifactId}
 * @param name        the project's display name, used as the README title and the POM description;
 *                    {@code null} uses {@code artifactId}
 * @param topology    which of the three application shapes to write
 * @param database    which JDBC driver the backend declares and which connection its README
 *                    configures; read only when {@code topology} has a backend
 * @param entityName  the simple name of the starting {@code @ExerisDomain} entity, an upper-case
 *                    ASCII identifier; {@code null} uses {@value #DEFAULT_ENTITY}
 * @since 0.10
 */
public record ProjectScaffoldRequest(String groupId, String artifactId, String packageName, String name,
                                     ProjectTopology topology, DatabaseType database, String entityName) {

    /**
     * The entity name used when none is given.
     *
     * @since 0.10
     */
    public static final String DEFAULT_ENTITY = "Item";

    private static final Pattern MAVEN_ID = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final Pattern ENTITY = Pattern.compile("[A-Z][A-Za-z0-9]*");
    private static final Pattern NOT_PACKAGE_CHAR = Pattern.compile("[^a-z0-9_]");

    /**
     * Validates every value and derives the ones passed as {@code null}.
     *
     * @throws NullPointerException     if {@code groupId}, {@code artifactId}, {@code topology} or
     *                                  {@code database} is {@code null}
     * @throws IllegalArgumentException if a value is not valid for its use; the message names it
     */
    public ProjectScaffoldRequest {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(topology, "topology");
        Objects.requireNonNull(database, "database");
        requireMavenId("groupId", groupId);
        requireMavenId("artifactId", artifactId);

        if (packageName == null) {
            packageName = derivePackage(groupId, artifactId);
        }
        if (!SourceVersion.isName(packageName, SourceVersion.latestSupported())) {
            throw new IllegalArgumentException("package is not a Java package name: " + packageName);
        }

        if (name == null) {
            name = artifactId;
        }
        if (name.isBlank() || name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("name must be one non-blank line: " + name);
        }

        if (entityName == null) {
            entityName = DEFAULT_ENTITY;
        }
        if (!ENTITY.matcher(entityName).matches()
                || SourceVersion.isKeyword(entityName, SourceVersion.latestSupported())) {
            throw new IllegalArgumentException(
                    "entity must be an ASCII Java type name that starts with an upper-case letter: " + entityName);
        }
    }

    /**
     * Derives a base package from Maven coordinates: the groupId's segments followed by the
     * artifactId's, each lower-cased with every character outside {@code [a-z0-9_]} removed.
     *
     * <p>A segment that would start with a digit gets a leading {@code _}, and a segment that is a
     * Java keyword a trailing one. {@code com.example} and {@code order-service} give
     * {@code com.example.order.service}.
     *
     * @param groupId    a Maven groupId
     * @param artifactId a Maven artifactId
     * @return the derived package name, not validated
     * @since 0.10
     */
    public static String derivePackage(String groupId, String artifactId) {
        List<String> segments = new ArrayList<>();
        for (String raw : (groupId + "." + artifactId).split("[.\\-]")) {
            String segment = NOT_PACKAGE_CHAR.matcher(raw.toLowerCase(Locale.ROOT)).replaceAll("");
            if (segment.isEmpty()) {
                continue;
            }
            if (Character.isDigit(segment.charAt(0))) {
                segment = "_" + segment;
            }
            if (SourceVersion.isKeyword(segment, SourceVersion.latestSupported())) {
                segment = segment + "_";
            }
            segments.add(segment);
        }
        return String.join(".", segments);
    }

    private static void requireMavenId(String what, String value) {
        if (!MAVEN_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(what + " may hold only letters, digits, '_', '.' and '-': " + value);
        }
    }
}
