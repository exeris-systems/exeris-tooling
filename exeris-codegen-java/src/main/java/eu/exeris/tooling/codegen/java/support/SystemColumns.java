package eu.exeris.tooling.codegen.java.support;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.SystemFieldsMetadata;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * The system columns of an entity's table: which system roles are switched on, and the field and
 * column each one is held in.
 *
 * <p>A role is active only when the entity's flag for it is on: the owning tenant when the rows are
 * tenant-partitioned ({@link DataScopeSupport#isTenantPartitioned}), the audit times and actors
 * when {@code audited}, the soft-delete flag, time and actor when {@code softDelete}, the version
 * when {@code versioned}. A field that carries a system marker, or a role's canonical name, on an
 * entity whose flag for that role is off is an ordinary domain column: the migration creates it from
 * the field's declared type and the repository reads and writes it like any other field, with no
 * locking and no stamping.
 *
 * <p>The migration and the repository's SQL name the same columns only if both decide the question
 * the same way, so both ask here. The primary key ({@link PrimaryKeys}) is a system column whatever
 * the flags say.
 *
 * @since 0.11
 */
public final class SystemColumns {

    /**
     * A system role: the canonical field name, and the {@code systemFields} entry that renames it.
     */
    public enum Role {
        /** The owning tenant. */
        TENANT_ID("tenantId", SystemFieldsMetadata::tenantIdField),
        /** The creation time. */
        CREATED_AT("createdAt", SystemFieldsMetadata::createdAtField),
        /** The creating actor. */
        CREATED_BY("createdBy", SystemFieldsMetadata::createdByField),
        /** The time of the last write. */
        UPDATED_AT("updatedAt", SystemFieldsMetadata::updatedAtField),
        /** The actor of the last write. */
        UPDATED_BY("updatedBy", SystemFieldsMetadata::updatedByField),
        /** The soft-delete flag. */
        DELETED("deleted", SystemFieldsMetadata::softDeleteField),
        /** The soft-delete time. */
        DELETED_AT("deletedAt", SystemFieldsMetadata::softDeleteTimestampField),
        /** The soft-deleting actor. */
        DELETED_BY("deletedBy", SystemFieldsMetadata::softDeletedByField),
        /** The optimistic-lock version. */
        VERSION("version", SystemFieldsMetadata::versionField);

        private final String canonicalField;
        private final Function<SystemFieldsMetadata, String> declared;

        Role(String canonicalField, Function<SystemFieldsMetadata, String> declared) {
            this.canonicalField = canonicalField;
            this.declared = declared;
        }

        /**
         * Whether the entity's flags switch this role on.
         *
         * @param metadata the entity
         * @return true when the role is active
         */
        public boolean isActive(DomainMetadata metadata) {
            return switch (this) {
                case TENANT_ID -> DataScopeSupport.isTenantPartitioned(metadata);
                case CREATED_AT, CREATED_BY, UPDATED_AT, UPDATED_BY -> metadata.audited();
                case DELETED, DELETED_AT, DELETED_BY -> metadata.softDelete();
                case VERSION -> metadata.versioned();
            };
        }
    }

    private SystemColumns() {}

    /**
     * The Java name of the field holding a role: the name the {@code systemFields} block declares
     * when present and non-blank, else the canonical name.
     *
     * @param metadata the entity
     * @param role     the role
     * @return the field's name
     */
    public static String field(DomainMetadata metadata, Role role) {
        SystemFieldsMetadata declared = metadata.systemFields();
        String named = declared == null ? null : role.declared.apply(declared);
        return named == null || named.isBlank() ? role.canonicalField : named;
    }

    /**
     * The SQL column of a role, snake-cased like every other column.
     *
     * @param metadata the entity
     * @param role     the role
     * @return the column's name
     */
    public static String column(DomainMetadata metadata, Role role) {
        return ColumnNaming.snakeCase(field(metadata, role));
    }

    /**
     * The fields of every active role.
     *
     * @param metadata the entity
     * @return the field names, sorted
     */
    public static Set<String> activeFields(DomainMetadata metadata) {
        Set<String> fields = new TreeSet<>();
        for (Role role : Role.values()) {
            if (role.isActive(metadata)) {
                fields.add(field(metadata, role));
            }
        }
        return Collections.unmodifiableSet(fields);
    }

    /**
     * The columns of every active role.
     *
     * @param metadata the entity
     * @return the column names, sorted
     */
    public static Set<String> activeColumns(DomainMetadata metadata) {
        Set<String> columns = new TreeSet<>();
        for (Role role : Role.values()) {
            if (role.isActive(metadata)) {
                columns.add(column(metadata, role));
            }
        }
        return Collections.unmodifiableSet(columns);
    }

    /**
     * Whether a field's column is a system column of the table: the primary key's, or an active
     * role's. Every other field the table stores is a domain column.
     *
     * @param metadata  the entity
     * @param fieldName a Java field name
     * @return true when the field's column is a system column
     */
    public static boolean isSystemColumn(DomainMetadata metadata, String fieldName) {
        return PrimaryKeys.isKey(metadata, fieldName)
                || activeColumns(metadata).contains(ColumnNaming.snakeCase(fieldName));
    }
}
