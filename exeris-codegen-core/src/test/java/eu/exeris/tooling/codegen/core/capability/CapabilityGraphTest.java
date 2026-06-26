package eu.exeris.tooling.codegen.core.capability;

import eu.exeris.sdk.sourcemodel.ast.CapabilityModuleMetadata;
import eu.exeris.sdk.sourcemodel.ast.ProvidesMetadata;
import eu.exeris.sdk.sourcemodel.ast.RequiresMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@DisplayName("CapabilityGraph")
class CapabilityGraphTest {

    private static CapabilityModuleDescriptor module(String qName,
                                                     List<ProvidesMetadata> provides,
                                                     List<RequiresMetadata> requires) {
        String simple = qName.substring(qName.lastIndexOf('.') + 1);
        String pkg = qName.contains(".") ? qName.substring(0, qName.lastIndexOf('.')) : "";
        return new CapabilityModuleDescriptor(simple, pkg, qName,
                CapabilityModuleMetadata.builder().provides(provides).requires(requires).build());
    }

    @Test
    @DisplayName("a satisfied requirement resolves; provider initialises before requirer")
    void satisfiedResolvesAndOrders() {
        CapabilityModuleDescriptor provider = module("com.app.Billing",
                List.of(ProvidesMetadata.of("com.api.PaymentApi", "1.2.0")), List.of());
        CapabilityModuleDescriptor consumer = module("com.app.Checkout",
                List.of(), List.of(RequiresMetadata.of("com.api.PaymentApi", "[1.0,2.0)")));

        CapabilityGraph graph = CapabilityGraph.build(List.of(consumer, provider));

        assertThat(graph.warnings()).isEmpty();
        assertThat(graph.resolutions())
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.module()).isEqualTo("com.app.Checkout");
                    assertThat(r.satisfied()).isTrue();
                    assertThat(r.providers()).containsExactly("com.app.Billing");
                });
        // provider before requirer
        assertThat(graph.initOrder().indexOf("com.app.Billing"))
                .isLessThan(graph.initOrder().indexOf("com.app.Checkout"));
    }

    @Test
    @DisplayName("an unsatisfied non-optional requirement fails the build")
    void unsatisfiedRequiredThrows() {
        CapabilityModuleDescriptor consumer = module("com.app.Checkout",
                List.of(), List.of(RequiresMetadata.of("com.api.PaymentApi")));

        CapabilityGraphException ex = catchThrowableOfType(
                () -> CapabilityGraph.build(List.of(consumer)), CapabilityGraphException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.problems()).hasSize(1);
        assertThat(ex.problems().get(0)).contains("com.app.Checkout", "com.api.PaymentApi", "no @CapabilityModule provides it");
    }

    @Test
    @DisplayName("an unsatisfied OPTIONAL requirement is a warning, not a failure")
    void unsatisfiedOptionalWarns() {
        CapabilityModuleDescriptor consumer = module("com.app.Checkout",
                List.of(), List.of(RequiresMetadata.optional("com.api.PaymentApi")));

        CapabilityGraph graph = CapabilityGraph.build(List.of(consumer));

        assertThat(graph.warnings()).hasSize(1);
        assertThat(graph.warnings().get(0)).contains("com.api.PaymentApi", "optional");
        assertThat(graph.resolutions()).singleElement()
                .satisfies(r -> assertThat(r.satisfied()).isFalse());
    }

    @Test
    @DisplayName("a provider whose version is out of range does not satisfy the requirement")
    void versionMismatchThrows() {
        CapabilityModuleDescriptor provider = module("com.app.Billing",
                List.of(ProvidesMetadata.of("com.api.PaymentApi", "3.0.0")), List.of());
        CapabilityModuleDescriptor consumer = module("com.app.Checkout",
                List.of(), List.of(RequiresMetadata.of("com.api.PaymentApi", "[1.0,2.0)")));

        CapabilityGraphException ex = catchThrowableOfType(
                () -> CapabilityGraph.build(List.of(provider, consumer)), CapabilityGraphException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.problems().get(0)).contains("no provider matches", "com.app.Billing=3.0.0");
    }

    @Test
    @DisplayName("a dependency cycle fails the build")
    void cycleThrows() {
        CapabilityModuleDescriptor a = module("com.app.A",
                List.of(ProvidesMetadata.of("com.api.SvcA")),
                List.of(RequiresMetadata.of("com.api.SvcB")));
        CapabilityModuleDescriptor b = module("com.app.B",
                List.of(ProvidesMetadata.of("com.api.SvcB")),
                List.of(RequiresMetadata.of("com.api.SvcA")));

        CapabilityGraphException ex = catchThrowableOfType(
                () -> CapabilityGraph.build(List.of(a, b)), CapabilityGraphException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.problems()).anyMatch(p -> p.contains("dependency cycle"));
    }

    @Test
    @DisplayName("a module may provide and require the same service without a self-cycle")
    void selfProvisionIsNotACycle() {
        CapabilityModuleDescriptor selfContained = module("com.app.Mod",
                List.of(ProvidesMetadata.of("com.api.Svc", "1.0")),
                List.of(RequiresMetadata.of("com.api.Svc", "[1.0,2.0)")));

        CapabilityGraph graph = CapabilityGraph.build(List.of(selfContained));

        assertThat(graph.resolutions()).singleElement()
                .satisfies(r -> assertThat(r.satisfied()).isTrue());
        assertThat(graph.initOrder()).containsExactly("com.app.Mod");
    }

    @Test
    @DisplayName("multiple problems are all reported, deterministically")
    void allProblemsReported() {
        CapabilityModuleDescriptor c1 = module("com.app.C1",
                List.of(), List.of(RequiresMetadata.of("com.api.X")));
        CapabilityModuleDescriptor c2 = module("com.app.C2",
                List.of(), List.of(RequiresMetadata.of("com.api.Y")));

        assertThatThrownBy(() -> CapabilityGraph.build(List.of(c2, c1)))
                .isInstanceOf(CapabilityGraphException.class)
                .satisfies(t -> {
                    CapabilityGraphException ex = (CapabilityGraphException) t;
                    assertThat(ex.problems()).hasSize(2);
                    // sorted by requiring module (C1 before C2)
                    assertThat(ex.problems().get(0)).contains("com.app.C1");
                    assertThat(ex.problems().get(1)).contains("com.app.C2");
                });
    }

    @Test
    @DisplayName("modules and resolutions are sorted for a byte-stable manifest")
    void deterministicOrdering() {
        CapabilityModuleDescriptor p = module("com.app.Zeta",
                List.of(ProvidesMetadata.of("com.api.S")), List.of());
        CapabilityModuleDescriptor c = module("com.app.Alpha",
                List.of(), List.of(RequiresMetadata.of("com.api.S")));

        CapabilityGraph g1 = CapabilityGraph.build(List.of(p, c));
        CapabilityGraph g2 = CapabilityGraph.build(List.of(c, p));

        assertThat(g1.modules()).extracting(CapabilityModuleDescriptor::qualifiedName)
                .containsExactly("com.app.Alpha", "com.app.Zeta");
        assertThat(g1.modules()).isEqualTo(g2.modules());
        assertThat(g1.resolutions()).isEqualTo(g2.resolutions());
        assertThat(g1.initOrder()).isEqualTo(g2.initOrder());
        // the stamp binding is part of the byte-stable manifest too
        assertThat(g1.stamp().contentBinding()).isEqualTo(g2.stamp().contentBinding());
    }

    // ---------- ADR-024 composition validation stamp (obligation 7) ----------

    @Test
    @DisplayName("a validated graph carries a stamp: validated=true + a content binding")
    void stampPresentOnSuccess() {
        CapabilityGraph graph = CapabilityGraph.build(List.of(module("com.app.Mod",
                List.of(ProvidesMetadata.of("com.api.Svc", "1.0")), List.of())));

        assertThat(graph.stamp()).isNotNull();
        assertThat(graph.stamp().validated()).isTrue();
        assertThat(graph.stamp().contentBinding()).startsWith("sha256:");
        // schemaVersion bumped to 2 when the stamp landed
        assertThat(graph.schemaVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("the content binding is deterministic (order-independent) and pins the cap set + versions")
    void contentBindingDeterministicAndVersionSensitive() {
        CapabilityModuleDescriptor p = module("com.app.Zeta",
                List.of(ProvidesMetadata.of("com.api.S", "1.0")), List.of());
        CapabilityModuleDescriptor c = module("com.app.Alpha",
                List.of(), List.of(RequiresMetadata.of("com.api.S", "[1.0,2.0)")));

        // same cap set, different input order → identical binding
        String b1 = CapabilityGraph.build(List.of(p, c)).stamp().contentBinding();
        String b2 = CapabilityGraph.build(List.of(c, p)).stamp().contentBinding();
        assertThat(b1).isEqualTo(b2);

        // bump a provided version → the binding changes (non-transferable: "this" composition)
        CapabilityModuleDescriptor pV2 = module("com.app.Zeta",
                List.of(ProvidesMetadata.of("com.api.S", "1.1")), List.of());
        String b3 = CapabilityGraph.build(List.of(pV2, c)).stamp().contentBinding();
        assertThat(b3).isNotEqualTo(b1);
    }

    @Test
    @DisplayName("the emitted binding matches the composition-spec golden vector (cross-module conformance)")
    void contentBindingMatchesSpecGoldenVector() {
        // The same fixture the spec's CompositionBindingTest pins. The emitter now delegates to the
        // shared CompositionBinding, so this also proves the producer→spec adapter maps service and
        // version onto the right fields — a swapped field would stay deterministic yet miss this hash.
        CapabilityModuleDescriptor audit = module("com.app.Audit",
                List.of(ProvidesMetadata.of("com.api.AuditLog", "1.0.0")), List.of());
        CapabilityModuleDescriptor billing = module("com.app.Billing",
                List.of(ProvidesMetadata.of("com.api.Invoice", "2.0.0"),
                        ProvidesMetadata.of("com.api.PaymentApi", "1.2.0")), List.of());

        // input order reversed on purpose — the canonical form sorts by qualified name.
        assertThat(CapabilityGraph.build(List.of(billing, audit)).stamp().contentBinding())
                .isEqualTo("sha256:83aae84863de8480b0c1ec943f7d350900a1ff2aab78b4c311684ca2ecc79e96");
    }

    @Test
    @DisplayName("an unversioned @Provides binds as 'service@' — never the literal 'service@null'")
    void unversionedProvideHasNoNullSuffix() {
        // ProvidesMetadata.of(service) → version == null (review: the @null-suffix bug)
        CapabilityGraph graph = CapabilityGraph.build(List.of(module("com.app.Mod",
                List.of(ProvidesMetadata.of("com.api.Svc")), List.of())));

        // the binding is still well-formed + deterministic, with no "null" leaking in
        assertThat(graph.stamp().contentBinding()).matches("sha256:[0-9a-f]{64}");
        String rebuilt = CapabilityGraph.build(List.of(module("com.app.Mod",
                List.of(ProvidesMetadata.of("com.api.Svc")), List.of())))
                .stamp().contentBinding();
        assertThat(rebuilt).isEqualTo(graph.stamp().contentBinding());
        // an unversioned provide must NOT collide with a literal version "null"
        String literalNull = CapabilityGraph.build(List.of(module("com.app.Mod",
                List.of(ProvidesMetadata.of("com.api.Svc", "null")), List.of())))
                .stamp().contentBinding();
        assertThat(literalNull).isNotEqualTo(graph.stamp().contentBinding());
    }

    @Test
    @DisplayName("composition version: defaults to UNVERSIONED, or passes through the overload")
    void compositionVersionPassthrough() {
        CapabilityModuleDescriptor m = module("com.app.Mod",
                List.of(ProvidesMetadata.of("com.api.Svc", "1.0")), List.of());

        assertThat(CapabilityGraph.build(List.of(m)).stamp().compositionVersion())
                .isEqualTo(CompositionStamp.UNVERSIONED);
        assertThat(CapabilityGraph.build(List.of(m), "2.4.1").stamp().compositionVersion())
                .isEqualTo("2.4.1");

        // the composition version does NOT enter the content binding (it is a separate,
        // independently-matched field per ADR-024 obligation 7)
        assertThat(CapabilityGraph.build(List.of(m), "2.4.1").stamp().contentBinding())
                .isEqualTo(CapabilityGraph.build(List.of(m), "9.9.9").stamp().contentBinding());
    }
}
