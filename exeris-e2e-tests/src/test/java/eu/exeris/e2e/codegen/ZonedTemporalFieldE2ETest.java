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

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code OffsetDateTime} and {@code ZonedDateTime} entity fields, from source to a stored row and
 * back: javac + the processor write the metadata, the pipeline emits the tree, javac compiles the
 * whole of it, and the emitted repository writes and reads a row through the kernel's persistence
 * engine on in-memory H2 over the emitted migration.
 *
 * <p>The column holds the instant. A value written at {@code +02:00}, or in {@code Europe/Warsaw},
 * reads back as the same instant at {@code ZoneOffset.UTC}; the finder compares instants, so a
 * value naming the same instant at another offset finds the row.
 *
 * <p>The refusal half: a field type with no column encoding fails the run with
 * {@code EXT-GEN-3003} before anything is written.
 */
@Tag("e2e")
@Tag("codegen")
@DisplayName("OffsetDateTime / ZonedDateTime fields: compile, migrate to TIMESTAMPTZ, round-trip their instant on H2")
class ZonedTemporalFieldE2ETest {

    private static final String BASE_PACKAGE = "com.ship";

    private static final OffsetDateTime DEPARTED =
            OffsetDateTime.of(2026, 10, 4, 10, 15, 30, 0, ZoneOffset.ofHours(2));
    private static final ZonedDateTime ARRIVED =
            ZonedDateTime.of(2026, 10, 5, 8, 30, 0, 0, ZoneId.of("Europe/Warsaw"));

    @TempDir
    static Path workspace;

    private static GeneratedTree app;
    private static String migration;
    private static EmbeddedPersistenceEngineFixture database;
    private static Object repository;

