package eu.exeris.e2e.caps;

import eu.exeris.kernel.community.testkit.http.KernelBootstrapHttpEngineFixture;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.sdk.composition.CapManifest;
import eu.exeris.sdk.composition.runtime.CompositionConductor;
import eu.exeris.e2e.codegen.compile.ProcessorCompiler;
import eu.exeris.tooling.codegen.core.capability.CapTierWallException;
import eu.exeris.tooling.codegen.java.CodegenPipeline;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G3 — the cap-composition exit gate for Phase 1 of the gateway-caps plan (P1.4).
 *
 * <p>Every other capability test in this repo starts from hand-built
 * {@code CapabilityModuleDescriptor}s or hand-written {@code capability_*.json}. This one starts
 * from <b>annotated Java</b> and runs the whole chain end to end:
 *
 * <pre>
 *   @CapabilityModule sources
 *        → javac + ExerisDomainProcessor      (real annotation processing, real class files)
 *        → validateCapabilities               (ADR-024 predicates 1–3)
 *        → verifyCapTierWall                  (predicate 4, over the bytecode just emitted)
 *        → cap-manifest.json + Application    (CodegenPipeline)
 *        → KernelBootstrapHttpEngineFixture   (a real kernel, http subsystem, KERNEL READY)
 *        → CompositionConductor               (SDK 0.9.0, replaying the emitted initOrder)
 * </pre>
 *
 * <p>Two joints only this test can hold:
 * <ul>
 *   <li><b>Build-time stamp ↔ runtime assertion.</b> {@code CompositionConductor.start()} runs
 *       {@code CompositionStampAssertion} first, which recomputes the content binding from the
 *       manifest. Tooling's emitted stamp and the SDK's independent recomputation therefore have
 *       to agree — a canonicalization drift on either side fails here and nowhere else.</li>
 *   <li><b>initOrder is replayed verbatim.</b> The sample is arranged so the topological order is
 *       the <em>reverse</em> of the alphabetical one ({@code vault} provides, {@code audit}
 *       requires), so a conductor that re-sorted — or a tooling side that emitted a lexicographic
 *       order — would be caught rather than accidentally agreeing.</li>
 * </ul>
 *
 * <p>The kernel runs the {@code http} subsystem only: no database, no persistence provider. It is
 * there to put the conductor in its real temporal context (caps come up after {@code KERNEL READY}
 * and drain before the kernel stops), not because the conductor talks to it — ADR-024 obligation 9
 * keeps the kernel cap-blind, and this test double-checks that by never wiring the two together.
 */
@Tag("e2e")
@Tag("caps")
@DisplayName("G3 — cap composition e2e: annotations → processor → verify-capabilities → kernel → conductor")
class CapCompositionE2ETest {

    private static final String VAULT = "eu.exeris.caps.vault.VaultModule";
    private static final String AUDIT = "eu.exeris.caps.audit.AuditModule";
    private static final String BASE_PACKAGE = "eu.exeris.sku.gateway";

    @TempDir
    static Path workspace;

    private static Path classesDir;
    private static Path metadataDir;
    private static Path generatedDir;
    private static CodegenPipeline pipeline;

    @BeforeAll
    static void buildTheSampleSku() throws IOException {
        pipeline = CodegenPipeline.createDefault();
        classesDir = workspace.resolve("target/classes");
        metadataDir = classesDir.resolve("exeris-metadata");
        generatedDir = workspace.resolve("src/main/generated/java");

        ProcessorCompiler.compile(workspace.resolve("src"), classesDir, null, sampleSku());

        // The processor writes capability_*.json under CLASS_OUTPUT/exeris-metadata — i.e. exactly
        // the (classesDir, metadataDir) pair exeris:verify-capabilities is configured with.
        assertThat(metadataDir).isDirectory();

        pipeline.run(metadataDir, generatedDir, BASE_PACKAGE);
    }

