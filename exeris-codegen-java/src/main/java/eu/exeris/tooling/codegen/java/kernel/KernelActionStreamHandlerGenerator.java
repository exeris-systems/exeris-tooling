package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.java.support.KernelScaffold;
import eu.exeris.tooling.codegen.java.support.KernelStreamScaffold;
import eu.exeris.tooling.codegen.java.support.NameCasing;
import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Kernel Action Stream Handler Generator (per-action SSE streaming, ADR-044 Slice 2).
 *
 * <p>The per-action twin of {@link KernelStreamHandlerGenerator}. Where the
 * entity-level driver ({@code @ExerisDomain(realTimeApi)}) emits one collection
 * live-view handler per entity, this generator emits one
 * {@code <Entity><ActionPascal>StreamHandler} per <b>streaming action</b> —
 * each {@link ActionMetadata} on the entity for which {@link ActionMetadata#streaming()}
 * is {@code true} (driver 1b). Both implement the same kernel 0.10 streaming SPI
 * (ADR-043):
 * <ul>
 *   <li>{@code eu.exeris.kernel.spi.http.HttpStreamHandler} — the
 *       {@code @FunctionalInterface} {@code void handle(HttpStreamExchange)}</li>
 *   <li>{@code eu.exeris.kernel.spi.http.HttpStreamExchange} —
 *       {@code request()} / {@code emit(StreamEvent)} / {@code close()}</li>
 *   <li>{@code eu.exeris.kernel.spi.http.StreamEvent} — implementation-blind
 *       wire event ({@code event:}/{@code data:}/{@code id:})</li>
 * </ul>
 *
 * <h2>Route (axis 3c)</h2>
 * <p>The handler is registered by {@link KernelApplicationGenerator} at
 * {@code POST {base}/{id}/actions/{kebab(name)}} via the router's typed
 * {@code streamRoute(...)} — the same path the respond-once action route would
 * use, but the request <b>opens the stream</b> instead of responding once. A
 * streaming action gets the {@code streamRoute} ONLY (never both a respond-once
 * {@code route(...)} and a stream route).
 *
 * <h2>Named event (ADR-044 obligation 2)</h2>
 * <p>The SSE {@code event:} name carried on each emitted frame is the action's
 * {@code @Action(streamEventType)} when present, else a deterministic default
 * (the action name). The keep-alive scaffold uses the reserved {@code keep-alive}
 * name with empty data.
 *
 * <h2>Producer seam (ADR-044 obligation 3) + determinism (constraint #3)</h2>
 * <p>Identical scaffold to Slice 1: a deterministic, finite keep-alive loop with
 * a <b>constant</b> interval (no wall-clock / timestamp / random) standing in
 * behind a clearly-marked EV1 seam ({@code // TODO: bind domain-event bus
 * producer (EV1)}). The loop is <b>replaced</b>, not reshaped, when EV1 lands.
 *
 * <h2>Kernel-target discipline (hard constraint #1)</h2>
 * <p>No {@code text/event-stream} literal, no chunk framing (Core's
 * {@code SseEventEncoder} / {@code HttpStreamEngine} own the wire). Client
 * disconnect surfaces as an unchecked {@code StreamClosedException} from
 * {@code emit(...)} and is let to propagate (never caught-and-swallowed); no heap
 * queue — back-pressure parks the virtual thread inside {@code emit}.
 *
 * @implNote Emission is JavaPoet-based (ADR-015), routed through
 * {@link KernelScaffold} like the other Java emitters. The driver is a
 * <em>collection</em> ({@code domain.actions()} filtered by {@code streaming()}),
 * so this generator overrides {@link #generateMultiple(DomainMetadata)} to emit
 * one file per streaming action; {@link #generate(DomainMetadata)} returns the
 * first (or {@code null}) only for the single-generator path.
 *
 * @see "docs/adr/ADR-044-tooling-sse-stream-emitter-shape.md — Slice 2."
 * @see KernelStreamHandlerGenerator
 *
 * @author Exeris Team
 * @since 0.6.0
 */
public class KernelActionStreamHandlerGenerator implements KernelArtifactGenerator {

    // Shared kernel-streaming SPI ClassNames + the deterministic keep-alive
    // scaffold (constants, fields, loop) live in KernelStreamScaffold so the
    // Slice 1 and Slice 2 stream generators don't copy-paste them.
    private static final ClassName HTTP_STREAM_HANDLER = KernelStreamScaffold.HTTP_STREAM_HANDLER;
    private static final ClassName HTTP_STREAM_EXCHANGE = KernelStreamScaffold.HTTP_STREAM_EXCHANGE;
    private static final ClassName STREAM_EVENT = KernelStreamScaffold.STREAM_EVENT;

    @Override
    public boolean supports(DomainMetadata metadata) {
        return metadata != null && hasStreamingAction(metadata);
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
        // Iterate in declared action order — deterministic output (constraint #3).
        for (ActionMetadata action : metadata.actions()) {
            if (action.streaming()) {
                files.add(buildActionStreamHandler(metadata, action));
            }
        }
        return files;
    }

    private GeneratedFile buildActionStreamHandler(DomainMetadata metadata, ActionMetadata action) {
        String basePackage = metadata.packageName().replace(".domain", "");
        String packageName = basePackage + ".handler";
        String entity = metadata.entityName();
        String actionPascal = NameCasing.pascal(action.name());
        String className = entity + actionPascal + "StreamHandler";
        String actionPath = metadata.effectivePath()
                + "/{id}/actions/" + NameCasing.kebab(action.name());

        // ADR-044 obligation 2: the SSE event: name is @Action.streamEventType
        // when present, else a deterministic default (the action name).
        String eventName = action.hasStreamEventType()
                ? action.streamEventType()
                : action.name();

        ClassName selfType = ClassName.get(packageName, className);

        TypeSpec streamHandler = KernelScaffold.publicClass(className)
                .addSuperinterface(HTTP_STREAM_HANDLER)
                .addJavadoc("Generated per-action SSE stream handler for $L.$L(...).\n", entity, action.name())
                .addJavadoc("<p>Implements {@link $T}; registered at {@code POST $L}\n",
                        HTTP_STREAM_HANDLER, actionPath)
                .addJavadoc("via the router's {@code streamRoute(...)} — the request opens the stream\n")
                .addJavadoc("(ADR-044 Slice 2, axis 3c). Each emitted frame carries the named SSE\n")
                .addJavadoc("event {@code $L}.\n", eventName)
                .addJavadoc("<p>Slice 2 scaffold: emits a deterministic keep-alive then closes.\n")
                .addJavadoc("Closes after $L keep-alives (~$Ls); the EV1 seam replaces the loop\n",
                        KernelStreamScaffold.KEEPALIVE_ITERATIONS,
                        KernelStreamScaffold.keepAliveWindowSeconds())
                .addJavadoc("with a long-lived {@code @DomainEvent} subscription projecting each\n")
                .addJavadoc("event into a {@link $T}.\n", STREAM_EVENT)
                .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain model.\n")
                .addFields(KernelStreamScaffold.commonFields(selfType))
                // Per-action extra: the named SSE event this handler emits (the
                // @Action.streamEventType, or the action name) — Slice-2 only.
                .addField(FieldSpec.builder(ClassName.get(String.class), "STREAM_EVENT_TYPE",
                                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$S", eventName)
                        .build())
                .addMethod(buildHandleMethod(entity, action, eventName))
                .build();

        return new GeneratedFile(packageName, className,
                KernelScaffold.render(packageName, streamHandler), ArtifactType.ACTION_STREAM_HANDLER);
    }

    private MethodSpec buildHandleMethod(String entity, ActionMetadata action, String eventName) {
        return MethodSpec.methodBuilder("handle")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID)
                .addParameter(HTTP_STREAM_EXCHANGE, "exchange")
                .addJavadoc("Opens the per-action SSE stream for $L.$L(...) and emits events.\n",
                        entity, action.name())
                .addJavadoc("<p>Client disconnect surfaces as an unchecked\n")
                .addJavadoc("{@code StreamClosedException} from {@link $T#emit($T)}; this method\n",
                        HTTP_STREAM_EXCHANGE, STREAM_EVENT)
                .addJavadoc("lets it propagate so the engine can run stream teardown — it is NOT\n")
                .addJavadoc("caught and swallowed. Back-pressure parks the virtual thread inside\n")
                .addJavadoc("{@code emit}; this handler never buffers to a heap queue.\n")
                .addStatement("LOG.debug($S)", "Opening " + entity + "." + action.name() + " action stream")
                // Shared deterministic keep-alive scaffold (EV1 seam + loop + close).
                // Slice-2 heartbeat note: the RxJS-over-fetch client parses NAMED SSE
                // frames, so it will dispatch the EV1 named event (STREAM_EVENT_TYPE)
                // once the seam replaces this loop.
                .addCode(KernelStreamScaffold.keepAliveScaffold(List.of(
                        "Named keep-alive heartbeat (deterministic name, empty data). The",
                        "RxJS client parses named SSE frames, so it can dispatch the EV1",
                        "named event (STREAM_EVENT_TYPE = \"" + eventName + "\") once the seam lands.")))
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
        // Distinct from KernelStreamHandlerGenerator's STREAM_HANDLER (Slice 1) so
        // the per-type registry lookup resolves THIS generator unambiguously — both
        // emit HttpStreamHandler subtypes, but they are different drivers.
        return ArtifactType.ACTION_STREAM_HANDLER;
    }
}
