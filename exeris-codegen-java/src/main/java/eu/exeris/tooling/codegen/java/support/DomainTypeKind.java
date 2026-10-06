package eu.exeris.tooling.codegen.java.support;

import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;

import java.util.List;
import java.util.Set;

/**
 * How the emitted persistence code treats a {@code FieldMetadata.type()} string: which
 * {@code PersistenceStatement} bind and {@code RowCursor} read it goes through, and, for the list
 * route, whether it can be parsed from a query parameter.
 *
 * <p>One classification, read by the repository emitter (binds and reads) and by
 * {@link ListQuerySupport} (which fields the list route may sort and filter on), so a column the
 * repository binds as a string is also the column the list filter binds as a string.
 *
 * <p>Both spellings of every type are accepted: {@code FieldMetadata.type()} comes from
 * {@code VariableElement.asType().toString()}, which javac renders fully qualified, while
 * hand-built metadata usually carries the short form.
 *
 * <p>A type string alone cannot say whether it names an enum, so {@link #of(String)} never answers
 * {@link #ENUM}; {@link #of(FieldMetadata)} does, for a field whose {@code enumType} is set. The
 * pipeline sets it from the enums the processor emitted ({@link EnumTypes}).
 *
 * @since 0.9.0
 */
public enum DomainTypeKind {
    /** {@code List<X>}, persisted as a JSON text column. */
    LIST,
    UUID,
    STRING,
    LONG,
    INT,
    BOOL,
    DOUBLE,
    BIG_DECIMAL,
    /** {@code Instant} and anything else whose name contains it — a {@code TIMESTAMPTZ} column. */
    INSTANT_LIKE,
    LOCAL_DATE_TIME,
    LOCAL_DATE,
    /**
     * A field typed as an enum: stored as its constant's name and read back through the enum's
     * {@code valueOf(String)}. A list filter and a sort key.
     */
    ENUM,
    /**
     * A type nothing here recognises — a map, a set, a record, {@code OffsetDateTime},
     * {@code BigInteger}. The repository stores it through {@code toString()} and reads it back
     * through the type's static {@code valueOf(String)}, which compiles only for a type that has
     * one; its column may hold JSON or an engine-specific rendering, so it is never a list filter
     * or a sort key.
     */
    OPAQUE;

    private static final String LIST_PREFIX = "List<";
    private static final String QUALIFIED_LIST_PREFIX = "java.util.List<";
    private static final Set<String> UUID_TYPES = Set.of("UUID", "java.util.UUID");
    private static final Set<String> STRING_TYPES = Set.of("String", "java.lang.String");
    private static final Set<String> LONG_TYPES = Set.of("Long", "long", "java.lang.Long");
    private static final Set<String> INT_TYPES = Set.of("Integer", "int", "java.lang.Integer");
    private static final Set<String> BOOL_TYPES = Set.of("Boolean", "boolean", "java.lang.Boolean");
    private static final Set<String> DOUBLE_TYPES = Set.of("Double", "double", "java.lang.Double");
    private static final Set<String> BIG_DECIMAL_TYPES = Set.of("BigDecimal", "java.math.BigDecimal");

    /**
     * The kind of a field: {@link #ENUM} when its type is not otherwise recognised and its
     * {@code enumType} is set, else the kind of its type string.
     *
     * @param field the field
     * @return its kind
     */
    public static DomainTypeKind of(FieldMetadata field) {
        DomainTypeKind kind = of(field.type());
        return kind == OPAQUE && field.isEnum() ? ENUM : kind;
    }

    /**
     * The kind of a metadata type string.
     *
     * @param type a {@code FieldMetadata.type()} value
     * @return its kind; {@link #OPAQUE} for anything not otherwise recognised, an enum included
     */
    public static DomainTypeKind of(String type) {
        if (listElementType(type) != null) return LIST;
        // Any other parameterised type: its arguments may name a recognised type
        // (Map<String, LocalDate>), which the containment checks below would match.
        if (type.contains("<")) return OPAQUE;
        if (UUID_TYPES.contains(type)) return UUID;
        if (STRING_TYPES.contains(type)) return STRING;
        if (LONG_TYPES.contains(type)) return LONG;
        if (INT_TYPES.contains(type)) return INT;
        if (BOOL_TYPES.contains(type)) return BOOL;
        if (DOUBLE_TYPES.contains(type)) return DOUBLE;
        if (BIG_DECIMAL_TYPES.contains(type)) return BIG_DECIMAL;
        if (type.contains("Instant")) return INSTANT_LIKE;
        // LocalDateTime before LocalDate: "LocalDateTime".contains("LocalDate").
        if (type.contains("LocalDateTime")) return LOCAL_DATE_TIME;
        if (type.contains("LocalDate")) return LOCAL_DATE;
        return OPAQUE;
    }

    /**
     * The element type of a {@code List}-typed field, in either spelling, or {@code null} when the
     * type is not a list.
     *
     * @param type a {@code FieldMetadata.type()} value
     * @return the element type string, or {@code null}
     */
    public static String listElementType(String type) {
        for (String prefix : List.of(LIST_PREFIX, QUALIFIED_LIST_PREFIX)) {
            if (type.startsWith(prefix) && type.endsWith(">")) {
                return type.substring(prefix.length(), type.length() - 1);
            }
        }
        return null;
    }
}
