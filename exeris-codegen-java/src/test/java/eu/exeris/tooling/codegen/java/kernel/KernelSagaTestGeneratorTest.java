package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.SagaMetadata;
import eu.exeris.sdk.sourcemodel.ast.SagaStepMetadata;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-generator test for {@link KernelSagaTestGenerator} (T2, ADR-058).
 *
 * <p>Shape only. That the emitted chain assertion <em>catches a broken transition chain</em> is
 * proven by {@code GeneratedTestsE2ETest}, which compiles and runs it against a real emitted saga.
 */
@DisplayName("KernelSagaTestGenerator")
class KernelSagaTestGeneratorTest {

    private static DomainMetadata sagaEntity(String sagaName, SagaStepMetadata... steps) {
        return DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .sagaMetadata(SagaMetadata.builder(sagaName)
                        .timeout("PT30M")
                        .maxRetries(3)
                        .steps(List.of(steps))
                        .build())
                .build();
    }

    private static final DomainMetadata ORDER_SAGA = sagaEntity("OrderSaga",
            SagaStepMetadata.simple("reserveStock", 0, "ReleaseStock"),
            SagaStepMetadata.simple("chargePayment", 1, null));

    private static String generate(DomainMetadata metadata) {
        return new KernelSagaTestGenerator().generate(metadata, "com.example").content();
    }

    @Test
    @DisplayName("emits <Saga>FlowTest beside the saga, typed as a TEST artefact")
    void emitsSagaTest() {
        GeneratedFile file = new KernelSagaTestGenerator().generate(ORDER_SAGA, "com.example");

        assertThat(file.className()).isEqualTo("OrderSagaFlowTest");
        assertThat(file.packageName()).isEqualTo("com.example.saga");
        assertThat(file.artifactType()).isEqualTo(ArtifactType.TEST);
    }

    @Test
    @DisplayName("emits nothing for an entity that declares no saga")
    void skipsNonSagaEntities() {
        DomainMetadata plain = DomainMetadata.builder("Order", "com.example.domain").build();

        assertThat(new KernelSagaTestGenerator().generate(plain, "com.example")).isNull();
    }

    @Test
    @DisplayName("the class name is derived through the saga emitter, not spelled again")
    void derivesTheClassNameFromTheEmitter() {
        // A @Saga(name) that already ends in Flow keeps it; anything else gains the suffix. Both
        // sides go through KernelSagaGenerator.sagaFlowType, so a test naming a class the emitter
        // does not produce is not expressible.
        GeneratedFile alreadyFlow = new KernelSagaTestGenerator()
                .generate(sagaEntity("PaymentFlow", SagaStepMetadata.simple("charge", 0, null)),
                        "com.example");

        assertThat(alreadyFlow.className()).isEqualTo("PaymentFlowTest");
    }

    @Test
    @DisplayName("the chain assertion reads the RECORDED steps, not a baked-in count")
    void chainAssertionIsStructural() {
        String source = generate(ORDER_SAGA);

        // This is the whole point: the emitter walks the step list twice (register, then lay
        // transitions over it) and the assertion compares those two walks at runtime. A literal
        // expected count would come from the same metadata as the emitter and could never fail.
        assertThat(source)
                .contains("assertThat(flow.transitions).hasSize(flow.steps.size() - 1)")
                .contains("for (int i = 0; i < flow.steps.size() - 1; i++)")
                .contains("assertThat(flow.transitions.get(i)).isEqualTo(i + \"->\" + (i + 1))");
        // No step-name literals: those are single-path and asserting them would be circular.
        assertThat(source).doesNotContain("\"reserveStock\"");
    }

    @Test
    @DisplayName("covers lazy-init idempotence and that schedule reuses the initialized plan")
    void coversTheLifecycleContract() {
        String source = generate(ORDER_SAGA);

        assertThat(source)
                .contains("void initializeIsIdempotent()")
                .contains("assertThat(saga.initialize()).isSameAs(saga.initialize())")
                .contains("void scheduleHandsTheSchedulerTheInitializedPlan()")
                .contains("assertThat(flow.scheduled).isSameAs(saga.initialize())")
                .contains("assertThat(flow.compiled).isEqualTo(1)");
    }

    @Test
    @DisplayName("asserts every step stub returns CONTINUE, compensations included")
    void coversTheDefaultOutcome() {
        String source = generate(ORDER_SAGA);

        assertThat(source)
                .contains("void everyStepDefaultsToContinueSoTheSkeletonIsSchedulable()")
                .contains("for (FlowStepAction action : flow.actions)")
                .contains("assertThat(action.execute(flow)).isEqualTo(FlowOutcome.CONTINUE)")
                .contains("if (compensation != null)");
    }

    @Test
    @DisplayName("the dependency contract holds: JUnit + AssertJ only, no mocking framework")
    void importsOnlyTheContractDependencies() {
        String source = generate(ORDER_SAGA);

        assertThat(source)
                .contains("import org.junit.jupiter.api.Test;")
                .contains("import org.assertj.core.api.Assertions;")
                .contains("import com.example.testsupport.RecordingFlow;");
        assertThat(source)
                .doesNotContain("org.mockito")
                .doesNotContain("org.easymock");
    }

    @Test
    @DisplayName("emission is deterministic — byte-identical across runs")
    void emissionIsDeterministic() {
        assertThat(generate(ORDER_SAGA)).isEqualTo(generate(ORDER_SAGA));
    }
}
