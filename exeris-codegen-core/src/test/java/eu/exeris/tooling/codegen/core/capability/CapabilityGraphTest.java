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
    }
}
