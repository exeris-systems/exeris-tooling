package eu.exeris.tooling.codegen.java.support;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;

/**
 * The packages the generated application derives from an entity's package.
 *
 * <p>An entity lives in a package ending in {@code .domain} ({@code EXT-PROC-1033}); its
 * generated repository, service, handler, event, graph, saga and client types live in sibling
 * packages named by replacing that trailing segment, and the application bootstrap and the
 * generated tests import them from there. Only the trailing segment is replaced: a
 * {@code .domain} earlier in the name ({@code com.domainco.app.domain},
 * {@code a.domain.b.domain}) belongs to the base package and is kept. Every emitter, main and
 * test, derives through this class, so the trees they write agree on where each type is.
 *
 * @since 0.11
 */
public final class DomainPackages {

    /** The trailing segment of an entity's package, which the derived packages replace. */
    public static final String DOMAIN_SUFFIX = ".domain";

    /** The sibling package of the generated repository and its error types. */
    public static final String REPOSITORY = "repository";
    /** The sibling package of the generated service. */
    public static final String SERVICE = "service";
    /** The sibling package of the generated HTTP and stream handlers. */
    public static final String HANDLER = "handler";
    /** The sibling package of the generated domain events and event handlers. */
    public static final String EVENT = "event";
    /** The sibling package of the generated graph synchronisation. */
    public static final String GRAPH = "graph";
    /** The sibling package of the generated sagas. */
    public static final String SAGA = "saga";
    /** The sibling package of the generated typed HTTP client. */
    public static final String CLIENT = "client";

    private DomainPackages() {
    }

    /**
     * Returns the entity's package without its trailing {@code .domain} segment — the package
     * the derived sibling packages hang off.
     *
     * @param domainPackage an entity's package; must end in {@code .domain}
     * @return the base package; never {@code null}
     * @throws IllegalArgumentException if the package is {@code null} or does not end in
     *         {@code .domain}; callers refuse such an entity with {@code EXT-PROC-1033} first
     */
    public static String basePackage(String domainPackage) {
        if (domainPackage == null || !domainPackage.endsWith(DOMAIN_SUFFIX)) {
            throw new IllegalArgumentException("entity package '" + domainPackage
                    + "' does not end in '" + DOMAIN_SUFFIX + "'");
        }
        return domainPackage.substring(0, domainPackage.length() - DOMAIN_SUFFIX.length());
    }

    /**
     * Returns the entity's package without its trailing {@code .domain} segment.
     *
     * @param metadata the entity; its package must end in {@code .domain}
     * @return the base package; never {@code null}
     * @throws IllegalArgumentException if the entity's package does not end in {@code .domain}
     */
    public static String basePackage(DomainMetadata metadata) {
        return basePackage(metadata.packageName());
    }

    /**
     * Returns the sibling package {@code segment} of the entity's package: its trailing
     * {@code .domain} replaced by {@code .segment}.
     *
     * @param metadata the entity; its package must end in {@code .domain}
     * @param segment  the last segment of the derived package, e.g. {@link #REPOSITORY}
     * @return the derived package; never {@code null}
     * @throws IllegalArgumentException if the entity's package does not end in {@code .domain}
     */
    public static String sibling(DomainMetadata metadata, String segment) {
        return basePackage(metadata) + "." + segment;
    }
}
