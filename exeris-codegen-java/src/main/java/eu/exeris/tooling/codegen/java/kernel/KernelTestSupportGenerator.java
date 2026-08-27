package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.TypeVariableName;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.java.support.KernelScaffold;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Emits the one piece of test infrastructure the generated tests share:
 * {@code RecordingHttpExchange}, a hand-rolled {@link eu.exeris.kernel.spi.http.HttpExchange}
 * double that captures what a handler responded.
 *
 * <p><b>Why hand-rolled and not a mock (ADR-058).</b> Tooling emits no {@code pom.xml}, so every
 * import a generated test carries becomes a hard requirement on the consumer's build. The
 * dependency contract is therefore JUnit 5 + AssertJ and nothing else — a mocking framework would
 * make {@code -Dexeris.tests} impose a third library on every downstream project. {@code
 * HttpExchange} has two abstract methods, so the double costs less than the dependency would.
 *
 * <p>The four {@code respond(...)} overloads are all overridden, including the interface's
 * <em>default</em> ones. That is deliberate: the defaults funnel through the codec path to build an
 * {@code HttpResponse}, which needs an encoder bound at runtime. Overriding them keeps the double
 * infrastructure-free and lets a test assert on the status and the response object the handler
 * actually passed, rather than on an encoded buffer.
 *
 * <p>Project-wide, like {@link KernelApplicationGenerator}: one copy per application under
 * {@code <basePackage>.testsupport}, not one per entity.
 *
 * @since 0.7.0
 */
public final class KernelTestSupportGenerator {

    /** Sub-package the emitted support types live in, relative to the project base package. */
    public static final String TEST_SUPPORT_PACKAGE = "testsupport";

    /** Simple name of the emitted {@code HttpExchange} double. */
    public static final String RECORDING_EXCHANGE = "RecordingHttpExchange";

    /** Simple name of the emitted persistence-SPI double. */
    public static final String RECORDING_PERSISTENCE = "RecordingPersistence";

    /** Simple name of the emitted flow-SPI double. */
    public static final String RECORDING_FLOW = "RecordingFlow";

    /** Simple name of the emitted request-body double. */
    public static final String RECORDING_REQUEST_BODY = "RecordingRequestBody";

    /** Simple name of the emitted event-engine double (T48). */
    public static final String RECORDING_EVENT_ENGINE = "RecordingEventEngine";

    private static final ClassName HTTP_EXCHANGE =
            ClassName.get("eu.exeris.kernel.spi.http", "HttpExchange");
    private static final ClassName HTTP_REQUEST =
            ClassName.get("eu.exeris.kernel.spi.http", "HttpRequest");
    private static final ClassName HTTP_RESPONSE =
            ClassName.get("eu.exeris.kernel.spi.http", "HttpResponse");
    private static final ClassName HTTP_STATUS =
            ClassName.get("eu.exeris.kernel.spi.http", "HttpStatus");
    private static final ClassName HTTP_METHOD =
            ClassName.get("eu.exeris.kernel.spi.http", "HttpMethod");
    private static final ClassName HTTP_VERSION =
            ClassName.get("eu.exeris.kernel.spi.http", "HttpVersion");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName MAP = ClassName.get("java.util", "Map");
    private static final ClassName LINKED_HASH_MAP = ClassName.get("java.util", "LinkedHashMap");
    private static final ClassName FUNCTION = ClassName.get("java.util.function", "Function");
    private static final ClassName MEMORY_SEGMENT = ClassName.get("java.lang.foreign", "MemorySegment");

    private static final String SPI_MEMORY = "eu.exeris.kernel.spi.memory";
    private static final ClassName LOANED_BUFFER = ClassName.get(SPI_MEMORY, "LoanedBuffer");
    private static final ClassName MEMORY_ALLOCATOR = ClassName.get(SPI_MEMORY, "MemoryAllocator");
    private static final ClassName ALLOCATION_HINT = ClassName.get(SPI_MEMORY, "AllocationHint");
    private static final ClassName MEMORY_STATS = ClassName.get(SPI_MEMORY, "MemoryStats");
    private static final ClassName HTTP_REQUEST_BODY_DECODER =
            ClassName.get("eu.exeris.kernel.spi.http", "HttpRequestBodyDecoder");
    private static final ClassName HTTP_REQUEST_BODY_DECODER_REGISTRY =
            ClassName.get("eu.exeris.kernel.spi.http", "HttpRequestBodyDecoderRegistry");
    private static final ClassName HTTP_REQUEST_DECODING_CONTEXT =
            ClassName.get("eu.exeris.kernel.spi.http", "HttpRequestDecodingContext");
    private static final ClassName CLASS_OF_ANY = ClassName.get("java.lang", "Class");

    private static final String SPI_PERSISTENCE = "eu.exeris.kernel.spi.persistence";
    private static final ClassName TRANSACTIONAL_EXECUTOR =
            ClassName.get(SPI_PERSISTENCE, "TransactionalExecutor");
    private static final ClassName TRANSACTIONAL_WORK =
            ClassName.get(SPI_PERSISTENCE, "TransactionalExecutor", "TransactionalWork");
    private static final ClassName TRANSACTION_ISOLATION =
            ClassName.get(SPI_PERSISTENCE, "TransactionIsolation");
    private static final ClassName PERSISTENCE_CONNECTION =
            ClassName.get(SPI_PERSISTENCE, "PersistenceConnection");
    private static final ClassName PERSISTENCE_STATEMENT =
            ClassName.get(SPI_PERSISTENCE, "PersistenceStatement");
    private static final ClassName QUERY_RESULT = ClassName.get(SPI_PERSISTENCE, "QueryResult");
    private static final ClassName ROW_CURSOR = ClassName.get(SPI_PERSISTENCE, "RowCursor");

