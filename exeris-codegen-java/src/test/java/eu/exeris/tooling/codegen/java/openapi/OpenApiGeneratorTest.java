package eu.exeris.tooling.codegen.java.openapi;

import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.ActionParamMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.License;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OpenApiGenerator")
class OpenApiGeneratorTest {

    @TempDir Path tempDir;

    private OpenApiGenerator generator;

    @BeforeEach
    void setup() {
        generator = new OpenApiGenerator();
        generator.setOutputDirectory(tempDir);
    }

    @Test
    @DisplayName("generateYaml emits a non-empty OpenAPI 3.1 YAML document")
    void generateYaml() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").build();

        String yaml = generator.generateYaml(meta);

        assertThat(yaml)
                .contains("openapi: 3.1.0")
                .contains("Order API")
                .contains("/orders");
    }

    @Test
    @DisplayName("the emitted YAML publishes the owner as readOnly, and it parses back that way")
    void ownerIsReadOnlyInTheEmittedDocument() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .dataScope(eu.exeris.sdk.sourcemodel.ast.DataScope.TENANT)
                .fields(List.of(
                        FieldMetadata.builder("orderNumber", "String").build(),
                        FieldMetadata.builder("tenantId", "java.util.UUID").build()))
                .build();

        String yaml = generator.generateYaml(meta);

        assertThat(yaml).contains("readOnly: true");
        OpenAPI parsed = new OpenAPIV3Parser().readContents(yaml).getOpenAPI();
        io.swagger.v3.oas.models.media.Schema<?> tenant = (io.swagger.v3.oas.models.media.Schema<?>)
                parsed.getComponents().getSchemas().get("Order").getProperties().get("tenantId");
        assertThat(tenant.getReadOnly()).isTrue();
        assertThat(parsed.getComponents().getSchemas().get("OrderCreateDto").getProperties())
                .doesNotContainKey("tenantId");
    }

    @Test
    @DisplayName("generate(metadata) writes <entity>-api.yaml under the output directory")
    void generateWritesFile() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").build();

        OpenAPI openAPI = generator.generate(meta);

        assertThat(openAPI.getOpenapi()).isEqualTo("3.1.0");
        Path outFile = tempDir.resolve("order-api.yaml");
        assertThat(Files.exists(outFile)).isTrue();
        assertThat(Files.readString(outFile)).contains("openapi: 3.1.0");
    }

    @Test
    @DisplayName("validateMetadata: null metadata → IllegalArgumentException")
    void rejectsNullMetadata() {
        assertThatThrownBy(() -> generator.generateYaml(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be null");
    }

    @Test
    @DisplayName("validateMetadata: empty entity name → IllegalArgumentException")
    void rejectsEmptyEntityName() {
        DomainMetadata bad = DomainMetadata.builder("", "com.example.domain").build();

        assertThatThrownBy(() -> generator.generateYaml(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Entity name");
    }

    @Test
    @DisplayName("Description: domain description used when set; falls back to \"REST API for <Entity> management\" when blank")
    void infoDescriptionBranches() throws IOException {
        DomainMetadata withDesc = DomainMetadata.builder("Order", "com.example.domain")
                .description("Customer orders").build();
        DomainMetadata withoutDesc = DomainMetadata.builder("Order", "com.example.domain")
                .build();

        assertThat(generator.generate(withDesc).getInfo().getDescription())
                .isEqualTo("Customer orders");
        assertThat(generator.generate(withoutDesc).getInfo().getDescription())
                .isEqualTo("REST API for Order management");
    }

    @Test
    @DisplayName("Contact and License setters propagate into the emitted Info block")
    void contactAndLicensePropagate() throws IOException {
        Contact contact = new Contact().name("Exeris").email("oss@exeris.eu");
        License license = new License().name("Apache-2.0");
        generator.setContact(contact);
        generator.setLicense(license);
        generator.setApiTitle("My API");
        generator.setBaseUrl("https://api.exeris.eu");

        OpenAPI openAPI = generator.generate(
                DomainMetadata.builder("Order", "com.example.domain").build());

        assertThat(openAPI.getInfo().getContact()).isSameAs(contact);
        assertThat(openAPI.getInfo().getLicense()).isSameAs(license);
        assertThat(openAPI.getInfo().getTitle()).isEqualTo("My API - Order API");
        assertThat(openAPI.getServers().get(0).getUrl()).isEqualTo("https://api.exeris.eu");
    }

    @Test
    @DisplayName("generateAggregated builds a multi-entity spec under <module>-api.yaml")
    void generateAggregated() throws IOException {
        DomainMetadata order = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").build();
        DomainMetadata product = DomainMetadata.builder("Product", "com.example.domain")
                .path("/products").build();

        OpenAPI openAPI = generator.generateAggregated(List.of(order, product), "catalog");

        assertThat(openAPI.getOpenapi()).isEqualTo("3.1.0");
        assertThat(openAPI.getInfo().getTitle()).isEqualTo("Exeris API - Catalog Module");
        assertThat(openAPI.getPaths()).containsKey("/orders");
        assertThat(openAPI.getPaths()).containsKey("/products");
        // Tag list aggregates both entities.
        assertThat(openAPI.getTags()).extracting("name")
                .contains("Order", "Product");
        assertThat(Files.exists(tempDir.resolve("catalog-api.yaml"))).isTrue();
    }

    @Test
    @DisplayName("generateAggregated rejects null / empty metadata list")
    void generateAggregatedRejectsEmpty() {
        assertThatThrownBy(() -> generator.generateAggregated(null, "catalog"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.generateAggregated(List.of(), "catalog"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("getOutputDirectory returns the path set via the setter")
    void outputDirectoryGetter() {
        assertThat(generator.getOutputDirectory()).isEqualTo(tempDir);
    }

    @Test
    @DisplayName("ADR-079: neither emitted document declares a security requirement or scheme")
    void emitsNoSecurityBlock() throws IOException {
        DomainMetadata order = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").build();
        DomainMetadata product = DomainMetadata.builder("Product", "com.example.domain")
                .path("/products").build();

        OpenAPI aggregate = generator.generateAggregated(List.of(order, product), "catalog");
        OpenAPI single = generator.generate(order);

        // Both documents, because the requirement was set on both: the per-entity spec and the
        // aggregate. An emitted app binds no HttpRoutePolicy, so every route is permit-all and no
        // token is ever read — a spec that says otherwise describes a check that does not run.
        assertThat(aggregate.getSecurity()).isNull();
        assertThat(aggregate.getComponents().getSecuritySchemes()).isNull();
        assertThat(single.getSecurity()).isNull();
        assertThat(single.getComponents().getSecuritySchemes()).isNull();
        assertThat(Files.readString(tempDir.resolve("catalog-api.yaml")))
                .doesNotContain("bearerAuth");
    }

    @Test
    @DisplayName("D9: the emitted document carries no null-valued fields and no swagger bookkeeping")
    void emitsNoUnsetFields() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").versioned(true)
                .description("Customer order")
                .fields(List.of(
                        FieldMetadata.builder("orderNumber", "String").required(true).build(),
                        FieldMetadata.builder("amount", "BigDecimal").build()))
                .actions(List.of(ActionMetadata.builder("approve").build()))
                .build();

        String yaml = generator.generateYaml(meta);

        // The same document used to be 1664 lines, 1479 of them ": null". `exampleSetFlag` is not
        // an OpenAPI field at all — it is swagger-model bookkeeping, and the 3.1 schema's
        // unevaluatedProperties: false rejects it — so NON_NULL alone would not have been enough.
        assertThat(yaml)
                .doesNotContain(": null")
                .doesNotContain("exampleSetFlag");
        assertThat(yaml.lines().count()).isLessThan(400);
    }

    @Test
    @DisplayName("D9: the emitted document parses back as an OpenAPI 3.1 spec with its routes intact")
    void emittedDocumentRoundTrips() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").versioned(true)
                .fields(List.of(FieldMetadata.builder("orderNumber", "String").required(true).build()))
                .actions(List.of(ActionMetadata.builder("approve").build()))
                .build();

        SwaggerParseResult parsed = new OpenAPIV3Parser().readContents(generator.generateYaml(meta));

        // Dropping fields from the writer is only safe if the reader still gets a whole document,
        // so the gate is a parse rather than a text assertion.
        assertThat(parsed.getMessages()).isEmpty();
        assertThat(parsed.getOpenAPI()).isNotNull();
        assertThat(parsed.getOpenAPI().getPaths())
                .containsKeys("/orders", "/orders/{id}", "/orders/{id}/actions/approve");
        assertThat(parsed.getOpenAPI().getComponents().getSchemas())
                .containsKeys("Order", "OrderCreateDto", "OrderUpdateDto");
    }

    @Test
    @DisplayName("every schema in the emitted document has a type, a $ref or a composition")
    void everyEmittedSchemaIsTyped() throws IOException {
        OpenAPI single = new OpenAPIV3Parser().readContents(generator.generateYaml(richOrder())).getOpenAPI();
        generator.generateAggregated(List.of(richOrder(), product()), "catalog");
        OpenAPI aggregate = new OpenAPIV3Parser()
                .readContents(Files.readString(tempDir.resolve("catalog-api.yaml"))).getOpenAPI();

        // Read back from the YAML, not taken from the model: the 3.1 writer drops a schema's
        // type unless it is in the model's `types` set, which a model-level check cannot see.
        assertThat(untypedSchemas(single)).isEmpty();
        assertThat(untypedSchemas(aggregate)).isEmpty();
        assertThat(allSchemas(single)).hasSizeGreaterThan(40);
    }

    @Test
    @DisplayName("every $ref in the emitted document resolves to a schema the document defines")
    void everyEmittedReferenceResolves() throws IOException {
        OpenAPI single = new OpenAPIV3Parser().readContents(generator.generateYaml(richOrder())).getOpenAPI();
        generator.generateAggregated(List.of(richOrder(), product()), "catalog");
        OpenAPI aggregate = new OpenAPIV3Parser()
                .readContents(Files.readString(tempDir.resolve("catalog-api.yaml"))).getOpenAPI();

        assertThat(danglingReferences(single)).isEmpty();
        assertThat(danglingReferences(aggregate)).isEmpty();
        assertThat(single.getComponents().getSchemas()).containsKey("OrderApproveRequest");
        assertThat(single.getComponents().getSchemas()).doesNotContainKey("OrderCancelRequest");
    }

    @Test
    @DisplayName("an action's request schema declares each parameter with its type and format, and none as required")
    void actionRequestSchemaDeclaresTheParameters() throws IOException {
        OpenAPI parsed = new OpenAPIV3Parser().readContents(generator.generateYaml(richOrder())).getOpenAPI();

        Schema<?> request = parsed.getComponents().getSchemas().get("OrderApproveRequest");
        assertThat(request.getTypes()).containsExactly("object");
        assertThat(request.getProperties()).containsOnlyKeys("note", "approvedOn", "priority");
        assertThat(request.getProperties().get("note").getTypes()).containsExactly("string");
        assertThat(request.getProperties().get("approvedOn").getTypes()).containsExactly("string");
        assertThat(request.getProperties().get("approvedOn").getFormat()).isEqualTo("date");
        assertThat(request.getProperties().get("priority").getTypes()).containsExactly("integer");
        assertThat(request.getProperties().get("priority").getFormat()).isEqualTo("int32");
        assertThat(request.getRequired()).isNull();
    }

    /** One entity reaching every builder path: list parameters of each kind, a versioned write, actions. */
    private static DomainMetadata richOrder() {
        return DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").versioned(true)
                .dataScope(eu.exeris.sdk.sourcemodel.ast.DataScope.TENANT)
                .fields(List.of(
                        FieldMetadata.builder("orderNumber", "java.lang.String").required(true)
                                .sortable(true).filterable(true).build(),
                        FieldMetadata.builder("status", "com.example.domain.OrderStatus")
                                .enumType("com.example.domain.OrderStatus").sortable(true).filterable(true).build(),
                        FieldMetadata.builder("amount", "java.math.BigDecimal").sortable(true).build(),
                        FieldMetadata.builder("quantity", "int").filterable(true).build(),
                        FieldMetadata.builder("urgent", "boolean").filterable(true).build(),
                        FieldMetadata.builder("dueOn", "java.time.LocalDate").sortable(true).filterable(true).build(),
                        FieldMetadata.builder("placedAt", "java.time.Instant").sortable(true).build(),
                        FieldMetadata.builder("tenantId", "java.util.UUID").build()))
                .actions(List.of(
                        ActionMetadata.builder("approve")
                                .addParam(ActionParamMetadata.builder("note", "java.lang.String").build())
                                .addParam(ActionParamMetadata.builder("approvedOn", "java.time.LocalDate").build())
                                .addParam(ActionParamMetadata.builder("priority", "int").build())
                                .build(),
                        ActionMetadata.builder("cancel").build()))
                .build();
    }

    private static DomainMetadata product() {
        return DomainMetadata.builder("Product", "com.example.domain")
                .path("/products")
                .fields(List.of(FieldMetadata.builder("sku", "String").filterable(true).build()))
                .actions(List.of(ActionMetadata.builder("discontinue")
                        .addParam(ActionParamMetadata.builder("reason", "String").build()).build()))
                .build();
    }

    /** Each schema of the document with where it sits, nested schemas included. */
    private static Map<String, Schema<?>> allSchemas(OpenAPI document) {
        Map<String, Schema<?>> found = new LinkedHashMap<>();
        if (document.getComponents() != null && document.getComponents().getSchemas() != null) {
            document.getComponents().getSchemas().forEach((name, schema) ->
                    collect("#/components/schemas/" + name, schema, found));
        }
        document.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, op) -> {
            String at = method + " " + path;
            if (op.getParameters() != null) {
                for (Parameter parameter : op.getParameters()) {
                    collect(at + " parameter " + parameter.getName(), parameter.getSchema(), found);
                }
            }
            if (op.getRequestBody() != null && op.getRequestBody().getContent() != null) {
                op.getRequestBody().getContent().forEach((media, type) ->
                        collect(at + " requestBody " + media, type.getSchema(), found));
            }
            if (op.getResponses() != null) {
                op.getResponses().forEach((status, response) -> {
                    if (response.getContent() != null) {
                        response.getContent().forEach((media, type) ->
                                collect(at + " " + status + " " + media, type.getSchema(), found));
                    }
                });
            }
        }));
        return found;
    }

    private static void collect(String at, Schema<?> schema, Map<String, Schema<?>> found) {
        if (schema == null) {
            found.put(at, null);
            return;
        }
        found.put(at, schema);
        if (schema.getProperties() != null) {
            schema.getProperties().forEach((name, property) -> collect(at + "/properties/" + name, property, found));
        }
        if (schema.getItems() != null) {
            collect(at + "/items", schema.getItems(), found);
        }
        if (schema.getAdditionalProperties() instanceof Schema<?> additional) {
            collect(at + "/additionalProperties", additional, found);
        }
        for (List<Schema> composed : java.util.Arrays.asList(schema.getAllOf(), schema.getAnyOf(), schema.getOneOf())) {
            if (composed != null) {
                for (int i = 0; i < composed.size(); i++) {
                    collect(at + "/composition[" + i + "]", composed.get(i), found);
                }
            }
        }
    }

    private static List<String> untypedSchemas(OpenAPI document) {
        List<String> untyped = new ArrayList<>();
        allSchemas(document).forEach((at, schema) -> {
            boolean typed = schema != null && (schema.get$ref() != null
                    || (schema.getTypes() != null && !schema.getTypes().isEmpty())
                    || schema.getAllOf() != null || schema.getAnyOf() != null || schema.getOneOf() != null);
            if (!typed) {
                untyped.add(at);
            }
        });
        return untyped;
    }

    private static List<String> danglingReferences(OpenAPI document) {
        Map<String, Schema> defined = document.getComponents() == null
                || document.getComponents().getSchemas() == null
                ? Map.of() : document.getComponents().getSchemas();
        List<String> dangling = new ArrayList<>();
        allSchemas(document).forEach((at, schema) -> {
            String ref = schema == null ? null : schema.get$ref();
            if (ref != null && !(ref.startsWith("#/components/schemas/")
                    && defined.containsKey(ref.substring("#/components/schemas/".length())))) {
                dangling.add(at + " -> " + ref);
            }
        });
        return dangling;
    }

    @Test
    @DisplayName("The same metadata emits a byte-identical document twice")
    void emissionIsDeterministic() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").versioned(true)
                .fields(List.of(
                        FieldMetadata.builder("orderNumber", "String").required(true).build(),
                        FieldMetadata.builder("amount", "BigDecimal").build()))
                .actions(List.of(
                        ActionMetadata.builder("approve").build(),
                        ActionMetadata.builder("cancel").build()))
                .build();

        // Hard constraint 3, and this generator had no gate for it: two entities' worth of
        // map-valued state (paths, schemas, tags) reaches the writer, and a HashMap slipped in
        // anywhere would show up here rather than as an unexplained diff in a consumer's repo.
        assertThat(generator.generateYaml(meta)).isEqualTo(generator.generateYaml(meta));
        assertThat(new OpenApiGenerator().generateYaml(meta)).isEqualTo(generator.generateYaml(meta));
    }
}
