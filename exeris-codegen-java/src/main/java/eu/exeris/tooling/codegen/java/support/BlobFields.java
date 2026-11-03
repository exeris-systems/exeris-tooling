package eu.exeris.tooling.codegen.java.support;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;

import java.util.List;

/**
 * The {@code @Blob} fields of an entity and what the generated code derives from them (ADR-108).
 *
 * <p>A {@code @Blob} field's bytes live in the kernel's blob store, under a key derived from the row
 * and the field, and never in the row: it is no column, no finder, no filter or sort property, and
 * no member of the migration. Every emitter that walks an entity's fields for its columns asks
 * {@link #isColumn} so that the repository, the migration and the list query cannot disagree about
 * which fields are stored. The field stays a member of the entity class and of its JSON, where the
 * generated application leaves it {@code null}.
 *
 * @since 0.11
 */
public final class BlobFields {

    /** The path segment between the row's path and the field's name. */
    public static final String SEGMENT = "blobs";

    private BlobFields() {}

    /**
     * Whether {@code field} is stored as a column of the entity's table.
     *
     * @param field the field
     * @return {@code false} for a {@code @Blob} field, {@code true} for every other field
     */
    public static boolean isColumn(FieldMetadata field) {
        return !field.hasBlob();
    }

    /**
     * The entity's {@code @Blob} fields.
     *
     * @param metadata the entity
     * @return the fields, in declaration order; empty when there are none
     */
    public static List<FieldMetadata> of(DomainMetadata metadata) {
        if (!metadata.hasFields()) {
            return List.of();
        }
        return metadata.fields().stream().filter(FieldMetadata::hasBlob).toList();
    }

    /**
     * Whether the entity declares a {@code @Blob} field.
     *
     * @param metadata the entity
     * @return {@code true} when at least one field carries a blob facet
     */
    public static boolean any(DomainMetadata metadata) {
        return metadata.hasFields() && metadata.fields().stream().anyMatch(FieldMetadata::hasBlob);
    }

    /**
     * The container a field's objects are addressed in: the declared {@code @Blob.container}, or the
     * entity's table name when none is declared.
     *
     * @param metadata the entity
     * @param field    one of its {@code @Blob} fields
     * @return the container name
     */
    public static String container(DomainMetadata metadata, FieldMetadata field) {
        return field.blob().hasContainer() ? field.blob().container() : ColumnNaming.table(metadata);
    }

    /**
     * The path of a field's routes, relative to the entity's base path: {@code /{id}/blobs/<field>}.
     *
     * @param field one of the entity's {@code @Blob} fields
     * @return the path suffix, starting with {@code /}
     */
    public static String routeSuffix(FieldMetadata field) {
        return "/{id}/" + SEGMENT + "/" + field.name();
    }
}