    @Test
    @DisplayName("the processor extracts both caps, and the @CapabilityLifecycle owner rides along")
    void processorExtractsTheComposition() throws IOException {
        assertThat(Files.readString(metadataDir.resolve("capability_VaultModule.json")))
                .contains("\"qualifiedName\" : \"" + VAULT + "\"")
                .contains("\"lifecycleOwner\" : \"" + VAULT + "\"")
                .contains("eu.exeris.caps.api.VaultApi");
        assertThat(Files.readString(metadataDir.resolve("capability_AuditModule.json")))
                .contains("\"lifecycleOwner\" : \"" + AUDIT + "\"")
                .contains("\"versionRange\" : \"[1.0.0,2.0.0)\"");
    }

    @Test
    @DisplayName("the graph gate resolves the composition (ADR-024 predicates 1–3)")
    void graphGateAcceptsTheComposition() throws IOException {
        assertThat(pipeline.validateCapabilities(metadataDir)).isEqualTo(2);
    }

    @Test
    @DisplayName("the Wall gate is clean over the compiled caps — including a cap using its OWN internals")
    void wallGateIsCleanOverTheCompiledCaps() throws IOException {
        // VaultModule holds a eu.exeris.caps.vault.internal.VaultStore field. Own internals are
        // legal; the identical reference from a sibling is not — that half is the nested class below.
        assertThat(pipeline.verifyCapTierWall(classesDir, metadataDir)).isEqualTo(2);
    }

    @Test
    @DisplayName("cap-manifest.json pins the dependency order, not the alphabet")
    void manifestPinsTheDependencyOrder() throws IOException {
        String json = Files.readString(generatedDir.resolve("cap-manifest.json"));
        // Read it back through the SDK's own record — the same shape the conductor parses, so an
        // initOrder that only looks right as text cannot pass here.
        CapManifest manifest = JsonMapper.builder()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .build()
                .readValue(json, CapManifest.class);

        // audit < vault alphabetically, but audit @Requires what vault @Provides — so a
        // lexicographic emitter would produce the opposite of this.
        assertThat(manifest.initOrder()).containsExactly(VAULT, AUDIT);
        assertThat(manifest.modules()).extracting(CapManifest.Module::qualifiedName)
                .containsExactlyInAnyOrder(VAULT, AUDIT);
        assertThat(manifest.stamp().validated()).isTrue();
        assertThat(manifest.stamp().contentBinding()).startsWith("sha256:");
    }

    @Test
    @DisplayName("G2: the emitted Application of a composed build carries the conductor call site")
    void emittedApplicationCarriesTheConductorCallSite() throws IOException {
        assertThat(Files.readString(generatedDir.resolve("eu/exeris/sku/gateway/Application.java")))
                .contains("import eu.exeris.sdk.composition.runtime.CompositionConductor")
                .contains("CompositionConductor.from(capManifest()).start()");
    }

    @Test
    @DisplayName("under a running kernel, the conductor replays initOrder verbatim and unwinds in reverse")
    void conductorReplaysTheInitOrderUnderARunningKernel() throws Exception {
        CapLifecycleRecorder.reset();
        Path manifest = generatedDir.resolve("cap-manifest.json");

        try (KernelBootstrapHttpEngineFixture kernel = new KernelBootstrapHttpEngineFixture()) {
            kernel.start(exchange -> exchange.respond(HttpStatus.OK));
            assertThat(kernel.isRunning()).as("KERNEL READY before any cap is touched").isTrue();

            // The SKU shape: the conductor comes up inside the booted kernel, against the manifest
            // this build emitted, loading owners from the classes this build compiled. start()
            // runs CompositionStampAssertion first, so reaching the assertions below already
            // proves the SDK recomputed tooling's content binding and agreed with it.
            try (URLClassLoader capLoader = capClassLoader();
                 CompositionConductor ignored =
                         CompositionConductor.from(manifest).classLoader(capLoader).start()) {

                // initialize sweeps the whole composition in initOrder, THEN ready sweeps it again
                // (the all-caps barrier) — not initialize+ready per cap.
                assertThat(CapLifecycleRecorder.events()).containsExactly(
                        "vault:initialize", "audit:initialize", "vault:ready", "audit:ready");
            }

            // close() == shutdown(): drain then terminate, both in reverse initOrder.
            assertThat(CapLifecycleRecorder.events()).containsExactly(
                    "vault:initialize", "audit:initialize", "vault:ready", "audit:ready",
                    "audit:drain", "vault:drain", "audit:terminate", "vault:terminate");

            // Caps are fully drained while the kernel is still up — the SKU-entrypoint shutdown
            // order the conductor's contract requires (caps first, kernel second).
            assertThat(kernel.isRunning()).isTrue();
        }
    }

