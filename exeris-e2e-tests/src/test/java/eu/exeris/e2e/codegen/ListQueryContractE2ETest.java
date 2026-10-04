package eu.exeris.e2e.codegen;

import eu.exeris.e2e.codegen.compile.GeneratedTree;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.tooling.codegen.core.MetadataLoader;
import eu.exeris.tooling.codegen.java.openapi.OpenApiComponentsBuilder;
import eu.exeris.tooling.codegen.java.openapi.OpenApiPathsBuilder;
import eu.exeris.tooling.codegen.java.support.ListQuerySupport;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The list route's query and envelope, read out of every Java artefact that states them, and held
 * against {@code contract/list-query.json}.
 *
 * <p><b>Why.</b> The emitted Angular service builds the list request and reads the response, and it
 * is built by npm, in a build that never runs with this one. Both sides read the contract file;
 * this test proves the Java side states exactly it — the generated list query (by running it), the
 * generated page record, the OpenAPI document and the support constants every Java emitter reads.
 *
 * <p>The emitted tree is built through the real processor, so the sortable and filterable sets
 * asserted here are the processor's reading of {@code @Field(sortable / filterable)} and of
 * {@code @Relationship}, not hand-built metadata.
 */
@Tag("e2e")
@Tag("codegen")
@DisplayName("List query contract: generated list query == page record == OpenAPI == contract file")
class ListQueryContractE2ETest {

    private static final String BASE_PACKAGE = "eu.exeris.e2e.catalog";
    private static final String CUSTOMER_ID = "00000000-0000-4000-8000-00000000000c";

    @TempDir
    static Path workspace;

    private static JsonNode contract;
    private static GeneratedTree app;
    private static Class<?> queryType;
    private static Class<?> pageType;
    private static DomainMetadata productMetadata;

    @BeforeAll
    static void build() throws Exception {
        try (InputStream in = ListQueryContractE2ETest.class.getResourceAsStream("/contract/list-query.json")) {
            assertThat(in).as("contract/list-query.json on the test classpath").isNotNull();
            contract = JsonMapper.builder().build().readTree(in);
        }
        app = GeneratedTree.build(workspace, BASE_PACKAGE, domainSources(), Map.of());
        queryType = app.loader().loadClass(BASE_PACKAGE + ".repository.ProductListQuery");
        pageType = app.loader().loadClass(BASE_PACKAGE + ".repository.ProductPage");
        productMetadata = metadata("Product");
    }

    @AfterAll
    static void close() throws IOException {
        if (app != null) {
            app.close();
        }
    }

    @Test
    @DisplayName("the parameter names, the default and maximum size, and the reserved names")
    void parametersMatchTheContract() throws Exception {
        assertThat(contract.at("/parameters/page/default").asInt()).isZero();
        assertThat(contract.at("/parameters/page/minimum").asInt()).isZero();
        assertThat(contract.at("/parameters/size/default").asInt())
                .isEqualTo(ListQuerySupport.DEFAULT_SIZE)
                .isEqualTo(queryType.getField("DEFAULT_SIZE").getInt(null));
        assertThat(contract.at("/parameters/size/minimum").asInt()).isEqualTo(1);
        assertThat(contract.at("/parameters/size/maximum").asInt())
                .isEqualTo(ListQuerySupport.MAX_SIZE)
                .isEqualTo(queryType.getField("MAX_SIZE").getInt(null));
        assertThat(strings(contract.get("reserved"))).isEqualTo(ListQuerySupport.RESERVED)
                .containsExactly(ListQuerySupport.PAGE, ListQuerySupport.SIZE, ListQuerySupport.SORT);
        assertThat(strings(contract.get("filterableScalarTypes")))
                .isEqualTo(ListQuerySupport.filterableScalarTypes());
        assertThat(strings(contract.at("/parameters/sort/directions"))).containsExactly("asc", "desc");
    }

