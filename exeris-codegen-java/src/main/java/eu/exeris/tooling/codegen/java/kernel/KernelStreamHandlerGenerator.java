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
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;

import javax.lang.model.element.Modifier;

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
 * <h2>Slice 1 shape (RFC-2026-06-22): entity-level scaffold</h2>
 * <p>Driver is the entity-level {@code @ExerisDomain(realTimeApi)} flag
 * (already plumbed into {@link DomainMetadata#realTimeApi()}); the per-action
 * {@code @Action(streaming)} driver is Slice 2, blocked on an SDK
 * {@code ActionMetadata} widening, and is deliberately not emitted here.
 *
 * <p>The emitted body is a <b>deterministic scaffold</b>: it runs a
 * fixed-iteration keep-alive loop with a <b>constant</b> interval
 * ({@code KEEPALIVE_INTERVAL_MILLIS}, no wall-clock / timestamp / random — hard
 * constraint #3) so the handler compiles and runs end-to-end before EV1 domain
 * event payloads are rich, then calls {@code close()}. The real producer is a
 * clearly-marked seam ({@code // TODO: bind domain-event bus producer (EV1)})
 * to project the entity's {@code @DomainEvent}s into {@code StreamEvent}s; that
 * lands without reshaping the route or the TS client.
 *
 * <h2>Kernel-target discipline (hard constraint #1)</h2>
 * <p>The handler stays on the SPI: no {@code text/event-stream} literal and no
 * chunk framing — Core's {@code SseEventEncoder} / {@code HttpStreamEngine} own
 * the wire format. Client disconnect surfaces as an unchecked
 * {@code StreamClosedException} from {@code emit(...)}; the loop lets it
 * propagate (the engine runs teardown) and never catch-and-swallows it. The
 * handler does not buffer to a heap queue — back-pressure parks the virtual
 * thread inside {@code emit}.
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

    private static final ClassName HTTP_STREAM_HANDLER =
            ClassName.get("eu.exeris.kernel.spi.http", "HttpStreamHandler");
    private static final ClassName HTTP_STREAM_EXCHANGE =
            ClassName.get("eu.exeris.kernel.spi.http", "HttpStreamExchange");
    private static final ClassName STREAM_EVENT =
            ClassName.get("eu.exeris.kernel.spi.http", "StreamEvent");
    private static final ClassName THREAD = ClassName.get("java.lang", "Thread");
    private static final ClassName SLF4J_LOGGER = ClassName.get("org.slf4j", "Logger");
    private static final ClassName SLF4J_LOGGER_FACTORY = ClassName.get("org.slf4j", "LoggerFactory");

    /**
     * Deterministic keep-alive cadence. A compile-time CONSTANT — never a
     * wall-clock read — so the same {@link DomainMetadata} yields byte-identical
     * output (hard constraint #3).
     */
    private static final long KEEPALIVE_INTERVAL_MILLIS = 15_000L;

    /**
     * Bounded keep-alive iteration count for the scaffold loop. Deterministic
     * and finite so the generated handler terminates cleanly (calls
     * {@code close()}) until the EV1 producer seam replaces the loop with a
     * real domain-event subscription.
     */
    private static final int KEEPALIVE_ITERATIONS = 4;

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

        TypeSpec streamHandler = KernelScaffold.publicClass(className)
                .addSuperinterface(HTTP_STREAM_HANDLER)
                .addJavadoc("Generated SSE live-view stream handler for $L.\n", entity)
                .addJavadoc("<p>Implements {@link $T}; registered at {@code GET $L}\n",
                        HTTP_STREAM_HANDLER, streamPath)
                .addJavadoc("via the router's {@code streamRoute(...)} (collection-level live view).\n")
                .addJavadoc("<p>Slice 1 scaffold: emits a deterministic keep-alive then closes.\n")
                .addJavadoc("The real producer (projecting this entity's {@code @DomainEvent}s into\n")
                .addJavadoc("{@link $T}s) is the EV1 seam in {@link #handle($T)}.\n",
                        STREAM_EVENT, HTTP_STREAM_EXCHANGE)
                .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain model.\n")
                .addField(FieldSpec.builder(SLF4J_LOGGER, "LOG",
                                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$T.getLogger($T.class)", SLF4J_LOGGER_FACTORY, selfType)
                        .build())
                .addField(FieldSpec.builder(TypeName.LONG, "KEEPALIVE_INTERVAL_MILLIS",
                                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$LL", KEEPALIVE_INTERVAL_MILLIS)
                        .build())
                .addField(FieldSpec.builder(TypeName.INT, "KEEPALIVE_ITERATIONS",
                                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$L", KEEPALIVE_ITERATIONS)
                        .build())
                .addMethod(buildHandleMethod(entity))
                .build();

        return new GeneratedFile(packageName, className,
                KernelScaffold.render(packageName, streamHandler), ArtifactType.STREAM_HANDLER);
    }

    private MethodSpec buildHandleMethod(String entity) {
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
                .addComment("TODO: bind domain-event bus producer (EV1) — subscribe to this")
                .addComment("entity's @DomainEvent stream and project each event into a")
                .addComment("StreamEvent.of(eventType, json). Until EV1 payloads are rich, the")
                .addComment("scaffold below emits a deterministic, finite keep-alive so the")
                .addComment("handler compiles and runs end-to-end. Replace the loop, keep the")
                .addComment("emit/close contract (let StreamClosedException propagate).")
                .beginControlFlow("for (int i = 0; i < KEEPALIVE_ITERATIONS; i++)")
                .addComment("Comment-only keep-alive frame: no data payload, deterministic name.")
                .addStatement("exchange.emit($T.of($S, $S))",
                        STREAM_EVENT, "keep-alive", "")
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

    @Override
    public ArtifactType artifactType() {
        return ArtifactType.STREAM_HANDLER;
    }
}
