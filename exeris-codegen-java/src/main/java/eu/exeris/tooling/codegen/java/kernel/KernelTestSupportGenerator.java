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

    /**
     * Every shared double, in emission order.
     *
     * @param basePackage the project base package; the support types land in
     *                    {@code <basePackage>.testsupport}
     */
    public List<GeneratedFile> generateAll(String basePackage) {
        return List.of(generate(basePackage), generatePersistence(basePackage));
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
        type.addMethod(unsupported("getSegment", MEMORY_SEGMENT));
        type.addMethod(unsupported("getLength", TypeName.INT));

        return new GeneratedFile(packageName, RECORDING_PERSISTENCE,
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

    private static MethodSpec unsupported(String name, TypeName returnType) {
        return override(name)
                .returns(returnType)
                .addParameter(TypeName.INT, "column")
                .addStatement("throw new $T($S)", UnsupportedOperationException.class,
                        name + " is not recorded — no generated repository reads a column this way")
                .build();
    }

    /** {@code <basePackage>.testsupport} — where the shared doubles live. */
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
}
