package eu.exeris.tooling.codegen.core.driver;

import eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.GraphMetadata;
import eu.exeris.sdk.sourcemodel.ast.SagaMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RequiredSubsystems")
class RequiredSubsystemsTest {

    private static DomainMetadata.Builder entity(String name) {
        return DomainMetadata.builder(name, "com.example.domain").path("/" + name.toLowerCase(Locale.ROOT));
    }

    @Test
    @DisplayName("a plain entity boots http, persistence and crypto only")
    void plainEntityBootsTheAlwaysOnThree() {
        assertThat(RequiredSubsystems.selector(List.of(entity("Note").build())))
                .isEqualTo("http,persistence,crypto");
    }

    @Test
    @DisplayName("graph metadata, a saga and a domain event each add their subsystem")
    void eachDeclarationAddsItsSubsystem() {
        assertThat(RequiredSubsystems.selector(List.of(
                entity("Order").graphMetadata(GraphMetadata.simple("Order")).build())))
                .isEqualTo("http,persistence,graph,crypto");
        assertThat(RequiredSubsystems.selector(List.of(
                entity("Order").sagaMetadata(SagaMetadata.simple("OrderSaga")).build())))
                .isEqualTo("http,persistence,flow,crypto");
        assertThat(RequiredSubsystems.selector(List.of(
                entity("Order").events(List.of(DomainEventMetadata.simple("OrderCreated"))).build())))
                .isEqualTo("http,persistence,events,crypto");
    }

    @Test
    @DisplayName("everything declared yields the full list, in the fixed order")
    void everythingYieldsTheFullList() {
        DomainMetadata everything = entity("Order")
                .events(List.of(DomainEventMetadata.simple("OrderCreated")))
                .graphMetadata(GraphMetadata.simple("Order"))
                .sagaMetadata(SagaMetadata.simple("OrderSaga"))
                .build();

        assertThat(RequiredSubsystems.selector(List.of(everything)))
                .isEqualTo("http,persistence,graph,flow,events,crypto");
    }

    @Test
    @DisplayName("the order is fixed whatever order the declaring entities arrive in")
    void orderIsIndependentOfDomainOrder() {
        DomainMetadata events = entity("Invoice")
                .events(List.of(DomainEventMetadata.simple("InvoiceIssued"))).build();
        DomainMetadata saga = entity("Order").sagaMetadata(SagaMetadata.simple("OrderSaga")).build();
        DomainMetadata graph = entity("Customer").graphMetadata(GraphMetadata.simple("Customer")).build();

        String forward = RequiredSubsystems.selector(List.of(events, saga, graph));
        assertThat(forward).isEqualTo("http,persistence,graph,flow,events,crypto");
        assertThat(RequiredSubsystems.selector(List.of(graph, saga, events))).isEqualTo(forward);
        assertThat(RequiredSubsystems.selector(List.of(saga, events, graph))).isEqualTo(forward);
    }

    @Test
    @DisplayName("one declaring entity is enough — the subsystem is the application's")
    void oneDeclaringEntityIsEnough() {
        DomainMetadata plain = entity("Note").build();
        DomainMetadata saga = entity("Order").sagaMetadata(SagaMetadata.simple("OrderSaga")).build();

        assertThat(RequiredSubsystems.forDomains(List.of(plain, saga))).contains(RequiredSubsystems.FLOW);
    }

    @Test
    @DisplayName("no domains still names the always-on three, and null reads as empty")
    void noDomainsNamesTheAlwaysOnThree() {
        assertThat(RequiredSubsystems.forDomains(List.of())).containsExactly("http", "persistence", "crypto");
        assertThat(RequiredSubsystems.forDomains(null)).containsExactly("http", "persistence", "crypto");
    }
}
