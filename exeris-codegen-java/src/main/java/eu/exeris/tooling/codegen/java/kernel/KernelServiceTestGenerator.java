package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.java.kernel.KernelRepositoryGenerator.FinderSpec;
import eu.exeris.tooling.codegen.java.support.KernelScaffold;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Emits {@code <Entity>ServiceTest} — the generated test for the generated service (T2, ADR-058).
 *
 * <h2>What it covers, and why that is worth covering</h2>
 * <p>The service is a delegation layer, so its contract is exactly <em>which</em> repository method
 * each call reaches and <em>what</em> comes back. Three of those are hand-wired rather than
 * mechanical, and each fails silently if a regeneration gets it wrong:
 *
 * <ul>
 *   <li>{@code delete(id)} delegates to {@code deleteById(id)} — the one name that changes across
 *       the boundary.</li>
 *   <li>{@code save} / {@code update} return the <em>repository's</em> result, not the argument
 *       they were handed. The repository's {@code save} fills in a generated id (and, on an audited
 *       entity, the audit stamps) before returning; a service that returned its own parameter would
 *       compile, pass any type check, and hand callers an entity with a null id.</li>
 *   <li>Each T8 finder reaches the same-named repository finder, with the argument passed through.
 *       That the method <em>exists</em> is a compile-time fact; that the call is wired to the right
 *       one is not.</li>
 * </ul>
 *
 * <p>Both surfaces are emitted from {@link KernelRepositoryGenerator#finderSpecs}, so a double that
 * overrides a finder the service never calls — the way this test could quietly stop testing
 * anything — is not expressible.
 *
 * <h2>The repository double</h2>
 * <p>A nested {@code Stub<Entity>Repository} subclasses the generated repository and overrides
 * every method the service delegates to. Same contract as ADR-058's service double: the generated
 * repository is {@code public}, non-final, and its constructor only assigns the
 * {@code TransactionalExecutor}, so {@code super(null)} is safe and no persistence engine — no
 * driver, no database, no {@code ScopedValue} provider slot — is involved.
 *
 * <p>One thing the subclass does inherit is the repository's static initialisation. For an entity
 * with a collection field that constructs the Jackson mapper the repository uses for JSON columns.
 * That adds nothing to the ADR-058 dependency contract: such an entity's generated <em>main</em>
 * code already imports Jackson, so a consumer who can compile it can run this.
 *
 * <p>Per entity, like the service it covers. Deterministic: the emitted source is a pure function
 * of the metadata (hard-constraint #3) — fixed {@code UUID} literals rather than
 * {@code UUID.randomUUID()}, and the finder order is the repository's.
 *
 * @implNote Emission is JavaPoet-based (ADR-015).
 * @since 0.7.0
 */
public final class KernelServiceTestGenerator {

    /**
     * Fixed identifiers for the id-carrying calls. Literals rather than {@code UUID.randomUUID()}
     * for the same reason as the handler test: a random id is still deterministic <em>as text</em>,
     * but it makes the generated test's own failure output differ run to run for no benefit.
     */
    private static final String FIXED_ID = "00000000-0000-4000-8000-000000000001";

    /** The count staged on the double — an arbitrary non-zero, so a {@code 0} default cannot pass. */
    private static final long STAGED_COUNT = 7L;

    private static final ClassName TEST = ClassName.get("org.junit.jupiter.api", "Test");
    private static final ClassName ASSERTIONS = ClassName.get("org.assertj.core.api", "Assertions");
    private static final ClassName TRANSACTIONAL_EXECUTOR =
            ClassName.get("eu.exeris.kernel.spi.persistence", "TransactionalExecutor");
    private static final ClassName UUID = ClassName.get("java.util", "UUID");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName OPTIONAL = ClassName.get("java.util", "Optional");

    /** @param metadata the entity whose service is under test */
    public GeneratedFile generate(DomainMetadata metadata) {
        String entity = metadata.entityName();
        String domainPackage = metadata.packageName();
        if (!domainPackage.endsWith(".domain")) {
            // Same contract (and same reason) as the handler-test generator: the service and
            // repository package paths are derived by replacing the '.domain' suffix.
            throw new IllegalArgumentException(
                    "Domain package '" + domainPackage + "' for entity '" + entity
                            + "' does not end with '.domain' — the service-test generator derives"
                            + " the .service/.repository package paths from that suffix");
        }
        String infrastructureBase = domainPackage.substring(0, domainPackage.lastIndexOf(".domain"));
        String packageName = infrastructureBase + ".service";
        String className = entity + "ServiceTest";

        ClassName entityType = ClassName.get(domainPackage, entity);
        ClassName serviceType = ClassName.get(packageName, entity + "Service");
        ClassName repositoryType =
                ClassName.get(infrastructureBase + ".repository", entity + "Repository");
        ClassName stubType = ClassName.bestGuess("Stub" + entity + "Repository");
        List<FinderSpec> finders = KernelRepositoryGenerator.finderSpecs(metadata);

        TypeSpec.Builder type = KernelScaffold.publicClass(className)
                .addJavadoc("Generated tests for {@link $T}.\n", serviceType)
                .addJavadoc("<p>Covers what the service actually owes its callers: which repository\n")
                .addJavadoc("method each call reaches, and that the repository's result — not the\n")
                .addJavadoc("argument — is what comes back.\n")
                .addJavadoc("<p>Requires JUnit 5 and AssertJ on the test classpath, and nothing else.\n")
                .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain models.\n");

        type.addMethod(findByIdTest(entityType, serviceType, stubType));
        type.addMethod(findAllTest(entityType, serviceType, stubType));
        for (FinderSpec finder : finders) {
            type.addMethod(finderTest(entityType, serviceType, stubType, finder));
        }
        type.addMethod(saveTest(entityType, serviceType, stubType));
        type.addMethod(updateTest(entityType, serviceType, stubType));
        type.addMethod(deleteTest(serviceType, stubType));
        type.addMethod(countTest(serviceType, stubType));
        type.addType(stubRepository(entityType, repositoryType, stubType, finders));

        return new GeneratedFile(packageName, className,
                KernelScaffold.render(packageName, type.build()), ArtifactType.TEST);
    }

    private MethodSpec findByIdTest(ClassName entityType, ClassName serviceType, ClassName stubType) {
        return test("findByIdDelegatesTheIdAndReturnsTheRepositoryResult")
                .addStatement("$T found = new $T()", entityType, entityType)
                .addStatement("$T repository = new $T()", stubType, stubType)
                .addStatement("repository.byId = $T.of(found)", OPTIONAL)
                .addStatement("$T service = new $T(repository)", serviceType, serviceType)
                .addStatement("$T.assertThat(service.findById($T.fromString($S))).containsSame(found)",
                        ASSERTIONS, UUID, FIXED_ID)
                .addStatement("$T.assertThat(repository.lookedUp).isEqualTo($T.fromString($S))",
                        ASSERTIONS, UUID, FIXED_ID)
                .build();
    }

    private MethodSpec findAllTest(ClassName entityType, ClassName serviceType, ClassName stubType) {
        return test("findAllReturnsTheRepositoryResult")
                .addStatement("$T repository = new $T()", stubType, stubType)
                .addStatement("repository.all = $T.of(new $T())", LIST, entityType)
                .addStatement("$T service = new $T(repository)", serviceType, serviceType)
                .addStatement("$T.assertThat(service.findAll()).isSameAs(repository.all)", ASSERTIONS)
                .build();
    }

    /**
     * One per T8 finder: the call reaches the same-named repository finder, and the argument
     * arrives unchanged. The recorded finder name is what makes this non-vacuous — that the method
     * compiles proves it exists, not that the delegation is wired to the right one.
     */
    private MethodSpec finderTest(ClassName entityType, ClassName serviceType, ClassName stubType,
                                  FinderSpec finder) {
        CodeBlock sample = sampleArgument(finder.paramTypeName());
        MethodSpec.Builder test = test(finder.methodName() + "DelegatesToTheRepository")
                .addStatement("$T repository = new $T()", stubType, stubType)
                .addStatement("repository.byFinder = $T.of(new $T())", LIST, entityType)
                .addStatement("$T service = new $T(repository)", serviceType, serviceType)
                .addStatement("$T.assertThat(service.$L($L)).isSameAs(repository.byFinder)",
                        ASSERTIONS, finder.methodName(), sample)
                .addStatement("$T.assertThat(repository.lastFinder).isEqualTo($S)",
                        ASSERTIONS, finder.methodName());
        if (!isNullLiteral(sample)) {
            test.addStatement("$T.assertThat(repository.lastArgument).isEqualTo($L)",
                    ASSERTIONS, sample);
        }
        return test.build();
    }

    private MethodSpec saveTest(ClassName entityType, ClassName serviceType, ClassName stubType) {
        return test("saveReturnsWhatTheRepositoryReturnedNotItsArgument")
                .addJavadoc("The repository's {@code save} fills in a generated id before returning,\n")
                .addJavadoc("so a service that handed back its own argument would give callers an\n")
                .addJavadoc("entity with a null id — and still compile. Two distinct instances make\n")
                .addJavadoc("that substitution visible.\n")
                .addStatement("$T argument = new $T()", entityType, entityType)
                .addStatement("$T persisted = new $T()", entityType, entityType)
                .addStatement("$T repository = new $T()", stubType, stubType)
                .addStatement("repository.saveResult = persisted")
                .addStatement("$T service = new $T(repository)", serviceType, serviceType)
                .addStatement("$T.assertThat(service.save(argument)).isSameAs(persisted)", ASSERTIONS)
                .addStatement("$T.assertThat(repository.saved).isSameAs(argument)", ASSERTIONS)
                .build();
    }

    private MethodSpec updateTest(ClassName entityType, ClassName serviceType, ClassName stubType) {
        return test("updateDelegatesBothArgumentsAndReturnsTheRepositoryResult")
                .addStatement("$T argument = new $T()", entityType, entityType)
                .addStatement("$T persisted = new $T()", entityType, entityType)
                .addStatement("$T repository = new $T()", stubType, stubType)
                .addStatement("repository.updateResult = persisted")
                .addStatement("$T service = new $T(repository)", serviceType, serviceType)
                .addStatement("$T.assertThat(service.update($T.fromString($S), argument))"
                        + ".isSameAs(persisted)", ASSERTIONS, UUID, FIXED_ID)
                .addStatement("$T.assertThat(repository.updatedId).isEqualTo($T.fromString($S))",
                        ASSERTIONS, UUID, FIXED_ID)
                .addStatement("$T.assertThat(repository.updated).isSameAs(argument)", ASSERTIONS)
                .build();
    }

    private MethodSpec deleteTest(ClassName serviceType, ClassName stubType) {
        return test("deleteDelegatesToDeleteById")
                .addJavadoc("The one method whose name changes across the boundary.\n")
                .addStatement("$T repository = new $T()", stubType, stubType)
                .addStatement("$T service = new $T(repository)", serviceType, serviceType)
                .addStatement("service.delete($T.fromString($S))", UUID, FIXED_ID)
                .addStatement("$T.assertThat(repository.deleted).isEqualTo($T.fromString($S))",
                        ASSERTIONS, UUID, FIXED_ID)
                .build();
    }

    private MethodSpec countTest(ClassName serviceType, ClassName stubType) {
        return test("countReturnsTheRepositoryCount")
                .addStatement("$T repository = new $T()", stubType, stubType)
                .addStatement("repository.count = $LL", STAGED_COUNT)
                .addStatement("$T service = new $T(repository)", serviceType, serviceType)
                .addStatement("$T.assertThat(service.count()).isEqualTo($LL)", ASSERTIONS, STAGED_COUNT)
                .build();
    }

    /**
     * A value of the finder's parameter type to pass through.
     *
     * <p>Dispatches on the metadata type string, the way {@link KernelTypeMapping} does, rather
     * than on the resolved {@link TypeName}: an unqualified {@code String} field resolves through
     * {@code ClassName.bestGuess} to a default-package {@code String} — which compiles, because
     * the emitted source says {@code String}, but is not equal to {@code ClassName.get(String
     * .class)}. Matching the string keeps the two dispatches on the same input.
     *
     * <p>{@code UUID} and {@code String} — between them nearly every filterable field — and the
     * numeric/boolean types get a real value, so the pass-through assertion has something to
     * compare. Every other type ({@code BigDecimal}, a temporal, an enum) gets {@code null}:
     * synthesizing an instance would mean knowing an enum constant, and the assertion this test
     * actually rests on is the recorded finder name, not the argument.
     */
    private CodeBlock sampleArgument(String paramTypeName) {
        return switch (paramTypeName) {
            case "UUID", "java.util.UUID" -> CodeBlock.of("$T.fromString($S)", UUID, FIXED_ID);
            case "String", "java.lang.String" -> CodeBlock.of("$S", "sample");
            case "boolean", "Boolean", "java.lang.Boolean" -> CodeBlock.of("true");
            case "int", "Integer", "java.lang.Integer" -> CodeBlock.of("$L", STAGED_COUNT);
            case "long", "Long", "java.lang.Long" -> CodeBlock.of("$LL", STAGED_COUNT);
            case "double", "Double", "java.lang.Double" -> CodeBlock.of("$L.0", STAGED_COUNT);
            default -> CodeBlock.of("null");
        };
    }

    private boolean isNullLiteral(CodeBlock sample) {
        return "null".equals(sample.toString());
    }

    /**
     * The nested repository double. Fields are package-private and set directly by each test — a
     * generated double has no callers to protect, and accessors would be noise.
     */
    private TypeSpec stubRepository(ClassName entityType, ClassName repositoryType,
                                    ClassName stubType, List<FinderSpec> finders) {
        TypeName listOfEntity = ParameterizedTypeName.get(LIST, entityType);
        TypeName optionalOfEntity = ParameterizedTypeName.get(OPTIONAL, entityType);

        TypeSpec.Builder stub = TypeSpec.classBuilder(stubType.simpleName())
                .addModifiers(Modifier.STATIC, Modifier.FINAL)
                .superclass(repositoryType)
                .addJavadoc("Records what the service asked for and returns what the test staged.\n")
                .addJavadoc("<p>{@code super(null)} is safe: the generated repository constructor\n")
                .addJavadoc("only assigns the {@code TransactionalExecutor}, and no method\n")
                .addJavadoc("overridden here reads it — so no database, driver or transaction is\n")
                .addJavadoc("involved in a service test.\n")
                .addField(FieldSpec.builder(listOfEntity, "all").initializer("$T.of()", LIST).build())
                .addField(FieldSpec.builder(optionalOfEntity, "byId")
                        .initializer("$T.empty()", OPTIONAL).build())
                .addField(FieldSpec.builder(listOfEntity, "byFinder")
                        .initializer("$T.of()", LIST).build())
                .addField(FieldSpec.builder(entityType, "saveResult").build())
                .addField(FieldSpec.builder(entityType, "updateResult").build())
                .addField(FieldSpec.builder(TypeName.LONG, "count").build())
                .addField(FieldSpec.builder(UUID, "lookedUp").build())
                .addField(FieldSpec.builder(UUID, "deleted").build())
                .addField(FieldSpec.builder(entityType, "saved").build())
                .addField(FieldSpec.builder(UUID, "updatedId").build())
                .addField(FieldSpec.builder(entityType, "updated").build())
                .addField(FieldSpec.builder(String.class, "lastFinder").build())
                .addField(FieldSpec.builder(Object.class, "lastArgument").build())
                .addMethod(MethodSpec.constructorBuilder()
                        .addStatement("super(($T) null)", TRANSACTIONAL_EXECUTOR)
                        .build())
                .addMethod(MethodSpec.methodBuilder("findAll")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(listOfEntity)
                        .addStatement("return all")
                        .build())
                .addMethod(MethodSpec.methodBuilder("findById")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(optionalOfEntity)
                        .addParameter(UUID, "id")
                        .addStatement("this.lookedUp = id")
                        .addStatement("return byId")
                        .build());

        for (FinderSpec finder : finders) {
            stub.addMethod(MethodSpec.methodBuilder(finder.methodName())
                    .addAnnotation(Override.class)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(listOfEntity)
                    .addParameter(finder.paramType(), finder.paramName())
                    .addStatement("this.lastFinder = $S", finder.methodName())
                    .addStatement("this.lastArgument = $L", finder.paramName())
                    .addStatement("return byFinder")
                    .build());
        }

        return stub.addMethod(MethodSpec.methodBuilder("save")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(entityType)
                        .addParameter(entityType, "entity")
                        .addStatement("this.saved = entity")
                        .addStatement("return saveResult")
                        .build())
                .addMethod(MethodSpec.methodBuilder("update")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(entityType)
                        .addParameter(UUID, "id")
                        .addParameter(entityType, "entity")
                        .addStatement("this.updatedId = id")
                        .addStatement("this.updated = entity")
                        .addStatement("return updateResult")
                        .build())
                .addMethod(MethodSpec.methodBuilder("deleteById")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(UUID, "id")
                        .addStatement("this.deleted = id")
                        .build())
                .addMethod(MethodSpec.methodBuilder("count")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(TypeName.LONG)
                        .addStatement("return count")
                        .build())
                .build();
    }

    private static MethodSpec.Builder test(String name) {
        return MethodSpec.methodBuilder(name)
                .addAnnotation(TEST)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID);
    }
}
