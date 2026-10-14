package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.tooling.codegen.java.support.ForeignKeys;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * The generated repository-test cases for the foreign-key refusals (ADR-076 Amendment 1), emitted
 * into {@code <Entity>RepositoryTest} by {@link KernelRepositoryTestGenerator}.
 *
 * <p>The persistence double throws what the kernel's persistence SPI throws for a refused statement —
 * a {@code PersistenceProviderException} carrying the SQLState and the driver's message — in the
 * shapes the two engines give it: PostgreSQL's lower-case constraint name under {@code 23503}, and
 * H2's upper-case one under {@code 23506} for a missing parent, wrapped once to show the cause chain
 * is walked.
 */
final class KernelForeignKeyRefusalTests {

    private static final ClassName TEST = ClassName.get("org.junit.jupiter.api", "Test");
    private static final ClassName ASSERTIONS = ClassName.get("org.assertj.core.api", "Assertions");
    private static final ClassName UUID = ClassName.get("java.util", "UUID");
    private static final ClassName PERSISTENCE_PROVIDER_EXCEPTION =
            ClassName.get("eu.exeris.kernel.spi.exceptions.persistence", "PersistenceProviderException");
    private static final String NEW_PERSISTENCE = "$T persistence = new $T()";
    private static final String NEW_REPOSITORY = "$T repository = new $T(persistence)";
    private static final String STAGE_FAILURE = "persistence.writeFailure = $T.queryFailed($S, $S, null)";

    private KernelForeignKeyRefusalTests() {}

    /**
     * The cases for one entity of a build: a write naming a missing row, on both engines' shapes,
     * and a violation of a constraint the build did not declare left as it is, when the entity
     * references another; a delete of a row others reference, when it can be refused.
     *
     * @param newEntity the statements declaring a saveable {@code entity}
     * @param write     wraps a write statement in the tenant scope the entity needs, if any
     */
    static List<MethodSpec> cases(DomainMetadata metadata, List<DomainMetadata> build,
                                  ClassName repositoryType, ClassName persistenceType,
                                  CodeBlock newEntity, UnaryOperator<String> write) {
        List<MethodSpec> cases = new ArrayList<>();
        ClassName referenceNotFound = KernelErrorGenerator.referenceNotFoundType(metadata, build);
        if (referenceNotFound != null) {
            ForeignKeys.Reference reference = ForeignKeys.outgoing(metadata, build).get(0);
            cases.add(saveRefusesMissingReference(repositoryType, persistenceType, newEntity, write,
                    referenceNotFound, reference));
            cases.add(updateRefusesMissingReference(repositoryType, persistenceType, newEntity, write,
                    referenceNotFound, reference));
            cases.add(saveLeavesOtherViolation(repositoryType, persistenceType, newEntity, write));
        }
        ClassName stillReferenced = KernelErrorGenerator.stillReferencedType(metadata, build);
        if (stillReferenced != null) {
            cases.add(deleteRefusesReferencedRow(repositoryType, persistenceType, stillReferenced));
        }
        return cases;
    }

    private static MethodSpec saveRefusesMissingReference(ClassName repositoryType, ClassName persistenceType,
                                                          CodeBlock newEntity,
                                                          UnaryOperator<String> write,
                                                          ClassName referenceNotFound,
                                                          ForeignKeys.Reference reference) {
        String detail = "foreign key constraint violated: ERROR: insert or update on table \""
                + reference.table() + "\" violates foreign key constraint \"" + reference.constraint() + "\"";
        return test("saveRefusesARowNamingAMissingReference")
                .addJavadoc("PostgreSQL's shape: SQLState {@code $L}, the constraint named in lower case.\n",
                        KernelForeignKeyRefusals.FOREIGN_KEY_VIOLATION)
                .addStatement(NEW_PERSISTENCE, persistenceType, persistenceType)
                .addStatement(STAGE_FAILURE, PERSISTENCE_PROVIDER_EXCEPTION,
                        KernelForeignKeyRefusals.FOREIGN_KEY_VIOLATION, detail)
                .addStatement(NEW_REPOSITORY, repositoryType, repositoryType)
                .addCode(newEntity)
                .addStatement(write.apply("$T.assertThatThrownBy(() -> repository.save(entity))"
                                + ".isInstanceOfSatisfying($T.class, e -> $T.assertThat(e.relation()).isEqualTo($S))"),
                        ASSERTIONS, referenceNotFound, ASSERTIONS, reference.relation())
                .build();
    }

