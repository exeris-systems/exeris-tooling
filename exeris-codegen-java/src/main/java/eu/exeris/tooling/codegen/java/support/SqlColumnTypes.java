package eu.exeris.tooling.codegen.java.support;

/**
 * The SQL column type a domain field of a given metadata type string is declared as — the one
 * mapping the migration's {@code CREATE TABLE} and the repository's statements both read.
 *
 * <p>The repository needs it wherever it binds a value as text into a column of another type: the
 * kernel SPI has no decimal or date bind, so a {@code BigDecimal} or a {@code LocalDate} travels as
 * a string, and PostgreSQL types a string parameter {@code character varying} and refuses it
 * against a {@code numeric} or {@code date} column. The statement therefore casts that placeholder
 * to the column's type, and the type it casts to has to be the one the migration declared — so it
 * is read here, from the same function, and the two cannot drift apart.
 *
 * @since 0.9
 */
public final class SqlColumnTypes {

    private SqlColumnTypes() {}

    /**
     * The SQL type of a domain column. Matched on the simple name, so both spellings of a type map
     * alike; anything unrecognised, a {@code List<X>} (stored as JSON text) and an enum included,
     * is {@code VARCHAR(255)}.
     *
     * @param javaType a {@code FieldMetadata.type()} value
     * @return the SQL type, as the migration declares it
     */
    public static String of(String javaType) {
        return switch (simpleName(javaType)) {
            case "String" -> "VARCHAR(255)";
            case "UUID" -> "UUID";
            case "Long", "long" -> "BIGINT";
            case "Integer", "int" -> "INTEGER";
            case "Short", "short", "Byte", "byte" -> "SMALLINT";
            case "BigDecimal" -> "DECIMAL(19,4)";
            case "Double", "double" -> "DOUBLE PRECISION";
            case "Float", "float" -> "REAL";
            case "Boolean", "boolean" -> "BOOLEAN";
            case "Instant", "LocalDateTime", "OffsetDateTime", "ZonedDateTime" -> "TIMESTAMPTZ";
            case "LocalDate" -> "DATE";
            case "LocalTime" -> "TIME";
            default -> "VARCHAR(255)";
        };
    }

    /**
     * The placeholder a statement binds a value of {@code kind} through: {@code ?}, or, for a kind
     * written as text into a column of another type ({@code BigDecimal}, {@code LocalDate}), the
     * placeholder cast to the column's SQL type.
     *
     * @param kind     the column's kind
     * @param javaType its metadata type string
     * @return the placeholder text
     */
    public static String placeholder(DomainTypeKind kind, String javaType) {
        return switch (kind) {
            case BIG_DECIMAL, LOCAL_DATE -> "CAST(? AS " + of(javaType) + ")";
            default -> "?";
        };
    }

    private static String simpleName(String javaType) {
        if (javaType == null) {
            return "";
        }
        return javaType.contains(".") ? javaType.substring(javaType.lastIndexOf('.') + 1) : javaType;
    }
}
