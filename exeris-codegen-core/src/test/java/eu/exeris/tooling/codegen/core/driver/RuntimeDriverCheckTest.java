package eu.exeris.tooling.codegen.core.driver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RuntimeDriverCheck")
class RuntimeDriverCheckTest {

    private static final String SUBSYSTEM = RequiredDrivers.SUBSYSTEM_PROVIDER;
    private static final String PERSISTENCE = RequiredDrivers.PERSISTENCE_PROVIDER;

    @TempDir
    Path workspace;

    /** A classpath element in exploded form — what {@code target/classes} looks like. */
    private Path directoryElement(String name, String... registeredSpis) throws IOException {
        Path root = Files.createDirectories(workspace.resolve(name));
        Path services = Files.createDirectories(root.resolve("META-INF/services"));
        for (String spi : registeredSpis) {
            Files.writeString(services.resolve(spi), "com.example.Impl\n");
        }
        return root;
    }

    /** A classpath element as a jar — what a resolved dependency looks like. */
    private Path jarElement(String name, String... registeredSpis) throws IOException {
        Path jar = workspace.resolve(name);
        try (OutputStream out = Files.newOutputStream(jar);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String spi : registeredSpis) {
                zip.putNextEntry(new ZipEntry("META-INF/services/" + spi));
                zip.write("com.example.Impl\n".getBytes());
                zip.closeEntry();
            }
        }
        return jar;
    }

    @Test
    @DisplayName("a driver jar registering both SPIs satisfies the scan")
    void satisfiedByAJar() throws IOException {
        Path driver = jarElement("driver.jar", SUBSYSTEM, PERSISTENCE);

        RuntimeDriverCheck.Result result =
                RuntimeDriverCheck.scan(List.of(driver), Set.of(SUBSYSTEM, PERSISTENCE));

        assertThat(result.satisfied()).isTrue();
        assertThat(result.missing()).isEmpty();
        assertThat(result.scanned()).isEqualTo(1);
    }

    @Test
    @DisplayName("registrations may be spread across elements — the app's own classes dir counts too")
    void registrationsMayBeSpreadAcrossElements() throws IOException {
        Path appClasses = directoryElement("classes", PERSISTENCE);
        Path driver = jarElement("driver.jar", SUBSYSTEM);

        RuntimeDriverCheck.Result result =
                RuntimeDriverCheck.scan(List.of(appClasses, driver), Set.of(SUBSYSTEM, PERSISTENCE));

        assertThat(result.satisfied()).isTrue();
    }

    @Test
    @DisplayName("this is the T50 shape: a classpath with SPI and Core but no driver")
    void reportsEverySpiWhenNoDriverIsPresent() throws IOException {
        // The measured shape of exeris-kernel-core: a jar with classes and no META-INF/services
        // at all. It is why this gate is not vacuous — Core cannot satisfy it.
        Path core = jarElement("exeris-kernel-core.jar");

        RuntimeDriverCheck.Result result =
                RuntimeDriverCheck.scan(List.of(core), List.of(SUBSYSTEM, PERSISTENCE).stream()
                        .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)));

        assertThat(result.satisfied()).isFalse();
        assertThat(result.missing()).containsExactly(SUBSYSTEM, PERSISTENCE);
        assertThat(result.required()).containsExactly(SUBSYSTEM, PERSISTENCE);
        assertThat(result.scanned()).isEqualTo(1);
    }

    @Test
    @DisplayName("a partial driver set reports only what is missing, in request order")
    void reportsOnlyTheMissingHalf() throws IOException {
        Path partial = jarElement("partial.jar", SUBSYSTEM);

        RuntimeDriverCheck.Result result = RuntimeDriverCheck.scan(List.of(partial),
                new java.util.LinkedHashSet<>(List.of(SUBSYSTEM, PERSISTENCE)));

        assertThat(result.missing()).containsExactly(PERSISTENCE);
    }

    @Test
    @DisplayName("a zero-byte service file registers nothing and does not count")
    void emptyServiceFileIsNotAProvider() throws IOException {
        Path root = Files.createDirectories(workspace.resolve("empty-reg"));
        Files.createDirectories(root.resolve("META-INF/services"));
        Files.createFile(root.resolve("META-INF/services").resolve(SUBSYSTEM));

        RuntimeDriverCheck.Result result =
                RuntimeDriverCheck.scan(List.of(root), Set.of(SUBSYSTEM));

        assertThat(result.missing()).containsExactly(SUBSYSTEM);
    }

    @Test
    @DisplayName("an unreadable or absent element is skipped, not fatal, and is not counted")
    void unreadableElementsAreSkipped() throws IOException {
        Path notAJar = Files.writeString(workspace.resolve("broken.jar"), "this is not a zip");
        Path vanished = workspace.resolve("never-existed.jar");
        Path driver = jarElement("driver.jar", SUBSYSTEM);

        RuntimeDriverCheck.Result result =
                RuntimeDriverCheck.scan(List.of(notAJar, vanished, driver), Set.of(SUBSYSTEM));

        assertThat(result.satisfied()).isTrue();
        // driver.jar only: the broken one threw and the absent one never opened.
        assertThat(result.scanned()).isEqualTo(1);
    }

    @Test
    @DisplayName("an empty classpath is a miss, and scanned() says why it cannot be read as a pass")
    void emptyClasspathIsAMiss() {
        RuntimeDriverCheck.Result result = RuntimeDriverCheck.scan(List.of(), Set.of(SUBSYSTEM));

        assertThat(result.satisfied()).isFalse();
        assertThat(result.scanned()).isZero();
    }

    @Test
    @DisplayName("no required SPI is a vacuous verdict, distinct from a satisfied one")
    void noRequirementIsVacuous() {
        RuntimeDriverCheck.Result result = RuntimeDriverCheck.scan(List.of(), Set.of());

        assertThat(result.vacuous()).isTrue();
        assertThat(result.satisfied()).isTrue();
    }
}
