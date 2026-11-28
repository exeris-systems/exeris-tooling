package eu.exeris.tooling.codegen.core.contract;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A peer the consumer declares: the name it is known by in this build, and exactly one of a
 * path or a Maven coordinate that leads to the peer's contract.
 *
 * <p>The name is the consumer's (ADR-048 section 2). It lands in generated source, so it is
 * constrained: an ASCII letter, then letters, digits and single hyphens.
 *
 * @param name       the consumer-declared name
 * @param path       a contract directory, a directory containing {@code META-INF/exeris/contract/},
 *                   or a {@code .jar}; {@code null} when a coordinate is given
 * @param coordinate {@code groupId:artifactId:version} of the backend's main jar; {@code null}
 *                   when a path is given
 * @since 0.12
 */
public record PeerReference(String name, Path path, Coordinate coordinate) {


    /**
     * Validates the reference.
     */
    public PeerReference {
        Objects.requireNonNull(name, "name");
        if (!isUsable(name)) {
            throw new PeerContractException(name, "the name is not usable: it must start with a letter and "
                    + "contain only letters, digits and single hyphens");
        }
        if ((path == null) == (coordinate == null)) {
            throw new PeerContractException(name, "declare exactly one of a path or a coordinate");
        }
    }

    /**
     * Whether a name is an ASCII letter followed by letters, digits and single hyphens, each hyphen
     * followed by at least one letter or digit. Scans once, with no backtracking and no recursion.
     */
    private static boolean isUsable(String name) {
        if (name.isEmpty() || !isAsciiLetter(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '-') {
                if (i + 1 == name.length() || !isAsciiAlphanumeric(name.charAt(i + 1))) {
                    return false;
                }
            } else if (!isAsciiAlphanumeric(c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isAsciiAlphanumeric(char c) {
        return isAsciiLetter(c) || (c >= '0' && c <= '9');
    }

    /**
     * A Maven coordinate {@code groupId:artifactId:version}.
     *
     * @param groupId    the group
     * @param artifactId the artifact
     * @param version    the version
     */
    public record Coordinate(String groupId, String artifactId, String version) {

        /**
         * Parses {@code groupId:artifactId:version}.
         *
         * @param peer the peer the coordinate belongs to, for the refusal
         * @param text the coordinate text
         * @return the coordinate
         * @throws PeerContractException when it does not have exactly three non-blank parts
         */
        public static Coordinate parse(String peer, String text) {
            String[] parts = text.trim().split(":", -1);
            if (parts.length != 3 || java.util.Arrays.stream(parts).anyMatch(String::isBlank)) {
                throw new PeerContractException(peer, "'" + text + "' is not a coordinate of the form "
                        + "groupId:artifactId:version");
            }
            return new Coordinate(parts[0], parts[1], parts[2]);
        }

        @Override
        public String toString() {
            return groupId + ":" + artifactId + ":" + version;
        }
    }
}
