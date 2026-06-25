package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.SystemFieldsMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
                .contains("INSERT INTO orders (id, order_number, amount) VALUES (?, ?, ?)")
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
                .doesNotContain("toJson");
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
                // null-guarded with bindNull (T19 — kernel 0.10 SPI; TIMESTAMPTZ ↔ Instant
                // through the driver, no ISO-String round-trip).
                .contains("stmt.bindUuid(2, entity.getTenantId())")
                .contains("if (entity.getCreatedAt() == null) stmt.bindNull(3); else stmt.bindInstant(3, entity.getCreatedAt());")
                .contains("if (entity.getUpdatedAt() == null) stmt.bindNull(4); else stmt.bindInstant(4, entity.getUpdatedAt());")
                // mapRow reads them back natively via row.getInstant.
                .contains("entity.setTenantId(row.getUuid(2))")
                .contains("entity.setCreatedAt(row.getInstant(3))")
                .contains("entity.setUpdatedAt(row.getInstant(4))");
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
                .contains("long expectedVersion = entity.getVersion()")
                .contains("entity.setVersion(expectedVersion + 1L)")
                // The "not found or stale version" error message is used when versioned.
                .contains("Order not found or stale version: ");
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
                .contains("long expectedVersion = entity.getVersion()")
                .contains("entity.setVersion(expectedVersion + 1L)")
                // Bind layout: [0]=order_number, [1]=version (new), [2]=id,
                // [3]=expectedVersion (the optimistic-lock guard).
                .contains("stmt.bindLong(1, entity.getVersion())")
                .contains("stmt.bindUuid(2, id)")
                .contains("stmt.bindLong(3, expectedVersion)")
                // Distinct error message for stale-version case.
                .contains("Order not found or stale version: ");
    }

    @Test
    @DisplayName("System-field overrides (T5): columns + accessors follow the overridden java names")
    void shouldHonourSystemFieldOverrides() {
        // tenantId→orgId, updatedAt→modifiedAt, version→rev; createdAt/deleted
        // left at defaults to prove per-field resolution.
        SystemFieldsMetadata sf = new SystemFieldsMetadata(
                "id", "createdAt", "createdBy",
                "modifiedAt", "updatedBy", "orgId",
                "rev", "deleted", null, null);

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
                .contains("entity.setModifiedAt(now)")
                // T19: native getInstant/bindInstant for the (overridden) updatedAt column.
                .contains("entity.setModifiedAt(row.getInstant(")
                .contains("if (entity.getModifiedAt() == null) stmt.bindNull(")
                .contains(", entity.getModifiedAt());")
                .contains("long expectedVersion = entity.getRev()")
                .contains("entity.setRev(expectedVersion + 1L)")
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
}
