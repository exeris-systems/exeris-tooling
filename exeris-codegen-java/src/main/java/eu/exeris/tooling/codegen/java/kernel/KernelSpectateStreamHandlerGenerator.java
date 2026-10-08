package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.java.support.DataScopeSupport;
import eu.exeris.tooling.codegen.java.support.KernelEventSupport;
import eu.exeris.tooling.codegen.java.support.KernelScaffold;
import eu.exeris.tooling.codegen.java.support.KernelStreamScaffold;
import eu.exeris.tooling.codegen.java.support.KernelStreamScaffold.StreamEventBinding;
import eu.exeris.tooling.codegen.java.support.NameCasing;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Kernel Spectate Stream Handler Generator (SSE spectate route, ADR-044 Amendment 2 decision 7).
 *
 * <p>Emits one {@code <Entity>SpectateStreamHandler} per entity annotated
 * {@code @ExerisDomain(realTimeApi = true)}: an open-ended stream of one row's events. The handler
 * implements the kernel streaming SPI (ADR-043), {@code eu.exeris.kernel.spi.http.HttpStreamHandler},
 * and is registered by {@link KernelApplicationGenerator} at {@code GET {base}/{id}/stream} via the
 * router's {@code streamRoute(...)}. The entity-level live view
 * ({@link KernelStreamHandlerGenerator}, {@code GET {base}/stream}) and this route differ in their
 * segment count, so neither template can take the other's request.
 *
 * <h2>What the handler does, in order (Amendment 2, decisions 6 and 7)</h2>
 * <ol>
 *   <li>The tenant guard, on a tenant-partitioned entity: no bound {@code STORAGE_CONTEXT} is
 *       refused. The processor refuses {@code realTimeApi} on such an entity
 *       ({@code EXT-PROC-1014}); the guard holds for metadata that did not pass it.</li>
 *   <li>The path {@code id}.</li>
 *   <li>{@code service.findById(id)} under row-level security; an absent or invisible row is
 *       refused. Visibility is checked once, when the stream opens.</li>
 *   <li>One bus subscription per {@code @DomainEvent} of the entity, forwarding only events whose
 *       descriptor stream id equals the row id — the id every emitted publish call passes as the
 *       stream id.</li>
 *   <li>Each forwarded event as a frame named by its {@code @DomainEvent} name, and a
 *       {@code keep-alive} frame after each {@code KEEPALIVE_INTERVAL_MILLIS} without one, until the
 *       client disconnects. There is no deadline; the keep-alive is what lets a disconnect surface
 *       from {@code emit} while the row is quiet.</li>
 * </ol>
 * <p>An entity with no {@code @DomainEvent} subscribes to nothing: its spectate stream checks the
 * row and sends keep-alive frames until the client disconnects.
 *
 * <h2>Frames</h2>
 * <p>The engine writes the {@code 200} response head before {@code handle} runs, so a refusal is
 * the reserved {@code stream-error} frame, whose data is an RFC 9457 problem object carrying the
 * status the respond-once {@code GET {base}/{id}} route answers: {@code 400} for a malformed
 * {@code id}, {@code 404} for a row that is absent or invisible, {@code 500} for the tenant guard
 * and any other failure.
 *
 * <h2>Hand-off queue (Amendment 2, decision 5)</h2>
 * <p>The bus callback runs on a dispatch thread and {@code emit} must run on the stream's own, so
 * events cross through a bounded queue of {@code STREAM_BUFFER_CAPACITY} that drops on full and
 * logs each drop.
 *
 * <h2>Kernel-target discipline</h2>
 * <p>No {@code text/event-stream} literal and no framing: Core owns the wire. A
 * {@code StreamClosedException} from {@code emit} propagates; {@code close()} runs in
 * {@code finally} and is idempotent.
 *
 * @implNote Emission is JavaPoet-based (ADR-015), routed through {@link KernelScaffold}. The
 *     request steps, the row-id filter, the subscription body and the refusal helper are
 *     {@link KernelStreamScaffold}'s, shared with {@link KernelActionStreamHandlerGenerator}; the
 *     subscription body is also the entity-level producer's.
 *
 * @since 0.10
 * @see "docs/adr/ADR-044-tooling-sse-stream-emitter-shape.md — Amendment 2, decision 7."
 * @see KernelStreamHandlerGenerator
 */
