package eu.exeris.tooling.codegen.java.support;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.RelationshipMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ListQuerySupport}: which properties the list route sorts and filters on. Every Java artefact
 * of the route reads these two lists, so their rules are pinned here once.
 */
@DisplayName("ListQuerySupport")
class ListQuerySupportTest {

    private static DomainMetadata entity(List<FieldMetadata> fields, List<RelationshipMetadata> relationships) {
        return DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .fields(fields)
                .relationships(relationships)
                .build();
    }

    @Test
    @DisplayName("sortable: the flagged fields, by name, with column names, never a JSON column")
    void sortable() {
        DomainMetadata metadata = entity(List.of(
                FieldMetadata.builder("placedAt", "java.time.Instant").sortable(true).build(),
                FieldMetadata.builder("orderNumber", "String").sortable(true).build(),
                FieldMetadata.builder("tags", "java.util.List<String>").sortable(true).build(),
                FieldMetadata.builder("note", "String").build()), List.of());

        assertThat(ListQuerySupport.sortable(metadata))
                .extracting(ListQuerySupport.Property::name, ListQuerySupport.Property::column)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("orderNumber", "order_number"),
                        org.assertj.core.groups.Tuple.tuple("placedAt", "placed_at"));
    }

    @Test
    @DisplayName("filters: parseable flagged fields and MANY_TO_ONE foreign keys, by name, never a "
            + "reserved name, an instant or a JSON column, each name once")
    void filters() {
        DomainMetadata metadata = entity(List.of(
                FieldMetadata.builder("status", "com.example.domain.OrderStatus")
                        .enumType("com.example.domain.OrderStatus").filterable(true).build(),
                FieldMetadata.builder("placedAt", "java.time.Instant").filterable(true).build(),
                FieldMetadata.builder("scheduledFor", "java.time.LocalDateTime").filterable(true).build(),
                FieldMetadata.builder("tags", "java.util.List<String>").filterable(true).build(),
                FieldMetadata.builder("size", "int").filterable(true).build(),
                FieldMetadata.builder("urgent", "boolean").filterable(true).build(),
                // The explicit-UUID foreign key: a field and a relationship, one parameter.
                FieldMetadata.simple("warehouseId", "java.util.UUID")),
                List.of(RelationshipMetadata.manyToOne("customer", "Customer"),
                        RelationshipMetadata.manyToOne("warehouseId", "Warehouse"),
                        RelationshipMetadata.oneToMany("lines", "OrderLine", "order")));

        assertThat(ListQuerySupport.filters(metadata))
                .extracting(ListQuerySupport.Property::name, ListQuerySupport.Property::column,
                        ListQuerySupport.Property::kind)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("customerId", "customer_id", DomainTypeKind.UUID),
                        org.assertj.core.groups.Tuple.tuple("status", "status", DomainTypeKind.ENUM),
                        org.assertj.core.groups.Tuple.tuple("urgent", "urgent", DomainTypeKind.BOOL),
                        org.assertj.core.groups.Tuple.tuple("warehouseId", "warehouse_id", DomainTypeKind.UUID));
    }

    @Test
    @DisplayName("a type nothing recognises is neither a sort key nor a filter; an enum is both; "
            + "an offset or zoned date-time is a sort key only")
    void unrecognisedTypesAreNeitherSortKeysNorFilters() {
        DomainMetadata metadata = entity(List.of(
                FieldMetadata.simple("placedAt", "java.time.OffsetDateTime"),
                FieldMetadata.simple("dueAt", "java.time.ZonedDateTime"),
                FieldMetadata.simple("attributes", "java.util.Map<java.lang.String,java.lang.String>"),
                FieldMetadata.simple("labels", "java.util.Set<java.lang.String>"),
                FieldMetadata.simple("serial", "java.math.BigInteger"),
                FieldMetadata.simple("address", "com.example.domain.Address"),
                // Named like an enum, emitted as none: enumType unset.
                FieldMetadata.simple("phase", "com.example.domain.OrderPhase"),
                FieldMetadata.builder("status", "com.example.domain.OrderStatus")
                        .enumType("com.example.domain.OrderStatus")
                        .sortable(true).filterable(true).build()), List.of());

        assertThat(ListQuerySupport.sortable(metadata))
                .extracting(ListQuerySupport.Property::name, ListQuerySupport.Property::kind)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("dueAt", DomainTypeKind.ZONED_DATE_TIME),
                        org.assertj.core.groups.Tuple.tuple("placedAt", DomainTypeKind.OFFSET_DATE_TIME),
                        org.assertj.core.groups.Tuple.tuple("status", DomainTypeKind.ENUM));
        assertThat(ListQuerySupport.filters(metadata))
                .extracting(ListQuerySupport.Property::name, ListQuerySupport.Property::kind)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("status", DomainTypeKind.ENUM));
    }

    @Test
    @DisplayName("the published constants: reserved names, sizes, envelope, filterable types sorted")
    void constants() {
        assertThat(ListQuerySupport.RESERVED).containsExactly("page", "size", "sort");
        assertThat(ListQuerySupport.DEFAULT_SIZE).isEqualTo(20);
        assertThat(ListQuerySupport.MAX_SIZE).isEqualTo(100);
        assertThat(ListQuerySupport.ENVELOPE)
                .containsExactly("content", "totalElements", "totalPages", "size", "number", "first", "last");
        assertThat(ListQuerySupport.filterableScalarTypes()).isSorted()
                .contains("java.util.UUID", "boolean", "java.time.LocalDate")
                .contains("short", "Short", "java.lang.Short", "byte", "Byte", "java.lang.Byte",
                        "float", "Float", "java.lang.Float")
                .doesNotContain("java.time.Instant", "java.time.LocalDateTime");
        assertThat(ListQuerySupport.sortableScalarTypes()).isSorted()
                .containsAll(ListQuerySupport.filterableScalarTypes())
                .contains("java.time.Instant", "java.time.LocalDateTime",
                        "java.time.OffsetDateTime", "java.time.ZonedDateTime")
                .doesNotContain("java.math.BigInteger");
        assertThat(ListQuerySupport.filterableScalarTypes())
                .doesNotContain("java.time.OffsetDateTime", "java.time.ZonedDateTime");
    }

    @Test
    @DisplayName("system fields are never a sort key or a filter, even recorded without @Field; "
            + "an ordinary field without @Field is both")
    void systemFieldsAreExcluded() {
        DomainMetadata metadata = DomainMetadata.builder("Species", "com.example.domain")
                .path("/species")
                .dataScope(eu.exeris.sdk.sourcemodel.ast.DataScope.UNIVERSE)
                .audited(true)
                .versioned(true)
                .softDelete(true)
                .systemFields(eu.exeris.sdk.sourcemodel.ast.SystemFieldsMetadata.builder()
                        .tenantIdField("organizationId").sharedScopeField("worldId").build())
                // FieldMetadata.simple: sortable and filterable, as the processor records a field
                // that carries no @Field.
                .fields(List.of(
                        FieldMetadata.simple("id", "java.util.UUID"),
                        FieldMetadata.simple("name", "String"),
                        FieldMetadata.simple("organizationId", "java.util.UUID"),
                        FieldMetadata.simple("worldId", "java.util.UUID"),
                        FieldMetadata.simple("createdAt", "java.time.Instant"),
                        FieldMetadata.simple("updatedBy", "String"),
                        FieldMetadata.simple("version", "long"),
                        FieldMetadata.simple("deleted", "boolean")))
                .build();

        assertThat(ListQuerySupport.sortable(metadata)).extracting(ListQuerySupport.Property::name)
                .containsExactly("name");
        assertThat(ListQuerySupport.filters(metadata)).extracting(ListQuerySupport.Property::name)
                .containsExactly("name");
        assertThat(ListQuerySupport.systemFieldNames(metadata)).contains(
                "id", "organizationId", "worldId", "createdAt", "createdBy", "updatedAt", "updatedBy",
                "version", "deleted", "deletedAt", "deletedBy");
    }

    @Test
    @DisplayName("a tenant entity's default owner is excluded; on an entity without the role, a field "
            + "with a system name is ordinary")
    void systemRolesFollowTheEntityFlags() {
        DomainMetadata tenant = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").tenantScoped(true)
                .fields(List.of(FieldMetadata.simple("tenantId", "java.util.UUID"),
                        FieldMetadata.simple("createdAt", "java.time.Instant")))
                .build();
        DomainMetadata global = DomainMetadata.builder("Note", "com.example.domain")
                .path("/notes")
                .fields(List.of(FieldMetadata.simple("tenantId", "java.util.UUID"),
                        FieldMetadata.simple("createdAt", "java.time.Instant")))
                .build();

        assertThat(ListQuerySupport.sortable(tenant)).extracting(ListQuerySupport.Property::name)
                .containsExactly("createdAt");
        assertThat(ListQuerySupport.filters(tenant)).isEmpty();
        assertThat(ListQuerySupport.sortable(global)).extracting(ListQuerySupport.Property::name)
                .containsExactly("createdAt", "tenantId");
    }
}
