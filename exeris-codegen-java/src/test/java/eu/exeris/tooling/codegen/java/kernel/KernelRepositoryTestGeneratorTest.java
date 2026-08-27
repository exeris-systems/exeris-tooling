package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Per-generator test for {@link KernelRepositoryTestGenerator} (T2, ADR-058).
 *
 * <p>Shape only. That the emitted round-trip <em>catches a desync</em> is proven by
 * {@code GeneratedTestsE2ETest}, which compiles and runs it against a real emitted repository —
 * the only place that assertion means anything.
 */
@DisplayName("KernelRepositoryTestGenerator")
class KernelRepositoryTestGeneratorTest {

    private static final DomainMetadata ORDER =
            DomainMetadata.builder("Order", "com.example.domain")
                    .path("/orders")
                    .fields(List.of(
                            FieldMetadata.builder("orderNumber", "String").build(),
                            FieldMetadata.builder("quantity", "int").build(),
                            FieldMetadata.builder("expedited", "boolean").build(),
                            FieldMetadata.builder("status", "com.example.OrderStatus").build()))
                    .build();

    /** The same entity, tenant-partitioned — the only difference the T36 emission keys on. */
    private static final DomainMetadata TENANT_ORDER =
            DomainMetadata.builder("Order", "com.example.domain")
                    .path("/orders")
                    .tenantScoped(true)
                    .fields(List.of(
                            FieldMetadata.builder("orderNumber", "String").build(),
                            FieldMetadata.builder("quantity", "int").build()))
                    .build();

    private static String generate(DomainMetadata metadata) {
        return new KernelRepositoryTestGenerator().generate(metadata, "com.example").content();
    }

    @Test
    @DisplayName("emits <Entity>RepositoryTest into the repository package, typed as a TEST artefact")
    void emitsRepositoryTest() {
        GeneratedFile file = new KernelRepositoryTestGenerator().generate(ORDER, "com.example");

        assertThat(file.className()).isEqualTo("OrderRepositoryTest");
        assertThat(file.packageName()).isEqualTo("com.example.repository");
        assertThat(file.artifactType()).isEqualTo(ArtifactType.TEST);
    }

    @Test
    @DisplayName("the round-trip replays the recorded binds as the query result")
    void roundTripsTheRecordedBinds() {
        String source = generate(ORDER);

        assertThat(source)
                .contains("repository.save(original)")
                // The snapshot has to happen before the read: prepare(...) clears the binds.
                .contains("persistence.row = persistence.recordedRow()")
                .contains("Order loaded = repository.findById(original.getId()).orElseThrow()");
    }

    @Test
    @DisplayName("every column is asserted through the accessor the bind path uses")
    void assertsEveryColumnThroughItsAccessor() {
        String source = generate(ORDER);

        assertThat(source)
                .contains("assertThat(loaded.getId()).isEqualTo(original.getId())")
                .contains("assertThat(loaded.getOrderNumber()).isEqualTo(original.getOrderNumber())")
                .contains("assertThat(loaded.getQuantity()).isEqualTo(original.getQuantity())")
                // A primitive boolean reads as is<Name>(), which is what the repository binds too.
                .contains("assertThat(loaded.isExpedited()).isEqualTo(original.isExpedited())")
                .contains("assertThat(loaded.getStatus()).isEqualTo(original.getStatus())");
    }

    @Test
    @DisplayName("stages a value wherever one can be synthesized, and skips the rest")
    void stagesSynthesizableColumnsOnly() {
        String source = generate(ORDER);

        assertThat(source)
                .contains("original.setOrderNumber(\"sample\")")
                .contains("original.setQuantity(7)")
                .contains("original.setId(UUID.fromString(")
                // An enum constant is not knowable here, so the field is left null — it still
                // round-trips, the comparison is just vacuous for it.
                .doesNotContain("original.setStatus(");
    }

    @Test
    @DisplayName("asserts no SQL text — that check would be circular, not a check")
    void assertsBehaviourNotEmittedSql() {
        String source = generate(ORDER);

        // The test and the repository are generated from one DomainMetadata, so a changed column
        // list changes both and a SQL-text assertion could never fail. What is actually at risk is
        // index alignment between the bind path and the read path, and that only shows at runtime.
        assertThat(source)
                .doesNotContain("SELECT ")
                .doesNotContain("INSERT INTO")
                .doesNotContain("persistence.sql");
    }

    @Test
    @DisplayName("covers the paths around the round-trip: id fill-in, WHERE id, empty, not-found, count")
    void coversTheSurroundingPaths() {
        String source = generate(ORDER);

        assertThat(source)
                .contains("void saveGeneratesAMissingIdAndBindsItFirst()")
                .contains("assertThat(persistence.binds.get(0)).isEqualTo(entity.getId())")
                .contains("void updateBindsTheIdAfterTheSetList()")
                .contains("void findByIdIsEmptyWhenTheQueryReturnsNoRow()")
                .contains("void updateRejectsWhenNoRowMatched()")
                .contains("void deleteByIdRejectsWhenNoRowMatched()")
                .contains("void countReadsTheAggregateFromColumnZero()")
                // ADR-076: the emitted assertion names the type. isInstanceOf(RuntimeException)
                // would be satisfied by an NPE and leave the guard untested; the dedicated type
                // excludes that exactly, where the old "not found" substring did it by accident
                // of wording. Unversioned entity here, so update reports the missing row.
                .contains("isInstanceOf(OrderNotFoundException.class)");
    }

