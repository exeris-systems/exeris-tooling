package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.java.support.KernelScaffold;
import eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;

import javax.lang.model.element.Modifier;

/**
 * Kernel Event Handler Generator.
 * <p>
 * Emits a per-entity {@code *EventSubscriber} class that subscribes to the
 * domain events emitted by the matching {@code *EventPublisher} through the
 * Open-Core {@code eu.exeris.kernel.spi.events.EventBus}. Each declared
 * {@link DomainEventMetadata} produces:
 * <ul>
 *   <li>One {@code private static final String} constant carrying the
 *       event-type name (matches the {@code EventTypeSpec.name} the
 *       publisher registers; together they form the routing key the
 *       {@code EventBus} dispatches on).</li>
 *   <li>One {@code protected void handle<EventName>(EventDescriptor descriptor,
 *       EventPayload payload)} method. Each method matches the SPI's
 *       {@link eu.exeris.kernel.spi.events.EventHandler} functional
 *       interface and is subscribed individually by method reference.</li>
 * </ul>
 * The generated class exposes {@code subscribe()} and {@code unsubscribe()}
 * for lifecycle control; subscriptions are tracked via the
 * {@link eu.exeris.kernel.spi.events.SubscriptionToken}s the bus hands back.
 * <p>
 * The default {@code handle<EventName>} body is a logging stub that closes
 * the payload (per the SPI's refCount contract — the handler that consumes
 * the {@link eu.exeris.kernel.spi.events.EventPayload} <b>must</b> call
 * {@code close()} or use {@code try-with-resources} to release the slab).
 * Downstream consumers subclass the generated subscriber and override the
 * handler methods to add behaviour.
 * <p>
 * Note: the legacy generator was coupled to {@link KernelSagaGenerator}
 * (auto-invoked {@code saga.start(...)} on {@code *CreatedEvent}). That
 * coupling is removed — saga triggering is now application-level wiring
 * once the saga generator lands, kept symmetric with how
 * {@link KernelServiceGenerator} no longer auto-publishes from {@code save()}.
 *
 * @implNote Emission is JavaPoet-based (ADR-015).
 *
 * @author Exeris Team
 * @since 0.1.0
 */
public class KernelEventHandlerGenerator implements KernelArtifactGenerator {

    private static final ClassName ARRAY_LIST = ClassName.get("java.util", "ArrayList");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName SLF4J_LOGGER = ClassName.get("org.slf4j", "Logger");
    private static final ClassName SLF4J_LOGGER_FACTORY = ClassName.get("org.slf4j", "LoggerFactory");

    private static final ClassName EVENT_ENGINE =
            ClassName.get("eu.exeris.kernel.spi.events", "EventEngine");
    private static final ClassName EVENT_DESCRIPTOR =
            ClassName.get("eu.exeris.kernel.spi.events", "EventDescriptor");
    private static final ClassName EVENT_PAYLOAD =
            ClassName.get("eu.exeris.kernel.spi.events", "EventPayload");
    private static final ClassName SUBSCRIPTION_TOKEN =
            ClassName.get("eu.exeris.kernel.spi.events", "SubscriptionToken");

    @Override
    public GeneratedFile generate(DomainMetadata metadata) {
        if (!metadata.hasEvents()) {
            return null;
        }

        String entity = metadata.entityName();

        long distinctNames = metadata.events().stream()
                .map(e -> eventName(e, entity))
                .distinct()
                .count();
        if (distinctNames != metadata.events().size()) {
            throw new IllegalArgumentException(
                    "Duplicate event names after normalisation for entity '"
                            + entity + "'. Each @DomainEvent must produce a unique "
                            + "<Name>Event identifier.");
        }

        String packageName = metadata.packageName().replace(".domain", ".event");
        String className = entity + "EventSubscriber";
        ClassName selfType = ClassName.get(packageName, className);
        TypeName listOfTokens = ParameterizedTypeName.get(LIST, SUBSCRIPTION_TOKEN);

        TypeSpec.Builder subscriber = KernelScaffold.publicClass(className)
                .addJavadoc("Generated domain-event subscriber for $L.\n", entity)
                .addJavadoc("<p>Subscribes the declared {@code handle<EventName>} methods\n")
                .addJavadoc("to the Open-Core SPI {@link $T} bus. Each handler matches the\n", EVENT_ENGINE)
                .addJavadoc("{@link eu.exeris.kernel.spi.events.EventHandler} functional\n")
                .addJavadoc("interface; the default body logs the event and closes the\n")
                .addJavadoc("payload (per the SPI refCount contract). Subclasses override\n")
                .addJavadoc("the handler methods to add behaviour.\n")
                .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain model.\n")
                .addField(FieldSpec.builder(SLF4J_LOGGER, "LOG",
                                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$T.getLogger($T.class)", SLF4J_LOGGER_FACTORY, selfType)
                        .build());

        for (DomainEventMetadata event : metadata.events()) {
            subscriber.addField(buildEventNameConstant(entity, event));
        }

        subscriber.addField(FieldSpec.builder(EVENT_ENGINE, "eventEngine",
                        Modifier.PRIVATE, Modifier.FINAL).build())
                .addField(FieldSpec.builder(listOfTokens, "subscriptions",
                                Modifier.PRIVATE, Modifier.FINAL)
                        .initializer("new $T<>()", ARRAY_LIST)
                        .build());

        subscriber.addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(EVENT_ENGINE, "eventEngine")
                .addStatement("this.eventEngine = eventEngine")
                .build());

        subscriber.addMethod(buildSubscribe(entity, metadata));
        subscriber.addMethod(buildUnsubscribe());

        for (DomainEventMetadata event : metadata.events()) {
            subscriber.addMethod(buildHandler(entity, event));
        }

        return new GeneratedFile(packageName, className,
                KernelScaffold.render(packageName, subscriber.build()), ArtifactType.EVENT_HANDLER);
    }

