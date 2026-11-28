package eu.exeris.tooling.initializr.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelTest {

    private static ProjectScaffoldRequest request(String groupId, String artifactId, String pkg, String name,
                                                  String entity) {
        return new ProjectScaffoldRequest(groupId, artifactId, pkg, name, ProjectTopology.BACKEND_ONLY,
                DatabaseType.H2, entity);
    }

    @Test
    @DisplayName("derives the package, the name and the entity when they are not given")
    void defaults() {
        ProjectScaffoldRequest request = request("com.example", "order-service", null, null, null);
        assertThat(request.packageName()).isEqualTo("com.example.order.service");
        assertThat(request.name()).isEqualTo("order-service");
        assertThat(request.entityName()).isEqualTo("Item");
    }

    @Test
    @DisplayName("derives a valid package from coordinates with digits, keywords and other characters")
    void derivePackage() {
        assertThat(ProjectScaffoldRequest.derivePackage("Com.Example", "2fa-int.App_x")).isEqualTo("com.example._2fa.int_.app_x");
        assertThat(ProjectScaffoldRequest.derivePackage("org..x", "-")).isEqualTo("org.x");
    }

    @ParameterizedTest
    @ValueSource(strings = {"com example", "com/example", "", "com.ex$ample"})
    @DisplayName("refuses a groupId with characters Maven does not allow")
    void refusesGroupId(String groupId) {
        assertThatThrownBy(() -> request(groupId, "demo", "com.example", null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("groupId");
    }

    @ParameterizedTest
    @ValueSource(strings = {".", "..", "...."})
    @DisplayName("refuses an artifactId or groupId made only of dots, which name a directory")
    void refusesDotsOnly(String value) {
        assertThatThrownBy(() -> request("com.example", value, "com.example", null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("artifactId");
        assertThatThrownBy(() -> request(value, "demo", "com.example", null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("groupId");
    }

    @Test
    @DisplayName("refuses an artifactId with characters Maven does not allow")
    void refusesArtifactId() {
        assertThatThrownBy(() -> request("com.example", "my app", null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("artifactId");
    }

    @ParameterizedTest
    @ValueSource(strings = {"com.example.class", "1com", "com..example", "com.ex-ample"})
    @DisplayName("refuses a package that is not a Java package name")
    void refusesPackage(String pkg) {
        assertThatThrownBy(() -> request("com.example", "demo", pkg, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("package");
    }

    @ParameterizedTest
    @ValueSource(strings = {"item", "Item$", "Ítem", "1Item", "Order Line"})
    @DisplayName("refuses an entity that is not an upper-case ASCII type name")
    void refusesEntity(String entity) {
        assertThatThrownBy(() -> request("com.example", "demo", null, null, entity))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("entity");
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "two\nlines"})
    @DisplayName("refuses a name that is blank or not one line")
    void refusesName(String name) {
        assertThatThrownBy(() -> request("com.example", "demo", null, name, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("name");
    }

    @ParameterizedTest
    @ValueSource(strings = {"UUID", "Field", "ExerisDomain", "String"})
    @DisplayName("refuses an entity named like a type the entity template refers to, and lists them")
    void refusesReservedEntity(String entity) {
        assertThat(ProjectScaffoldRequest.RESERVED_ENTITY_NAMES).contains(entity);
        assertThatThrownBy(() -> request("com.example", "demo", null, null, entity))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("entity")
                .hasMessageContaining("UUID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Con", "Nul", "Aux", "Prn", "Com1", "Lpt9", "CON"})
    @DisplayName("refuses an entity that is a Windows device name, whatever its case")
    void refusesDeviceEntity(String entity) {
        assertThatThrownBy(() -> request("com.example", "demo", null, null, entity))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Windows device");
    }

    @ParameterizedTest
    @ValueSource(strings = {"java", "java.demo", "java.lang"})
    @DisplayName("refuses a package that starts with the segment java")
    void refusesJavaPackage(String pkg) {
        assertThatThrownBy(() -> request("com.example", "demo", pkg, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'java'");
    }

    @Test
    @DisplayName("refuses a groupId that derives a package under java, and accepts a package that only begins with the letters")
    void javaGroupId() {
        assertThatThrownBy(() -> request("java", "demo", null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'java'");
        assertThat(request("com.example", "demo", "javax.demo", null, null).packageName()).isEqualTo("javax.demo");
        assertThat(request("com.example", "demo", "javafx.demo", null, null).packageName()).isEqualTo("javafx.demo");
    }

    @ParameterizedTest
    @ValueSource(strings = {"com.con.x", "a.NUL", "com1.demo", "LPT3"})
    @DisplayName("refuses a package with a Windows device name as a segment, whatever its case")
    void refusesDevicePackage(String pkg) {
        assertThatThrownBy(() -> request("com.example", "demo", pkg, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Windows device");
    }

    @ParameterizedTest
    @ValueSource(strings = {"con", "NUL", "COM1", "Aux.txt", "lpt1"})
    @DisplayName("refuses an artifactId or groupId that is a Windows device name, whatever its case")
    void refusesDeviceMavenId(String value) {
        assertThatThrownBy(() -> request("com.example", value, "com.example", null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("artifactId");
        assertThatThrownBy(() -> request(value, "demo", "com.example", null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("groupId");
    }

    @ParameterizedTest
    @ValueSource(strings = {"console", "com10", "nulls", "my-con"})
    @DisplayName("accepts an artifactId that only contains a device name")
    void acceptsNearDeviceName(String value) {
        assertThat(request("com.example", value, "com.example", null, null).artifactId()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"--version", "-rf", "_app", ".hidden"})
    @DisplayName("refuses an artifactId that does not start with a letter or a digit")
    void refusesArtifactIdStart(String value) {
        assertThatThrownBy(() -> request("com.example", value, "com.example", null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("artifactId");
    }

    @ParameterizedTest
    @ValueSource(strings = {"demo.", "a..", "a.b."})
    @DisplayName("refuses an artifactId that ends in a dot")
    void refusesArtifactIdTrailingDot(String value) {
        assertThatThrownBy(() -> request("com.example", value, "com.example", null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("artifactId")
                .hasMessageContaining("dot");
        assertThat(request("com.example", "a.b", "com.example", null, null).artifactId()).isEqualTo("a.b");
    }

    @ParameterizedTest
    @ValueSource(strings = {"com.$x", "com.a$b", "$"})
    @DisplayName("refuses a package that holds a dollar sign")
    void refusesDollarPackage(String pkg) {
        assertThatThrownBy(() -> request("com.example", "demo", pkg, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'$'");
    }

    @Test
    @DisplayName("bounds the artifactId so its npm name stays within 214 characters")
    void boundsArtifactId() {
        int max = ProjectScaffoldRequest.MAX_ARTIFACT_ID_LENGTH;
        assertThat(request("com.example", "a".repeat(max), "com.example", null, null).artifactId()).hasSize(max);
        assertThatThrownBy(() -> request("com.example", "a".repeat(max + 1), "com.example", null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("artifactId");
        assertThat(max + "-frontend".length()).isLessThanOrEqualTo(214);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\uFFFEb", "a\uFFFFb", "a\u2028b", "a\u2029b", "a\u202Eb", "a\u2066b", "a\uD800b", "a\u0000b"})
    @DisplayName("refuses a name with a character XML 1.0 forbids, a line separator or a bidirectional override")
    void refusesNameCharacters(String name) {
        assertThatThrownBy(() -> request("com.example", "demo", null, name, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("name");
    }

    @Test
    @DisplayName("accepts a name with non-ASCII letters and a supplementary character")
    void acceptsUnicodeName() {
        assertThat(request("com.example", "demo", null, "Zażółć \uD83D\uDE00", null).name()).isEqualTo("Zażółć \uD83D\uDE00");
    }

    @Test
    @DisplayName("finds topologies and databases by their exact command-line value")
    void cliValues() {
        for (ProjectTopology topology : ProjectTopology.values()) {
            assertThat(ProjectTopology.fromCliValue(topology.cliValue())).contains(topology);
        }
        for (DatabaseType database : DatabaseType.values()) {
            assertThat(DatabaseType.fromCliValue(database.cliValue())).contains(database);
        }
        assertThat(ProjectTopology.fromCliValue("E2E")).isEmpty();
        assertThat(DatabaseType.fromCliValue("H2")).isEmpty();
        assertThat(ProjectTopology.fromCliValue("frontend-only")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/abs", "a//b", "a/../b", "./a", "a\\b", "a/", ""})
    @DisplayName("refuses a file path that is absolute or has an empty, '.' or '..' segment")
    void refusesPath(String path) {
        assertThatThrownBy(() -> ScaffoldFile.text(path, "x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a file keeps a copy of its bytes, compares by content and describes itself without it")
    void scaffoldFile() {
        byte[] bytes = {1, 2};
        ScaffoldFile file = new ScaffoldFile("a/b", bytes, false);
        bytes[0] = 9;
        file.content()[1] = 9;
        assertThat(file.content()).containsExactly(1, 2);
        assertThat(file).isEqualTo(new ScaffoldFile("a/b", new byte[] {1, 2}, false))
                .hasSameHashCodeAs(new ScaffoldFile("a/b", new byte[] {1, 2}, false))
                .isNotEqualTo(new ScaffoldFile("a/b", new byte[] {1, 2}, true))
                .isNotEqualTo(new ScaffoldFile("a/c", new byte[] {1, 2}, false))
                .isNotEqualTo("a/b");
        assertThat(file).hasToString("ScaffoldFile[path=a/b, size=2, executable=false]");
        assertThat(ScaffoldFile.script("run.sh", "x").executable()).isTrue();
    }

    @Test
    @DisplayName("a tree refuses two files at one path, and finds a file by path")
    void projectTree() {
        ScaffoldFile a = ScaffoldFile.text("a", "1");
        List<ScaffoldFile> twice = List.of(a, ScaffoldFile.text("a", "2"));
        assertThatThrownBy(() -> new ProjectTree(twice))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("a");
        ProjectTree tree = new ProjectTree(List.of(a));
        assertThat(tree.find("a")).contains(a);
        assertThat(tree.find("b")).isEmpty();
        assertThat(tree.paths()).containsExactly("a");
    }
}
