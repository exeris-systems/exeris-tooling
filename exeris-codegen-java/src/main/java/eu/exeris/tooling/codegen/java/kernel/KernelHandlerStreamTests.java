package eu.exeris.tooling.codegen.java.kernel;

import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.ASSERTIONS;
import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.FIXED_ID;
import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.OPTIONAL;
import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.REQUEST_BODY_DECODE_EXCEPTION;
import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.UUID;
import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.invoke;
import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.test;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.ActionParamMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.ParamSource;
import eu.exeris.tooling.codegen.java.support.DomainPackages;
import eu.exeris.tooling.codegen.java.support.KernelStreamScaffold;
import eu.exeris.tooling.codegen.java.support.KernelStreamScaffold.StreamEventBinding;
import eu.exeris.tooling.codegen.java.support.NameCasing;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Emits the generated handler test cases for the entity's stream handlers (ADR-043, ADR-044,
 * ADR-058): the live view, the spectate stream and each per-action stream.
 *
 * <p>The engine writes a stream's {@code 200} head before the handler runs, so what these cases
 * assert is the frames the handler emits — a refusal is one {@code stream-error} frame carrying the
 * status the respond-once route answers — and that the stream is closed once and its bus
 * subscriptions dropped when the handler returns.
 *
 * <p>A stream that would stay open is ended by the exchange double: it disconnects after a set
 * number of frames, the way a peer that hangs up surfaces, or the case runs the handler on an
 * interrupted thread, which ends a keep-alive wait at once. No case waits on a clock.
 */
final class KernelHandlerStreamTests {

    private static final ClassName STREAM_EVENT = KernelStreamScaffold.STREAM_EVENT;
    private static final ClassName THREAD = KernelStreamScaffold.THREAD;
    private static final ClassName EVENT_DESCRIPTOR =
            ClassName.get("eu.exeris.kernel.spi.events", "EventDescriptor");

    /** A row id no case stages a row for: the stream id of an event of some other row. */
    private static final String FOREIGN_ID = "00000000-0000-4000-8000-000000000003";
    private static final String MALFORMED_ID = "not-a-uuid";
    private static final String ID = "id";
    private static final String ON_STREAM = "onStream";
    private static final String DISPATCH = "handle";

    private static final String NEW_SERVICE = "$T service = new $T()";
    private static final String GET_WITH_ID = "$T exchange = $T.get($S).withPathParam($S, $S)";
    private static final String FRAME = "$T.of($S, $S)";
    private static final String STAGE = "engine.stage($S, $L($T.fromString($S)), $S)";
    private static final String NEW_ENGINE = "$T engine = new $T()";
    private static final String ASSERT_FRAMES = "$T.assertThat(exchange.frames).containsExactly($L)";
    private static final String ASSERT_NO_FRAME = "$T.assertThat(exchange.frames).isEmpty()";
    private static final String ASSERT_CLOSED_ONCE = "$T.assertThat(exchange.closes).isEqualTo(1)";
    private static final String ASSERT_UNSUBSCRIBED = "$T.assertThat(engine.activeSubscriptions()).isZero()";
    private static final String ASSERT_NEVER_SUBSCRIBED = "$T.assertThat(engine.subscribed).isEmpty()";
    private static final String ASSERT_NOT_UPDATED = "$T.assertThat(service.updatedId).isNull()";
    private static final String ROW_PRESENT = "service.byId = $T.of(new $T())";
    private static final String ROW_ABSENT = "service.byId = $T.empty()";

    private static final String BAD_REQUEST = "Bad Request";
    private static final String NOT_FOUND = "Not Found";

    private KernelHandlerStreamTests() {
    }

    /** What every stream case of one entity names. */
    record Scaffold(DomainMetadata metadata, ClassName entityType, String handlerPackage,
                    ClassName streamExchangeType, ClassName engineType, ClassName bodyType,
                    ClassName stubType, String basePath, boolean tenantScoped) {

        ClassName streamHandler(String simpleName) {
            return ClassName.get(handlerPackage, simpleName);
        }
    }

    /** One per-action stream handler and how its request is shaped. */
    private record ActionStream(ActionMetadata action, ClassName handlerType, ClassName requestType,
                                List<ActionParamMetadata> bodyParams, List<StreamEventBinding> bindings) {

        boolean decodes() {
            return requestType != null;
        }

        boolean subscribes() {
            return !bindings.isEmpty();
        }

        String prefix() {
            return DISPATCH + NameCasing.pascal(action.name()) + "Stream";
        }
    }

