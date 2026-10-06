package eu.exeris.e2e.codegen;

import eu.exeris.e2e.codegen.compile.GeneratedTree;
import eu.exeris.kernel.community.testkit.persistence.EmbeddedPersistenceEngineFixture;
import eu.exeris.kernel.community.testkit.persistence.EmbeddedPersistenceEngineFixtures;
import eu.exeris.kernel.core.persistence.TransactionOrchestrator;
import eu.exeris.kernel.spi.persistence.TransactionalExecutor;
import eu.exeris.tooling.codegen.java.kernel.UnpersistableFieldTypeException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Every field type the repository generator stores, written, read, updated, found and listed
 * through the emitted repository on a real PostgreSQL over the emitted migration, and every type it
 * refuses, refused. <b>Opt-in</b>: skipped unless {@code -Dexeris.e2e.postgres.url} names a JDBC URL
 * (credentials included) for a role that may create a schema. The run works in its own schema,
 * {@value #SCHEMA}, which it drops before and after.
 *
 * {@snippet lang="shell" :
 * mvn -pl exeris-e2e-tests -am test -Dtest=PostgresTypeMatrixE2ETest -Dsurefire.failIfNoSpecifiedTests=false \
 *     -Dexeris.e2e.postgres.url='jdbc:postgresql://localhost:5432/postgres?user=postgres&password=…'
 * }
 *
 * <p>PostgreSQL types a {@code bindString} parameter {@code character varying} and refuses it
 * against a {@code numeric} or {@code date} column ({@code 42804}) and in an equality with one
 * ({@code 42883}); H2 converts silently. This matrix is where a value bound in the wrong encoding is
 * visible.
 *
 * <p>Each type is its own {@code GLOBAL} entity with one field, {@code probe}, so one cell's failure
 * cannot mask another's and no row-level security is involved. Each type is a row, each repository
 * operation a cell: {@code insert} ({@code save}), {@code insertNull} ({@code save} with the field
 * null, then {@code findById} reads it back null — wrapper types only), {@code read}
 * ({@code findById}, value compared), {@code update}, {@code finder} ({@code findByProbe}, or its
 * absence for a {@code List}), {@code filter} and {@code sort} ({@code findPage}, and whether the type
 * is offered at all is asserted, not skipped). When {@code save} fails, the row is seeded through an
 * untyped SQL literal so the remaining cells are still measured. Every cell must be {@code OK}.
 *
 * <p>{@code -Dexeris.e2e.postgres.matrixReport=<file>} also writes the matrix as a Markdown table.
 */
@Tag("e2e")
@Tag("postgres")
@DisplayName("Field types on PostgreSQL (opt-in): every stored type round-trips through the emitted repository")
class PostgresTypeMatrixE2ETest {

    private static final String URL_PROPERTY = "exeris.e2e.postgres.url";
    private static final String REPORT_PROPERTY = "exeris.e2e.postgres.matrixReport";
    private static final String SCHEMA = "exeris_type_matrix_e2e";
    private static final String BASE_PACKAGE = "com.lab";
    private static final String OK = "OK";
    private static final String NOT_OFFERED = "-";
    private static final List<String> OPERATIONS =
            List.of("insert", "insertNull", "read", "update", "finder", "filter", "sort");

    @TempDir
    static Path workspace;

    private static final Map<String, Map<String, String>> MATRIX = new LinkedHashMap<>();
    private static final Map<String, Map<String, String>> EXPECTED = new LinkedHashMap<>();
    private static final List<GeneratedTree> TREES = new ArrayList<>();
    private static String adminUrl;
    private static String schemaUrl;
    private static EmbeddedPersistenceEngineFixture database;

    /**
     * One measured type: its field declaration, two distinct values, a SQL literal for the first,
     * how a value is written as a query parameter, and what the list route and finder offer for it.
     */
    private record Probe(String entity, String type, List<String> imports, boolean boxed,
                         Function<ClassLoader, Object> first, Function<ClassLoader, Object> second,
                         String firstSqlLiteral, Function<Object, String> queryValue,
                         boolean finder, boolean filterable, boolean sortable) {
    }

    @BeforeAll
    static void measure() throws Throwable {
        String url = System.getProperty(URL_PROPERTY);
        assumeTrue(url != null && !url.isBlank(),
                "opt-in: set -D" + URL_PROPERTY + "=<jdbc:postgresql://…> to run the PostgreSQL type matrix");
        adminUrl = url;
        schemaUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;

        try (Connection admin = DriverManager.getConnection(adminUrl); Statement st = admin.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            st.execute("CREATE SCHEMA " + SCHEMA);
        }
        database = EmbeddedPersistenceEngineFixtures.forJdbcUrl(schemaUrl, false);
        database.start();
        TransactionalExecutor executor = new TransactionOrchestrator(database.engine());

        GeneratedTree tree = GeneratedTree.build(workspace.resolve("matrix"), BASE_PACKAGE, sources(), Map.of());
        TREES.add(tree);
        try (Connection admin = DriverManager.getConnection(schemaUrl); Statement st = admin.createStatement()) {
            for (Path migration : migrations(tree.generatedRoot())) {
                st.execute(Files.readString(migration));
            }
            measureRelationship(tree, executor, admin);
            for (Probe probe : probes()) {
                measure(tree, executor, admin, probe);
            }
        }
        writeReport();
    }

    @AfterAll
    static void close() throws IOException, SQLException {
        if (database != null) {
            database.close();
        }
        for (GeneratedTree tree : TREES) {
            tree.close();
        }
        if (adminUrl != null) {
            try (Connection admin = DriverManager.getConnection(adminUrl); Statement st = admin.createStatement()) {
                st.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            }
        }
    }

    @TestFactory
    @DisplayName("every type × operation cell is as expected")
    Stream<DynamicTest> cells() {
        return MATRIX.entrySet().stream().flatMap(row -> OPERATIONS.stream().map(op -> DynamicTest.dynamicTest(
                row.getKey() + " × " + op,
                () -> assertThat(row.getValue().getOrDefault(op, NOT_OFFERED)).as(row.getKey() + " × " + op)
                        .isEqualTo(EXPECTED.get(row.getKey()).getOrDefault(op, NOT_OFFERED)))));
    }

    @TestFactory
    @DisplayName("a type the repository cannot store fails generation with EXT-GEN-3003, naming the field")
    Stream<DynamicTest> refusals() {
        return Stream.of(
                        new String[] {"LocalTime", "java.time.LocalTime", "java.time.LocalTime"},
                        new String[] {"Duration", "java.time.Duration", "java.time.Duration"},
                        new String[] {"char", null, "char"},
                        new String[] {"Character", null, "java.lang.Character"},
                        new String[] {"byte[]", null, "byte[]"},
                        new String[] {"BigInteger", "java.math.BigInteger", "java.math.BigInteger"})
                .map(refused -> DynamicTest.dynamicTest(refused[0], () -> {
                    String entity = "Refused" + refused[0].replaceAll("[^A-Za-z]", "");
                    List<String> imports = refused[1] == null ? List.of() : List.of(refused[1]);
                    Map<String, String> sources = Map.of("com/lab/domain/" + entity + ".java",
                            entitySource(entity, refused[0], imports, ""));
                    Path dir = workspace.resolve("refused-" + entity.toLowerCase(Locale.ROOT));
                    assertThatThrownBy(() -> GeneratedTree.build(dir, BASE_PACKAGE, sources, Map.of()))
                            .isInstanceOf(UnpersistableFieldTypeException.class)
                            .hasMessageStartingWith("[Exeris] EXT-GEN-3003: ")
                            .hasMessageContaining("com.lab.domain." + entity + ".probe : " + refused[2]);
                }));
    }

    // ── measurement ──────────────────────────────────────────────────────────────────────────

    private static Map<String, String> sources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("com/lab/domain/Grade.java", "package com.lab.domain;\n\npublic enum Grade { A, B }\n");
        sources.put("com/lab/domain/Owner.java", entitySource("Owner", "String", List.of(), ""));
        sources.put("com/lab/domain/RefBox.java", entitySource("RefBox", "UUID", List.of(),
                "    @Relationship(targetEntity = Owner.class, displayField = \"probe\")\n"));
        for (Probe probe : probes()) {
            sources.put("com/lab/domain/" + probe.entity() + ".java",
                    entitySource(probe.entity(), probe.type(), probe.imports(), ""));
        }
        return sources;
    }

    private static void measureRelationship(GeneratedTree tree, TransactionalExecutor executor, Connection admin)
            throws Throwable {
        Object owners = repository(tree, executor, "Owner");
        Object owner = tree.loader().loadClass(BASE_PACKAGE + ".domain.Owner").getConstructor().newInstance();
        owner.getClass().getMethod("setProbe", String.class).invoke(owner, "owner-1");
        UUID ownerId = (UUID) get(invoke(owners, "save", owner), "getId");
        measure(tree, executor, admin, new Probe("RefBox", "UUID @Relationship (MANY_TO_ONE fk)", List.of(), true,
                l -> ownerId, l -> ownerId, "'" + ownerId + "'", Object::toString, true, true, true));
    }

    private static void measure(GeneratedTree tree, TransactionalExecutor executor, Connection admin, Probe probe)
            throws Exception {
        Map<String, String> row = MATRIX.computeIfAbsent(probe.type(), k -> new LinkedHashMap<>());
        Map<String, String> expected = EXPECTED.computeIfAbsent(probe.type(), k -> new LinkedHashMap<>());
        expected.put("insert", OK);
        expected.put("read", OK);
        expected.put("update", OK);
        expected.put("finder", OK);
        if (probe.boxed()) {
            expected.put("insertNull", OK);
        }
        if (probe.filterable()) {
            expected.put("filter", OK);
        }
        if (probe.sortable()) {
            expected.put("sort", OK);
        }

        Object repo = repository(tree, executor, probe.entity());
        Class<?> entityType = tree.loader().loadClass(BASE_PACKAGE + ".domain." + probe.entity());
        Method getter = getter(entityType);
        Method setter = entityType.getMethod("setProbe", getter.getReturnType());
        Object a = probe.first().apply(tree.loader());
        Object b = probe.second().apply(tree.loader());

        Object entity = entityType.getConstructor().newInstance();
        setter.invoke(entity, a);
        Object[] saved = new Object[1];
        row.put("insert", cell(() -> saved[0] = invoke(repo, "save", entity)));
        UUID id;
        if (saved[0] != null) {
            id = (UUID) get(saved[0], "getId");
        } else {
            id = UUID.randomUUID();
            try (Statement st = admin.createStatement()) {
                st.execute("INSERT INTO " + table(tree, probe.entity()) + " (id, probe) VALUES ('" + id + "', "
                        + probe.firstSqlLiteral() + ")");
            }
        }

        if (probe.boxed()) {
            row.put("insertNull", cell(() -> {
                Object empty = entityType.getConstructor().newInstance();
                UUID emptyId = (UUID) get(invoke(repo, "save", empty), "getId");
                Optional<?> found = (Optional<?>) invoke(repo, "findById", emptyId);
                Object value = getter.invoke(found.orElseThrow(() -> new AssertionError("findById returned empty")));
                if (value != null) {
                    throw new AssertionError("a null value read back as " + value);
                }
            }));
        }

        row.put("read", cell(() -> {
            Optional<?> found = (Optional<?>) invoke(repo, "findById", id);
            Object value = getter.invoke(found.orElseThrow(() -> new AssertionError("findById returned empty")));
            if (!same(a, value)) {
                throw new AssertionError("read back " + value + " (wrote " + a + ")");
            }
        }));

        row.put("update", cell(() -> {
            Object changed = entityType.getConstructor().newInstance();
            entityType.getMethod("setId", UUID.class).invoke(changed, id);
            setter.invoke(changed, b);
            invoke(repo, "update", id, changed);
            Object value = getter.invoke(((Optional<?>) invoke(repo, "findById", id)).orElseThrow());
            if (!same(b, value)) {
                throw new AssertionError("read back " + value + " after update to " + b);
            }
        }));
        Object current = OK.equals(row.get("update")) ? b : a;

        Method finder = Stream.of(repo.getClass().getMethods())
                .filter(m -> m.getName().equals("findByProbe")).findFirst().orElse(null);
        if (!probe.finder()) {
            row.put("finder", finder == null ? OK : "a finder was emitted for a JSON column");
        } else {
            row.put("finder", finder == null ? "no finder emitted" : cell(() -> {
                List<?> found = (List<?>) invoke(repo, "findByProbe", current);
                if (found.stream().noneMatch(e -> id.equals(get(e, "getId")))) {
                    throw new AssertionError("finder did not return the row (" + found.size() + " rows)");
                }
            }));
        }

        Class<?> queryType = tree.loader().loadClass(BASE_PACKAGE + ".repository." + probe.entity() + "ListQuery");
        Class<?> filterType = tree.loader().loadClass(
                BASE_PACKAGE + ".repository." + probe.entity() + "ListQuery$Filter");
        boolean filterable = Stream.of(filterType.getRecordComponents()).anyMatch(c -> c.getName().equals("probe"));
        @SuppressWarnings("unchecked")
        boolean sortable = ((List<Object>) queryType.getField("SORTABLE").get(null)).contains("probe");
        row.put("filter", !filterable ? NOT_OFFERED : cell(() -> {
            Object page = page(repo, queryType, "probe=" + URLEncoder.encode(
                    probe.queryValue().apply(current), StandardCharsets.UTF_8));
            List<?> content = (List<?>) get(page, "content");
            if (content.stream().noneMatch(e -> id.equals(get(e, "getId")))) {
                throw new AssertionError("filter did not return the row (" + content.size() + " rows)");
            }
        }));
        row.put("sort", !sortable ? NOT_OFFERED : cell(() -> {
            page(repo, queryType, "sort=probe,desc");
            page(repo, queryType, "sort=probe,asc");
        }));
    }

    private static Object page(Object repo, Class<?> queryType, String raw) throws Throwable {
        Object query = queryType.getMethod("parse", String.class).invoke(null, raw);
        return invoke(repo, "findPage", query);
    }

    // ── harness ──────────────────────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface Work {
        void run() throws Throwable;
    }

    private static String cell(Work work) {
        try {
            work.run();
            return OK;
        } catch (Throwable t) {
            return describe(t);
        }
    }

    /** The innermost SQLException's SQLState and message, else the outermost failure. */
    private static String describe(Throwable t) {
        Throwable cause = t instanceof InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : t;
        for (Throwable c = cause; c != null; c = c.getCause()) {
            if (c instanceof SQLException sql) {
                return "[" + sql.getSQLState() + "] " + sql.getMessage().lines().findFirst().orElse("");
            }
        }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    private static boolean same(Object expected, Object actual) {
        if (expected == null || actual == null) {
            return expected == actual;
        }
        return switch (expected) {
            case BigDecimal d when actual instanceof BigDecimal e -> d.compareTo(e) == 0;
            case OffsetDateTime d when actual instanceof OffsetDateTime e -> d.isEqual(e);
            case ZonedDateTime d when actual instanceof ZonedDateTime e -> d.toInstant().equals(e.toInstant());
            default -> expected.equals(actual);
        };
    }

    private static Object invoke(Object target, String method, Object... args) throws Throwable {
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals(method) && m.getParameterCount() == args.length) {
                try {
                    return m.invoke(target, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
        }
        throw new NoSuchMethodException(method);
    }

    private static Object get(Object target, String getter) {
        try {
            return target.getClass().getMethod(getter).invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot call " + getter, e);
        }
    }

    private static Method getter(Class<?> entityType) throws NoSuchMethodException {
        try {
            return entityType.getMethod("getProbe");
        } catch (NoSuchMethodException e) {
            return entityType.getMethod("isProbe");
        }
    }

    private static Object repository(GeneratedTree tree, TransactionalExecutor executor, String entity)
            throws ReflectiveOperationException {
        return tree.loader().loadClass(BASE_PACKAGE + ".repository." + entity + "Repository")
                .getConstructor(TransactionalExecutor.class).newInstance(executor);
    }

    /** The table the create migration for {@code entity} names: its name, underscores dropped, starts with the entity's. */
    private static String table(GeneratedTree tree, String entity) throws IOException {
        Pattern create = Pattern.compile("CREATE TABLE (?:IF NOT EXISTS )?([a-z_0-9]+)", Pattern.CASE_INSENSITIVE);
        String key = entity.toLowerCase(Locale.ROOT);
        for (Path migration : migrations(tree.generatedRoot())) {
            Matcher m = create.matcher(Files.readString(migration));
            while (m.find()) {
                if (m.group(1).replace("_", "").toLowerCase(Locale.ROOT).startsWith(key)) {
                    return m.group(1);
                }
            }
        }
        throw new AssertionError("no create migration for " + entity);
    }

    private static List<Path> migrations(Path generated) throws IOException {
        try (Stream<Path> files = Files.walk(generated.resolve("db/migration"))) {
            return files.filter(p -> p.toString().endsWith(".sql"))
                    .sorted((x, y) -> x.getFileName().toString().compareTo(y.getFileName().toString()))
                    .toList();
        }
    }

    private static void writeReport() throws IOException {
        String target = System.getProperty(REPORT_PROPERTY);
        if (target == null || target.isBlank()) {
            return;
        }
        StringBuilder out = new StringBuilder("| type | ").append(String.join(" | ", OPERATIONS)).append(" |\n");
        out.append("|---".repeat(OPERATIONS.size() + 1)).append("|\n");
        MATRIX.forEach((type, row) -> {
            out.append("| ").append(type);
            OPERATIONS.forEach(op -> out.append(" | ").append(row.getOrDefault(op, NOT_OFFERED)));
            out.append(" |\n");
        });
        Path report = Path.of(target);
        if (report.getParent() != null) {
            Files.createDirectories(report.getParent());
        }
        Files.writeString(report, out.toString());
    }

    private static String entitySource(String entity, String type, List<String> imports, String annotation) {
        StringBuilder importLines = new StringBuilder();
        for (String i : imports) {
            importLines.append("import ").append(i).append(";\n");
        }
        String javaType = type.startsWith("UUID") ? "UUID" : type;
        String accessor = "boolean".equals(javaType) ? "isProbe" : "getProbe";
        return """
                package com.lab.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Relationship;
                import java.util.UUID;
                %s
                @ExerisDomain(module = "lab", path = "/%s", dataScope = ExerisDomain.DataScope.GLOBAL)
                public class %s {

                    private UUID id;

                %s    private %s probe;

                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public %s %s() { return probe; }
                    public void setProbe(%s probe) { this.probe = probe; }
                }
                """.formatted(importLines, entity.toLowerCase(Locale.ROOT), entity, annotation, javaType,
                javaType, accessor, javaType);
    }

    /** A scalar every list operation is offered for, with a finder. */
    private static Probe scalar(String entity, String type, List<String> imports, boolean boxed,
                                Object first, Object second, String literal) {
        return new Probe(entity, type, imports, boxed, l -> first, l -> second, literal,
                v -> v instanceof BigDecimal d ? d.toPlainString() : v.toString(), true, true, true);
    }

    /** A timestamp: a finder and a sort key, not a filter (equality on an instant is not offered). */
    private static Probe timestamp(String entity, String type, Object first, Object second, String literal) {
        return new Probe(entity, type, List.of("java.time.*"), true, l -> first, l -> second, literal,
                Object::toString, true, false, true);
    }

    private static List<Probe> probes() {
        UUID u1 = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID u2 = UUID.fromString("00000000-0000-4000-8000-000000000002");
        Instant i1 = Instant.parse("2026-10-06T10:15:30Z");
        Instant i2 = Instant.parse("2026-10-07T11:00:00Z");
        List<Probe> probes = new ArrayList<>(List.of(
                scalar("UuidBox", "UUID", List.of(), true, u1, u2, "'" + u1 + "'"),
                scalar("StringBox", "String", List.of(), true, "alpha", "beta", "'alpha'"),
                scalar("LongBox", "Long", List.of(), true, 5_000_000_000L, 6_000_000_000L, "5000000000"),
                scalar("LongPBox", "long", List.of(), false, 5_000_000_000L, 6_000_000_000L, "5000000000"),
                scalar("IntBox", "Integer", List.of(), true, 7, 8, "7"),
                scalar("IntPBox", "int", List.of(), false, 7, 8, "7"),
                scalar("ShortBox", "Short", List.of(), true, (short) 3, (short) 4, "3"),
                scalar("ShortPBox", "short", List.of(), false, (short) 3, (short) 4, "3"),
                scalar("ByteBox", "Byte", List.of(), true, (byte) 3, (byte) 4, "3"),
                scalar("BytePBox", "byte", List.of(), false, (byte) 3, (byte) 4, "3"),
                scalar("BoolBox", "Boolean", List.of(), true, Boolean.TRUE, Boolean.FALSE, "true"),
                scalar("BoolPBox", "boolean", List.of(), false, true, false, "true"),
                scalar("FloatBox", "Float", List.of(), true, 1.25f, 2.5f, "1.25"),
                scalar("FloatPBox", "float", List.of(), false, 1.25f, 2.5f, "1.25"),
                scalar("DoubleBox", "Double", List.of(), true, 1.5d, 2.5d, "1.5"),
                scalar("DoublePBox", "double", List.of(), false, 1.5d, 2.5d, "1.5"),
                scalar("DecimalBox", "BigDecimal", List.of("java.math.BigDecimal"), true,
                        new BigDecimal("12.34"), new BigDecimal("56.78"), "'12.34'"),
                scalar("LocalDateBox", "LocalDate", List.of("java.time.LocalDate"), true,
                        LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 7), "'2026-10-06'"),
                timestamp("InstantBox", "Instant", i1, i2, "'" + i1 + "'"),
                timestamp("LdtBox", "LocalDateTime", LocalDateTime.ofInstant(i1, ZoneOffset.UTC),
                        LocalDateTime.ofInstant(i2, ZoneOffset.UTC), "'2026-10-06T10:15:30Z'"),
                timestamp("OdtBox", "OffsetDateTime", i1.atOffset(ZoneOffset.ofHours(2)),
                        i2.atOffset(ZoneOffset.ofHours(-5)), "'" + i1 + "'"),
                timestamp("ZdtBox", "ZonedDateTime", i1.atZone(ZoneId.of("Europe/Warsaw")),
                        i2.atZone(ZoneId.of("Europe/Warsaw")), "'" + i1 + "'"),
                // A JSON column: no finder, neither filtered nor sorted.
                new Probe("ListBox", "List<String>", List.of("java.util.List"), true,
                        l -> List.of("a", "b"), l -> List.of("c"), "'[\"a\",\"b\"]'", Object::toString,
                        false, false, false)));
        probes.add(new Probe("EnumBox", "Grade", List.of(), true,
                l -> enumConstant(l, "A"), l -> enumConstant(l, "B"), "'A'", v -> ((Enum<?>) v).name(),
                true, true, true));
        return probes;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(ClassLoader loader, String name) {
        try {
            return Enum.valueOf((Class) loader.loadClass(BASE_PACKAGE + ".domain.Grade"), name);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }
}
