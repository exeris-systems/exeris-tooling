package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.java.support.KernelScaffold;

import javax.lang.model.element.Modifier;

/**
 * Emits {@code <Entity>HandlerTest} — the generated test for the generated handler
 * (T2 slice a, ADR-058).
 *
 * <h2>What it covers, and why only that</h2>
 * <p>The three bodyless CRUD routes: {@code handleGetAll}, {@code handleGetById} (found, absent,
 * and malformed-id) and {@code handleDelete}. Each asserts the response <em>status</em>, which is
 * this handler's actual contract with the router — a regeneration that reorders a guard or drops
 * the 404 branch changes a status, and this catches it.
 *
 * <p>{@code handleCreate} / {@code handleUpdate} are deliberately absent from this slice: both read
 * the request body, which arrives as a {@link eu.exeris.kernel.spi.memory.LoanedBuffer}, so testing
 * them needs a heap-backed buffer double on top of the exchange double. That is a bigger emitted
 * surface and it carries the {@code @Validation} rejection paths with it, so it is its own slice
 * rather than a rushed addition to this one.
 *
 * <h2>The service double</h2>
 * <p>A nested {@code Stub<Entity>Service} subclasses the generated service and overrides the three
 * methods under test. Subclassing rather than mocking is what keeps the dependency contract at
 * JUnit 5 + AssertJ (ADR-058): the generated service is {@code public}, non-final, and its
 * constructor only assigns the repository, so {@code super(null)} is safe and no repository —
 * hence no persistence stack — is ever touched.
 *
 * <p>Per entity, like the handler it covers. Deterministic: the emitted source is a pure function
 * of the entity name and package (hard-constraint #3), and the one {@code UUID} the tests need is
 * a fixed literal rather than {@code UUID.randomUUID()}, so regenerating twice is byte-identical.
 *
 * @implNote Emission is JavaPoet-based (ADR-015).
 * @since 0.7.0
 */
public final class KernelHandlerTestGenerator {

    /**
     * A fixed identifier for the path-parameter tests. Deliberately a literal: a
     * {@code UUID.randomUUID()} in the emitted source would still be deterministic <em>as text</em>,
     * but it would make the generated test's own failure output differ run to run for no benefit.
     */
    private static final String FIXED_ID = "00000000-0000-4000-8000-000000000001";

    private static final ClassName TEST = ClassName.get("org.junit.jupiter.api", "Test");
    private static final ClassName ASSERTIONS = ClassName.get("org.assertj.core.api", "Assertions");
    private static final ClassName HTTP_STATUS = ClassName.get("eu.exeris.kernel.spi.http", "HttpStatus");
    private static final ClassName UUID = ClassName.get("java.util", "UUID");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName OPTIONAL = ClassName.get("java.util", "Optional");

