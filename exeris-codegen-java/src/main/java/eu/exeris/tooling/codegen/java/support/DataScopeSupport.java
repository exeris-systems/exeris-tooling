package eu.exeris.tooling.codegen.java.support;

import eu.exeris.sdk.sourcemodel.ast.DataScope;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;

/**
 * The one place emitters ask what an entity's data-scope tier means for output.
 *
 * <p>Before ADR-059 every generator read {@code DomainMetadata.tenantScoped()}
 * directly. That boolean is now deprecated for removal in SDK 1.0.0 and, more
 * to the point, it is no longer the whole answer: an author can declare
 * {@code dataScope = TENANT} without ever writing {@code tenantScoped = true},
 * and a generator still reading the raw boolean would emit a table with no
 * owner column, no RLS policy and no owner index — a silent loss of tenancy
 * rather than a build error. Every such read goes through
 * {@link DomainMetadata#effectiveDataScope()}, and the emitters ask that
 * question here so there is exactly one place to change.
 *
 * <p>The predicate is deliberately phrased as "not {@code GLOBAL}" rather than
 * "is {@code TENANT}". {@link DataScope#UNIVERSE} is rows owned by a tenant but
 * readable across tenants. Until it can be transcribed, UNIVERSE emits the TENANT
 * shape — which is UNIVERSE minus the widening, i.e. strictly narrower than
 * declared. Failing closed is the whole point: an "is TENANT" test would send
 * UNIVERSE down the GLOBAL path and publish rows the author scoped to an owner.
 *
 * <p>Since 0.8.0 the <b>processor refuses</b> a UNIVERSE declaration outright (T29),
 * so the tier no longer arrives here from an annotated source. This branch stays
 * anyway, and deliberately: metadata also reaches the emitters from the {@code -io}
 * reader and from {@code exeris-codegen-maven-plugin} reading metadata JSON, neither
 * of which goes through the processor's diagnostics. If UNIVERSE arrives by one of
 * those routes, narrower-than-declared is still the only safe answer.
 *
 * <p>An emitted RLS policy names a PostgreSQL session variable; it does not call an
 * accessor. At the pinned kernel {@code v0.11.0} {@code exeris.tenant_id} is named in
 * SPI, Core and the TCK — so the tenant policy this class drives rests on a contract —
 * while {@code exeris.shared_scope} is named only in {@code exeris-kernel-community},
 * and the driver is swappable. Kernel 0.12 promotes both to constants on
 * {@code ConnectionInterceptor}; the transcription is gated on that pin, and on an SDK
 * carrier naming the field that holds a row's shared-scope value — without one every
 * row keeps the column's {@code ''} default and UNIVERSE is behaviourally TENANT.
 *
 * <p><b>T29, closed in 0.8.0.</b> The gap was never that UNIVERSE under-delivers —
 * it is that on the archetypal UNIVERSE entity it does not build. A shared-world row
 * is precisely one with no tenant system-field block, and this shape makes the
 * emitted repository bind {@code entity.getTenantId()}, so the author's reward for
 * declaring the tier was {@code cannot find symbol} inside generated code they are
 * told not to edit, pointing at a getter nobody asked them to write. Fixed where it
 * belongs — a processor ERROR at the declaration — rather than by relaxing the
 * policy here.
 *
 * <p>When the carrier mapping lands (ADR-059 obligation 5, last part), UNIVERSE
 * gains its own branch here rather than at seven call sites.
 *
 * @author Exeris Team
 * @since 0.7.0
 */
public final class DataScopeSupport {

    private DataScopeSupport() {
    }

    /**
     * Whether the entity's rows are partitioned by an owning tenant — the
     * question that decides the owner column, the RLS policy, the owner index
     * and the migration tier.
     *
     * @param metadata the entity metadata
     * @return true for {@code TENANT} and (fail-closed) {@code UNIVERSE}
     */
    public static boolean isTenantPartitioned(DomainMetadata metadata) {
        return metadata.effectiveDataScope() != DataScope.GLOBAL;
    }
}
