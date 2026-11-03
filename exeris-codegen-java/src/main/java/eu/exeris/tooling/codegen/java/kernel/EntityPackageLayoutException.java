package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.diagnostics.DiagnosticId;

import java.util.List;

/**
 * Generation refused: an entity's package does not end in {@code .domain}
 * ({@link DiagnosticId#ENTITY_PACKAGE_NOT_DOMAIN}).
 *
 * <p>The generated application puts an entity's repository, service, handler and event types in
 * sibling packages it derives by replacing the trailing {@code .domain} of the entity's package,
 * and its bootstrap imports them from there. Without the suffix every derived package is the
 * entity's own, and the bootstrap would import types that are not where the per-entity
 * generators wrote them. The annotation processor refuses such an entity on the declaration; this
 * exception is the same refusal for metadata handed to the pipeline directly. The message is
 * already formatted with the identifier, so a caller prints it unchanged.
 *
 * @since 0.11
 */
public final class EntityPackageLayoutException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /** Not {@code transient}: the offending entities must survive serialization with the message. */
    private final List<String> entities;

    /**
     * Creates the refusal for the given entities. The message is prefixed with
     * {@code EXT-PROC-1033} and lists the entities in the order given, followed by the way out;
     * {@link #entities()} returns an unmodifiable copy of the list.
     *
     * @param entities one entry per refused entity, each its fully-qualified name; in a stable order
     */
    public EntityPackageLayoutException(List<String> entities) {
        super(DiagnosticId.ENTITY_PACKAGE_NOT_DOMAIN.format(buildMessage(entities)));
        this.entities = List.copyOf(entities);
    }

    /**
     * Returns the refused entities, one fully-qualified name each, as the message lists them.
     *
     * @return the refused entities, unmodifiable
     */
    public List<String> entities() {
        return entities;
    }

    private static String buildMessage(List<String> entities) {
        return "The generated application derives an entity's repository, service, handler and event "
                + "packages by replacing the trailing '.domain' of its package, and "
                + (entities.size() == 1 ? "this entity's package does" : "these entities' packages do")
                + " not end in '.domain':\n  "
                + String.join("\n  ", entities)
                + "\nMove each entity into a package ending in '.domain' (com.shop.order.domain.Order "
                + "has its repository generated as com.shop.order.repository.OrderRepository).";
    }
}
