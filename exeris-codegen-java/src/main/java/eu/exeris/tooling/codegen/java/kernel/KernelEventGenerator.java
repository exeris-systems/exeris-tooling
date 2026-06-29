package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.java.support.KernelEventSupport;
import eu.exeris.tooling.codegen.java.support.KernelScaffold;
import eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;

import javax.lang.model.element.Modifier;

/**
 * Kernel Event Generator.
 * <p>
 * Emits a per-entity {@code *EventPublisher} class that publishes domain
 * events through the Open-Core {@code eu.exeris.kernel.spi.events.EventEngine}
 * bus. Shape mirrors the canonical community benchmark app's
 * {@code DomainEventPublisher} pattern.
 * <p>
 * Emitted publisher contract:
 * <ul>
 *   <li>One {@code static final EventTypeSpec} constant per
 *       {@link DomainEventMetadata}. Each spec is constructed via
 *       {@code EventTypeSpec.ofPersistent(name, ordinal)} so the
 *       descriptor carries {@code FLAG_PERSISTENT} and the SPI persists
 *       the event through the transactional outbox transparently.</li>
 *   <li>The ordinal is derived from {@code hashCode(entityName + "." + eventName)}
 *       masked to 31 bits — stable across regenerations, deterministic.
 *       Note that {@code EventRegistry} is a global per-engine namespace,
 *       so cross-entity collisions are possible (two different
 *       {@code entityName + eventName} keys can hash to the same 31-bit
 *       value, and the SPI registry will reject the second registration
 *       with {@code EventRegistryException}). Downstream consumers that
 *       need explicit ordinal allocation can wrap the generated
 *       publisher or extend {@code DomainEventMetadata} to carry an
 *       explicit ordinal.</li>
 *   <li>Constructor takes an {@code EventEngine}, registers every spec
 *       with {@code eventEngine.registry()}. Per the SPI contract
 *       {@code register(EventTypeSpec)} is idempotent for identical
 *       specs — no defensive {@code catch} is needed; any exception
 *       (e.g.\ {@code EventRegistryException} on conflicting
 *       re-registration with different settings) propagates so the
 *       caller sees the misconfiguration immediately.</li>
 *   <li>One {@code publish<EventName>(UUID streamId)} method per event:
 *       builds an {@code EventDescriptor} via {@code EventDescriptor.of(...)}
 *       carrying a fresh event UUID, the aggregate stream UUID, the
 *       registered ordinal, the spec flags, and the current epoch
 *       milliseconds; then calls {@code eventEngine.bus().publish(descriptor,
 *       EventPayload.empty())}.</li>
 * </ul>
 * <p>
 * Payloads default to {@link eu.exeris.kernel.spi.events.EventPayload#empty()}.
 * Routing happens entirely through the Valhalla-ready {@code EventDescriptor};
 * downstream consumers that need to ship payload bytes can extend the
 * generated publisher.
 *
 * @implNote Emission is JavaPoet-based (ADR-015).
 *
 * @author Exeris Team
 * @since 0.1.0
 */
public class KernelEventGenerator implements KernelArtifactGenerator {

    private static final ClassName UUID = ClassName.get("java.util", "UUID");
    private static final ClassName SLF4J_LOGGER = ClassName.get("org.slf4j", "Logger");
    private static final ClassName SLF4J_LOGGER_FACTORY = ClassName.get("org.slf4j", "LoggerFactory");

    private static final ClassName EVENT_ENGINE = KernelEventSupport.EVENT_ENGINE;
    private static final ClassName EVENT_DESCRIPTOR = KernelEventSupport.EVENT_DESCRIPTOR;
    private static final ClassName EVENT_PAYLOAD = KernelEventSupport.EVENT_PAYLOAD;
    private static final ClassName EVENT_TYPE_SPEC =
            ClassName.get("eu.exeris.kernel.spi.events", "EventTypeSpec");

