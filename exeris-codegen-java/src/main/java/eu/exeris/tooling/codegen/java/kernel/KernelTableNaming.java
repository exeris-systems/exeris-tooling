package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;

import java.util.Locale;

/**
 * Single source of truth for the SQL table name a kernel entity maps to.
 *
 * <p>Every emitter that names the table reads it here — the repository's {@code TABLE} field and
 * SQL, the Flyway {@code CREATE TABLE} and migration filename, the foreign-key targets, the
 * shared-scope migration and the graph-sync node descriptor — so they cannot disagree.
 *
 * <p>The default is the SDK's {@link DomainMetadata#effectiveTableName()}: the snake-cased
 * English plural of the entity name ({@code Order} → {@code orders}, {@code ConstructionOrder}
 * → {@code construction_orders}, {@code Colony} → {@code colonies}, {@code Box} →
 * {@code boxes}). The processor warns for an entity whose derived table differs from
 * {@code toSnakeCase(entityName) + "s"}, naming the override that keeps the existing table.
 */
final class KernelTableNaming {

    private KernelTableNaming() {}

    /**
     * Effective SQL table name: the explicit {@code @ExerisDomain.tableName} override when present
     * and non-blank, otherwise {@link DomainMetadata#effectiveTableName()}.
     *
     * <p>The override is trimmed and lower-cased under {@link Locale#ROOT}, so every table name
     * this returns is lower-case: the migration filename ({@code V…__create_<table>.sql}) stays
     * predictable, and the name is the same on PostgreSQL, which folds unquoted identifiers to
     * lower-case, as on engines that do not. A mixed-case, quoted table name is not supported.
     */
    static String effectiveTable(DomainMetadata metadata) {
        String override = metadata.tableName();
        if (override != null && !override.isBlank()) {
            return override.trim().toLowerCase(Locale.ROOT);
        }
        return metadata.effectiveTableName();
    }

    /**
     * Snake-cased SQL column name for a MANY_TO_ONE relationship (T8/T9 convention,
     * idempotent on a trailing {@code Id}). Both the FK <em>column</em> (T8,
     * {@link KernelFlywayGenerator}) and the FK <em>constraint</em> (T9,
     * {@link KernelApplicationGenerator}) derive the column name here so the
     * {@code ALTER TABLE … FOREIGN KEY (<col>)} always targets the column the
     * {@code CREATE TABLE} emitted.
     *
     * <p>The entity-typed {@code @Relationship Customer customer} → {@code customer_id};
     * the explicit-UUID-FK {@code @Relationship UUID customerId} → {@code customer_id}
     * (not {@code customer_id_id}).
     */
    static String foreignKeyColumn(String relationshipName) {
        return toSnakeCase(foreignKeyBase(relationshipName)) + "_id";
    }

    /**
     * Base name for a FK column/finder, stripping a trailing {@code Id} so the
     * explicit-UUID-FK style ({@code customerId}) and the entity-typed style
     * ({@code customer}) both reduce to {@code customer}. Idempotent. Drives both
     * the FK column ({@link #foreignKeyColumn}) and the {@code findBy<Rel>Id}
     * finder name + parameter name in the repository/service emitters.
     */
    static String foreignKeyBase(String relationshipName) {
        if (relationshipName.length() > 2 && relationshipName.endsWith("Id")) {
            return relationshipName.substring(0, relationshipName.length() - 2);
        }
        return relationshipName;
    }

    private static String toSnakeCase(String camelCase) {
        return camelCase.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
