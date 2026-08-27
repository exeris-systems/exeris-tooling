package eu.exeris.tooling.codegen.core.driver;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Derives the kernel SPIs an emitted application needs a provider for (T50, ADR-078).
 *
 * <h2>Driven by what was emitted, not by the subsystem name list</h2>
 * The emitted {@code Application.subsystems()} returns a fixed comma-separated string, and a
 * consumer is invited by its own javadoc to override it. Deriving the requirement from that
 * string would therefore check something the running application may not ask for, and would
 * fail builds that are correct. Every entry below is instead justified by an artefact the
 * pipeline <em>emitted into this project</em>:
 *
 * <ul>
 *   <li>{@code SubsystemProvider} — always. {@code Application.main()} hands
 *       {@code BootstrapSelector.forNames(subsystems())} to the kernel, and the orchestrator
 *       resolves those names through {@code ServiceLoader<SubsystemProvider>}. With none
 *       registered, no name resolves, whichever names the consumer chose.</li>
 *   <li>{@code PersistenceProvider} — always. A repository is emitted per entity and the
 *       composition root builds its {@code TransactionalExecutor} over
 *       {@code KernelProviders.persistenceEngine()}.</li>
 *   <li>{@code HttpProvider} — always. Handlers are emitted per entity and routed by
 *       {@code Application}.</li>
 *   <li>{@code EventProvider} — when some entity declares a {@code @DomainEvent}, which is
 *       what makes an {@code <Entity>EventPublisher} exist.</li>
 *   <li>{@code GraphProvider} — when some entity carries graph metadata.</li>
 *   <li>{@code FlowProvider} — when some entity declares a saga.</li>
 * </ul>
 *
 * <p>Crypto is deliberately absent even though the default {@code subsystems()} names it: no
 * emitted artefact uses it, so requiring it would be a claim about the consumer's subsystem
 * list rather than about this pipeline's output — the very reasoning this class avoids.
 *
 * @since 0.8.0
 */
public final class RequiredDrivers {

    public static final String SUBSYSTEM_PROVIDER =
            "eu.exeris.kernel.spi.bootstrap.SubsystemProvider";
    public static final String PERSISTENCE_PROVIDER =
            "eu.exeris.kernel.spi.persistence.PersistenceProvider";
    public static final String HTTP_PROVIDER =
            "eu.exeris.kernel.spi.http.HttpProvider";
    public static final String EVENT_PROVIDER =
            "eu.exeris.kernel.spi.events.EventProvider";
    public static final String GRAPH_PROVIDER =
            "eu.exeris.kernel.spi.graph.GraphProvider";
    public static final String FLOW_PROVIDER =
            "eu.exeris.kernel.spi.flow.FlowProvider";

    private RequiredDrivers() {
    }

    /**
     * @param domains every entity this build emitted code for
     * @return the SPIs a provider must be registered for, in a stable order (the always-on
     *         three first, so the message a failing build prints reads the same way twice);
     *         empty when {@code domains} is empty, because a build that emitted no
     *         application requires no driver to run one
     */
    public static Set<String> forDomains(List<DomainMetadata> domains) {
        Set<String> required = new LinkedHashSet<>();
        if (domains == null || domains.isEmpty()) {
            return required;
        }
        required.add(SUBSYSTEM_PROVIDER);
        required.add(PERSISTENCE_PROVIDER);
        required.add(HTTP_PROVIDER);
        if (domains.stream().anyMatch(DomainMetadata::hasEvents)) {
            required.add(EVENT_PROVIDER);
        }
        if (domains.stream().anyMatch(DomainMetadata::hasGraphMetadata)) {
            required.add(GRAPH_PROVIDER);
        }
        if (domains.stream().anyMatch(d -> d.isSaga() && d.sagaMetadata() != null)) {
            required.add(FLOW_PROVIDER);
        }
        return required;
    }

    /**
     * The artefact that supplies every SPI above in the open-core tree — named in the failure
     * message so the build reports a dependency to add rather than a subsystem that would not
     * start. Enterprise and third-party drivers register the same SPIs and satisfy the check
     * equally; this is the answer for the consumer who has none.
     */
    public static String suggestedArtifact() {
        return "eu.exeris.kernel:exeris-kernel-community";
    }
}
