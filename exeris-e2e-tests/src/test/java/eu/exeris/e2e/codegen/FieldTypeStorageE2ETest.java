package eu.exeris.e2e.codegen;

import eu.exeris.e2e.codegen.compile.GeneratedTree;
import eu.exeris.e2e.codegen.compile.ProcessorCompiler;
import eu.exeris.kernel.community.testkit.persistence.EmbeddedPersistenceEngineFixture;
import eu.exeris.kernel.community.testkit.persistence.EmbeddedPersistenceEngineFixtures;
import eu.exeris.kernel.core.persistence.TransactionOrchestrator;
import eu.exeris.kernel.spi.persistence.TransactionalExecutor;
import eu.exeris.tooling.codegen.java.CodegenPipeline;
import eu.exeris.tooling.codegen.java.kernel.UnpersistableFieldTypeException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The scalar field types whose storage is easy to get wrong, from source to a stored row and back on
 * in-memory H2 over the emitted migration: wrapper types holding {@code null}, {@code Short},
 * {@code Byte} and {@code Float} through their typed binds, {@code BigDecimal} and {@code LocalDate}
 * through a cast placeholder, and finders on {@code Instant} and {@code LocalDateTime}. A {@code List}
 * field gets no finder, and a field type with no column encoding fails generation.
 *
 * <p>H2 converts a string parameter to a numeric or date column silently, so this test cannot see a
 * missing cast; {@code PostgresTypeMatrixE2ETest} (opt-in) is where that shows. What it proves on
 * every build is that the cast, the typed binds and the {@code NULL} handling run and round-trip on a
 * second engine.
 */
@Tag("e2e")
@Tag("codegen")
@DisplayName("Field types on H2: wrapper nulls, Short/Byte/Float, cast placeholders and finders round-trip")
class FieldTypeStorageE2ETest {

    private static final String BASE_PACKAGE = "com.yard";

    private static final Instant PICKED = Instant.parse("2026-10-06T10:15:30Z");

    /** Field name → its value in the written row, in declaration order. */
    private static final Map<String, Object> VALUES = values();

    @TempDir
    static Path workspace;

    private static GeneratedTree app;
    private static EmbeddedPersistenceEngineFixture database;
    private static Object repository;
    private static Class<?> entityType;

    @BeforeAll
    static void generateCompileAndMigrate() throws Exception {
        app = GeneratedTree.build(workspace.resolve("app"), BASE_PACKAGE, Map.of(
                "com/yard/domain/Gauge.java", gaugeSource()), Map.of());
        String migration = migration(app.generatedRoot(), "__create_gauges");

        database = EmbeddedPersistenceEngineFixtures.inMemoryH2();
        database.start();
        TransactionalExecutor executor = new TransactionOrchestrator(database.engine());
        // TIMESTAMPTZ and the column DEFAULT restated in H2's spelling, as ZonedTemporalFieldE2ETest does.
        String h2Migration = migration
                .replace("TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE")
                .replace("PRIMARY KEY DEFAULT gen_random_uuid()", "DEFAULT RANDOM_UUID() PRIMARY KEY");
        executor.executeManaged(connection -> statements(h2Migration).forEach(connection::executeUpdate));
        entityType = app.loader().loadClass(BASE_PACKAGE + ".domain.Gauge");
        repository = app.loader().loadClass(BASE_PACKAGE + ".repository.GaugeRepository")
                .getConstructor(TransactionalExecutor.class)
                .newInstance(executor);
    }

    @AfterAll
    static void close() throws IOException {
        if (database != null) {
            database.close();
        }
        if (app != null) {
            app.close();
        }
    }

    @Test
    @DisplayName("a row whose wrapper fields are all null saves, and reads back with every field null")
    void nullWrappersRoundTrip() throws Exception {
        Object empty = entityType.getConstructor().newInstance();

        Object read = findById((UUID) get(invoke("save", empty), "getId"));

        for (String field : VALUES.keySet()) {
            assertThat(get(read, getter(field))).as(field).isNull();
        }
    }

