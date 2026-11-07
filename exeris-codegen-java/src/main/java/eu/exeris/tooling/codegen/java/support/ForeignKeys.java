package eu.exeris.tooling.codegen.java.support;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.RelationshipMetadata;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The foreign keys a build constrains, read from every entity's {@code MANY_TO_ONE} relationships.
 *
 * <p>The foreign-key migration, the repository that recognises a violation of one, the handler that
 * answers it and the OpenAPI that declares the answer all derive the constraints here, so a status
 * is declared exactly where a constraint can produce it. A relationship whose target is not an
 * entity of the build gets no constraint: the migration cannot reference a table the build did not
 * create.
 *
 * <p>A constraint deletes the referencing rows with the referenced one ({@code ON DELETE CASCADE})
 * when either side of the relationship declares {@code cascadeDelete}: the owning
 * {@code MANY_TO_ONE}, or the inverse {@code ONE_TO_MANY} whose {@code mappedBy} names it
 * ({@link #backReference}).
 *
 * @since 0.10
 */
public final class ForeignKeys {

    private ForeignKeys() {}

    /**
     * One constraint the build emits.
     *
     * @param relationship  the {@code MANY_TO_ONE} relationship the constraint enforces
     * @param table         the referencing table
     * @param column        the foreign-key column
     * @param constraint    the constraint name, {@code fk_<table>_<column>}
     * @param cascadeDelete whether deleting the referenced row deletes the referencing rows
     */
    public record Reference(RelationshipMetadata relationship, String table, String column,
                            String constraint, boolean cascadeDelete) {

        /**
         * The relationship's name: the field a request body names the referenced row through.
         *
         * @return the relationship's name
         */
        public String relation() {
            return relationship.name();
        }

        /**
         * The referenced entity's name.
         *
         * @return the target entity's name
         */
        public String targetEntity() {
            return relationship.targetEntity();
        }
    }

    /**
     * The constraints {@code source}'s rows are checked against: one per {@code MANY_TO_ONE}
     * relationship whose target is an entity of {@code build}, sorted by relationship name and then
     * constraint name — an order that depends on neither the order of {@code build} nor the order
     * the relationships are declared in.
     *
     * @param source the referencing entity
     * @param build  every entity of the build, {@code source} included
     * @return the constraints; empty when the entity references no entity of the build
     */
    public static List<Reference> outgoing(DomainMetadata source, List<DomainMetadata> build) {
        if (!source.hasRelationships()) {
            return List.of();
        }
        String table = ColumnNaming.table(source);
        List<Reference> references = new ArrayList<>();
        for (RelationshipMetadata rel : source.relationships()) {
            if (rel.type() != RelationshipMetadata.RelationType.MANY_TO_ONE
                    || !inBuild(rel.targetEntity(), build)) {
                continue;
            }
            String column = ColumnNaming.foreignKeyColumn(rel.name());
            references.add(new Reference(rel, table, column, "fk_" + table + "_" + column,
                    cascadesDelete(rel) || cascadedFromInverse(source, rel, build)));
        }
        references.sort(Comparator.comparing(Reference::relation).thenComparing(Reference::constraint));
        return List.copyOf(references);
    }

    /**
     * Whether some entity of {@code build} — {@code target} itself included — references
     * {@code target} through a constraint.
     *
     * @param target the entity that may be referenced
     * @param build  every entity of the build
     * @return {@code true} when a constraint references {@code target}'s table
     */
    public static boolean referenced(DomainMetadata target, List<DomainMetadata> build) {
        for (DomainMetadata source : build) {
            for (Reference reference : outgoing(source, build)) {
                if (reference.targetEntity().equals(target.entityName())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether a write of {@code source} can be refused for naming a row that does not exist: the
     * entity references an entity of the build.
     *
     * @param source the entity written
     * @param build  every entity of the build
     * @return {@code true} when {@code save} and {@code update} can violate a constraint
     */
    public static boolean refusesMissingReference(DomainMetadata source, List<DomainMetadata> build) {
        return !outgoing(source, build).isEmpty();
    }

    /**
     * Whether a {@code DELETE} of {@code target} can be refused because other rows still reference
     * it: the entity is referenced, and its delete removes the row. A soft delete keeps the row, so
     * no constraint sees it.
     *
     * @param target the entity deleted
     * @param build  every entity of the build
     * @return {@code true} when {@code deleteById} can violate a constraint
     */
    public static boolean refusesReferencedDelete(DomainMetadata target, List<DomainMetadata> build) {
        return !target.softDelete() && referenced(target, build);
    }

    /**
     * The {@code MANY_TO_ONE} of the inverse's target that holds the rows of {@code inverse}: the
     * first, in declaration order, of the target's relationships back to {@code owner} whose
     * relationship name, field name, key base ({@code customer} for {@code customerId}) or key
     * parameter ({@code customerId}) is the inverse's {@code mappedBy}.
     *
     * @param owner   the entity declaring the inverse
     * @param inverse a {@code ONE_TO_MANY} of {@code owner} with a {@code mappedBy}
     * @param build   every entity of the build
     * @return the back-reference; empty when the target is no entity of the build or declares no
     *         such relationship
     */
    public static Optional<RelationshipMetadata> backReference(DomainMetadata owner, RelationshipMetadata inverse,
                                                               List<DomainMetadata> build) {
        Optional<DomainMetadata> target = entityOf(inverse.targetEntity(), build);
        if (target.isEmpty() || !target.get().hasRelationships() || inverse.mappedBy() == null) {
            return Optional.empty();
        }
        for (RelationshipMetadata candidate : target.get().relationships()) {
            if (candidate.type() == RelationshipMetadata.RelationType.MANY_TO_ONE
                    && simpleName(candidate.targetEntity()).equals(owner.entityName())
                    && namedBy(candidate, inverse.mappedBy())) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * The entity of {@code build} that a relationship's target names.
     *
     * @param targetEntity the target, as a relationship records it: a simple name, or a qualified
     *                     one when the processor fell back to the field's type
     * @param build        every entity of the build
     * @return the entity; empty when no entity of the build has that name
     */
    public static Optional<DomainMetadata> entityOf(String targetEntity, List<DomainMetadata> build) {
        String name = simpleName(targetEntity);
        return build.stream().filter(domain -> domain.entityName().equals(name)).findFirst();
    }

    private static boolean namedBy(RelationshipMetadata candidate, String mappedBy) {
        String base = ColumnNaming.foreignKeyBase(candidate.name());
        return mappedBy.equals(candidate.name()) || mappedBy.equals(candidate.fieldName())
                || mappedBy.equals(base) || mappedBy.equals(base + "Id");
    }

    private static boolean cascadesDelete(RelationshipMetadata rel) {
        return rel.cascade() == RelationshipMetadata.CascadeType.ALL
                || rel.cascade() == RelationshipMetadata.CascadeType.REMOVE;
    }

    /** Whether an inverse on {@code rel}'s target declares {@code cascadeDelete} and resolves to {@code rel}. */
    private static boolean cascadedFromInverse(DomainMetadata source, RelationshipMetadata rel,
                                               List<DomainMetadata> build) {
        Optional<DomainMetadata> target = entityOf(rel.targetEntity(), build);
        if (target.isEmpty() || !target.get().hasRelationships()) {
            return false;
        }
        for (RelationshipMetadata inverse : target.get().relationships()) {
            if (InverseCollections.isInverse(inverse) && cascadesDelete(inverse)
                    && simpleName(inverse.targetEntity()).equals(source.entityName())
                    && backReference(target.get(), inverse, build).filter(rel::equals).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private static String simpleName(String entity) {
        return entity.substring(entity.lastIndexOf('.') + 1);
    }

    private static boolean inBuild(String entityName, List<DomainMetadata> build) {
        for (DomainMetadata domain : build) {
            if (domain.entityName().equals(entityName)) {
                return true;
            }
        }
        return false;
    }
}
