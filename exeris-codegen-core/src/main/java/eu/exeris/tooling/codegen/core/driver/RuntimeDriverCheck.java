package eu.exeris.tooling.codegen.core.driver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads a runtime classpath and reports which kernel service-provider registrations are
 * absent from it (T50, ADR-078).
 *
 * <h2>Why a resource scan and not a class load</h2>
 * Kernel providers are discovered at boot by {@link java.util.ServiceLoader}, so a provider
 * is present exactly when some classpath element carries the matching
 * {@code META-INF/services/<spi>} entry. That is a file lookup — no consumer class is
 * loaded, nothing is instantiated, and the check adds no dependency of its own (JDK
 * {@code java.nio.file} and {@code java.util.zip} only, the same self-imposed floor
 * {@code CapTierWall} keeps for its class-file scan).
 *
 * <h2>What a pass proves, and what it does not</h2>
 * A pass proves each required SPI has <em>at least one</em> registered implementation on the
 * runtime classpath. It does <b>not</b> prove that implementation supplies a particular
 * subsystem name, satisfies a version range, or starts successfully — answering any of those
 * means running the provider, which a build-time gate deliberately does not do.
 *
 * <p>The check is nonetheless non-vacuous, and that is measurable rather than assumed:
 * {@code exeris-kernel-core} registers <b>no</b> {@code META-INF/services} entries at all in
 * its main artefact, so an application built against SPI + Core alone — the exact shape T50
 * describes — fails here rather than at boot.
 *
 * @since 0.8.0
 */
public final class RuntimeDriverCheck {

    /** Where a {@link java.util.ServiceLoader} registration lives inside a classpath element. */
    private static final String SERVICES = "META-INF/services/";

    private RuntimeDriverCheck() {
    }

    /**
     * The verdict: what was required, which of it had no registration anywhere on the
     * classpath, and how many elements were examined.
     *
     * @param required every SPI interface name the scan looked for, in request order
     * @param missing  the subset of {@code required} with no provider registered, in the same
     *                 order; empty when every one was found
     * @param scanned  how many classpath elements were readable and examined — carried so a
     *                 "nothing missing" verdict over an empty classpath cannot read as a pass
     */
    public record Result(List<String> required, List<String> missing, int scanned) {

        public Result {
            required = List.copyOf(required);
            missing = List.copyOf(missing);
        }

        public boolean satisfied() {
            return missing.isEmpty();
        }

        /** True when nothing was required — no emitted application, so no driver to demand. */
        public boolean vacuous() {
            return required.isEmpty();
        }
    }

    /**
     * Scans {@code classpath} for a {@code META-INF/services} registration of each entry in
     * {@code requiredSpis}.
     *
     * <p>An element that cannot be read — a jar that is not a zip, a path that vanished
     * between resolution and this call — is skipped rather than fatal: the goal is to report
     * a missing driver, and turning an unreadable third-party jar into a build failure would
     * report something else entirely. Skipped elements do not count towards {@code scanned}.
     *
     * @param classpath    runtime classpath elements — jars and compiled-output directories
     * @param requiredSpis fully-qualified SPI interface names; never null
     * @return the verdict; never null
     */
    public static Result scan(List<Path> classpath, Set<String> requiredSpis) {
        Set<String> found = new LinkedHashSet<>();
        int scanned = 0;
        for (Path element : classpath) {
            if (!Files.exists(element)) {
                continue;
            }
            try {
                if (Files.isDirectory(element)) {
                    collectFromDirectory(element, requiredSpis, found);
                } else {
                    collectFromArchive(element, requiredSpis, found);
                }
                scanned++;
            } catch (IOException | UncheckedIOException e) {
                // Unreadable element — see the method javadoc. Deliberately silent at this
                // layer: the caller owns diagnostics, and this class owns no logger.
            }
            if (found.size() == requiredSpis.size()) {
                // Every SPI accounted for; the rest of the classpath cannot change the verdict.
                break;
            }
        }
        List<String> missing = new ArrayList<>();
        for (String spi : requiredSpis) {
            if (!found.contains(spi)) {
                missing.add(spi);
            }
        }
        return new Result(List.copyOf(requiredSpis), missing, scanned);
    }

    private static void collectFromDirectory(Path root, Set<String> requiredSpis, Set<String> found)
            throws IOException {
        Path services = root.resolve(SERVICES);
        if (!Files.isDirectory(services)) {
            return;
        }
        for (String spi : requiredSpis) {
            if (!found.contains(spi) && isNonEmptyFile(services.resolve(spi))) {
                found.add(spi);
            }
        }
    }

    private static void collectFromArchive(Path archive, Set<String> requiredSpis, Set<String> found)
            throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            for (String spi : requiredSpis) {
                if (!found.contains(spi) && registers(zip.getEntry(SERVICES + spi))) {
                    found.add(spi);
                }
            }
        }
    }

    /**
     * A zero-byte service file registers nothing — {@code ServiceLoader} reads it and yields no
     * implementations — so it must not count as a provider, in a jar exactly as in a directory.
     *
     * <p>{@code ZipFile} reads the central directory, so {@code getSize()} is normally known
     * here; the {@code -1} branch is the case where it is not, and it resolves to "present".
     * A missing driver reported wrongly is a build this gate broke for no reason, which is a
     * worse failure than the one it exists to catch — so an unknown size fails open.
     */
    private static boolean registers(ZipEntry entry) {
        return entry != null && entry.getSize() != 0L;
    }

    /** The directory half of {@link #registers(ZipEntry)} — same rule, different carrier. */
    private static boolean isNonEmptyFile(Path candidate) throws IOException {
        return Files.isRegularFile(candidate) && Files.size(candidate) > 0L;
    }
}
