package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.java.kernel.KernelRepositoryGenerator.Column;
import eu.exeris.tooling.codegen.java.kernel.KernelRepositoryGenerator.ColumnKind;
import eu.exeris.tooling.codegen.java.support.KernelScaffold;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Emits {@code <Entity>RepositoryTest} — the generated test for the generated repository
 * (T2, ADR-058).
 *
 * <h2>What it asserts, and what it deliberately does not</h2>
 * <p>It does <b>not</b> assert the emitted SQL text. That assertion would be circular: the test and
 * the repository are generated from one {@code DomainMetadata}, so a changed column list changes
 * both, and the test could never fail on it. It would be a change-detector for a change that cannot
 * happen.
 *
 * <p>What is genuinely at risk is the <em>alignment</em> between two independent emitter paths:
 * {@code emitInsertBinds} numbers the INSERT's parameters, {@code emitReadCol} numbers
 * {@code mapRow}'s cursor reads, and each walks the column layout with its own counter. An
 * off-by-one, a skipped system column, or a bind whose type does not match the accessor the read
 * side uses — all of these compile, and all of them corrupt every row.
 *
 * <p>So the central test is a <b>round-trip</b>: save an entity against a recording double, replay
 * the recorded binds back as the query result, load it, and compare field for field. The
 * expectations come from the same column layout as the emitter, which is what keeps it honest —
 * the test asserts runtime <em>behaviour</em>, not emitted text, so an index that drifts on one side
 * lands on the other side's value and the comparison fails. The double's typed accessors cast, so a
 * value bound as one type and read as another fails there too.
 *
 * <p>Around that: the id is filled in when absent (and lands at parameter 0), the WHERE-clause id
 * binds after the SET list, an empty result is {@code Optional.empty()}, a zero-row write is
 * rejected, and {@code count()} reads the aggregate.
 *
 * <h2>The double</h2>
 * <p>{@code RecordingPersistence} (emitted once per project by {@link KernelTestSupportGenerator})
 * plays every persistence-SPI role at once. No database, no driver, no transaction — and the
 * ADR-058 dependency contract stays JUnit 5 + AssertJ.
 *
 * @implNote Emission is JavaPoet-based (ADR-015).
 * @since 0.7.0
 */
public final class KernelRepositoryTestGenerator {

    private static final ClassName TEST = ClassName.get("org.junit.jupiter.api", "Test");
    private static final ClassName ASSERTIONS = ClassName.get("org.assertj.core.api", "Assertions");
    private static final ClassName UUID = ClassName.get("java.util", "UUID");
    private static final ClassName MAP = ClassName.get("java.util", "Map");
    private static final ClassName INTEGER = ClassName.get(Integer.class);
    private static final ClassName OBJECT = ClassName.get(Object.class);

    /** The substring both not-found messages the repository throws share. */
    private static final String NOT_FOUND = "not found";

    /**
     * @param metadata    the entity whose repository is under test
     * @param basePackage the project base package (the {@code testsupport} package is resolved from
     *                    it, since the persistence double is project-wide)
     */
    public GeneratedFile generate(DomainMetadata metadata, String basePackage) {
        String entity = metadata.entityName();
        String domainPackage = metadata.packageName();
        if (!domainPackage.endsWith(".domain")) {
            throw new IllegalArgumentException(
                    "Domain package '" + domainPackage + "' for entity '" + entity
                            + "' does not end with '.domain' — the repository-test generator derives"
                            + " the .repository package path from that suffix");
        }
        String infrastructureBase = domainPackage.substring(0, domainPackage.lastIndexOf(".domain"));
        String packageName = infrastructureBase + ".repository";
        String className = entity + "RepositoryTest";

        ClassName entityType = ClassName.get(domainPackage, entity);
        ClassName repositoryType = ClassName.get(packageName, entity + "Repository");
        ClassName persistenceType = ClassName.get(
                KernelTestSupportGenerator.supportPackage(basePackage),
                KernelTestSupportGenerator.RECORDING_PERSISTENCE);
        List<Column> columns = KernelRepositoryGenerator.columnLayout(metadata);

        TypeSpec.Builder type = KernelScaffold.publicClass(className)
                .addJavadoc("Generated tests for {@link $T}.\n", repositoryType)
                .addJavadoc("<p>Covers the invariant no compile check can: that the parameter\n")
                .addJavadoc("indices the INSERT binds and the column indices {@code mapRow} reads\n")
                .addJavadoc("are the same layout — proven by a save/load round-trip against a\n")
                .addJavadoc("recording double rather than by asserting the emitted SQL, which is\n")
                .addJavadoc("generated from the same metadata as this test and so could never\n")
                .addJavadoc("disagree with it.\n")
                .addJavadoc("<p>Requires JUnit 5 and AssertJ on the test classpath, and nothing else.\n")
                .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain models.\n");

        type.addMethod(roundTripTest(entityType, repositoryType, persistenceType, columns));
        type.addMethod(saveFillsIdTest(entityType, repositoryType, persistenceType));
        type.addMethod(updateBindsIdTest(entityType, repositoryType, persistenceType, metadata, columns));
        type.addMethod(findByIdEmptyTest(repositoryType, persistenceType));
        type.addMethod(updateRejectsTest(entityType, repositoryType, persistenceType, metadata));
        type.addMethod(deleteRejectsTest(repositoryType, persistenceType));
        type.addMethod(countTest(repositoryType, persistenceType));

        return new GeneratedFile(packageName, className,
                KernelScaffold.render(packageName, type.build()), ArtifactType.TEST);
    }