public class KernelSpectateStreamHandlerGenerator implements KernelArtifactGenerator {

    private static final ClassName HTTP_STREAM_HANDLER = KernelStreamScaffold.HTTP_STREAM_HANDLER;
    private static final ClassName HTTP_STREAM_EXCHANGE = KernelStreamScaffold.HTTP_STREAM_EXCHANGE;
    private static final ClassName STREAM_EVENT = KernelStreamScaffold.STREAM_EVENT;
    private static final ClassName RUNTIME_EXCEPTION = ClassName.get("java.lang", "RuntimeException");
    private static final ClassName INTERRUPTED_EXCEPTION =
            ClassName.get("java.lang", "InterruptedException");

    /** Parameter name of the stream exchange on every emitted method. */
    private static final String EXCHANGE = "exchange";
    /** Name of the service field and constructor parameter. */
    private static final String SERVICE = "service";
    /** Name of the event-engine field and constructor parameter. */
    private static final String EVENT_ENGINE = "eventEngine";
    /** Name of the emitted method that holds the stream open. */
    private static final String SPECTATE = "spectate";
    /** The emitted {@code try} opener. */
    private static final String TRY = "try";
    /** The emitted {@code catch} clause opener. */
    private static final String CATCH = "catch ($T e)";
    /** The emitted statement that emits one {@code keep-alive} frame. */
    private static final String EMIT_KEEP_ALIVE = "exchange.emit($T.of($S, $S))";

    /**
     * Creates the generator. It keeps no per-domain state, so one instance serves every domain
     * in a build.
     */
    public KernelSpectateStreamHandlerGenerator() {
        // no state to initialise
    }

    @Override
    public boolean supports(DomainMetadata metadata) {
        return metadata != null && metadata.realTimeApi();
    }

    /**
     * Whether the entity's spectate handler subscribes to the bus, and therefore takes an
     * {@code EventEngine} by constructor after the service.
     *
     * <p>Shared with {@link KernelApplicationGenerator}, which emits the matching
     * {@code RuntimeComponents} factory: one predicate keeps the factory's arity and the
     * constructor's equal.
     *
     * @param metadata the entity
     * @return {@code true} when the entity is {@code realTimeApi} and declares at least one
     *         {@code @DomainEvent}
     */
    public static boolean subscribes(DomainMetadata metadata) {
        return metadata.realTimeApi() && metadata.hasEvents();
    }

    /**
     * The simple name of the entity's spectate handler, shared with
     * {@link KernelApplicationGenerator} so the class it constructs is the class written here.
     *
     * @param metadata the entity
     * @return {@code <Entity>SpectateStreamHandler}
     */
    public static String className(DomainMetadata metadata) {
        return metadata.entityName() + "SpectateStreamHandler";
    }

