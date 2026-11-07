package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.tooling.codegen.core.driver.RequiredSubsystems;
import eu.exeris.tooling.codegen.java.support.DataScopeSupport;

import java.util.List;
import java.util.Optional;

/**
 * Decides whether a build is one the generated integration tests can boot (ADR-058 Amendment 1).
 *
 * <p>The integration tests boot the whole generated application on an in-memory H2 database and
 * apply every generated migration to it. That holds only for a build whose migrations H2 can run
 * and whose subsystems need nothing but HTTP, persistence and, where a route demands one, a
 * verified identity:
 *
 * <ul>
 *   <li>every entity's data scope resolves to {@code GLOBAL}: a tenant-partitioned table carries
 *       row-level-security DDL that H2 does not run, and because the migrations apply to the whole
 *       application, one such table rules H2 out for every entity;</li>
 *   <li>no entity carries graph metadata, so the build derives no graph subsystem;</li>
 *   <li>no entity declares a {@code @Blob} field, so the build derives no storage subsystem, whose
 *       store needs a configured driver;</li>
 *   <li>the build carries no capability composition, whose boot reads a manifest no integration
 *       test provides.</li>
 * </ul>
 *
 * <p>The reason names the first violation in that order, and the first entity in build order, so
 * the message is the same on every run.
 *
 * @since 0.11
 */
public final class KernelIntegrationScope {

    private KernelIntegrationScope() {
    }

    /**
     * Returns why the generated integration tests are withheld from a build, or nothing when they
     * are emitted.
     *
     * @param domains  every entity of the build, in build order
     * @param composed whether the build carries capability metadata
     * @return the reason, naming the first violation; empty when the build is in scope
     */
    public static Optional<String> withheldReason(List<DomainMetadata> domains, boolean composed) {
        for (DomainMetadata domain : domains) {
            if (DataScopeSupport.isTenantPartitioned(domain)) {
                return Optional.of("entity " + domain.fullyQualifiedName() + " has data scope "
                        + domain.effectiveDataScope() + ", and H2 runs no row-level-security migration;"
                        + " only an application whose every entity is GLOBAL is booted");
            }
        }
        if (RequiredSubsystems.usesGraph(domains)) {
            return Optional.of("an entity carries graph metadata, so the application boots the graph"
                    + " subsystem, which the integration tests do not configure");
        }
        if (RequiredSubsystems.usesStorage(domains)) {
            return Optional.of("an entity declares a @Blob field, so the application boots the storage"
                    + " subsystem, which needs a blob store driver the integration tests do not configure");
        }
        if (composed) {
            return Optional.of("the build carries a @CapabilityModule, and a composed boot reads a"
                    + " capability manifest the integration tests do not provide");
        }
        return Optional.empty();
    }
}