    /** The central test — see the class Javadoc for why it is a round-trip and not a SQL check. */
    private MethodSpec roundTripTest(ClassName entityType, ClassName repositoryType,
                                     ClassName persistenceType, List<Column> columns) {
        MethodSpec.Builder test = test("savedRowReadsBackColumnForColumn")
                .addJavadoc("The INSERT's binds, replayed as the SELECT's row. Every column has to\n")
                .addJavadoc("survive the trip: a bind index that drifts from its read index lands on\n")
                .addJavadoc("a neighbour's value, and a bind whose type does not match the accessor\n")
                .addJavadoc("the read side uses fails the double's cast.\n")
                .addStatement("$T persistence = new $T()", persistenceType, persistenceType)
                .addStatement("$T repository = new $T(persistence)", repositoryType, repositoryType)
                .addStatement("$T original = new $T()", entityType, entityType);

        for (Column column : columns) {
            CodeBlock sample = stagedValue(column);
            if (sample != null) {
                test.addStatement("original.$L($L)", KernelRepositoryGenerator.setterFor(column), sample);
            }
        }

        test.addStatement("repository.save(original)")
                .addCode("\n")
                .addComment("Snapshot before the read: prepare(...) clears the recorded binds.")
                .addStatement("persistence.row = persistence.recordedRow()")
                .addStatement("$T loaded = repository.findById(original.getId()).orElseThrow()", entityType)
                .addCode("\n");

        for (Column column : columns) {
            String accessor = KernelRepositoryGenerator.getterFor(column);
            test.addStatement("$T.assertThat(loaded.$L()).isEqualTo(original.$L())",
                    ASSERTIONS, accessor, accessor);
        }
        return test.build();
    }

    private MethodSpec saveFillsIdTest(ClassName entityType, ClassName repositoryType,
                                       ClassName persistenceType) {
        return test("saveGeneratesAMissingIdAndBindsItFirst")
                .addJavadoc("{@code id} is column 0 of the layout, so it is also parameter 0 of the\n")
                .addJavadoc("INSERT — and the value bound there is the one the caller can read back\n")
                .addJavadoc("off the entity afterwards.\n")
                .addStatement("$T persistence = new $T()", persistenceType, persistenceType)
                .addStatement("$T repository = new $T(persistence)", repositoryType, repositoryType)
                .addStatement("$T entity = new $T()", entityType, entityType)
                .addStatement("$T.assertThat(entity.getId()).isNull()", ASSERTIONS)
                .addStatement("repository.save(entity)")
                .addStatement("$T.assertThat(entity.getId()).isNotNull()", ASSERTIONS)
                .addStatement("$T.assertThat(persistence.binds.get(0)).isEqualTo(entity.getId())",
                        ASSERTIONS)
                .build();
    }

    /**
     * The UPDATE lays its parameters out differently from the INSERT: the SET list is every column
     * except {@code id}, and {@code id} binds after it to close the WHERE clause. That index is the
     * one thing a reordering of {@code emitUpdateBinds} would silently break.
     */
    private MethodSpec updateBindsIdTest(ClassName entityType, ClassName repositoryType,
                                         ClassName persistenceType, DomainMetadata metadata,
                                         List<Column> columns) {
        // SET list = columns minus id, so the WHERE id lands one slot past its last entry.
        int whereIdIndex = columns.size() - 1;
        MethodSpec.Builder test = test("updateBindsTheIdAfterTheSetList")
                .addStatement("$T persistence = new $T()", persistenceType, persistenceType)
                .addStatement("$T repository = new $T(persistence)", repositoryType, repositoryType)
                .addStatement("$T entity = new $T()", entityType, entityType);
        stageVersion(test, metadata, columns);
        test.addStatement("$T id = $T.fromString($S)", UUID, UUID, KernelTestSamples.FIXED_ID)
                .addStatement("repository.update(id, entity)")
                .addStatement("$T.assertThat(persistence.binds.get($L)).isEqualTo(id)",
                        ASSERTIONS, whereIdIndex);
        return test.build();
    }

