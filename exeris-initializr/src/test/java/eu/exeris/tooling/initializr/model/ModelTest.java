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
        assertThat(file.toString()).isEqualTo("ScaffoldFile[path=a/b, size=2, executable=false]");
        assertThat(ScaffoldFile.script("run.sh", "x").executable()).isTrue();
    }

    @Test
    @DisplayName("a tree refuses two files at one path, and finds a file by path")
    void projectTree() {
        ScaffoldFile a = ScaffoldFile.text("a", "1");
        assertThatThrownBy(() -> new ProjectTree(List.of(a, ScaffoldFile.text("a", "2"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("a");
        ProjectTree tree = new ProjectTree(List.of(a));
        assertThat(tree.find("a")).contains(a);
        assertThat(tree.find("b")).isEmpty();
        assertThat(tree.paths()).containsExactly("a");
    }
}
