package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.java.support.KernelEventSupport;
import eu.exeris.tooling.codegen.java.support.KernelScaffold;
import eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
 *       the event through the transactional outbox transparently. A
 *       declared {@code @DomainEvent.topic} lands here as the three-arg
 *       {@code ofPersistent(name, ordinal, topic)} (ADR-050): the
 *       binding-agnostic routing target rides the per-<em>type</em>
 *       registration record, not the per-instance {@code EventDescriptor}
 *       (which stays primitive-only / Valhalla-ready). Broker bindings
 *       honour it; the in-memory bus treats it as advisory.</li>
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
 *   <li>One {@code publish<EventName>(...)} method per event: builds an
 *       {@code EventDescriptor} via {@code EventDescriptor.of(...)} carrying a
 *       fresh event UUID, the aggregate stream UUID, the registered ordinal, the
 *       spec flags, and the current epoch milliseconds. An event with
 *       {@code payloadFields} takes the aggregate as a second parameter
 *       ({@code publish<Event>(UUID streamId, <Entity> entity)}) and ships an
 *       EV1 payload (below); an event with none keeps the
 *       {@code publish<Event>(UUID streamId)} shape and an empty payload.</li>
 * </ul>
 * <p>
 * <b>EV1 payloads (ADR-046).</b> For an event with {@code payloadFields}, the
 * publisher builds a nested redacted {@code <Event>Payload} record (its declared
 * {@code payloadFields} minus {@code sensitiveFields}) from the aggregate's
 * getters, resolves an {@code EventPayloadCodec} via
 * {@link eu.exeris.kernel.spi.context.KernelProviders#eventPayloadCodecRegistry()}
 * ("site B" resolution, default content-type {@code application/json}), and
 * publishes the encoded {@code EventPayload}. When the slot is unbound or no codec
 * supports the payload, it falls back to
 * {@link eu.exeris.kernel.spi.events.EventPayload#empty()} (the latter emitting a
 * producer-side codec-resolution-failure JFR). Redaction is the publisher's job,
 * applied before encode; the generated code names only SPI symbols (never a codec
 * driver / Jackson — the Wall).
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

    // ADR-046 Event-Payload Codec SPI — resolved in the generated publisher ("site B").
    private static final ClassName KERNEL_PROVIDERS =
            ClassName.get("eu.exeris.kernel.spi.context", "KernelProviders");
    private static final ClassName EVENT_PAYLOAD_CODEC =
            ClassName.get("eu.exeris.kernel.spi.events.codec", "EventPayloadCodec");
    private static final ClassName EVENT_PAYLOAD_CODEC_REGISTRY =
            ClassName.get("eu.exeris.kernel.spi.events.codec", "EventPayloadCodecRegistry");
    private static final ClassName EVENT_CODEC_CONTEXT =
            ClassName.get("eu.exeris.kernel.spi.events.codec", "EventCodecContext");
    private static final ClassName OPTIONAL = ClassName.get("java.util", "Optional");
    private static final ClassName JFR_EVENT = ClassName.get("jdk.jfr", "Event");
    private static final String ENCODE_HELPER = "encodePayload";
    private static final String JFR_EVENT_NAME = "CodecUnresolvedEvent";

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

        boolean anyPayload = false;
        for (DomainEventMetadata event : metadata.events()) {
            List<FieldMetadata> payload = payloadFields(event, metadata);
            if (payload.isEmpty()) {
                publisher.addMethod(buildPublishMethod(event, metadata));
            } else {
                anyPayload = true;
                publisher.addType(buildPayloadRecord(event, metadata, payload));
                publisher.addMethod(buildPublishMethodWithPayload(event, metadata, selfType, payload));
            }
        }

        // EV1 (ADR-046): the codec-resolution helper + the producer-side
        // codec-resolution-failure JFR are emitted once, only when at least one
        // event carries a payload.
        if (anyPayload) {
            publisher.addMethod(buildEncodePayloadHelper(selfType));
            publisher.addType(buildCodecUnresolvedJfrEvent(packageName, entity));
        }

        publisher.addMethod(buildRegisterEventTypes(metadata));

        return new GeneratedFile(packageName, className,
                KernelScaffold.render(packageName, publisher.build()), ArtifactType.EVENT);
    }

    private FieldSpec buildEventTypeSpec(String entity, DomainEventMetadata event) {
        String eventName = KernelEventSupport.eventName(event, entity);
        int ordinal = (entity + "." + eventName).hashCode() & 0x7FFFFFFF;
        String constantName = KernelEventSupport.toConstantCase(eventName);
        FieldSpec.Builder field = FieldSpec.builder(EVENT_TYPE_SPEC, constantName,
                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);
        // ADR-050: a declared @DomainEvent.topic lands on the per-type EventTypeSpec
        // (binding-agnostic routing target), not on the per-instance EventDescriptor.
        // Absent/blank topic keeps the two-arg factory (topic = null, "no override").
        if (event.topic() != null && !event.topic().isBlank()) {
            field.initializer("$T.ofPersistent($S, $L, $S)",
                    EVENT_TYPE_SPEC, eventName, ordinal, event.topic());
        } else {
            field.initializer("$T.ofPersistent($S, $L)", EVENT_TYPE_SPEC, eventName, ordinal);
        }
        return field.build();
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
            method.addJavadoc("<p>Routes on topic {@code $L} (ADR-050) — carried on the\n", event.topic());
            method.addJavadoc("registered {@code EventTypeSpec}; broker bindings honour it, the\n");
            method.addJavadoc("in-memory bus treats it as advisory.\n");
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
                // No payloadFields on this event → nothing to encode, so it ships an
                // empty payload. Events WITH payloadFields take the codec-resolved
                // path (buildPublishMethodWithPayload, ADR-046).
                .addStatement("eventEngine.bus().publish(descriptor, $T.empty())", EVENT_PAYLOAD)
                .addStatement("LOG.debug($S, eventUuid, streamId)",
                        "Published " + eventName + ": eventId={} streamId={}");

        return method.build();
    }

    /** Resolved payload fields for an event: its {@code payloadFields} minus
     *  {@code sensitiveFields}, mapped to the entity's {@link FieldMetadata}
     *  (declaration-order, skipping names not present on the entity). */
    private static List<FieldMetadata> payloadFields(DomainEventMetadata event, DomainMetadata metadata) {
        Set<String> sensitive = new HashSet<>(event.sensitiveFields());
        List<FieldMetadata> out = new ArrayList<>();
        for (String name : event.payloadFields()) {
            if (sensitive.contains(name)) {
                continue;
            }
            metadata.fields().stream()
                    .filter(f -> f.name().equals(name))
                    .findFirst()
                    .ifPresent(out::add);
        }
        return out;
    }

    /** A nested {@code <Event>Payload} record carrying the redacted payload fields. */
    private TypeSpec buildPayloadRecord(DomainEventMetadata event, DomainMetadata metadata,
                                        List<FieldMetadata> fields) {
        String eventName = KernelEventSupport.eventName(event, metadata.entityName());
        MethodSpec.Builder ctor = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC);
        for (FieldMetadata f : fields) {
            ctor.addParameter(ParameterSpec.builder(KernelTypeMapping.typeNameOf(f.type()), f.name()).build());
        }
        return TypeSpec.recordBuilder(eventName + "Payload")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addJavadoc("Redacted EV1 payload for {@code $L} — its declared payloadFields\n", eventName)
                .addJavadoc("minus sensitiveFields. Serialized by the resolved ADR-046 codec.\n")
                .recordConstructor(ctor.build())
                .build();
    }

    /** Publish method for an event WITH a payload: builds the redacted record from
     *  the entity, resolves the ADR-046 codec, and publishes the encoded payload. */
    private MethodSpec buildPublishMethodWithPayload(DomainEventMetadata event, DomainMetadata metadata,
                                                     ClassName selfType, List<FieldMetadata> fields) {
        String eventName = KernelEventSupport.eventName(event, metadata.entityName());
        String constantName = KernelEventSupport.toConstantCase(eventName);
        ClassName entityType = ClassName.get(metadata.packageName(), metadata.entityName());
        ClassName payloadType = selfType.nestedClass(eventName + "Payload");

        MethodSpec.Builder method = MethodSpec.methodBuilder("publish" + eventName)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID)
                .addParameter(UUID, "streamId")
                .addParameter(entityType, "entity")
                .addJavadoc("Publishes a {@code $L} event for the given aggregate stream.\n", eventName)
                .addJavadoc("@param streamId aggregate (entity) UUID — encoded into the descriptor's stream-id pair\n")
                .addJavadoc("@param entity source of the redacted EV1 payload (payloadFields minus sensitiveFields)\n");
        if (event.topic() != null && !event.topic().isBlank()) {
            method.addJavadoc("<p>Routes on topic {@code $L} (ADR-050) — carried on the registered\n", event.topic());
            method.addJavadoc("{@code EventTypeSpec}, honoured by broker bindings, advisory in-memory.\n");
        }

        StringBuilder args = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                args.append(", ");
            }
            FieldMetadata f = fields.get(i);
            // Primitive `boolean` getter is `isX()`, everything else `getX()` — the
            // SDK getter convention the Repository generator already follows.
            String prefix = "boolean".equals(f.type()) ? "is" : "get";
            args.append("entity.").append(prefix).append(capitalize(f.name())).append("()");
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
                .addStatement("$T payload = new $T(" + args + ")", payloadType, payloadType)
                .addStatement("eventEngine.bus().publish(descriptor, $L($T.class, payload, $S))",
                        ENCODE_HELPER, payloadType, eventName)
                .addStatement("LOG.debug($S, eventUuid, streamId)",
                        "Published " + eventName + ": eventId={} streamId={}");
        return method.build();
    }

    /** Shared helper resolving the ADR-046 codec ("site B") and encoding the payload.
     *  Falls back to {@link EventPayload#empty()} when the slot is unbound or no codec
     *  supports the payload; the null-resolve branch emits the codec-resolution JFR. */
    private MethodSpec buildEncodePayloadHelper(ClassName selfType) {
        ClassName jfrEvent = selfType.nestedClass(JFR_EVENT_NAME);
        TypeName classWildcard = ParameterizedTypeName.get(
                ClassName.get("java.lang", "Class"), WildcardTypeName.subtypeOf(Object.class));
        TypeName optionalRegistry = ParameterizedTypeName.get(OPTIONAL, EVENT_PAYLOAD_CODEC_REGISTRY);
        return MethodSpec.methodBuilder(ENCODE_HELPER)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(EVENT_PAYLOAD)
                .addParameter(classWildcard, "payloadType")
                .addParameter(ClassName.get("java.lang", "Object"), "payload")
                .addParameter(ClassName.get("java.lang", "String"), "eventName")
                .addJavadoc("Resolves the ADR-046 event-payload codec from the kernel provider slot\n")
                .addJavadoc("and encodes the payload; empty payload when no codec is configured.\n")
                .addStatement("$T registry = $T.eventPayloadCodecRegistry()", optionalRegistry, KERNEL_PROVIDERS)
                .beginControlFlow("if (registry.isEmpty())")
                .addStatement("LOG.debug($S, payloadType.getName(), eventName)",
                        "No event-payload codec registry bound for {} ({}); publishing empty payload")
                .addStatement("return $T.empty()", EVENT_PAYLOAD)
                .endControlFlow()
                .addStatement("$T codec = registry.get().resolve(payloadType, $T.JSON)",
                        EVENT_PAYLOAD_CODEC, EVENT_CODEC_CONTEXT)
                .beginControlFlow("if (codec == null)")
                .addStatement("$T jfr = new $T()", jfrEvent, jfrEvent)
                .beginControlFlow("if (jfr.isEnabled())")
                .addStatement("jfr.payloadType = payloadType.getName()")
                .addStatement("jfr.contentType = $T.JSON", EVENT_CODEC_CONTEXT)
                .addStatement("jfr.eventType = eventName")
                .addStatement("jfr.commit()")
                .endControlFlow()
                .addStatement("LOG.debug($S, payloadType.getName(), eventName)",
                        "No event-payload codec resolved for {} ({}); publishing empty payload")
                .addStatement("return $T.empty()", EVENT_PAYLOAD)
                .endControlFlow()
                // payload is Object on purpose: the codec SPI's encode(Object, ctx)
                // is generics-free (no type erasure to reason about) — the codec
                // resolved for payloadType serializes the concrete record at runtime.
                .addStatement("return codec.encode(payload, $T.json(eventName))", EVENT_CODEC_CONTEXT)
                .build();
    }

    /** Producer-side codec-resolution-failure JFR event (ADR-046), secret-safe
     *  (type/content-type/event-type strings only — no payload bytes, no message). */
    private TypeSpec buildCodecUnresolvedJfrEvent(String packageName, String entity) {
        ClassName label = ClassName.get("jdk.jfr", "Label");
        return TypeSpec.classBuilder(JFR_EVENT_NAME)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .superclass(JFR_EVENT)
                .addJavadoc("Emitted when the registry resolves no codec for an event payload,\n")
                .addJavadoc("so an empty payload shipped (ADR-046, producer-side resolution).\n")
                .addAnnotation(AnnotationSpec.builder(ClassName.get("jdk.jfr", "Name"))
                        .addMember("value", "$S", packageName + "." + entity + "EventPayloadCodecUnresolved").build())
                .addAnnotation(AnnotationSpec.builder(label)
                        .addMember("value", "$S", "Event Payload Codec Unresolved").build())
                .addAnnotation(AnnotationSpec.builder(ClassName.get("jdk.jfr", "Category"))
                        .addMember("value", "{$S, $S}", "Exeris", "Events").build())
                .addAnnotation(AnnotationSpec.builder(ClassName.get("jdk.jfr", "StackTrace"))
                        .addMember("value", "$L", false).build())
                .addField(FieldSpec.builder(String.class, "payloadType")
                        .addAnnotation(AnnotationSpec.builder(label).addMember("value", "$S", "Payload Type").build()).build())
                .addField(FieldSpec.builder(String.class, "contentType")
                        .addAnnotation(AnnotationSpec.builder(label).addMember("value", "$S", "Content Type").build()).build())
                .addField(FieldSpec.builder(String.class, "eventType")
                        .addAnnotation(AnnotationSpec.builder(label).addMember("value", "$S", "Event Type").build()).build())
                .build();
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
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