    @Test
    @DisplayName("the processor's sortable and filterable sets reach the emitted list query")
    @SuppressWarnings("unchecked")
    void processorSetsReachTheListQuery() throws Exception {
        // customerId carries @Relationship and no @Field, and the processor records a field without
        // @Field as sortable and filterable alike — so it is sortable here too. id, tenantId,
        // createdAt and updatedAt are recorded the same way and are system fields: never offered.
        assertThat((List<Object>) queryType.getField("SORTABLE").get(null))
                .containsExactly("customerId", "name", "price");
        Class<?> filterType = app.loader().loadClass(BASE_PACKAGE + ".repository.ProductListQuery$Filter");
        assertThat(Arrays.stream(filterType.getRecordComponents()).map(RecordComponent::getName))
                // active: @Field(filterable); customerId: the @Relationship's foreign key, a field
                // the processor records without @Field and so as filterable, once — not twice for
                // the field and the relationship; price and status: @Field(filterable). name is
                // sortable only, note neither.
                .containsExactly("active", "customerId", "price", "status");
    }

    @Test
    @DisplayName("parse reads the format the contract states, and toQueryString writes it back")
    void parseAndWriteBack() throws Exception {
        Object query = parse("page=2&size=5&sort=price,desc&status=ACTIVE&active=true"
                + "&customerId=" + CUSTOMER_ID + "&price=9.50");

        assertThat(component(query, "page")).isEqualTo(2);
        assertThat(component(query, "size")).isEqualTo(5);
        assertThat(component(query, "sort")).isEqualTo("price");
        assertThat(component(query, "descending")).isEqualTo(true);
        Object filter = component(query, "filter");
        assertThat(component(filter, "status")).hasToString("ACTIVE");
        assertThat(component(filter, "active")).isEqualTo(true);
        assertThat(component(filter, "customerId")).hasToString(CUSTOMER_ID);
        assertThat(component(filter, "price")).isEqualTo(new BigDecimal("9.50"));

        String written = (String) queryType.getMethod("toQueryString").invoke(query);
        assertThat(parse(written)).isEqualTo(query);

        Object defaults = parse("");
        assertThat(component(defaults, "page")).isEqualTo(contract.at("/parameters/page/default").asInt());
        assertThat(component(defaults, "size")).isEqualTo(contract.at("/parameters/size/default").asInt());
        assertThat(component(defaults, "sort")).isNull();
        // The bare property sorts ascending; the direction is case-insensitive.
        assertThat(component(parse("sort=name"), "descending")).isEqualTo(false);
        assertThat(component(parse("sort=name,DESC"), "descending")).isEqualTo(true);
    }

