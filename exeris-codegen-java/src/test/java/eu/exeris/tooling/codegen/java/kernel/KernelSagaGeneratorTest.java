package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.SagaMetadata;
import eu.exeris.sdk.sourcemodel.ast.SagaStepMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Per-generator test for {@link KernelSagaGenerator} (emits the per-entity
 * {@code *Flow} skeleton against Open-Core SPI {@code spi.flow.FlowEngine}
 * + {@code spi.flow.FlowDefinitionBuilder} + {@code spi.flow.model.*}).
 */
@DisplayName("KernelSagaGenerator")
class KernelSagaGeneratorTest {

    private KernelGeneratorStrategy strategy;

    @BeforeEach
    void setup() {
        strategy = new KernelGeneratorStrategy();
    }

    @Test
    @DisplayName("Should generate SagaFlow skeleton emitting against Open-Core SPI FlowEngine")
    void shouldGenerateSagaFlow() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .sagaMetadata(SagaMetadata.builder("OrderFulfillment")
                        .timeout("PT45M")
                        .maxRetries(5)
                        .steps(List.of(
                                SagaStepMetadata.builder("reserve-inventory", 0)
                                        .compensation("restoreInventory")
                                        .build(),
                                SagaStepMetadata.simple("send-email", 1, null)))
                        .build())
                .build();

        List<GeneratedFile> files = strategy.generate(metadata);

        GeneratedFile sagaFlow = files.stream()
                .filter(f -> f.artifactType() == ArtifactType.SAGA)
                .findFirst()
                .orElseThrow();

        assertThat(sagaFlow.className()).isEqualTo("OrderFulfillmentFlow");
        assertThat(sagaFlow.packageName()).isEqualTo("com.example.saga");
        assertThat(sagaFlow.content())
                .contains("import eu.exeris.kernel.spi.flow.FlowEngine")
                .contains("import eu.exeris.kernel.spi.flow.FlowDefinitionBuilder")
                .contains("import eu.exeris.kernel.spi.flow.model.FlowContext")
                .contains("import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan")
                .contains("import eu.exeris.kernel.spi.flow.model.FlowOutcome")
                .contains("public class OrderFulfillmentFlow")
                .contains("private static final String DEFINITION_NAME = \"OrderFulfillment\"")
                .contains("private static final long TIMEOUT_NANOS = Duration.parse(\"PT45M\").toNanos()")
                .contains("private static final int MAX_RETRIES = 5")
                .contains("public OrderFulfillmentFlow(FlowEngine flowEngine)")
                .contains("public synchronized FlowExecutionPlan initialize()")
                .contains("FlowDefinitionBuilder builder = flowEngine.plans().newDefinition(DEFINITION_NAME)")
                .contains("builder.step(\"reserve-inventory\", this::reserveInventory, this::compensateReserveInventory)")
                .contains("builder.step(\"send-email\", this::sendEmail, null)")
                .contains("builder.transition(0, 1)")
                .contains("builder.timeoutDuration(TIMEOUT_NANOS).maxRetries(MAX_RETRIES)")
                .contains("flowEngine.plans().compile(builder.build())")
                .contains("public void schedule(FlowContext context)")
                .contains("flowEngine.scheduler().schedule(initialize(), context)")
                .contains("protected FlowOutcome reserveInventory(FlowContext context)")
                .contains("protected FlowOutcome compensateReserveInventory(FlowContext context)")
                .contains("protected FlowOutcome sendEmail(FlowContext context)")
                .contains("return FlowOutcome.CONTINUE");
    }

    @Test
    @DisplayName("Should not emit SagaFlow when no saga metadata is declared")
    void shouldSkipSagaWhenNoSagaMetadata() {
        DomainMetadata metadata = DomainMetadata.builder("Tenant", "com.example.domain")
                .build();

        List<GeneratedFile> files = strategy.generate(metadata);

        assertThat(files).isNotEmpty()
                .extracting(GeneratedFile::artifactType)
                .doesNotContain(ArtifactType.SAGA);
    }

    @Test
    @DisplayName("Should emit a placeholder step when saga declares no steps")
    void shouldEmitPlaceholderWhenNoSteps() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .sagaMetadata(SagaMetadata.simple("OrderSaga"))
                .build();

        List<GeneratedFile> files = strategy.generate(metadata);

        String sagaFlow = files.stream()
                .filter(f -> f.artifactType() == ArtifactType.SAGA)
                .findFirst().orElseThrow().content();
        assertThat(sagaFlow)
                .contains("builder.step(\"process\", this::process, null)")
                .contains("protected FlowOutcome process(FlowContext context)");
    }

    @Test
    @DisplayName("Should reject step names that normalise to the same Java method identifier")
    void shouldRejectNormalisedStepNameCollision() {
        // "my-step" and "my_step" both normalise to camelCase "myStep";
        // a raw-name check would miss this. The guard runs on the
        // emitted method names, so it catches the collision.
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .sagaMetadata(SagaMetadata.builder("OrderSaga")
                        .steps(List.of(
                                SagaStepMetadata.simple("my-step", 0, null),
                                SagaStepMetadata.simple("my_step", 1, null)))
                        .build())
                .build();

        assertThatThrownBy(() -> strategy.generate(metadata))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Saga step method-name collision")
                .hasMessageContaining("myStep");
    }

    @Test
    @DisplayName("Should reject action-vs-compensation method-name collision across steps")
    void shouldRejectActionVsCompensationCollision() {
        // Step "compensate-foo" emits action method `compensateFoo()`.
        // Step "foo" + compensation also emits a compensation method
        // `compensateFoo()`. The guard catches the cross-category clash.
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .sagaMetadata(SagaMetadata.builder("OrderSaga")
                        .steps(List.of(
                                SagaStepMetadata.simple("compensate-foo", 0, null),
                                SagaStepMetadata.builder("foo", 1)
                                        .compensation("rollback")
                                        .build()))
                        .build())
                .build();

        assertThatThrownBy(() -> strategy.generate(metadata))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Saga step method-name collision")
                .hasMessageContaining("compensateFoo");
    }
}
