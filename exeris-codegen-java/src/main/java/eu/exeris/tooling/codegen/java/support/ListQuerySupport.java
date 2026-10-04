package eu.exeris.tooling.codegen.java.support;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.RelationshipMetadata;
import eu.exeris.sdk.sourcemodel.ast.SystemFieldsMetadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The list route's query contract: which query parameters {@code GET {base}} reads, which fields an
 * entity may be sorted and filtered on, and the field names of the page envelope it answers with.
 *
 * <p>Every artefact that states the contract reads it here — the emitted list query and page
 * types, the repository's {@code ORDER BY} and {@code WHERE} whitelist, the handler, the client and
 * the OpenAPI document — so none of them can accept a parameter another one does not. The same
 * values are pinned in {@code contract/list-query.json}, which the TypeScript emitter's tests read.
 *
 * <p>Every list this class returns is sorted by property name, so the emitted text is a pure function
 * of the metadata.
 *
 * @since 0.9.0
 */
public final class ListQuerySupport {

    /** Zero-based page index. */
    public static final String PAGE = "page";
    /** Page size. */
    public static final String SIZE = "size";
    /** {@code <property>,<asc|desc>}. */
    public static final String SORT = "sort";

    /** The page size when the request names none — the size the emitted Angular list starts at. */
    public static final int DEFAULT_SIZE = 20;

    /**
     * The largest page size the route serves. A larger request is refused with {@code 400} rather
     * than clamped: the server materialises a page in memory before encoding it, so the bound is
     * what keeps one request from reading a whole table, and refusing keeps the answer to a request
     * the answer it asked for.
     */
    public static final int MAX_SIZE = 100;

    /** The parameter names that are never a filter, in sorted order. */
    public static final List<String> RESERVED = List.of(PAGE, SIZE, SORT);

    /** The page envelope's members, in declaration order — the members the emitted front reads. */
    public static final List<String> ENVELOPE =
            List.of("content", "totalElements", "totalPages", "size", "number", "first", "last");

    /**
     * The kinds a filter value can be parsed into from a query parameter. A {@code List} column is
     * JSON text, so equality on it is not meaningful; {@code Instant} and {@code LocalDateTime}
     * equality matches one instant and nothing a person types, so those wait for range filters.
     */
    private static final Set<DomainTypeKind> FILTER_KINDS = EnumSet.of(
            DomainTypeKind.UUID, DomainTypeKind.STRING, DomainTypeKind.LONG, DomainTypeKind.INT,
            DomainTypeKind.BOOL, DomainTypeKind.DOUBLE, DomainTypeKind.BIG_DECIMAL,
            DomainTypeKind.LOCAL_DATE, DomainTypeKind.ENUM_LIKE);

    private ListQuerySupport() {}

    /**
     * One property the list route reads.
     *
     * @param name     the query-parameter name (filter) or sort value, and the Java field or
     *                 component name
     * @param javaType the metadata type string
     * @param kind     its {@link DomainTypeKind}
     * @param column   the SQL column it is sorted or filtered on
     */
    public record Property(String name, String javaType, DomainTypeKind kind, String column) {}

    /**
     * The properties {@code sort} accepts: every {@code @Field(sortable = true)} field — and every
     * field the processor recorded without {@code @Field}, which it marks sortable — except a
     * {@code List} field, whose column is JSON text, and a {@link #systemFieldNames system field}.
     * Sorted by name.
     *
     * @param metadata the entity
     * @return the sortable properties
     */
    public static List<Property> sortable(DomainMetadata metadata) {
        Set<String> system = systemFieldNames(metadata);
        Map<String, Property> byName = new TreeMap<>();
        for (FieldMetadata field : metadata.fields()) {
            DomainTypeKind kind = DomainTypeKind.of(field.type());
            if (field.sortable() && kind != DomainTypeKind.LIST && !system.contains(field.name())) {
                byName.putIfAbsent(field.name(), new Property(field.name(), field.type(), kind,
                        ColumnNaming.snakeCase(field.name())));
            }
        }
        return List.copyOf(byName.values());
    }