    @Override
    public GeneratedFile generate(DomainMetadata metadata) {
        // The registry filters through supports(); the single-generator path does not.
        if (!supports(metadata)) {
            return null;
        }
        String entity = metadata.entityName();
        String className = className(metadata);
        assertNoActionHandlerNamedLikeIt(metadata, className);
        String basePackage = metadata.packageName().replace(".domain", "");
        String packageName = basePackage + ".handler";
        String spectatePath = metadata.effectivePath() + "/{id}/stream";
        String entityLower = entity.isEmpty() ? entity
                : entity.substring(0, 1).toLowerCase(Locale.ROOT) + entity.substring(1);

        ClassName selfType = ClassName.get(packageName, className);
        ClassName entityType = ClassName.get(metadata.packageName(), entity);
        ClassName serviceType = ClassName.get(basePackage + ".service", entity + "Service");
        List<StreamEventBinding> bindings = bindings(metadata);
        boolean subscribes = !bindings.isEmpty();
        boolean tenantPartitioned = DataScopeSupport.isTenantPartitioned(metadata);

        TypeSpec.Builder handler = KernelScaffold.publicClass(className)
                .addSuperinterface(HTTP_STREAM_HANDLER)
                .addJavadoc("Generated SSE spectate stream handler for one $L row.\n", entity)
                .addJavadoc("<p>Implements {@link $T}; registered at {@code GET $L}\n",
                        HTTP_STREAM_HANDLER, spectatePath)
                .addJavadoc("via the router's {@code streamRoute(...)}. The stream stays open until the client\n")
                .addJavadoc("disconnects: see {@link #handle($T)} for the order of the steps.\n",
                        HTTP_STREAM_EXCHANGE)
                .addJavadoc("<p>The engine writes the {@code 200} response head before {@link #handle($T)}\n",
                        HTTP_STREAM_EXCHANGE)
                .addJavadoc("runs, so every refusal is a {@code stream-error} frame whose data is a problem\n")
                .addJavadoc("object carrying the status the respond-once {@code GET} by id answers.\n")
                .addJavadoc("<p>Row visibility is checked once, when the stream opens. A row that becomes\n")
                .addJavadoc("invisible to the caller, or is deleted, while the stream is open keeps\n")
                .addJavadoc("streaming its events until the client disconnects.\n")
                .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain model.\n")
                .addFields(fields(selfType, serviceType, subscribes))
                .addMethod(constructor(serviceType, subscribes))
                .addMethod(handleMethod(entity, tenantPartitioned, subscribes))
                .addMethod(serveMethod(entity, entityLower, entityType, bindings, tenantPartitioned))
                .addMethod(spectateMethod(subscribes))
                .addMethod(KernelStreamScaffold.refuseMethod("the respond-once {@code GET} by id"));

        return new GeneratedFile(packageName, className,
                KernelScaffold.render(packageName, handler.build()), ArtifactType.SPECTATE_STREAM_HANDLER);
    }

    /**
     * Refuses a streaming action whose handler would have this handler's name: the action
     * {@code spectate} of the same entity is {@code <Entity>SpectateStreamHandler} too, and one file
     * and one {@code RuntimeComponents} accessor would silently replace the other.
     */
    private static void assertNoActionHandlerNamedLikeIt(DomainMetadata metadata, String className) {
        for (ActionMetadata action : metadata.actions()) {
            if (action.streaming() && "Spectate".equals(NameCasing.pascal(action.name()))) {
                throw new IllegalStateException(metadata.entityName() + " is realTimeApi and declares the"
                        + " streaming action \"" + action.name() + "\": both stream handlers would be named "
                        + className + ". Rename the action.");
            }
        }
    }

    /**
     * Every {@code @DomainEvent} of the entity, in declaration order: the bus key the publisher
     * registers it under, and its name as the frame name — the bindings the live view uses.
     */
    private static List<StreamEventBinding> bindings(DomainMetadata metadata) {
        // Two events that normalise to one bus key would subscribe twice and deliver each event
        // twice; the publisher refuses the same metadata.
        KernelEventSupport.assertDistinctEventNames(metadata);
        List<StreamEventBinding> bindings = new ArrayList<>();
        for (DomainEventMetadata event : metadata.events()) {
            bindings.add(KernelStreamScaffold.bindingOf(event, metadata.entityName()));
        }
        return bindings;
    }

