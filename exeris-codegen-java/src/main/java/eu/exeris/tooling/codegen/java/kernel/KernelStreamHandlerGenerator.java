package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.java.support.KernelEventSupport;
import eu.exeris.tooling.codegen.java.support.KernelScaffold;
import eu.exeris.tooling.codegen.java.support.KernelStreamScaffold;
import eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Kernel Stream Handler Generator (SSE live-view, Slice 1).
 *
 * <p>Emits one {@code *StreamHandler} per entity annotated
 * {@code @ExerisDomain(realTimeApi = true)}. The generated class implements the
 * Open-Core streaming SPI introduced by kernel 0.10 (ADR-043):
 * <ul>
 *   <li>{@code eu.exeris.kernel.spi.http.HttpStreamHandler} — the
 *       {@code @FunctionalInterface} {@code void handle(HttpStreamExchange)}</li>
 *   <li>{@code eu.exeris.kernel.spi.http.HttpStreamExchange} —
 *       {@code request()} / {@code emit(StreamEvent)} / {@code close()}</li>
 *   <li>{@code eu.exeris.kernel.spi.http.StreamEvent} — implementation-blind
 *       wire event ({@code event:}/{@code data:}/{@code id:})</li>
 * </ul>
 *
 * <h2>Slice 1 shape (RFC-2026-06-22): entity-level live view</h2>
 * <p>Driver is the entity-level {@code @ExerisDomain(realTimeApi)} flag
 * (already plumbed into {@link DomainMetadata#realTimeApi()}); the per-action
 * {@code @Action(streaming)} driver is Slice 2, blocked on an SDK
 * {@code ActionMetadata} widening, and is deliberately not emitted here.
 *
 * <p>The emitted body depends on whether the entity declares any
 * {@code @DomainEvent}:
 * <ul>
 *   <li><b>EV1 producer</b> (the entity has {@code @DomainEvent}s) — the handler
 *       subscribes to each event on the kernel bus
 *       ({@code KernelProviders.eventEngine().bus()}) and projects it into a named
 *       {@code StreamEvent} ({@code event:} = the raw {@code @DomainEvent(name)},
 *       {@code data:} = the codec-encoded payload JSON the publisher already emits,
 *       ADR-046), draining onto the stream's virtual thread until the client
 *       disconnects, then dropping the subscriptions. This is the long-lived live
 *       view; see {@link KernelStreamScaffold#eventProducerScaffold(java.util.List)}.</li>
 *   <li><b>Keep-alive fallback</b> (no {@code @DomainEvent}) — nothing to project,
 *       so the handler runs a fixed-iteration keep-alive loop with a <b>constant</b>
 *       interval ({@code KEEPALIVE_INTERVAL_MILLIS}, no wall-clock / timestamp /
 *       random — hard constraint #3), then {@code close()}s after
 *       {@code KEEPALIVE_ITERATIONS * KEEPALIVE_INTERVAL_MILLIS} (~60s), and a
 *       browser {@code EventSource} auto-reconnects on that cadence.</li>
 * </ul>
 *
 * <p>The bus subscription key is the <em>normalised</em> {@code <Name>Event} name
 * the publisher registered the event under (an internal routing key, via
 * {@link eu.exeris.tooling.codegen.java.support.KernelEventSupport#eventName}); the
 * SSE {@code event:} wire-name is the <em>raw</em> event name — the vocabulary the
 * TS {@code EventHandler} discriminates on. The TS client's per-name
 * {@code addEventListener} wiring (the named frames the native {@code EventSource}
 * {@code onmessage} does not see) is the matching follow-up on the TS emitter.
 *
 * <h2>Kernel-target discipline (hard constraint #1)</h2>
 * <p>The handler stays on the SPI: no {@code text/event-stream} literal and no
 * chunk framing — Core's {@code SseEventEncoder} / {@code HttpStreamEngine} own
 * the wire format. Client disconnect surfaces as an unchecked
 * {@code StreamClosedException} from {@code emit(...)}; the loop lets it
 * propagate (the engine runs teardown) and never catch-and-swallows it.
 * Back-pressure parks the virtual thread inside {@code emit} — the producer's
 * one cross-VT hand-off is a <em>bounded</em> queue that drops frames when full
 * (No-Waste-Compute), never the banned unbounded heap egress queue.
 *
 * <p>The route is registered collection-level
 * ({@code GET {base}/stream}) by {@link KernelApplicationGenerator} via the
 * router's typed {@code streamRoute(method, path, handler)}, distinct from the
 * respond-once {@code route(...)}.
 *
 * @implNote Emission is JavaPoet-based (ADR-015), routed through
 * {@link KernelScaffold} like the other Java emitters.
 *
 * @see "docs/adr/ADR-043.link.md — cross-repo stub; kernel-side authoritative
 *      copy owns the streaming SPI."
 * @see "docs/rfc/RFC-2026-06-22 SSE Stream Emitter (tooling).md — Slice 1."
 *
 * @author Exeris Team
 * @since 0.6.0
 */
public class KernelStreamHandlerGenerator implements KernelArtifactGenerator {

    // Shared kernel-streaming SPI ClassNames + the deterministic keep-alive
    // scaffold (constants, fields, loop) live in KernelStreamScaffold so the
    // Slice 1 and Slice 2 stream generators don't copy-paste them.
    private static final ClassName HTTP_STREAM_HANDLER = KernelStreamScaffold.HTTP_STREAM_HANDLER;
    private static final ClassName HTTP_STREAM_EXCHANGE = KernelStreamScaffold.HTTP_STREAM_EXCHANGE;
    private static final ClassName STREAM_EVENT = KernelStreamScaffold.STREAM_EVENT;

    @Override
    public boolean supports(DomainMetadata metadata) {
        return metadata.realTimeApi();
    }

    @Override
    public GeneratedFile generate(DomainMetadata metadata) {
        // Defensive: registry already filters via supports(), but the
        // single-generator generate(...) path does not.
        if (!metadata.realTimeApi()) {
            return null;
        }

        String basePackage = metadata.packageName().replace(".domain", "");
        String packageName = basePackage + ".handler";
        String entity = metadata.entityName();
        String className = entity + "StreamHandler";
        String streamPath = metadata.effectivePath() + "/stream";

        ClassName selfType = ClassName.get(packageName, className);

        // EV1 producer when the entity declares @DomainEvent(s); otherwise the
        // deterministic keep-alive fallback (nothing to project). Bindings are in
        // declaration order — deterministic output (constraint #3).
        List<KernelStreamScaffold.StreamEventBinding> bindings = eventBindings(metadata, entity);
        boolean hasProducer = !bindings.isEmpty();

        TypeSpec.Builder streamHandler = KernelScaffold.publicClass(className)
                .addSuperinterface(HTTP_STREAM_HANDLER)
                .addJavadoc("Generated SSE live-view stream handler for $L.\n", entity)
                .addJavadoc("<p>Implements {@link $T}; registered at {@code GET $L}\n",
                        HTTP_STREAM_HANDLER, streamPath)
                .addJavadoc("via the router's {@code streamRoute(...)} (collection-level live view).\n");

        if (hasProducer) {
            streamHandler
                    .addJavadoc("<p>EV1 producer: subscribes to this entity's {@code @DomainEvent}s on\n")
                    .addJavadoc("the kernel bus and projects each into a named {@link $T}\n", STREAM_EVENT)
                    .addJavadoc("({@code event:} = the raw event name, {@code data:} = the codec-encoded\n")
                    .addJavadoc("payload JSON, ADR-046), until the client disconnects; see {@link #handle($T)}.\n",
                            HTTP_STREAM_EXCHANGE)
                    .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain model.\n")
                    .addFields(KernelStreamScaffold.producerFields(selfType))
                    .addMethod(buildProducerHandleMethod(entity, bindings));
        } else {
            streamHandler
                    .addJavadoc("<p>This entity declares no {@code @DomainEvent}, so there is nothing to\n")
                    .addJavadoc("project: the handler emits a deterministic keep-alive then closes.\n")
                    .addJavadoc("Closes after $L keep-alives (~$Ls); a browser EventSource auto-reconnects\n",
                            KernelStreamScaffold.KEEPALIVE_ITERATIONS,
                            KernelStreamScaffold.keepAliveWindowSeconds())
                    .addJavadoc("on that cadence. Declaring a {@code @DomainEvent} switches this handler\n")
                    .addJavadoc("to the EV1 producer (a long-lived bus subscription).\n")
                    .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain model.\n")
                    .addFields(KernelStreamScaffold.commonFields(selfType))
                    .addMethod(buildKeepAliveHandleMethod(entity));
        }

        return new GeneratedFile(packageName, className,
                KernelScaffold.render(packageName, streamHandler.build()), ArtifactType.STREAM_HANDLER);
    }

    /**
     * Resolves the entity's {@code @DomainEvent}s into stream bindings. Each
     * carries the publisher's normalised {@code <Name>Event} subscription key
     * (the bus routing key) and the raw event name (the SSE {@code event:}
     * wire-name); see {@link KernelStreamScaffold.StreamEventBinding}.
     */
    private static List<KernelStreamScaffold.StreamEventBinding> eventBindings(
            DomainMetadata metadata, String entity) {
        // Fail loudly (as the publisher does) if two @DomainEvents normalise to the
        // same <Name>Event key — this generator runs BEFORE KernelEventGenerator, so
        // without the guard it would silently emit duplicate bus.subscribe(...) calls
        // and the bus would deliver every event twice to the stream.
        KernelEventSupport.assertDistinctEventNames(metadata);
        List<KernelStreamScaffold.StreamEventBinding> bindings = new ArrayList<>();
        for (DomainEventMetadata event : metadata.events()) {
            String subscribeName = KernelEventSupport.eventName(event, entity);
            String raw = event.name();
            // Mirror the TS discriminator (raw @DomainEvent name); fall back to the
            // normalised name only when the author left the name blank, so the
            // wire frame is never an unnamed (empty event:) one.
            String wireName = (raw == null || raw.isBlank()) ? subscribeName : raw;
            bindings.add(new KernelStreamScaffold.StreamEventBinding(subscribeName, wireName));
        }
        return bindings;
    }

    private MethodSpec buildProducerHandleMethod(
            String entity, List<KernelStreamScaffold.StreamEventBinding> bindings) {
        return MethodSpec.methodBuilder("handle")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID)
                .addParameter(HTTP_STREAM_EXCHANGE, "exchange")
                .addJavadoc("Holds the SSE stream open and emits live-view events for $L.\n", entity)
                .addJavadoc("<p>Subscribes to this entity's {@code @DomainEvent}s on the kernel bus\n")
                .addJavadoc("and projects each into a named {@link $T}. Client disconnect\n", STREAM_EVENT)
                .addJavadoc("surfaces as an unchecked {@code StreamClosedException} from\n")
                .addJavadoc("{@link $T#emit($T)}; this method lets it propagate so the engine can\n",
                        HTTP_STREAM_EXCHANGE, STREAM_EVENT)
                .addJavadoc("run stream teardown — it is NOT caught and swallowed mid-stream.\n")
                .addJavadoc("Back-pressure parks the virtual thread inside {@code emit}; the bounded\n")
                .addJavadoc("hand-off queue drops frames for a slow consumer rather than growing the heap.\n")
                .addStatement("LOG.debug($S)", "Opening " + entity + " live-view stream")
                .addCode(KernelStreamScaffold.eventProducerScaffold(bindings))
                .build();
    }

    private MethodSpec buildKeepAliveHandleMethod(String entity) {
        return MethodSpec.methodBuilder("handle")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID)
                .addParameter(HTTP_STREAM_EXCHANGE, "exchange")
                .addJavadoc("Holds the SSE stream open and emits live-view events for $L.\n", entity)
                .addJavadoc("<p>Client disconnect surfaces as an unchecked\n")
                .addJavadoc("{@code StreamClosedException} from {@link $T#emit($T)}; this method\n",
                        HTTP_STREAM_EXCHANGE, STREAM_EVENT)
                .addJavadoc("lets it propagate so the engine can run stream teardown — it is NOT\n")
                .addJavadoc("caught and swallowed. Back-pressure parks the virtual thread inside\n")
                .addJavadoc("{@code emit}; this handler never buffers to a heap queue.\n")
                .addStatement("LOG.debug($S)", "Opening " + entity + " live-view stream")
                // Shared deterministic keep-alive scaffold (no producer for this
                // entity). Slice-1 heartbeat note: native EventSource ignores NAMED
                // frames, so the keep-alive is invisible to onmessage (fine — nothing
                // to process); EV1 named events reach the TS client via per-name
                // addEventListener once the entity declares a @DomainEvent.
                .addCode(KernelStreamScaffold.keepAliveScaffold(List.of(
                        "Named keep-alive heartbeat (deterministic name, empty data). The",
                        "browser EventSource.onmessage ignores named events — fine for a",
                        "heartbeat the client need not process; EV1 named domain events are",
                        "instead delivered via per-name addEventListener on the TS client.")))
                .build();
    }

    @Override
    public ArtifactType artifactType() {
        return ArtifactType.STREAM_HANDLER;
    }
}
