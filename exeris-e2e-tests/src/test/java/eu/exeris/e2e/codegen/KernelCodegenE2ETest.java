package eu.exeris.e2e.codegen;

import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.GraphEdgeMetadata;
import eu.exeris.sdk.sourcemodel.ast.GraphMetadata;
import eu.exeris.sdk.sourcemodel.ast.SagaMetadata;
import eu.exeris.tooling.codegen.java.kernel.KernelGeneratorStrategy;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("e2e")
@Tag("codegen")
@DisplayName("Kernel Codegen E2E Tests")
class KernelCodegenE2ETest {

    private static DomainMetadata orderMetadata;
    private static DomainMetadata productMetadata;

    @BeforeAll
    static void setupMetadata() {
        orderMetadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .module("sales")
                .description("Sales order entity")
                .tenantScoped(true)
                .audited(true)
                .events(List.of(DomainEventMetadata.simple("OrderCreated")))
                .graphMetadata(GraphMetadata.simple("Order"))
                .sagaMetadata(SagaMetadata.simple("OrderSaga"))
                .build();

        productMetadata = DomainMetadata.builder("Product", "com.shop.domain")
                .path("/products")
                .module("catalog")
                .build();
    }

    @Nested
    @DisplayName("Kernel Backend")
    class KernelBackendTests {
        private final KernelGeneratorStrategy strategy = new KernelGeneratorStrategy();

        @Test
        @DisplayName("Should generate exactly the SPI-aligned subset (Controller, Service, Repository, Event + EventHandler + GraphSync + Saga when declared, Migration, OpenAPI, Client)")
        void shouldGenerateCoreArtifacts() {
            List<GeneratedFile> files = strategy.generate(orderMetadata);
            assertThat(files).extracting(GeneratedFile::artifactType)
                    .containsExactlyInAnyOrder(
                            ArtifactType.CONTROLLER,
                            ArtifactType.SERVICE,
                            ArtifactType.REPOSITORY,
                            ArtifactType.EVENT,
                            ArtifactType.EVENT_HANDLER,
                            ArtifactType.GRAPH_SYNC,
                            ArtifactType.SAGA,
                            ArtifactType.CONFIGURATION,
                            ArtifactType.OPENAPI_SPEC,
                            // CLIENT added when KernelClientGenerator was
                            // unparked against the 0.8.0-SNAPSHOT
                            // CommunityWebClient SPI. The CommunityWebClient
                            // stub at exeris-e2e-tests/src/test/java/eu/
                            // exeris/kernel/community/http/client/ is the
                            // compile-test classpath shim the generated
                            // client code resolves against.
                            ArtifactType.CLIENT);
        }

        @Test
        @DisplayName("Handler emits against Open-Core SPI HttpExchange")
        void handlerShouldUseSpiHttpExchange() {
            List<GeneratedFile> files = strategy.generate(orderMetadata);
            String handler = files.stream().filter(f -> f.artifactType() == ArtifactType.CONTROLLER)
                    .findFirst().orElseThrow().content();
            assertThat(handler)
                    .contains("eu.exeris.kernel.spi.http.HttpExchange")
                    .contains("eu.exeris.kernel.spi.http.HttpStatus")
                    .contains("eu.exeris.kernel.spi.memory.LoanedBuffer")
                    .contains("handleGetAll(HttpExchange exchange)")
                    .contains("handleCreate(HttpExchange exchange)");
        }

        @Test
        @DisplayName("EventPublisher emits against Open-Core SPI EventEngine")
        void eventPublisherShouldUseSpiEventEngine() {
            List<GeneratedFile> files = strategy.generate(orderMetadata);
            String publisher = files.stream().filter(f -> f.artifactType() == ArtifactType.EVENT)
                    .findFirst().orElseThrow().content();
            assertThat(publisher)
                    .contains("eu.exeris.kernel.spi.events.EventEngine")
                    .contains("eu.exeris.kernel.spi.events.EventDescriptor")
                    .contains("eu.exeris.kernel.spi.events.EventPayload")
                    .contains("eu.exeris.kernel.spi.events.EventTypeSpec")
                    .contains("EventTypeSpec.ofPersistent(\"OrderCreatedEvent\"")
                    .contains("eventEngine.bus().publish(descriptor, EventPayload.empty())");
        }

