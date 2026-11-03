package eu.exeris.tooling.codegen.core.driver;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.tooling.codegen.core.access.RouteRequirements;

import java.util.ArrayList;
import java.util.List;

/**
 * Derives the kernel subsystem names the emitted {@code Application.subsystems()} returns by
 * default, from the domain model.
 *
 * <p>The kernel boots the names it is handed through {@code BootstrapSelector.forNames(...)} and
 * adds each name's {@code dependsOn} closure itself, so the list names only what the generated code
 * reads and leaves {@code memory} and the like to the kernel. Each conditional name is tied to the
 * predicate under which an emitter writes the code that reads its engine:
 *
 * <ul>
 *   <li>{@code http} — always. A handler is emitted per entity, and {@code Application} binds the
 *       edge router as the server handler.</li>
 *   <li>{@code persistence} — always. A repository is emitted per entity, and
 *       {@code Application.transactionalExecutor()} reads {@code KernelProviders.persistenceEngine()}
 *       inside the boot callback. An {@code Application} is emitted only when at least one entity
 *       is, so no emitted application lacks a repository.</li>
 *   <li>{@code graph} — {@link #usesGraph}: some entity carries graph metadata, which is what makes
 *       an {@code <Entity>GraphSync} over a {@code GraphEngine} exist.</li>
 *   <li>{@code flow} — {@link #usesFlow}: some entity declares a saga, which is what makes a saga
 *       flow exist and {@code RuntimeComponents} read {@code KernelProviders.flowEngine()}.</li>
 *   <li>{@code events} — {@link #usesEvents}: some entity declares a {@code @DomainEvent}, which is
 *       what makes the publisher, the subscriber and the EV1 stream producer exist, each built over
 *       {@code KernelProviders.eventEngine()}.</li>
 *   <li>{@code security} — {@link #usesSecurity}: some generated route demands a verified identity
 *       (ADR-105), which only the security subsystem establishes. Without a security provider the
 *       subsystem does not run and such a route answers {@code 401}, which is the correct failure;
 *       a route policy that demands nothing needs no subsystem.</li>
 *   <li>{@code storage} — {@link #usesStorage}: some entity declares a {@code @Blob} field, which
 *       is what makes a handler take {@code KernelProviders.BLOB_STORE} and serve the field's
 *       routes (ADR-108). The kernel's storage subsystem binds that store only when
 *       {@code storage.blob.provider} names a driver; without one the binding is absent and the
 *       composition fails inside the boot callback, which is where the cause is named.</li>
 *   <li>{@code crypto} — always. No emitted code reads the crypto provider, but the kernel's HTTP
 *       transport does: a server configured with TLS listener material serves TLS only when a
 *       crypto provider is bound, and serves plaintext otherwise, and an outbound {@code https}
 *       client is refused without one. Whether TLS is configured is deployment configuration, which
 *       the domain model does not carry, so the name stays.</li>
 * </ul>
 *
 * <p>The order is fixed, whatever order the domains arrive in, so the emitted string is stable.
 * It is not a boot order: the kernel orders the subsystems it is handed by their {@code dependsOn}
 * closure and phase. {@code storage} depends on {@code memory} alone, and no listed name depends on
 * it, so it sits among the conditional names, after the ones that existed before it, and the
 * strings of builds without a {@code @Blob} field are unchanged. {@link RequiredDrivers} reads the
 * graph, flow, events and storage predicates, so the SPIs the build requires and the names the
 * default list boots agree.
 *
 * @since 0.9
 */
public final class RequiredSubsystems {