    private static List<FieldSpec> fields(ClassName selfType, ClassName serviceType, boolean subscribes) {
        List<FieldSpec> fields = new ArrayList<>();
        fields.add(KernelStreamScaffold.loggerField(selfType));
        fields.add(FieldSpec.builder(String.class, "STREAM_ERROR",
                        Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("$S", KernelStreamScaffold.STREAM_ERROR_FRAME)
                .addJavadoc("The reserved frame name of a refusal after the response head.\n")
                .build());
        fields.add(FieldSpec.builder(TypeName.LONG, "KEEPALIVE_INTERVAL_MILLIS",
                        Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("$LL", KernelStreamScaffold.KEEPALIVE_INTERVAL_MILLIS)
                .addJavadoc("How long the stream may be quiet before it sends a {@code keep-alive} frame.\n")
                .build());
        if (subscribes) {
            fields.add(FieldSpec.builder(TypeName.INT, "STREAM_BUFFER_CAPACITY",
                            Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer("$L", KernelStreamScaffold.STREAM_BUFFER_CAPACITY)
                    .addJavadoc("Bounded hand-off between the bus dispatch thread and this stream's thread;\n"
                            + "a full queue drops the frame and logs it.\n")
                    .build());
        }
        fields.add(FieldSpec.builder(serviceType, SERVICE, Modifier.PRIVATE, Modifier.FINAL).build());
        if (subscribes) {
            fields.add(FieldSpec.builder(KernelStreamScaffold.EVENT_ENGINE, EVENT_ENGINE,
                    Modifier.PRIVATE, Modifier.FINAL).build());
        }
        return fields;
    }

    private static MethodSpec constructor(ClassName serviceType, boolean subscribes) {
        MethodSpec.Builder constructor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addJavadoc("Every argument is captured at composition: {@link #handle($T)} runs on the\n",
                        HTTP_STREAM_EXCHANGE)
                .addJavadoc("stream's own thread, where the kernel binds no engine.\n")
                .addJavadoc("@param service the entity's service, which loads the row\n")
                .addParameter(serviceType, SERVICE)
                .addStatement("this.$L = $L", SERVICE, SERVICE);
        if (subscribes) {
            constructor.addJavadoc("@param eventEngine the engine whose bus this handler subscribes on\n")
                    .addParameter(KernelStreamScaffold.EVENT_ENGINE, EVENT_ENGINE)
                    .addStatement("this.$L = $L", EVENT_ENGINE, EVENT_ENGINE);
        }
        return constructor.build();
    }

    private static MethodSpec handleMethod(String entity, boolean tenantPartitioned, boolean subscribes) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("handle")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID)
                .addParameter(HTTP_STREAM_EXCHANGE, EXCHANGE)
                .addJavadoc("Streams the events of one $L row, in this order:\n", entity)
                .addJavadoc("<ol>\n");
        if (tenantPartitioned) {
            method.addJavadoc("<li>the tenant guard: no bound {@code STORAGE_CONTEXT} is refused;</li>\n");
        }
        method.addJavadoc("<li>the path {@code id};</li>\n")
                .addJavadoc("<li>the row, read under row-level security;</li>\n");
        if (subscribes) {
            method.addJavadoc("<li>a subscription to each of the entity's events, filtered on the row id;</li>\n")
                    .addJavadoc("<li>each forwarded event as a frame, and a {@code keep-alive} frame after each\n")
                    .addJavadoc("{@code KEEPALIVE_INTERVAL_MILLIS} without one, until the client disconnects.</li>\n");
        } else {
            method.addJavadoc("<li>a {@code keep-alive} frame every {@code KEEPALIVE_INTERVAL_MILLIS} until the\n")
                    .addJavadoc("client disconnects: the entity declares no event to forward.</li>\n");
        }
        return method.addJavadoc("</ol>\n")
                .addJavadoc("<p>A refusal is one {@code stream-error} frame, and so is any other failure:\n")
                .addJavadoc("{@code 500}. The stream is closed when this returns; a\n")
                .addJavadoc("{@code StreamClosedException} from {@code emit} propagates.\n")
                .addStatement("LOG.log($T.DEBUG, $S)", KernelScaffold.LOGGER_LEVEL,
                        "Opening " + entity + " spectate stream")
                .beginControlFlow(TRY)
                .addStatement("serve(exchange)")
                .nextControlFlow("catch ($T closed)", KernelStreamScaffold.STREAM_CLOSED_EXCEPTION)
                .addStatement("throw closed")
                .nextControlFlow(CATCH, RUNTIME_EXCEPTION)
                .addStatement("LOG.log($T.ERROR, $S, e)", KernelScaffold.LOGGER_LEVEL,
                        entity + " spectate stream failed")
                .addStatement("refuse(exchange, $L, $S)", 500, "Internal Server Error")
                .nextControlFlow("finally")
                .addStatement("exchange.close()")
                .endControlFlow()
                .build();
    }

    private static MethodSpec serveMethod(String entity, String entityLower, ClassName entityType,
                                          List<StreamEventBinding> bindings, boolean tenantPartitioned) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("serve")
                .addModifiers(Modifier.PRIVATE)
                .addParameter(HTTP_STREAM_EXCHANGE, EXCHANGE);
        if (tenantPartitioned) {
            method.addCode(KernelStreamScaffold.tenantGuard(
                    KernelHandlerGenerator.tenantUnboundMessage(entityLower)));
        }
        method.addCode(KernelStreamScaffold.pathId())
                .addCode(KernelStreamScaffold.rowLoad(entityType,
                        "Failed to load " + entityLower + " for its spectate stream"));
        if (bindings.isEmpty()) {
            return method.addStatement("$L(exchange)", SPECTATE).build();
        }
        method.addComment("Only events published under this row's id are forwarded.")
                .addCode(KernelStreamScaffold.rowHandOff())
                .beginControlFlow(TRY);
        CodeBlock guard = KernelStreamScaffold.rowStreamIdGuard();
        for (StreamEventBinding binding : bindings) {
            method.addCode(KernelStreamScaffold.subscription(binding, guard, "DEBUG",
                    binding.wireName() + " frame dropped on the " + entity
                            + " spectate stream (slow consumer)"));
        }
        return method.addStatement("$L(exchange, queue)", SPECTATE)
                .nextControlFlow("finally")
                .beginControlFlow("for ($T token : tokens)", KernelStreamScaffold.SUBSCRIPTION_TOKEN)
                .addStatement("bus.unsubscribe(token)")
                .endControlFlow()
                .endControlFlow()
                .build();
    }

