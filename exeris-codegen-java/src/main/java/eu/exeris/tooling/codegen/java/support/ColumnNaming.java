package eu.exeris.tooling.codegen.java.support;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;

import java.util.Locale;

/**
 * SQL column naming shared by the emitted repository, migrations and list query.
 *
 * <p>The schema, the repository's SQL and the list route's sort and filter whitelist all name the
 * same columns, so they derive the names here and nowhere else.
 *
 * @since 0.9
 */
public final class ColumnNaming {

    private ColumnNaming() {}

    /**
     * The column of a field: {@code orderNumber} → {@code order_number}, under {@link Locale#ROOT}.
     *
     * @param camelCase a Java field name
     * @return its column name
     */
    public static String snakeCase(String camelCase) {
        return camelCase.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    /**
     * Base name of a {@code MANY_TO_ONE} relationship's foreign key, with a trailing {@code Id}
     * stripped, so the explicit-UUID style ({@code customerId}) and the entity-typed style
     * ({@code customer}) both reduce to {@code customer}. Idempotent.
     *
     * @param relationshipName the relationship's name
     * @return the base name
     */
    public static String foreignKeyBase(String relationshipName) {
        if (relationshipName.length() > 2 && relationshipName.endsWith("Id")) {
            return relationshipName.substring(0, relationshipName.length() - 2);
        }
        return relationshipName;
    }

    /**
     * The foreign-key column of a {@code MANY_TO_ONE} relationship: {@code customer} and
     * {@code customerId} both → {@code customer_id}.
     *
     * @param relationshipName the relationship's name
     * @return the column name
     */
    public static String foreignKeyColumn(String relationshipName) {
        return snakeCase(foreignKeyBase(relationshipName)) + "_id";
    }

    /**
     * The table of an entity: the explicit {@code @ExerisDomain.tableName} override when present and
     * non-blank, trimmed and lower-cased under {@link Locale#ROOT}, otherwise
     * {@link DomainMetadata#effectiveTableName()}.
     *
     * <p>Every table name this returns is lower-case, so the migration filename stays predictable
     * and the name is the same on PostgreSQL, which folds unquoted identifiers to lower-case, as on
     * engines that do not. A mixed-case, quoted table name is not supported.
     *
     * @param metadata the entity
     * @return its table name
     */
    public static String table(DomainMetadata metadata) {
        String override = metadata.tableName();
        if (override != null && !override.isBlank()) {
            return override.trim().toLowerCase(Locale.ROOT);
        }
        return metadata.effectiveTableName();
    }
}
