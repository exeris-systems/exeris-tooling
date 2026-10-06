package eu.exeris.tooling.codegen.java.support;

import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;

import java.util.HashSet;
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
 * pipeline sets it from the enums the processor emitted ({@link EnumTypes}). Metadata handed to a
 * generator without passing through the pipeline carries {@code enumType} only where its author set
 * it; an enum field without it is {@link #OPAQUE}, stored and read back exactly as an enum is but
 * neither sorted nor filtered on, unless its type is spelled like a refused JDK type
 * ({@code Duration}, say), which is then refused as that type.
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
    /**
     * {@code Short}: a {@code SMALLINT} column, written through {@code bindShort} and read through
     * {@code getShort}. A list filter and a sort key.
     */
    SHORT,
    /**
     * {@code Byte}: a {@code SMALLINT} column — SQL has no one-byte integer type — written through
     * {@code bindShort} and read through {@code getShort} narrowed to {@code byte}. A list filter and
     * a sort key.
     */
    BYTE,
    BOOL,
    /**
     * {@code Float}: a {@code REAL} column, written through {@code bindFloat} and read through
     * {@code getFloat}. A list filter and a sort key, as {@code Double} is.
     */
    FLOAT,
    DOUBLE,
    /**
     * {@code BigDecimal}: no typed SPI accessor, so it is written as its plain string and the
     * statement casts that parameter to the column's {@code DECIMAL} type; read back through
     * {@code getString}.
     */
    BIG_DECIMAL,
    /** {@code Instant} and anything else whose name contains it — a {@code TIMESTAMPTZ} column. */
    INSTANT_LIKE,
    LOCAL_DATE_TIME,
    /**
     * {@code LocalDate}: no typed SPI accessor, so it is written as its ISO string and the statement
     * casts that parameter to {@code DATE}; read back through {@code getString}.
     */
    LOCAL_DATE,
    /**
     * {@code OffsetDateTime}: stored as its instant in a {@code TIMESTAMPTZ} column and read back at
     * {@code ZoneOffset.UTC}. A sort key, ordered by instant.
     */
    OFFSET_DATE_TIME,
    /**
     * {@code ZonedDateTime}: stored as its instant in a {@code TIMESTAMPTZ} column and read back at
     * {@code ZoneOffset.UTC}. A sort key, ordered by instant.
     */
    ZONED_DATE_TIME,
    /**
     * A type the repository has no column encoding for, so generation refuses the field
     * ({@code EXT-GEN-3003}): a parameterised type other than {@code List}, an array, {@code char}
     * or {@code Character}, {@code BigInteger}, and the JDK value types that have no static
     * {@code valueOf(String)}: {@code LocalTime}, {@code OffsetTime}, {@code Duration},
     * {@code Period}, {@code Year}, {@code YearMonth}, {@code MonthDay}, {@code ZoneId},
     * {@code ZoneOffset}, {@code java.util.Date}, {@code Currency}, {@code Locale}, {@code URI},
     * {@code URL} and {@code Object}. Each of these produced either a generator failure or a
     * repository that did not compile.
     */
    UNSTORABLE,
    /**
     * A field typed as an enum: stored as its constant's name and read back through the enum's
     * {@code valueOf(String)}. A list filter and a sort key.
     */
    ENUM,
    /**
     * A type nothing here recognises and nothing refuses — a record, a value type of the
     * application's own, an enum the metadata does not mark as one. The repository stores it
     * through {@code toString()} and reads it back through the type's static
     * {@code valueOf(String)}, which compiles only for a type that has one; whether it does cannot be
     * known from a type name. Its column may hold an engine-specific rendering, so it is never a
     * list filter or a sort key.
     */
    OPAQUE;

    private static final String LIST_PREFIX = "List<";
    private static final String QUALIFIED_LIST_PREFIX = "java.util.List<";
    private static final Set<String> UUID_TYPES = Set.of("UUID", "java.util.UUID");
    private static final Set<String> STRING_TYPES = Set.of("String", "java.lang.String");
    private static final Set<String> LONG_TYPES = Set.of("Long", "long", "java.lang.Long");
    private static final Set<String> INT_TYPES = Set.of("Integer", "int", "java.lang.Integer");
    private static final Set<String> SHORT_TYPES = Set.of("Short", "short", "java.lang.Short");
    private static final Set<String> BYTE_TYPES = Set.of("Byte", "byte", "java.lang.Byte");
    private static final Set<String> BOOL_TYPES = Set.of("Boolean", "boolean", "java.lang.Boolean");
    private static final Set<String> FLOAT_TYPES = Set.of("Float", "float", "java.lang.Float");
    private static final Set<String> DOUBLE_TYPES = Set.of("Double", "double", "java.lang.Double");
    private static final Set<String> BIG_DECIMAL_TYPES = Set.of("BigDecimal", "java.math.BigDecimal");
    private static final Set<String> OFFSET_DATE_TIME_TYPES = Set.of("OffsetDateTime", "java.time.OffsetDateTime");
    private static final Set<String> ZONED_DATE_TIME_TYPES = Set.of("ZonedDateTime", "java.time.ZonedDateTime");
    /** The primitive spellings; every other spelling of a scalar kind is its wrapper and may be {@code null}. */
    private static final Set<String> PRIMITIVES =
            Set.of("long", "int", "short", "byte", "boolean", "float", "double", "char");

    /**
     * The JDK types with no column encoding, in both spellings: none has a static
     * {@code valueOf(String)} the repository's read could call, and none has a typed SPI accessor.
     */
    private static final Set<String> UNSTORABLE_TYPES = bothSpellings(
            "java.math.BigInteger", "java.lang.Character", "java.lang.Object",
            "java.time.LocalTime", "java.time.OffsetTime", "java.time.Duration", "java.time.Period",
            "java.time.Year", "java.time.YearMonth", "java.time.MonthDay", "java.time.ZoneId",
            "java.time.ZoneOffset", "java.util.Date", "java.util.Currency", "java.util.Locale",
            "java.net.URI", "java.net.URL");

    /**
     * The kind of a field: {@link #ENUM} when its {@code enumType} is set and its type is not a
     * recognised scalar — an enum the application named like a refused JDK type included, since
     * {@code enumType} is only ever set to an enum the processor emitted — else the kind of its type
     * string.
     *
     * @param field the field
     * @return its kind
     */
    public static DomainTypeKind of(FieldMetadata field) {
        DomainTypeKind kind = of(field.type());
        boolean enumCandidate = kind == OPAQUE
                || (kind == UNSTORABLE && !field.type().contains("<") && !field.type().endsWith("[]"));
        return enumCandidate && field.isEnum() ? ENUM : kind;
    }

    /**
     * The kind of a metadata type string.
     *
     * @param type a {@code FieldMetadata.type()} value
     * @return its kind; {@link #OPAQUE} for anything not otherwise recognised, an enum included
     */
    public static DomainTypeKind of(String type) {
        if (listElementType(type) != null) return LIST;
        // Any other parameterised type or an array: its arguments or component may name a
        // recognised type (Map<String, LocalDate>, Instant[]), which the containment checks below
        // would match.
        if (type.contains("<") || type.endsWith("[]")) return UNSTORABLE;
        if (UUID_TYPES.contains(type)) return UUID;
        if (STRING_TYPES.contains(type)) return STRING;
        if (LONG_TYPES.contains(type)) return LONG;
        if (INT_TYPES.contains(type)) return INT;
        if (SHORT_TYPES.contains(type)) return SHORT;
        if (BYTE_TYPES.contains(type)) return BYTE;
        if (BOOL_TYPES.contains(type)) return BOOL;
        if (FLOAT_TYPES.contains(type)) return FLOAT;
        if (DOUBLE_TYPES.contains(type)) return DOUBLE;
        if (BIG_DECIMAL_TYPES.contains(type)) return BIG_DECIMAL;
        if (OFFSET_DATE_TIME_TYPES.contains(type)) return OFFSET_DATE_TIME;
        if (ZONED_DATE_TIME_TYPES.contains(type)) return ZONED_DATE_TIME;
        if ("char".equals(type) || UNSTORABLE_TYPES.contains(type)) return UNSTORABLE;
        if (type.contains("Instant")) return INSTANT_LIKE;
        // LocalDateTime before LocalDate: "LocalDateTime".contains("LocalDate").
        if (type.contains("LocalDateTime")) return LOCAL_DATE_TIME;
        if (type.contains("LocalDate")) return LOCAL_DATE;
        return OPAQUE;
    }

    /**
     * Whether {@code type} is a primitive spelling, whose value is never {@code null}. Every other
     * spelling of a scalar is a reference the repository null-guards on write and reads back as
     * {@code null} from a SQL {@code NULL}.
     *
     * @param type a {@code FieldMetadata.type()} value
     * @return whether it names a primitive type
     */
    public static boolean isPrimitive(String type) {
        return PRIMITIVES.contains(type);
    }

    private static Set<String> bothSpellings(String... qualified) {
        Set<String> names = new HashSet<>();
        for (String name : qualified) {
            names.add(name);
            names.add(name.substring(name.lastIndexOf('.') + 1));
        }
        return Set.copyOf(names);
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
