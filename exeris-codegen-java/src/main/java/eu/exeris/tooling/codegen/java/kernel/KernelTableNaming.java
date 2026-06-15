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
     */
    static String effectiveTable(DomainMetadata metadata) {
        String override = metadata.tableName();
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        return toSnakeCase(metadata.entityName()) + "s";
    }

    private static String toSnakeCase(String camelCase) {
        return camelCase.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }
}
