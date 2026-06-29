package eu.exeris.tooling.codegen.java.support;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Shared scaffold for the two kernel SSE stream-handler generators —
 * {@code KernelStreamHandlerGenerator} (entity-level live-view, ADR-043 Slice 1)
 * and {@code KernelActionStreamHandlerGenerator} (per-action, ADR-044 Slice 2).
 *
 * <p>This helper is the single home for the kernel streaming SPI {@link ClassName}s
 * and the two body shapes both generators draw from, so they don't copy-paste them
 * (CLAUDE.md strong-default #2 — "extract shared scaffold"; the 0.4.0 duplication
 * target):
 * <ul>
 *   <li>{@link #eventProducerScaffold(List)} — the <b>EV1 producer</b>: subscribe
 *       to the entity's {@code @DomainEvent} bus and project each event into a
 *       named SSE {@code StreamEvent}. This is the live-view body for an entity
 *       that declares domain events (Slice 1).</li>
 *   <li>{@link #keepAliveScaffold(List)} — the deterministic, finite keep-alive
 *       loop that stands in where there is no producer yet: an entity with
 *       {@code realTimeApi} but no {@code @DomainEvent} (the Slice 1 fallback),
 *       and the per-action handler (Slice 2), whose producer needs an SDK widening
 *       linking a streaming action to its event types.</li>
 * </ul>
 *
 * <p>Determinism (hard constraint #3): every value here is a compile-time
 * CONSTANT — no wall-clock, no random — so the same metadata yields byte-identical
 * output. Kernel-target discipline (hard constraint #1): both bodies stay on the
 * SPI ({@code emit}/{@code close}), emit no {@code text/event-stream} literal, and
 * let {@code StreamClosedException} from {@code emit} propagate.
 *
 * @author Exeris Team
 * @since 0.6.0
 */
public final class KernelStreamScaffold {

    private KernelStreamScaffold() {
    }

    public static final ClassName HTTP_STREAM_HANDLER =
            ClassName.get("eu.exeris.kernel.spi.http", "HttpStreamHandler");
    public static final ClassName HTTP_STREAM_EXCHANGE =
            ClassName.get("eu.exeris.kernel.spi.http", "HttpStreamExchange");
    public static final ClassName STREAM_EVENT =
            ClassName.get("eu.exeris.kernel.spi.http", "StreamEvent");
    public static final ClassName THREAD = ClassName.get("java.lang", "Thread");
    public static final ClassName SLF4J_LOGGER = ClassName.get("org.slf4j", "Logger");
    public static final ClassName SLF4J_LOGGER_FACTORY =
            ClassName.get("org.slf4j", "LoggerFactory");

    // --- EV1 producer SPI (ADR-043 stream + ADR-046 codec) ------------------
    /** {@code eu.exeris.kernel.spi.context.KernelProviders} — the ScopedValue
     *  accessor surface; {@code eventEngine().bus()} reaches the event bus. */
    public static final ClassName KERNEL_PROVIDERS =
            ClassName.get("eu.exeris.kernel.spi.context", "KernelProviders");
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
    public static final ClassName VALUE_LAYOUT =
            ClassName.get("java.lang.foreign", "ValueLayout");
    public static final ClassName STANDARD_CHARSETS =
            ClassName.get("java.nio.charset", "StandardCharsets");
    public static final ClassName BLOCKING_QUEUE =
            ClassName.get("java.util.concurrent", "BlockingQueue");
    public static final ClassName ARRAY_BLOCKING_QUEUE =
            ClassName.get("java.util.concurrent", "ArrayBlockingQueue");
    public static final ClassName LIST = ClassName.get("java.util", "List");
    public static final ClassName ARRAY_LIST = ClassName.get("java.util", "ArrayList");

    /**
     * Bounded hand-off capacity between the bus dispatch thread(s) and a stream's
     * own virtual thread. A compile-time CONSTANT (determinism, #3). Bounded — not
     * unbounded — so a slow spectator drops intermediate frames rather than growing
     * the heap (ADR-043 obligation 4: never an unbounded egress queue).
     */
    public static final int STREAM_BUFFER_CAPACITY = 256;

    /**
     * Deterministic keep-alive cadence. A compile-time CONSTANT — never a
     * wall-clock read — so the same metadata yields byte-identical output
     * (hard constraint #3).
     */
    public static final long KEEPALIVE_INTERVAL_MILLIS = 15_000L;

    /**
     * Bounded keep-alive iteration count for the scaffold loop. Deterministic and
     * finite so the generated handler terminates cleanly (calls {@code close()})
     * until the EV1 producer seam replaces the loop with a real subscription.
     */
    public static final int KEEPALIVE_ITERATIONS = 4;

    /**
     * The window (in seconds) the scaffold holds the stream open before closing —
     * {@code KEEPALIVE_ITERATIONS × KEEPALIVE_INTERVAL_MILLIS / 1000}. Used in the
     * generated handler's Javadoc.
     */
    public static long keepAliveWindowSeconds() {
        return (KEEPALIVE_ITERATIONS * KEEPALIVE_INTERVAL_MILLIS) / 1000;
    }

    /** The {@code LOG} field every stream handler carries. {@code selfType} is the
     *  generated class's own {@link ClassName} (for the
     *  {@code LoggerFactory.getLogger(X.class)} init). */
    public static FieldSpec loggerField(ClassName selfType) {
        return FieldSpec.builder(SLF4J_LOGGER, "LOG",
                        Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("$T.getLogger($T.class)", SLF4J_LOGGER_FACTORY, selfType)
                .build();
    }

    /**
     * The {@code LOG} field plus the two keep-alive constant fields the
     * keep-alive body ({@link #keepAliveScaffold(List)}) reads. {@code selfType}
     * is the generated class's own {@link ClassName}.
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
     * The {@code LOG} field plus the {@code STREAM_BUFFER_CAPACITY} constant the
     * producer body ({@link #eventProducerScaffold(List)}) reads. No keep-alive
     * constants — the producer never sleeps. {@code selfType} is the generated
     * class's own {@link ClassName}.
     */
    public static List<FieldSpec> producerFields(ClassName selfType) {
        return List.of(
                loggerField(selfType),
                FieldSpec.builder(TypeName.INT, "STREAM_BUFFER_CAPACITY",
                                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$L", STREAM_BUFFER_CAPACITY)
                        .addJavadoc("Bounded hand-off between the bus dispatch thread and this\n"
                                + "stream's virtual thread; drop-on-full keeps No-Waste-Compute.\n")
                        .build());
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
     *                 entity to {@link #keepAliveScaffold(List)} instead)
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
                .addStatement("$T bus = null", EVENT_BUS)
                .add("// Bounded hand-off: the subscribe callback runs on a bus dispatch\n")
                .add("// virtual thread, but emit(...) must run on THIS stream's VT (it\n")
                .add("// parks under back-pressure). The callback offers; this VT drains.\n")
                .addStatement("$T queue = new $T<>(STREAM_BUFFER_CAPACITY)", queueType, ARRAY_BLOCKING_QUEUE)
                .addStatement("$T tokens = new $T<>()", tokenListType, ARRAY_LIST)
                .beginControlFlow("try")
                .addStatement("bus = $T.eventEngine().bus()", KERNEL_PROVIDERS);

        for (StreamEventBinding b : bindings) {
            body.add("tokens.add(bus.subscribe($S, (descriptor, payload) -> {\n", b.subscribeName());
            body.indent();
            // Copy bytes to a String INSIDE try (payload): the off-heap segment is
            // invalid after close() on the Enterprise tier (RAII, ADR-046).
            body.beginControlFlow("try (payload)");
            body.addStatement("$T data = new $T(payload.segment().toArray($T.JAVA_BYTE), $T.UTF_8)",
                    ClassName.get(String.class), ClassName.get(String.class),
                    VALUE_LAYOUT, STANDARD_CHARSETS);
            body.beginControlFlow("if (!queue.offer($T.of($S, data)))", STREAM_EVENT, b.wireName());
            body.addStatement("LOG.debug($S)",
                    b.wireName() + " live-view frame dropped (slow consumer)");
            body.endControlFlow();
            body.endControlFlow();
            body.unindent();
            body.add("}));\n");
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
                .addStatement("LOG.debug($S)", "Live-view stream closed by peer")
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
     * The shared body of the stream handler's {@code handle(HttpStreamExchange)}
     * method, from the EV1 seam comment through the keep-alive loop to
     * {@code close()}. The caller emits its own {@code LOG.debug(...)} opener and
     * Javadoc before adding this block.
     *
     * <p>{@code heartbeatNote} is the driver-specific comment placed inside the
     * loop (the only prose that differs between Slice 1 and Slice 2 — how the
     * respective TS client treats the named frame); the structural code is
     * identical for both.
     *
     * @param heartbeatNote comment lines emitted inside the loop, above the
     *                      {@code emit(...)} call; never {@code null}
     */
    public static CodeBlock keepAliveScaffold(List<String> heartbeatNote) {
        CodeBlock.Builder body = CodeBlock.builder()
                .add("// Keep-alive fallback: no producer is bound for this handler — the\n")
                .add("// entity-level live view (Slice 1) routes to eventProducerScaffold(...)\n")
                .add("// once it declares a @DomainEvent; the per-action handler (Slice 2)\n")
                .add("// still awaits an SDK widening linking a streaming action to its\n")
                .add("// event types. Until then this emits a deterministic, finite\n")
                .add("// keep-alive so the handler compiles and runs end-to-end, holding\n")
                .add("// the emit/close contract (let StreamClosedException propagate).\n")
                .beginControlFlow("for (int i = 0; i < KEEPALIVE_ITERATIONS; i++)");
        for (String line : heartbeatNote) {
            body.add("// $L\n", line);
        }
        return body
                .addStatement("exchange.emit($T.of($S, $S))", STREAM_EVENT, "keep-alive", "")
                .beginControlFlow("try")
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
