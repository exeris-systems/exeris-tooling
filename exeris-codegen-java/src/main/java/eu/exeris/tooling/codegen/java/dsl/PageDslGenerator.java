package eu.exeris.tooling.codegen.java.dsl;

import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.tooling.codegen.java.support.NameCasing;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Generates page DSL JSON for Atom v3 UI.
 * @author Exeris Team
 * @since 0.1.0
 */
public final class PageDslGenerator {

    private final DomainMetadata metadata;
    private final ObjectMapper mapper;

    public PageDslGenerator(DomainMetadata metadata) {
        this.metadata = Objects.requireNonNull(metadata, "metadata cannot be null");
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public String generateListPage() throws IOException {
        return mapper.writeValueAsString(buildListPage());
    }

    public String generateDetailPage() throws IOException {
        return mapper.writeValueAsString(buildDetailPage());
    }

    public void writeListPageTo(Path outputPath) throws IOException {
        Files.createDirectories(outputPath);
        Files.writeString(outputPath.resolve(metadata.entityName().toLowerCase() + ".list-page.json"), generateListPage());
    }

    public void writeDetailPageTo(Path outputPath) throws IOException {
        Files.createDirectories(outputPath);
        Files.writeString(outputPath.resolve(metadata.entityName().toLowerCase() + ".detail-page.json"), generateDetailPage());
    }

    private Map<String, Object> buildListPage() {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("$type", "page");
        page.put("id", metadata.entityName().toLowerCase() + "-list");
        page.put("title", metadata.pluralName() != null ? metadata.pluralName() : metadata.entityName() + "s");
        page.put("entity", metadata.entityName());
        page.put("layout", "list");
        page.put("breadcrumbs", List.of(
            Map.of("label", "Home", "path", "/"),
            Map.of("label", metadata.entityName(), "path", metadata.effectivePath())
        ));
        page.put("components", List.of(Map.of("$ref", metadata.entityName().toLowerCase() + ".table.json")));
        return page;
    }

    private Map<String, Object> buildDetailPage() {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("$type", "page");
        page.put("id", metadata.entityName().toLowerCase() + "-detail");
        page.put("title", metadata.entityName() + " Details");
        page.put("entity", metadata.entityName());
        page.put("layout", "detail");
        page.put("breadcrumbs", List.of(
            Map.of("label", "Home", "path", "/"),
            Map.of("label", metadata.pluralName() != null ? metadata.pluralName() : metadata.entityName() + "s", "path", metadata.effectivePath()),
            Map.of("label", "{name}", "path", metadata.effectivePath() + "/{id}")
        ));
        page.put("headerActions", buildHeaderActions());
        page.put("sections", List.of(
            Map.of("title", "General Information", "$ref", metadata.entityName().toLowerCase() + ".schema.json"),
            Map.of("title", "Audit Information", "collapsed", true, "fields", List.of(
                Map.of("name", "createdAt", "label", "Created At", "type", "datetime"),
                Map.of("name", "updatedAt", "label", "Updated At", "type", "datetime")
            ))
        ));
        return page;
    }

    private List<Map<String, Object>> buildHeaderActions() {
        List<Map<String, Object>> actions = new ArrayList<>();
        actions.add(Map.of("name", "edit", "label", "Edit", "icon", "pencil", "type", "primary", "action", "navigate", "target", metadata.effectivePath() + "/{id}/edit"));
        actions.add(Map.of("name", "delete", "label", "Delete", "icon", "trash", "type", "danger", "action", "delete", "confirm", true));
        if (metadata.hasActions()) {
            for (ActionMetadata action : metadata.actions()) {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("name", action.name());
                a.put("label", action.name());
                a.put("type", action.dangerous() ? "danger" : "secondary");
                a.put("action", "api");
                a.put("method", action.httpMethod());
                a.put("endpoint", metadata.effectivePath() + "/{id}/actions/" + NameCasing.kebab(action.name()));
                if (action.requiresConfirmation()) a.put("confirm", true);
                actions.add(a);
            }
        }
        return actions;
    }

}
