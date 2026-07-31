package eu.exeris.e2e.codegen;

import eu.exeris.e2e.codegen.compile.ProcessorCompiler;
import eu.exeris.tooling.codegen.java.CodegenPipeline;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Annotation → SQL: what {@code @Relationship} declares is what the emitted schema says.
 *
 * <p>Written after a bug that no existing test could see. The processor read an attribute named
 * {@code type}, which {@code @Relationship} does not have (it declares {@code relationshipType}),
 * so every relationship reached the generators as {@code MANY_TO_ONE}. The generators were fine —
 * each gates on {@code MANY_TO_ONE} and is unit-tested against hand-built metadata — and the
 * processor tests asserted the JSON had <em>a</em> relationships array. The defect lived exactly in
 * the seam neither side covered, and produced wrong DDL: an FK column, its index and a
 * {@code FOREIGN KEY} constraint on the collection side of a one-to-many, where the child owns them.
 *
 * <p>So this test runs the real chain — annotated sources → {@code javac} + processor →
 * {@code CodegenPipeline} → emitted Flyway — and asserts on the SQL rather than on the metadata.
 * The {@code -Aexeris.strict} audit cannot substitute for it: strict mode reports attributes that
 * are <em>extracted but unconsumed</em>, and an attribute the processor never reads at all is
 * invisible to it.
 */
@Tag("e2e")
@Tag("codegen")
@DisplayName("Relationship → SQL e2e: @Relationship(relationshipType/cascade*) reaches the schema")
class RelationshipSqlE2ETest {

    @TempDir
    static Path workspace;

    private static String createOrders;
    private static String foreignKeys;

    @BeforeAll
    static void generateTheSchema() throws IOException {
        Path classes = workspace.resolve("target/classes");
        Path generated = workspace.resolve("src/main/generated/java");

        ProcessorCompiler.compile(workspace.resolve("src"), classes, null, sources());
        CodegenPipeline.createDefault()
                .run(classes.resolve("exeris-metadata"), generated, "com.shop");

        createOrders = migration(generated, "orders");
        foreignKeys = migration(generated, "foreign_keys");
    }

    @Test
    @DisplayName("the MANY_TO_ONE side owns the FK column and its index")
    void owningSideGetsTheForeignKeyColumn() {
        assertThat(createOrders).contains("customer_id");
    }

    @Test
    @DisplayName("the ONE_TO_MANY side gets no column, no index, no constraint — the child owns them")
    void collectionSideGetsNothing() {
        // Before the extraction fix this table carried an `items_id UUID`, an index on it and a
        // FOREIGN KEY into order_items — schema for a reference that does not exist on this row.
        assertThat(createOrders).doesNotContain("items_id");
        assertThat(foreignKeys).doesNotContain("items");
    }

    @Test
    @DisplayName("cascadeDelete becomes ON DELETE CASCADE; its absence stays RESTRICT")
    void cascadeFlagsReachTheConstraint() {
        assertThat(foreignKeys)
                .contains("FOREIGN KEY (customer_id)")
                .contains("ON DELETE CASCADE")
                // carrier_id declares no cascade, so it must keep the safe default.
                .contains("FOREIGN KEY (carrier_id)")
                .contains("ON DELETE RESTRICT");
    }

    /** Reads the one emitted migration whose file name contains {@code fragment}. */
    private static String migration(Path generated, String fragment) throws IOException {
        try (Stream<Path> files = Files.walk(generated.resolve("db/migration"))) {
            Path file = files.filter(p -> p.getFileName().toString().contains(fragment))
                    .filter(p -> p.toString().endsWith(".sql"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no migration matching '" + fragment + "'"));
            return Files.readString(file);
        }
    }

    private static Map<String, String> sources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("com/shop/domain/Customer.java",
                """
                package com.shop.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;

                @ExerisDomain(module = "sales", path = "/customers")
                public class Customer {

                    @Field(label = "Name")
                    private String name;
                }
                """);
        sources.put("com/shop/domain/Carrier.java",
                """
                package com.shop.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;

                @ExerisDomain(module = "sales", path = "/carriers")
                public class Carrier {

                    @Field(label = "Name")
                    private String name;
                }
                """);
        sources.put("com/shop/domain/OrderItem.java",
                """
                package com.shop.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;

                @ExerisDomain(module = "sales", path = "/order-items")
                public class OrderItem {

                    @Field(label = "Description")
                    private String description;
                }
                """);
        sources.put("com/shop/domain/Order.java",
                """
                package com.shop.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;
                import eu.exeris.sdk.annotation.Relationship;
                import eu.exeris.sdk.annotation.Relationship.RelationshipType;

                import java.util.List;
                import java.util.UUID;

                @ExerisDomain(module = "sales", path = "/orders")
                public class Order {

                    @Field(label = "Order Number")
                    private String orderNumber;

                    // Owning side, deleting the customer takes its orders with it.
                    @Relationship(targetEntity = Customer.class, displayField = "name",
                            cascadeDelete = true)
                    private UUID customerId;

                    // Owning side without a cascade — the constraint must stay RESTRICT.
                    @Relationship(targetEntity = Carrier.class, displayField = "name")
                    private UUID carrierId;

                    // Collection side: OrderItem holds the order_id, never the other way round.
                    @Relationship(targetEntity = OrderItem.class, displayField = "description",
                            relationshipType = RelationshipType.ONE_TO_MANY, mappedBy = "order")
                    private List<OrderItem> items;
                }
                """);
        return sources;
    }
}
