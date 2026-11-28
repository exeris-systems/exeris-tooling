package eu.exeris.e2e.codegen;

import eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.java.kernel.KernelGeneratorStrategy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The payload record of each domain event the generated publisher declares, held against
 * {@code contract/event-payload.json}.
 *
 * <p>The generated TypeScript payload interface declares the contract's components;
 * {@code event-payload-parity.spec.ts} in {@code exeris-codegen-ts} holds it against the same file,
 * which is the only place the two builds meet.
 */
@Tag("e2e")
@Tag("codegen")
@DisplayName("Event payload parity: generated <Event>EventPayload records == contract")
class EventPayloadParityE2ETest {

    record Field(String name, String type) {}

    record Component(String name, String javaType, String tsType) {}

    record Event(String name, List<String> payloadFields, List<String> sensitiveFields,
                 List<Component> components) {}

    record Contract(String description, String entity, List<Field> fields, List<Event> events) {}

    private static Contract contract;
    private static String publisher;

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = EventPayloadParityE2ETest.class.getResourceAsStream("/contract/event-payload.json")) {
            assertThat(in).as("contract/event-payload.json on the test classpath").isNotNull();
            contract = JsonMapper.builder().build().readValue(in, Contract.class);
        }
        DomainMetadata metadata = DomainMetadata.builder(contract.entity(), "eu.exeris.e2e.parity.domain")
                .fields(contract.fields().stream()
                        .map(f -> FieldMetadata.builder(f.name(), f.type()).build())
                        .toList())
                .events(contract.events().stream()
                        .map(e -> DomainEventMetadata.builder(e.name())
                                .payloadFields(e.payloadFields())
                                .sensitiveFields(e.sensitiveFields())
                                .build())
                        .toList())
                .build();
        GeneratedFile file = new KernelGeneratorStrategy().generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.EVENT)
                .findFirst()
                .orElseThrow();
        publisher = file.content();
    }

    @Test
    @DisplayName("each event's payload record declares exactly the contract's components")
    void payloadRecordsDeclareTheContract() {
        for (Event event : contract.events()) {
            Matcher m = Pattern.compile("record " + event.name() + "EventPayload\\(([^)]*)\\)").matcher(publisher);
            if (event.components().isEmpty()) {
                assertThat(m.find()).as("no payload record for %s", event.name()).isFalse();
                continue;
            }
            assertThat(m.find()).as("payload record for %s", event.name()).isTrue();
            List<String> declared = new ArrayList<>();
            for (String component : m.group(1).split(",")) {
                declared.add(component.trim().replaceAll("\\s+", " "));
            }
            List<String> expected = event.components().stream()
                    .map(c -> c.javaType() + " " + c.name())
                    .toList();
            assertThat(declared).as("components of %s", event.name()).containsExactlyElementsOf(expected);
        }
    }
}
