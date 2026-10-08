package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.java.support.DataScopeSupport;
import eu.exeris.tooling.codegen.java.support.KernelScaffold;
import eu.exeris.tooling.codegen.java.support.KernelStreamScaffold;
import eu.exeris.tooling.codegen.java.support.KernelStreamScaffold.StreamEventBinding;
import eu.exeris.tooling.codegen.java.support.NameCasing;
import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Kernel Action Stream Handler Generator (per-action SSE streaming, ADR-044 Slice 2 and
 * Amendment 2).
 *
 * <p>Emits one {@code <Entity><ActionPascal>StreamHandler} per <b>streaming action</b> — each
 * {@link ActionMetadata} on the entity for which {@link ActionMetadata#streaming()} is
 * {@code true}. The handler implements the kernel streaming SPI (ADR-043):
 * {@code eu.exeris.kernel.spi.http.HttpStreamHandler}, handed an
 * {@code HttpStreamExchange} whose SSE response head ({@code 200}) the engine has written before
 * {@code handle} runs.
 *
 * <h2>Route</h2>
 * <p>Registered by {@link KernelApplicationGenerator} at
 * {@code POST {base}/{id}/actions/{kebab(name)}} via the router's {@code streamRoute(...)} — the
 * path a respond-once action uses; the request runs the action and opens the stream. A streaming
 * action gets the stream route only: {@link KernelHandlerGenerator} emits no respond-once method
 * for it.
 *
 * <h2>What the handler does, in order (Amendment 2, decisions 1 and 6)</h2>
 * <ol>
 *   <li>The tenant guard, on a tenant-partitioned entity: no bound {@code STORAGE_CONTEXT} is
 *       refused.</li>
 *   <li>The path {@code id}, then the {@code @ActionParam} body when the action declares one,
 *       decoded through {@code parseBody} as the respond-once route decodes it.</li>
 *   <li>{@code service.findById(id)} under row-level security; an absent or invisible row is
 *       refused.</li>
 *   <li>One bus subscription per {@code @DomainEvent} the action triggers (trigger
 *       {@code ACTION}, {@code actionName} naming the action), forwarding only events whose
 *       descriptor stream id equals the row id — the id every emitted publish call passes as the
 *       stream id.</li>
 *   <li>The action, as the respond-once route runs it: the entity method, then
 *       {@code service.update}, then the publish calls after the commit (ADR-075).</li>
 *   <li>Each forwarded event as a frame named by its {@code @DomainEvent} name, until every event
 *       of the triggered set has arrived or {@code STREAM_DEADLINE_MILLIS} has passed on the
 *       monotonic clock; then {@code close()}.</li>
 * </ol>
 * <p>The subscriptions are in place before the action runs, so no event it publishes can precede
 * them. An action that triggers no event subscribes to nothing and closes once it has run.
 *
 * <h2>Frames</h2>
 * <p>An event frame carries the {@code @DomainEvent} name and the codec-encoded payload. A failure
 * is the reserved {@code stream-error} frame, whose data is an RFC 9457 problem object whose
 * {@code status} is the one the respond-once action route answers for the same failure
 * (ADR-076, ADR-079): {@code 500} for the tenant guard and for a server-side fault, {@code 400}
 * for a malformed {@code id}, a body the caller got wrong, or a write naming another tenant,
 * {@code 404} for a row that is absent or invisible, {@code 409} for a version conflict on a
 * versioned entity. The handler emits no result frame: {@code @Action.streamEventType} names it,
 * and the generators do not know the action's return type (the {@code STREAM_EVENT_TYPE} constant
 * carries the name).
 *
 * <h2>Hand-off queue (Amendment 2, decision 5)</h2>
 * <p>The bus callback runs on a dispatch thread and {@code emit} must run on the stream's own, so
 * events cross through a bounded queue of {@code STREAM_BUFFER_CAPACITY} that drops on full and
 * logs each drop. A dropped event leaves the triggered set incomplete, so that stream closes at
 * its deadline.
 *
 * <h2>Kernel-target discipline</h2>
 * <p>No {@code text/event-stream} literal and no framing: Core owns the wire. A
 * {@code StreamClosedException} from {@code emit} propagates; {@code close()} runs in
 * {@code finally} and is idempotent.
 *
 * @implNote Emission is JavaPoet-based (ADR-015), routed through {@link KernelScaffold}; the
 *     subscription body is {@link KernelStreamScaffold#subscription}, shared with the entity-level
 *     producer. The driver is a collection ({@code domain.actions()} filtered by
 *     {@code streaming()}), so this generator overrides {@link #generateMultiple(DomainMetadata)}
 *     to emit one file per streaming action.
 *
 * @since 0.6
 * @see "docs/adr/ADR-044-tooling-sse-stream-emitter-shape.md — Slice 2, Amendment 2."
 * @see KernelStreamHandlerGenerator
 */
public class KernelActionStreamHandlerGenerator implements KernelArtifactGenerator {

    private static final ClassName HTTP_STREAM_HANDLER = KernelStreamScaffold.HTTP_STREAM_HANDLER;
    private static final ClassName HTTP_STREAM_EXCHANGE = KernelStreamScaffold.HTTP_STREAM_EXCHANGE;
    private static final ClassName STREAM_EVENT = KernelStreamScaffold.STREAM_EVENT;
    private static final ClassName MEMORY_ALLOCATOR =
            ClassName.get("eu.exeris.kernel.spi.memory", "MemoryAllocator");
    private static final ClassName UUID = ClassName.get("java.util", "UUID");
    private static final ClassName OBJECTS = ClassName.get("java.util", "Objects");
    private static final ClassName SET = ClassName.get("java.util", "Set");
    private static final ClassName HASH_SET = ClassName.get("java.util", "HashSet");
    private static final ClassName TIME_UNIT = KernelStreamScaffold.TIME_UNIT;
    private static final ClassName ILLEGAL_ARGUMENT_EXCEPTION =
            ClassName.get("java.lang", "IllegalArgumentException");
    private static final ClassName ILLEGAL_STATE_EXCEPTION =
            ClassName.get("java.lang", "IllegalStateException");
    private static final ClassName RUNTIME_EXCEPTION = ClassName.get("java.lang", "RuntimeException");
    private static final ClassName INTERRUPTED_EXCEPTION =
            ClassName.get("java.lang", "InterruptedException");

    /** Parameter name of the stream exchange on every emitted method. */
    private static final String EXCHANGE = "exchange";
    /** Name of the emitted refusal helper. */
    private static final String REFUSE = "refuse";
    /** Name of the emitted method that runs the action. */
    private static final String INVOKE = "invoke";
    /** Name of the publisher field and constructor parameter. */
    private static final String PUBLISHER = "publisher";
    /** Name of the event-engine field and constructor parameter. */
    private static final String EVENT_ENGINE = "eventEngine";
    /** Name of the allocator field and constructor parameter. */
    private static final String ALLOCATOR = "allocator";
    /** Name of the service field and constructor parameter. */
    private static final String SERVICE = "service";
    /** The emitted statement that refuses with a status and its reason phrase. */
    private static final String REFUSE_STATEMENT = REFUSE + "(exchange, $L, $S)";
    /** The emitted statement that logs at ERROR with a cause. */
    private static final String LOG_ERROR_CAUSE = "LOG.log($T.ERROR, $S, e)";
    /** The emitted {@code catch} clause opener. */
    private static final String CATCH = "catch ($T e)";
    private static final String RETURN = "return";
    private static final String RETURN_FALSE = "return false";

    private static final int BAD_REQUEST = 400;
    private static final int NOT_FOUND = 404;
    private static final int CONFLICT = 409;
    private static final int SERVER_ERROR = 500;
    private static final String BAD_REQUEST_TITLE = "Bad Request";
    private static final String NOT_FOUND_TITLE = "Not Found";
    private static final String CONFLICT_TITLE = "Conflict";
    private static final String SERVER_ERROR_TITLE = "Internal Server Error";

    /**
     * Creates the generator. It keeps no per-domain state, so one instance serves every domain
     * in a build.
     */
    public KernelActionStreamHandlerGenerator() {
        // no state to initialise
    }

    @Override
    public boolean supports(DomainMetadata metadata) {
        return metadata != null && hasStreamingAction(metadata);
    }

    /**
     * The {@code @DomainEvent}s a streaming action's handler subscribes to and publishes: those
     * whose trigger is {@code ACTION} and whose {@code actionName} names the action, in declaration
     * order.
     *
     * <p>Shared with {@link KernelApplicationGenerator}, which emits the matching
     * {@code RuntimeComponents} factory: a non-empty set adds the publisher and the
     * {@code EventEngine} to the handler's constructor, and one predicate keeps the two arities
     * equal.
     *
     * @param metadata the entity
     * @param action   one of its streaming actions
     * @return the triggered events; empty when the action triggers none
     */
    public static List<DomainEventMetadata> triggeredEvents(DomainMetadata metadata, ActionMetadata action) {
        return KernelHandlerGenerator.triggered(metadata, DomainEventMetadata.Trigger.ACTION, action.name());
    }

    /**
     * Whether a streaming action's handler decodes a request body, and therefore takes a
     * {@code MemoryAllocator} by constructor. Shared with {@link KernelApplicationGenerator} for
     * the reason {@link #triggeredEvents} is.
     *
     * @param action a streaming action
     * @return {@code true} when the action declares an {@code @ActionParam}
     */
    public static boolean decodesBody(ActionMetadata action) {
        return action.hasParams();
    }

    /**
     * Single-file path: returns the first streaming-action handler, or
     * {@code null}. The real entry point for this collection-driven generator is
     * {@link #generateMultiple(DomainMetadata)}.
     */
    @Override
    public GeneratedFile generate(DomainMetadata metadata) {
        List<GeneratedFile> files = generateMultiple(metadata);
        return files.isEmpty() ? null : files.get(0);
    }

    @Override
    public List<GeneratedFile> generateMultiple(DomainMetadata metadata) {
        List<GeneratedFile> files = new ArrayList<>();
        if (metadata == null) {
            return files;
        }
        // Declared action order: deterministic output (constraint #3).
        for (ActionMetadata action : metadata.actions()) {
            if (action.streaming()) {
                files.add(buildActionStreamHandler(metadata, action));
            }
        }
        return files;
    }

    /** The names, types and readers one handler is generated from. */
    private record Plan(DomainMetadata metadata, ActionMetadata action, ClassName selfType,
                        ClassName entityType, ClassName serviceType, ClassName requestType,
                        List<StreamEventBinding> bindings, boolean tenantPartitioned) {

        String entity() {
            return metadata.entityName();
        }

        String entityLower() {
            String entity = entity();
            return entity.isEmpty() ? entity
                    : entity.substring(0, 1).toLowerCase(Locale.ROOT) + entity.substring(1);
        }

        String qualifiedAction() {
            return entity() + "." + action.name();
        }

        boolean subscribes() {
            return !bindings.isEmpty();
        }
    }

    private GeneratedFile buildActionStreamHandler(DomainMetadata metadata, ActionMetadata action) {
        String basePackage = metadata.packageName().replace(".domain", "");
        String packageName = basePackage + ".handler";
        String entity = metadata.entityName();
        String className = entity + NameCasing.pascal(action.name()) + "StreamHandler";
        String actionPath = metadata.effectivePath() + "/{id}/actions/" + NameCasing.kebab(action.name());
        // ADR-044 obligation 2 and Amendment 2 decision 3: the result frame's name is
        // @Action.streamEventType when present, else the action name.
        String resultFrame = action.hasStreamEventType() ? action.streamEventType() : action.name();

        ClassName selfType = ClassName.get(packageName, className);
        ClassName requestType = decodesBody(action)
                ? selfType.nestedClass(KernelHandlerGenerator.actionRequestName(action))
                : null;
        Plan plan = new Plan(metadata, action, selfType,
                ClassName.get(metadata.packageName(), entity),
                ClassName.get(basePackage + ".service", entity + "Service"),
                requestType, bindings(metadata, action),
                DataScopeSupport.isTenantPartitioned(metadata));

        TypeSpec.Builder handler = KernelScaffold.publicClass(className)
                .addSuperinterface(HTTP_STREAM_HANDLER)
                .addJavadoc("Generated per-action SSE stream handler for $L(...).\n", plan.qualifiedAction())
                .addJavadoc("<p>Implements {@link $T}; registered at {@code POST $L}\n",
                        HTTP_STREAM_HANDLER, actionPath)
                .addJavadoc("via the router's {@code streamRoute(...)}. The request runs the action and\n")
                .addJavadoc("opens the stream: see {@link #handle($T)} for the order of the steps.\n",
                        HTTP_STREAM_EXCHANGE)
                .addJavadoc("<p>The engine writes the {@code 200} response head before {@link #handle($T)}\n",
                        HTTP_STREAM_EXCHANGE)
                .addJavadoc("runs, so every refusal is a {@code stream-error} frame whose data is a problem\n")
                .addJavadoc("object carrying the status the respond-once action route answers.\n");
        if (plan.subscribes()) {
            handler.addJavadoc("<p><b>Interleaving.</b> Events are filtered on the row id, the stream id every\n")
                    .addJavadoc("publish of this entity carries. Two concurrent invocations on the same row\n")
                    .addJavadoc("publish under the same stream id, and an event descriptor carries no\n")
                    .addJavadoc("correlation id, so each of their streams can receive the other's event frames,\n")
                    .addJavadoc("and an event of one can complete the other.\n");
        }
        handler.addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain model.\n")
                .addFields(fields(plan, resultFrame))
                .addMethod(constructor(plan))
                .addMethod(handleMethod(plan))
                .addMethod(serveMethod(plan))
                .addMethod(invokeMethod(plan));
        if (plan.subscribes()) {
            handler.addMethod(awaitEventsMethod(plan));
        }
        handler.addMethod(KernelStreamScaffold.refuseMethod("the respond-once action route"));
        if (decodesBody(action)) {
            handler.addMethod(KernelHandlerGenerator.parseBodyMethod(HTTP_STREAM_EXCHANGE))
                    .addType(KernelHandlerGenerator.buildActionRequestRecord(action));
        }

        return new GeneratedFile(packageName, className,
                KernelScaffold.render(packageName, handler.build()), ArtifactType.ACTION_STREAM_HANDLER);
    }

    /**
     * The subscription bindings of the action's triggered events: the bus key the publisher
     * registers each under, and its {@code @DomainEvent} name as the frame name.
     */
    private static List<StreamEventBinding> bindings(DomainMetadata metadata, ActionMetadata action) {
        List<StreamEventBinding> bindings = new ArrayList<>();
        for (DomainEventMetadata event : triggeredEvents(metadata, action)) {
            bindings.add(KernelStreamScaffold.bindingOf(event, metadata.entityName()));
        }
        return bindings;
    }

    private static List<FieldSpec> fields(Plan plan, String resultFrame) {
        List<FieldSpec> fields = new ArrayList<>();
        fields.add(KernelStreamScaffold.loggerField(plan.selfType()));
        fields.add(FieldSpec.builder(String.class, "STREAM_EVENT_TYPE",
                        Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("$S", resultFrame)
                .addJavadoc("The name of this action's result frame ({@code @Action.streamEventType}, or the\n"
                        + "action name). No frame of this name is emitted: the result frame carries the\n"
                        + "action's return value, and the generators do not know its type.\n")
                .build());
        fields.add(FieldSpec.builder(String.class, "STREAM_ERROR",
                        Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("$S", KernelStreamScaffold.STREAM_ERROR_FRAME)
                .addJavadoc("The reserved frame name of a refusal after the response head.\n")
                .build());
        if (plan.subscribes()) {
            fields.add(FieldSpec.builder(TypeName.INT, "STREAM_BUFFER_CAPACITY",
                            Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer("$L", KernelStreamScaffold.STREAM_BUFFER_CAPACITY)
                    .addJavadoc("Bounded hand-off between the bus dispatch thread and this stream's thread;\n"
                            + "a full queue drops the frame and logs it.\n")
                    .build());
            fields.add(FieldSpec.builder(TypeName.LONG, "STREAM_DEADLINE_MILLIS",
                            Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer("$LL", KernelStreamScaffold.STREAM_DEADLINE_MILLIS)
                    .addJavadoc("How long the stream waits, after the action has run, for its events.\n")
                    .build());
            CodeBlock names = plan.bindings().stream()
                    .map(b -> CodeBlock.of("$S", b.wireName()))
                    .collect(CodeBlock.joining(", "));
            fields.add(FieldSpec.builder(ParameterizedTypeName.get(KernelStreamScaffold.LIST,
                                    ClassName.get(String.class)), "TRIGGERED_EVENTS",
                            Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer("$T.of($L)", KernelStreamScaffold.LIST, names)
                    .addJavadoc("The frame names of the events this action triggers; the stream closes once\n"
                            + "each has arrived.\n")
                    .build());
        }
        fields.add(FieldSpec.builder(plan.serviceType(), SERVICE, Modifier.PRIVATE, Modifier.FINAL).build());
        if (decodesBody(plan.action())) {
            fields.add(FieldSpec.builder(MEMORY_ALLOCATOR, ALLOCATOR, Modifier.PRIVATE, Modifier.FINAL).build());
        }
        if (plan.subscribes()) {
            fields.add(FieldSpec.builder(publisherType(plan.metadata()), PUBLISHER,
                    Modifier.PRIVATE, Modifier.FINAL).build());
            fields.add(FieldSpec.builder(KernelStreamScaffold.EVENT_ENGINE, EVENT_ENGINE,
                    Modifier.PRIVATE, Modifier.FINAL).build());
        }
        return fields;
    }

    private static ClassName publisherType(DomainMetadata metadata) {
        return ClassName.get(metadata.packageName().replace(".domain", ".event"),
                metadata.entityName() + "EventPublisher");
    }

    private static MethodSpec constructor(Plan plan) {
        MethodSpec.Builder constructor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addJavadoc("Every argument is captured at composition: {@link #handle($T)} runs on the\n",
                        HTTP_STREAM_EXCHANGE)
                .addJavadoc("stream's own thread, where the kernel binds the allocator and the decoder\n")
                .addJavadoc("registry and no engine.\n")
                .addJavadoc("@param service the entity's service, which loads and writes the row\n")
                .addParameter(plan.serviceType(), SERVICE)
                .addStatement("this.$L = $L", SERVICE, SERVICE);
        if (decodesBody(plan.action())) {
            constructor.addJavadoc("@param allocator the allocator the body decoder is handed\n")
                    .addParameter(MEMORY_ALLOCATOR, ALLOCATOR)
                    .addStatement("this.$L = $T.requireNonNull($L, $S)", ALLOCATOR, OBJECTS, ALLOCATOR,
                            "allocator must not be null — RuntimeComponents captures it from "
                                    + "KernelProviders.MEMORY_ALLOCATOR inside the bootstrap callback");
        }
        if (plan.subscribes()) {
            constructor.addJavadoc("@param publisher the publisher of the events this action triggers\n")
                    .addJavadoc("@param eventEngine the engine whose bus this handler subscribes on\n")
                    .addParameter(publisherType(plan.metadata()), PUBLISHER)
                    .addParameter(KernelStreamScaffold.EVENT_ENGINE, EVENT_ENGINE)
                    .addStatement("this.$L = $L", PUBLISHER, PUBLISHER)
                    .addStatement("this.$L = $L", EVENT_ENGINE, EVENT_ENGINE);
        }
        return constructor.build();
    }

    private static MethodSpec handleMethod(Plan plan) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("handle")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID)
                .addParameter(HTTP_STREAM_EXCHANGE, EXCHANGE)
                .addJavadoc("Runs {@code $L(...)} and streams what it publishes, in this order:\n",
                        plan.qualifiedAction())
                .addJavadoc("<ol>\n");
        if (plan.tenantPartitioned()) {
            method.addJavadoc("<li>the tenant guard: no bound {@code STORAGE_CONTEXT} is refused;</li>\n");
        }
        method.addJavadoc("<li>the path {@code id}$L;</li>\n",
                        decodesBody(plan.action()) ? ", then the request body" : "")
                .addJavadoc("<li>the row, read under row-level security;</li>\n");
        if (plan.subscribes()) {
            method.addJavadoc("<li>a subscription to each triggered event, filtered on the row id;</li>\n");
        }
        method.addJavadoc("<li>the action: the entity method, {@code service.update}, then the publish\n")
                .addJavadoc("calls;</li>\n");
        if (plan.subscribes()) {
            method.addJavadoc("<li>each forwarded event as a frame, until every triggered event has arrived\n")
                    .addJavadoc("or {@code STREAM_DEADLINE_MILLIS} has passed.</li>\n");
        }
        return method.addJavadoc("</ol>\n")
                .addJavadoc("<p>A refusal is one {@code stream-error} frame, and so is any other failure:\n")
                .addJavadoc("{@code 500}. The stream is closed when this returns; a\n")
                .addJavadoc("{@code StreamClosedException} from {@code emit} propagates.\n")
                .addStatement("LOG.log($T.DEBUG, $S)", KernelScaffold.LOGGER_LEVEL,
                        "Opening " + plan.qualifiedAction() + " action stream")
                .beginControlFlow("try")
                .addStatement("serve(exchange)")
                .nextControlFlow("catch ($T closed)", KernelStreamScaffold.STREAM_CLOSED_EXCEPTION)
                .addStatement("throw closed")
                .nextControlFlow(CATCH, RUNTIME_EXCEPTION)
                .addStatement(LOG_ERROR_CAUSE, KernelScaffold.LOGGER_LEVEL,
                        plan.qualifiedAction() + " action stream failed")
                .addStatement(REFUSE_STATEMENT, SERVER_ERROR, SERVER_ERROR_TITLE)
                .nextControlFlow("finally")
                .addStatement("exchange.close()")
                .endControlFlow()
                .build();
    }

    private static MethodSpec serveMethod(Plan plan) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("serve")
                .addModifiers(Modifier.PRIVATE)
                .addParameter(HTTP_STREAM_EXCHANGE, EXCHANGE);
        if (plan.tenantPartitioned()) {
            method.addCode(KernelStreamScaffold.tenantGuard(
                    KernelHandlerGenerator.tenantUnboundMessage(plan.entityLower())));
        }
        method.addCode(KernelStreamScaffold.pathId());
        if (decodesBody(plan.action())) {
            method.addStatement("$T request", plan.requestType())
                    .beginControlFlow("try")
                    .addStatement("request = parseBody(exchange, $T.class)", plan.requestType())
                    .nextControlFlow(CATCH, ILLEGAL_ARGUMENT_EXCEPTION)
                    .addStatement(REFUSE_STATEMENT, BAD_REQUEST, BAD_REQUEST_TITLE)
                    .addStatement(RETURN)
                    .nextControlFlow(CATCH, ILLEGAL_STATE_EXCEPTION)
                    .addStatement(LOG_ERROR_CAUSE, KernelScaffold.LOGGER_LEVEL,
                            KernelHandlerGenerator.decoderUnavailableMessage(plan.entityLower()))
                    .addStatement(REFUSE_STATEMENT, SERVER_ERROR, SERVER_ERROR_TITLE)
                    .addStatement(RETURN)
                    .nextControlFlow(CATCH, RUNTIME_EXCEPTION)
                    .addStatement(LOG_ERROR_CAUSE, KernelScaffold.LOGGER_LEVEL,
                            KernelHandlerGenerator.decodeFailedMessage(plan.entityLower()))
                    .addStatement(REFUSE_STATEMENT, SERVER_ERROR, SERVER_ERROR_TITLE)
                    .addStatement(RETURN)
                    .endControlFlow();
        }
        method.addCode(KernelStreamScaffold.rowLoad(plan.entityType(),
                "Failed to load " + plan.entityLower() + " for action " + plan.action().name()));
        String invokeCall = decodesBody(plan.action())
                ? INVOKE + "(exchange, id, found.get(), request)"
                : INVOKE + "(exchange, id, found.get())";
        if (!plan.subscribes()) {
            return method.addStatement(invokeCall).build();
        }
        method.addComment("Subscribe before the action runs, so no event it publishes can precede the")
                .addComment("subscription. Only events published under this row's id are forwarded.")
                .addCode(KernelStreamScaffold.rowHandOff())
                .beginControlFlow("try");
        CodeBlock guard = KernelStreamScaffold.rowStreamIdGuard();
        for (StreamEventBinding binding : plan.bindings()) {
            method.addCode(KernelStreamScaffold.subscription(binding, guard, "WARNING",
                    binding.wireName() + " frame dropped on the " + plan.qualifiedAction()
                            + " action stream (slow consumer); the stream closes at its deadline"));
        }
        return method.beginControlFlow("if ($L)", invokeCall)
                .addStatement("awaitEvents(exchange, queue)")
                .endControlFlow()
                .nextControlFlow("finally")
                .beginControlFlow("for ($T token : tokens)", KernelStreamScaffold.SUBSCRIPTION_TOKEN)
                .addStatement("bus.unsubscribe(token)")
                .endControlFlow()
                .endControlFlow()
                .build();
    }

    /**
     * The action, as {@link KernelHandlerGenerator}'s respond-once action method runs it, with each
     * {@code respond(status)} replaced by a {@code stream-error} frame of the same status.
     */
    private static MethodSpec invokeMethod(Plan plan) {
        DomainMetadata metadata = plan.metadata();
        ActionMetadata action = plan.action();
        MethodSpec.Builder method = MethodSpec.methodBuilder(INVOKE)
                .addModifiers(Modifier.PRIVATE)
                .returns(TypeName.BOOLEAN)
                .addJavadoc("Runs the action on {@code entity}, persists it and publishes its events.\n")
                .addJavadoc("@return {@code true} when the write committed; {@code false} after a refusal\n")
                .addParameter(HTTP_STREAM_EXCHANGE, EXCHANGE)
                .addParameter(UUID, "id")
                .addParameter(plan.entityType(), "entity");
        if (decodesBody(action)) {
            method.addParameter(plan.requestType(), "request");
            String args = action.params().stream()
                    .map(p -> "request." + p.name() + "()")
                    .collect(Collectors.joining(", "));
            method.beginControlFlow("try")
                    .addStatement("entity.$L($L)", action.effectiveMethodName(), args);
        } else {
            method.beginControlFlow("try")
                    .addStatement("entity.$L()", action.effectiveMethodName());
        }
        boolean needsAggregate = triggeredEvents(metadata, action).stream()
                .anyMatch(event -> !KernelEventGenerator.payloadFields(event, metadata).isEmpty());
        if (needsAggregate) {
            method.addStatement("$T updated = service.update(id, entity)", plan.entityType());
        } else {
            method.addStatement("service.update(id, entity)");
        }
        KernelHandlerGenerator.appendPublishCalls(method, metadata, DomainEventMetadata.Trigger.ACTION,
                action.name(), "id", needsAggregate ? "updated" : null);
        method.addStatement("return true");
        ClassName conflict = KernelErrorGenerator.versionConflictType(metadata);
        if (conflict != null) {
            method.nextControlFlow(CATCH, conflict)
                    .addStatement(REFUSE_STATEMENT, CONFLICT, CONFLICT_TITLE);
        } else {
            method.nextControlFlow(CATCH, KernelErrorGenerator.notFoundType(metadata))
                    .addStatement(REFUSE_STATEMENT, NOT_FOUND, NOT_FOUND_TITLE);
        }
        ClassName tenantMismatch = KernelErrorGenerator.tenantMismatchType(metadata);
        if (tenantMismatch != null) {
            ClassName sharedScopeMismatch = KernelErrorGenerator.sharedScopeMismatchType(metadata);
            if (sharedScopeMismatch == null) {
                method.nextControlFlow(CATCH, tenantMismatch);
            } else {
                method.nextControlFlow("catch ($T | $T e)", tenantMismatch, sharedScopeMismatch);
            }
            method.addStatement(REFUSE_STATEMENT, BAD_REQUEST, BAD_REQUEST_TITLE);
        }
        return method.nextControlFlow(CATCH, RUNTIME_EXCEPTION)
                .addStatement(LOG_ERROR_CAUSE, KernelScaffold.LOGGER_LEVEL,
                        "Failed to execute action " + action.name() + " on " + plan.entityLower())
                .addStatement(REFUSE_STATEMENT, SERVER_ERROR, SERVER_ERROR_TITLE)
                .endControlFlow()
                .addStatement(RETURN_FALSE)
                .build();
    }

    private static MethodSpec awaitEventsMethod(Plan plan) {
        TypeName queueType = ParameterizedTypeName.get(KernelStreamScaffold.BLOCKING_QUEUE, STREAM_EVENT);
        TypeName pendingType = ParameterizedTypeName.get(SET, ClassName.get(String.class));
        return MethodSpec.methodBuilder("awaitEvents")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .addJavadoc("Emits each forwarded event until every triggered event has arrived, or until\n")
                .addJavadoc("{@code STREAM_DEADLINE_MILLIS} has passed on the monotonic clock.\n")
                .addParameter(HTTP_STREAM_EXCHANGE, EXCHANGE)
                .addParameter(queueType, "queue")
                .addStatement("$T pending = new $T<>(TRIGGERED_EVENTS)", pendingType, HASH_SET)
                .addStatement("long deadline = $T.nanoTime() + $T.MILLISECONDS.toNanos(STREAM_DEADLINE_MILLIS)",
                        ClassName.get(System.class), TIME_UNIT)
                .beginControlFlow("try")
                .beginControlFlow("while (!pending.isEmpty())")
                .addStatement("long wait = deadline - $T.nanoTime()", ClassName.get(System.class))
                .addStatement("$T frame = wait > 0 ? queue.poll(wait, $T.NANOSECONDS) : null",
                        STREAM_EVENT, TIME_UNIT)
                .beginControlFlow("if (frame == null)")
                .addStatement("LOG.log($T.DEBUG, $S)", KernelScaffold.LOGGER_LEVEL,
                        plan.qualifiedAction() + " action stream reached its deadline before every "
                                + "triggered event arrived")
                .addStatement(RETURN)
                .endControlFlow()
                .addStatement("exchange.emit(frame)")
                .addStatement("pending.remove(frame.event())")
                .endControlFlow()
                .nextControlFlow(CATCH, INTERRUPTED_EXCEPTION)
                .addStatement("$T.currentThread().interrupt()", KernelStreamScaffold.THREAD)
                .endControlFlow()
                .build();
    }

    private boolean hasStreamingAction(DomainMetadata metadata) {
        for (ActionMetadata action : metadata.actions()) {
            if (action.streaming()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public ArtifactType artifactType() {
        // Distinct from KernelStreamHandlerGenerator's STREAM_HANDLER so the per-type registry
        // lookup resolves this generator unambiguously: both emit HttpStreamHandler subtypes,
        // for different drivers.
        return ArtifactType.ACTION_STREAM_HANDLER;
    }
}
