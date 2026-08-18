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
 * readable across tenants; its kernel carrier ({@code sharedScopeKey}, the
 * read-widen / write-pin RLS mode) is on the kernel 0.11 line. Until the carrier
 * is transcribed here, UNIVERSE emits the TENANT shape — which is UNIVERSE minus
 * the widening, i.e. strictly narrower than declared. Failing closed is the whole
 * point: an "is TENANT" test would send UNIVERSE down the GLOBAL path and publish
 * rows the author scoped to an owner. The processor warns that the widening half
 * is not transcribed yet.
 *
 * <p><b>This rationale used to say the pin was what blocked the transcription</b>
 * ("the kernel 0.11 line, which this repo does not pin yet"). U0 pinned kernel
 * {@code 0.11.0}, so only the transcription itself is outstanding. Corrected
 * because the sentence was the one a reader would trust before deciding whether
 * the work was reachable.
 *
 * <p><b>Known consequence, and it is not merely "narrower" (T29).</b> On the
 * archetypal UNIVERSE entity — a shared-world row with no tenant system-field
 * block, which is what "not tenant-partitioned" means — the emitted repository
 * grows a {@code tenant_id} column and calls {@code getTenantId()} on a type that
 * has none, so the build fails with {@code cannot find symbol} inside generated
 * code the consumer is told not to edit. The policy is right; what is missing is
 * an actionable processor diagnostic refusing the declaration instead of letting
 * it become a downstream compile error. Tracked in ROADMAP 0.8.0.
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
