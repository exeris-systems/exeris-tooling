package eu.exeris.tooling.codegen.java.support;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.RelationshipMetadata;

import java.util.List;
import java.util.Optional;

/**
 * The inverse collections of an entity: the {@code List} fields that a
 * {@code @Relationship(relationshipType = ONE_TO_MANY, mappedBy = ...)} declares.
 *
 * <p>The rows of an inverse collection are held by the target entity's table, through the
 * {@code MANY_TO_ONE} that {@code mappedBy} names ({@link ForeignKeys#backReference}); the entity's
 * own row holds nothing for it. The field is no column ({@link StoredFields#isColumn}), in neither
 * request body, and {@code null} in the entity's JSON: the rows are read from the target's list
 * route, filtered by the back-reference's key. The classification reads the entity alone, so every
 * per-entity emitter agrees on it; whether {@code mappedBy} resolves is checked against the whole
 * build before anything is written.
 *
 * @since 0.11
 */
public final class InverseCollections {

    private InverseCollections() {}

    /**
     * Whether {@code field} is an inverse collection of {@code metadata}.
     *
     * @param metadata the entity
     * @param field    one of its fields
     * @return {@code true} for a {@code List} field whose relationship is a {@code ONE_TO_MANY}
     *         with a {@code mappedBy}
     */
    public static boolean isInverse(DomainMetadata metadata, FieldMetadata field) {
        return relationship(metadata, field).isPresent();
    }

    /**
     * The {@code ONE_TO_MANY} relationship that makes {@code field} an inverse collection.
     *
     * @param metadata the entity
     * @param field    one of its fields
     * @return the relationship; empty when the field is no inverse collection
     */
    public static Optional<RelationshipMetadata> relationship(DomainMetadata metadata, FieldMetadata field) {
        if (!metadata.hasRelationships() || DomainTypeKind.of(field) != DomainTypeKind.LIST) {
            return Optional.empty();
        }
        for (RelationshipMetadata rel : metadata.relationships()) {
            if (rel.fieldName().equals(field.name()) && isInverse(rel)) {
                return Optional.of(rel);
            }
        }
        return Optional.empty();
    }

    /**
     * Whether {@code relationship} is the inverse side of a {@code MANY_TO_ONE}: a
     * {@code ONE_TO_MANY} that names its back-reference through {@code mappedBy}.
     *
     * @param relationship the relationship
     * @return {@code true} for a {@code ONE_TO_MANY} with a non-blank {@code mappedBy}
     */
    public static boolean isInverse(RelationshipMetadata relationship) {
        return relationship.type() == RelationshipMetadata.RelationType.ONE_TO_MANY
                && relationship.mappedBy() != null && !relationship.mappedBy().isBlank();
    }

    /**
     * The entity's inverse collections.
     *
     * @param metadata the entity
     * @return the fields, in declaration order; empty when there are none
     */
    public static List<FieldMetadata> of(DomainMetadata metadata) {
        if (!metadata.hasFields()) {
            return List.of();
        }
        return metadata.fields().stream().filter(field -> isInverse(metadata, field)).toList();
    }
}