    /**
     * Whether the entity has a stream handler.
     *
     * @param metadata the entity
     * @return {@code true} for a {@code realTimeApi} entity or one with a streaming action
     */
    static boolean any(DomainMetadata metadata) {
        return metadata.realTimeApi() || metadata.actions().stream().anyMatch(ActionMetadata::streaming);
    }

    /**
     * The stream cases of the entity, and the {@code onStream} helper when a case stages an event.
     *
     * @param s the types the cases name
     * @return the methods to add to the handler test, in a fixed order
     */
    static List<MethodSpec> cases(Scaffold s) {
        DomainMetadata metadata = s.metadata();
        List<MethodSpec> cases = new ArrayList<>();
        if (metadata.realTimeApi()) {
            cases.add(liveCase(s));
            cases.addAll(spectateCases(s));
        }
        for (ActionMetadata action : metadata.actions()) {
            if (action.streaming()) {
                cases.addAll(actionCases(s, actionStream(s, action)));
            }
        }
        if (KernelStreamHandlerGenerator.hasProducer(metadata)
                || KernelSpectateStreamHandlerGenerator.subscribes(metadata)) {
            cases.add(onStreamHelper());
        }
        return List.copyOf(cases);
    }

    // ---------------------------------------------------------------------------------------
    // Live view: GET {base}/stream
    // ---------------------------------------------------------------------------------------

    private static MethodSpec liveCase(Scaffold s) {
        DomainMetadata metadata = s.metadata();
        ClassName handlerType = s.streamHandler(metadata.entityName() + "StreamHandler");
        String path = s.basePath() + "/stream";
        if (!KernelStreamHandlerGenerator.hasProducer(metadata)) {
            MethodSpec.Builder m = test("liveStreamSendsAKeepAliveAndClosesWhenInterrupted")
                    .addJavadoc("The entity declares no event, so the live view only keeps the stream\n")
                    .addJavadoc("alive; an interrupt ends the wait between keep-alives, and the stream closes.\n")
                    .addStatement("$T handler = new $T()", handlerType, handlerType)
                    .addStatement("$T exchange = $T.get($S)", s.streamExchangeType(), s.streamExchangeType(), path);
            dispatchInterrupted(m, "handler.handle(exchange)");
            return m.addStatement(ASSERT_FRAMES, ASSERTIONS, CodeBlock.of(FRAME, STREAM_EVENT,
                            KernelStreamScaffold.KEEP_ALIVE_FRAME, ""))
                    .addStatement(ASSERT_CLOSED_ONCE, ASSERTIONS)
                    .build();
        }
        List<StreamEventBinding> bindings = bindings(metadata, metadata.events());
        MethodSpec.Builder m = test("liveStreamForwardsEachEventUnderItsFrameName")
                .addJavadoc("Each event of the entity reaches the stream as a frame named for the event,\n")
                .addJavadoc("carrying the payload the bus delivered. When the peer disconnects the handler\n")
                .addJavadoc("drops its subscriptions and closes the stream.\n")
                .addStatement(NEW_ENGINE, s.engineType(), s.engineType());
        List<CodeBlock> frames = new ArrayList<>();
        List<CodeBlock> subscribed = new ArrayList<>();
        for (int i = 0; i < bindings.size(); i++) {
            StreamEventBinding binding = bindings.get(i);
            String data = "{\"seq\":" + (i + 1) + "}";
            m.addStatement(STAGE,
                    binding.subscribeName(), ON_STREAM, UUID, FIXED_ID, data);
            frames.add(CodeBlock.of(FRAME, STREAM_EVENT, binding.wireName(), data));
            subscribed.add(CodeBlock.of("$S", binding.subscribeName()));
        }
        return m.addStatement("$T handler = new $T(engine)", handlerType, handlerType)
                .addStatement("$T exchange = $T.get($S)", s.streamExchangeType(), s.streamExchangeType(), path)
                .addStatement("exchange.framesBeforeDisconnect = $L", bindings.size() - 1)
                .addStatement("handler.handle(exchange)")
                .addStatement(ASSERT_FRAMES, ASSERTIONS, CodeBlock.join(frames, ", "))
                .addStatement("$T.assertThat(engine.subscribed).containsExactly($L)", ASSERTIONS,
                        CodeBlock.join(subscribed, ", "))
                .addStatement(ASSERT_UNSUBSCRIBED, ASSERTIONS)
                .addStatement(ASSERT_CLOSED_ONCE, ASSERTIONS)
                .build();
    }