    /**
     * The open-ended loop: forwarded frames as they arrive, a keep-alive when the row is quiet, until
     * {@code emit} throws on a disconnect. Without subscriptions there is nothing to wait on, so it
     * sleeps between keep-alives.
     */
    private static MethodSpec spectateMethod(boolean subscribes) {
        MethodSpec.Builder method = MethodSpec.methodBuilder(SPECTATE)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .addParameter(HTTP_STREAM_EXCHANGE, EXCHANGE);
        if (subscribes) {
            method.addJavadoc("Emits each forwarded event, and a {@code keep-alive} frame after each\n")
                    .addJavadoc("{@code KEEPALIVE_INTERVAL_MILLIS} without one, until {@code emit} throws on a\n")
                    .addJavadoc("disconnect.\n")
                    .addParameter(ParameterizedTypeName.get(KernelStreamScaffold.BLOCKING_QUEUE, STREAM_EVENT),
                            "queue")
                    .beginControlFlow(TRY)
                    .beginControlFlow("while (true)")
                    .addStatement("$T frame = queue.poll(KEEPALIVE_INTERVAL_MILLIS, $T.MILLISECONDS)",
                            STREAM_EVENT, KernelStreamScaffold.TIME_UNIT)
                    .beginControlFlow("if (frame == null)")
                    .addStatement(EMIT_KEEP_ALIVE, STREAM_EVENT, KernelStreamScaffold.KEEP_ALIVE_FRAME, "")
                    .nextControlFlow("else")
                    .addStatement("exchange.emit(frame)")
                    .endControlFlow()
                    .endControlFlow();
        } else {
            method.addJavadoc("Emits a {@code keep-alive} frame every {@code KEEPALIVE_INTERVAL_MILLIS} until\n")
                    .addJavadoc("{@code emit} throws on a disconnect.\n")
                    .beginControlFlow(TRY)
                    .beginControlFlow("while (true)")
                    .addStatement("$T.sleep(KEEPALIVE_INTERVAL_MILLIS)", KernelStreamScaffold.THREAD)
                    .addStatement(EMIT_KEEP_ALIVE, STREAM_EVENT, KernelStreamScaffold.KEEP_ALIVE_FRAME, "")
                    .endControlFlow();
        }
        return method.nextControlFlow(CATCH, INTERRUPTED_EXCEPTION)
                .addStatement("$T.currentThread().interrupt()", KernelStreamScaffold.THREAD)
                .endControlFlow()
                .build();
    }

    @Override
    public ArtifactType artifactType() {
        // Distinct from STREAM_HANDLER and ACTION_STREAM_HANDLER so the per-type registry lookup
        // resolves this generator unambiguously: all three emit HttpStreamHandler subtypes.
        return ArtifactType.SPECTATE_STREAM_HANDLER;
    }
}
