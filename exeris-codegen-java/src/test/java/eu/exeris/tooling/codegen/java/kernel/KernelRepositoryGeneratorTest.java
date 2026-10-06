package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.RelationshipMetadata;
import eu.exeris.sdk.sourcemodel.ast.SystemFieldsMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Per-generator test for {@link KernelRepositoryGenerator}.
 *
 * <p>Repositories are wired against the Open-Core SPI
 * {@code TransactionalExecutor} + {@code PersistenceStatement} +
 * {@code QueryResult} + {@code RowCursor} surface (no JDBC).
 */
@DisplayName("KernelRepositoryGenerator")
class KernelRepositoryGeneratorTest {

    private KernelGeneratorStrategy strategy;

    @BeforeEach
    void setup() {
        strategy = new KernelGeneratorStrategy();
    }

    @Test
    @DisplayName("Should generate Repository wired against Open-Core SPI TransactionalExecutor")
    void shouldGenerateRepository() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(
                        FieldMetadata.builder("orderNumber", "String").build(),
                        FieldMetadata.builder("amount", "BigDecimal").build()))
                .build();

        List<GeneratedFile> files = strategy.generate(metadata);

        GeneratedFile repo = files.stream()
                .filter(f -> f.artifactType() == ArtifactType.REPOSITORY)
                .findFirst()
                .orElseThrow();

        assertThat(repo.className()).isEqualTo("OrderRepository");
        assertThat(repo.packageName()).isEqualTo("com.example.repository");
        assertThat(repo.content())
                .contains("public class OrderRepository")
                .contains("import eu.exeris.kernel.spi.persistence.TransactionalExecutor")
                .contains("import eu.exeris.kernel.spi.persistence.PersistenceStatement")
                .contains("import eu.exeris.kernel.spi.persistence.QueryResult")
                .contains("import eu.exeris.kernel.spi.persistence.RowCursor")
                .contains("private final TransactionalExecutor executor")
                .contains("public OrderRepository(TransactionalExecutor executor)")
                .contains("findById")
                .contains("findAll")
                .contains("save")
                .contains("deleteById")
                .contains("count")
                .contains("executor.query(conn ->")
                .contains("executor.executeManaged(conn ->")
                .contains("conn.prepare(sql)")
                .contains("stmt.bindUuid(0, id)")
                .contains("row.getUuid(")
                .contains("row.getString(")
                // Explicit column list — RowCursor is index-only.
                .contains("SELECT id, order_number, amount FROM orders WHERE id = ?")
                // BigDecimal is bound as text, so its placeholder casts to the migration's column type.
                .contains("INSERT INTO orders (id, order_number, amount) VALUES (?, ?, CAST(? AS DECIMAL(19,4)))")
                .doesNotContain("SELECT * FROM")
                // No JDBC residue.
                .doesNotContain("import java.sql.")
                .doesNotContain("import javax.sql")
                .doesNotContain("private final DataSource")
                .doesNotContain("dataSource.getConnection()")
                // BigDecimal binds via String (no bindBigDecimal in SPI).
                .contains("toPlainString()")
                .contains("new BigDecimal(v)");
    }

    @Test
    @DisplayName("Should emit JSON helpers (Jackson 3) when entity has List<X> fields")
    void shouldEmitJsonHelpersForListFields() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(FieldMetadata.builder("tags", "List<java.util.UUID>").build()))
                .build();

        GeneratedFile repo = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.REPOSITORY)
                .findFirst().orElseThrow();

        assertThat(repo.content())
                .contains("import tools.jackson.databind.ObjectMapper")
                .contains("import tools.jackson.core.JacksonException")
                .contains("import tools.jackson.core.type.TypeReference")
                .contains("private static final ObjectMapper MAPPER = new ObjectMapper()")
                .contains("private static <T> List<T> parseList")
                .contains("private static String toJson")
                // No Jackson 2 leakage.
                .doesNotContain("com.fasterxml.jackson");
        // The import is a compile requirement nothing else declares, so the class says so.
        assertThat(repo.content())
                .contains("<p>Compile requirement: {@code List<X>} fields are persisted as JSON")
                .contains("Declare {@code tools.jackson.core:jackson-databind}.");
        assertThat(KernelRepositoryGenerator.importsJackson(metadata)).isTrue();
    }

    @Test
    @DisplayName("Repository without List<X> fields skips Jackson helpers")
    void shouldSkipJsonHelpersWithoutListFields() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(FieldMetadata.builder("orderNumber", "String").build()))
                .build();

        GeneratedFile repo = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.REPOSITORY)
                .findFirst().orElseThrow();

        assertThat(repo.content())
                .doesNotContain("tools.jackson")
                .doesNotContain("ObjectMapper")
                .doesNotContain("TypeReference")
                .doesNotContain("parseList")
                .doesNotContain("toJson")
                .doesNotContain("Compile requirement");
        assertThat(KernelRepositoryGenerator.importsJackson(metadata)).isFalse();
    }

    @Test
    @DisplayName("Soft-delete domains filter by deleted = false on reads and use UPDATE for deletes")
    void shouldHandleSoftDelete() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .softDelete(true)
                .fields(List.of(FieldMetadata.builder("orderNumber", "String").build()))
                .build();

        GeneratedFile repo = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.REPOSITORY)
                .findFirst().orElseThrow();

        assertThat(repo.content())
                .contains("AND deleted = false")
                .contains("WHERE deleted = false")
                // deleteById excludes tombstoned rows so double-delete raises
                // "not found" — consistent with the findById/findAll filter.
                .contains("UPDATE orders SET deleted = true WHERE id = ? AND deleted = false")
                .doesNotContain("DELETE FROM orders");
    }

    @Test
    @DisplayName("Tenant-scoped + audited entity: tenant_id / created_at / updated_at columns appear in SELECT and bind chain")
    void shouldEmitTenantAndAuditedColumns() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .tenantScoped(true)
                .audited(true)
                .fields(List.of(FieldMetadata.builder("orderNumber", "String").build()))
                .build();

        GeneratedFile repo = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.REPOSITORY)
                .findFirst().orElseThrow();

        assertThat(repo.content())
                // Column layout: id + domain field + tenant_id + created_at + updated_at.
                .contains("SELECT id, order_number, tenant_id, created_at, updated_at FROM orders WHERE id = ?")
                .contains("INSERT INTO orders (id, order_number, tenant_id, created_at, updated_at)")
                // save() stamps Instant.now() onto both audited timestamps before binding.
                .contains("Instant now = Instant.now()")
                .contains("entity.setCreatedAt(now)")
                .contains("entity.setUpdatedAt(now)")
                // update() stamps only updatedAt automatically; createdAt is null-guarded.
                .contains("entity.setUpdatedAt(Instant.now())")
                // tenant_id binds via bindUuid; audited timestamps via native bindInstant,
                // null-guarded with bindNull; TIMESTAMPTZ ↔ Instant through the driver,
                // no ISO-String round-trip.
                .contains("stmt.bindUuid(2, entity.getTenantId())")
                .contains("if (entity.getCreatedAt() == null) stmt.bindNull(3); else stmt.bindInstant(3, entity.getCreatedAt());")
                .contains("if (entity.getUpdatedAt() == null) stmt.bindNull(4); else stmt.bindInstant(4, entity.getUpdatedAt());")
                // mapRow reads them back natively via row.getInstant.
                .contains("entity.setTenantId(row.getUuid(2))")
                .contains("entity.setCreatedAt(row.getInstant(3))")
                .contains("entity.setUpdatedAt(row.getInstant(4))");
    }

    @Test
    @DisplayName("T36: both write paths fill an absent tenant from the acting StorageContext")
    void shouldStampTheActingTenantOnWrites() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .tenantScoped(true)
                .audited(true)
                .fields(List.of(FieldMetadata.builder("orderNumber", "String").build()))
                .build();

        GeneratedFile repo = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.REPOSITORY)
                .findFirst().orElseThrow();

        String stamp = "if (entity.getTenantId() == null) entity.setTenantId(actingTenantId());";
        assertThat(repo.content())
                // Both write paths, each ahead of its own SQL — not just save. The SET list writes
                // tenant_id on every update, so an entity built from a request body rather than
                // from a read would otherwise clear the row's owner. A subsequence, because
                // contains() alone would pass on one occurrence in either method.
                .containsSubsequence(
                        "public Order save(Order entity)", stamp, "INSERT INTO orders",
                        "public Order update(UUID id, Order entity)", stamp, "UPDATE orders SET")
                // The resolver reads the request-scoped accessor, which throws on an unbound
                // slot, and not storageContextOrSystem(), whose own Javadoc says it silently
                // disables tenant isolation.
                .contains("KernelProviders.storageContext().isolationKey()")
                .doesNotContain("storageContextOrSystem")
                // ADR-036 §2 / T43: a deployment fault must not reach the handler as the
                // IllegalArgumentException that maps to 400.
                .contains("catch (IllegalArgumentException e)")
                .contains("throw new IllegalStateException(TENANT_KEY_NOT_A_UUID, e);")
                .contains("new IllegalStateException(TENANT_SCOPE_REQUIRED)");
    }

    @Test
    @DisplayName("T36: a global entity gets no tenant stamp and no resolver")
    void shouldNotStampATenantOnAGlobalEntity() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .audited(true)
                .fields(List.of(FieldMetadata.builder("orderNumber", "String").build()))
                .build();

        GeneratedFile repo = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.REPOSITORY)
                .findFirst().orElseThrow();

        // Non-vacuous: shouldStampTheActingTenantOnWrites proves the same emitter does write
        // all three of these for a tenant-partitioned entity.
        assertThat(repo.content())
                .contains("entity.setCreatedAt(now)")
                .doesNotContain("actingTenantId")
                .doesNotContain("TENANT_SCOPE_REQUIRED")
                .doesNotContain("KernelProviders");
    }

    @Test
    @DisplayName("both write paths refuse a foreign tenant while one is bound, after the stamp")
    void shouldRefuseAForeignTenantOnWrites() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .tenantScoped(true)
                .fields(List.of(FieldMetadata.builder("orderNumber", "String").build()))
                .build();

        String repo = repositoryOf(metadata).content();

        String stamp = "if (entity.getTenantId() == null) entity.setTenantId(actingTenantId());";
        String refusal = "refuseForeignTenant(entity.getTenantId());";
        assertThat(repo)
                .containsSubsequence(
                        "public Order save(Order entity)", stamp, refusal, "INSERT INTO orders",
                        "public Order update(UUID id, Order entity)", stamp, refusal, "UPDATE orders SET")
                .contains("private static void refuseForeignTenant(UUID tenantId)")
                // "None bound" must stay a no-op: the slot is tested before the throwing accessor.
                .containsSubsequence(
                        "if (tenantId == null || !KernelProviders.STORAGE_CONTEXT.isBound())",
                        "return;",
                        "Optional<String> isolationKey = KernelProviders.storageContext().isolationKey();",
                        "if (isolationKey.isEmpty())",
                        "return;",
                        "bound = UUID.fromString(isolationKey.get());",
                        "throw new IllegalStateException(TENANT_KEY_NOT_A_UUID, e);",
                        "if (!bound.equals(tenantId))",
                        "throw new OrderTenantMismatchException(tenantId);");
    }

    @Test
    @DisplayName("the owner is not in the UPDATE SET list — no update can move a row")
    void shouldNeverUpdateTheOwner() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .tenantScoped(true)
                .audited(true)
                .versioned(true)
                .fields(List.of(FieldMetadata.builder("orderNumber", "String").build()))
                .build();

        String repo = repositoryOf(metadata).content();

        // The INSERT writes it; the UPDATE does not, and its binds close on id and version
        // straight after updated_at.
        assertThat(repo)
                .contains("INSERT INTO orders (id, order_number, tenant_id, created_at, updated_at, version)")
                .contains("UPDATE orders SET order_number = ?, created_at = ?, updated_at = ?, version = ? "
                        + "WHERE id = ? AND version = ?")
                .doesNotContain("tenant_id = ?")
                .containsSubsequence("stmt.bindUuid(4, id);", "stmt.bindLong(5, expectedVersion);");
        assertThat(KernelRepositoryGenerator.updateColumns(metadata))
                .extracting(KernelRepositoryGenerator.Column::sqlName)
                .containsExactly("order_number", "created_at", "updated_at", "version");
    }

    @Test
    @DisplayName("an entity with nothing to update still emits valid SQL — SET id = id, no binds")
    void shouldEmitAValidUpdateWhenNothingIsWritable() {
        // Owner-only (the owner is never written on update) and field-less global: both have an
        // empty SET list.
        for (boolean tenantScoped : new boolean[]{true, false}) {
            String repo = repositoryOf(DomainMetadata.builder("Marker", "com.example.domain")
                    .tenantScoped(tenantScoped).build()).content();

            assertThat(repo)
                    .contains("UPDATE markers SET id = id WHERE id = ?")
                    .containsSubsequence("public Marker update(UUID id, Marker entity)",
                            "stmt.bindUuid(0, id);", "rowsAffected[0] = stmt.executeUpdate();")
                    .doesNotContain("SET  WHERE");
        }
    }

    @Test
    @DisplayName("a UNIVERSE entity refuses a foreign shared scope too; a global entity refuses nothing")
    void shouldRefuseAForeignSharedScopeOnAUniverseEntityOnly() {
        String universe = repositoryOf(universe("java.util.UUID",
                eu.exeris.sdk.sourcemodel.ast.DataScope.UNIVERSE, "worldId")).content();
        String universeString = repositoryOf(universe("java.lang.String",
                eu.exeris.sdk.sourcemodel.ast.DataScope.UNIVERSE, "worldId")).content();
        String global = repositoryOf(DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(FieldMetadata.builder("orderNumber", "String").build()))
                .build()).content();

        assertThat(universe)
                .containsSubsequence(
                        "public Species save(Species entity)",
                        "refuseForeignTenant(entity.getOrganizationId());",
                        "if (entity.getWorldId() == null) entity.setWorldId(actingSharedScope());",
                        "refuseForeignSharedScope(entity.getWorldId());",
                        "INSERT INTO specieses")
                .contains("private static void refuseForeignSharedScope(UUID worldId)")
                .containsSubsequence("UUID bound = actingSharedScope();",
                        "if (bound != null && !bound.equals(worldId))",
                        "throw new SpeciesSharedScopeMismatchException(worldId.toString());")
                // The shared scope stays writable on update (the owner may move its row between
                // scopes); the owner does not.
                .contains("UPDATE specieses SET name = ?, world_id = ? WHERE id = ?");
        assertThat(universeString)
                .contains("private static void refuseForeignSharedScope(String worldId)")
                .contains("throw new SpeciesSharedScopeMismatchException(worldId);");
        assertThat(global)
                .doesNotContain("refuseForeign")
                .doesNotContain("MismatchException");
    }

    /** A UNIVERSE entity with an owner and a shared-scope key of {@code scopeType}. */
    private static DomainMetadata universe(String scopeType, eu.exeris.sdk.sourcemodel.ast.DataScope tier,
                                           String sharedScopeField) {
        return DomainMetadata.builder("Species", "com.example.domain")
                .dataScope(tier)
                .systemFields(SystemFieldsMetadata.builder()
                        .tenantIdField("organizationId").sharedScopeField(sharedScopeField).build())
                .fields(List.of(
                        FieldMetadata.builder("name", "String").build(),
                        FieldMetadata.builder("organizationId", "java.util.UUID").build(),
                        FieldMetadata.builder("worldId", scopeType).build()))
                .build();
    }

    private GeneratedFile repositoryOf(DomainMetadata metadata) {
        return strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.REPOSITORY)
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("T29 B: both write paths tag an untagged UNIVERSE row with the acting shared scope (UUID key)")
    void shouldStampTheActingSharedScopeOnWrites() {
        String repo = repositoryOf(universe("java.util.UUID",
                eu.exeris.sdk.sourcemodel.ast.DataScope.UNIVERSE, "worldId")).content();

        String tenantStamp = "if (entity.getOrganizationId() == null) entity.setOrganizationId(actingTenantId());";
        String scopeStamp = "if (entity.getWorldId() == null) entity.setWorldId(actingSharedScope());";
        assertThat(repo)
                // After the owner, in both write paths, each ahead of its own SQL.
                .containsSubsequence(
                        "public Species save(Species entity)", tenantStamp, scopeStamp, "INSERT INTO specieses",
                        "public Species update(UUID id, Species entity)", tenantStamp, scopeStamp,
                        "UPDATE specieses SET")
                .contains("private static UUID actingSharedScope()")
                // The fallback accessor, deliberately: no bound context means no scope, which is the
                // narrower answer, where the tenant resolver must throw on the same condition.
                .contains("KernelProviders.storageContextOrSystem().sharedScopeKey()")
                .contains(".filter(key -> !key.isBlank())")
                .contains("throw new IllegalStateException(SHARED_SCOPE_KEY_NOT_A_UUID, e);")
                .contains("private static final String SHARED_SCOPE_KEY_NOT_A_UUID")
                // The owner resolver is untouched by the widening.
                .contains("KernelProviders.storageContext().isolationKey()");
    }

    @Test
    @DisplayName("T29 B: a String shared-scope key is stamped as-is — no UUID parse, no parse-failure message")
    void shouldStampAStringSharedScopeWithoutParsing() {
        String repo = repositoryOf(universe("java.lang.String",
                eu.exeris.sdk.sourcemodel.ast.DataScope.UNIVERSE, "worldId")).content();

        assertThat(repo)
                .contains("if (entity.getWorldId() == null) entity.setWorldId(actingSharedScope());")
                .contains("private static String actingSharedScope()")
                .contains("return KernelProviders.storageContextOrSystem().sharedScopeKey()")
                .contains(".filter(key -> !key.isBlank()).orElse(null);")
                .doesNotContain("SHARED_SCOPE_KEY_NOT_A_UUID");
    }

    @Test
    @DisplayName("T29 B: no shared-scope stamp on a TENANT entity, nor on a UNIVERSE entity without the carrier")
    void shouldNotStampASharedScopeOffTheUniverseTier() {
        // A TENANT entity whose metadata carries the component anyway (hand-written JSON — the
        // processor drops it) and a UNIVERSE entity that fails closed to TENANT.
        String tenant = repositoryOf(universe("java.util.UUID",
                eu.exeris.sdk.sourcemodel.ast.DataScope.TENANT, "worldId")).content();
        String carrierless = repositoryOf(universe("java.util.UUID",
                eu.exeris.sdk.sourcemodel.ast.DataScope.UNIVERSE, null)).content();

        // Non-vacuous: shouldStampTheActingSharedScopeOnWrites proves the emitter writes these.
        for (String repo : List.of(tenant, carrierless)) {
            assertThat(repo)
                    .contains("actingTenantId()")
                    .doesNotContain("actingSharedScope")
                    .doesNotContain("SHARED_SCOPE_KEY_NOT_A_UUID")
                    .doesNotContain("storageContextOrSystem");
        }
    }

    @Test
    @DisplayName("Full feature flag matrix: tenantScoped + audited + softDelete + versioned all wire together")
    void shouldHandleFullFeatureMatrix() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .tenantScoped(true).audited(true).softDelete(true).versioned(true)
                .fields(List.of(FieldMetadata.builder("orderNumber", "String").build()))
                .build();

        GeneratedFile repo = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.REPOSITORY)
                .findFirst().orElseThrow();

        assertThat(repo.content())
                // Full SELECT lists every system column in stable order.
                .contains("SELECT id, order_number, tenant_id, created_at, updated_at, deleted, version FROM orders")
                // Soft-delete filter combines with versioned WHERE.
                .contains("WHERE id = ? AND deleted = false")
                // Soft-delete UPDATE excludes already-tombstoned rows.
                .contains("UPDATE orders SET deleted = true WHERE id = ? AND deleted = false")
                // findAll filter:
                .contains("FROM orders WHERE deleted = false")
                // count filter:
                .contains("SELECT COUNT(*) FROM orders WHERE deleted = false")
                // Optimistic-lock UPDATE adds AND version = ? on top of the audited SET clause.
                .contains("AND version = ?")
                // The expected version is read into a boxed local and null-defaulted, so a
                // `Long version` field behaves like the `long` it shadows instead of NPE-ing.
                .contains("Long currentVersion = entity.getVersion()")
                .contains("long expectedVersion = currentVersion == null ? 0L : currentVersion")
                .contains("entity.setVersion(expectedVersion + 1L)")
                // ADR-076: versioned, so the zero-row outcome is reported as a conflict — the
                // statement matched on id AND version and cannot say which of them missed.
                .contains("throw new OrderVersionConflictException(id)");
    }

    @Test
    @DisplayName("Type matrix: Long / Integer / Boolean / Double / Instant / LocalDateTime / LocalDate / enum-like all bind + read correctly")
    void shouldCoverEveryDomainTypeKind() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(
                        FieldMetadata.builder("longId", "Long").build(),
                        FieldMetadata.builder("count", "Integer").build(),
                        FieldMetadata.builder("active", "Boolean").build(),
                        FieldMetadata.builder("ratio", "Double").build(),
                        FieldMetadata.builder("placedAt", "Instant").build(),
                        FieldMetadata.builder("scheduledFor", "LocalDateTime").build(),
                        FieldMetadata.builder("placedOn", "LocalDate").build(),
                        FieldMetadata.builder("status", "com.example.OrderStatus").build()))
                .build();

        GeneratedFile repo = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.REPOSITORY)
                .findFirst().orElseThrow();

        String src = repo.content();
        // Read side — one accessor per type-kind.
        assertThat(src)
                .contains("entity.setLongId(row.getLong(")
                .contains("entity.setCount(row.getInt(")
                .contains("entity.setActive(row.getBoolean(")
                .contains("entity.setRatio(row.getDouble(")
                // T19: Instant reads natively; LocalDate still String-parses.
                .contains("entity.setPlacedAt(row.getInstant(")
                // T19b: LocalDateTime bridges through getInstant at the UTC offset.
                .contains("entity.setScheduledFor(LocalDateTime.ofInstant(v, ZoneOffset.UTC))")
                .contains("entity.setPlacedOn(LocalDate.parse(v))")
                .contains("entity.setStatus(OrderStatus.valueOf(v))")
                // Bind side — one binder per type-kind (with null guard for nullable types).
                .contains("stmt.bindLong(")
                .contains("stmt.bindInt(")
                .contains("stmt.bindBoolean(")
                .contains("stmt.bindDouble(")
                // Enum-like + LocalDate share the null-guarded toString binder; Instant
                // binds natively via bindInstant, null-guarded with bindNull (T19).
                .contains("entity.getStatus() == null ? null : entity.getStatus().toString()")
                .contains("if (entity.getPlacedAt() == null) stmt.bindNull(")
                .contains(", entity.getPlacedAt());")
                // T19b: LocalDateTime binds natively via bindInstant at the UTC offset.
                .contains("if (entity.getScheduledFor() == null) stmt.bindNull(")
                .contains(", entity.getScheduledFor().toInstant(ZoneOffset.UTC));")
                .contains("entity.getPlacedOn() == null ? null : entity.getPlacedOn().toString()")
                // Enum import — JavaPoet emits the ClassName for ENUM_LIKE.
                .contains("import com.example.OrderStatus");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"OffsetDateTime", "java.time.OffsetDateTime"})
    @DisplayName("OffsetDateTime: binds its instant, reads it back at UTC, and its finder binds the instant")
    void offsetDateTimeTravelsAsItsInstant(String type) {
        String src = repositoryFor(FieldMetadata.builder("departedAt", type).filterable(true).build());
        // Columns: id 0, name 1, departedAt 2; the UPDATE binds name at 0 and departedAt at 1.

        assertThat(src)
                .contains("{ Instant v = row.getInstant(2); if (v != null) "
                        + "entity.setDepartedAt(OffsetDateTime.ofInstant(v, ZoneOffset.UTC)); }")
                .contains("if (entity.getDepartedAt() == null) stmt.bindNull(1); "
                        + "else stmt.bindInstant(1, entity.getDepartedAt().toInstant());")
                .contains("public List<Shipment> findByDepartedAt(OffsetDateTime departedAt)")
                .contains("if (departedAt == null) stmt.bindNull(0); else stmt.bindInstant(0, departedAt.toInstant());")
                .contains("import java.time.OffsetDateTime;")
                .doesNotContain("OffsetDateTime.valueOf(")
                .doesNotContain("departedAt.toString()");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ZonedDateTime", "java.time.ZonedDateTime"})
    @DisplayName("ZonedDateTime: binds its instant and reads it back at UTC")
    void zonedDateTimeTravelsAsItsInstant(String type) {
        String src = repositoryFor(FieldMetadata.builder("arrivedAt", type).build());

        assertThat(src)
                .contains("{ Instant v = row.getInstant(2); if (v != null) "
                        + "entity.setArrivedAt(ZonedDateTime.ofInstant(v, ZoneOffset.UTC)); }")
                .contains("if (entity.getArrivedAt() == null) stmt.bindNull(1); "
                        + "else stmt.bindInstant(1, entity.getArrivedAt().toInstant());")
                .contains("import java.time.ZonedDateTime;")
                .doesNotContain("ZonedDateTime.valueOf(");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"BigDecimal", "java.math.BigDecimal"})
    @DisplayName("BigDecimal: every placeholder it fills is cast to the migration's DECIMAL type")
    void bigDecimalPlaceholdersAreCast(String type) {
        String src = repositoryFor(FieldMetadata.builder("weight", type).filterable(true).build());
        String cast = "CAST(? AS " + new KernelFlywayGenerator().domainColumnType(type) + ")";

        assertThat(cast).isEqualTo("CAST(? AS DECIMAL(19,4))");
        assertThat(src)
                .contains("INSERT INTO shipments (id, name, weight) VALUES (?, ?, " + cast + ")")
                .contains("UPDATE shipments SET name = ?, weight = " + cast + " WHERE id = ?")
                .contains("WHERE weight = " + cast + " ORDER BY id")
                .contains("predicates.add(\"weight = " + cast + "\")")
                .contains("stmt.bindString(1, entity.getWeight() == null ? null : entity.getWeight().toPlainString());")
                .contains("stmt.bindString(0, weight == null ? null : weight.toPlainString());")
                .contains("{ String v = row.getString(2); if (v != null) entity.setWeight(new BigDecimal(v)); }");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"LocalDate", "java.time.LocalDate"})
    @DisplayName("LocalDate: every placeholder it fills is cast to DATE")
    void localDatePlaceholdersAreCast(String type) {
        String src = repositoryFor(FieldMetadata.builder("shippedOn", type).filterable(true).build());

        assertThat(new KernelFlywayGenerator().domainColumnType(type)).isEqualTo("DATE");
        assertThat(src)
                .contains("INSERT INTO shipments (id, name, shipped_on) VALUES (?, ?, CAST(? AS DATE))")
                .contains("UPDATE shipments SET name = ?, shipped_on = CAST(? AS DATE) WHERE id = ?")
                .contains("WHERE shipped_on = CAST(? AS DATE) ORDER BY id")
                .contains("predicates.add(\"shipped_on = CAST(? AS DATE)\")")
                .contains("{ String v = row.getString(2); if (v != null) entity.setShippedOn(LocalDate.parse(v)); }");
    }

    @Test
    @DisplayName("a column bound through a typed accessor gets a bare placeholder")
    void typedColumnsAreNotCast() {
        assertThat(repositoryFor(FieldMetadata.builder("count", "Integer").build()))
                .contains("VALUES (?, ?, ?)")
                .doesNotContain("CAST(");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"Short", "java.lang.Short"})
    @DisplayName("Short: bindShort, null-guarded, read through getShort unless the column is NULL")
    void boxedShortIsTyped(String type) {
        String src = repositoryFor(FieldMetadata.builder("crates", type).filterable(true).build());

        assertThat(src)
                .contains("if (entity.getCrates() == null) stmt.bindNull(1); else stmt.bindShort(1, entity.getCrates());")
                .contains("if (!row.isNull(2)) entity.setCrates(row.getShort(2));")
                .contains("if (crates == null) stmt.bindNull(0); else stmt.bindShort(0, crates);")
                .contains("stmt.bindShort(index++, filter.crates());")
                .doesNotContain("valueOf(");
    }

    @Test
    @DisplayName("Byte: bindShort through shortValue(), read back narrowed from getShort")
    void boxedByteIsTyped() {
        String src = repositoryFor(FieldMetadata.builder("grade", "java.lang.Byte").filterable(true).build());

        assertThat(src)
                .contains("if (entity.getGrade() == null) stmt.bindNull(1); "
                        + "else stmt.bindShort(1, entity.getGrade().shortValue());")
                .contains("if (!row.isNull(2)) entity.setGrade((byte) row.getShort(2));")
                .contains("stmt.bindShort(index++, filter.grade().shortValue());");
    }

    @Test
    @DisplayName("Float: bindFloat, null-guarded, read through getFloat unless the column is NULL")
    void boxedFloatIsTyped() {
        String src = repositoryFor(FieldMetadata.builder("ratio", "Float").filterable(true).build());

        assertThat(src)
                .contains("if (entity.getRatio() == null) stmt.bindNull(1); else stmt.bindFloat(1, entity.getRatio());")
                .contains("if (!row.isNull(2)) entity.setRatio(row.getFloat(2));")
                .contains("stmt.bindFloat(index++, filter.ratio());");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"short", "byte", "float"})
    @DisplayName("primitive short, byte and float generate, bound and read without a null guard")
    void primitiveNarrowNumericsGenerate(String type) {
        String src = repositoryFor(FieldMetadata.builder("size", type).filterable(true).build());
        String bind = "float".equals(type) ? "bindFloat" : "bindShort";

        assertThat(src)
                .contains("stmt." + bind + "(1, entity.getSize());")
                .contains("public List<Shipment> findBySize(" + type + " size)")
                .contains("stmt." + bind + "(0, size);")
                .doesNotContain("if (entity.getSize() == null)")
                .doesNotContain("row.isNull(2)");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"Long", "java.lang.Long", "Integer", "Boolean", "Double", "java.lang.Double"})
    @DisplayName("a wrapper-typed scalar binds NULL for null and reads NULL back as null")
    void boxedScalarsAreNullSafe(String type) {
        String src = repositoryFor(FieldMetadata.builder("level", type).filterable(true).build());

        assertThat(src)
                .contains("if (entity.getLevel() == null) stmt.bindNull(1); else stmt.bind")
                .contains("if (!row.isNull(2)) entity.setLevel(row.get")
                .contains("if (level == null) stmt.bindNull(0); else stmt.bind");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"long", "int", "double"})
    @DisplayName("a primitive scalar is bound and read as it is")
    void primitiveScalarsAreUnguarded(String type) {
        String src = repositoryFor(FieldMetadata.builder("level", type).build());

        assertThat(src)
                .doesNotContain("if (entity.getLevel() == null)")
                .doesNotContain("row.isNull(");
    }

    @Test
    @DisplayName("Instant and LocalDateTime finders bind the instant the write path stores")
    void instantFindersBindTheInstant() {
        assertThat(repositoryFor(FieldMetadata.builder("pickedAt", "java.time.Instant").filterable(true).build()))
                .contains("if (pickedAt == null) stmt.bindNull(0); else stmt.bindInstant(0, pickedAt);")
                .doesNotContain("pickedAt.toString()");
        assertThat(repositoryFor(FieldMetadata.builder("pickedAt", "LocalDateTime").filterable(true).build()))
                .contains("if (pickedAt == null) stmt.bindNull(0); "
                        + "else stmt.bindInstant(0, pickedAt.toInstant(ZoneOffset.UTC));")
                .doesNotContain("pickedAt.toString()");
    }

    @Test
    @DisplayName("a List field gets no finder: its column is JSON text, which equality cannot match")
    void listFieldHasNoFinder() {
        DomainMetadata metadata = shipment(FieldMetadata.builder("tags", "java.util.List<java.lang.String>")
                .filterable(true).build());

        assertThat(new KernelRepositoryGenerator().generate(metadata).content())
                .doesNotContain("findByTags");
        assertThat(KernelRepositoryGenerator.finderSpecs(metadata)).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"java.time.DayOfWeek", "com.example.domain.Money", "java.sql.Timestamp"})
    @DisplayName("a type with a valueOf(String) the generator cannot rule out keeps the string round-trip")
    void opaqueValueTypesAreKept(String type) {
        String src = repositoryFor(FieldMetadata.builder("payload", type).build());
        String simple = type.substring(type.lastIndexOf('.') + 1);

        assertThat(src).contains("entity.setPayload(" + simple + ".valueOf(v));");
    }

    @Test
    @DisplayName("an enum named like a refused JDK type is stored as an enum once its enumType is set")
    void enumNamedLikeARefusedTypeIsKept() {
        String src = repositoryFor(FieldMetadata.builder("window", "Duration")
                .enumType("com.example.domain.Duration").build());

        assertThat(src).contains("entity.setWindow(Duration.valueOf(v));");
        assertThatThrownBy(() -> repositoryFor(FieldMetadata.builder("window", "Duration").build()))
                .isInstanceOf(UnpersistableFieldTypeException.class);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "java.util.Map<java.lang.String,java.lang.String>",
            "java.util.Set<java.util.UUID>",
            "java.util.Optional<java.lang.String>",
            // Its type argument names a recognised type; the field is still refused, not read as a LocalDate.
            "java.util.Map<java.lang.String,java.time.LocalDate>",
            "Map<String, Integer>",
            "java.math.BigInteger",
            "BigInteger",
            // No valueOf(String): the repository's read did not compile.
            "java.time.LocalTime", "LocalTime", "java.time.Duration", "java.time.Period",
            "java.util.Date", "java.lang.Character",
            // A primitive char or an array failed generation inside JavaPoet.
            "char", "byte[]"})
    @DisplayName("EXT-GEN-3003: a field type with no column encoding is refused, naming entity, field and type")
    void unpersistableTypeIsRefused(String type) {
        DomainMetadata metadata = shipment(FieldMetadata.builder("payload", type).build());

        assertThatThrownBy(() -> strategy.generate(metadata))
                .isInstanceOf(UnpersistableFieldTypeException.class)
                .hasMessageStartingWith("[Exeris] EXT-GEN-3003: ")
                .hasMessageContaining("com.example.domain.Shipment.payload : " + type)
                .hasMessageContaining("List<…>");
        assertThatThrownBy(() -> KernelRepositoryGenerator.requirePersistableFields(List.of(metadata)))
                .isInstanceOf(UnpersistableFieldTypeException.class);
    }

    @Test
    @DisplayName("EXT-GEN-3003: the refusal lists every refused field of every entity, sorted, with its reason")
    void refusalListsEveryField() {
        DomainMetadata shipment = shipment(
                FieldMetadata.builder("tags", "java.util.Set<java.lang.String>").build(),
                FieldMetadata.builder("weight", "java.math.BigInteger").build(),
                FieldMetadata.builder("window", "java.time.Duration").build(),
                FieldMetadata.builder("blob", "byte[]").build());
        DomainMetadata crate = DomainMetadata.builder("Crate", "com.example.domain")
                .fields(List.of(FieldMetadata.builder("labels", "java.util.Map<java.lang.String,java.lang.String>").build()))
                .build();

        assertThatThrownBy(() -> KernelRepositoryGenerator.requirePersistableFields(List.of(shipment, crate)))
                .isInstanceOfSatisfying(UnpersistableFieldTypeException.class, e -> assertThat(e.fields())
                        .containsExactly(
                                "com.example.domain.Crate.labels : java.util.Map<java.lang.String,java.lang.String>"
                                        + " (a parameterised type other than List<…>)",
                                "com.example.domain.Shipment.blob : byte[] (an array)",
                                "com.example.domain.Shipment.tags : java.util.Set<java.lang.String>"
                                        + " (a parameterised type other than List<…>)",
                                "com.example.domain.Shipment.weight : java.math.BigInteger"
                                        + " (no typed SPI accessor and no valueOf(String); BigDecimal is stored)",
                                "com.example.domain.Shipment.window : java.time.Duration"
                                        + " (no typed SPI accessor and no valueOf(String))"));
    }

    @Test
    @DisplayName("a field that is not a column (it shadows a system column) is not refused, whatever its type")
    void shadowedFieldIsNotRefused() {
        DomainMetadata metadata = DomainMetadata.builder("Shipment", "com.example.domain")
                .versioned(true)
                .fields(List.of(
                        FieldMetadata.builder("name", "String").build(),
                        FieldMetadata.builder("version", "java.util.Optional<java.lang.Long>").build()))
                .build();

        KernelRepositoryGenerator.requirePersistableFields(List.of(metadata));
        assertThat(new KernelRepositoryGenerator().generate(metadata).content())
                .contains("SELECT id, name, version FROM shipments");
    }

    private static DomainMetadata shipment(FieldMetadata... fields) {
        List<FieldMetadata> all = new java.util.ArrayList<>();
        all.add(FieldMetadata.builder("name", "String").build());
        all.addAll(List.of(fields));
        return DomainMetadata.builder("Shipment", "com.example.domain").fields(all).build();
    }

    private static String repositoryFor(FieldMetadata field) {
        return new KernelRepositoryGenerator().generate(shipment(field)).content();
    }

    @Test
    @DisplayName("Versioned entities emit optimistic-lock UPDATE with WHERE id = ? AND version = ?")
    void shouldEmitOptimisticLockForVersionedEntities() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .versioned(true)
                .fields(List.of(FieldMetadata.builder("orderNumber", "String").build()))
                .build();

        GeneratedFile repo = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.REPOSITORY)
                .findFirst().orElseThrow();

        assertThat(repo.content())
                // Column is part of layout — both SELECT and SET-clause.
                .contains("SELECT id, order_number, version FROM orders")
                .contains("UPDATE orders SET order_number = ?, version = ? WHERE id = ? AND version = ?")
                // Auto-increment: capture expected, then increment on the entity
                // so the SET version = ? bind gets the new value.
                .contains("Long currentVersion = entity.getVersion()")
                .contains("long expectedVersion = currentVersion == null ? 0L : currentVersion")
                .contains("entity.setVersion(expectedVersion + 1L)")
                // Bind layout: [0]=order_number, [1]=version (new), [2]=id,
                // [3]=expectedVersion (the optimistic-lock guard). The version bind reads
                // through a boxed local too, so bindLong never unboxes a null.
                .contains("Long versionValue = entity.getVersion()")
                .contains("stmt.bindLong(1, versionValue == null ? 0L : versionValue)")
                .contains("stmt.bindUuid(2, id)")
                .contains("stmt.bindLong(3, expectedVersion)")
                // ADR-076: the stale-version case carries its own type, which the handler maps to 409.
                .contains("throw new OrderVersionConflictException(id)");
    }

    @Test
    @DisplayName("System-field overrides (T5): columns + accessors follow the overridden java names")
    void shouldHonourSystemFieldOverrides() {
        // tenantId→orgId, updatedAt→modifiedAt, version→rev; createdAt/deleted
        // left at defaults to prove per-field resolution.
        SystemFieldsMetadata sf = SystemFieldsMetadata.builder()
                .updatedAtField("modifiedAt")
                .tenantIdField("orgId")
                .versionField("rev")
                .softDeleteField("deleted")
                .build();

        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .tenantScoped(true).audited(true).softDelete(true).versioned(true)
                .systemFields(sf)
                .fields(List.of(FieldMetadata.builder("orderNumber", "String").build()))
                .build();

        GeneratedFile repo = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.REPOSITORY)
                .findFirst().orElseThrow();

        String src = repo.content();
        assertThat(src)
                // Column layout uses snake-cased overridden names.
                .contains("SELECT id, order_number, org_id, created_at, modified_at, deleted, rev FROM orders")
                // Soft-delete filter still uses the (default) deleted column.
                .contains("WHERE id = ? AND deleted = false")
                // Optimistic-lock WHERE uses the overridden version column.
                .contains("AND rev = ?")
                // Accessors derive from the overridden java names.
                .contains("entity.getOrgId()")
                .contains("entity.setOrgId(row.getUuid(")
                // T36 stamp reads the override, not a hardcoded tenantId.
                .contains("if (entity.getOrgId() == null) entity.setOrgId(actingTenantId());")
                .contains("entity.setModifiedAt(now)")
                // T19: native getInstant/bindInstant for the (overridden) updatedAt column.
                .contains("entity.setModifiedAt(row.getInstant(")
                .contains("if (entity.getModifiedAt() == null) stmt.bindNull(")
                .contains(", entity.getModifiedAt());")
                .contains("Long currentVersion = entity.getRev()")
                .contains("long expectedVersion = currentVersion == null ? 0L : currentVersion")
                .contains("entity.setRev(expectedVersion + 1L)")
                // The boxed local is named after the overridden java field, not the default.
                .contains("Long revValue = entity.getRev()")
                .contains("entity.setRev(row.getLong(")
                // Old hardcoded names must NOT appear for the overridden fields.
                .doesNotContain("entity.getTenantId()")
                .doesNotContain("entity.getVersion()")
                .doesNotContain("entity.setUpdatedAt(");
    }

    @Test
    @DisplayName("Table-name override (T6): TABLE field + emitted SQL use the explicit tableName")
    void shouldHonourTableNameOverride() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .tableName("legacy_orders")
                .fields(List.of(FieldMetadata.builder("orderNumber", "String").build()))
                .build();

        GeneratedFile repo = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.REPOSITORY)
                .findFirst().orElseThrow();

        assertThat(repo.content())
                .contains("private static final String TABLE = \"legacy_orders\"")
                .contains("SELECT id, order_number FROM legacy_orders WHERE id = ?")
                .contains("INSERT INTO legacy_orders (id, order_number) VALUES (?, ?)")
                .doesNotContain("FROM orders");
    }

    @Test
    @DisplayName("T14: a declared `id` field does not produce a duplicate id column")
    void shouldNotDuplicateDeclaredIdColumn() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(
                        FieldMetadata.builder("id", "UUID").build(),
                        FieldMetadata.builder("orderNumber", "String").build()))
                .build();

        GeneratedFile repo = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.REPOSITORY)
                .findFirst().orElseThrow();

        // PK `id` wins; the shadowing domain field is dropped — single id column.
        assertThat(repo.content())
                .contains("INSERT INTO orders (id, order_number) VALUES (?, ?)")
                .contains("SELECT id, order_number FROM orders WHERE id = ?")
                .doesNotContain("id, id");
    }

    @Test
    @DisplayName("T2: the findById collision is decided on the derived finder name, not the field name")
    void shouldDecideThePrimaryKeyCollisionOnTheDerivedName() {
        // Only the first character is capitalized, so `id` and `Id` both derive `findById`
        // and collide with the built-in primary-key lookup.
        assertThat(KernelRepositoryGenerator.shadowsPrimaryKeyLookup(
                FieldMetadata.builder("id", "UUID").filterable(true).build())).isTrue();
        assertThat(KernelRepositoryGenerator.shadowsPrimaryKeyLookup(
                FieldMetadata.builder("Id", "UUID").filterable(true).build())).isTrue();

        // `ID` and `iD` derive `findByID`, which collides with nothing — a case-insensitive
        // test on the field name would drop these finders for no reason.
        assertThat(KernelRepositoryGenerator.shadowsPrimaryKeyLookup(
                FieldMetadata.builder("ID", "UUID").filterable(true).build())).isFalse();
        assertThat(KernelRepositoryGenerator.shadowsPrimaryKeyLookup(
                FieldMetadata.builder("iD", "UUID").filterable(true).build())).isFalse();

        // Nothing that merely contains "id" is affected.
        assertThat(KernelRepositoryGenerator.shadowsPrimaryKeyLookup(
                FieldMetadata.builder("idempotencyKey", "String").filterable(true).build())).isFalse();
        assertThat(KernelRepositoryGenerator.shadowsPrimaryKeyLookup(
                FieldMetadata.builder("customerId", "UUID").filterable(true).build())).isFalse();
    }

    @Test
    @DisplayName("T2: a filterable field named `Id` emits no second findById; `ID` still gets its finder")
    void shouldNotEmitDuplicateFindByIdForCapitalisedIdField() {
        DomainMetadata capitalised = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(FieldMetadata.builder("Id", "UUID").filterable(true).build()))
                .build();

        GeneratedFile repo = strategy.generate(capitalised).stream()
                .filter(f -> f.artifactType() == ArtifactType.REPOSITORY)
                .findFirst().orElseThrow();

        // Exactly one declaration — the built-in primary-key lookup.
        assertThat(repo.content().split("Optional<Order> findById\\(", -1)).hasSize(2);

        DomainMetadata upperCase = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(FieldMetadata.builder("ID", "UUID").filterable(true).build()))
                .build();

        GeneratedFile upperRepo = strategy.generate(upperCase).stream()
                .filter(f -> f.artifactType() == ArtifactType.REPOSITORY)
                .findFirst().orElseThrow();

        assertThat(upperRepo.content()).contains("findByID(");
    }

    @Test
    @DisplayName("T14: domain fields shadowing active audited/versioned columns are de-duped")
    void shouldNotDuplicateSystemColumns() {
        DomainMetadata metadata = DomainMetadata.builder("Account", "com.example.domain")
                .audited(true)
                .versioned(true)
                .fields(List.of(
                        FieldMetadata.builder("name", "String").build(),
                        FieldMetadata.builder("version", "Long").build(),       // shadows system version
                        FieldMetadata.builder("createdAt", "Instant").build())) // shadows system created_at
                .build();

        GeneratedFile repo = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.REPOSITORY)
                .findFirst().orElseThrow();

        // system semantics win: each system column appears exactly once
        assertThat(repo.content())
                .contains("INSERT INTO accounts (id, name, created_at, updated_at, version) VALUES (?, ?, ?, ?, ?)")
                .doesNotContain("version, version")
                .doesNotContain("created_at, created_at");
    }

    @Test
    @DisplayName("T14: domain fields shadowing tenantId / soft-delete columns are de-duped")
    void shouldNotDuplicateTenantAndSoftDeleteColumns() {
        DomainMetadata metadata = DomainMetadata.builder("Account", "com.example.domain")
                .tenantScoped(true)
                .softDelete(true)
                .fields(List.of(
                        FieldMetadata.builder("name", "String").build(),
                        FieldMetadata.builder("tenantId", "UUID").build(),    // shadows system tenant_id
                        FieldMetadata.builder("deleted", "boolean").build())) // shadows system deleted
                .build();

        GeneratedFile repo = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.REPOSITORY)
                .findFirst().orElseThrow();

        assertThat(repo.content())
                .contains("INSERT INTO accounts (id, name, tenant_id, deleted) VALUES (?, ?, ?, ?)")
                .doesNotContain("tenant_id, tenant_id")
                .doesNotContain("deleted, deleted");
    }

    @Test
    @DisplayName("T15: a primitive boolean binds via isX(), a Boolean wrapper via getX()")
    void shouldUseIsAccessorForPrimitiveBoolean() {
        DomainMetadata metadata = DomainMetadata.builder("Employee", "com.example.domain")
                .fields(List.of(
                        FieldMetadata.builder("onVacation", "boolean").build(),
                        FieldMetadata.builder("active", "Boolean").build()))
                .build();

        GeneratedFile repo = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.REPOSITORY)
                .findFirst().orElseThrow();

        assertThat(repo.content())
                .contains("entity.isOnVacation()")        // primitive boolean -> is
                .doesNotContain("entity.getOnVacation()")
                .contains("entity.getActive()");          // Boolean wrapper -> get
    }

    @Test
    @DisplayName("T8: emits findBy<Field> for every filterable field, typed + bound by the field's Java type")
    void shouldEmitFindersForFilterableFields() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(
                        // Not filterable → no finder.
                        FieldMetadata.builder("orderNumber", "String").searchable(true).build(),
                        FieldMetadata.builder("status", "com.example.OrderStatus").filterable(true).build(),
                        FieldMetadata.builder("amount", "BigDecimal").filterable(true).build(),
                        FieldMetadata.builder("quantity", "int").filterable(true).build(),
                        FieldMetadata.builder("active", "Boolean").filterable(true).build()))
                .build();

        GeneratedFile repo = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.REPOSITORY)
                .findFirst().orElseThrow();

        String src = repo.content();
        assertThat(src)
                // Filterable fields → findBy<Field>, param typed to the field's Java type.
                .contains("public List<Order> findByStatus(OrderStatus status)")
                .contains("public List<Order> findByAmount(BigDecimal amount)")
                .contains("public List<Order> findByQuantity(int quantity)")
                .contains("public List<Order> findByActive(Boolean active)")
                // Each filters on its own column, ends with a stable ORDER BY id.
                .contains("SELECT id, order_number, status, amount, quantity, active FROM orders WHERE status = ? ORDER BY id")
                .contains("WHERE amount = CAST(? AS DECIMAL(19,4)) ORDER BY id")
                .contains("WHERE quantity = ? ORDER BY id")
                // Binds dispatch on the field type: an enum as its string, BigDecimal as its plain
                // string (the write path's encoding), int via bindInt, Boolean via a null-guarded bindBoolean.
                .contains("stmt.bindString(0, status == null ? null : status.toString())")
                .contains("stmt.bindString(0, amount == null ? null : amount.toPlainString())")
                .contains("stmt.bindInt(0, quantity)")
                .contains("if (active == null) stmt.bindNull(0); else stmt.bindBoolean(0, active);")
                // Same kernel-SPI read shape as findAll: query + prepare + mapRow into a List.
                .contains("result.add(mapRow(qr.row()))")
                // A non-filterable field gets no finder.
                .doesNotContain("findByOrderNumber");
    }

    @Test
    @DisplayName("T8: emits findBy<Rel>Id(UUID) for MANY_TO_ONE relationships only, sorted by name")
    void shouldEmitFindersForManyToOneRelationships() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(FieldMetadata.builder("orderNumber", "String").build()))
                .relationships(List.of(
                        // Entity-typed FK style: name "customer" → column customer_id, method findByCustomerId.
                        RelationshipMetadata.builder("customer", "Customer")
                                .type(RelationshipMetadata.RelationType.MANY_TO_ONE).build(),
                        // Explicit-UUID-FK style: name "warehouseId" → column warehouse_id (NOT warehouse_id_id),
                        // method findByWarehouseId.
                        RelationshipMetadata.builder("warehouseId", "Warehouse")
                                .type(RelationshipMetadata.RelationType.MANY_TO_ONE).build(),
                        // Non-MANY_TO_ONE relationships get no finder.
                        RelationshipMetadata.builder("items", "OrderItem")
                                .type(RelationshipMetadata.RelationType.ONE_TO_MANY).build()))
                .build();

        GeneratedFile repo = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.REPOSITORY)
                .findFirst().orElseThrow();

        String src = repo.content();
        assertThat(src)
                .contains("public List<Order> findByCustomerId(UUID customerId)")
                .contains("public List<Order> findByWarehouseId(UUID warehouseId)")
                // FK column normalisation: both styles resolve to <base>_id, not <name>_id.
                .contains("WHERE customer_id = ? ORDER BY id")
                .contains("WHERE warehouse_id = ? ORDER BY id")
                .doesNotContain("warehouse_id_id")
                // FK params bind via bindUuid.
                .contains("stmt.bindUuid(0, customerId)")
                .contains("stmt.bindUuid(0, warehouseId)")
                // ONE_TO_MANY relationship is excluded.
                .doesNotContain("findByItems");
    }

    @Test
    @DisplayName("T8: finder order is stable — filterable fields (by name) then FK finders (by name)")
    void shouldEmitFindersInStableSortedOrder() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(
                        FieldMetadata.builder("zone", "String").filterable(true).build(),
                        FieldMetadata.builder("alpha", "String").filterable(true).build()))
                .relationships(List.of(
                        RelationshipMetadata.builder("warehouseId", "Warehouse")
                                .type(RelationshipMetadata.RelationType.MANY_TO_ONE).build(),
                        RelationshipMetadata.builder("customer", "Customer")
                                .type(RelationshipMetadata.RelationType.MANY_TO_ONE).build()))
                .build();

        String src = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.REPOSITORY)
                .findFirst().orElseThrow().content();

        // filterable: alpha < zone; FK: customer < warehouseId; filterable block before FK block.
        assertThat(src.indexOf("findByAlpha")).isLessThan(src.indexOf("findByZone"));
        assertThat(src.indexOf("findByZone")).isLessThan(src.indexOf("findByCustomerId"));
        assertThat(src.indexOf("findByCustomerId")).isLessThan(src.indexOf("findByWarehouseId"));
        // Finders are slotted between findAll and save.
        assertThat(src.indexOf("findAll")).isLessThan(src.indexOf("findByAlpha"));
        assertThat(src.indexOf("findByWarehouseId")).isLessThan(src.indexOf("public Order save"));
    }

    @Test
    @DisplayName("an explicit-UUID foreign key without @Field gets one finder, not a field finder and an FK "
            + "finder of the same name")
    void explicitForeignKeyFieldIsNotFoundTwice() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                // FieldMetadata.simple: how the processor records a field that carries no @Field.
                .fields(List.of(FieldMetadata.simple("customerId", "java.util.UUID")))
                .relationships(List.of(RelationshipMetadata.manyToOne("customerId", "Customer")))
                .build();

        assertThat(KernelRepositoryGenerator.finderSpecs(metadata))
                .extracting(KernelRepositoryGenerator.FinderSpec::methodName)
                .containsExactly("findByCustomerId");
    }

    @Test
    @DisplayName("T8: soft-delete finders include the AND deleted = false filter")
    void shouldApplySoftDeleteFilterToFinders() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .softDelete(true)
                .fields(List.of(FieldMetadata.builder("status", "String").filterable(true).build()))
                .build();

        String src = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.REPOSITORY)
                .findFirst().orElseThrow().content();

        assertThat(src).contains("WHERE status = ? AND deleted = false ORDER BY id");
    }

    @Test
    @DisplayName("T8: generation is deterministic — same metadata yields byte-identical repository output")
    void finderGenerationIsDeterministic() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .softDelete(true)
                .fields(List.of(
                        FieldMetadata.builder("status", "com.example.OrderStatus").filterable(true).build(),
                        FieldMetadata.builder("amount", "BigDecimal").filterable(true).build(),
                        FieldMetadata.builder("quantity", "int").filterable(true).build()))
                .relationships(List.of(
                        RelationshipMetadata.builder("warehouseId", "Warehouse")
                                .type(RelationshipMetadata.RelationType.MANY_TO_ONE).build(),
                        RelationshipMetadata.builder("customer", "Customer")
                                .type(RelationshipMetadata.RelationType.MANY_TO_ONE).build()))
                .build();

        String first = repoContent(metadata);
        String second = repoContent(metadata);
        assertThat(second).isEqualTo(first);
    }

    private String repoContent(DomainMetadata metadata) {
        return new KernelGeneratorStrategy().generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.REPOSITORY)
                .findFirst().orElseThrow().content();
    }

    @Test
    @DisplayName("a filterable field named 'id' gets NO finder — findById(UUID) is already the "
            + "primary-key lookup")
    void skipsTheFinderThatWouldShadowFindById() {
        // FieldMetadata.simple(...) — what the processor uses for any field without @Field — sets
        // filterable(true), so a plain `private UUID id;` on an entity emitted a second
        // findById(UUID) here and in the service, and the generated tree did not compile.
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(
                        FieldMetadata.builder("id", "java.util.UUID").filterable(true).build(),
                        FieldMetadata.builder("status", "com.example.OrderStatus").filterable(true).build()))
                .build();

        GeneratedFile repository = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.REPOSITORY)
                .findFirst().orElseThrow();

        assertThat(repository.content()).containsOnlyOnce("findById(UUID id)");
        assertThat(repository.content()).contains("findByStatus(");
    }

    // ------------------------------------------------------------------ the list route's query

    private static String listRepositoryOf(DomainMetadata metadata) {
        return new KernelRepositoryGenerator().generate(metadata).content().replaceAll("\\s+", " ");
    }

    @Test
    @DisplayName("findPage builds WHERE from fixed predicates, one per set filter, after soft delete")
    void findPageAppendsAFixedPredicatePerFilter() {
        String src = listRepositoryOf(KernelListQueryGeneratorTest.order());

        assertThat(src)
                .contains("public OrderPage findPage(OrderListQuery query)")
                .contains("predicates.add(\"deleted = false\");")
                .contains("if (filter.customerId() != null) { predicates.add(\"customer_id = ?\"); }")
                .contains("if (filter.status() != null) { predicates.add(\"status = ?\"); }")
                .contains("String where = predicates.isEmpty() ? \"\" : \" WHERE \" + String.join(\" AND \", predicates);")
                .contains("String countSql = \"SELECT COUNT(*) FROM orders\" + where;")
                .contains("+ where + order + \" LIMIT ? OFFSET ?\";");
        // The soft-delete predicate precedes every filter, as on findAll.
        assertThat(src.indexOf("deleted = false")).isLessThan(src.indexOf("customer_id = ?"));
    }

    @Test
    @DisplayName("findPage orders by a column from a fixed table, id last, and binds limit then offset")
    void findPageOrdersFromAFixedTableAndBindsLimitThenOffset() {
        String src = listRepositoryOf(KernelListQueryGeneratorTest.order());

        assertThat(src)
                .contains("String order = query.sort() == null ? \" ORDER BY id\" : \" ORDER BY \" "
                        + "+ sortColumn(query.sort()) + (query.descending() ? \" DESC, id\" : \" ASC, id\");")
                .contains("private static String sortColumn(String property) { return switch (property) {")
                .contains("case \"orderNumber\" -> \"order_number\";")
                .contains("default -> throw new IllegalArgumentException(\"not a sortable property: \" + property);")
                .contains("int next = bindListFilter(stmt, filter); stmt.bindInt(next, query.size()); "
                        + "stmt.bindLong(next + 1, (long) query.page() * query.size());")
                .contains("return OrderPage.of(content, total, query.page(), query.size());");
        // A JSON column is never an ORDER BY target.
        assertThat(src).doesNotContain("case \"tags\" ->");
    }

    @Test
    @DisplayName("bindListFilter binds each set filter through the bind its column's writes use, in "
            + "predicate order")
    void bindListFilterFollowsThePredicateOrder() {
        String src = listRepositoryOf(KernelListQueryGeneratorTest.order());

        assertThat(src)
                .contains("if (filter.customerId() != null) { stmt.bindUuid(index++, filter.customerId()); }")
                .contains("if (filter.dueOn() != null) { stmt.bindString(index++, filter.dueOn().toString()); }")
                .contains("if (filter.orderNumber() != null) { stmt.bindString(index++, filter.orderNumber()); }")
                .contains("if (filter.status() != null) { stmt.bindString(index++, filter.status().toString()); }")
                .contains("if (filter.urgent() != null) { stmt.bindBoolean(index++, filter.urgent()); }");
        assertThat(src.indexOf("bindUuid(index++, filter.customerId())"))
                .isLessThan(src.indexOf("filter.dueOn().toString()"));
    }

    @Test
    @DisplayName("findPage binds no tenant: the list reads what row-level security shows, as findAll does")
    void findPageLeavesTenantVisibilityToRowLevelSecurity() {
        DomainMetadata tenantScoped = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .tenantScoped(true)
                .fields(List.of(FieldMetadata.builder("orderNumber", "String").filterable(true).build()))
                .build();
        String src = listRepositoryOf(tenantScoped);
        String findPage = src.substring(src.indexOf("public OrderPage findPage"),
                src.indexOf("return OrderPage.of("));

        // tenant_id is selected, as findAll selects it; it is never a predicate or a bind.
        assertThat(findPage).doesNotContain("tenant_id = ?").doesNotContain("actingTenantId")
                .doesNotContain("KernelProviders");
    }

    @Test
    @DisplayName("an entity with nothing sortable orders by id alone and emits no sortColumn")
    void nothingSortable() {
        DomainMetadata bare = DomainMetadata.builder("Tag", "com.example.domain").path("/tags").build();
        String src = listRepositoryOf(bare);

        assertThat(src).contains("String order = \" ORDER BY id\";").doesNotContain("sortColumn");
    }
}
