package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.RelationshipMetadata;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.java.support.ListQuerySupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-generator test for {@link KernelListQueryGenerator}: the list route's query and page types.
 *
 * <p>The text checks here pin the whitelist each emitted type carries; that the emitted query
 * actually parses, refuses and round-trips is proven by running it — the compile gate and the boot
 * test in {@code exeris-e2e-tests}.
 */
@DisplayName("KernelListQueryGenerator")
class KernelListQueryGeneratorTest {

    private final KernelListQueryGenerator generator = new KernelListQueryGenerator();

    /** Sortable and filterable fields of every kind the route treats differently. */
    static DomainMetadata order() {
        return DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .softDelete(true)
                .fields(List.of(
                        FieldMetadata.builder("orderNumber", "java.lang.String").sortable(true).filterable(true).build(),
                        FieldMetadata.builder("status", "com.example.domain.OrderStatus").filterable(true).build(),
                        FieldMetadata.builder("amount", "java.math.BigDecimal").sortable(true).build(),
                        FieldMetadata.builder("urgent", "boolean").filterable(true).build(),
                        FieldMetadata.builder("dueOn", "java.time.LocalDate").filterable(true).sortable(true).build(),
                        // Neither: not a parameter at all.
                        FieldMetadata.builder("note", "String").build(),
                        // Filterable but an instant: equality on it is not offered.
                        FieldMetadata.builder("placedAt", "java.time.Instant").filterable(true).sortable(true).build(),
                        // A JSON column: neither sorted nor filtered.
                        FieldMetadata.builder("tags", "java.util.List<java.lang.String>")
                                .filterable(true).sortable(true).build(),
                        // Named after a reserved parameter: sortable, never a filter.
                        FieldMetadata.builder("size", "int").filterable(true).sortable(true).build()))
                .relationships(List.of(
                        RelationshipMetadata.manyToOne("customer", "Customer"),
                        RelationshipMetadata.oneToMany("lines", "OrderLine", "order")))
                .build();
    }

    @Test
    @DisplayName("emits the query and the page into the repository package, as LIST_QUERY artefacts")
    void emitsTwoTypesIntoTheRepositoryPackage() {
        List<GeneratedFile> files = generator.generateMultiple(order());

        assertThat(files).extracting(GeneratedFile::className).containsExactly("OrderListQuery", "OrderPage");
        assertThat(files).extracting(GeneratedFile::packageName).containsOnly("com.example.repository");
        assertThat(files).extracting(GeneratedFile::artifactType).containsOnly(ArtifactType.LIST_QUERY);
    }

    @Test
    @DisplayName("the sort whitelist is the sortable fields, sorted, without a JSON column")
    void sortWhitelist() {
        String query = flat(generator.generateMultiple(order()).get(0));

        assertThat(query).contains(
                "public static final List<String> SORTABLE = List.of(\"amount\", \"dueOn\", \"orderNumber\", "
                        + "\"placedAt\", \"size\");");
    }

    @Test
    @DisplayName("the filter record has one nullable component per filter, foreign key included")
    void filterRecord() {
        String query = flat(generator.generateMultiple(order()).get(0));

        assertThat(query)
                .contains("record Filter(UUID customerId, LocalDate dueOn, String orderNumber, "
                        + "OrderStatus status, Boolean urgent)")
                .doesNotContain("placedAt =")
                .doesNotContain("Instant")
                .doesNotContain("case \"tags\"")
                .doesNotContain("case \"note\"");
    }

    @Test
    @DisplayName("parse reads each parameter by its own type and refuses every other name")
    void parseDispatch() {
        String query = flat(generator.generateMultiple(order()).get(0));

        assertThat(query)
                .contains("public static OrderListQuery parse(String rawQuery)")
                .contains("case \"page\" -> page = Integer.parseInt(value);")
                .contains("case \"size\" -> size = Integer.parseInt(value);")
                .contains("case \"customerId\" -> filterCustomerId = UUID.fromString(value);")
                .contains("case \"dueOn\" -> filterDueOn = parseDate(value);")
                .contains("case \"orderNumber\" -> filterOrderNumber = value;")
                .contains("case \"status\" -> filterStatus = OrderStatus.valueOf(value);")
                .contains("case \"urgent\" -> filterUrgent = parseBoolean(value);")
                .contains("default -> throw new IllegalArgumentException(\"unknown query parameter: \" + name);")
                .contains("throw new IllegalArgumentException(\"repeated query parameter: \" + name);")
                // A field named "size" is not a second "size" case: the parameter is the page size.
                .containsOnlyOnce("case \"size\"");
    }

    @Test
    @DisplayName("the constructor bounds page and size and checks sort against the whitelist")
    void constructorRefusals() {
        String query = flat(generator.generateMultiple(order()).get(0));

        assertThat(query)
                .contains("public static final int DEFAULT_SIZE = " + ListQuerySupport.DEFAULT_SIZE + ";")
                .contains("public static final int MAX_SIZE = " + ListQuerySupport.MAX_SIZE + ";")
                .contains("if (page < 0)")
                .contains("if (size < 1 || size > MAX_SIZE)")
                .contains("if (sort != null && !SORTABLE.contains(sort))");
    }

    @Test
    @DisplayName("toQueryString writes what parse reads")
    void toQueryString() {
        String query = flat(generator.generateMultiple(order()).get(0));

        assertThat(query)
                .contains("query.append(\"page=\").append(page).append(\"&size=\").append(size);")
                .contains("query.append(\"&sort=\").append(encode(sort)).append(descending ? \",desc\" : \",asc\");")
                .contains("query.append(\"&status=\").append(encode(String.valueOf(filter.status())));");
    }

    @Test
    @DisplayName("the page record's members are the envelope the front reads, in order")
    void pageEnvelope() {
        String page = flat(generator.generateMultiple(order()).get(1));

        assertThat(page)
                .contains("public record OrderPage(List<Order> content, long totalElements, int totalPages, "
                        + "int size, int number, boolean first, boolean last)")
                .contains("int totalPages = (int) ((totalElements + size - 1) / size);")
                .contains("number == 0, number >= totalPages - 1");
        assertThat(ListQuerySupport.ENVELOPE)
                .containsExactly("content", "totalElements", "totalPages", "size", "number", "first", "last");
    }

    @Test
    @DisplayName("an entity with nothing sortable or filterable still gets both types")
    void bareEntity() {
        DomainMetadata bare = DomainMetadata.builder("Tag", "com.example.domain").path("/tags").build();

        String query = flat(generator.generateMultiple(bare).get(0));

        assertThat(query)
                .contains("public static final List<String> SORTABLE = List.of();")
                .contains("record Filter()")
                .contains("return new TagListQuery(page, size, sort, descending, new Filter());");
    }

    @Test
    @DisplayName("regenerating yields byte-identical output")
    void deterministic() {
        assertThat(generator.generateMultiple(order()))
                .extracting(GeneratedFile::content)
                .isEqualTo(new KernelListQueryGenerator().generateMultiple(order()).stream()
                        .map(GeneratedFile::content).toList());
    }

    /** The emitted source with every whitespace run collapsed, so JavaPoet's line wrapping is not asserted. */
    private static String flat(GeneratedFile file) {
        return file.content().replaceAll("\\s+", " ");
    }
}
