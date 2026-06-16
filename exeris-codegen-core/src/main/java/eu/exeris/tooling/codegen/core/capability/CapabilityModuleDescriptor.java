package eu.exeris.tooling.codegen.core.capability;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import eu.exeris.sdk.sourcemodel.ast.CapabilityModuleMetadata;

/**
 * Read model for one {@code capability_*.json} the processor emits — the
 * generator-side counterpart of {@code ExerisDomainProcessor.CapabilityModuleJson}.
 *
 * <p>The SDK's {@link CapabilityModuleMetadata} carries no module identity
 * (provides/requires/lifecycleOwner only), so the wire shape wraps it with the
 * module's own {@code name}/{@code packageName}/{@code qualifiedName}. Capabilities
 * are app-wide, not per-entity, so these are a separate JSON family parallel to
 * {@code DomainMetadata}.
 *
 * <p>Jackson deserializes this record (and the nested SDK record) via the always-present
 * {@code RecordComponents} class attribute — no {@code -parameters} flag needed.
 *
 * @param name          the {@code @CapabilityModule} class simple name
 * @param packageName   its package
 * @param qualifiedName its fully-qualified name — the stable graph node id
 * @param module        the SDK capability metadata (provides/requires/lifecycleOwner)
 *
 * @since 0.5.0
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CapabilityModuleDescriptor(
        String name,
        String packageName,
        String qualifiedName,
        CapabilityModuleMetadata module
) {
    /** Never-null view of the module body (an absent body reads as {@link CapabilityModuleMetadata#empty()}). */
    public CapabilityModuleMetadata moduleOrEmpty() {
        return module != null ? module : CapabilityModuleMetadata.empty();
    }
}
