package eu.exeris.tooling.initializr.engine;

import eu.exeris.tooling.initializr.model.DatabaseType;
import eu.exeris.tooling.initializr.model.ExerisCapability;
import eu.exeris.tooling.initializr.model.ProjectScaffoldRequest;
import eu.exeris.tooling.initializr.model.ProjectTopology;
import eu.exeris.tooling.initializr.model.ProjectTree;
import eu.exeris.tooling.initializr.model.ScaffoldFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectGeneratorTest {

    @Test
    @DisplayName("E2E project scaffolding creates full monorepo with backend, frontend, and presentation view")
    void e2eScaffolding() {
        ProjectScaffoldRequest request = ProjectScaffoldRequest.builder()
                .name("Inventory Hub")
                .slug("inventory-hub")
                .groupId("eu.exeris.demo")
                .artifactId("inventory-hub")
                .packageName("eu.exeris.demo.inventory")
                .topology(ProjectTopology.E2E)
                .database(DatabaseType.POSTGRESQL)
                .capabilities(List.of(ExerisCapability.AUDIT_TRAIL, ExerisCapability.JWT_VALIDATION))
                .entityName("Item")
                .build();

        ProjectTree tree = ProjectGenerator.generate(request);
        assertThat(tree.size()).isGreaterThanOrEqualTo(12);

        // Root files
        assertThat(tree.hasFile("README.md")).isTrue();
        assertThat(tree.hasFile(".gitignore")).isTrue();
        assertThat(tree.hasFile("docker-compose.yml")).isTrue();

        // Backend files
        assertThat(tree.hasFile("backend/pom.xml")).isTrue();
        assertThat(tree.hasFile("backend/src/main/resources/application.properties")).isTrue();
        assertThat(tree.hasFile("backend/src/main/java/eu/exeris/demo/inventory/domain/Item.java")).isTrue();
        assertThat(tree.hasFile("backend/src/main/java/eu/exeris/demo/inventory/view/ItemDashboardView.java")).isTrue();
        assertThat(tree.hasFile("backend/src/main/java/eu/exeris/demo/inventory/caps/ApplicationModule.java")).isTrue();
        assertThat(tree.hasFile("backend/src/test/java/eu/exeris/demo/inventory/ItemE2ETest.java")).isTrue();

        // Frontend files
        assertThat(tree.hasFile("frontend/package.json")).isTrue();
        assertThat(tree.hasFile("frontend/proxy.conf.json")).isTrue();
        assertThat(tree.hasFile("frontend/tsconfig.json")).isTrue();
        assertThat(tree.hasFile("frontend/src/main.ts")).isTrue();
        assertThat(tree.hasFile("frontend/src/app/app.component.ts")).isTrue();
        assertThat(tree.hasFile("frontend/src/app/components/item-list.component.ts")).isTrue();

        // Inspect backend pom.xml
        ScaffoldFile pom = tree.find("backend/pom.xml").orElseThrow();
        String pomContent = pom.contentAsString();
        assertThat(pomContent).contains("exeris-app-starter");
        assertThat(pomContent).contains("exeris-caps-audit-trail");
        assertThat(pomContent).contains("exeris-caps-jwt-validation");
        assertThat(pomContent).contains("postgresql");

        // Inspect domain Item.java
        ScaffoldFile entity = tree.find("backend/src/main/java/eu/exeris/demo/inventory/domain/Item.java").orElseThrow();
        String entityContent = entity.contentAsString();
        assertThat(entityContent).contains("@ExerisDomain(module = \"inventory\", path = \"/items\")");
        assertThat(entityContent).contains("@UI(");
        assertThat(entityContent).contains("@Field(label = \"Title\"");
        assertThat(entityContent).contains("@Validation(minLength = 3");
        assertThat(entityContent).contains("public class Item");

        // Inspect ItemDashboardView.java
        ScaffoldFile view = tree.find("backend/src/main/java/eu/exeris/demo/inventory/view/ItemDashboardView.java").orElseThrow();
        String viewContent = view.contentAsString();
        assertThat(viewContent).contains("@View(");
        assertThat(viewContent).contains("name = \"ItemDashboard\"");
        assertThat(viewContent).contains("@Region(slot = \"header\")");
        assertThat(viewContent).contains("@Block(type = Block.BlockType.HERO)");

        // Inspect frontend package.json
        ScaffoldFile pkgJson = tree.find("frontend/package.json").orElseThrow();
        assertThat(pkgJson.contentAsString()).contains("@angular/core");
        assertThat(pkgJson.contentAsString()).contains("@exeris/codegen-ts");
        assertThat(pkgJson.contentAsString()).contains("tailwindcss");
    }

    @Test
    @DisplayName("Backend only scaffolding generates clean microservice layout")
    void backendOnlyScaffolding() {
        ProjectScaffoldRequest request = ProjectScaffoldRequest.builder()
                .name("Order Service")
                .slug("order-service")
                .groupId("eu.exeris.order")
                .artifactId("order-service")
                .packageName("eu.exeris.order")
                .topology(ProjectTopology.BACKEND_ONLY)
                .database(DatabaseType.H2)
                .entityName("Order")
                .build();

        ProjectTree tree = ProjectGenerator.generate(request);

        assertThat(tree.hasFile("pom.xml")).isTrue();
        assertThat(tree.hasFile("src/main/java/eu/exeris/order/domain/Order.java")).isTrue();
        assertThat(tree.hasFile("src/test/java/eu/exeris/order/OrderE2ETest.java")).isTrue();
        assertThat(tree.hasFile("README.md")).isTrue();
        assertThat(tree.hasFile("frontend/package.json")).isFalse();
    }

    @Test
    @DisplayName("Frontend only scaffolding generates Angular 22 app with codegen tooling")
    void frontendOnlyScaffolding() {
        ProjectScaffoldRequest request = ProjectScaffoldRequest.builder()
                .name("Order Console")
                .slug("order-console")
                .groupId("eu.exeris.order")
                .artifactId("order-console")
                .packageName("eu.exeris.order")
                .topology(ProjectTopology.FRONTEND_ONLY)
                .database(DatabaseType.H2)
                .entityName("Order")
                .build();

        ProjectTree tree = ProjectGenerator.generate(request);

        assertThat(tree.hasFile("package.json")).isTrue();
        assertThat(tree.hasFile("proxy.conf.json")).isTrue();
        assertThat(tree.hasFile("src/main.ts")).isTrue();
        assertThat(tree.hasFile("pom.xml")).isFalse();
    }
}