    private FieldSpec buildEventNameConstant(String entity, DomainEventMetadata event) {
        String name = eventName(event, entity);
        return FieldSpec.builder(String.class, toConstantCase(name),
                        Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("$S", name)
                .build();
    }

    private MethodSpec buildSubscribe(String entity, DomainMetadata metadata) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("subscribe")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID)
                .addJavadoc("Subscribes every declared {@code handle<EventName>} method to\n")
                .addJavadoc("the event bus and tracks the returned {@link $T}s so they can\n", SUBSCRIPTION_TOKEN)
                .addJavadoc("be released by {@link #unsubscribe()}.\n");
        for (DomainEventMetadata event : metadata.events()) {
            String name = eventName(event, entity);
            String constantName = toConstantCase(name);
            String handlerMethod = "handle" + name;
            method.addStatement("subscriptions.add(eventEngine.bus().subscribe($L, this::$L))",
                    constantName, handlerMethod);
        }
        return method.build();
    }

    private MethodSpec buildUnsubscribe() {
        return MethodSpec.methodBuilder("unsubscribe")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID)
                .addJavadoc("Releases every subscription registered by {@link #subscribe()}.\n")
                .beginControlFlow("for ($T token : subscriptions)", SUBSCRIPTION_TOKEN)
                .addStatement("eventEngine.bus().unsubscribe(token)")
                .endControlFlow()
                .addStatement("subscriptions.clear()")
                .build();
    }

    private MethodSpec buildHandler(String entity, DomainEventMetadata event) {
        String name = eventName(event, entity);
        return MethodSpec.methodBuilder("handle" + name)
                .addModifiers(Modifier.PROTECTED)
                .returns(TypeName.VOID)
                .addParameter(EVENT_DESCRIPTOR, "descriptor")
                .addParameter(EVENT_PAYLOAD, "payload")
                .addJavadoc("Default handler for {@code $L}.\n", name)
                .addJavadoc("<p>Closes the payload per the SPI refCount contract; subclasses\n")
                .addJavadoc("override this method to add domain behaviour. Always use a\n")
                .addJavadoc("{@code try-with-resources} (or explicit {@code payload.close()})\n")
                .addJavadoc("in the overridden body to keep the slab pool from leaking.\n")
                .addJavadoc("@param descriptor routing metadata — stream UUID, ordinal, flags, timestamp\n")
                .addJavadoc("@param payload    ref-counted event bytes; the handler owns the close\n")
                .beginControlFlow("try (payload)")
                .addStatement("LOG.debug($S, descriptor.toEventUuid(), descriptor.toStreamUuid())",
                        "Received " + name + ": eventId={} streamId={}")
                .endControlFlow()
                .build();
    }

    private String eventName(DomainEventMetadata event, String entityName) {
        String raw = event.name();
        if (raw == null || raw.isBlank()) {
            return entityName + "Event";
        }
        return raw.endsWith("Event") ? raw : raw + "Event";
    }

    private String toConstantCase(String camelCase) {
        StringBuilder sb = new StringBuilder(camelCase.length() + 4);
        for (int i = 0; i < camelCase.length(); i++) {
            char c = camelCase.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('_');
            }
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }

    @Override
    public ArtifactType artifactType() {
        return ArtifactType.EVENT_HANDLER;
    }
}