    /**
     * The filter parameters: every {@code @Field(filterable = true)} field of a kind in which a
     * query-parameter value can be parsed, then every {@code MANY_TO_ONE} relationship's foreign key
     * as {@code <base>Id=<uuid>}. A field named after a reserved parameter ({@code page},
     * {@code size}, {@code sort}) is not a filter, because the parameter means the other thing, and
     * a {@link #systemFieldNames system field} is never one. A foreign key whose parameter name a
     * field already took is the same column and is not added twice. Sorted by name.
     *
     * @param metadata the entity
     * @return the filter parameters
     */
    public static List<Property> filters(DomainMetadata metadata) {
        Set<String> system = systemFieldNames(metadata);
        Map<String, Property> byName = new TreeMap<>();
        for (FieldMetadata field : metadata.fields()) {
            DomainTypeKind kind = DomainTypeKind.of(field.type());
            if (field.filterable() && FILTER_KINDS.contains(kind) && !RESERVED.contains(field.name())
                    && !system.contains(field.name())) {
                byName.putIfAbsent(field.name(), new Property(field.name(), field.type(), kind,
                        ColumnNaming.snakeCase(field.name())));
            }
        }
        if (metadata.hasRelationships()) {
            for (RelationshipMetadata relationship : metadata.relationships()) {
                if (relationship.type() != RelationshipMetadata.RelationType.MANY_TO_ONE) {
                    continue;
                }
                String name = ColumnNaming.foreignKeyBase(relationship.name()) + "Id";
                if (!RESERVED.contains(name) && !system.contains(name)) {
                    byName.putIfAbsent(name, new Property(name, "java.util.UUID", DomainTypeKind.UUID,
                            ColumnNaming.foreignKeyColumn(relationship.name())));
                }
            }
        }
        return List.copyOf(byName.values());
    }

    /**
     * The fields that are never a sort key or a filter, whatever {@code @Field} says: the primary
     * key, the owning tenant and the shared-scope field (the fields ADR-090 makes server-owned), the
     * audit, version and soft-delete fields of an entity that has them, and every field
     * {@code SystemFieldsMetadata} names for a role. Resolved the way the repository resolves them:
     * the declared name, else the canonical default, for each role the entity's flags switch on.
     * The primary key stays the {@code ORDER BY} tiebreak.
     *
     * @param metadata the entity
     * @return the system field names, sorted
     */
    public static Set<String> systemFieldNames(DomainMetadata metadata) {
        SystemFieldsMetadata declared = metadata.systemFields();
        Set<String> names = new TreeSet<>();
        names.add("id");
        DataScopeSupport.ownerFieldName(metadata).ifPresent(names::add);
        DataScopeSupport.sharedScopeField(metadata).ifPresent(field -> names.add(field.name()));
        if (metadata.audited()) {
            names.add(role(declared == null ? null : declared.createdAtField(), "createdAt"));
            names.add(role(declared == null ? null : declared.createdByField(), "createdBy"));
            names.add(role(declared == null ? null : declared.updatedAtField(), "updatedAt"));
            names.add(role(declared == null ? null : declared.updatedByField(), "updatedBy"));
        }
        if (metadata.versioned()) {
            names.add(role(declared == null ? null : declared.versionField(), "version"));
        }
        if (metadata.softDelete()) {
            names.add(role(declared == null ? null : declared.softDeleteField(), "deleted"));
            names.add(role(declared == null ? null : declared.softDeleteTimestampField(), "deletedAt"));
            names.add(role(declared == null ? null : declared.softDeletedByField(), "deletedBy"));
        }
        if (declared != null) {
            for (String name : List.of(nullToEmpty(declared.primaryKeyField()),
                    nullToEmpty(declared.createdAtField()), nullToEmpty(declared.createdByField()),
                    nullToEmpty(declared.updatedAtField()), nullToEmpty(declared.updatedByField()),
                    nullToEmpty(declared.tenantIdField()), nullToEmpty(declared.versionField()),
                    nullToEmpty(declared.softDeleteField()), nullToEmpty(declared.softDeleteTimestampField()),
                    nullToEmpty(declared.softDeletedByField()), nullToEmpty(declared.sharedScopeField()))) {
                if (!name.isBlank()) {
                    names.add(name);
                }
            }
        }
        return Collections.unmodifiableSet(names);
    }

    private static String role(String declared, String fallback) {
        return declared == null || declared.isBlank() ? fallback : declared;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * The metadata type strings a filter accepts, both spellings, sorted — published in the
     * contract file so the front sends a filter only for a field the server parses. An enum is
     * accepted under its own type name and is not in this list.
     *
     * @return the filterable scalar type names
     */
    public static List<String> filterableScalarTypes() {
        List<String> types = new ArrayList<>(List.of(
                "UUID", "java.util.UUID", "String", "java.lang.String", "long", "Long", "java.lang.Long",
                "int", "Integer", "java.lang.Integer", "boolean", "Boolean", "java.lang.Boolean",
                "double", "Double", "java.lang.Double", "BigDecimal", "java.math.BigDecimal",
                "LocalDate", "java.time.LocalDate"));
        types.sort(null);
        return List.copyOf(types);
    }
}
