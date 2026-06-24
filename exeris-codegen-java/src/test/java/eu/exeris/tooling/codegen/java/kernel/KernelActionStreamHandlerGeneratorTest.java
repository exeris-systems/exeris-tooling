package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-generator test for {@link KernelActionStreamHandlerGenerator} (ADR-044 Slice 2).
 */
@DisplayName("KernelActionStreamHandlerGenerator")
class KernelActionStreamHandlerGeneratorTest {

    private final KernelActionStreamHandlerGenerator gen = new KernelActionStreamHandlerGenerator();

    private static DomainMetadata orderWith(List<ActionMetadata> actions) {
        return DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .actions(actions)
                .build();
    }

    @Test
    @DisplayName("supports() is true only when an action is streaming")
    void supportsOnlyStreamingActions() {
        assertThat(gen.supports(orderWith(List.of(
                ActionMetadata.builder("cancel").methodName("cancel").build())))).isFalse();
        assertThat(gen.supports(orderWith(List.of(
                ActionMetadata.builder("trackShipment").methodName("trackShipment")
                        .streaming(true).build())))).isTrue();
        assertThat(gen.supports(null)).isFalse();
    }

    @Test
    @DisplayName("emits one HttpStreamHandler per streaming action, named <Entity><ActionPascal>StreamHandler")
    void emitsOneFilePerStreamingAction() {
        DomainMetadata order = orderWith(List.of(
                ActionMetadata.builder("cancel").methodName("cancel").build(),
                ActionMetadata.builder("trackShipment").methodName("trackShipment")
                        .streaming(true).streamEventType("ShipmentMoved").build(),
                ActionMetadata.builder("watchPrice").methodName("watchPrice")
                        .streaming(true).build()));

        List<GeneratedFile> files = gen.generateMultiple(order);

        assertThat(files).hasSize(2);
        assertThat(files).extracting(GeneratedFile::className)
                .containsExactly("OrderTrackShipmentStreamHandler", "OrderWatchPriceStreamHandler");
        assertThat(files).allSatisfy(f -> {
            assertThat(f.artifactType()).isEqualTo(ArtifactType.STREAM_HANDLER);
            assertThat(f.packageName()).isEqualTo("com.example.handler");
            assertThat("java").isEqualTo(f.extension());
        });
    }

    @Test
    @DisplayName("handler implements HttpStreamHandler, uses the named streamEventType, and keeps the SPI/EV1 contract")
    void handlerShapeIsKernelTargetAndNamed() {
        DomainMetadata order = orderWith(List.of(
                ActionMetadata.builder("trackShipment").methodName("trackShipment")
                        .streaming(true).streamEventType("ShipmentMoved").build()));

        String content = gen.generate(order).content();

        assertThat(content)
                .contains("implements HttpStreamHandler")
                .contains("public void handle(HttpStreamExchange exchange)")
                // named SSE event from @Action.streamEventType (obligation 2)
                .contains("STREAM_EVENT_TYPE = \"ShipmentMoved\"")
                // deterministic keep-alive scaffold (constant, no wall-clock)
                .contains("KEEPALIVE_INTERVAL_MILLIS = 15000L")
                .contains("exchange.close()")
                // EV1 producer seam marker
                .contains("bind domain-event bus producer (EV1)")
                // kernel-target discipline: Core owns the wire
                .doesNotContain("text/event-stream");
    }

    @Test
    @DisplayName("defaults the SSE event name to the action name when streamEventType is unset")
    void defaultsEventNameToActionName() {
        DomainMetadata order = orderWith(List.of(
                ActionMetadata.builder("watchPrice").methodName("watchPrice")
                        .streaming(true).build()));

        assertThat(gen.generate(order).content())
                .contains("STREAM_EVENT_TYPE = \"watchPrice\"");
    }

    @Test
    @DisplayName("same metadata → byte-identical output (determinism, constraint #3)")
    void deterministicOutput() {
        DomainMetadata order = orderWith(List.of(
                ActionMetadata.builder("trackShipment").methodName("trackShipment")
                        .streaming(true).streamEventType("ShipmentMoved").build()));

        assertThat(gen.generate(order).content()).isEqualTo(gen.generate(order).content());
    }
}
