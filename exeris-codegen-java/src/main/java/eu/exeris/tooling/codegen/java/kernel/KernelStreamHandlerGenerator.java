package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.java.support.KernelScaffold;
import eu.exeris.tooling.codegen.java.support.KernelStreamScaffold;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;

import javax.lang.model.element.Modifier;
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
 * event payloads are rich, then calls {@code close()}. With the current constants
 * it closes after {@code KEEPALIVE_ITERATIONS * KEEPALIVE_INTERVAL_MILLIS} (~60s),
 * so a browser {@code EventSource} auto-reconnects on that cadence until the EV1
 * seam replaces the loop with a long-lived subscription. The real producer is a
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

        TypeSpec streamHandler = KernelScaffold.publicClass(className)
                .addSuperinterface(HTTP_STREAM_HANDLER)
                .addJavadoc("Generated SSE live-view stream handler for $L.\n", entity)
                .addJavadoc("<p>Implements {@link $T}; registered at {@code GET $L}\n",
                        HTTP_STREAM_HANDLER, streamPath)
                .addJavadoc("via the router's {@code streamRoute(...)} (collection-level live view).\n")
                .addJavadoc("<p>Slice 1 scaffold: emits a deterministic keep-alive then closes.\n")
                .addJavadoc("Closes after $L keep-alives (~$Ls); a browser EventSource auto-reconnects\n",
                        KernelStreamScaffold.KEEPALIVE_ITERATIONS,
                        KernelStreamScaffold.keepAliveWindowSeconds())
                .addJavadoc("on that cadence until the EV1 seam replaces the loop.\n")
                .addJavadoc("The real producer (projecting this entity's {@code @DomainEvent}s into\n")
                .addJavadoc("{@link $T}s) is the EV1 seam in {@link #handle($T)}.\n",
                        STREAM_EVENT, HTTP_STREAM_EXCHANGE)
                .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain model.\n")
                .addFields(KernelStreamScaffold.commonFields(selfType))
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
                // Shared deterministic keep-alive scaffold (EV1 seam + loop + close).
                // Slice-1 heartbeat note: native EventSource ignores NAMED frames, so
                // the keep-alive is invisible to onmessage (fine — nothing to process);
                // EV1 named events reach the TS client via per-name addEventListener.
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
