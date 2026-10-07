package eu.exeris.tooling.codegen.java.support;

import eu.exeris.sdk.sourcemodel.ast.DataScope;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.SystemFieldsMetadata;

import java.util.Optional;

/**
 * The one place emitters ask what an entity's data-scope tier means for output.
 *
 * <p>{@code DomainMetadata.tenantScoped()} is deprecated for removal in SDK 1.0.0 and is not the
 * whole answer (ADR-059): an author can declare {@code dataScope = TENANT} without ever writing
 * {@code tenantScoped = true}, and a generator still reading the raw boolean
 * would emit a table with no owner column, no RLS policy and no owner index —
 * a silent loss of tenancy rather than a build error. Every such read goes
 * through {@link DomainMetadata#effectiveDataScope()}, and the emitters ask
 * that question here so there is exactly one place to change.
 *
 * <h2>Two questions, because a UNIVERSE row is owned</h2>
 * <p>{@link #isTenantPartitioned} asks whether rows have an owning tenant, and is
 * deliberately phrased as "not {@code GLOBAL}" rather than "is {@code TENANT}".
 * {@link DataScope#UNIVERSE} is rows owned by a tenant <em>and</em> readable across the
 * tenant's shared scope (kernel ADR-012 §4b): the owner column, the owner-pinned policy,
 * the owner index, the tenant stamp, the tenant migration tier and the handler's tenant
 * guard are all exactly right for it. An "is TENANT" test would send UNIVERSE down the
 * GLOBAL path and publish rows the author scoped to an owner.
 *
 * <p>{@link #sharedScopeField} asks the second question — which column widens reads — and
 * is answered only for a transcribable UNIVERSE entity. Everything UNIVERSE adds on top of
 * the TENANT shape hangs off that answer: the additive shared-scope migration
 * ({@code KernelSharedScopeMigrationGenerator}) and the repository's shared-scope stamp.
 *
 * <h2>Metadata that did not come through the processor</h2>
 * <p>The processor refuses a UNIVERSE declaration that names no {@code @SharedScope} field
 * or no owner, so from an annotated source the carrier is always filled.
 * Metadata also reaches the emitters from the {@code -io} reader and from
 * {@code exeris-codegen-maven-plugin} reading metadata JSON, neither of which goes through
 * the processor's diagnostics. A UNIVERSE entity without the carrier therefore fails
 * closed here — the TENANT shape, owner-private, never widened — and one whose carrier
 * names a field it does not declare fails generation: a policy over a missing column would
 * be a migration that cannot apply.
 *
 * @since 0.7
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
     * @return true for {@code TENANT} and {@code UNIVERSE}, whose rows are owned too
     */
    public static boolean isTenantPartitioned(DomainMetadata metadata) {
        return metadata.effectiveDataScope() != DataScope.GLOBAL;
    }

    /**
     * The Java name of the field that holds a row's owning tenant, for an entity whose rows have
     * one: {@code systemFields.tenantIdField} when declared, else the canonical {@code tenantId} —
     * the same resolution the repository binds through.
     *
     * @param metadata the entity metadata
     * @return the owner field's name, or empty for a {@code GLOBAL} entity
     */
    public static Optional<String> ownerFieldName(DomainMetadata metadata) {
        if (!isTenantPartitioned(metadata)) {
            return Optional.empty();
        }
        SystemFieldsMetadata systemFields = metadata.systemFields();
        String name = systemFields == null ? null : systemFields.tenantIdField();
        return Optional.of(name == null || name.isBlank() ? "tenantId" : name);
    }

    /**
     * The {@code @SharedScope} field of a transcribable UNIVERSE entity: the column whose value
     * widens reads across the tenant's shared scope.
     *
     * @param metadata the entity metadata
     * @return the field, or empty for every entity that is not UNIVERSE and for a UNIVERSE entity
     *         whose metadata carries no {@code sharedScopeField} (which then fails closed to the
     *         TENANT shape)
     * @throws IllegalArgumentException if {@code sharedScopeField} names a field the entity does not
     *                                  declare
     */
    public static Optional<FieldMetadata> sharedScopeField(DomainMetadata metadata) {
        if (metadata.effectiveDataScope() != DataScope.UNIVERSE) {
            return Optional.empty();
        }
        SystemFieldsMetadata systemFields = metadata.systemFields();
        String name = systemFields == null ? null : systemFields.sharedScopeField();
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(metadata.fields().stream()
                .filter(field -> field.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        metadata.entityName() + ": systemFields.sharedScopeField names '" + name
                                + "', which is not a field of the entity. The shared-scope policy "
                                + "would compare a column the table does not have.")));
    }
}
