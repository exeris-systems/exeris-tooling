package eu.exeris.tooling.codegen.java.dsl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PageDslGenerator")
class PageDslGeneratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir Path tempDir;

    @Test
    @DisplayName("Constructor rejects null metadata")
    void constructorRejectsNullMetadata() {
        assertThatThrownBy(() -> new PageDslGenerator(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("metadata cannot be null");
    }

    @Test
    @DisplayName("generateListPage emits the list-layout page with breadcrumbs and table $ref")
    void listPageBaseShape() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .build();

        JsonNode page = MAPPER.readTree(new PageDslGenerator(meta).generateListPage());

        assertThat(page.get("$type").asText()).isEqualTo("page");
        assertThat(page.get("id").asText()).isEqualTo("order-list");
        // Title comes from DomainMetadata.pluralName(), which is derived
        // from entityName ("Order" → "Orders"). pluralName() never returns
        // null in the current DomainMetadata contract, so the !=null
        // fallback branch in buildListPage is unreachable from this API.
        assertThat(page.get("title").asText()).isEqualTo("Orders");
        assertThat(page.get("entity").asText()).isEqualTo("Order");
        assertThat(page.get("layout").asText()).isEqualTo("list");

        JsonNode breadcrumbs = page.get("breadcrumbs");
        assertThat(breadcrumbs).hasSize(2);
        assertThat(breadcrumbs.get(0).get("label").asText()).isEqualTo("Home");
        assertThat(breadcrumbs.get(1).get("label").asText()).isEqualTo("Order");
        assertThat(breadcrumbs.get(1).get("path").asText()).isEqualTo("/orders");

        JsonNode components = page.get("components");
        assertThat(components.get(0).get("$ref").asText()).isEqualTo("order.table.json");
    }

    @Test
    @DisplayName("generateDetailPage emits the detail-layout page with edit/delete header actions and audit section")
    void detailPageBaseShape() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .build();

        JsonNode page = MAPPER.readTree(new PageDslGenerator(meta).generateDetailPage());

        assertThat(page.get("$type").asText()).isEqualTo("page");
        assertThat(page.get("id").asText()).isEqualTo("order-detail");
        assertThat(page.get("title").asText()).isEqualTo("Order Details");
        assertThat(page.get("layout").asText()).isEqualTo("detail");

        JsonNode crumbs = page.get("breadcrumbs");
        assertThat(crumbs).hasSize(3);
        assertThat(crumbs.get(2).get("label").asText()).isEqualTo("{name}");
        assertThat(crumbs.get(2).get("path").asText()).isEqualTo("/orders/{id}");

        // Header actions: edit + delete (always).
        JsonNode actions = page.get("headerActions");
        assertThat(actions).hasSize(2);
        assertThat(actions.get(0).get("name").asText()).isEqualTo("edit");
        assertThat(actions.get(0).get("type").asText()).isEqualTo("primary");
        assertThat(actions.get(0).get("target").asText()).isEqualTo("/orders/{id}/edit");
        assertThat(actions.get(1).get("name").asText()).isEqualTo("delete");
        assertThat(actions.get(1).get("type").asText()).isEqualTo("danger");
        assertThat(actions.get(1).get("confirm").asBoolean()).isTrue();

        // Sections: schema $ref + audit info.
        JsonNode sections = page.get("sections");
        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).get("$ref").asText()).isEqualTo("order.schema.json");
        assertThat(sections.get(1).get("title").asText()).isEqualTo("Audit Information");
        assertThat(sections.get(1).get("collapsed").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("Domain actions: dangerous → \"danger\" type, safe → \"secondary\", requiresConfirmation toggles confirm")
    void detailPageDomainActions() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .actions(List.of(
                        ActionMetadata.builder("approve")
                                .httpMethod("POST")
                                .requiresConfirmation(true)
                                .build(),
                        ActionMetadata.builder("cancel")
                                .httpMethod("DELETE")
                                .dangerous(true)
                                .build(),
                        ActionMetadata.builder("inspect")
                                .httpMethod("GET")
                                .build()))
                .build();

        JsonNode page = MAPPER.readTree(new PageDslGenerator(meta).generateDetailPage());
        JsonNode actions = page.get("headerActions");

        // 2 always-present + 3 domain.
        assertThat(actions).hasSize(5);

        JsonNode approve = actions.get(2);
        assertThat(approve.get("name").asText()).isEqualTo("approve");
        assertThat(approve.get("type").asText()).isEqualTo("secondary");
        assertThat(approve.get("method").asText()).isEqualTo("POST");
        assertThat(approve.get("endpoint").asText()).isEqualTo("/orders/{id}/actions/approve");
        assertThat(approve.get("confirm").asBoolean()).isTrue();

        JsonNode cancel = actions.get(3);
        assertThat(cancel.get("type").asText()).isEqualTo("danger");
        // dangerous && !requiresConfirmation → no confirm key emitted.
        assertThat(cancel.has("confirm")).isFalse();

        JsonNode inspect = actions.get(4);
        // Safe + no-confirm action → "secondary" type, no confirm key.
        assertThat(inspect.get("type").asText()).isEqualTo("secondary");
        assertThat(inspect.has("confirm")).isFalse();
    }

    @Test
    @DisplayName("Action endpoint paths are kebab-cased from camelCase names")
    void actionEndpointsKebabCased() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .actions(List.of(
                        ActionMetadata.builder("approveOrder").httpMethod("POST").build(),
                        ActionMetadata.builder("requestRefund").httpMethod("POST").build()))
                .build();

        JsonNode actions = MAPPER.readTree(new PageDslGenerator(meta).generateDetailPage())
                .get("headerActions");

        assertThat(actions.get(2).get("endpoint").asText())
                .isEqualTo("/orders/{id}/actions/approve-order");
        assertThat(actions.get(3).get("endpoint").asText())
                .isEqualTo("/orders/{id}/actions/request-refund");
    }

    @Test
    @DisplayName("List-page title follows DomainMetadata.pluralName() for non-regular pluralisations (y→ies, sibilant→es)")
    void listPageTitleFollowsPluralName() throws IOException {
        // Category → Categories (y → ies branch), Branch → Branches (sh → es).
        DomainMetadata category = DomainMetadata.builder("Category", "com.example.domain").build();
        DomainMetadata branch = DomainMetadata.builder("Branch", "com.example.domain").build();

        JsonNode categoryPage = MAPPER.readTree(new PageDslGenerator(category).generateListPage());
        JsonNode branchPage = MAPPER.readTree(new PageDslGenerator(branch).generateListPage());

        assertThat(categoryPage.get("title").asText()).isEqualTo("Categories");
        assertThat(branchPage.get("title").asText()).isEqualTo("Branches");
    }

    @Test
    @DisplayName("writeListPageTo / writeDetailPageTo create the directory and write the expected files")
    void writePageFiles() throws IOException {
        Path nested = tempDir.resolve("nested/pages");
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .build();
        PageDslGenerator gen = new PageDslGenerator(meta);

        gen.writeListPageTo(nested);
        gen.writeDetailPageTo(nested);

        assertThat(Files.exists(nested.resolve("order.list-page.json"))).isTrue();
        assertThat(Files.exists(nested.resolve("order.detail-page.json"))).isTrue();
        // Content sanity-check on one of them.
        JsonNode list = MAPPER.readTree(Files.readString(nested.resolve("order.list-page.json")));
        assertThat(list.get("layout").asText()).isEqualTo("list");
    }
}