    /**
     * @param metadata    the entity whose handler is under test
     * @param basePackage the project base package (the {@code testsupport} package is resolved
     *                    from it, since the exchange double is project-wide)
     * @return the emitted test; never {@code null}
     */
    public GeneratedFile generate(DomainMetadata metadata, String basePackage) {
        String entity = metadata.entityName();
        String domainPackage = metadata.packageName();
        if (!domainPackage.endsWith(".domain")) {
            // Same contract (and same reason) as KernelApplicationGenerator: the handler/service/
            // repository package paths are derived by replacing the '.domain' suffix, so without it
            // the emitted test would import types from the wrong packages.
            throw new IllegalArgumentException(
                    "Domain package '" + domainPackage + "' for entity '" + entity
                            + "' does not end with '.domain' — the handler-test generator derives the"
                            + " .handler/.service/.repository package paths from that suffix");
        }
        String infrastructureBase = domainPackage.substring(0, domainPackage.lastIndexOf(".domain"));
        String packageName = infrastructureBase + ".handler";
        String className = entity + "HandlerTest";

        ClassName entityType = ClassName.get(domainPackage, entity);
        ClassName handlerType = ClassName.get(packageName, entity + "Handler");
        ClassName serviceType = ClassName.get(infrastructureBase + ".service", entity + "Service");
        ClassName repositoryType = ClassName.get(infrastructureBase + ".repository", entity + "Repository");
        ClassName exchangeType = ClassName.get(
                KernelTestSupportGenerator.supportPackage(basePackage),
                KernelTestSupportGenerator.RECORDING_EXCHANGE);
        ClassName stubType = ClassName.bestGuess("Stub" + entity + "Service");
        String basePath = metadata.effectivePath();

        TypeSpec.Builder type = KernelScaffold.publicClass(className)
                .addJavadoc("Generated tests for {@link $T}.\n", handlerType)
                .addJavadoc("<p>Covers the bodyless CRUD routes — the status each one owes the\n")
                .addJavadoc("router. Body-carrying routes ({@code handleCreate} /\n")
                .addJavadoc("{@code handleUpdate}) and the {@code @Validation} rejection paths are\n")
                .addJavadoc("not covered here; they need a request-body double.\n")
                .addJavadoc("<p>Requires JUnit 5 and AssertJ on the test classpath, and nothing else.\n")
                .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain models.\n");

        type.addMethod(getAllTest(entity, entityType, handlerType, exchangeType, stubType, basePath));
        type.addMethod(getByIdFoundTest(entity, entityType, handlerType, exchangeType, stubType, basePath));
        type.addMethod(getByIdAbsentTest(entity, handlerType, exchangeType, stubType, basePath));
        type.addMethod(getByIdMalformedTest(entity, handlerType, exchangeType, stubType, basePath));
        type.addMethod(deleteTest(entity, handlerType, exchangeType, stubType, basePath));
        type.addType(stubService(entity, entityType, serviceType, repositoryType, stubType));

        return new GeneratedFile(packageName, className,
                KernelScaffold.render(packageName, type.build()), ArtifactType.TEST);
    }

    private MethodSpec getAllTest(String entity, ClassName entityType, ClassName handlerType,
                                  ClassName exchangeType, ClassName stubType, String basePath) {
        return test("handleGetAllRespondsOkWithTheServiceResult")
                .addStatement("$T service = new $T()", stubType, stubType)
                .addStatement("service.all = $T.of(new $T())", LIST, entityType)
                .addStatement("$T handler = new $T(service)", handlerType, handlerType)
                .addStatement("$T exchange = $T.get($S)", exchangeType, exchangeType, basePath)
                .addStatement("handler.handleGetAll(exchange)")
                .addStatement("$T.assertThat(exchange.status()).isEqualTo($T.OK)", ASSERTIONS, HTTP_STATUS)
                .addStatement("$T.assertThat(exchange.body()).isEqualTo(service.all)", ASSERTIONS)
                .build();
    }

    private MethodSpec getByIdFoundTest(String entity, ClassName entityType, ClassName handlerType,
                                        ClassName exchangeType, ClassName stubType, String basePath) {
        return test("handleGetByIdRespondsOkWhenTheEntityExists")
                .addStatement("$T found = new $T()", entityType, entityType)
                .addStatement("$T service = new $T()", stubType, stubType)
                .addStatement("service.byId = $T.of(found)", OPTIONAL)
                .addStatement("$T handler = new $T(service)", handlerType, handlerType)
                .addStatement("$T exchange = $T.get($S).withPathParam($S, $S)",
                        exchangeType, exchangeType, basePath + "/" + FIXED_ID, "id", FIXED_ID)
                .addStatement("handler.handleGetById(exchange)")
                .addStatement("$T.assertThat(exchange.status()).isEqualTo($T.OK)", ASSERTIONS, HTTP_STATUS)
                .addStatement("$T.assertThat(exchange.body()).isSameAs(found)", ASSERTIONS)
                .build();
    }

