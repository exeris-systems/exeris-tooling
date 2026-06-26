package eu.exeris.tooling.codegen.core.capability;

import eu.exeris.sdk.composition.CapManifest;
import eu.exeris.sdk.composition.CompositionBinding;
import eu.exeris.sdk.sourcemodel.ast.ProvidesMetadata;

import java.util.List;

/**
 * The ADR-024 composition validation stamp — a build-time verdict the tooling emits
 * into {@code cap-manifest.json} once the capability graph passes validation, and that
 * the <b>platform composition runtime</b> asserts (never re-validates) at SKU startup.
 *
 * <p>Per ADR-024 obligation 7 (the 2026-06-17 "Validation Stamp Lifecycle" amendment) the
 * stamp carries three things:
 * <ul>
 *   <li>{@link #validated} — the verdict. Always {@code true} in an emitted manifest: a
 *       failed validation throws {@link CapabilityGraphException} and no manifest is
 *       written, so the field's presence (not its value) is what the platform asserts.</li>
 *   <li>{@link #compositionVersion} — the composition's release identity, a <em>build
 *       input</em> (not derivable from the graph), supplied via the
 *       {@code -Dexeris.composition.version} seam.</li>
 *   <li>{@link #contentBinding} — a hash over the resolved cap set (exact modules + their
 *       provided service@version pairs). The binding makes the stamp
 *       <b>non-transferable</b>: it attests "<i>this</i> composition is valid", not
 *       "<i>some</i> composition is valid", so the platform can fail-fast on a stale,
 *       hand-edited, version-drifted, or partially-deployed manifest.</li>
 * </ul>
 *
 * <p>This is a platform concern, never a kernel one (ADR-024 obligation 9 — the open kernel
 * stays cap-blind). The stamp is a correctness / fail-fast consistency assertion that
 * catches <em>honest</em> mistakes; it is explicitly <b>not</b> a tamper-proof lock or a
 * licence gate (ADR-023 enforcement is contractual, not technical).
 *
 * @since 0.6.0
 */
public record CompositionStamp(
        boolean validated,
        String compositionVersion,
        String contentBinding
) {

    /** Composition version used when the build supplies none (the {@code -Dexeris.composition.version} seam). */
    public static final String UNVERSIONED = "0.0.0";

    /**
     * Builds the stamp for an already-validated, deterministically-sorted module list.
     * Call ONLY on the validation-success path — a failed graph throws before this.
     *
     * @param sortedModules      modules sorted by qualified name (as {@link CapabilityGraph#build} sorts them)
     * @param compositionVersion the composition release identity, or {@code null}/blank → {@link #UNVERSIONED}
     */
    public static CompositionStamp of(List<CapabilityModuleDescriptor> sortedModules, String compositionVersion) {
        String version = (compositionVersion == null || compositionVersion.isBlank())
                ? UNVERSIONED : compositionVersion;
        return new CompositionStamp(true, version, computeBinding(sortedModules));
    }

    /**
     * Content binding = {@code "sha256:" + hex(SHA-256)} over a canonical, sorted serialization of
     * the resolved cap set. The algorithm is the <b>one</b> canonical definition in
     * {@link CompositionBinding} (ADR-024 obligation 8b) — emitter and asserter share it instead of
     * each maintaining a byte-verbatim copy. This method only adapts the tooling's producer records
     * ({@link CapabilityModuleDescriptor} / {@link ProvidesMetadata}) to the spec's read shape; the
     * canonical form (sort, separators, the {@code "  provides "} prefix, the {@code null → ""}
     * unversioned normalization) lives there. Deterministic — same cap set ⇒ same binding, with no
     * wall-clock or iteration-order leak (hard-constraint #3). Hashes the cap set ONLY (not
     * {@link #compositionVersion}), per ADR-024 obligation 7.
     */
    static String computeBinding(List<CapabilityModuleDescriptor> sortedModules) {
        return CompositionBinding.compute(sortedModules.stream()
                .map(CompositionStamp::toSpecModule)
                .toList());
    }

    /** Adapt a tooling producer descriptor to the composition-spec read shape for binding. */
    private static CapManifest.Module toSpecModule(CapabilityModuleDescriptor m) {
        List<ProvidesMetadata> provides = m.moduleOrEmpty().provides();
        List<CapManifest.Provided> specProvides = provides == null
                ? List.of()
                : provides.stream()
                        .map(p -> new CapManifest.Provided(p.service(), p.version()))
                        .toList();
        return new CapManifest.Module(m.qualifiedName(), new CapManifest.ModuleBody(specProvides));
    }
}
