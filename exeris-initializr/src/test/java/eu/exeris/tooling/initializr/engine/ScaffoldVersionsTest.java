package eu.exeris.tooling.initializr.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScaffoldVersionsTest {

    private static final Path TOOLING_BOM = Path.of("..", "exeris-tooling-bom", "pom.xml");

    @Test
    @DisplayName("the bundled tooling version is this reactor's version, filled in by the build")
    void toolingIsTheReactorVersion() throws IOException {
        assertThat(ScaffoldVersions.bundled().tooling()).isEqualTo(reactorVersion());
    }

    @Test
    @DisplayName("H2, JUnit and AssertJ are the versions exeris-tooling-bom builds and tests with")
    void testedVersions() throws IOException {
        String bom = Files.readString(TOOLING_BOM, StandardCharsets.UTF_8);
        ScaffoldVersions versions = ScaffoldVersions.bundled();
        assertThat(versions.h2()).isEqualTo(property(bom, "h2.version"));
        assertThat(versions.junit()).isEqualTo(property(bom, "junit.version"));
        assertThat(versions.assertj()).isEqualTo(property(bom, "assertj.version"));
        assertThat(versions.postgresql()).matches("\\d+\\.\\d+\\.\\d+");
    }

    @Test
    @DisplayName("refuses a version that is blank or an unresolved placeholder")
    void refusesUnsetVersions() {
        assertThatThrownBy(() -> new ScaffoldVersions("${project.version}", "1", "1", "1", "1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tooling");
        assertThatThrownBy(() -> new ScaffoldVersions("1", " ", "1", "1", "1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("h2");
        assertThatThrownBy(() -> new ScaffoldVersions("1", "1", null, "1", "1"))
                .isInstanceOf(NullPointerException.class);
    }

    private static String reactorVersion() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        Matcher parent = Pattern.compile("<parent>.*?<version>([^<]+)</version>", Pattern.DOTALL).matcher(pom);
        assertThat(parent.find()).isTrue();
        return parent.group(1);
    }

    private static String property(String pom, String name) {
        Matcher m = Pattern.compile("<" + Pattern.quote(name) + ">([^<]+)</").matcher(pom);
        assertThat(m.find()).as(name + " in " + TOOLING_BOM).isTrue();
        return m.group(1);
    }
}
