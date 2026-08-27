package eu.exeris.tooling.codegen.core.driver;

import eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.GraphMetadata;
import eu.exeris.sdk.sourcemodel.ast.SagaMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RequiredDrivers")
class RequiredDriversTest {

    private static DomainMetadata.Builder order() {
        return DomainMetadata.builder("Order", "com.example.domain").path("/orders");
    }

    @Test
    @DisplayName("a plain entity requires the three SPIs every emitted app uses, and no more")
    void plainEntityRequiresTheAlwaysOnThree() {
        assertThat(RequiredDrivers.forDomains(List.of(order().build())))
                .containsExactly(
                        RequiredDrivers.SUBSYSTEM_PROVIDER,
                        RequiredDrivers.PERSISTENCE_PROVIDER,
                        RequiredDrivers.HTTP_PROVIDER);
    }

    @Test
    @DisplayName("crypto is not required, though the emitted subsystems() names it")
    void cryptoIsNotRequired() {
        // The emitted default subsystems() string is "http,persistence,graph,flow,events,crypto",
        // and a consumer is invited to override it. Requiring crypto would be a claim about that
        // string rather than about an emitted artefact — no emitted code uses crypto — and the
        // whole point of deriving from artefacts is that it survives the override.
        assertThat(RequiredDrivers.forDomains(List.of(order().build())))
                .noneMatch(spi -> spi.contains("crypto"));
    }

    @Test
    @DisplayName("declared events add the event SPI, because that is what makes a publisher exist")
    void eventsAddTheEventProvider() {
        DomainMetadata withEvents = order()
                .events(List.of(DomainEventMetadata.simple("OrderCreated")))
                .build();

        assertThat(RequiredDrivers.forDomains(List.of(withEvents)))
                .contains(RequiredDrivers.EVENT_PROVIDER);
        assertThat(RequiredDrivers.forDomains(List.of(order().build())))
                .doesNotContain(RequiredDrivers.EVENT_PROVIDER);
    }

    @Test
    @DisplayName("graph and saga metadata each add their SPI")
    void graphAndSagaAddTheirProviders() {
        DomainMetadata graph = order().graphMetadata(GraphMetadata.simple("Order")).build();
        DomainMetadata saga = order().sagaMetadata(SagaMetadata.simple("OrderSaga")).build();

        assertThat(RequiredDrivers.forDomains(List.of(graph)))
                .contains(RequiredDrivers.GRAPH_PROVIDER)
                .doesNotContain(RequiredDrivers.FLOW_PROVIDER);
        assertThat(RequiredDrivers.forDomains(List.of(saga)))
                .contains(RequiredDrivers.FLOW_PROVIDER)
                .doesNotContain(RequiredDrivers.GRAPH_PROVIDER);
    }

    @Test
    @DisplayName("one entity's declaration is enough — the requirement is the app's, not the entity's")
    void oneDeclaringEntityIsEnough() {
        DomainMetadata plain = order().build();
        DomainMetadata withEvents = DomainMetadata.builder("Invoice", "com.example.domain")
                .path("/invoices")
                .events(List.of(DomainEventMetadata.simple("InvoiceIssued")))
                .build();

        assertThat(RequiredDrivers.forDomains(List.of(plain, withEvents)))
                .contains(RequiredDrivers.EVENT_PROVIDER);
    }

    @Test
    @DisplayName("no domains requires nothing — a build that emitted no app needs no driver")
    void noDomainsRequiresNothing() {
        assertThat(RequiredDrivers.forDomains(List.of())).isEmpty();
        assertThat(RequiredDrivers.forDomains(null)).isEmpty();
    }

    @Test
    @DisplayName("order is stable, so a failing build prints the same message twice")
    void orderIsStable() {
        DomainMetadata everything = order()
                .events(List.of(DomainEventMetadata.simple("OrderCreated")))
                .graphMetadata(GraphMetadata.simple("Order"))
                .sagaMetadata(SagaMetadata.simple("OrderSaga"))
                .build();

        assertThat(RequiredDrivers.forDomains(List.of(everything)))
                .containsExactlyElementsOf(RequiredDrivers.forDomains(List.of(everything)));
    }
}