    @Test
    @DisplayName("the WHERE-clause id binds one slot past the SET list")
    void bindsTheWhereIdAfterTheSetList() {
        // 5 columns (id + four fields) → SET list is 4 wide → WHERE id is parameter 4.
        assertThat(generate(ORDER)).contains("assertThat(persistence.binds.get(4)).isEqualTo(id)");
    }

    @Test
    @DisplayName("a versioned entity is NOT pre-staged — the update tests pin the T26 fix")
    void doesNotStageAwayTheVersionNullPath() {
        DomainMetadata versioned = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .versioned(true)
                .fields(List.of(FieldMetadata.builder("orderNumber", "String").build()))
                .build();

        // update() reads the version off a freshly constructed entity. Staging it first would have
        // hidden T26 (a wrapper `Long version` unboxed to null); leaving it unset is what makes
        // every consumer's generated test a regression test for that fix.
        assertThat(generate(versioned)).doesNotContain("entity.setVersion(");
    }

    @Test
    @DisplayName("the dependency contract holds: JUnit + AssertJ only, no mocking framework")
    void importsOnlyTheContractDependencies() {
        String source = generate(ORDER);

        assertThat(source)
                .contains("import org.junit.jupiter.api.Test;")
                .contains("import org.assertj.core.api.Assertions;")
                .contains("import com.example.testsupport.RecordingPersistence;");
        assertThat(source)
                .doesNotContain("org.mockito")
                .doesNotContain("org.easymock");
    }

    @Test
    @DisplayName("emission is deterministic — byte-identical across runs (no random UUID literal)")
    void emissionIsDeterministic() {
        assertThat(generate(ORDER)).isEqualTo(generate(ORDER));
        assertThat(generate(ORDER)).doesNotContain("UUID.randomUUID()");
    }

    @Test
    @DisplayName("T36: every write in a tenant-scoped entity's tests runs inside a bound tenant")
    void bindsATenantAroundEveryWrite() {
        String source = generate(TENANT_ORDER);

        // A tenant-scoped repository resolves the acting tenant on every write, so a write made
        // outside a bound scope throws before it reaches the double — which would fail these
        // tests for a reason none of them is about.
        assertThat(source)
                .contains("asTenant(() -> repository.save(original))")
                .contains("asTenant(() -> repository.save(entity))")
                .contains("asTenant(() -> repository.update(id, entity))")
                .contains("asTenant(() -> Assertions.assertThatThrownBy(() -> repository.update(")
                .contains("ScopedValue.where(KernelProviders.STORAGE_CONTEXT, TENANT_SCOPE).run(work)")
                .contains("ImmutableStorageContext.shared(TENANT_KEY)");
        // The read paths are left alone — binding a tenant around them would say something the
        // test does not mean.
        assertThat(source)
                .contains("Assertions.assertThatThrownBy(() -> repository.deleteById(")
                .doesNotContain("asTenant(() -> repository.deleteById(");
    }

    @Test
    @DisplayName("T36: the stamp pair is emitted, keyed on a tenant no other value in the file shares")
    void emitsTheStampPair() {
        String source = generate(TENANT_ORDER);

        assertThat(source)
                .contains("void saveStampsTheActingTenantWhenTheCallerLeftItUnset()")
                .contains("void saveKeepsATenantTheCallerSet()")
                // The bound tenant must differ from the UUID every other field is staged with,
                // or the stamp test could not tell a value that came from the context apart from
                // one that was already on the entity.
                .contains("TENANT_KEY = \"00000000-0000-4000-8000-000000000002\"")
                .contains("callerTenant = UUID.fromString(\"00000000-0000-4000-8000-000000000001\")");
    }

    @Test
    @DisplayName("T36: a global entity's tests carry none of the tenant scaffold")
    void globalEntityGetsNoTenantScaffold() {
        // Non-vacuous: bindsATenantAroundEveryWrite proves the same emitter writes all of this
        // for a tenant-partitioned entity.
        assertThat(generate(ORDER))
                .contains("repository.save(original)")
                .doesNotContain("asTenant")
                .doesNotContain("TENANT_SCOPE")
                .doesNotContain("KernelProviders")
                .doesNotContain("ScopedValue");
    }

    @Test
    @DisplayName("T36: the tenant scaffold adds no test-only dependency, only kernel SPI")
    void tenantScaffoldStaysInsideTheDependencyContract() {
        String source = generate(TENANT_ORDER);

        // The two SPI types the scaffold reaches for are already compile-time requirements of the
        // repository under test, so the "JUnit + AssertJ and nothing else" half of ADR-058 is
        // untouched: no mocking framework, no test-only artefact.
        assertThat(source)
                .contains("import eu.exeris.kernel.spi.context.KernelProviders;")
                .contains("import eu.exeris.kernel.spi.security.ImmutableStorageContext;")
                .doesNotContain("org.mockito")
                .doesNotContain("org.easymock");
    }

    @Test
    @DisplayName("rejects a domain package that does not end with '.domain'")
    void rejectsNonDomainPackage() {
        DomainMetadata bad = DomainMetadata.builder("Order", "com.example.order").build();
        KernelRepositoryTestGenerator generator = new KernelRepositoryTestGenerator();

        assertThatThrownBy(() -> generator.generate(bad, "com.example"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("com.example.order")
                .hasMessageContaining(".domain");
    }
}