    @Test
    @DisplayName("everything the contract lists as refused is refused with IllegalArgumentException")
    void refusals() {
        int maximum = contract.at("/parameters/size/maximum").asInt();
        for (String raw : List.of(
                "search=lamp",                       // an unknown parameter name
                "page=1&page=2",                     // a repeated parameter
                "sort=status,asc",                   // not sortable
                "sort=name,up",                      // a direction other than asc or desc
                "active=yes",                        // a boolean that is not true or false
                "status=RETIRED",                    // not a constant
                "customerId=not-a-uuid",
                "price=cheap",
                "page=-1",
                "size=0",
                "size=" + (maximum + 1),
                "page=%zz")) {                       // malformed percent-encoding
            assertThatThrownBy(() -> parse(raw)).as(raw).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("the page record, the OpenAPI envelope schema and the contract name the same members")
    void envelopeMatchesTheContract() throws Exception {
        List<String> envelope = strings(contract.get("envelope"));

        assertThat(Arrays.stream(pageType.getRecordComponents()).map(RecordComponent::getName).toList())
                .isEqualTo(envelope);
        assertThat(ListQuerySupport.ENVELOPE).isEqualTo(envelope);
        Schema<?> schema = OpenApiComponentsBuilder.buildComponents(productMetadata).getSchemas().get("ProductPage");
        assertThat(new ArrayList<>(schema.getProperties().keySet())).isEqualTo(envelope);
        // Every member is required; swagger keeps the list in its own order.
        assertThat(schema.getRequired()).containsExactlyInAnyOrderElementsOf(envelope);

        Method of = pageType.getMethod("of", List.class, long.class, int.class, int.class);
        Object empty = of.invoke(null, List.of(), 0L, 0, 20);
        assertThat(component(empty, "totalPages")).isEqualTo(0);
        assertThat(component(empty, "first")).isEqualTo(true);
        assertThat(component(empty, "last")).isEqualTo(true);
        Object middle = of.invoke(null, List.of(), 45L, 1, 20);
        assertThat(component(middle, "totalPages")).isEqualTo(3);
        assertThat(component(middle, "first")).isEqualTo(false);
        assertThat(component(middle, "last")).isEqualTo(false);
    }

    @Test
    @DisplayName("the OpenAPI list operation declares exactly the parameters the list query reads")
    void openApiDeclaresTheParameters() throws Exception {
        Operation list = OpenApiPathsBuilder.buildPaths(productMetadata).get("/products").getGet();

        assertThat(list.getParameters()).extracting(Parameter::getName)
                .containsExactly("page", "size", "sort", "active", "customerId", "price", "status");
        assertThat(list.getParameters()).extracting(Parameter::getIn).containsOnly("query");
        Parameter size = list.getParameters().get(1);
        assertThat(size.getSchema().getDefault()).isEqualTo(contract.at("/parameters/size/default").asInt());
        assertThat(size.getSchema().getMaximum().intValue()).isEqualTo(contract.at("/parameters/size/maximum").asInt());
        assertThat(list.getParameters().get(2).getSchema().getEnum())
                .containsExactly("customerId,asc", "customerId,desc", "name,asc", "name,desc",
                        "price,asc", "price,desc");
        assertThat(list.getResponses()).containsOnlyKeys("200", "400", "500");
        assertThat(list.getResponses().get("200").getContent().get("application/json").getSchema().get$ref())
                .isEqualTo("#/components/schemas/ProductPage");
    }

    // ------------------------------------------------------------------ harness

    private static Object parse(String raw) throws Exception {
        try {
            return queryType.getMethod("parse", String.class).invoke(null, raw);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    private static Object component(Object record, String name) throws Exception {
        return record.getClass().getMethod(name).invoke(record);
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asString()));
        return values;
    }

    /** The processor's own metadata for one entity, read the way the pipeline reads it. */
    private static DomainMetadata metadata(String entity) throws IOException {
        return new MetadataLoader(workspace.resolve("target/classes")).load(entity, DomainMetadata.class);
    }

    private static Map<String, String> domainSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("eu/exeris/e2e/catalog/domain/ProductStatus.java",
                """
                package eu.exeris.e2e.catalog.domain;

                public enum ProductStatus { ACTIVE, DRAFT }
                """);
        sources.put("eu/exeris/e2e/catalog/domain/Customer.java",
                """
                package eu.exeris.e2e.catalog.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;

                import java.util.UUID;

                @ExerisDomain(module = "catalog", path = "/customers")
                public class Customer {

                    @Field(label = "ID")
                    private UUID id;

                    @Field(label = "Name")
                    private String name;

                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                }
                """);
        sources.put("eu/exeris/e2e/catalog/domain/Product.java",
                """
                package eu.exeris.e2e.catalog.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;
                import eu.exeris.sdk.annotation.Relationship;

                import java.math.BigDecimal;
                import java.time.Instant;
                import java.util.UUID;

                @ExerisDomain(module = "catalog", path = "/products",
                              dataScope = ExerisDomain.DataScope.TENANT, audited = true)
                public class Product {

                    // The primary key, the owner and the audit fields carry no @Field, so the
                    // processor records them sortable and filterable; none of them is offered.
                    private UUID id;

                    private UUID tenantId;

                    private Instant createdAt;

                    private Instant updatedAt;

                    @Field(label = "Name", sortable = true)
                    private String name;

                    @Field(label = "Price", sortable = true, filterable = true)
                    private BigDecimal price;

                    @Field(label = "Status", filterable = true)
                    private ProductStatus status;

                    @Field(label = "Active", filterable = true)
                    private boolean active;

                    @Field(label = "Note")
                    private String note;

                    @Relationship(targetEntity = Customer.class, displayField = "name")
                    private UUID customerId;

                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public UUID getTenantId() { return tenantId; }
                    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
                    public Instant getCreatedAt() { return createdAt; }
                    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
                    public Instant getUpdatedAt() { return updatedAt; }
                    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                    public BigDecimal getPrice() { return price; }
                    public void setPrice(BigDecimal price) { this.price = price; }
                    public ProductStatus getStatus() { return status; }
                    public void setStatus(ProductStatus status) { this.status = status; }
                    public boolean isActive() { return active; }
                    public void setActive(boolean active) { this.active = active; }
                    public String getNote() { return note; }
                    public void setNote(String note) { this.note = note; }
                    public UUID getCustomerId() { return customerId; }
                    public void setCustomerId(UUID customerId) { this.customerId = customerId; }
                }
                """);
        return sources;
    }
}