    // ---------------------------------------------------------------------------------------
    // Spectate: GET {base}/{id}/stream
    // ---------------------------------------------------------------------------------------

    private static List<MethodSpec> spectateCases(Scaffold s) {
        DomainMetadata metadata = s.metadata();
        ClassName handlerType = s.streamHandler(KernelSpectateStreamHandlerGenerator.className(metadata));
        boolean subscribes = KernelSpectateStreamHandlerGenerator.subscribes(metadata);
        String path = s.basePath() + "/" + FIXED_ID + "/stream";
        List<MethodSpec> cases = new ArrayList<>();

        MethodSpec.Builder malformed = test("spectateStreamAnswersAMalformedIdWithABadRequestFrame")
                .addJavadoc("The id is parsed before the row is read, so a malformed one never reaches\n")
                .addJavadoc("the service.\n");
        newSpectate(malformed, s, handlerType, subscribes);
        malformed.addStatement(GET_WITH_ID, s.streamExchangeType(),
                        s.streamExchangeType(), s.basePath() + "/" + MALFORMED_ID + "/stream", ID, MALFORMED_ID)
                .addStatement(invoke(DISPATCH, s.tenantScoped()))
                .addStatement(ASSERT_FRAMES, ASSERTIONS, problemFrame(400, BAD_REQUEST))
                .addStatement(ASSERT_CLOSED_ONCE, ASSERTIONS)
                .addStatement("$T.assertThat(service.lookedUp).isNull()", ASSERTIONS);
        cases.add(malformed.build());

        MethodSpec.Builder absent = test("spectateStreamAnswersAnAbsentRowWithANotFoundFrame")
                .addJavadoc("A row that is absent, or invisible to the caller, is refused before the\n")
                .addJavadoc("stream subscribes to anything.\n");
        newSpectate(absent, s, handlerType, subscribes);
        absent.addStatement(ROW_ABSENT, OPTIONAL)
                .addStatement(GET_WITH_ID, s.streamExchangeType(),
                        s.streamExchangeType(), path, ID, FIXED_ID)
                .addStatement(invoke(DISPATCH, s.tenantScoped()))
                .addStatement(ASSERT_FRAMES, ASSERTIONS, problemFrame(404, NOT_FOUND))
                .addStatement(ASSERT_CLOSED_ONCE, ASSERTIONS);
        if (subscribes) {
            absent.addStatement(ASSERT_NEVER_SUBSCRIBED, ASSERTIONS);
        }
        cases.add(absent.build());

        cases.add(subscribes ? spectateForwardsCase(s, handlerType, path)
                : spectateHoldsOpenCase(s, handlerType, path));
        return cases;
    }

    private static MethodSpec spectateForwardsCase(Scaffold s, ClassName handlerType, String path) {
        StreamEventBinding binding = bindings(s.metadata(), s.metadata().events()).get(0);
        String foreign = "{\"row\":\"other\"}";
        String own = "{\"row\":\"this\"}";
        MethodSpec.Builder m = test("spectateStreamForwardsOnlyTheEventsOfItsRow")
                .addJavadoc("An event published under another row's id is filtered out; the row's own\n")
                .addJavadoc("event is the first frame. When the peer disconnects the handler drops its\n")
                .addJavadoc("subscriptions and closes the stream.\n");
        newSpectate(m, s, handlerType, true);
        return m.addStatement(ROW_PRESENT, OPTIONAL, s.entityType())
                .addStatement(STAGE,
                        binding.subscribeName(), ON_STREAM, UUID, FOREIGN_ID, foreign)
                .addStatement(STAGE,
                        binding.subscribeName(), ON_STREAM, UUID, FIXED_ID, own)
                .addStatement(GET_WITH_ID, s.streamExchangeType(),
                        s.streamExchangeType(), path, ID, FIXED_ID)
                .addStatement("exchange.framesBeforeDisconnect = 0")
                .addStatement("$T.assertThatThrownBy(() -> $L).isInstanceOf($T.class)", ASSERTIONS,
                        invoke(DISPATCH, s.tenantScoped()), KernelStreamScaffold.STREAM_CLOSED_EXCEPTION)
                .addStatement(ASSERT_FRAMES, ASSERTIONS,
                        CodeBlock.of(FRAME, STREAM_EVENT, binding.wireName(), own))
                .addStatement(ASSERT_UNSUBSCRIBED, ASSERTIONS)
                .addStatement(ASSERT_CLOSED_ONCE, ASSERTIONS)
                .build();
    }