    private MethodSpec getByIdAbsentTest(String entity, ClassName handlerType, ClassName exchangeType,
                                         ClassName stubType, String basePath) {
        return test("handleGetByIdRespondsNotFoundWhenTheEntityIsAbsent")
                .addStatement("$T service = new $T()", stubType, stubType)
                .addStatement("service.byId = $T.empty()", OPTIONAL)
                .addStatement("$T handler = new $T(service)", handlerType, handlerType)
                .addStatement("$T exchange = $T.get($S).withPathParam($S, $S)",
                        exchangeType, exchangeType, basePath + "/" + FIXED_ID, "id", FIXED_ID)
                .addStatement("handler.handleGetById(exchange)")
                .addStatement("$T.assertThat(exchange.status()).isEqualTo($T.NOT_FOUND)",
                        ASSERTIONS, HTTP_STATUS)
                .build();
    }

    private MethodSpec getByIdMalformedTest(String entity, ClassName handlerType, ClassName exchangeType,
                                            ClassName stubType, String basePath) {
        return test("handleGetByIdRespondsBadRequestOnAMalformedId")
                .addJavadoc("The id guard runs before the service is consulted, so a malformed path\n")
                .addJavadoc("parameter must never reach it.\n")
                .addStatement("$T service = new $T()", stubType, stubType)
                .addStatement("$T handler = new $T(service)", handlerType, handlerType)
                .addStatement("$T exchange = $T.get($S).withPathParam($S, $S)",
                        exchangeType, exchangeType, basePath + "/not-a-uuid", "id", "not-a-uuid")
                .addStatement("handler.handleGetById(exchange)")
                .addStatement("$T.assertThat(exchange.status()).isEqualTo($T.BAD_REQUEST)",
                        ASSERTIONS, HTTP_STATUS)
                .addStatement("$T.assertThat(service.lookedUp).isNull()", ASSERTIONS)
                .build();
    }

    private MethodSpec deleteTest(String entity, ClassName handlerType, ClassName exchangeType,
                                  ClassName stubType, String basePath) {
        return test("handleDeleteRespondsNoContentAndDelegatesTheId")
                .addStatement("$T service = new $T()", stubType, stubType)
                .addStatement("$T handler = new $T(service)", handlerType, handlerType)
                .addStatement("$T exchange = $T.delete($S).withPathParam($S, $S)",
                        exchangeType, exchangeType, basePath + "/" + FIXED_ID, "id", FIXED_ID)
                .addStatement("handler.handleDelete(exchange)")
                .addStatement("$T.assertThat(exchange.status()).isEqualTo($T.NO_CONTENT)",
                        ASSERTIONS, HTTP_STATUS)
                .addStatement("$T.assertThat(service.deleted).isEqualTo($T.fromString($S))",
                        ASSERTIONS, UUID, FIXED_ID)
                .build();
    }

    /**
     * The nested service double. Fields are package-private and set directly by each test — a
     * generated double has no callers to protect, and accessors would be noise.
     */
    private TypeSpec stubService(String entity, ClassName entityType, ClassName serviceType,
                                 ClassName repositoryType, ClassName stubType) {
        TypeName listOfEntity = ParameterizedTypeName.get(LIST, entityType);
        TypeName optionalOfEntity = ParameterizedTypeName.get(OPTIONAL, entityType);

        return TypeSpec.classBuilder(stubType.simpleName())
                .addModifiers(Modifier.STATIC, Modifier.FINAL)
                .superclass(serviceType)
                .addJavadoc("Records what the handler asked for and returns what the test staged.\n")
                .addJavadoc("<p>{@code super(null)} is safe: the generated service constructor only\n")
                .addJavadoc("assigns the repository, and no method overridden here reads it — so no\n")
                .addJavadoc("persistence engine is involved in a handler test.\n")
                .addField(FieldSpec.builder(listOfEntity, "all")
                        .initializer("$T.of()", LIST).build())
                .addField(FieldSpec.builder(optionalOfEntity, "byId")
                        .initializer("$T.empty()", OPTIONAL).build())
                .addField(FieldSpec.builder(UUID, "lookedUp").build())
                .addField(FieldSpec.builder(UUID, "deleted").build())
                .addMethod(MethodSpec.constructorBuilder()
                        .addStatement("super(($T) null)", repositoryType)
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
                        .build())
                .addMethod(MethodSpec.methodBuilder("delete")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(UUID, "id")
                        .addStatement("this.deleted = id")
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
