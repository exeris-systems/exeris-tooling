package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.RelationshipMetadata;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Per-generator test for {@link KernelServiceTestGenerator} (T2, ADR-058).
 *
 * <p>Shape only. That the emitted tests <em>compile and pass</em> against the emitted service is
 * proven end-to-end by {@code GeneratedTestsE2ETest} — substring checks here would happily accept a
 * test that never runs.
 */
@DisplayName("KernelServiceTestGenerator")
class KernelServiceTestGeneratorTest {

    private static final DomainMetadata ORDER =
            DomainMetadata.builder("Order", "com.example.domain").path("/orders").build();

    /** An entity with one finder of each kind: a filterable field, and a MANY_TO_ONE FK. */
    private static final DomainMetadata ORDER_WITH_FINDERS =
            DomainMetadata.builder("Order", "com.example.domain")
                    .path("/orders")
                    .fields(List.of(
                            FieldMetadata.builder("orderNumber", "String").filterable(true).build(),
                            FieldMetadata.builder("quantity", "int").filterable(true).build(),
                            FieldMetadata.builder("status", "com.example.OrderStatus")
                                    .filterable(true).build()))
                    .relationships(List.of(
                            RelationshipMetadata.builder("customer", "Customer")
                                    .type(RelationshipMetadata.RelationType.MANY_TO_ONE).build()))
                    .build();

    private static String generate(DomainMetadata metadata) {
        return new KernelServiceTestGenerator().generate(metadata).content();
    }

    @Test
    @DisplayName("emits <Entity>ServiceTest into the service package, typed as a TEST artefact")
    void emitsServiceTest() {
        GeneratedFile file = new KernelServiceTestGenerator().generate(ORDER);

        assertThat(file.className()).isEqualTo("OrderServiceTest");
        assertThat(file.packageName()).isEqualTo("com.example.service");
        assertThat(file.artifactType()).isEqualTo(ArtifactType.TEST);
    }

    @Test
    @DisplayName("covers each CRUD delegation, including the delete → deleteById rename")
    void coversTheCrudDelegations() {
        String source = generate(ORDER);

        assertThat(source)
                .contains("service.findById(UUID.fromString(")
                .contains("service.findAll()")
                .contains("service.save(argument)")
                .contains("service.update(UUID.fromString(")
                .contains("service.delete(UUID.fromString(")
                .contains("service.count()")
                // The name changes across the boundary, so the double records the repository side.
                .contains("assertThat(repository.deleted).isEqualTo(");
    }

    @Test
    @DisplayName("save/update assert the REPOSITORY's result came back, not the argument")
    void provesTheResultIsTheRepositorys() {
        String source = generate(ORDER);

        // The repository's save fills in a generated id before returning; a service handing back
        // its own argument would compile and silently return an entity with a null id. Two
        // distinct instances are what make the substitution detectable.
        assertThat(source)
                .contains("Order persisted = new Order()")
                .contains("assertThat(service.save(argument)).isSameAs(persisted)")
                .contains("assertThat(repository.saved).isSameAs(argument)")
                .contains(".isSameAs(persisted)");
    }

    @Test
    @DisplayName("one delegation test per T8 finder, recording which repository finder was reached")
    void coversEveryFinder() {
        String source = generate(ORDER_WITH_FINDERS);

        assertThat(source)
                .contains("void findByOrderNumberDelegatesToTheRepository()")
                .contains("void findByQuantityDelegatesToTheRepository()")
                .contains("void findByStatusDelegatesToTheRepository()")
                .contains("void findByCustomerIdDelegatesToTheRepository()")
                // That the method exists is a compile-time fact; that the call is wired to the
                // right one is not — hence the recorded name.
                .contains("assertThat(repository.lastFinder).isEqualTo(\"findByOrderNumber\")")
                .contains("assertThat(repository.lastFinder).isEqualTo(\"findByCustomerId\")");
    }

    @Test
    @DisplayName("finder arguments: a real value where one can be synthesized, null otherwise")
    void passesASampleArgumentWhereItCan() {
        String source = generate(ORDER_WITH_FINDERS);

        assertThat(source)
                .contains("service.findByOrderNumber(\"sample\")")
                .contains("service.findByQuantity(7)")
                .contains("service.findByCustomerId(UUID.fromString(")
                // An enum needs a constant this generator cannot know, so the pass-through
                // assertion is dropped rather than guessed at — the finder-name one still holds.
                .contains("service.findByStatus(null)")
                .contains("assertThat(repository.lastArgument).isEqualTo(\"sample\")");
    }

    @Test
    @DisplayName("the finder set is the repository's own, so the double cannot drift from the service")
    void finderSetComesFromTheRepositorySpec() {
        String source = generate(ORDER_WITH_FINDERS);

        // Every emitted finder test has a matching override on the double, and both come from
        // KernelRepositoryGenerator.finderSpecs — a double overriding a method the service never
        // calls is not expressible.
        for (KernelRepositoryGenerator.FinderSpec spec :
                KernelRepositoryGenerator.finderSpecs(ORDER_WITH_FINDERS)) {
            assertThat(source)
                    .contains("void " + spec.methodName() + "DelegatesToTheRepository()")
                    .contains("this.lastFinder = \"" + spec.methodName() + "\"");
        }
    }

    @Test
    @DisplayName("the repository double subclasses the generated repository — no persistence stack")
    void stubsTheRepositoryBySubclassing() {
        String source = generate(ORDER);

        assertThat(source)
                .contains("static final class StubOrderRepository extends OrderRepository")
                .contains("super((TransactionalExecutor) null)")
                .contains("public Optional<Order> findById(UUID id)")
                .contains("public void deleteById(UUID id)")
                .contains("public long count()");
    }

    @Test
    @DisplayName("the emitted test binds no kernel provider — no ScopedValue slot, no driver")
    void bindsNoKernelProviderSlot() {
        String source = generate(ORDER_WITH_FINDERS);

        // The TransactionalExecutor appears only as the cast on super(null); nothing is resolved.
        assertThat(source)
                .doesNotContain("ScopedValue")
                .doesNotContain("KernelProviders")
                .doesNotContain("PersistenceStatement");
    }

    @Test
    @DisplayName("the dependency contract holds: JUnit + AssertJ only, no mocking framework")
    void importsOnlyTheContractDependencies() {
        String source = generate(ORDER_WITH_FINDERS);

        assertThat(source)
                .contains("import org.junit.jupiter.api.Test;")
                .contains("import org.assertj.core.api.Assertions;");
        assertThat(source)
                .doesNotContain("org.mockito")
                .doesNotContain("org.easymock")
                .doesNotContain("Mockito.");
    }

    @Test
    @DisplayName("emission is deterministic — byte-identical across runs (no random UUID literal)")
    void emissionIsDeterministic() {
        String first = generate(ORDER_WITH_FINDERS);
        String second = generate(ORDER_WITH_FINDERS);

        assertThat(first).isEqualTo(second);
        assertThat(first).doesNotContain("UUID.randomUUID()");
    }

    @Test
    @DisplayName("rejects a domain package that does not end with '.domain'")
    void rejectsNonDomainPackage() {
        DomainMetadata bad = DomainMetadata.builder("Order", "com.example.order").build();
        KernelServiceTestGenerator generator = new KernelServiceTestGenerator();

        assertThatThrownBy(() -> generator.generate(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("com.example.order")
                .hasMessageContaining(".domain");
    }
}
