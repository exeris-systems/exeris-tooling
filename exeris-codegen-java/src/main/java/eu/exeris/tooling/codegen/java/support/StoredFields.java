package eu.exeris.tooling.codegen.java.support;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;

/**
 * Which fields of an entity its own row stores.
 *
 * <p>Two kinds of field are held outside the row: a {@code @Blob} field, whose bytes live in the
 * blob store ({@link BlobFields}), and an inverse collection, whose rows live in the target
 * entity's table ({@link InverseCollections}). Neither is a column, a finder, a filter or sort
 * property, or a member of the migration. Every emitter that walks an entity's fields for its
 * columns asks {@link #isColumn}, so that the repository, the migration and the list query cannot
 * disagree about which fields are stored.
 *
 * @since 0.11
 */
public final class StoredFields {

    private StoredFields() {}

    /**
     * Whether {@code field} is stored as a column of the entity's table.
     *
     * @param metadata the entity
     * @param field    one of its fields
     * @return {@code false} for a {@code @Blob} field and an inverse collection, {@code true} for
     *         every other field
     */
    public static boolean isColumn(DomainMetadata metadata, FieldMetadata field) {
        return BlobFields.isColumn(field) && !InverseCollections.isInverse(metadata, field);
    }
}