    private static MethodSpec spectateHoldsOpenCase(Scaffold s, ClassName handlerType, String path) {
        MethodSpec.Builder m = test("spectateStreamHoldsAFoundRowOpenUntilInterrupted")
                .addJavadoc("The entity declares no event, so a found row's stream only keeps alive; an\n")
                .addJavadoc("interrupt ends the wait, and the stream closes without a refusal frame.\n");
        newSpectate(m, s, handlerType, false);
        m.addStatement(ROW_PRESENT, OPTIONAL, s.entityType())
                .addStatement(GET_WITH_ID, s.streamExchangeType(),
                        s.streamExchangeType(), path, ID, FIXED_ID);
        dispatchInterrupted(m, invoke(DISPATCH, s.tenantScoped()));
        return m.addStatement(ASSERT_NO_FRAME, ASSERTIONS)
                .addStatement(ASSERT_CLOSED_ONCE, ASSERTIONS)
                .addStatement("$T.assertThat(service.lookedUp).isEqualTo($T.fromString($S))",
                        ASSERTIONS, UUID, FIXED_ID)
                .build();
    }

    private static void newSpectate(MethodSpec.Builder m, Scaffold s, ClassName handlerType, boolean subscribes) {
        m.addStatement(NEW_SERVICE, s.stubType(), s.stubType());
        if (subscribes) {
            m.addStatement(NEW_ENGINE, s.engineType(), s.engineType())
                    .addStatement("$T handler = new $T(service, engine)", handlerType, handlerType);
        } else {
            m.addStatement("$T handler = new $T(service)", handlerType, handlerType);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Per-action stream: POST {base}/{id}/actions/{action}
    // ---------------------------------------------------------------------------------------

    private static ActionStream actionStream(Scaffold s, ActionMetadata action) {
        DomainMetadata metadata = s.metadata();
        ClassName handlerType = s.streamHandler(
                metadata.entityName() + NameCasing.pascal(action.name()) + "StreamHandler");
        ClassName requestType = KernelActionStreamHandlerGenerator.decodesBody(action)
                ? handlerType.nestedClass(KernelHandlerGenerator.actionRequestName(action))
                : null;
        List<ActionParamMetadata> bodyParams = action.params().stream()
                .filter(p -> p.effectiveBindingSource() == ParamSource.BODY)
                .toList();
        return new ActionStream(action, handlerType, requestType, bodyParams,
                bindings(metadata, KernelActionStreamHandlerGenerator.triggeredEvents(metadata, action)));
    }

    private static List<MethodSpec> actionCases(Scaffold s, ActionStream a) {
        String path = s.basePath() + "/" + FIXED_ID + "/actions/" + NameCasing.kebab(a.action().name());
        List<MethodSpec> cases = new ArrayList<>();

        MethodSpec.Builder malformed = test(a.prefix() + "AnswersAMalformedIdWithABadRequestFrame")
                .addJavadoc("The id is parsed first, so a malformed one is refused before the row is read.\n")
                .addStatement(NEW_SERVICE, s.stubType(), s.stubType());
        stageRequest(malformed, s, a);
        arrangeAction(malformed, s, a, s.basePath() + "/" + MALFORMED_ID + "/actions/"
                + NameCasing.kebab(a.action().name()), MALFORMED_ID);
        cases.add(malformed.addStatement(ASSERT_FRAMES, ASSERTIONS, problemFrame(400, BAD_REQUEST))
                .addStatement(ASSERT_CLOSED_ONCE, ASSERTIONS)
                .addStatement(ASSERT_NOT_UPDATED, ASSERTIONS)
                .build());

        MethodSpec.Builder absent = test(a.prefix() + "AnswersAnAbsentRowWithANotFoundFrame")
                .addJavadoc("The row is read before the action runs, so an absent one is refused and\n")
                .addJavadoc("nothing is written.\n");
        absent.addStatement(NEW_SERVICE, s.stubType(), s.stubType()).addStatement(ROW_ABSENT, OPTIONAL);
        stageRequest(absent, s, a);
        arrangeAction(absent, s, a, path, FIXED_ID);
        absent.addStatement(ASSERT_FRAMES, ASSERTIONS, problemFrame(404, NOT_FOUND))
                .addStatement(ASSERT_CLOSED_ONCE, ASSERTIONS)
                .addStatement(ASSERT_NOT_UPDATED, ASSERTIONS);
        if (a.subscribes()) {
            absent.addStatement(ASSERT_NEVER_SUBSCRIBED, ASSERTIONS);
        }
        cases.add(absent.build());

        if (a.decodes()) {
            cases.add(actionUndecodableCase(s, a, path));
        }
        cases.add(actionRefusedWriteCase(s, a, path));
        cases.add(actionSuccessCase(s, a, path));
        return cases;
    }

    private static MethodSpec actionUndecodableCase(Scaffold s, ActionStream a, String path) {
        MethodSpec.Builder m = test(a.prefix() + "AnswersAnUndecodableBodyWithABadRequestFrame")
                .addStatement(NEW_SERVICE, s.stubType(), s.stubType())
                .addStatement(ROW_PRESENT, OPTIONAL, s.entityType())
                .addStatement("$T body = new $T()", s.bodyType(), s.bodyType())
                .addStatement("body.failure = $T.malformedBody($T.class.getName(), 0L, null)",
                        REQUEST_BODY_DECODE_EXCEPTION, a.requestType());
        arrangeAction(m, s, a, path, FIXED_ID);
        return m.addStatement(ASSERT_FRAMES, ASSERTIONS, problemFrame(400, BAD_REQUEST))
                .addStatement(ASSERT_CLOSED_ONCE, ASSERTIONS)
                .addStatement(ASSERT_NOT_UPDATED, ASSERTIONS)
                .build();
    }

    /**
     * The write the action makes is refused: a versioned row whose version moved answers 409, and
     * an unversioned row that vanished between the read and the write answers 404.
     */
    private static MethodSpec actionRefusedWriteCase(Scaffold s, ActionStream a, String path) {
        boolean versioned = s.metadata().versioned();
        MethodSpec.Builder m = test(a.prefix() + (versioned
                ? "AnswersAStaleVersionWithAConflictFrame"
                : "AnswersAVanishedRowWithANotFoundFrame"))
                .addJavadoc("The row was read but the write matched none, so the action's events are\n")
                .addJavadoc("never published and the refusal is the only frame.\n")
                .addStatement(NEW_SERVICE, s.stubType(), s.stubType())
                .addStatement(ROW_PRESENT, OPTIONAL, s.entityType())
                .addStatement("service.rowExists = false");
        stageRequest(m, s, a);
        arrangeAction(m, s, a, path, FIXED_ID);
        m.addStatement(ASSERT_FRAMES, ASSERTIONS,
                        versioned ? problemFrame(409, "Conflict") : problemFrame(404, NOT_FOUND))
                .addStatement(ASSERT_CLOSED_ONCE, ASSERTIONS)
                .addStatement(ASSERT_NOT_UPDATED, ASSERTIONS);
        if (a.subscribes()) {
            m.addStatement(ASSERT_UNSUBSCRIBED, ASSERTIONS);
        }
        return m.build();
    }

    private static MethodSpec actionSuccessCase(Scaffold s, ActionStream a, String path) {
        MethodSpec.Builder m = test(a.prefix() + (a.subscribes()
                ? "StreamsEachTriggeredEventAndCloses"
                : "RunsTheActionAndClosesWithoutAFrame"));
        if (a.subscribes()) {
            m.addJavadoc("The action runs, its write commits, and each event it publishes reaches the\n")
                    .addJavadoc("stream; the stream closes once every triggered event has arrived.\n");
        } else {
            m.addJavadoc("The action triggers no event, so the stream closes as soon as the write commits.\n");
        }
        m.addStatement(NEW_SERVICE, s.stubType(), s.stubType())
                .addStatement(ROW_PRESENT, OPTIONAL, s.entityType());
        stageRequest(m, s, a);
        arrangeAction(m, s, a, path, FIXED_ID);
        if (a.decodes()) {
            m.addStatement("$T.assertThat(body.decodedType).isEqualTo($T.class)", ASSERTIONS, a.requestType());
        }
        if (a.subscribes()) {
            List<CodeBlock> names = a.bindings().stream()
                    .map(b -> CodeBlock.of("$S", b.wireName()))
                    .toList();
            m.addStatement("$T.assertThat(exchange.frames).extracting($T::event).containsExactlyInAnyOrder($L)",
                            ASSERTIONS, STREAM_EVENT, CodeBlock.join(names, ", "))
                    .addStatement(ASSERT_UNSUBSCRIBED, ASSERTIONS);
        } else {
            m.addStatement(ASSERT_NO_FRAME, ASSERTIONS);
        }
        return m.addStatement("$T.assertThat(service.updatedId).isEqualTo($T.fromString($S))",
                        ASSERTIONS, UUID, FIXED_ID)
                .addStatement(ASSERT_CLOSED_ONCE, ASSERTIONS)
                .build();
    }

    /** The request body the action decodes, staged to decode, when it decodes one. */
    private static void stageRequest(MethodSpec.Builder m, Scaffold s, ActionStream a) {
        if (a.decodes()) {
            m.addStatement("$T body = new $T()", s.bodyType(), s.bodyType())
                    .addStatement("body.next = new $T($L)", a.requestType(),
                            KernelHandlerActionTests.actionRequestArgs(a.bodyParams()));
        }
    }

    /**
     * The engine when the handler subscribes, the handler over the service (and the body, and a
     * publisher on that engine, when it takes them), the exchange for {@code path} with {@code id}
     * bound, and the dispatch.
     */
    private static void arrangeAction(MethodSpec.Builder m, Scaffold s, ActionStream a, String path, String id) {
        List<CodeBlock> args = new ArrayList<>();
        args.add(CodeBlock.of("service"));
        if (a.decodes()) {
            args.add(CodeBlock.of("body"));
        }
        if (a.subscribes()) {
            DomainMetadata metadata = s.metadata();
            ClassName publisherType = ClassName.get(DomainPackages.sibling(metadata, DomainPackages.EVENT),
                    metadata.entityName() + "EventPublisher");
            m.addStatement(NEW_ENGINE, s.engineType(), s.engineType());
            args.add(CodeBlock.of("new $T(engine)", publisherType));
            args.add(CodeBlock.of("engine"));
        }
        m.addStatement("$T handler = new $T($L)", a.handlerType(), a.handlerType(), CodeBlock.join(args, ", "));
        if (a.decodes()) {
            m.addStatement("$T exchange = $T.post($S, body).withPathParam($S, $S)", s.streamExchangeType(),
                    s.streamExchangeType(), path, ID, id);
        } else {
            m.addStatement("$T exchange = $T.post($S).withPathParam($S, $S)", s.streamExchangeType(),
                    s.streamExchangeType(), path, ID, id);
        }
        KernelHandlerActionTests.appendActionDispatch(m, DISPATCH, a.decodes(), s.tenantScoped());
    }

    private static CodeBlock problemFrame(int status, String title) {
        return CodeBlock.of(FRAME, STREAM_EVENT, KernelStreamScaffold.STREAM_ERROR_FRAME,
                "{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status + "}");
    }

    private static List<StreamEventBinding> bindings(DomainMetadata metadata, List<DomainEventMetadata> events) {
        List<StreamEventBinding> bindings = new ArrayList<>();
        for (DomainEventMetadata event : events) {
            bindings.add(KernelStreamScaffold.bindingOf(event, metadata.entityName()));
        }
        return bindings;
    }

    /**
     * Runs {@code dispatch} on an interrupted thread and asserts the handler left the interrupt
     * set; the flag is cleared whatever happens, so no later case runs interrupted.
     */
    private static void dispatchInterrupted(MethodSpec.Builder m, String dispatch) {
        m.addStatement("boolean interrupted")
                .addStatement("$T.currentThread().interrupt()", THREAD)
                .beginControlFlow("try")
                .addStatement(dispatch)
                .nextControlFlow("finally")
                .addStatement("interrupted = $T.interrupted()", THREAD)
                .endControlFlow()
                .addStatement("$T.assertThat(interrupted).isTrue()", ASSERTIONS);
    }

    private static MethodSpec onStreamHelper() {
        return MethodSpec.methodBuilder(ON_STREAM)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(EVENT_DESCRIPTOR)
                .addParameter(UUID, "stream")
                .addJavadoc("A descriptor of an event published under {@code stream}: the stream id is\n")
                .addJavadoc("all a stream handler's row filter reads.\n")
                .addStatement("return $T.of(0L, 1L, stream.getMostSignificantBits(),\n"
                        + "        stream.getLeastSignificantBits(), 0, 0, 0L)", EVENT_DESCRIPTOR)
                .build();
    }
}
