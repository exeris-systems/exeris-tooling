package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.SagaMetadata;
import eu.exeris.sdk.sourcemodel.ast.SagaStepMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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

    // --- K5 / kernel ADR-064: @Saga.version reaches the flow definition ------------------------

    @Test
    @DisplayName("K5: a saga at the default version 1 emits no version — byte-identical to an explicit version(1)")
    void unversionedSagaEmitsNoVersion() {
        // Version 1 is FlowDefinition.INITIAL_VERSION, what an unversioned builder produces anyway.
        // Emitting nothing keeps every saga written before K5 byte-identical, and keeps the call
        // off engines whose builder does not override the throwing default.
        String byDefault = sagaFlow(sagaVersioned(null));
        String explicitOne = sagaFlow(sagaVersioned(1));

        assertThat(explicitOne).isEqualTo(byDefault);
        assertThat(byDefault)
                .doesNotContain("DEFINITION_VERSION")
                .doesNotContain(".version(")
                .contains("FlowDefinitionBuilder builder = flowEngine.plans().newDefinition(DEFINITION_NAME);");
    }

    @Test
    @DisplayName("K5: a declared version > 1 emits DEFINITION_VERSION and builder.version(...) before the plan is compiled")
    void versionedSagaDeclaresItsVersion() {
        String flow = sagaFlow(sagaVersioned(3));

        assertThat(flow)
                .contains("private static final int DEFINITION_VERSION = 3;")
                .contains("From {@code @Saga(version = 3)}")
                .contains("builder.version(DEFINITION_VERSION);");
        // The identity pair sits together, and the version is told to the builder after it exists
        // and before build() — a call placed after build() would compile and version nothing.
        assertThat(flow.indexOf("DEFINITION_VERSION = 3"))
                .isGreaterThan(flow.indexOf("DEFINITION_NAME = \"OrderFulfillment\""))
                .isLessThan(flow.indexOf("TIMEOUT_NANOS ="));
        assertThat(flow.indexOf("builder.version(DEFINITION_VERSION);"))
                .isGreaterThan(flow.indexOf("newDefinition(DEFINITION_NAME);"))
                .isLessThan(flow.indexOf("builder.step("))
                .isLessThan(flow.indexOf("compile(builder.build())"));
    }

    @Test
    @DisplayName("K5: declaring a version is purely additive — the field and the call, nothing else moves")
    void versionIsPurelyAdditive() {
        List<String> unversioned = sagaFlow(sagaVersioned(null)).lines().toList();
        List<String> versioned = sagaFlow(sagaVersioned(7)).lines().toList();

        // Walk the versioned flow in order, consuming the unversioned one as a subsequence: every
        // line that does not advance it was added. Order-aware, so a moved or rewritten line shows
        // up as both an unconsumed original and an extra rather than hiding behind a set match.
        List<String> added = new ArrayList<>();
        int next = 0;
        for (String line : versioned) {
            if (next < unversioned.size() && line.equals(unversioned.get(next))) {
                next++;
            } else {
                added.add(line);
            }
        }
        assertThat(next)
                .as("every line of the unversioned flow survives, in order")
                .isEqualTo(unversioned.size());
        assertThat(added).containsExactly(
                "    /**",
                "     * From {@code @Saga(version = 7)}. With {@link #DEFINITION_NAME} it is",
                "     * this plan's identity in the kernel plan catalog (ADR-064): a parked",
                "     * instance resumes only on the version it parked under, and moving it",
                "     * across versions takes a registered migration.",
                "     */",
                "    private static final int DEFINITION_VERSION = 7;",
                "",
                "        builder.version(DEFINITION_VERSION);");
    }

    @Test
    @DisplayName("K5: a version below 1 is refused at generation time, not at the saga's first initialize()")
    void versionBelowOneIsRefused() {
        assertThatThrownBy(() -> strategy.generate(sagaVersioned(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Saga 'OrderFulfillment' on entity 'Order' declares version 0")
                .hasMessageContaining("FlowDefinition.INITIAL_VERSION");
        assertThatThrownBy(() -> strategy.generate(sagaVersioned(-2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("declares version -2");
    }

    /** The two-step saga from {@link #shouldGenerateSagaFlow()}; {@code null} leaves the builder default. */
    private static DomainMetadata sagaVersioned(Integer version) {
        SagaMetadata.Builder saga = SagaMetadata.builder("OrderFulfillment")
                .steps(List.of(
                        SagaStepMetadata.builder("reserve-inventory", 0)
                                .compensation("restoreInventory")
                                .build(),
                        SagaStepMetadata.simple("send-email", 1, null)));
        if (version != null) {
            saga.version(version);
        }
        return DomainMetadata.builder("Order", "com.example.domain")
                .sagaMetadata(saga.build())
                .build();
    }

    private String sagaFlow(DomainMetadata metadata) {
        return strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.SAGA)
                .findFirst().orElseThrow().content();
    }
}