    @Test
    @DisplayName("every value reads back as written")
    void valuesRoundTrip() throws Exception {
        Object read = findById(saveAll());

        for (Map.Entry<String, Object> value : VALUES.entrySet()) {
            Object actual = get(read, getter(value.getKey()));
            if (value.getValue() instanceof BigDecimal expected) {
                assertThat((BigDecimal) actual).as(value.getKey()).isEqualByComparingTo(expected);
            } else {
                assertThat(actual).as(value.getKey()).isEqualTo(value.getValue());
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"layers", "grade", "ratio", "weight", "shippedOn", "pickedAt", "scheduledFor", "count", "active"})
    @DisplayName("the finder on each field returns the row written with its value")
    void findersMatch(String field) throws Exception {
        UUID id = saveAll();

        List<?> found = (List<?>) invoke("findBy" + Character.toUpperCase(field.charAt(0)) + field.substring(1),
                VALUES.get(field));

        assertThat(found).extracting(row -> get(row, "getId")).contains(id);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"layers=4", "grade=2", "ratio=0.75", "weight=12.5", "shippedOn=2026-10-06"})
    @DisplayName("the list filter on each field returns the row written with its value")
    void listFiltersMatch(String filter) throws Exception {
        UUID id = saveAll();
        Object query = app.loader().loadClass(BASE_PACKAGE + ".repository.GaugeListQuery")
                .getMethod("parse", String.class).invoke(null, filter + "&size=100");

        List<?> content = (List<?>) get(invoke("findPage", query), "content");

        assertThat(content).extracting(row -> get(row, "getId")).contains(id);
    }

    @Test
    @DisplayName("a List field gets no finder")
    void listFieldHasNoFinder() {
        assertThat(Stream.of(repository.getClass().getMethods()).map(Method::getName))
                .contains("findByLayers")
                .doesNotContain("findByTags");
    }

    @Test
    @DisplayName("EXT-GEN-3003: LocalTime, Duration, char, byte[] and List-of-parameterised fields fail the run, and nothing is written")
    void typesWithoutAColumnEncodingAreRefused() throws IOException {
        Path refused = workspace.resolve("refused");
        Path classes = refused.resolve("target/classes");
        Path generated = refused.resolve("src/main/generated/java");
        ProcessorCompiler.compile(refused.resolve("src/main/java"), classes, null, Map.of(
                "com/yard/domain/Shift.java", shiftSource()));

        assertThatThrownBy(() -> CodegenPipeline.createDefault()
                .run(classes.resolve("exeris-metadata"), generated, BASE_PACKAGE))
                .isInstanceOf(UnpersistableFieldTypeException.class)
                .hasMessageStartingWith("[Exeris] EXT-GEN-3003: ")
                .hasMessageContaining("com.yard.domain.Shift.startsAt : java.time.LocalTime")
                .hasMessageContaining("com.yard.domain.Shift.length : java.time.Duration")
                .hasMessageContaining("com.yard.domain.Shift.code : char")
                .hasMessageContaining("com.yard.domain.Shift.badge : byte[]")
                .hasMessageContaining("com.yard.domain.Shift.labels : java.util.List<java.util.Map<")
                .hasMessageContaining("com.yard.domain.Shift.grid : java.util.List<java.util.List<java.lang.String>>"
                        + " (a List element must be a plain type)");
        assertThat(generated).doesNotExist();
    }