    private static final String SPI_FLOW = "eu.exeris.kernel.spi.flow";
    private static final String SPI_FLOW_MODEL = SPI_FLOW + ".model";
    private static final String SPI_EVENTS = "eu.exeris.kernel.spi.events";
    private static final ClassName EVENT_ENGINE = ClassName.get(SPI_EVENTS, "EventEngine");
    private static final ClassName EVENT_BUS = ClassName.get(SPI_EVENTS, "EventBus");
    private static final ClassName EVENT_REGISTRY = ClassName.get(SPI_EVENTS, "EventRegistry");
    private static final ClassName EVENT_QUEUE = ClassName.get(SPI_EVENTS, "EventQueue");
    private static final ClassName EVENT_LOOP = ClassName.get(SPI_EVENTS, "EventLoop");
    private static final ClassName EVENT_ENGINE_STATS = ClassName.get(SPI_EVENTS, "EventEngineStats");
    private static final ClassName EVENT_DESCRIPTOR = ClassName.get(SPI_EVENTS, "EventDescriptor");
    private static final ClassName EVENT_PAYLOAD = ClassName.get(SPI_EVENTS, "EventPayload");
    private static final ClassName EVENT_TYPE_SPEC = ClassName.get(SPI_EVENTS, "EventTypeSpec");
    private static final ClassName EVENT_HANDLER = ClassName.get(SPI_EVENTS, "EventHandler");
    private static final ClassName SUBSCRIPTION_TOKEN = ClassName.get(SPI_EVENTS, "SubscriptionToken");
    private static final ClassName FLOW_ENGINE = ClassName.get(SPI_FLOW, "FlowEngine");
    private static final ClassName PLAN_FACTORY = ClassName.get(SPI_FLOW, "FlowExecutionPlanFactory");
    private static final ClassName DEFINITION_BUILDER = ClassName.get(SPI_FLOW, "FlowDefinitionBuilder");
    private static final ClassName FLOW_SCHEDULER = ClassName.get(SPI_FLOW, "FlowScheduler");
    private static final ClassName FLOW_REGISTRY = ClassName.get(SPI_FLOW, "FlowRegistry");
    private static final ClassName FLOW_CAPABILITIES = ClassName.get(SPI_FLOW, "FlowEngineCapabilities");
    private static final ClassName FLOW_STATS = ClassName.get(SPI_FLOW, "FlowEngineStats");
    private static final ClassName FLOW_DEFINITION = ClassName.get(SPI_FLOW_MODEL, "FlowDefinition");
    private static final ClassName FLOW_PLAN = ClassName.get(SPI_FLOW_MODEL, "FlowExecutionPlan");
    private static final ClassName FLOW_CONTEXT = ClassName.get(SPI_FLOW_MODEL, "FlowContext");
    private static final ClassName FLOW_STEP_ACTION = ClassName.get(SPI_FLOW_MODEL, "FlowStepAction");
    private static final ClassName FLOW_STEP_DESCRIPTOR = ClassName.get(SPI_FLOW_MODEL, "FlowStepDescriptor");
    private static final ClassName FLOW_STATE = ClassName.get(SPI_FLOW_MODEL, "FlowState");
    private static final ClassName ARRAY_LIST = ClassName.get("java.util", "ArrayList");

    /**
     * Every shared double, in emission order.
     *
     * @param basePackage the project base package; the support types land in
     *                    {@code <basePackage>.testsupport}
     */
    public List<GeneratedFile> generateAll(String basePackage) {
        return List.of(generate(basePackage), generatePersistence(basePackage),
                generateFlow(basePackage), generateRequestBody(basePackage),
                generateEventEngine(basePackage));
    }

