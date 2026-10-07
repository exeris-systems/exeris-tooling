package eu.exeris.tooling.codegen.core.driver;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;

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
 *   <li>{@code crypto} — always. No emitted code reads the crypto provider, but the kernel's HTTP
 *       transport does: a server configured with TLS listener material serves TLS only when a
 *       crypto provider is bound, and serves plaintext otherwise, and an outbound {@code https}
 *       client is refused without one. Whether TLS is configured is deployment configuration, which
 *       the domain model does not carry, so the name stays.</li>
 * </ul>
 *
 * <p>The order is fixed, whatever order the domains arrive in, so the emitted string is stable.
 * {@link RequiredDrivers} reads the same three predicates, so the SPIs the build requires and the
 * names the default list boots agree.
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
    /** Subsystem name of crypto; always listed. */
    public static final String CRYPTO = "crypto";

    private RequiredSubsystems() {
    }

    /**
     * Returns the subsystem names the default {@code Application.subsystems()} lists.
     *
     * @param domains every entity this build emits code for
     * @return the subsystem names, in the fixed order {@code http, persistence, graph, flow,
     *         events, crypto} with the conditional ones this build does not use left out
     */
    public static List<String> forDomains(List<DomainMetadata> domains) {
        List<DomainMetadata> all = domains == null ? List.of() : domains;
        List<String> names = new ArrayList<>(6);
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
}
