package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
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
}