    /**
     * @param basePackage the project base package; the support type lands in
     *                    {@code <basePackage>.testsupport}
     * @return the single emitted file; never {@code null}
     */
    public GeneratedFile generate(String basePackage) {
        String packageName = supportPackage(basePackage);
        TypeName stringMap = ParameterizedTypeName.get(MAP,
                ClassName.get(String.class), ClassName.get(String.class));

        TypeSpec.Builder type = KernelScaffold.publicClass(RECORDING_EXCHANGE)
                .addModifiers(Modifier.FINAL)
                .addSuperinterface(HTTP_EXCHANGE)
                .addJavadoc("An {@link $T} double that records what a handler responded.\n", HTTP_EXCHANGE)
                .addJavadoc("<p>Every {@code respond(...)} overload is overridden, the interface's\n")
                .addJavadoc("default ones included: those build an {@link $T} through the codec\n", HTTP_RESPONSE)
                .addJavadoc("path, which needs an encoder bound at runtime. Recording the status and\n")
                .addJavadoc("the response object directly keeps this double free of kernel runtime\n")
                .addJavadoc("state, so a handler test needs no bootstrap.\n")
                .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain models.\n")
                .addField(FieldSpec.builder(HTTP_REQUEST, "request", Modifier.PRIVATE, Modifier.FINAL).build())
                .addField(FieldSpec.builder(stringMap, "pathParams", Modifier.PRIVATE, Modifier.FINAL)
                        .initializer("new $T<>()", LINKED_HASH_MAP).build())
                .addField(FieldSpec.builder(HTTP_STATUS, "status", Modifier.PRIVATE).build())
                .addField(FieldSpec.builder(ClassName.get(Object.class), "body", Modifier.PRIVATE).build());

        type.addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PRIVATE)
                .addParameter(HTTP_REQUEST, "request")
                .addStatement("this.request = request")
                .build());

        type.addMethod(factory("get", "GET"));
        type.addMethod(factory("delete", "DELETE"));
        // POST/PUT with no body. A handler's body guard rejects before anything reads the
        // LoanedBuffer, so these need no buffer double — see KernelHandlerTestGenerator.
        type.addMethod(factory("post", "POST"));
        type.addMethod(factory("put", "PUT"));
        // …and the body-carrying pair, for the paths past a successful decode.
        type.addMethod(bodyFactory("post", "POST"));
        type.addMethod(bodyFactory("put", "PUT"));

        type.addMethod(MethodSpec.methodBuilder("withPathParam")
                .addModifiers(Modifier.PUBLIC)
                .returns(ClassName.get(supportPackage(basePackage), RECORDING_EXCHANGE))
                .addParameter(String.class, "name")
                .addParameter(String.class, "value")
                .addJavadoc("Binds one path parameter, the way the router binds {@code {id}}.\n")
                .addStatement("this.pathParams.put(name, value)")
                .addStatement("return this")
                .build());

        type.addMethod(MethodSpec.methodBuilder("status")
                .addModifiers(Modifier.PUBLIC)
                .returns(HTTP_STATUS)
                .addJavadoc("The status the handler responded with, or {@code null} if it never responded.\n")
                .addStatement("return status")
                .build());

        type.addMethod(MethodSpec.methodBuilder("body")
                .addModifiers(Modifier.PUBLIC)
                .returns(Object.class)
                .addJavadoc("The response object the handler passed, or {@code null} for a bodyless response.\n")
                .addStatement("return body")
                .build());

        type.addMethod(MethodSpec.methodBuilder("request")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(HTTP_REQUEST)
                .addStatement("return request")
                .build());

        type.addMethod(MethodSpec.methodBuilder("pathParams")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(stringMap)
                .addStatement("return pathParams")
                .build());

        type.addMethod(MethodSpec.methodBuilder("respond")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(HTTP_RESPONSE, "response")
                .addStatement("this.status = response.status()")
                .addStatement("this.body = null")
                .build());

        type.addMethod(MethodSpec.methodBuilder("respond")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(HTTP_STATUS, "status")
                .addStatement("this.status = status")
                .addStatement("this.body = null")
                .build());

        type.addMethod(MethodSpec.methodBuilder("respond")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(HTTP_STATUS, "status")
                .addParameter(Object.class, "body")
                .addStatement("this.status = status")
                .addStatement("this.body = body")
                .build());

        return new GeneratedFile(packageName, RECORDING_EXCHANGE,
                KernelScaffold.render(packageName, type.build()), ArtifactType.TEST);
    }

    /**
     * Emits {@code RecordingPersistence} — one object playing every role of the persistence SPI a
     * generated repository touches: {@code TransactionalExecutor}, {@code PersistenceConnection},
     * {@code PersistenceStatement}, {@code QueryResult} and {@code RowCursor}.
     *
     * <p><b>One class, five interfaces.</b> The repository walks
     * {@code executor.query(conn -> conn.prepare(sql).…executeQuery().row())}, so a faithful double
     * would be five objects wiring into each other. Collapsing them puts the recorded binds and the
     * staged row in one place, which is what lets a test replay the one against the other — the
     * round-trip assertion this double exists for. The signatures do not collide: the three
     * {@code close()} declarations are identical, and so are the two {@code columnCount()}.
     *
     * <p>State is deliberately public and unencapsulated: {@code row} stages what the next query
     * returns ({@code null} = no rows), {@code rowsAffected} stages what a write reports (set it to
     * {@code 0} to exercise the not-found paths), and {@code binds} / {@code sql} record what the
     * repository did. {@link #RECORDING_PERSISTENCE}{@code .recordedRow()} snapshots the binds,
     * which matters because {@code prepare(...)} clears them — a test must copy before it queries.
     *
     * <p>{@code close()} is a no-op on purpose. The repository closes statements and results inside
     * try-with-resources, and a close that reset state would erase the recording mid-method.
     *
     * <p>{@code getSegment} / {@code getLength} throw: no emitted repository reads a column that
     * way, and a double that silently returned {@code null} would hide the day one does.
     */
    public GeneratedFile generatePersistence(String basePackage) {
        String packageName = supportPackage(basePackage);
        TypeName intObjectMap = ParameterizedTypeName.get(MAP,
                ClassName.get(Integer.class), ClassName.get(Object.class));
        TypeVariableName t = TypeVariableName.get("T");

        TypeSpec.Builder type = KernelScaffold.publicClass(RECORDING_PERSISTENCE)
                .addModifiers(Modifier.FINAL)
                .addSuperinterface(TRANSACTIONAL_EXECUTOR)
                .addSuperinterface(PERSISTENCE_CONNECTION)
                .addSuperinterface(PERSISTENCE_STATEMENT)
                .addSuperinterface(QUERY_RESULT)
                .addSuperinterface(ROW_CURSOR)
                .addJavadoc("A persistence-SPI double: one object plays the executor, the\n")
                .addJavadoc("connection, the statement, the result and the cursor, so what a\n")
                .addJavadoc("repository <em>bound</em> and what it later <em>reads</em> can be\n")
                .addJavadoc("compared in one place.\n")
                .addJavadoc("<p>Stage {@code row} with the row a query should return ({@code null}\n")
                .addJavadoc("means no rows) and {@code rowsAffected} with what a write reports.\n")
                .addJavadoc("Inspect {@code sql} and {@code binds} for what the repository did.\n")
                .addJavadoc("<p>No database, no driver, no transaction — every method is either a\n")
                .addJavadoc("recording or a staged answer.\n")
                .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain models.\n")
                .addField(FieldSpec.builder(String.class, "sql", Modifier.PUBLIC)
                        .addJavadoc("The SQL of the most recently prepared statement.\n").build())
                .addField(FieldSpec.builder(intObjectMap, "binds", Modifier.PUBLIC, Modifier.FINAL)
                        .initializer("new $T<>()", LINKED_HASH_MAP)
                        .addJavadoc("Parameter index to bound value, for the current statement.\n")
                        .build())
                .addField(FieldSpec.builder(intObjectMap, "row", Modifier.PUBLIC)
                        .addJavadoc("The row the next query returns, by column index; null = no rows.\n")
                        .build())
                .addField(FieldSpec.builder(TypeName.LONG, "rowsAffected", Modifier.PUBLIC)
                        .initializer("1L")
                        .addJavadoc("What the next write reports; 0 drives the not-found paths.\n")
                        .build())
                .addField(FieldSpec.builder(TypeName.BOOLEAN, "rowServed", Modifier.PRIVATE).build());

        type.addMethod(MethodSpec.methodBuilder("recordedRow")
                .addModifiers(Modifier.PUBLIC)
                .returns(intObjectMap)
                .addJavadoc("A snapshot of the current binds — the row an INSERT would have written.\n")
                .addJavadoc("<p>Copies, because {@code prepare(...)} clears {@code binds}: staging\n")
                .addJavadoc("this as {@code row} before the read is what makes a round-trip work.\n")
                .addStatement("return new $T<>(binds)", LINKED_HASH_MAP)
                .build());

        // --- TransactionalExecutor: every form runs the work against this same object.
        for (String executeForm : List.of("execute", "executeManaged")) {
            type.addMethod(override(executeForm)
                    .addParameter(TRANSACTIONAL_WORK, "work")
                    .addStatement("work.run(this)")
                    .build());
        }
        type.addMethod(override("executeManaged")
                .addParameter(TRANSACTION_ISOLATION, "isolation")
                .addParameter(TypeName.BOOLEAN, "readOnly")
                .addParameter(TRANSACTIONAL_WORK, "work")
                .addStatement("work.run(this)")
                .build());
        type.addMethod(override("query")
                .addTypeVariable(t)
                .returns(t)
                .addParameter(ParameterizedTypeName.get(FUNCTION, PERSISTENCE_CONNECTION, t), "query")
                .addStatement("return query.apply(this)")
                .build());

        // --- PersistenceConnection
        type.addMethod(override("prepare")
                .returns(PERSISTENCE_STATEMENT)
                .addParameter(String.class, "sql")
                .addStatement("this.sql = sql")
                .addStatement("this.binds.clear()")
                .addStatement("return this")
                .build());
        type.addMethod(override("executeQuery")
                .returns(QUERY_RESULT)
                .addParameter(String.class, "sql")
                .addStatement("prepare(sql)")
                .addStatement("return executeQuery()")
                .build());
        type.addMethod(override("executeUpdate")
                .returns(TypeName.LONG)
                .addParameter(String.class, "sql")
                .addStatement("prepare(sql)")
                .addStatement("return executeUpdate()")
                .build());
        type.addMethod(override("beginTransaction").build());
        type.addMethod(override("beginTransaction")
                .addParameter(TRANSACTION_ISOLATION, "isolation")
                .addParameter(TypeName.BOOLEAN, "readOnly")
                .build());
        type.addMethod(override("commit").build());
        type.addMethod(override("rollback").build());
        type.addMethod(returning("inTransaction", TypeName.BOOLEAN, "false"));
        type.addMethod(returning("isOpen", TypeName.BOOLEAN, "true"));
        // Shared by connection, statement and result — see the class Javadoc on why it is inert.
        type.addMethod(override("close").build());

        // --- PersistenceStatement: every bind is the same recording.
        for (Bind bind : BINDS) {
            type.addMethod(override(bind.method())
                    .returns(PERSISTENCE_STATEMENT)
                    .addParameter(TypeName.INT, "index")
                    .addParameter(bind.valueType(), "value")
                    .addStatement("binds.put(index, value)")
                    .addStatement("return this")
                    .build());
        }
        type.addMethod(override("bindNull")
                .returns(PERSISTENCE_STATEMENT)
                .addParameter(TypeName.INT, "index")
                .addStatement("binds.put(index, null)")
                .addStatement("return this")
                .build());
        type.addMethod(override("executeQuery")
                .returns(QUERY_RESULT)
                .addStatement("this.rowServed = false")
                .addStatement("return this")
                .build());
        type.addMethod(returning("executeUpdate", TypeName.LONG, "rowsAffected"));

        // --- QueryResult
        type.addMethod(override("next")
                .returns(TypeName.BOOLEAN)
                .addStatement("if (row == null || rowServed) return false")
                .addStatement("this.rowServed = true")
                .addStatement("return true")
                .build());
        type.addMethod(returning("row", ROW_CURSOR, "this"));
        type.addMethod(returning("rowsAffected", TypeName.LONG, "rowsAffected"));
        type.addMethod(returning("commandTag", ClassName.get(String.class), "\"\""));
        type.addMethod(returning("columnCount", TypeName.INT, "row == null ? 0 : row.size()"));

        // --- RowCursor
        type.addMethod(MethodSpec.methodBuilder("cell")
                .addModifiers(Modifier.PRIVATE)
                .returns(Object.class)
                .addParameter(TypeName.INT, "index")
                .addStatement("return row == null ? null : row.get(index)")
                .build());
        for (Unbox unbox : UNBOXED) {
            type.addMethod(override(unbox.method())
                    .returns(unbox.primitive())
                    .addParameter(TypeName.INT, "column")
                    .addStatement("$T value = cell(column)", Object.class)
                    .addStatement("return value == null ? $L : ($T) value",
                            unbox.absent(), unbox.boxed())
                    .build());
        }
        for (Cast cast : CASTS) {
            type.addMethod(override(cast.method())
                    .returns(cast.type())
                    .addParameter(TypeName.INT, "column")
                    .addStatement("return ($T) cell(column)", cast.type())
                    .build());
        }
        type.addMethod(override("isNull")
                .returns(TypeName.BOOLEAN)
                .addParameter(TypeName.INT, "column")
                .addStatement("return cell(column) == null")
                .build());
        type.addMethod(returning("isValid", TypeName.BOOLEAN, "true"));
        String noSuchRead = "no generated repository reads a column this way";
        type.addMethod(unsupported("getSegment", MEMORY_SEGMENT, "column", noSuchRead));
        type.addMethod(unsupported("getLength", TypeName.INT, "column", noSuchRead));

        return new GeneratedFile(packageName, RECORDING_PERSISTENCE,
                KernelScaffold.render(packageName, type.build()), ArtifactType.TEST);
    }

    /**
     * Emits {@code RecordingFlow} — one object playing every flow-SPI role a generated saga
     * touches: {@code FlowEngine}, {@code FlowExecutionPlanFactory}, {@code FlowDefinitionBuilder},
     * {@code FlowScheduler}, and (so a test needs no second fixture) the {@code FlowExecutionPlan}
     * and {@code FlowContext} it hands back.
     *
     * <p>Same collapse, and the same reason, as {@code RecordingPersistence}: the saga walks
     * {@code flowEngine.plans().newDefinition(name).step(...).transition(...)}, and what a test has
     * to compare — the registered steps against the transition chain laid over them — only exists
     * in one place if one object recorded both.
     *
     * <p>{@code build()} returns {@code null} deliberately. What a saga test asserts is what the
     * builder was <em>told</em>, and the recorded call lists carry strictly more of that than the
     * built {@code FlowDefinition} would; nothing under test reads the definition back.
     */
    public GeneratedFile generateFlow(String basePackage) {
        String packageName = supportPackage(basePackage);
        TypeName stringList = ParameterizedTypeName.get(LIST, ClassName.get(String.class));
        TypeName actionList = ParameterizedTypeName.get(LIST, FLOW_STEP_ACTION);

        TypeSpec.Builder type = KernelScaffold.publicClass(RECORDING_FLOW)
                .addModifiers(Modifier.FINAL)
                .addSuperinterface(FLOW_ENGINE)
                .addSuperinterface(PLAN_FACTORY)
                .addSuperinterface(DEFINITION_BUILDER)
                .addSuperinterface(FLOW_SCHEDULER)
                .addSuperinterface(FLOW_PLAN)
                .addSuperinterface(FLOW_CONTEXT)
                .addJavadoc("A flow-SPI double: one object plays the engine, the plan factory, the\n")
                .addJavadoc("definition builder, the scheduler, and the plan and context they pass\n")
                .addJavadoc("around — so the registered steps and the transition chain laid over\n")
                .addJavadoc("them can be compared in one place.\n")
                .addJavadoc("<p>No scheduler thread, no persistence, no engine lifecycle: every\n")
                .addJavadoc("method is either a recording or a staged answer.\n")
                .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain models.\n")
                .addField(FieldSpec.builder(String.class, "definitionName", Modifier.PUBLIC)
                        .addJavadoc("The name the saga registered its definition under.\n").build())
                .addField(FieldSpec.builder(stringList, "steps", Modifier.PUBLIC, Modifier.FINAL)
                        .initializer("new $T<>()", ARRAY_LIST)
                        .addJavadoc("Step names, in registration order — the transition indices point here.\n")
                        .build())
                .addField(FieldSpec.builder(actionList, "actions", Modifier.PUBLIC, Modifier.FINAL)
                        .initializer("new $T<>()", ARRAY_LIST)
                        .addJavadoc("The action bound to each step, positionally aligned with {@code steps}.\n")
                        .build())
                .addField(FieldSpec.builder(actionList, "compensations", Modifier.PUBLIC, Modifier.FINAL)
                        .initializer("new $T<>()", ARRAY_LIST)
                        .addJavadoc("The compensation per step; null where the step declares none.\n")
                        .build())
                .addField(FieldSpec.builder(stringList, "transitions", Modifier.PUBLIC, Modifier.FINAL)
                        .initializer("new $T<>()", ARRAY_LIST)
                        .addJavadoc("Each transition as {@code from->to}, in the order it was declared.\n")
                        .build())
                .addField(FieldSpec.builder(TypeName.LONG, "timeoutNanos", Modifier.PUBLIC).build())
                .addField(FieldSpec.builder(TypeName.INT, "maxRetries", Modifier.PUBLIC).build())
                .addField(FieldSpec.builder(TypeName.INT, "compiled", Modifier.PUBLIC)
                        .addJavadoc("How many times a definition was compiled — 1 proves lazy init is idempotent.\n")
                        .build())
                .addField(FieldSpec.builder(FLOW_PLAN, "scheduled", Modifier.PUBLIC)
                        .addJavadoc("The plan handed to the scheduler, or null if nothing was scheduled.\n")
                        .build());

        // --- FlowEngine
        type.addMethod(returning("plans", PLAN_FACTORY, "this"));
        type.addMethod(returning("scheduler", FLOW_SCHEDULER, "this"));
        type.addMethod(override("start").build());
        type.addMethod(override("close").build());
        type.addMethod(unsupportedNoArg("registry", FLOW_REGISTRY));
        type.addMethod(unsupportedNoArg("capabilities", FLOW_CAPABILITIES));
        type.addMethod(unsupportedNoArg("stats", FLOW_STATS));

        // --- FlowExecutionPlanFactory
        type.addMethod(override("newDefinition")
                .returns(DEFINITION_BUILDER)
                .addParameter(String.class, "definitionName")
                .addStatement("this.definitionName = definitionName")
                .addStatement("return this")
                .build());
        type.addMethod(override("compile")
                .returns(FLOW_PLAN)
                .addParameter(FLOW_DEFINITION, "definition")
                .addStatement("this.compiled++")
                .addStatement("return this")
                .build());

        // --- FlowDefinitionBuilder
        type.addMethod(override("step")
                .returns(DEFINITION_BUILDER)
                .addParameter(String.class, "name")
                .addParameter(FLOW_STEP_ACTION, "action")
                .addParameter(FLOW_STEP_ACTION, "compensation")
                .addStatement("steps.add(name)")
                .addStatement("actions.add(action)")
                .addStatement("compensations.add(compensation)")
                .addStatement("return this")
                .build());
        type.addMethod(override("transition")
                .returns(DEFINITION_BUILDER)
                .addParameter(TypeName.INT, "fromStep")
                .addParameter(TypeName.INT, "toStep")
                .addStatement("transitions.add(fromStep + $S + toStep)", "->")
                .addStatement("return this")
                .build());
        type.addMethod(override("transition")
                .returns(DEFINITION_BUILDER)
                .addParameter(TypeName.INT, "fromStep")
                .addParameter(TypeName.INT, "toStep")
                .addParameter(String.class, "conditionTag")
                .addStatement("return transition(fromStep, toStep)")
                .build());
        type.addMethod(override("timeoutDuration")
                .returns(DEFINITION_BUILDER)
                .addParameter(TypeName.LONG, "durationNanos")
                .addStatement("this.timeoutNanos = durationNanos")
                .addStatement("return this")
                .build());
        type.addMethod(override("maxRetries")
                .returns(DEFINITION_BUILDER)
                .addParameter(TypeName.INT, "maxRetries")
                .addStatement("this.maxRetries = maxRetries")
                .addStatement("return this")
                .build());
        type.addMethod(returning("build", FLOW_DEFINITION, "null"));

        // --- FlowScheduler
        type.addMethod(override("schedule")
                .addParameter(FLOW_PLAN, "plan")
                .addParameter(FLOW_CONTEXT, "context")
                .addStatement("this.scheduled = plan")
                .build());
        type.addMethod(override("park").addParameter(FLOW_CONTEXT, "context").build());
        type.addMethod(override("wake").addParameter(FLOW_CONTEXT, "context").build());

        // --- FlowExecutionPlan (definitionName() is shared with FlowContext)
        type.addMethod(returning("definitionName", ClassName.get(String.class), "definitionName"));
        type.addMethod(returning("stepCount", TypeName.INT, "steps.size()"));
        type.addMethod(returning("timeoutDurationNanos", TypeName.LONG, "timeoutNanos"));
        type.addMethod(unsupported("stepAt", FLOW_STEP_DESCRIPTOR, "stepIndex",
                "a generated saga reads its steps back off this double's own recording"));

        // --- FlowContext
        type.addMethod(returning("instanceIdMost", TypeName.LONG, "0L"));
        type.addMethod(returning("instanceIdLeast", TypeName.LONG, "0L"));
        type.addMethod(returning("currentStep", TypeName.INT, "0"));
        type.addMethod(returning("timeoutNanos", TypeName.LONG, "timeoutNanos"));
        type.addMethod(unsupportedNoArg("state", FLOW_STATE));

        return new GeneratedFile(packageName, RECORDING_FLOW,
                KernelScaffold.render(packageName, type.build()), ArtifactType.TEST);
    }

    /** One {@code bind*(int, X)} recording method. */
    private record Bind(String method, TypeName valueType) {}

    /** One primitive {@code RowCursor} accessor: unbox the staged value, or answer {@code absent}. */
    private record Unbox(String method, TypeName primitive, TypeName boxed, String absent) {}

    /** One reference-typed {@code RowCursor} accessor: a plain cast of the staged value. */
    private record Cast(String method, TypeName type) {}

    private static final List<Bind> BINDS = List.of(
            new Bind("bindInt", TypeName.INT),
            new Bind("bindLong", TypeName.LONG),
            new Bind("bindShort", TypeName.SHORT),
            new Bind("bindFloat", TypeName.FLOAT),
            new Bind("bindDouble", TypeName.DOUBLE),
            new Bind("bindBoolean", TypeName.BOOLEAN),
            new Bind("bindString", ClassName.get(String.class)),
            new Bind("bindUuid", ClassName.get("java.util", "UUID")),
            new Bind("bindBytes", ArrayTypeName.of(TypeName.BYTE)),
            new Bind("bindInstant", ClassName.get("java.time", "Instant")));

    private static final List<Unbox> UNBOXED = List.of(
            new Unbox("getInt", TypeName.INT, TypeName.INT.box(), "0"),
            new Unbox("getLong", TypeName.LONG, TypeName.LONG.box(), "0L"),
            new Unbox("getShort", TypeName.SHORT, TypeName.SHORT.box(), "(short) 0"),
            new Unbox("getFloat", TypeName.FLOAT, TypeName.FLOAT.box(), "0f"),
            new Unbox("getDouble", TypeName.DOUBLE, TypeName.DOUBLE.box(), "0d"),
            new Unbox("getBoolean", TypeName.BOOLEAN, TypeName.BOOLEAN.box(), "false"));

    private static final List<Cast> CASTS = List.of(
            new Cast("getString", ClassName.get(String.class)),
            new Cast("getBytes", ArrayTypeName.of(TypeName.BYTE)),
            new Cast("getUuid", ClassName.get("java.util", "UUID")),
            new Cast("getInstant", ClassName.get("java.time", "Instant")));

    private static MethodSpec.Builder override(String name) {
        return MethodSpec.methodBuilder(name)
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC);
    }

    private static MethodSpec returning(String name, TypeName returnType, String expression) {
        return override(name).returns(returnType).addStatement("return $L", expression).build();
    }

    /** An accessor no generated artefact calls; throwing beats answering null silently. */
    private static MethodSpec unsupportedNoArg(String name, TypeName returnType) {
        return override(name)
                .returns(returnType)
                .addStatement("throw new $T($S)", UnsupportedOperationException.class,
                        name + " is not recorded — no generated artefact calls it")
                .build();
    }

    /**
     * An indexed accessor no generated artefact calls. The reason is a parameter, not a constant:
     * this helper serves doubles for different SPIs, and a persistence message emitted on a flow
     * double would misdescribe the one case where it is ever read — the failure itself.
     */
    private static MethodSpec unsupported(String name, TypeName returnType, String paramName,
                                          String reason) {
        return override(name)
                .returns(returnType)
                .addParameter(TypeName.INT, paramName)
                .addStatement("throw new $T($S)", UnsupportedOperationException.class,
                        name + " is not recorded — " + reason)
                .build();
    }

    /** {@code <basePackage>.testsupport} — where the shared doubles live. */
    /**
     * Emits {@code RecordingEventEngine} — one object playing the three roles of the events SPI a
     * generated publisher walks: the {@code EventEngine} it is constructed with, the
     * {@code EventRegistry} its constructor registers every {@code EventTypeSpec} into, and the
     * {@code EventBus} each {@code publish*} call reaches. Same shape as
     * {@link #RECORDING_PERSISTENCE}, and for the same reason: T48 made the publisher a constructor
     * argument of the generated handler, so the emitted handler test needs something constructible,
     * and ADR-058 fixes the emitted-test classpath at JUnit 5 + AssertJ — there is no mocking
     * framework to reach for.
     *
     * <p>{@code queue()}, {@code loop()} and {@code stats()} return {@code null}: no generated code
     * calls them, and a double that fabricates a value for a role nothing plays would be inventing
     * behaviour rather than recording it. {@code start()} and {@code close()} are no-ops for the
     * same reason.
     *
     * @param basePackage the project base package; the support type lands in
     *                    {@code <basePackage>.testsupport}
     * @return the single emitted file; never {@code null}
     */
    public GeneratedFile generateEventEngine(String basePackage) {
        String packageName = supportPackage(basePackage);
        TypeName descriptorList = ParameterizedTypeName.get(LIST, EVENT_DESCRIPTOR);
        TypeName specList = ParameterizedTypeName.get(LIST, EVENT_TYPE_SPEC);
        TypeName stringSet = ParameterizedTypeName.get(ClassName.get(java.util.Set.class),
                ClassName.get(String.class));

        TypeSpec.Builder type = KernelScaffold.publicClass(RECORDING_EVENT_ENGINE)
                .addModifiers(Modifier.FINAL)
                .addSuperinterface(EVENT_ENGINE)
                .addSuperinterface(EVENT_BUS)
                .addSuperinterface(EVENT_REGISTRY)
                .addJavadoc("Recording double for the events SPI a generated publisher walks.\n")
                .addJavadoc("<p>One object plays {@link $T}, {@link $T} and {@link $T}: the\n",
                        EVENT_ENGINE, EVENT_BUS, EVENT_REGISTRY)
                .addJavadoc("publisher is constructed with the engine, registers its\n")
                .addJavadoc("{@code EventTypeSpec}s into the registry, and publishes onto the bus.\n")
                .addJavadoc("<p>{@code published} and {@code registered} are the assertion surface.\n")
                .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain models.\n")
                .addField(FieldSpec.builder(descriptorList, "published", Modifier.PUBLIC, Modifier.FINAL)
                        .initializer("new $T<>()", ClassName.get(java.util.ArrayList.class))
                        .addJavadoc("Every descriptor passed to {@link #publish}, in call order.\n")
                        .build())
                .addField(FieldSpec.builder(specList, "registered", Modifier.PUBLIC, Modifier.FINAL)
                        .initializer("new $T<>()", ClassName.get(java.util.ArrayList.class))
                        .addJavadoc("Every spec passed to {@link #register}, in call order.\n")
                        .build());

        // EventEngine
        type.addMethod(override("bus", EVENT_BUS).addStatement("return this").build());
        type.addMethod(override("registry", EVENT_REGISTRY).addStatement("return this").build());
        type.addMethod(override("queue", EVENT_QUEUE).addStatement("return null").build());
        type.addMethod(override("loop", EVENT_LOOP).addStatement("return null").build());
        type.addMethod(override("stats", EVENT_ENGINE_STATS).addStatement("return null").build());
        type.addMethod(override("start", TypeName.VOID).build());
        type.addMethod(override("close", TypeName.VOID).build());

        // EventBus
        type.addMethod(override("publish", TypeName.VOID)
                .addParameter(EVENT_DESCRIPTOR, "descriptor")
                .addParameter(EVENT_PAYLOAD, "payload")
                .addStatement("published.add(descriptor)")
                .build());
        type.addMethod(override("publishAndAwait", TypeName.VOID)
                .addParameter(EVENT_DESCRIPTOR, "descriptor")
                .addParameter(EVENT_PAYLOAD, "payload")
                .addStatement("published.add(descriptor)")
                .build());
        type.addMethod(override("subscribe", SUBSCRIPTION_TOKEN)
                .addParameter(ClassName.get(String.class), "eventType")
                .addParameter(EVENT_HANDLER, "handler")
                .addStatement("return null")
                .build());
        type.addMethod(override("unsubscribe", TypeName.VOID)
                .addParameter(SUBSCRIPTION_TOKEN, "token")
                .build());

        // EventRegistry
        type.addMethod(override("register", TypeName.VOID)
                .addParameter(EVENT_TYPE_SPEC, "spec")
                .addStatement("registered.add(spec)")
                .build());
        type.addMethod(override("resolve", EVENT_TYPE_SPEC)
                .addParameter(ClassName.get(String.class), "eventType")
                .addStatement("return registered.stream()\n"
                        + "        .filter(spec -> spec.name().equals(eventType))\n"
                        + "        .findFirst()\n"
                        + "        .orElse(null)")
                .build());
        type.addMethod(override("registeredTypes", stringSet)
                .addStatement("return registered.stream().map($T::name).collect($T.toSet())",
                        EVENT_TYPE_SPEC, ClassName.get(java.util.stream.Collectors.class))
                .build());
        type.addMethod(override("size", TypeName.INT)
                .addStatement("return registered.size()")
                .build());

        return new GeneratedFile(packageName, RECORDING_EVENT_ENGINE,
                KernelScaffold.render(packageName, type.build()), ArtifactType.TEST);
    }

    /** A {@code public} {@code @Override} method builder — every double method has that shape. */
    private static MethodSpec.Builder override(String name, TypeName returns) {
        return MethodSpec.methodBuilder(name)
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(returns);
    }

    public static String supportPackage(String basePackage) {
        return basePackage + "." + TEST_SUPPORT_PACKAGE;
    }

    /** A {@code static RecordingHttpExchange <verb>(String path)} factory for one HTTP method. */
    private MethodSpec factory(String name, String httpMethod) {
        return MethodSpec.methodBuilder(name)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(ClassName.bestGuess(RECORDING_EXCHANGE))
                .addParameter(String.class, "path")
                .addJavadoc("A bodyless {@code $L} exchange for {@code path}.\n", httpMethod)
                .addStatement("return new $L($T.noBody($T.$L, path, $T.HTTP_1_1, $T.of()))",
                        RECORDING_EXCHANGE, HTTP_REQUEST, HTTP_METHOD, httpMethod, HTTP_VERSION, LIST)
                .build();
    }

    /**
     * A {@code static RecordingHttpExchange <verb>(String path, LoanedBuffer body)} factory.
     *
     * <p>{@code HttpRequest.hasBody()} is {@code body != null} and nothing more, so the buffer's
     * only job here is to be non-null: it decides whether the handler's body guard rejects or the
     * decoder runs. Its contents are never read — the emitted decoder answers with a staged object.
     */
    private MethodSpec bodyFactory(String name, String httpMethod) {
        return MethodSpec.methodBuilder(name)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(ClassName.bestGuess(RECORDING_EXCHANGE))
                .addParameter(String.class, "path")
                .addParameter(LOANED_BUFFER, "body")
                .addJavadoc("A body-carrying {@code $L} exchange for {@code path}.\n", httpMethod)
                .addJavadoc("<p>{@code body} only has to be non-null: {@code hasBody()} is a null\n")
                .addJavadoc("check, and the decoder bound for the test never reads the buffer.\n")
                .addStatement("return new $L(new $T($T.$L, path, $T.HTTP_1_1, $T.of(), body))",
                        RECORDING_EXCHANGE, HTTP_REQUEST, HTTP_METHOD, httpMethod, HTTP_VERSION, LIST)
                .build();
    }

    /**
     * Emits {@code RecordingRequestBody} — one object playing every role the handler's
     * {@code parseBody} resolves past {@code hasBody()}: the
     * {@code HttpRequestBodyDecoderRegistry} it looks up, the {@code HttpRequestBodyDecoder} that
     * registry returns, the {@code LoanedBuffer} handed to it, and the {@code MemoryAllocator} the
     * {@code HttpRequestDecodingContext} carries.
     *
     * <p><b>Why the allocator is in here.</b> It is not optional and it is easy to miss:
     * {@code HttpRequestDecodingContext} is a record with {@code requireNonNull(allocator)}, and
     * {@code parseBody} fills that slot from {@code KernelProviders.MEMORY_ALLOCATOR.get()}. An
     * unbound slot throws {@code NoSuchElementException}, which {@code parseBody} catches and maps
     * to {@code 400 BAD_REQUEST} — the <em>same</em> status a validation rejection produces. A
     * generated test that bound only the decoder registry would go green on every rejection case
     * while never once reaching the validation guard. Binding it is what makes those cases mean
     * what they say.
     *
     * <p>Which is also why {@link KernelHandlerTestGenerator} always emits the accept case
     * alongside the reject cases: {@code 201 CREATED} can only come out the far end of a decode
     * that actually worked, so the pair fails loudly if this wiring ever stops working, instead of
     * passing quietly for the wrong reason.
     *
     * <p>The buffer and allocator roles are inert: every accessor throws, because nothing reads
     * them. The decoder ignores the buffer and answers with {@code next}, so a double that returned
     * bytes would be staging input no code path consumes.
     */
    public GeneratedFile generateRequestBody(String basePackage) {
        String packageName = supportPackage(basePackage);
        TypeName wildcardClass = ParameterizedTypeName.get(CLASS_OF_ANY,
                com.palantir.javapoet.WildcardTypeName.subtypeOf(ClassName.OBJECT));

        TypeSpec.Builder type = KernelScaffold.publicClass(RECORDING_REQUEST_BODY)
                .addModifiers(Modifier.FINAL)
                .addSuperinterface(HTTP_REQUEST_BODY_DECODER_REGISTRY)
                .addSuperinterface(HTTP_REQUEST_BODY_DECODER)
                .addSuperinterface(LOANED_BUFFER)
                .addSuperinterface(MEMORY_ALLOCATOR)
                .addJavadoc("Everything {@code parseBody} resolves, in one object: the decoder\n")
                .addJavadoc("registry, the decoder it returns, the request buffer, and the memory\n")
                .addJavadoc("allocator the decoding context requires.\n")
                .addJavadoc("<p>Stage {@code next} with the object the body should decode into, bind\n")
                .addJavadoc("this instance into both provider slots, and the handler runs its\n")
                .addJavadoc("post-decode path — the {@code @Validation} guards — with no kernel\n")
                .addJavadoc("bootstrap, no driver and no port.\n")
                .addJavadoc("<p>{@code decodedType} and {@code contentType} record what the handler\n")
                .addJavadoc("asked for.\n")
                .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain models.\n")
                .addField(FieldSpec.builder(ClassName.OBJECT, "next", Modifier.PUBLIC)
                        .addJavadoc("What the next decode returns.\n").build())
                .addField(FieldSpec.builder(wildcardClass, "decodedType", Modifier.PUBLIC)
                        .addJavadoc("The target type the handler asked to decode into.\n").build())
                .addField(FieldSpec.builder(String.class, "contentType", Modifier.PUBLIC)
                        .addJavadoc("The content-type the handler resolved the decoder with.\n").build());

        // --- HttpRequestBodyDecoderRegistry: always this same object.
        type.addMethod(override("resolve")
                .returns(HTTP_REQUEST_BODY_DECODER)
                .addParameter(wildcardClass, "targetType")
                .addParameter(String.class, "contentType")
                .addStatement("this.contentType = contentType")
                .addStatement("return this")
                .build());

        // --- HttpRequestBodyDecoder.
        type.addMethod(override("supports")
                .returns(TypeName.BOOLEAN)
                .addParameter(wildcardClass, "targetType")
                .addParameter(String.class, "contentType")
                .addStatement("return true")
                .build());
        type.addMethod(override("decode")
                .returns(ClassName.OBJECT)
                .addParameter(LOANED_BUFFER, "body")
                .addParameter(wildcardClass, "targetType")
                .addParameter(HTTP_REQUEST_DECODING_CONTEXT, "context")
                .addJavadoc("Answers with {@code next}, ignoring {@code body}: what a handler test\n")
                .addJavadoc("covers is the path <em>past</em> a decode, not the decode itself —\n")
                .addJavadoc("that belongs to the codec driver, which is not generated code.\n")
                .addStatement("this.decodedType = targetType")
                .addStatement("return next")
                .build());

        // --- LoanedBuffer: inert. close()/retain() do nothing (the transport owns the body per
        // the HttpRequest contract); everything that would hand out bytes throws.
        type.addMethod(override("close").build());
        type.addMethod(override("retain").build());
        type.addMethod(unreadBuffer("segment", MEMORY_SEGMENT));
        type.addMethod(unreadBuffer("size", TypeName.LONG));
        type.addMethod(unreadBuffer("capacity", TypeName.LONG));
        type.addMethod(unreadBuffer("refCount", TypeName.INT));
        type.addMethod(unreadBuffer("view", LOANED_BUFFER));
        // The buffer outlives every generated test unchanged, so it is alive throughout and
        // nothing ever runs a close action — but both are on the interface, so both are answered.
        type.addMethod(override("isAlive").returns(TypeName.BOOLEAN).addStatement("return true").build());
        type.addMethod(override("addCloseAction")
                .addParameter(ClassName.get("java.lang", "Runnable"), "action")
                .addStatement("throw new $T($S)", UnsupportedOperationException.class, BUFFER_UNREAD)
                .build());
        type.addMethod(override("slice")
                .returns(LOANED_BUFFER)
                .addParameter(TypeName.LONG, "offset")
                .addParameter(TypeName.LONG, "length")
                .addStatement("throw new $T($S)", UnsupportedOperationException.class, BUFFER_UNREAD)
                .build());
        type.addMethod(override("peek")
                .returns(LOANED_BUFFER)
                .addParameter(TypeName.LONG, "offset")
                .addParameter(TypeName.LONG, "length")
                .addStatement("throw new $T($S)", UnsupportedOperationException.class, BUFFER_UNREAD)
                .build());
        type.addMethod(override("setSize")
                .addParameter(TypeName.LONG, "newSize")
                .addStatement("throw new $T($S)", UnsupportedOperationException.class, BUFFER_UNREAD)
                .build());

        // --- MemoryAllocator: the decoding context requires one to exist, not to allocate.
        type.addMethod(override("allocate")
                .returns(LOANED_BUFFER)
                .addParameter(ALLOCATION_HINT, "hint")
                .addStatement("throw new $T($S)", UnsupportedOperationException.class, ALLOCATOR_UNUSED)
                .build());
        type.addMethod(override("allocateNetwork")
                .returns(LOANED_BUFFER)
                .addParameter(TypeName.INT, "estimatedBytes")
                .addStatement("throw new $T($S)", UnsupportedOperationException.class, ALLOCATOR_UNUSED)
                .build());
        type.addMethod(override("allocateCarrierSlab")
                .returns(LOANED_BUFFER)
                .addParameter(TypeName.INT, "carrierIndex")
                .addStatement("throw new $T($S)", UnsupportedOperationException.class, ALLOCATOR_UNUSED)
                .build());
        type.addMethod(override("allocateInfrastructure")
                .returns(LOANED_BUFFER)
                .addParameter(TypeName.LONG, "sizeBytes")
                .addStatement("throw new $T($S)", UnsupportedOperationException.class, ALLOCATOR_UNUSED)
                .build());
        type.addMethod(override("stats")
                .returns(MEMORY_STATS)
                .addStatement("throw new $T($S)", UnsupportedOperationException.class, ALLOCATOR_UNUSED)
                .build());

        return new GeneratedFile(packageName, RECORDING_REQUEST_BODY,
                KernelScaffold.render(packageName, type.build()), ArtifactType.TEST);
    }

    private static final String BUFFER_UNREAD =
            "the request buffer is never read — the decoder bound for a generated test answers"
                    + " with a staged object";

    private static final String ALLOCATOR_UNUSED =
            "the decoding context requires an allocator to exist, not to allocate — nothing in a"
                    + " generated test allocates";

    /** A no-arg {@code LoanedBuffer} accessor that would hand out bytes nobody staged. */
    private static MethodSpec unreadBuffer(String name, TypeName returnType) {
        return override(name)
                .returns(returnType)
                .addStatement("throw new $T($S)", UnsupportedOperationException.class, BUFFER_UNREAD)
                .build();
    }
}