    @BeforeAll
    static void generateCompileAndMigrate() throws Exception {
        app = GeneratedTree.build(workspace.resolve("app"), BASE_PACKAGE, domainSources(), Map.of());
        migration = migration(app.generatedRoot(), "__create_shipments");

        database = EmbeddedPersistenceEngineFixtures.inMemoryH2();
        database.start();
        TransactionalExecutor executor = new TransactionOrchestrator(database.engine());
        // Through the engine's own connection, with two PostgreSQL spellings restated in H2's:
        // TIMESTAMPTZ is PostgreSQL's alias for TIMESTAMP WITH TIME ZONE, which H2 knows only by
        // the standard name, and H2 takes a column's DEFAULT before PRIMARY KEY and names the UUID
        // function RANDOM_UUID. Nothing else in the emitted migration is changed.
        String h2Migration = migration
                .replace("TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE")
                .replace("PRIMARY KEY DEFAULT gen_random_uuid()", "DEFAULT RANDOM_UUID() PRIMARY KEY");
        executor.executeManaged(connection -> statements(h2Migration).forEach(connection::executeUpdate));
        repository = app.loader().loadClass(BASE_PACKAGE + ".repository.ShipmentRepository")
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
    @DisplayName("the migration types both columns TIMESTAMPTZ")
    void bothColumnsAreTimestampTz() {
        assertThat(migration)
                .contains("departed_at TIMESTAMPTZ")
                .contains("arrived_at TIMESTAMPTZ");
    }

    @Test
    @DisplayName("save + findById: each value reads back as the same instant, at UTC")
    void valuesRoundTripAsTheirInstant() throws Exception {
        Object saved = save("outbound", DEPARTED, ARRIVED);

        Object read = findById((UUID) get(saved, "getId"));
        OffsetDateTime departed = (OffsetDateTime) get(read, "getDepartedAt");
        ZonedDateTime arrived = (ZonedDateTime) get(read, "getArrivedAt");

        assertThat(departed.toInstant()).isEqualTo(DEPARTED.toInstant());
        assertThat(departed.getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(arrived.toInstant()).isEqualTo(ARRIVED.toInstant());
        assertThat(arrived.getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("null values are stored as SQL NULL and read back as null")
    void nullValuesRoundTrip() throws Exception {
        Object saved = save("unscheduled", null, null);

        Object read = findById((UUID) get(saved, "getId"));

        assertThat(get(read, "getDepartedAt")).isNull();
        assertThat(get(read, "getArrivedAt")).isNull();
    }

    @Test
    @DisplayName("the finder matches on the instant: the same instant at another offset finds the row")
    void finderComparesInstants() throws Exception {
        OffsetDateTime departed = OffsetDateTime.of(2026, 11, 1, 6, 0, 0, 0, ZoneOffset.ofHours(-5));
        UUID id = (UUID) get(save("finder", departed, null), "getId");

        List<?> found = (List<?>) invoke("findByDepartedAt", OffsetDateTime.class,
                departed.withOffsetSameInstant(ZoneOffset.ofHours(9)));

        assertThat(found).extracting(row -> get(row, "getId")).containsExactly(id);
    }

    @Test
    @DisplayName("EXT-GEN-3003: a Map or Set field fails the run with the GEN id, and nothing is written")
    void parameterisedNonListFieldIsRefused() throws IOException {
        Path refused = workspace.resolve("refused");
        Path classes = refused.resolve("target/classes");
        Path generated = refused.resolve("src/main/generated/java");
        ProcessorCompiler.compile(refused.resolve("src/main/java"), classes, null, refusedSources());

        assertThatThrownBy(() -> CodegenPipeline.createDefault()
                .run(classes.resolve("exeris-metadata"), generated, BASE_PACKAGE))
                .isInstanceOf(UnpersistableFieldTypeException.class)
                .hasMessageStartingWith("[Exeris] EXT-GEN-3003: ")
                .hasMessageContaining("com.ship.domain.Manifest.attributes : "
                        + "java.util.Map<java.lang.String,java.lang.String>")
                .hasMessageContaining("com.ship.domain.Manifest.seals : java.util.Set<java.util.UUID>");
        assertThat(generated).doesNotExist();
    }

    // ── harness ──────────────────────────────────────────────────────────────────────────────

    private static Object save(String name, OffsetDateTime departed, ZonedDateTime arrived) throws Exception {
        Object entity = app.loader().loadClass(BASE_PACKAGE + ".domain.Shipment")
                .getConstructor().newInstance();
        entity.getClass().getMethod("setName", String.class).invoke(entity, name);
        entity.getClass().getMethod("setDepartedAt", OffsetDateTime.class).invoke(entity, departed);
        entity.getClass().getMethod("setArrivedAt", ZonedDateTime.class).invoke(entity, arrived);
        return invoke("save", entity.getClass(), entity);
    }

    private static Object findById(UUID id) throws Exception {
        Optional<?> found = (Optional<?>) invoke("findById", UUID.class, id);
        assertThat(found).as("the saved row is found by its id").isPresent();
        return found.orElseThrow();
    }

    private static Object invoke(String method, Class<?> parameterType, Object argument) throws Exception {
        try {
            return repository.getClass().getMethod(method, parameterType).invoke(repository, argument);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    private static Object get(Object target, String getter) {
        try {
            return target.getClass().getMethod(getter).invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot call " + getter, e);
        }
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

    private static Map<String, String> domainSources() {
        return Map.of("com/ship/domain/Shipment.java", """
                package com.ship.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;
                import java.time.OffsetDateTime;
                import java.time.ZonedDateTime;
                import java.util.UUID;

                @ExerisDomain(module = "logistics", path = "/shipments",
                              dataScope = ExerisDomain.DataScope.GLOBAL)
                public class Shipment {

                    private UUID id;

                    @Field(label = "Name")
                    private String name;

                    private OffsetDateTime departedAt;
                    private ZonedDateTime arrivedAt;

                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                    public OffsetDateTime getDepartedAt() { return departedAt; }
                    public void setDepartedAt(OffsetDateTime departedAt) { this.departedAt = departedAt; }
                    public ZonedDateTime getArrivedAt() { return arrivedAt; }
                    public void setArrivedAt(ZonedDateTime arrivedAt) { this.arrivedAt = arrivedAt; }
                }
                """);
    }

    private static Map<String, String> refusedSources() {
        return Map.of("com/ship/domain/Manifest.java", """
                package com.ship.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import java.util.Map;
                import java.util.Set;
                import java.util.UUID;

                @ExerisDomain(module = "logistics", path = "/manifests",
                              dataScope = ExerisDomain.DataScope.GLOBAL)
                public class Manifest {

                    private UUID id;
                    private Map<String, String> attributes;
                    private Set<UUID> seals;

                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public Map<String, String> getAttributes() { return attributes; }
                    public void setAttributes(Map<String, String> attributes) { this.attributes = attributes; }
                    public Set<UUID> getSeals() { return seals; }
                    public void setSeals(Set<UUID> seals) { this.seals = seals; }
                }
                """);
    }
}
