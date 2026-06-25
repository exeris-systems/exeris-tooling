package eu.exeris.tooling.codegen.core.capability;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
     * Content binding = {@code "sha256:" + hex(SHA-256)} over a canonical, sorted
     * serialization of the resolved cap set: each module's qualified name plus its provided
     * {@code service@version} pairs (sorted within the module). Deterministic — same cap set
     * ⇒ same binding, with no wall-clock or iteration-order leak (hard-constraint #3). Hashes
     * the cap set ONLY (not {@link #compositionVersion}), per ADR-024 obligation 7: the binding
     * pins the artefacts, the version is a separate field the platform matches independently.
     */
    static String computeBinding(List<CapabilityModuleDescriptor> sortedModules) {
        StringBuilder canonical = new StringBuilder();
        for (CapabilityModuleDescriptor m : sortedModules) {
            canonical.append(m.qualifiedName()).append('\n');
            var body = m.moduleOrEmpty();
            if (body.provides() != null) {
                body.provides().stream()
                        .map(p -> p.service() + '@' + p.version())
                        .sorted()
                        .forEach(s -> canonical.append("  provides ").append(s).append('\n'));
            }
        }
        return "sha256:" + sha256Hex(canonical.toString());
    }

    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // never on a conformant JRE
        }
    }
}