    @Override
    public GeneratedFile generate(DomainMetadata metadata) {
        if (!metadata.hasEvents()) {
            return null;
        }

        String packageName = metadata.packageName().replace(".domain", ".event");
        String entity = metadata.entityName();
        String className = entity + "EventPublisher";
        ClassName selfType = ClassName.get(packageName, className);

        KernelEventSupport.assertDistinctEventNames(metadata);

        TypeSpec.Builder publisher = KernelScaffold.publicClass(className)
                .addModifiers(Modifier.FINAL)
                .addJavadoc("Generated domain-event publisher for $L.\n", entity)
                .addJavadoc("<p>Publishes events through the Open-Core SPI\n")
                .addJavadoc("{@link $T} bus. Each event type is registered with the\n", EVENT_ENGINE)
                .addJavadoc("engine's {@code registry()} at construction; descriptors\n")
                .addJavadoc("carry {@code FLAG_PERSISTENT} so the SPI persists them via\n")
                .addJavadoc("the transactional outbox transparently.\n")
                .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain model.\n")
                .addField(FieldSpec.builder(SLF4J_LOGGER, "LOG",
                                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$T.getLogger($T.class)", SLF4J_LOGGER_FACTORY, selfType)
                        .build());

        for (DomainEventMetadata event : metadata.events()) {
            publisher.addField(buildEventTypeSpec(entity, event));
        }

        publisher.addField(FieldSpec.builder(EVENT_ENGINE, "eventEngine",
                        Modifier.PRIVATE, Modifier.FINAL).build());

        publisher.addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(EVENT_ENGINE, "eventEngine")
                .addStatement("this.eventEngine = eventEngine")
                .addStatement("registerEventTypes()")
                .build());

        for (DomainEventMetadata event : metadata.events()) {
            publisher.addMethod(buildPublishMethod(event, metadata));
        }

        publisher.addMethod(buildRegisterEventTypes(metadata));

        return new GeneratedFile(packageName, className,
                KernelScaffold.render(packageName, publisher.build()), ArtifactType.EVENT);
    }

    private FieldSpec buildEventTypeSpec(String entity, DomainEventMetadata event) {
        String eventName = KernelEventSupport.eventName(event, entity);
        int ordinal = (entity + "." + eventName).hashCode() & 0x7FFFFFFF;
        String constantName = KernelEventSupport.toConstantCase(eventName);
        return FieldSpec.builder(EVENT_TYPE_SPEC, constantName,
                        Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("$T.ofPersistent($S, $L)", EVENT_TYPE_SPEC, eventName, ordinal)
                .build();
    }

    private MethodSpec buildPublishMethod(DomainEventMetadata event, DomainMetadata metadata) {
        String eventName = KernelEventSupport.eventName(event, metadata.entityName());
        String constantName = KernelEventSupport.toConstantCase(eventName);
        String methodName = "publish" + eventName;

        MethodSpec.Builder method = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID)
                .addParameter(UUID, "streamId")
                .addJavadoc("Publishes a {@code $L} event for the given aggregate stream.\n", eventName)
                .addJavadoc("@param streamId aggregate (entity) UUID — encoded into the descriptor's stream-id pair\n");
        if (event.topic() != null && !event.topic().isBlank()) {
            method.addJavadoc("<p>Originally tagged with topic {@code $L} in the source\n", event.topic());
            method.addJavadoc("domain model; topic routing is not part of the Open-Core SPI\n");
            method.addJavadoc("event descriptor and is preserved here only for reference.\n");
        }

        method.addStatement("$T eventUuid = $T.randomUUID()", UUID, UUID)
                .addStatement("$T descriptor = $T.of(\n"
                                + "        eventUuid.getMostSignificantBits(),\n"
                                + "        eventUuid.getLeastSignificantBits(),\n"
                                + "        streamId.getMostSignificantBits(),\n"
                                + "        streamId.getLeastSignificantBits(),\n"
                                + "        $L.ordinal(),\n"
                                + "        $L.toDescriptorFlags(),\n"
                                + "        $T.currentTimeMillis())",
                        EVENT_DESCRIPTOR, EVENT_DESCRIPTOR, constantName, constantName,
                        ClassName.get("java.lang", "System"))
                // EV1: the resolved payload-field metadata now exists on
                // DomainEventMetadata (event.payloadFields() / sensitiveFields()),
                // but runtime byte-serialization of the payload stays gated on a
                // future kernel event-payload codec SPI (see the EV1 codec ADR).
                // Until that SPI lands the publisher ships an empty payload — DO
                // NOT change this runtime behaviour from EventPayload.empty().
                .addStatement("eventEngine.bus().publish(descriptor, $T.empty())", EVENT_PAYLOAD)
                .addStatement("LOG.debug($S, eventUuid, streamId)",
                        "Published " + eventName + ": eventId={} streamId={}");

        return method.build();
    }

    private MethodSpec buildRegisterEventTypes(DomainMetadata metadata) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("registerEventTypes")
                .addModifiers(Modifier.PRIVATE)
                .addJavadoc("Registers every declared event-type spec with the engine's\n")
                .addJavadoc("registry. {@link $T#register(EventTypeSpec)} is idempotent for\n",
                        ClassName.get("eu.exeris.kernel.spi.events", "EventRegistry"))
                .addJavadoc("identical specs (SPI contract), so concurrent publisher\n")
                .addJavadoc("instantiation is safe and no defensive catch is needed.\n");
        for (DomainEventMetadata event : metadata.events()) {
            String constantName = KernelEventSupport.toConstantCase(
                    KernelEventSupport.eventName(event, metadata.entityName()));
            method.addStatement("eventEngine.registry().register($L)", constantName);
        }
        return method.build();
    }

    @Override
    public ArtifactType artifactType() {
        return ArtifactType.EVENT;
    }
}