    private static MethodSpec updateRefusesMissingReference(ClassName repositoryType, ClassName persistenceType,
                                                            CodeBlock newEntity,
                                                            UnaryOperator<String> write,
                                                            ClassName referenceNotFound,
                                                            ForeignKeys.Reference reference) {
        String detail = "foreign key constraint violated: Referential integrity constraint violation: \""
                + reference.constraint().toUpperCase(Locale.ROOT) + ": PUBLIC."
                + reference.table().toUpperCase(Locale.ROOT) + " FOREIGN KEY("
                + reference.column().toUpperCase(Locale.ROOT) + ")\"";
        return test("updateRefusesARowNamingAMissingReference")
                .addJavadoc("H2's shape: SQLState {@code $L}, the constraint named in upper case, and\n",
                        KernelForeignKeyRefusals.MISSING_PARENT)
                .addJavadoc("the SPI exception wrapped once on its way out of the executor.\n")
                .addStatement(NEW_PERSISTENCE, persistenceType, persistenceType)
                .addStatement("persistence.writeFailure = new $T($S, $T.queryFailed($S, $S, null))",
                        IllegalStateException.class, "executor", PERSISTENCE_PROVIDER_EXCEPTION,
                        KernelForeignKeyRefusals.MISSING_PARENT, detail)
                .addStatement(NEW_REPOSITORY, repositoryType, repositoryType)
                .addCode(newEntity)
                .addStatement(write.apply("$T.assertThatThrownBy(() -> repository.update($T.fromString($S), entity))"
                                + ".isInstanceOfSatisfying($T.class, e -> $T.assertThat(e.relation()).isEqualTo($S))"),
                        ASSERTIONS, UUID, KernelTestSamples.FIXED_ID, referenceNotFound, ASSERTIONS,
                        reference.relation())
                .build();
    }

    private static MethodSpec saveLeavesOtherViolation(ClassName repositoryType, ClassName persistenceType,
                                                       CodeBlock newEntity,
                                                       UnaryOperator<String> write) {
        return test("saveLeavesAViolationOfAnotherConstraintAsItIs")
                .addJavadoc("A unique violation is not a missing reference, and propagates unchanged.\n")
                .addStatement(NEW_PERSISTENCE, persistenceType, persistenceType)
                .addStatement(STAGE_FAILURE, PERSISTENCE_PROVIDER_EXCEPTION, "23505",
                        "unique constraint violated")
                .addStatement(NEW_REPOSITORY, repositoryType, repositoryType)
                .addCode(newEntity)
                .addStatement(write.apply("$T.assertThatThrownBy(() -> repository.save(entity))"
                                + ".isSameAs(persistence.writeFailure)"),
                        ASSERTIONS)
                .build();
    }

    private static MethodSpec deleteRefusesReferencedRow(ClassName repositoryType, ClassName persistenceType,
                                                         ClassName stillReferenced) {
        return test("deleteByIdRefusesARowOtherRowsStillReference")
                .addJavadoc("A delete violates a foreign key only as the referenced side, so any\n")
                .addJavadoc("violation of one means other rows still reference this row.\n")
                .addStatement(NEW_PERSISTENCE, persistenceType, persistenceType)
                .addStatement(STAGE_FAILURE, PERSISTENCE_PROVIDER_EXCEPTION,
                        KernelForeignKeyRefusals.FOREIGN_KEY_VIOLATION,
                        "foreign key constraint violated: update or delete violates a foreign key constraint")
                .addStatement(NEW_REPOSITORY, repositoryType, repositoryType)
                .addStatement("$T.assertThatThrownBy(() -> repository.deleteById($T.fromString($S)))"
                                + ".isInstanceOfSatisfying($T.class,\n"
                                + "e -> $T.assertThat(e.id()).isEqualTo($T.fromString($S)))",
                        ASSERTIONS, UUID, KernelTestSamples.FIXED_ID, stillReferenced, ASSERTIONS, UUID,
                        KernelTestSamples.FIXED_ID)
                .build();
    }

    private static MethodSpec.Builder test(String name) {
        return MethodSpec.methodBuilder(name)
                .addAnnotation(TEST)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID);
    }
}
