package eu.exeris.e2e.examples;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Locates the SDK's example sources ({@code exeris-sdk-examples}) at the tag of the SDK version
 * this build compiles against.
 *
 * <p>The examples module is not published, so it is read from an {@code exeris-sdk} checkout. Two
 * sources, in order:
 * <ol>
 *   <li>the directory named by {@value #DIR_PROPERTY} (system property) or {@value #DIR_ENV}
 *       (environment): an {@code exeris-sdk} checkout of tag {@code v<version>}. Naming a
 *       directory that is not that checkout is an error, never a skip;</li>
 *   <li>an {@code exeris-sdk} clone beside this repository's checkout that carries the tag: the
 *       module is exported from the tag with {@code git archive}, so the clone's working tree may
 *       be on any branch.</li>
 * </ol>
 *
 * <p>The version is read from the {@code exeris-sdk-annotations} jar on the test classpath, so the
 * examples are always the ones written against the SDK this build resolves.
 */
final class SdkExamplesSource {

    /** System property naming an {@code exeris-sdk} checkout at the pinned tag. */
    static final String DIR_PROPERTY = "exeris.sdk.examples.dir";

    /** Environment variable equivalent of {@link #DIR_PROPERTY}. */
    static final String DIR_ENV = "EXERIS_SDK_EXAMPLES_DIR";

    /** The examples module inside an {@code exeris-sdk} checkout. */
    static final String MODULE = "exeris-sdk-examples";

    /** The source root of the examples module, relative to the checkout. */
    static final String SOURCE_ROOT = MODULE + "/src/main/java";

    /** The package every scenario lives under, as a path below {@link #SOURCE_ROOT}. */
    static final String SCENARIO_PACKAGE = "eu/exeris/sdk/examples";

    private static final String ANNOTATIONS_POM_PROPERTIES =
            "META-INF/maven/eu.exeris/exeris-sdk-annotations/pom.properties";

    private SdkExamplesSource() {
    }

    /**
     * The SDK version on the test classpath, which names the tag the examples are read from.
     *
     * @return the version, for example {@code 0.13.2}
     */
    static String pinnedVersion() {
        try (InputStream in = SdkExamplesSource.class.getClassLoader()
                .getResourceAsStream(ANNOTATIONS_POM_PROPERTIES)) {
            if (in == null) {
                throw new IllegalStateException("exeris-sdk-annotations carries no " + ANNOTATIONS_POM_PROPERTIES);
            }
            Properties properties = new Properties();
            properties.load(in);
            return properties.getProperty("version");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Resolves the examples' source root ({@value #SOURCE_ROOT}) for {@code version}.
     *
     * @param version the SDK version whose tag is wanted
     * @param scratch an empty directory a tag export may be written to
     * @param why     receives one line per source that was looked at and not used
     * @return the source root, or empty when no source carries the tag
     * @throws IllegalStateException if a configured directory is missing or not at the tag
     */
    static Optional<Path> resolve(String version, Path scratch, List<String> why) throws IOException {
        String configured = System.getProperty(DIR_PROPERTY, System.getenv(DIR_ENV));
        if (configured != null && !configured.isBlank()) {
            Path checkout = Path.of(configured).toAbsolutePath().normalize();
            requireCheckoutAt(checkout, version);
            return Optional.of(checkout.resolve(SOURCE_ROOT));
        }
        why.add(DIR_PROPERTY + " / " + DIR_ENV + " is not set");
        for (Path clone : siblingClones()) {
            if (!Files.isDirectory(clone)) {
                why.add(clone + " does not exist");
                continue;
            }
            if (!hasTag(clone, "v" + version)) {
                why.add(clone + " has no tag v" + version + " (git -C " + clone + " fetch --tags)");
                continue;
            }
            exportTag(clone, "v" + version, scratch);
            requireCheckoutAt(scratch, version);
            return Optional.of(scratch.resolve(SOURCE_ROOT));
        }
        return Optional.empty();
    }

    /**
     * Fails unless {@code checkout} holds the examples module of {@code version}: its POM names the
     * SDK parent at that version.
     */
    private static void requireCheckoutAt(Path checkout, String version) throws IOException {
        Path pom = checkout.resolve(MODULE).resolve("pom.xml");
        if (!Files.isRegularFile(pom) || !Files.isDirectory(checkout.resolve(SOURCE_ROOT))) {
            throw new IllegalStateException(checkout + " is not an exeris-sdk checkout carrying " + MODULE
                    + " (no " + pom + " or no " + SOURCE_ROOT + ")");
        }
        String text = Files.readString(pom, StandardCharsets.UTF_8);
        String parentVersion = text.substring(text.indexOf("<parent>"), text.indexOf("</parent>"));
        if (!parentVersion.contains("<version>" + version + "</version>")) {
            throw new IllegalStateException(checkout + " is not at tag v" + version
                    + ": its " + MODULE + "/pom.xml names a different SDK version");
        }
    }

    /**
     * The places an {@code exeris-sdk} clone is looked for: beside this checkout, and beside the
     * main checkout when this one is a linked worktree.
     */
    private static List<Path> siblingClones() {
        Set<Path> clones = new LinkedHashSet<>();
        Path repoRoot = Path.of("").toAbsolutePath().getParent();
        clones.add(repoRoot.resolveSibling("exeris-sdk"));
        git(repoRoot, "rev-parse", "--path-format=absolute", "--git-common-dir")
                .map(commonDir -> Path.of(commonDir).getParent())
                .ifPresent(mainCheckout -> clones.add(mainCheckout.resolveSibling("exeris-sdk")));
        return new ArrayList<>(clones);
    }

    private static boolean hasTag(Path clone, String tag) {
        return git(clone, "rev-parse", "-q", "--verify", "refs/tags/" + tag + "^{commit}").isPresent();
    }

    /** Writes the examples module of {@code tag} under {@code target}, with the checkout's layout. */
    private static void exportTag(Path clone, String tag, Path target) throws IOException {
        Path zip = Files.createTempFile("sdk-examples-", ".zip");
        try {
            if (git(clone, "archive", "--format=zip", "-o", zip.toString(), tag, "--",
                    MODULE + "/pom.xml", SOURCE_ROOT).isEmpty()) {
                throw new IllegalStateException("git archive " + tag + " failed in " + clone);
            }
            unzip(zip, target);
        } finally {
            Files.deleteIfExists(zip);
        }
    }

    private static void unzip(Path zip, Path target) throws IOException {
        Path root = target.toAbsolutePath().normalize();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                Path file = root.resolve(entry.getName()).normalize();
                if (!file.startsWith(root)) {
                    throw new IOException("archive entry outside the target: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(file);
                } else {
                    Files.createDirectories(file.getParent());
                    Files.copy(in, file);
                }
            }
        }
    }

    /**
     * Runs git in {@code dir}; the trimmed standard output when it exits 0, otherwise empty. Its
     * diagnostics are discarded: {@link #resolve} records its own reason for each source it skips.
     */
    private static Optional<String> git(Path dir, String... args) {
        List<String> command = new ArrayList<>(List.of("git", "-C", dir.toString()));
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                return Optional.empty();
            }
            return Optional.of(out);
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
