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
 * <p><b>What blocks the transcription is the session-variable name.</b> An emitted
 * RLS policy has to name the PostgreSQL session variable it reads. Measured at the
 * pinned kernel {@code v0.11.0}: {@code exeris.tenant_id} is named in SPI, Core and
 * the TCK — so the tenant policy this class already drives rests on a contract —
 * while {@code exeris.shared_scope} is named in {@code exeris-kernel-community} and
 * in no SPI, Core or TCK file. The driver is swappable Community/Enterprise, so a
 * migration the consumer commits may not depend on one driver's internal literal.
 * Kernel 0.12 promotes both names to constants on {@code ConnectionInterceptor},
 * which is what makes the transcription emittable: it is gated on the 0.12 pin.
 *
 * <p>A second half is missing independently of the pin, and it is not the column:
 * {@code shared_scope TEXT NOT NULL DEFAULT ''} is canonically named and fail-safe.
 * What is missing is any way to declare which field carries a row's shared-scope
 * value, without which the emitted repository binds nothing and every row keeps
 * {@code ''} — UNIVERSE with a column and no widening.
 *
 * <p>{@code StorageContext.sharedScopeKey()} is on 0.11, and reading only that is
 * how this repo came to say in five places that the gate was gone. It carries the
 * value; it does not name the variable an emitted policy reads.
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