    /** Subsystem name of the HTTP server; always listed. */
    public static final String HTTP = "http";
    /** Subsystem name of persistence; always listed. */
    public static final String PERSISTENCE = "persistence";
    /** Subsystem name of the graph engine; listed when {@link #usesGraph} holds. */
    public static final String GRAPH = "graph";
    /** Subsystem name of the flow (saga) engine; listed when {@link #usesFlow} holds. */
    public static final String FLOW = "flow";
    /** Subsystem name of the event bus; listed when {@link #usesEvents} holds. */
    public static final String EVENTS = "events";
    /** Subsystem name of security; listed when {@link #usesSecurity} holds. */
    public static final String SECURITY = "security";
    /** Subsystem name of blob storage; listed when {@link #usesStorage} holds. */
    public static final String STORAGE = "storage";
    /** Subsystem name of crypto; always listed. */
    public static final String CRYPTO = "crypto";

    private RequiredSubsystems() {
    }

    /**
     * Returns the subsystem names the default {@code Application.subsystems()} lists.
     *
     * @param domains every entity this build emits code for
     * @return the subsystem names, in the fixed order {@code http, persistence, graph, flow,
     *         events, security, storage, crypto} with the conditional ones this build does not use
     *         left out
     */
    public static List<String> forDomains(List<DomainMetadata> domains) {
        List<DomainMetadata> all = domains == null ? List.of() : domains;
        List<String> names = new ArrayList<>(8);
        names.add(HTTP);
        names.add(PERSISTENCE);
        if (usesGraph(all)) {
            names.add(GRAPH);
        }
        if (usesFlow(all)) {
            names.add(FLOW);
        }
        if (usesEvents(all)) {
            names.add(EVENTS);
        }
        if (usesSecurity(all)) {
            names.add(SECURITY);
        }
        if (usesStorage(all)) {
            names.add(STORAGE);
        }
        names.add(CRYPTO);
        return List.copyOf(names);
    }

    /**
     * Returns the selector string the default {@code Application.subsystems()} emits.
     *
     * @param domains every entity this build emits code for
     * @return {@link #forDomains} joined with commas, as {@code Application.subsystems()} returns it
     */
    public static String selector(List<DomainMetadata> domains) {
        return String.join(",", forDomains(domains));
    }

    /**
     * Reports whether some entity carries graph metadata — the predicate
     * {@code KernelGraphSyncGenerator} emits under.
     *
     * @param domains every entity this build emits code for
     * @return {@code true} when at least one entity has graph metadata
     */
    public static boolean usesGraph(List<DomainMetadata> domains) {
        return domains.stream().anyMatch(DomainMetadata::hasGraphMetadata);
    }

    /**
     * Reports whether some entity declares a saga — the predicate {@code KernelSagaGenerator}
     * emits under.
     *
     * @param domains every entity this build emits code for
     * @return {@code true} when at least one entity is a saga with saga metadata
     */
    public static boolean usesFlow(List<DomainMetadata> domains) {
        return domains.stream().anyMatch(d -> d.isSaga() && d.sagaMetadata() != null);
    }

    /**
     * Reports whether some entity declares a {@code @DomainEvent} — the predicate the publisher,
     * subscriber and event stream-producer emitters all emit under.
     *
     * @param domains every entity this build emits code for
     * @return {@code true} when at least one entity declares an event
     */
    public static boolean usesEvents(List<DomainMetadata> domains) {
        return domains.stream().anyMatch(DomainMetadata::hasEvents);
    }

    /**
     * Reports whether some generated route demands a verified identity — the predicate under which
     * the generated route policy declares an {@code authenticated()} or scoped requirement.
     *
     * @param domains every entity this build emits code for
     * @return {@code true} when an entity or action route resolves to a requirement that needs an
     *         identity
     */
    public static boolean usesSecurity(List<DomainMetadata> domains) {
        return RouteRequirements.demandIdentity(domains);
    }

    /**
     * Reports whether some entity declares a {@code @Blob} field — the predicate under which a
     * handler takes a blob store and serves the field's routes.
     *
     * @param domains every entity this build emits code for
     * @return {@code true} when at least one field of some entity carries a blob facet
     */
    public static boolean usesStorage(List<DomainMetadata> domains) {
        return domains.stream()
                .filter(DomainMetadata::hasFields)
                .anyMatch(d -> d.fields().stream().anyMatch(FieldMetadata::hasBlob));
    }
}