    @Nested
    @DisplayName("the negative half — a Wall-violating cap must fail the build")
    class WallViolatingSample {

        @Test
        @DisplayName("a cap reaching into a host runtime fails verify-capabilities (predicate 4)")
        void hostRuntimeReachFailsTheBuild(@TempDir Path dir) throws IOException {
            Path stubs = dir.resolve("stubs");
            Path classes = dir.resolve("classes");
            // The host-runtime type is compiled to a SEPARATE directory used only as -classpath —
            // that is how a real cap sees Spring (a dependency artefact), and it keeps the scanned
            // directory free of classes that would flag their own self-reference.
            ProcessorCompiler.compile(dir.resolve("stubsrc"), stubs, null, Map.of(
                    "org/springframework/context/ApplicationContext.java",
                    """
                    package org.springframework.context;
                    public interface ApplicationContext {}
                    """));
            ProcessorCompiler.compile(dir.resolve("src"), classes, stubs, Map.of(
                    "eu/exeris/caps/rogue/RogueModule.java",
                    """
                    package eu.exeris.caps.rogue;

                    import eu.exeris.sdk.annotation.capability.CapabilityModule;
                    import org.springframework.context.ApplicationContext;

                    @CapabilityModule
                    public class RogueModule {
                        // Parameter-only: this reference exists solely in the method descriptor,
                        // never in the constant pool. A pool-only scan would wave it through.
                        public void configure(ApplicationContext context) {
                        }
                    }
                    """));

            assertThatThrownBy(() ->
                    pipeline.verifyCapTierWall(classes, classes.resolve("exeris-metadata")))
                    .isInstanceOf(CapTierWallException.class)
                    .hasMessageContaining("eu.exeris.caps.rogue.RogueModule")
                    .hasMessageContaining("org.springframework.context.ApplicationContext");
        }

        @Test
        @DisplayName("a cap reaching into a SIBLING cap's internals fails too (same reference, other owner)")
        void siblingInternalsReachFailsTheBuild(@TempDir Path dir) throws IOException {
            Path siblings = dir.resolve("siblings");
            Path classes = dir.resolve("classes");
            ProcessorCompiler.compile(dir.resolve("sibsrc"), siblings, null, Map.of(
                    "eu/exeris/caps/vault/internal/VaultStore.java",
                    """
                    package eu.exeris.caps.vault.internal;
                    public class VaultStore {}
                    """));
            ProcessorCompiler.compile(dir.resolve("src"), classes, siblings, Map.of(
                    "eu/exeris/caps/rogue/RogueModule.java",
                    """
                    package eu.exeris.caps.rogue;

                    import eu.exeris.caps.vault.internal.VaultStore;
                    import eu.exeris.sdk.annotation.capability.CapabilityModule;

                    @CapabilityModule
                    public class RogueModule {
                        private final VaultStore borrowed = new VaultStore();
                    }
                    """));

            assertThatThrownBy(() ->
                    pipeline.verifyCapTierWall(classes, classes.resolve("exeris-metadata")))
                    .isInstanceOf(CapTierWallException.class)
                    .hasMessageContaining("eu.exeris.caps.vault.internal.VaultStore");
        }
    }

    // ------------------------------------------------------------------ fixture

    /** Loads the compiled sample caps; parent-first so hooks + recorder resolve to the test's own. */
    private static URLClassLoader capClassLoader() throws IOException {
        return new URLClassLoader(new URL[]{classesDir.toUri().toURL()},
                CapCompositionE2ETest.class.getClassLoader());
    }

