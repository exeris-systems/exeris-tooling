package eu.exeris.tooling.codegen.java.support;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;

import java.util.Locale;

/**
 * The primary key of an entity as the generated code names it: the field, its column, and the
 * accessors the generated Java calls.
 *
 * <p>The migration, the repository's SQL, the foreign-key targets, the entity accessors, the
 * OpenAPI schema and the generated tests all identify a row by the same field, so each takes its
 * name here and nowhere else. The route path variable and the {@code findById} / {@code deleteById}
 * / {@code UUID id} names are not the key: they name a URL segment and a role, and stay literal.
 *
 * @since 0.10
 */
public final class PrimaryKeys {

    private static final String DEFAULT_FIELD = "id";

    private PrimaryKeys() {}

    /**
     * The Java name of the field that holds the row's key; it is also the name of the key's JSON
     * and OpenAPI schema property.
     *
     * @param metadata the entity metadata
     * @return the key field's name
     */
    public static String field(DomainMetadata metadata) {
        return DEFAULT_FIELD;
    }

    /**
     * The SQL column of the key, snake-cased like every other column.
     *
     * @param metadata the entity metadata
     * @return the key column's name
     */
    public static String column(DomainMetadata metadata) {
        return ColumnNaming.snakeCase(field(metadata));
    }

    /**
     * The accessor the generated Java reads the key through.
     *
     * @param metadata the entity metadata
     * @return the getter's name, e.g. {@code getId}
     */
    public static String getter(DomainMetadata metadata) {
        return "get" + capitalize(field(metadata));
    }

    /**
     * The accessor the generated Java writes the key through.
     *
     * @param metadata the entity metadata
     * @return the setter's name, e.g. {@code setId}
     */
    public static String setter(DomainMetadata metadata) {
        return "set" + capitalize(field(metadata));
    }

    /**
     * Whether a field name denotes the key: the key field itself, or a name whose column is the
     * key's column.
     *
     * @param metadata  the entity metadata
     * @param fieldName a Java field name
     * @return true when the field is the key
     */
    public static boolean isKey(DomainMetadata metadata, String fieldName) {
        return field(metadata).equals(fieldName)
                || column(metadata).equals(ColumnNaming.snakeCase(fieldName));
    }

    private static String capitalize(String name) {
        return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }
}
