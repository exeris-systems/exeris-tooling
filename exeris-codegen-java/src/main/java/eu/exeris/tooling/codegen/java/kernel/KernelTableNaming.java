package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;

/**
 * Single source of truth for the SQL table name a kernel entity maps to.
 *
 * <p>Used by both {@link KernelRepositoryGenerator} (the {@code TABLE} field and
 * all emitted SQL) and {@link KernelFlywayGenerator} (the {@code CREATE TABLE}
 * and the migration filename). Keeping the derivation here guarantees the
 * repository and the migration always agree on the table name — previously each
 * generator computed {@code toSnakeCase(entityName) + "s"} independently (T6).
 *
 * <p><b>Determinism:</b> when no {@code tableName} override is present this
 * returns exactly {@code toSnakeCase(entityName) + "s"} — byte-identical to the
 * pre-T6 derivation. Note this intentionally differs from
 * {@link DomainMetadata#effectiveTableName()} (which drops the trailing
 * {@code "s"}); switching to that method would change default output and is
 * therefore avoided.
 */
final class KernelTableNaming {

    private KernelTableNaming() {}

    /**
     * Effective SQL table name: the explicit {@code @ExerisDomain} table-name
     * override when present and non-blank, otherwise the snake-cased,
     * naively-pluralised entity name.
     *
     * <p>The override is trimmed and lower-cased: this matches the default path
     * (always lower-case via {@code toSnakeCase}), keeps the emitted migration
     * filename predictable ({@code V…__create_<table>.sql}), and avoids surprises
     * on case-sensitive engines (PostgreSQL folds unquoted identifiers to
     * lower-case, MySQL and others do not). A genuinely mixed-case, quoted table
     * name is out of scope for 0.5.x.
     */
    static String effectiveTable(DomainMetadata metadata) {
        String override = metadata.tableName();
        if (override != null && !override.isBlank()) {
            return override.trim().toLowerCase();
        }
        return toSnakeCase(metadata.entityName()) + "s";
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
        return camelCase.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }
}
