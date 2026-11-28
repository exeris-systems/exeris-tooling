package eu.exeris.tooling.initializr.model;

import javax.lang.model.SourceVersion;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a new project is: its Maven coordinates, its base package, its topology, its database and
 * the name of the one entity it starts with.
 *
 * <p>Every value is validated on construction, so a request that exists can be written: an invalid
 * value raises {@link IllegalArgumentException} with a message naming it. {@code packageName},
 * {@code name} and {@code entityName} may be passed as {@code null} and are then derived. A Windows
 * device name ({@code CON}, {@code PRN}, {@code AUX}, {@code NUL}, {@code COM1}-{@code COM9},
 * {@code LPT1}-{@code LPT9}, compared without regard to case) is refused as an entity name, as a
 * package segment, and as a groupId or artifactId, or as the part of one before its first dot, because
 * the file or directory it names cannot be extracted on Windows.
 *
 * @param groupId     the Maven groupId of the generated project
 * @param artifactId  the Maven artifactId of the generated project, at most {@value #MAX_ARTIFACT_ID_LENGTH}
 *                    characters, starting with a letter or a digit and not ending in a dot, which
 *                    Windows cannot extract as the archive's root directory; also its npm package
 *                    name, in lower case
 * @param packageName the base package; the entity is written to {@code <packageName>.domain}, and
 *                    the generated {@code Application} to {@code <packageName>}, which the README
 *                    prints in a shell command, so it holds no {@code $}. {@code null} derives it
 *                    from {@code groupId} and {@code artifactId}
 * @param name        the project's display name, used as the README title and the POM description;
 *                    one line of characters XML 1.0 allows, with no line separator and no bidirectional
 *                    override; {@code null} uses {@code artifactId}
 * @param topology    which application shape to write
 * @param database    which JDBC driver the backend declares and which connection its README
 *                    configures
 * @param entityName  the simple name of the starting {@code @ExerisDomain} entity, an upper-case
 *                    ASCII identifier that is none of {@link #RESERVED_ENTITY_NAMES} and no Windows
 *                    device name; {@code null} uses {@value #DEFAULT_ENTITY}
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

    /**
     * The simple names the entity template refers to, which an entity of the same name would
     * shadow or redeclare: {@code UUID}, {@code Field} and {@code ExerisDomain} are imported by it,
     * and {@code String} types its generated fields.
     *
     * @since 0.12
     */
    public static final Set<String> RESERVED_ENTITY_NAMES =
            Collections.unmodifiableSet(new LinkedHashSet<>(List.of("UUID", "Field", "ExerisDomain", "String")));

    /**
     * The longest artifactId accepted; with the {@code -frontend} suffix the derived npm package name
     * stays within npm's limit of 214 characters.
     *
     * @since 0.12
     */
    public static final int MAX_ARTIFACT_ID_LENGTH = 100;

    private static final Pattern WINDOWS_DEVICE = Pattern.compile("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])");
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
        if (!Character.isLetterOrDigit(artifactId.charAt(0))) {
            throw new IllegalArgumentException("artifactId must start with a letter or a digit: " + artifactId);
        }
        if (artifactId.endsWith(".")) {
            throw new IllegalArgumentException("artifactId must not end in a dot: " + artifactId);
        }
        if (artifactId.length() > MAX_ARTIFACT_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "artifactId may hold at most " + MAX_ARTIFACT_ID_LENGTH + " characters: " + artifactId.length());
        }

        if (packageName == null) {
            packageName = derivePackage(groupId, artifactId);
        }
        if (!SourceVersion.isName(packageName, SourceVersion.latestSupported())) {
            throw new IllegalArgumentException("package is not a Java package name: " + packageName);
        }
        if (packageName.indexOf('$') >= 0) {
            throw new IllegalArgumentException(
                    "package must not hold '$', which a shell expands in the README's run command: " + packageName);
        }
        if ("java".equals(packageName) || packageName.startsWith("java.")) {
            throw new IllegalArgumentException(
                    "package must not start with the segment 'java', which the JVM reserves: " + packageName);
        }
        for (String segment : packageName.split("\\.")) {
            if (WINDOWS_DEVICE.matcher(segment).matches()) {
                throw new IllegalArgumentException(
                        "package must not hold the Windows device name '" + segment + "' as a segment: " + packageName);
            }
        }

        if (name == null) {
            name = artifactId;
        }
        if (name.isBlank() || name.codePoints().anyMatch(ProjectScaffoldRequest::isRefusedInName)) {
            throw new IllegalArgumentException(
                    "name must be one non-blank line of characters XML 1.0 allows, with no line separator or "
                            + "bidirectional override: " + name);
        }

        if (entityName == null) {
            entityName = DEFAULT_ENTITY;
        }
        if (!ENTITY.matcher(entityName).matches()
                || SourceVersion.isKeyword(entityName, SourceVersion.latestSupported())) {
            throw new IllegalArgumentException(
                    "entity must be an ASCII Java type name that starts with an upper-case letter: " + entityName);
        }
        if (RESERVED_ENTITY_NAMES.contains(entityName)) {
            throw new IllegalArgumentException(
                    "entity must not be a name the entity template uses " + RESERVED_ENTITY_NAMES + ": " + entityName);
        }
        if (WINDOWS_DEVICE.matcher(entityName).matches()) {
            throw new IllegalArgumentException("entity must not be a Windows device name: " + entityName);
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

    private static boolean isRefusedInName(int c) {
        boolean xmlChar = c == 0x9 || c == 0xA || c == 0xD || (c >= 0x20 && c <= 0xD7FF)
                || (c >= 0xE000 && c <= 0xFFFD) || (c >= 0x10000 && c <= 0x10FFFF);
        boolean separator = c == 0x2028 || c == 0x2029 || c == 0x85;
        boolean bidi = (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069);
        return !xmlChar || Character.isISOControl(c) || separator || bidi;
    }

    private static void requireMavenId(String what, String value) {
        if (!MAVEN_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(what + " may hold only letters, digits, '_', '.' and '-': " + value);
        }
        if (value.chars().allMatch(c -> c == '.')) {
            throw new IllegalArgumentException(what + " must hold more than dots, which name a directory: " + value);
        }
        int dot = value.indexOf('.');
        if (WINDOWS_DEVICE.matcher(dot < 0 ? value : value.substring(0, dot)).matches()) {
            throw new IllegalArgumentException(what + " must not start with a Windows device name: " + value);
        }
    }
}
