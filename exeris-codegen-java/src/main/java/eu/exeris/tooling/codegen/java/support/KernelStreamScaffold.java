package eu.exeris.tooling.codegen.java.support;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Shared scaffold for the three kernel SSE stream-handler generators —
 * {@code KernelStreamHandlerGenerator} (entity-level live-view, ADR-043 Slice 1),
 * {@code KernelActionStreamHandlerGenerator} (per-action, ADR-044 Slice 2) and
 * {@code KernelSpectateStreamHandlerGenerator} (one row's events, ADR-044 Amendment 2).
 *
 * <p>This helper is the single home for the kernel streaming SPI {@link ClassName}s,
 * the reserved frame names, and the body shapes the generators draw from, so they
 * don't copy-paste them (CLAUDE.md strong-default #2 — "extract shared scaffold"):
 * <ul>
 *   <li>{@link #eventProducerScaffold(List)} — the <b>EV1 producer</b>: subscribe
 *       to the entity's {@code @DomainEvent} bus and project each event into a
 *       named SSE {@code StreamEvent}. This is the live-view body for an entity
 *       that declares domain events (Slice 1).</li>
 *   <li>{@link #subscription(StreamEventBinding, CodeBlock, String, String)} — one
 *       bus subscription that forwards an event onto the bounded hand-off queue,
 *       shared by the entity-level producer, the per-action driver and the spectate
 *       handler.</li>
 *   <li>{@link #tenantGuard(String)}, {@link #pathId()}, {@link #rowLoad(ClassName, String)},
 *       {@link #rowHandOff()}, {@link #rowStreamIdGuard()} and {@link #refuseMethod(String)}
 *       — the steps of a stream that serves one row, and its {@code stream-error}
 *       refusal, shared by the per-action driver and the spectate handler.</li>
 *   <li>{@link #keepAliveScaffold(List, List)} — the deterministic, finite keep-alive
 *       loop of an entity with {@code realTimeApi} but no {@code @DomainEvent} (the
 *       Slice 1 fallback).</li>
 * </ul>
 *
 * <p>Determinism (hard constraint #3): every value here is a compile-time
 * CONSTANT — no wall-clock, no random — so the same metadata yields byte-identical
 * output. Kernel-target discipline (hard constraint #1): the bodies stay on the
 * SPI ({@code emit}/{@code close}), emit no {@code text/event-stream} literal, and
 * let {@code StreamClosedException} from {@code emit} propagate.
 *
 * @since 0.6
 */
public final class KernelStreamScaffold {

    /** {@code eu.exeris.kernel.spi.http.HttpStreamHandler} — the interface every emitted stream handler implements. */
    public static final ClassName HTTP_STREAM_HANDLER =
            ClassName.get("eu.exeris.kernel.spi.http", "HttpStreamHandler");
    /** {@code eu.exeris.kernel.spi.http.HttpStreamExchange} — the parameter of {@code handle}. */
    public static final ClassName HTTP_STREAM_EXCHANGE =
            ClassName.get("eu.exeris.kernel.spi.http", "HttpStreamExchange");
    /** {@code eu.exeris.kernel.spi.http.StreamEvent} — one SSE frame passed to {@code emit}. */
    public static final ClassName STREAM_EVENT =
            ClassName.get("eu.exeris.kernel.spi.http", "StreamEvent");
    /** {@code java.lang.Thread}, for restoring the interrupt flag. */
    public static final ClassName THREAD = ClassName.get("java.lang", "Thread");

    // --- EV1 producer SPI (ADR-043 stream + ADR-046 codec) ------------------
    /** {@code eu.exeris.kernel.spi.events.EventEngine} — handed to the producer
     *  handler's constructor by {@code RuntimeComponents}; {@code bus()} reaches the
     *  event bus. */
    public static final ClassName EVENT_ENGINE =
            ClassName.get("eu.exeris.kernel.spi.events", "EventEngine");
    /** {@code eu.exeris.kernel.spi.events.EventBus} — {@code subscribe(name, handler)}
     *  / {@code unsubscribe(token)}. */
    public static final ClassName EVENT_BUS =
            ClassName.get("eu.exeris.kernel.spi.events", "EventBus");
    /** {@code eu.exeris.kernel.spi.events.SubscriptionToken} — handed back by
     *  {@code subscribe}, fed to {@code unsubscribe} on teardown. */
    public static final ClassName SUBSCRIPTION_TOKEN =
            ClassName.get("eu.exeris.kernel.spi.events", "SubscriptionToken");
    /** {@code eu.exeris.kernel.spi.exceptions.http.StreamClosedException} — the
     *  unchecked disconnect signal thrown by {@code emit}; let it propagate. */
    public static final ClassName STREAM_CLOSED_EXCEPTION =
            ClassName.get("eu.exeris.kernel.spi.exceptions.http", "StreamClosedException");
    /** {@code java.lang.foreign.ValueLayout}, for copying a payload segment to a byte array. */
    public static final ClassName VALUE_LAYOUT =
            ClassName.get("java.lang.foreign", "ValueLayout");
    /** {@code java.nio.charset.StandardCharsets}, for decoding payload bytes as UTF-8. */
    public static final ClassName STANDARD_CHARSETS =
            ClassName.get("java.nio.charset", "StandardCharsets");
    /** {@code java.util.concurrent.BlockingQueue} — the declared type of the producer's hand-off queue. */
    public static final ClassName BLOCKING_QUEUE =
            ClassName.get("java.util.concurrent", "BlockingQueue");
    /** {@code java.util.concurrent.ArrayBlockingQueue} — the bounded implementation of that queue. */
    public static final ClassName ARRAY_BLOCKING_QUEUE =
            ClassName.get("java.util.concurrent", "ArrayBlockingQueue");
    /** {@code java.util.List}. */
    public static final ClassName LIST = ClassName.get("java.util", "List");
    /** {@code java.util.ArrayList}. */
    public static final ClassName ARRAY_LIST = ClassName.get("java.util", "ArrayList");
    /** {@code java.util.concurrent.TimeUnit}, for the timed {@code poll} of the hand-off queue. */
    public static final ClassName TIME_UNIT = ClassName.get(java.util.concurrent.TimeUnit.class);
    /** {@code eu.exeris.kernel.spi.context.KernelProviders}, whose {@code STORAGE_CONTEXT} the tenant guard reads. */
    public static final ClassName KERNEL_PROVIDERS =
            ClassName.get("eu.exeris.kernel.spi.context", "KernelProviders");

    /**
     * Bounded hand-off capacity between the bus dispatch thread(s) and a stream's
     * own virtual thread. A compile-time CONSTANT (determinism, #3). Bounded — not
     * unbounded — so a slow spectator drops intermediate frames rather than growing
     * the heap (ADR-043 obligation 4: never an unbounded egress queue).
     */
    public static final int STREAM_BUFFER_CAPACITY = 256;

    /**
     * How long a per-action stream waits, after its action has run, for the events the action
     * triggers. Measured on the monotonic clock by the emitted handler; the value is a
     * compile-time CONSTANT (determinism, #3), and an implementation detail rather than a
     * contract constant (ADR-044 Amendment 2, decision 1).
     */
    public static final long STREAM_DEADLINE_MILLIS = 30_000L;

    /**
     * The reserved frame name of a failure after the response head (ADR-044 Amendment 2,
     * decision 2). The processor refuses a {@code @DomainEvent} or a {@code streamEventType} of
     * this name.
     */
    public static final String STREAM_ERROR_FRAME = "stream-error";

    /**
     * The reserved frame name of the keep-alive heartbeat (ADR-044 obligation 2). The processor
     * refuses a {@code @DomainEvent} or a {@code streamEventType} of this name.
     */
    public static final String KEEP_ALIVE_FRAME = "keep-alive";

    /**
     * Deterministic keep-alive cadence. A compile-time CONSTANT — never a
     * wall-clock read — so the same metadata yields byte-identical output
     * (hard constraint #3).
     */
    public static final long KEEPALIVE_INTERVAL_MILLIS = 15_000L;

    /**
     * Bounded keep-alive iteration count for the scaffold loop. Deterministic and
     * finite so the generated handler terminates cleanly by calling {@code close()}.
     */
    public static final int KEEPALIVE_ITERATIONS = 4;

    private static final ClassName UUID = ClassName.get(java.util.UUID.class);
    private static final ClassName OPTIONAL = ClassName.get(java.util.Optional.class);
    private static final ClassName ILLEGAL_ARGUMENT_EXCEPTION = ClassName.get(IllegalArgumentException.class);
    private static final ClassName RUNTIME_EXCEPTION = ClassName.get(RuntimeException.class);

    /** Parameter name of the stream exchange on every emitted method. */
    private static final String EXCHANGE = "exchange";
    /** The emitted {@code try} opener. */
    private static final String TRY = "try";
    /** The emitted statement that refuses with a status and its reason phrase. */
    private static final String REFUSE_STATEMENT = "refuse(exchange, $L, $S)";
    /** The emitted {@code catch} clause opener. */
    private static final String CATCH = "catch ($T e)";
    private static final String RETURN = "return";
    private static final int BAD_REQUEST = 400;
    private static final int NOT_FOUND = 404;
    private static final int SERVER_ERROR = 500;
    private static final String SERVER_ERROR_TITLE = "Internal Server Error";

    private KernelStreamScaffold() {
    }

    /**
     * The window (in seconds) the scaffold holds the stream open before closing —
     * {@code KEEPALIVE_ITERATIONS × KEEPALIVE_INTERVAL_MILLIS / 1000}. Used in the
     * generated handler's Javadoc.
     *
     * @return the keep-alive window in whole seconds
     */
    public static long keepAliveWindowSeconds() {
        return (KEEPALIVE_ITERATIONS * KEEPALIVE_INTERVAL_MILLIS) / 1000;
    }

    /**
     * Returns the {@code LOG} field every stream handler carries — one shape for all generated
     * classes, see {@link KernelScaffold#loggerField(ClassName)}.
     *
     * @param selfType the generated class's own name
     * @return the logger field
     */
    public static FieldSpec loggerField(ClassName selfType) {
        return KernelScaffold.loggerField(selfType);
    }

    /**
     * The {@code LOG} field plus the two keep-alive constant fields the
     * keep-alive body ({@link #keepAliveScaffold(List, List)}) reads. {@code selfType}
     * is the generated class's own {@link ClassName}.
     *
     * @param selfType the generated class's own name
     * @return the logger field, then {@code KEEPALIVE_INTERVAL_MILLIS} and
     *         {@code KEEPALIVE_ITERATIONS}
     */
    public static List<FieldSpec> commonFields(ClassName selfType) {
        return List.of(
                loggerField(selfType),
                FieldSpec.builder(TypeName.LONG, "KEEPALIVE_INTERVAL_MILLIS",
                                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$LL", KEEPALIVE_INTERVAL_MILLIS)
                        .build(),
                FieldSpec.builder(TypeName.INT, "KEEPALIVE_ITERATIONS",
                                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$L", KEEPALIVE_ITERATIONS)
                        .build());
    }

    /**
     * The {@code LOG} field, the {@code STREAM_BUFFER_CAPACITY} constant and the
     * {@code eventEngine} field the producer body ({@link #eventProducerScaffold(List)})
     * reads. No keep-alive constants — the producer never sleeps. {@code selfType} is
     * the generated class's own {@link ClassName}; pair with
     * {@link #producerConstructor()}.
     *
     * @param selfType the generated class's own name
     * @return the logger field, then {@code STREAM_BUFFER_CAPACITY} and {@code eventEngine}
     */
    public static List<FieldSpec> producerFields(ClassName selfType) {
        return List.of(
                loggerField(selfType),
                FieldSpec.builder(TypeName.INT, "STREAM_BUFFER_CAPACITY",
                                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$L", STREAM_BUFFER_CAPACITY)
                        .addJavadoc("Bounded hand-off between the bus dispatch thread and this\n"
                                + "stream's virtual thread; drop-on-full keeps No-Waste-Compute.\n")
                        .build(),
                FieldSpec.builder(EVENT_ENGINE, "eventEngine", Modifier.PRIVATE, Modifier.FINAL)
                        .build());
    }

    /**
     * The producer handler's constructor: takes the {@code EventEngine} its body
     * subscribes on.
     *
     * <p>Constructor-injected rather than read from
     * {@code KernelProviders.eventEngine()} inside {@code handle}, because
     * {@code handle} runs on the stream's own thread, and the Community stream
     * dispatcher binds only the allocator and the request-body decoder registry
     * there — {@code EVENT_ENGINE} is a boot-scope binding, so the read would throw
     * {@code NoSuchElementException} after the response head had already been
     * written. {@code RuntimeComponents} resolves the engine inside the boot
     * callback, where it is bound — the same shape as the handler's
     * constructor-injected {@code MemoryAllocator}.
     *
     * @return a public constructor taking and storing the {@code EventEngine}
     */
    public static MethodSpec producerConstructor() {
        return MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(EVENT_ENGINE, "eventEngine")
                .addJavadoc("@param eventEngine the engine whose bus this handler subscribes on;\n")
                .addJavadoc("       captured at composition, never resolved on the stream thread\n")
                .addStatement("this.eventEngine = eventEngine")
                .build();
    }

    /**
     * One domain event to project onto the live-view stream.
     *
     * @param subscribeName the kernel event-type NAME the publisher registered
     *                      this event under (normalised {@code <Name>Event}) — the
     *                      key the bus routes on; MUST match the publisher.
     * @param wireName      the SSE {@code event:} frame name carried to the
     *                      browser — the raw {@code @DomainEvent(name)}, the
     *                      vocabulary the TS client discriminates on.
     */
    public record StreamEventBinding(String subscribeName, String wireName) {
    }

    /**
     * The binding of one {@code @DomainEvent}: the publisher's normalised {@code <Name>Event} key,
     * and the raw event name as the frame name — the normalised name when the raw one is blank,
     * so no frame is unnamed.
     *
     * @param event  the event
     * @param entity the entity's simple name
     * @return the event's subscription key and frame name
     */
    public static StreamEventBinding bindingOf(DomainEventMetadata event, String entity) {
        String subscribeName = KernelEventSupport.eventName(event, entity);
        String raw = event.name();
        return new StreamEventBinding(subscribeName, raw == null || raw.isBlank() ? subscribeName : raw);
    }

    /**
     * The EV1 producer body of {@code handle(HttpStreamExchange)}: subscribe to
     * each given domain event on the kernel bus and project it into a named SSE
     * {@code StreamEvent}, draining onto this stream's virtual thread until the
     * client disconnects. The caller emits its own {@code LOG.debug(...)} opener
     * and Javadoc before adding this block; {@link #producerFields(ClassName)}
     * supplies the fields it reads.
     *
     * <p>Shape (one VT per stream):
     * <ul>
     *   <li>The subscribe callback runs on a bus <em>dispatch</em> VT. It copies
     *       the payload bytes to a {@code String} <b>inside {@code try (payload)}</b>
     *       — the off-heap {@code MemorySegment} is invalid after {@code close()} on
     *       the Enterprise tier — and {@code offer}s a {@code StreamEvent} onto a
     *       bounded queue (drop-on-full; never blocks the dispatcher).</li>
     *   <li>The bus delivers raw codec-encoded bytes (ADR-046); on the Community
     *       JSON codec those bytes <em>are</em> the SSE {@code data:} field, so they
     *       pass through without a decode round-trip.</li>
     *   <li>The {@code handle} VT drains: {@code queue.take()} parks the VT until an
     *       event is queued, then {@code emit(...)} parks under back-pressure and
     *       throws {@code StreamClosedException} on disconnect — which the loop lets
     *       propagate (caught only to stop draining, never swallowed mid-stream).</li>
     *   <li>{@code finally} drops every subscription and {@code close()}s
     *       (idempotent — safe even after a disconnect).</li>
     * </ul>
     *
     * @param bindings the events to project, in deterministic declaration order;
     *                 never {@code null} or empty (the caller routes the no-event
     *                 entity to {@link #keepAliveScaffold(List, List)} instead)
     * @return the subscribe-and-drain block, through its {@code finally} teardown
     */
    public static CodeBlock eventProducerScaffold(List<StreamEventBinding> bindings) {
        // Variable types are parameterized; the `new` side uses the diamond
        // (raw ClassName + <>) so the emitted code reads idiomatically.
        TypeName queueType = ParameterizedTypeName.get(BLOCKING_QUEUE, STREAM_EVENT);
        TypeName tokenListType = ParameterizedTypeName.get(LIST, SUBSCRIPTION_TOKEN);

        CodeBlock.Builder body = CodeBlock.builder()
                .add("// EV1 producer: project this entity's @DomainEvent bus onto the\n")
                .add("// live-view SSE stream. The bus delivers raw codec-encoded bytes\n")
                .add("// (ADR-046); on the Community JSON codec those bytes ARE the\n")
                .add("// data: field, so they pass through without a decode round-trip.\n")
                .add("// bus is acquired INSIDE try so a failed acquisition still runs the\n")
                .add("// finally teardown (close()); the null guard keeps it self-contained.\n")
                .add("// The engine is the one captured at composition: this method runs on\n")
                .add("// the stream's own thread, where the kernel binds no EVENT_ENGINE.\n")
                .addStatement("$T bus = null", EVENT_BUS)
                .add("// Bounded hand-off: the subscribe callback runs on a bus dispatch\n")
                .add("// virtual thread, but emit(...) must run on THIS stream's VT (it\n")
                .add("// parks under back-pressure). The callback offers; this VT drains.\n")
                .addStatement("$T queue = new $T<>(STREAM_BUFFER_CAPACITY)", queueType, ARRAY_BLOCKING_QUEUE)
                .addStatement("$T tokens = new $T<>()", tokenListType, ARRAY_LIST)
                .beginControlFlow(TRY)
                .addStatement("bus = eventEngine.bus()");

        for (StreamEventBinding b : bindings) {
            body.add(subscription(b, null, "DEBUG", b.wireName() + " live-view frame dropped (slow consumer)"));
        }

        return body
                .beginControlFlow("while (true)")
                .add("// Drain on this stream VT: take() parks the VT until an event is\n")
                .add("// queued; emit(...) parks under back-pressure and throws\n")
                .add("// StreamClosedException on disconnect — let it propagate.\n")
                .addStatement("exchange.emit(queue.take())")
                .endControlFlow()
                .nextControlFlow("catch ($T closed)", STREAM_CLOSED_EXCEPTION)
                .add("// Normal termination: the peer disconnected or the stream closed.\n")
                .add("// The engine runs teardown; we stop draining (NOT swallowed\n")
                .add("// mid-stream — the loop exits and the method returns).\n")
                .addStatement("LOG.log($T.DEBUG, $S)", KernelScaffold.LOGGER_LEVEL, "Live-view stream closed by peer")
                .nextControlFlow("catch ($T interrupted)", InterruptedException.class)
                .addStatement("$T.currentThread().interrupt()", THREAD)
                .nextControlFlow("finally")
                .add("// Drop the subscriptions when the stream unwinds, then close\n")
                .add("// (idempotent — safe even after a disconnect). The null guard\n")
                .add("// covers a failed bus acquisition above (close() still runs).\n")
                .beginControlFlow("if (bus != null)")
                .beginControlFlow("for ($T token : tokens)", SUBSCRIPTION_TOKEN)
                .addStatement("bus.unsubscribe(token)")
                .endControlFlow()
                .endControlFlow()
                .addStatement("exchange.close()")
                .endControlFlow()
                .build();
    }

    /**
     * One {@code tokens.add(bus.subscribe(...))} statement: the callback copies the payload to a
     * {@code String} inside {@code try (payload)} and offers a named {@code StreamEvent} onto the
     * bounded {@code queue}, logging each frame it drops when the queue is full.
     *
     * <p>Shared by the entity-level producer and the per-action driver, so the two cannot copy a
     * payload, or drop a frame, differently. The payload bytes are copied inside
     * {@code try (payload)} because the off-heap segment is invalid after {@code close()} on the
     * Enterprise tier (ADR-046).
     *
     * @param binding     the event to subscribe to, and the frame name to forward it under
     * @param guard       {@code null} to forward every event of the type; otherwise a boolean
     *                    expression over the callback's {@code descriptor} that an event must
     *                    satisfy to be copied and forwarded
     * @param dropLevel   the {@code System.Logger.Level} constant name a dropped frame is logged at
     * @param dropMessage the log line of a dropped frame
     * @return the statement, through its closing {@code }));}
     */
    public static CodeBlock subscription(StreamEventBinding binding, CodeBlock guard,
                                         String dropLevel, String dropMessage) {
        CodeBlock.Builder body = CodeBlock.builder()
                .add("tokens.add(bus.subscribe($S, (descriptor, payload) -> {\n", binding.subscribeName())
                .indent()
                .beginControlFlow("try (payload)");
        if (guard != null) {
            body.beginControlFlow("if ($L)", guard);
        }
        body.addStatement("$T data = new $T(payload.segment().toArray($T.JAVA_BYTE), $T.UTF_8)",
                        ClassName.get(String.class), ClassName.get(String.class),
                        VALUE_LAYOUT, STANDARD_CHARSETS)
                .beginControlFlow("if (!queue.offer($T.of($S, data)))", STREAM_EVENT, binding.wireName())
                .addStatement("LOG.log($T.$L, $S)", KernelScaffold.LOGGER_LEVEL, dropLevel, dropMessage)
                .endControlFlow();
        if (guard != null) {
            body.endControlFlow();
        }
        return body.endControlFlow()
                .unindent()
                .add("}));\n")
                .build();
    }

    /**
     * The tenant guard of a stream on a tenant-partitioned entity: with no bound
     * {@code STORAGE_CONTEXT}, it logs {@code message} and refuses with {@code 500} before the
     * stream reads anything (ADR-044 Amendment 2, decision 6). Emitted into a method that has the
     * {@code exchange} parameter, a {@code LOG} field and a {@code refuse} helper
     * ({@link #refuseMethod(String)}), and returns {@code void}.
     *
     * @param message the log line that tells the operator what binds the context
     * @return the {@code if} block, through its {@code return}
     */
    public static CodeBlock tenantGuard(String message) {
        return CodeBlock.builder()
                .beginControlFlow("if (!$T.STORAGE_CONTEXT.isBound())", KERNEL_PROVIDERS)
                .addStatement("LOG.log($T.ERROR, $S)", KernelScaffold.LOGGER_LEVEL, message)
                .addStatement(REFUSE_STATEMENT, SERVER_ERROR, SERVER_ERROR_TITLE)
                .addStatement(RETURN)
                .endControlFlow()
                .build();
    }

    /**
     * Declares {@code UUID id} from the {@code id} path parameter, refusing a malformed one with
     * {@code 400}, the status the respond-once routes answer for it. Emitted into the same kind of
     * method as {@link #tenantGuard(String)}.
     *
     * @return the declaration and its {@code try}, through the refusal's {@code return}
     */
    public static CodeBlock pathId() {
        return CodeBlock.builder()
                .addStatement("$T id", UUID)
                .beginControlFlow(TRY)
                .addStatement("id = $T.fromString($L.pathParams().getOrDefault($S, $S))", UUID, EXCHANGE, "id", "")
                .nextControlFlow(CATCH, ILLEGAL_ARGUMENT_EXCEPTION)
                .addStatement(REFUSE_STATEMENT, BAD_REQUEST, "Bad Request")
                .addStatement(RETURN)
                .endControlFlow()
                .build();
    }

    /**
     * Declares {@code found}, the row {@code service.findById(id)} returns under row-level security,
     * and refuses an absent or invisible row with {@code 404} and a failed read with {@code 500}.
     * Follows {@link #pathId()}; the method's class has a {@code service} field.
     *
     * @param entityType         the entity class the service returns
     * @param loadFailureMessage the log line of a read that failed
     * @return the read, its failure branch and the {@code 404} branch
     */
    public static CodeBlock rowLoad(ClassName entityType, String loadFailureMessage) {
        return CodeBlock.builder()
                .addStatement("$T found", ParameterizedTypeName.get(OPTIONAL, entityType))
                .beginControlFlow(TRY)
                .addStatement("found = service.findById(id)")
                .nextControlFlow(CATCH, RUNTIME_EXCEPTION)
                .addStatement("LOG.log($T.ERROR, $S, e)", KernelScaffold.LOGGER_LEVEL, loadFailureMessage)
                .addStatement(REFUSE_STATEMENT, SERVER_ERROR, SERVER_ERROR_TITLE)
                .addStatement(RETURN)
                .endControlFlow()
                .add("// Absent, or invisible under row-level security: the same answer.\n")
                .beginControlFlow("if (found.isEmpty())")
                .addStatement(REFUSE_STATEMENT, NOT_FOUND, "Not Found")
                .addStatement(RETURN)
                .endControlFlow()
                .build();
    }

    /**
     * Declares the bounded hand-off {@code queue}, the subscription {@code tokens}, the two halves
     * of the row id the subscriptions filter on ({@code streamHigh}, {@code streamLow}), and the
     * {@code bus} of the constructor-captured {@code eventEngine}. Follows {@link #pathId()}; the
     * class has a {@code STREAM_BUFFER_CAPACITY} constant.
     *
     * @return the five declarations
     */
    public static CodeBlock rowHandOff() {
        return CodeBlock.builder()
                .addStatement("$T queue = new $T<>(STREAM_BUFFER_CAPACITY)",
                        ParameterizedTypeName.get(BLOCKING_QUEUE, STREAM_EVENT), ARRAY_BLOCKING_QUEUE)
                .addStatement("$T tokens = new $T<>()", ParameterizedTypeName.get(LIST, SUBSCRIPTION_TOKEN),
                        ARRAY_LIST)
                .addStatement("long streamHigh = id.getMostSignificantBits()")
                .addStatement("long streamLow = id.getLeastSignificantBits()")
                .addStatement("$T bus = eventEngine.bus()", EVENT_BUS)
                .build();
    }

    /**
     * The {@link #subscription} guard of a stream that serves one row: an event is forwarded only
     * when its descriptor's stream id is the row id, which every emitted publish call passes as the
     * stream id. Reads {@link #rowHandOff()}'s {@code streamHigh} and {@code streamLow}.
     *
     * @return the boolean expression over the callback's {@code descriptor}
     */
    public static CodeBlock rowStreamIdGuard() {
        return CodeBlock.of(
                "descriptor.streamIdHigh() == streamHigh && descriptor.streamIdLow() == streamLow");
    }

    /**
     * The {@code refuse(exchange, status, title)} helper: emits the reserved {@code stream-error}
     * frame, whose data is an RFC 9457 problem object (ADR-044 Amendment 2, decision 2). The class
     * has a {@code STREAM_ERROR} constant naming the frame.
     *
     * @param answeredBy the route whose status the frame carries, as the Javadoc names it
     * @return the private static helper
     */
    public static MethodSpec refuseMethod(String answeredBy) {
        return MethodSpec.methodBuilder("refuse")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .addJavadoc("Emits the reserved {@code stream-error} frame: an RFC 9457 problem object whose\n")
                .addJavadoc("{@code status} is the one $L answers.\n", answeredBy)
                .addParameter(HTTP_STREAM_EXCHANGE, EXCHANGE)
                .addParameter(TypeName.INT, "status")
                .addParameter(String.class, "title")
                .addStatement("exchange.emit($T.of(STREAM_ERROR, $S + title + $S + status + $S))", STREAM_EVENT,
                        "{\"type\":\"about:blank\",\"title\":\"", "\",\"status\":", "}")
                .build();
    }

    /**
     * The shared body of the stream handler's {@code handle(HttpStreamExchange)}
     * method, from the keep-alive comment through the loop to {@code close()}. The
     * caller emits its own {@code LOG.debug(...)} opener and Javadoc before adding
     * this block.
     *
     * <p>{@code reason} and {@code heartbeatNote} are the caller's prose: why this
     * handler has no producer, and how the TS client treats the named frame.
     *
     * @param reason        comment lines emitted above the loop, after the shared
     *                      opening line; never {@code null}
     * @param heartbeatNote comment lines emitted inside the loop, above the
     *                      {@code emit(...)} call; never {@code null}
     * @return the block from the keep-alive comment to {@code close()}
     */
    public static CodeBlock keepAliveScaffold(List<String> reason, List<String> heartbeatNote) {
        CodeBlock.Builder body = CodeBlock.builder()
                .add("// Keep-alive only: a deterministic, finite keep-alive, then close().\n");
        for (String line : reason) {
            body.add("// $L\n", line);
        }
        body.add("// The emit/close contract holds: StreamClosedException propagates.\n")
                .beginControlFlow("for (int i = 0; i < KEEPALIVE_ITERATIONS; i++)");
        for (String line : heartbeatNote) {
            body.add("// $L\n", line);
        }
        return body
                .addStatement("exchange.emit($T.of($S, $S))", STREAM_EVENT, KEEP_ALIVE_FRAME, "")
                .beginControlFlow(TRY)
                .addStatement("$T.sleep(KEEPALIVE_INTERVAL_MILLIS)", THREAD)
                .nextControlFlow("catch ($T e)", InterruptedException.class)
                .addStatement("$T.currentThread().interrupt()", THREAD)
                .addStatement("break")
                .endControlFlow()
                .endControlFlow()
                .addStatement("exchange.close()")
                .build();
    }
}