    /**
     * The sample SKU: two caps with a real {@code @Requires} edge (so the order is derived, not
     * declared), one shared service contract, one cap-private internal type, and one
     * {@code @ExerisDomain} entity — the domain is what makes the build emit an {@code Application}
     * at all, which is where the G2 conductor call site lands.
     */
    private static Map<String, String> sampleSku() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("eu/exeris/caps/api/VaultApi.java",
                """
                package eu.exeris.caps.api;

                /** The service contract vault provides and audit consumes. */
                public interface VaultApi {
                    String secret(String key);
                }
                """);
        sources.put("eu/exeris/caps/vault/internal/VaultStore.java",
                """
                package eu.exeris.caps.vault.internal;

                /** Cap-private: legal for vault, forbidden for every sibling cap (ADR-024). */
                public class VaultStore {
                    public void open() {
                    }
                }
                """);
        sources.put("eu/exeris/caps/vault/VaultModule.java",
                """
                package eu.exeris.caps.vault;

                import eu.exeris.caps.api.VaultApi;
                import eu.exeris.caps.vault.internal.VaultStore;
                import eu.exeris.e2e.caps.CapLifecycleRecorder;
                import eu.exeris.sdk.annotation.capability.CapabilityLifecycle;
                import eu.exeris.sdk.annotation.capability.CapabilityModule;
                import eu.exeris.sdk.annotation.capability.Provides;
                import eu.exeris.sdk.composition.lifecycle.CapabilityLifecycleHooks;

                import java.time.Duration;

                @CapabilityModule
                @CapabilityLifecycle
                @Provides(service = VaultApi.class, version = "1.0.0")
                public class VaultModule implements CapabilityLifecycleHooks {

                    private final VaultStore store = new VaultStore();

                    @Override
                    public void initialize() {
                        CapLifecycleRecorder.record("vault:initialize");
                    }

                    @Override
                    public void ready() {
                        store.open();
                        CapLifecycleRecorder.record("vault:ready");
                    }

                    @Override
                    public void drain(Duration remaining) {
                        CapLifecycleRecorder.record("vault:drain");
                    }

                    @Override
                    public void terminate() {
                        CapLifecycleRecorder.record("vault:terminate");
                    }
                }
                """);
        sources.put("eu/exeris/caps/audit/AuditModule.java",
                """
                package eu.exeris.caps.audit;

                import eu.exeris.caps.api.VaultApi;
                import eu.exeris.e2e.caps.CapLifecycleRecorder;
                import eu.exeris.sdk.annotation.capability.CapabilityLifecycle;
                import eu.exeris.sdk.annotation.capability.CapabilityModule;
                import eu.exeris.sdk.annotation.capability.Requires;
                import eu.exeris.sdk.composition.lifecycle.CapabilityLifecycleHooks;

                import java.time.Duration;

                @CapabilityModule
                @CapabilityLifecycle
                @Requires(service = VaultApi.class, versionRange = "[1.0.0,2.0.0)")
                public class AuditModule implements CapabilityLifecycleHooks {

                    @Override
                    public void initialize() {
                        CapLifecycleRecorder.record("audit:initialize");
                    }

                    @Override
                    public void ready() {
                        CapLifecycleRecorder.record("audit:ready");
                    }

                    @Override
                    public void drain(Duration remaining) {
                        CapLifecycleRecorder.record("audit:drain");
                    }

                    @Override
                    public void terminate() {
                        CapLifecycleRecorder.record("audit:terminate");
                    }
                }
                """);
        sources.put("eu/exeris/sku/gateway/domain/Ticket.java",
                """
                package eu.exeris.sku.gateway.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;

                @ExerisDomain(module = "gateway", path = "/tickets")
                public class Ticket {

                    @Field(label = "Subject", required = true)
                    private String subject;

                    public String getSubject() {
                        return subject;
                    }

                    public void setSubject(String subject) {
                        this.subject = subject;
                    }
                }
                """);
        return sources;
    }
}
