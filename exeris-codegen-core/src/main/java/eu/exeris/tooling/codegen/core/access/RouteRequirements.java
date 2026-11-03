package eu.exeris.tooling.codegen.core.access;

import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.RouteAccess;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Resolves the access requirement each generated route carries, from the declarations in the
 * domain model (ADR-105 obligations 10 to 12).
 *
 * <p>An element (an entity, or one of its actions) resolves on its own declarations:
 *
 * <table>
 *   <caption>One element's requirement</caption>
 *   <tr><th>The element declares</th><th>Requirement</th></tr>
 *   <tr><td>{@code @RouteAccess(PUBLIC)}</td><td>{@link Kind#PERMIT_ALL}</td></tr>
 *   <tr><td>a non-empty {@code permissions}</td><td>{@link Kind#ANY_SCOPE} over them</td></tr>
 *   <tr><td>{@code @RouteAccess(AUTHENTICATED)}, no {@code permissions}</td>
 *       <td>{@link Kind#AUTHENTICATED}</td></tr>
 *   <tr><td>nothing</td><td>no requirement</td></tr>
 * </table>
 *
 * <p>Every route of an entity carries the entity's requirement, except an action route whose
 * action declares its own: the nearest declaration wins, and an action's list replaces the
 * entity's rather than adding to it. A route with no requirement is one the generated policy
 * abstains on.
 *
 * <p>The scopes keep declaration order with repeats removed, so the emitted policy and the
 * contract row list them identically.
 *
 * @since 0.11
 */
public final class RouteRequirements {

    /** What a route demands of its caller. */
    public enum Kind {
        /** Anyone, with or without an identity. */
        PERMIT_ALL,
        /** A verified identity, any scopes. */
        AUTHENTICATED,
        /** A verified identity holding at least one of the scopes. */
        ANY_SCOPE
    }

    /** Which declaration a route's requirement came from. */
    public enum Source {
        /** The entity's own declaration, inherited by every route without a nearer one. */
        ENTITY,
        /** The action's own declaration, replacing the entity's on that action's route. */
        ACTION
    }

    /**
     * A route's requirement.
     *
     * @param kind   what the route demands
     * @param scopes the scopes of an {@link Kind#ANY_SCOPE} requirement in declaration order;
     *               empty for every other kind
     * @param source the declaration the requirement came from
     */
    public record Requirement(Kind kind, List<String> scopes, Source source) {

        /**
         * Copies the scopes.
         *
         * @param kind   what the route demands
         * @param scopes the scopes, copied
         * @param source the declaration the requirement came from
         */
        public Requirement {
            scopes = List.copyOf(scopes);
        }

        /**
         * Reports whether the route needs a verified identity.
         *
         * @return {@code true} for {@link Kind#AUTHENTICATED} and {@link Kind#ANY_SCOPE}
         */
        public boolean demandsIdentity() {
            return kind != Kind.PERMIT_ALL;
        }
    }

    private RouteRequirements() {
    }

    /**
     * Resolves the requirement every route of {@code domain} carries unless an action declares its
     * own.
     *
     * @param domain the entity
     * @return the entity's requirement, or {@code null} when the entity declares no access
     */
    public static Requirement forEntity(DomainMetadata domain) {
        return resolve(domain.routeAccess(), domain.permissions(), Source.ENTITY);
    }

    /**
     * Resolves the requirement of {@code action}'s route: its own declaration, else the entity's.
     *
     * @param domain the entity the action belongs to
     * @param action the action
     * @return the route's requirement, or {@code null} when neither declares access
     */
    public static Requirement forAction(DomainMetadata domain, ActionMetadata action) {
        Requirement own = resolve(action.routeAccess(), action.permissions(), Source.ACTION);
        return own != null ? own : forEntity(domain);
    }

    /**
     * Reports whether some route of some entity demands a verified identity, which is what makes
     * the application need the {@code security} subsystem.
     *
     * @param domains every entity this build emits code for
     * @return {@code true} when a route resolves to {@link Kind#AUTHENTICATED} or
     *         {@link Kind#ANY_SCOPE}
     */
    public static boolean demandIdentity(List<DomainMetadata> domains) {
        for (DomainMetadata domain : domains) {
            if (demands(forEntity(domain))) {
                return true;
            }
            for (ActionMetadata action : actions(domain)) {
                if (demands(forAction(domain, action))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Reports whether some route of some entity carries a requirement at all.
     *
     * @param domains every entity this build emits code for
     * @return {@code true} when an entity or an action declares access
     */
    public static boolean anyDeclared(List<DomainMetadata> domains) {
        for (DomainMetadata domain : domains) {
            if (forEntity(domain) != null) {
                return true;
            }
            for (ActionMetadata action : actions(domain)) {
                if (forAction(domain, action) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The entity's actions; metadata read from JSON that omits them carries none. */
    private static List<ActionMetadata> actions(DomainMetadata domain) {
        return domain.hasActions() ? domain.actions() : List.of();
    }

    private static boolean demands(Requirement requirement) {
        return requirement != null && requirement.demandsIdentity();
    }

    private static Requirement resolve(RouteAccess access, List<String> permissions, Source source) {
        if (access == RouteAccess.PUBLIC) {
            return new Requirement(Kind.PERMIT_ALL, List.of(), source);
        }
        List<String> scopes = permissions == null ? List.of() : List.copyOf(new LinkedHashSet<>(permissions));
        if (!scopes.isEmpty()) {
            return new Requirement(Kind.ANY_SCOPE, scopes, source);
        }
        if (access == RouteAccess.AUTHENTICATED) {
            return new Requirement(Kind.AUTHENTICATED, List.of(), source);
        }
        return null;
    }
}