        @Test
        @DisplayName("GraphSync emits against Open-Core SPI GraphEngine + GraphSession")
        void graphSyncShouldUseSpiGraphEngine() {
            DomainMetadata withEdges = DomainMetadata.builder("Order", "com.example.domain")
                    .path("/orders")
                    .graphMetadata(new GraphMetadata("Order", List.of(),
                            List.of(new GraphEdgeMetadata("ownerId", "User", "OWNED_BY")),
                            List.of()))
                    .build();
            List<GeneratedFile> files = strategy.generate(withEdges);
            String graphSync = files.stream().filter(f -> f.artifactType() == ArtifactType.GRAPH_SYNC)
                    .findFirst().orElseThrow().content();
            assertThat(graphSync)
                    .contains("eu.exeris.kernel.spi.graph.GraphEngine")
                    .contains("eu.exeris.kernel.spi.graph.GraphSession")
                    .contains("eu.exeris.kernel.spi.graph.model.GraphNodeDescriptor")
                    .contains("eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor")
                    .contains("GraphNodeDescriptor.create(\"Order\", \"orders\")")
                    .contains("GraphEdgeDescriptor.create(\"Order\", \"OWNED_BY\", \"User\")")
                    .contains("session.upsertNode(NODE_LABEL, entity.getId(), null)")
                    .contains("session.upsertEdge(OWNER_ID_EDGE, entity.getId(), entity.getOwnerId(), 1.0, null)");
        }

        @Test
        @DisplayName("SagaFlow emits against Open-Core SPI FlowEngine + flow model")
        void sagaShouldUseSpiFlowEngine() {
            List<GeneratedFile> files = strategy.generate(orderMetadata);
            String sagaFlow = files.stream().filter(f -> f.artifactType() == ArtifactType.SAGA)
                    .findFirst().orElseThrow().content();
            assertThat(sagaFlow)
                    .contains("eu.exeris.kernel.spi.flow.FlowEngine")
                    .contains("eu.exeris.kernel.spi.flow.FlowDefinitionBuilder")
                    .contains("eu.exeris.kernel.spi.flow.model.FlowContext")
                    .contains("eu.exeris.kernel.spi.flow.model.FlowExecutionPlan")
                    .contains("eu.exeris.kernel.spi.flow.model.FlowOutcome")
                    .contains("public synchronized FlowExecutionPlan initialize()")
                    .contains("flowEngine.plans().newDefinition(DEFINITION_NAME)")
                    .contains("flowEngine.plans().compile(builder.build())")
                    .contains("flowEngine.scheduler().schedule(initialize(), context)")
                    .contains("return FlowOutcome.CONTINUE");
        }

        @Test
        @DisplayName("EventSubscriber emits against Open-Core SPI EventBus + EventHandler")
        void eventSubscriberShouldUseSpiEventBus() {
            List<GeneratedFile> files = strategy.generate(orderMetadata);
            String subscriber = files.stream().filter(f -> f.artifactType() == ArtifactType.EVENT_HANDLER)
                    .findFirst().orElseThrow().content();
            assertThat(subscriber)
                    .contains("eu.exeris.kernel.spi.events.EventDescriptor")
                    .contains("eu.exeris.kernel.spi.events.EventPayload")
                    .contains("eu.exeris.kernel.spi.events.SubscriptionToken")
                    .contains("private static final String ORDER_CREATED_EVENT")
                    .doesNotContain("public static final String ORDER_CREATED_EVENT")
                    .contains("if (!subscriptions.isEmpty())")
                    .contains("eventEngine.bus().subscribe(ORDER_CREATED_EVENT, this::handleOrderCreatedEvent)")
                    .contains("protected void handleOrderCreatedEvent(EventDescriptor descriptor, EventPayload payload)")
                    .contains("try (payload)");
        }

        @Test
        @DisplayName("Should use canonical naming convention")
        void shouldUseCanonicalNaming() {
            List<GeneratedFile> files = strategy.generate(productMetadata);
            assertThat(files.stream().filter(f -> f.artifactType() == ArtifactType.SERVICE).findFirst().orElseThrow().className())
                    .isEqualTo("ProductService");
            assertThat(files.stream().filter(f -> f.artifactType() == ArtifactType.REPOSITORY).findFirst().orElseThrow().className())
                    .isEqualTo("ProductRepository");
        }
    }
}