    private MethodSpec findByIdEmptyTest(ClassName repositoryType, ClassName persistenceType) {
        return test("findByIdIsEmptyWhenTheQueryReturnsNoRow")
                .addStatement("$T persistence = new $T()", persistenceType, persistenceType)
                .addStatement("$T repository = new $T(persistence)", repositoryType, repositoryType)
                .addStatement("$T.assertThat(repository.findById($T.fromString($S))).isEmpty()",
                        ASSERTIONS, UUID, KernelTestSamples.FIXED_ID)
                .build();
    }

    private MethodSpec updateRejectsTest(ClassName entityType, ClassName repositoryType,
                                         ClassName persistenceType, DomainMetadata metadata) {
        MethodSpec.Builder test = test("updateRejectsWhenNoRowMatched")
                .addJavadoc("Zero rows affected is the row-is-gone (or, on a versioned entity, the\n")
                .addJavadoc("stale-version) case, and it must not pass for a silent no-op.\n")
                .addStatement("$T persistence = new $T()", persistenceType, persistenceType)
                .addStatement("persistence.rowsAffected = 0L")
                .addStatement("$T repository = new $T(persistence)", repositoryType, repositoryType)
                .addStatement("$T entity = new $T()", entityType, entityType);
        stageVersion(test, metadata, KernelRepositoryGenerator.columnLayout(metadata));
        // hasMessageContaining, not isInstanceOf(RuntimeException) alone: an NPE from an unstaged
        // field is also a RuntimeException, and would make this pass without the guard running.
        test.addStatement("$T.assertThatThrownBy(() -> repository.update($T.fromString($S), entity))"
                        + ".hasMessageContaining($S)",
                ASSERTIONS, UUID, KernelTestSamples.FIXED_ID, NOT_FOUND);
        return test.build();
    }

    private MethodSpec deleteRejectsTest(ClassName repositoryType, ClassName persistenceType) {
        return test("deleteByIdRejectsWhenNoRowMatched")
                .addStatement("$T persistence = new $T()", persistenceType, persistenceType)
                .addStatement("persistence.rowsAffected = 0L")
                .addStatement("$T repository = new $T(persistence)", repositoryType, repositoryType)
                .addStatement("$T.assertThatThrownBy(() -> repository.deleteById($T.fromString($S)))"
                                + ".hasMessageContaining($S)",
                        ASSERTIONS, UUID, KernelTestSamples.FIXED_ID, NOT_FOUND)
                .build();
    }

    private MethodSpec countTest(ClassName repositoryType, ClassName persistenceType) {
        return test("countReadsTheAggregateFromColumnZero")
                .addStatement("$T persistence = new $T()", persistenceType, persistenceType)
                .addStatement("persistence.row = $T.<$T, $T>of(0, $LL)",
                        MAP, INTEGER, OBJECT, KernelTestSamples.SAMPLE_NUMBER)
                .addStatement("$T repository = new $T(persistence)", repositoryType, repositoryType)
                .addStatement("$T.assertThat(repository.count()).isEqualTo($LL)",
                        ASSERTIONS, KernelTestSamples.SAMPLE_NUMBER)
                .build();
    }

    /**
     * A value to stage on the entity before saving, or {@code null} to leave the field alone.
     *
     * <p>The audit stamps are skipped because {@code save} overwrites them with
     * {@code Instant.now()}, and the soft-delete flag because staging it {@code true} would say
     * something this test does not mean. Both are still asserted on the way back — the round-trip
     * covers every column, staged or not; staging only makes the comparison sharper.
     */
    private CodeBlock stagedValue(Column column) {
        if (column.kind() == ColumnKind.CREATED_AT || column.kind() == ColumnKind.UPDATED_AT
                || column.kind() == ColumnKind.DELETED) {
            return null;
        }
        CodeBlock sample = KernelTestSamples.of(column.javaType());
        return KernelTestSamples.isNull(sample) ? null : sample;
    }

    /**
     * {@code update} reads {@code entity.getVersion()} into a {@code long} on a versioned entity, so
     * an unstaged {@code Long} would throw a null-unboxing NPE before the code under test runs.
     */
    private void stageVersion(MethodSpec.Builder test, DomainMetadata metadata, List<Column> columns) {
        if (!metadata.versioned()) {
            return;
        }
        columns.stream()
                .filter(c -> c.kind() == ColumnKind.VERSION)
                .findFirst()
                .ifPresent(c -> test.addStatement("entity.$L(0L)",
                        KernelRepositoryGenerator.setterFor(c)));
    }

    private static MethodSpec.Builder test(String name) {
        return MethodSpec.methodBuilder(name)
                .addAnnotation(TEST)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID);
    }
}