    // ── harness ──────────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> values() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("count", 5_000_000_000L);
        values.put("level", 7);
        values.put("active", Boolean.TRUE);
        values.put("reading", 1.5d);
        values.put("layers", (short) 4);
        values.put("grade", (byte) 2);
        values.put("ratio", 0.75f);
        values.put("weight", new BigDecimal("12.5"));
        values.put("shippedOn", LocalDate.of(2026, 10, 6));
        values.put("pickedAt", PICKED);
        values.put("scheduledFor", LocalDateTime.ofInstant(PICKED, ZoneOffset.UTC));
        values.put("tags", List.of("a", "b"));
        return values;
    }

    private static UUID saveAll() throws Exception {
        Object entity = entityType.getConstructor().newInstance();
        for (Map.Entry<String, Object> value : VALUES.entrySet()) {
            Method getter = entityType.getMethod(getter(value.getKey()));
            entityType.getMethod(setter(value.getKey()), getter.getReturnType()).invoke(entity, value.getValue());
        }
        return (UUID) get(invoke("save", entity), "getId");
    }

    private static Object findById(UUID id) throws Exception {
        Optional<?> found = (Optional<?>) invoke("findById", id);
        assertThat(found).as("the saved row is found by its id").isPresent();
        return found.orElseThrow();
    }

    private static Object invoke(String method, Object argument) throws Exception {
        for (Method m : repository.getClass().getMethods()) {
            if (m.getName().equals(method) && m.getParameterCount() == 1) {
                try {
                    return m.invoke(repository, argument);
                } catch (InvocationTargetException e) {
                    if (e.getCause() instanceof Exception cause) {
                        throw cause;
                    }
                    throw e;
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

    private static String getter(String field) {
        return "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
    }

    private static String setter(String field) {
        return "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
    }

    /** The migration's statements, its comment lines dropped. */
    private static List<String> statements(String sql) {
        String code = sql.lines().filter(line -> !line.strip().startsWith("--"))
                .reduce("", (a, b) -> a + b + "\n");
        return Stream.of(code.split(";")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private static String migration(Path generated, String fragment) throws IOException {
        try (Stream<Path> files = Files.walk(generated.resolve("db/migration"))) {
            Path file = files.filter(p -> p.getFileName().toString().contains(fragment))
                    .filter(p -> p.toString().endsWith(".sql"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no migration matching '" + fragment + "'"));
            return Files.readString(file);
        }
    }

    private static String gaugeSource() {
        StringBuilder fields = new StringBuilder();
        StringBuilder accessors = new StringBuilder();
        Map<String, String> types = new LinkedHashMap<>();
        types.put("count", "Long");
        types.put("level", "Integer");
        types.put("active", "Boolean");
        types.put("reading", "Double");
        types.put("layers", "Short");
        types.put("grade", "Byte");
        types.put("ratio", "Float");
        types.put("weight", "BigDecimal");
        types.put("shippedOn", "LocalDate");
        types.put("pickedAt", "Instant");
        types.put("scheduledFor", "LocalDateTime");
        types.put("tags", "List<String>");
        types.forEach((name, type) -> {
            fields.append("    private ").append(type).append(' ').append(name).append(";\n");
            accessors.append("    public ").append(type).append(' ').append(getter(name)).append("() { return ")
                    .append(name).append("; }\n")
                    .append("    public void ").append(setter(name)).append('(').append(type).append(' ')
                    .append(name).append(") { this.").append(name).append(" = ").append(name).append("; }\n");
        });
        return """
                package com.yard.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import java.math.BigDecimal;
                import java.time.Instant;
                import java.time.LocalDate;
                import java.time.LocalDateTime;
                import java.util.List;
                import java.util.UUID;

                @ExerisDomain(module = "yard", path = "/gauges", dataScope = ExerisDomain.DataScope.GLOBAL)
                public class Gauge {

                    private UUID id;
                %s
                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                %s}
                """.formatted(fields, accessors);
    }

    private static String shiftSource() {
        return """
                package com.yard.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import java.time.Duration;
                import java.time.LocalTime;
                import java.util.List;
                import java.util.Map;
                import java.util.UUID;

                @ExerisDomain(module = "yard", path = "/shifts", dataScope = ExerisDomain.DataScope.GLOBAL)
                public class Shift {

                    private UUID id;
                    private String name;
                    private LocalTime startsAt;
                    private Duration length;
                    private char code;
                    private byte[] badge;
                    private List<Map<String, String>> labels;
                    private List<List<String>> grid;

                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                    public LocalTime getStartsAt() { return startsAt; }
                    public void setStartsAt(LocalTime startsAt) { this.startsAt = startsAt; }
                    public Duration getLength() { return length; }
                    public void setLength(Duration length) { this.length = length; }
                    public char getCode() { return code; }
                    public void setCode(char code) { this.code = code; }
                    public byte[] getBadge() { return badge; }
                    public void setBadge(byte[] badge) { this.badge = badge; }
                    public List<Map<String, String>> getLabels() { return labels; }
                    public void setLabels(List<Map<String, String>> labels) { this.labels = labels; }
                    public List<List<String>> getGrid() { return grid; }
                    public void setGrid(List<List<String>> grid) { this.grid = grid; }
                }
                """;
    }
}
