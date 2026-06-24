package eu.exeris.tooling.codegen.java.support;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.TypeName;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Shared scaffold for the two kernel SSE stream-handler generators —
 * {@code KernelStreamHandlerGenerator} (entity-level live-view, ADR-043 Slice 1)
 * and {@code KernelActionStreamHandlerGenerator} (per-action, ADR-044 Slice 2).
 *
 * <p>Both emit an {@code HttpStreamHandler} whose body is, until the EV1 producer
 * seam lands, the <b>same deterministic keep-alive scaffold</b>: the same kernel
 * streaming SPI {@link ClassName}s, the same constant cadence, the same emit /
 * sleep / close loop. This helper is the single home for that shared surface so
 * the two generators don't copy-paste it (CLAUDE.md strong-default #2 — "extract
 * shared scaffold"; the 0.4.0 duplication target). When EV1 replaces the loop, it
 * changes in <em>one</em> place and both drivers move together.
 *
 * <p>Determinism (hard constraint #3): every value here is a compile-time
 * CONSTANT — no wall-clock, no random — so the same metadata yields byte-identical
 * output. Kernel-target discipline (hard constraint #1): the loop stays on the SPI
 * ({@code emit}/{@code close}), emits no {@code text/event-stream} literal, and
 * lets {@code StreamClosedException} from {@code emit} propagate.
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

    /**
     * The {@code LOG} field plus the two keep-alive constant fields every stream
     * handler carries. {@code selfType} is the generated class's own
     * {@link ClassName} (for the {@code LoggerFactory.getLogger(X.class)} init).
     */
    public static List<FieldSpec> commonFields(ClassName selfType) {
        return List.of(
                FieldSpec.builder(SLF4J_LOGGER, "LOG",
                                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$T.getLogger($T.class)", SLF4J_LOGGER_FACTORY, selfType)
                        .build(),
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
                .add("// TODO: bind domain-event bus producer (EV1) — subscribe to this\n")
                .add("// entity's @DomainEvent stream and project each event into a\n")
                .add("// StreamEvent.of(...). Until EV1 payloads are rich, the scaffold\n")
                .add("// below emits a deterministic, finite keep-alive so the handler\n")
                .add("// compiles and runs end-to-end. Replace the loop, keep the\n")
                .add("// emit/close contract (let StreamClosedException propagate).\n")
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
