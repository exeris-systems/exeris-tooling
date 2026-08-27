package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.java.kernel.KernelRepositoryGenerator.Column;
import eu.exeris.tooling.codegen.java.kernel.KernelRepositoryGenerator.ColumnKind;
import eu.exeris.tooling.codegen.java.support.KernelScaffold;

import static eu.exeris.tooling.codegen.java.support.DataScopeSupport.isTenantPartitioned;

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

    private static final ClassName KERNEL_PROVIDERS =
            ClassName.get("eu.exeris.kernel.spi.context", "KernelProviders");
    private static final ClassName STORAGE_CONTEXT =
            ClassName.get("eu.exeris.kernel.spi.security", "StorageContext");
    private static final ClassName IMMUTABLE_STORAGE_CONTEXT =
            ClassName.get("eu.exeris.kernel.spi.security", "ImmutableStorageContext");
    private static final ClassName SCOPED_VALUE = ClassName.get("java.lang", "ScopedValue");

    /**
     * The tenant the generated tests bind. Deliberately <em>not</em>
     * {@link KernelTestSamples#FIXED_ID}: the stamp test has to be able to tell a tenant that came
     * from the bound context apart from one that happened to be staged on the entity, and it cannot
     * if both are the same UUID.
     */
    private static final String TENANT_KEY = "00000000-0000-4000-8000-000000000002";
    private static final String AS_TENANT = "asTenant";

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
                .addJavadoc("<p>Requires JUnit 5 and AssertJ on the test classpath, and nothing\n")
                .addJavadoc("else beyond what the repository under test already needs.\n")
                .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain models.\n");

        boolean tenantScoped = isTenantPartitioned(metadata);
        if (tenantScoped) {
            type.addField(FieldSpec.builder(String.class, "TENANT_KEY",
                                    Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                            .initializer("$S", TENANT_KEY)
                            .build())
                    .addField(FieldSpec.builder(STORAGE_CONTEXT, "TENANT_SCOPE",
                                    Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                            .initializer("$T.shared(TENANT_KEY)", IMMUTABLE_STORAGE_CONTEXT)
                            .build());
        }

        type.addMethod(roundTripTest(entityType, repositoryType, persistenceType, columns,
                tenantScoped));
        type.addMethod(saveFillsIdTest(entityType, repositoryType, persistenceType, tenantScoped));
        if (tenantScoped) {
            type.addMethod(saveStampsTenantTest(entityType, repositoryType, persistenceType,
                    columns));
            type.addMethod(saveKeepsCallerTenantTest(entityType, repositoryType, persistenceType,
                    columns));
        }
        type.addMethod(updateBindsIdTest(entityType, repositoryType, persistenceType, columns,
                tenantScoped));
        type.addMethod(findByIdEmptyTest(repositoryType, persistenceType));
        // ADR-076: update reports a versioned entity's zero-row outcome as a conflict, because
        // it matched on id and version together; deleteById matched on id alone and can only
        // ever report a missing row.
        ClassName notFoundType = KernelErrorGenerator.notFoundType(metadata);
        ClassName conflictType = KernelErrorGenerator.versionConflictType(metadata);
        type.addMethod(updateRejectsTest(entityType, repositoryType, persistenceType, tenantScoped,
                conflictType != null ? conflictType : notFoundType));
        type.addMethod(deleteRejectsTest(repositoryType, persistenceType, notFoundType));
        type.addMethod(countTest(repositoryType, persistenceType));
        if (tenantScoped) {
            type.addMethod(asTenantHelper());
        }

        return new GeneratedFile(packageName, className,
                KernelScaffold.render(packageName, type.build()), ArtifactType.TEST);
    }

    /** The central test — see the class Javadoc for why it is a round-trip and not a SQL check. */
    private MethodSpec roundTripTest(ClassName entityType, ClassName repositoryType,
                                     ClassName persistenceType, List<Column> columns,
                                     boolean tenantScoped) {
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

        test.addStatement(write("repository.save(original)", tenantScoped))
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
                                       ClassName persistenceType, boolean tenantScoped) {
        return test("saveGeneratesAMissingIdAndBindsItFirst")
                .addJavadoc("{@code id} is column 0 of the layout, so it is also parameter 0 of the\n")
                .addJavadoc("INSERT — and the value bound there is the one the caller can read back\n")
                .addJavadoc("off the entity afterwards.\n")
                .addStatement("$T persistence = new $T()", persistenceType, persistenceType)
                .addStatement("$T repository = new $T(persistence)", repositoryType, repositoryType)
                .addStatement("$T entity = new $T()", entityType, entityType)
                .addStatement("$T.assertThat(entity.getId()).isNull()", ASSERTIONS)
                .addStatement(write("repository.save(entity)", tenantScoped))
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
                                         ClassName persistenceType, List<Column> columns,
                                         boolean tenantScoped) {
        // SET list = columns minus id, so the WHERE id lands one slot past its last entry.
        int whereIdIndex = columns.size() - 1;
        MethodSpec.Builder test = test("updateBindsTheIdAfterTheSetList")
                .addStatement("$T persistence = new $T()", persistenceType, persistenceType)
                .addStatement("$T repository = new $T(persistence)", repositoryType, repositoryType)
                .addStatement("$T entity = new $T()", entityType, entityType);
        test.addStatement("$T id = $T.fromString($S)", UUID, UUID, KernelTestSamples.FIXED_ID)
                .addStatement(write("repository.update(id, entity)", tenantScoped))
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
                                         ClassName persistenceType, boolean tenantScoped,
                                         ClassName rejectionType) {
        MethodSpec.Builder test = test("updateRejectsWhenNoRowMatched")
                .addJavadoc("Zero rows affected is the row-is-gone (or, on a versioned entity, the\n")
                .addJavadoc("stale-version) case, and it must not pass for a silent no-op.\n")
                .addStatement("$T persistence = new $T()", persistenceType, persistenceType)
                .addStatement("persistence.rowsAffected = 0L")
                .addStatement("$T repository = new $T(persistence)", repositoryType, repositoryType)
                .addStatement("$T entity = new $T()", entityType, entityType);
        // The type, not a message substring (ADR-076). The substring was here for a reason worth
        // keeping: isInstanceOf(RuntimeException) alone would also pass on an NPE from an unstaged
        // field, so the assertion has to exclude "some other RuntimeException". A dedicated type
        // does that exactly, where "not found" only did it by coincidence of wording — and it is
        // the same type the handler catches to answer 404/409 rather than 500.
        // Nothing is staged on the entity on purpose — on a versioned entity that also pins T26,
        // since update() reads the version off a freshly constructed instance.
        test.addStatement(write("$T.assertThatThrownBy(() -> repository.update($T.fromString($S), "
                        + "entity)).isInstanceOf($T.class)", tenantScoped),
                ASSERTIONS, UUID, KernelTestSamples.FIXED_ID, rejectionType);
        return test.build();
    }

    private MethodSpec deleteRejectsTest(ClassName repositoryType, ClassName persistenceType,
                                         ClassName notFoundType) {
        return test("deleteByIdRejectsWhenNoRowMatched")
                .addStatement("$T persistence = new $T()", persistenceType, persistenceType)
                .addStatement("persistence.rowsAffected = 0L")
                .addStatement("$T repository = new $T(persistence)", repositoryType, repositoryType)
                .addStatement("$T.assertThatThrownBy(() -> repository.deleteById($T.fromString($S)))"
                                + ".isInstanceOf($T.class)",
                        ASSERTIONS, UUID, KernelTestSamples.FIXED_ID, notFoundType)
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
     * Proves the stamp: a caller who sets no tenant gets the bound one, and it reaches the INSERT.
     *
     * <p>Asserting the entity alone would not be enough — a stamp applied after the binds were
     * recorded would still leave the row unowned — so the bind at the tenant column's index is
     * checked too. {@code TENANT_KEY} is deliberately not the sample UUID every other field is
     * staged with, so a value that came from the bound context cannot be confused with one that was
     * already there.
     */
    private MethodSpec saveStampsTenantTest(ClassName entityType, ClassName repositoryType,
                                            ClassName persistenceType, List<Column> columns) {
        Column tenant = tenantColumn(columns);
        String getter = KernelRepositoryGenerator.getterFor(tenant);
        return test("saveStampsTheActingTenantWhenTheCallerLeftItUnset")
                .addJavadoc("The emitted handler decodes a request body straight into the entity and\n")
                .addJavadoc("the emitted form treats the tenant as a system field it never sends, so\n")
                .addJavadoc("a caller leaving it unset is the ordinary path rather than the\n")
                .addJavadoc("exceptional one. An unstamped row is refused by this table's\n")
                .addJavadoc("row-level-security policy — reported as a security violation, which is\n")
                .addJavadoc("the least informative way to report a missing default.\n")
                .addStatement("$T persistence = new $T()", persistenceType, persistenceType)
                .addStatement("$T repository = new $T(persistence)", repositoryType, repositoryType)
                .addStatement("$T entity = new $T()", entityType, entityType)
                .addStatement("$T.assertThat(entity.$L()).isNull()", ASSERTIONS, getter)
                .addStatement("$L(() -> repository.save(entity))", AS_TENANT)
                .addStatement("$T.assertThat(entity.$L()).isEqualTo($T.fromString(TENANT_KEY))",
                        ASSERTIONS, getter, UUID)
                .addStatement("$T.assertThat(persistence.binds.get($L)).isEqualTo(entity.$L())",
                        ASSERTIONS, columns.indexOf(tenant), getter)
                .build();
    }

    /**
     * The other half of the contract: filling is not overwriting.
     *
     * <p>Whether a caller-supplied tenant is one this deployment may write is the RLS
     * {@code WITH CHECK} predicate's decision, not the repository's — re-deciding it here would be a
     * second implementation of a rule the database already enforces, and the two would drift.
     */
    private MethodSpec saveKeepsCallerTenantTest(ClassName entityType, ClassName repositoryType,
                                                 ClassName persistenceType, List<Column> columns) {
        Column tenant = tenantColumn(columns);
        return test("saveKeepsATenantTheCallerSet")
                .addJavadoc("A tenant the caller set survives the save — the stamp fills a gap, it\n")
                .addJavadoc("does not override an intent.\n")
                .addStatement("$T persistence = new $T()", persistenceType, persistenceType)
                .addStatement("$T repository = new $T(persistence)", repositoryType, repositoryType)
                .addStatement("$T entity = new $T()", entityType, entityType)
                .addStatement("$T callerTenant = $T.fromString($S)", UUID, UUID,
                        KernelTestSamples.FIXED_ID)
                .addStatement("entity.$L(callerTenant)", KernelRepositoryGenerator.setterFor(tenant))
                .addStatement("$L(() -> repository.save(entity))", AS_TENANT)
                .addStatement("$T.assertThat(entity.$L()).isEqualTo(callerTenant)",
                        ASSERTIONS, KernelRepositoryGenerator.getterFor(tenant))
                .build();
    }

    /** Emits the tenant-binding helper the write tests run inside. */
    private MethodSpec asTenantHelper() {
        return MethodSpec.methodBuilder(AS_TENANT)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(TypeName.VOID)
                .addParameter(Runnable.class, "work")
                .addJavadoc("Runs {@code work} with a tenant bound, the way a request reaches the\n")
                .addJavadoc("repository: the kernel's SecurityInterceptor binds\n")
                .addJavadoc("{@code STORAGE_CONTEXT} for the duration of the dispatch, and the\n")
                .addJavadoc("repository resolves the acting tenant out of it.\n")
                .addStatement("$T.where($T.STORAGE_CONTEXT, TENANT_SCOPE).run(work)",
                        SCOPED_VALUE, KERNEL_PROVIDERS)
                .build();
    }

    /**
     * Wraps a write in the tenant scope when the entity is tenant-partitioned. A tenant-scoped
     * repository resolves the acting tenant from the ambient {@code StorageContext} on every write
     * (T36), so a write made outside a bound scope throws before it reaches the double — failing
     * these tests for a reason none of them is about.
     */
    private static String write(String statement, boolean tenantScoped) {
        return tenantScoped ? AS_TENANT + "(() -> " + statement + ")" : statement;
    }

    /** The layout's tenant column; only called for an entity whose metadata emits one. */
    private static Column tenantColumn(List<Column> columns) {
        return columns.stream()
                .filter(c -> c.kind() == ColumnKind.TENANT_ID)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "tenant-partitioned entity with no tenant column in the layout"));
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

    private static MethodSpec.Builder test(String name) {
        return MethodSpec.methodBuilder(name)
                .addAnnotation(TEST)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID);
    }
}
