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
import eu.exeris.tooling.codegen.java.support.DataScopeSupport;
import eu.exeris.tooling.codegen.java.support.KernelScaffold;
import eu.exeris.tooling.codegen.java.support.PrimaryKeys;
import eu.exeris.tooling.codegen.java.support.ListQuerySupport;
import eu.exeris.tooling.codegen.java.support.NameCasing;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Emits {@code <Entity>HandlerTest} — the generated test for the generated handler (T2, ADR-058).
 *
 * <h2>What it covers, and why only that</h2>
 * <p>Every route's <em>status</em>, which is this handler's actual contract with the router — a
 * regeneration that reorders a guard or drops a branch changes a status, and this catches it.
 *
 * <p>Slice a: the bodyless routes — {@code handleGetAll} (the page, sort and filter it passes to
 * the service, and the query strings it refuses), {@code handleGetById} (found, absent,
 * malformed id) and {@code handleDelete}.
 *
 * <p>Slice b: the guard paths of the body-carrying routes. {@code handleCreate} and
 * {@code handleUpdate} both reject before reading the body — {@code parseBody} throws on
 * {@code hasBody() == false} ahead of resolving any decoder, and {@code handleUpdate}'s path-id
 * guard runs ahead of that again. So these three cases need no request-body double at all, and
 * each additionally asserts that the service was never reached.
 *
 * <p>The decode-failure paths: a body the decoder refuses as the caller's fault answers
 * {@code 400}; a decoder that fails for any other reason, and a decoder registry that is not
 * bound, answer {@code 500} (ADR-036 §2, kernel ADR-083).
 *
 * <p>Slice f: the paths <em>past</em> a successful decode — the {@code @Validation} guards. These
 * bind {@code RecordingRequestBody} into two kernel {@code ScopedValue} provider slots, which
 * ADR-058 permits because those slots live in {@code exeris-kernel-spi} — the artefact the generated
 * <em>main</em> code already requires. No driver, no bootstrap, no port; the dependency contract is
 * still JUnit 5 + AssertJ.
 *
 * <p>What those cases assert is the <em>boundary</em>, not the rule: a reject one step outside it
 * paired with an accept sitting exactly on it. A reject alone would survive an emitter that swapped
 * {@code <} for {@code <=}, since the rule and the probe value come from the same metadata. See
 * {@link #addValidationTests} for why the accept case is doing two jobs at once.
 *
 * <p>Tenant-partitioned entities: every route is dispatched inside a bound {@code StorageContext}
 * ({@code asTenant(...)}), because the handler's tenant guard answers {@code 500} to a request that
 * carries none. Such an entity also gets the foreign-tenant cases: a write the
 * repository refuses as naming a foreign tenant (or, on a UNIVERSE entity, a foreign shared scope)
 * answers {@code 400}.
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
 * @since 0.7
 */
public final class KernelHandlerTestGenerator {

    /**
     * A fixed identifier for the path-parameter tests. Deliberately a literal: a
     * {@code UUID.randomUUID()} in the emitted source would still be deterministic <em>as text</em>,
     * but it would make the generated test's own failure output differ run to run for no benefit.
     */
    private static final String FIXED_ID = "00000000-0000-4000-8000-000000000001";

    /**
     * The tenant a tenant-partitioned entity's handler tests dispatch under — the same literal the
     * generated repository test binds, and deliberately not {@link #FIXED_ID}.
     */
    private static final String TENANT_KEY = "00000000-0000-4000-8000-000000000002";
    private static final String AS_TENANT = "asTenant";

    private static final ClassName TEST = ClassName.get("org.junit.jupiter.api", "Test");
    private static final ClassName ASSERTIONS = ClassName.get("org.assertj.core.api", "Assertions");
    private static final ClassName HTTP_STATUS = ClassName.get("eu.exeris.kernel.spi.http", "HttpStatus");
    private static final ClassName UUID = ClassName.get("java.util", "UUID");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName OPTIONAL = ClassName.get("java.util", "Optional");
    private static final ClassName SCOPED_VALUE = ClassName.get("java.lang", "ScopedValue");
    private static final ClassName HTTP_KERNEL_PROVIDERS =
            ClassName.get("eu.exeris.kernel.spi.http", "HttpKernelProviders");
    private static final ClassName KERNEL_PROVIDERS =
            ClassName.get("eu.exeris.kernel.spi.context", "KernelProviders");
    private static final ClassName BIG_DECIMAL = ClassName.get("java.math", "BigDecimal");
    private static final ClassName REQUEST_BODY_DECODE_EXCEPTION =
            ClassName.get("eu.exeris.kernel.spi.exceptions.http", "RequestBodyDecodeException");
    private static final ClassName EXERIS_KERNEL_EXCEPTION =
            ClassName.get("eu.exeris.kernel.spi.exceptions", "ExerisKernelException");

    /**
     * The longest string literal a length case will emit. A {@code maxLength} in the thousands is a
     * database-column bound, not a guard worth driving with a literal that would dwarf the test —
     * those rules go uncovered rather than unreadable.
     */
    private static final int MAX_EMITTED_STRING = 512;

    /**
     * Creates the generator. It keeps no per-domain state, so one instance serves every domain
     * in a build.
     */
    public KernelHandlerTestGenerator() {
        // no state to initialise
    }

    /**
     * Emits the JUnit 5 test for the entity's generated HTTP handler.
     *
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
                .addJavadoc("<p>Covers the status each route owes the router: the bodyless CRUD\n")
                .addJavadoc("routes, the guard paths of {@code handleCreate} /\n")
                .addJavadoc("{@code handleUpdate} that reject before the body is read, the status\n")
                .addJavadoc("each kind of decode failure owes, and the {@code @Validation} guards\n")
                .addJavadoc("past a successful decode.\n")
                .addJavadoc("<p>Requires JUnit 5 and AssertJ on the test classpath, and nothing else.\n")
                .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain models.\n");

        ClassName bodyType = ClassName.get(
                KernelTestSupportGenerator.supportPackage(basePackage),
                KernelTestSupportGenerator.RECORDING_REQUEST_BODY);

        // The tenant guard covers every route of a tenant-partitioned entity: it wants a bound
        // StorageContext and answers 500 without one, so these tests dispatch the way a request
        // arrives, with a tenant bound. Without it every case here would fail on the guard, for a
        // reason none of them is about.
        boolean tenantScoped = DataScopeSupport.isTenantPartitioned(metadata);
        if (tenantScoped) {
            type.addField(FieldSpec.builder(ClassName.get("eu.exeris.kernel.spi.security", "StorageContext"),
                            "TENANT_SCOPE", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer("$T.shared($S)",
                            ClassName.get("eu.exeris.kernel.spi.security", "ImmutableStorageContext"),
                            TENANT_KEY)
                    .build());
        }

        addListTests(type, metadata, entityType, handlerType, exchangeType, stubType, basePath,
                tenantScoped);
        type.addMethod(getByIdFoundTest(entityType, handlerType, exchangeType, stubType, basePath,
                tenantScoped));
        type.addMethod(getByIdAbsentTest(handlerType, exchangeType, stubType, basePath,
                tenantScoped));
        type.addMethod(getByIdMalformedTest(handlerType, exchangeType, stubType, basePath,
                tenantScoped));
        type.addMethod(deleteTest(handlerType, exchangeType, stubType, basePath,
                tenantScoped));
        type.addMethod(deleteAbsentTest(handlerType, exchangeType, stubType, basePath,
                tenantScoped));
        type.addMethod(createMissingBodyTest(handlerType, exchangeType, stubType, basePath,
                tenantScoped));
        type.addMethod(updateMalformedIdTest(handlerType, exchangeType, stubType, basePath,
                tenantScoped));
        type.addMethod(updateMissingBodyTest(handlerType, exchangeType, stubType, basePath,
                tenantScoped));
        addDecodeFaultTests(type, entityType, handlerType, exchangeType, bodyType, stubType,
                basePath, tenantScoped);
        addValidationTests(type, metadata, entityType, handlerType, exchangeType, bodyType,
                stubType, basePath, tenantScoped);
        addCallerFaultTests(type, metadata, entityType, handlerType, exchangeType, bodyType,
                stubType, basePath);
        if (tenantScoped) {
            type.addMethod(MethodSpec.methodBuilder(AS_TENANT)
                    .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                    .addParameter(Runnable.class, "dispatch")
                    .addJavadoc("Runs {@code dispatch} with a tenant bound, the way the kernel's\n")
                    .addJavadoc("SecurityInterceptor binds {@code STORAGE_CONTEXT} around a request to a\n")
                    .addJavadoc("route that demands identity. The handler's tenant guard answers 500\n")
                    .addJavadoc("without one.\n")
                    .addStatement("$T.where($T.STORAGE_CONTEXT, TENANT_SCOPE).run(dispatch)",
                            SCOPED_VALUE, KERNEL_PROVIDERS)
                    .build());
        }
        type.addMethod(newHandlerFactory(metadata, handlerType, stubType, basePackage));
        // The bodyless routes never reach parseBody, so the allocator they hold is never
        // touched — but the handler still requires one, so they get a fresh double rather
        // than every call site naming it.
        type.addMethod(MethodSpec.methodBuilder("newHandler")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(handlerType)
                .addParameter(stubType, "service")
                .addJavadoc("The handler under test over a throwaway allocator, for the routes that\n")
                .addJavadoc("never decode a body.\n")
                .addStatement("return newHandler(service, new $T())", bodyType)
                .build());
        type.addType(stubService(entityType, serviceType, repositoryType, stubType,
                metadata));

        return new GeneratedFile(packageName, className,
                KernelScaffold.render(packageName, type.build()), ArtifactType.TEST);
    }

    /**
     * T48: the one place the handler is constructed, so the publisher argument is added in one
     * place rather than at every test site.
     *
     * <p>The publisher is real — a generated class over a {@code RecordingEventEngine} — rather
     * than a stub of the publisher itself: its constructor registers every {@code EventTypeSpec}
     * into the engine's registry, so a double that skipped that would test a publisher the
     * application never builds. ADR-058 fixes the emitted-test classpath at JUnit 5 + AssertJ, so
     * this is a double, not a mock.
     */
    private MethodSpec newHandlerFactory(DomainMetadata metadata, ClassName handlerType,
                                         ClassName stubType, String basePackage) {
        ClassName bodyType = ClassName.get(
                KernelTestSupportGenerator.supportPackage(basePackage),
                KernelTestSupportGenerator.RECORDING_REQUEST_BODY);

        MethodSpec.Builder factory = MethodSpec.methodBuilder("newHandler")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(handlerType)
                .addParameter(stubType, "service")
                .addParameter(bodyType, "allocator")
                .addJavadoc("The handler under test, over the supplied service double.\n")
                .addJavadoc("<p>T43-follow-up: the handler takes its {@code MemoryAllocator} at\n")
                .addJavadoc("construction rather than reading the {@code ScopedValue} per request, so\n")
                .addJavadoc("it is supplied here. {@link $T} already implements the interface — the\n", bodyType)
                .addJavadoc("decoding context requires an allocator to exist, never to allocate, so its\n")
                .addJavadoc("allocate methods throw and are never reached.\n");

        if (!KernelHandlerGenerator.publishesFromHandler(metadata)) {
            return factory.addStatement("return new $T(service, allocator)", handlerType).build();
        }

        ClassName publisherType = ClassName.get(
                metadata.packageName().replace(".domain", ".event"),
                metadata.entityName() + "EventPublisher");
        ClassName engineType = ClassName.get(
                KernelTestSupportGenerator.supportPackage(basePackage),
                KernelTestSupportGenerator.RECORDING_EVENT_ENGINE);
        return factory
                .addJavadoc("<p>The publisher is built over a fresh {@link $T}, so a\n", engineType)
                .addJavadoc("test that wants to assert on published events can construct one\n")
                .addJavadoc("itself and keep the reference.\n")
                .addStatement("return new $T(service, allocator, new $T(new $T()))",
                        handlerType, publisherType, engineType)
                .build();
    }

    /**
     * The list route: what reaches the service for a request, and what is refused before it does.
     *
     * <p>The service double records the {@code <Entity>ListQuery} it was handed, so each case asserts
     * the parse rather than a status alone: a handler that answered {@code 200} while dropping the
     * page or the filter on the floor would pass a status check. The refusals assert the service was
     * never reached. A sort and a filter case are emitted only for an entity that has a sortable
     * property, and a filter whose value a literal can be written for.
     */
    private void addListTests(TypeSpec.Builder type, DomainMetadata metadata, ClassName entityType,
                              ClassName handlerType, ClassName exchangeType, ClassName stubType,
                              String basePath, boolean tenantScoped) {
        ClassName queryType = KernelListQueryGenerator.listQueryType(metadata);
        ClassName pageType = KernelListQueryGenerator.pageType(metadata);

        type.addMethod(test("handleGetAllRespondsOkWithThePageTheServiceRead")
                .addJavadoc("No query string: the first page, at the default size, unsorted and unfiltered.\n")
                .addStatement("$T service = new $T()", stubType, stubType)
                .addStatement("service.page = $T.of($T.of(new $T()), 1L, 0, $T.DEFAULT_SIZE)",
                        pageType, LIST, entityType, queryType)
                .addStatement("$T handler = newHandler(service)", handlerType)
                .addStatement("$T exchange = $T.get($S)", exchangeType, exchangeType, basePath)
                .addStatement(invoke("handleGetAll", tenantScoped))
                .addStatement("$T.assertThat(exchange.status()).isEqualTo($T.OK)", ASSERTIONS, HTTP_STATUS)
                .addStatement("$T.assertThat(exchange.body()).isSameAs(service.page)", ASSERTIONS)
                .addStatement("$T.assertThat(service.listQuery).isEqualTo($T.of(0, $T.DEFAULT_SIZE))",
                        ASSERTIONS, queryType, queryType)
                .build());

        type.addMethod(test("handleGetAllPassesTheRequestedPageAndSize")
                .addStatement("$T service = new $T()", stubType, stubType)
                .addStatement("$T handler = newHandler(service)", handlerType)
                .addStatement("$T exchange = $T.get($S)", exchangeType, exchangeType,
                        basePath + "?page=2&size=5")
                .addStatement(invoke("handleGetAll", tenantScoped))
                .addStatement("$T.assertThat(exchange.status()).isEqualTo($T.OK)", ASSERTIONS, HTTP_STATUS)
                .addStatement("$T.assertThat(service.listQuery).isEqualTo($T.of(2, 5))", ASSERTIONS, queryType)
                .build());

        List<ListQuerySupport.Property> sortable = ListQuerySupport.sortable(metadata);
        if (!sortable.isEmpty()) {
            String property = sortable.get(0).name();
            type.addMethod(test("handleGetAllPassesTheRequestedSort")
                    .addStatement("$T service = new $T()", stubType, stubType)
                    .addStatement("$T handler = newHandler(service)", handlerType)
                    .addStatement("$T exchange = $T.get($S)", exchangeType, exchangeType,
                            basePath + "?sort=" + property + ",desc")
                    .addStatement(invoke("handleGetAll", tenantScoped))
                    .addStatement("$T.assertThat(exchange.status()).isEqualTo($T.OK)", ASSERTIONS, HTTP_STATUS)
                    .addStatement("$T.assertThat(service.listQuery.sort()).isEqualTo($S)", ASSERTIONS, property)
                    .addStatement("$T.assertThat(service.listQuery.descending()).isTrue()", ASSERTIONS)
                    .build());
        }

        ListQuerySupport.filters(metadata).stream()
                .filter(filter -> rawFilterValue(filter) != null)
                .findFirst()
                .ifPresent(filter -> type.addMethod(test("handleGetAllPassesTheRequestedFilter")
                    .addJavadoc("The first filter a literal can be written for: {@code $L}.\n", filter.name())
                    .addStatement("$T service = new $T()", stubType, stubType)
                    .addStatement("$T handler = newHandler(service)", handlerType)
                    .addStatement("$T exchange = $T.get($S)", exchangeType, exchangeType,
                            basePath + "?" + filter.name() + "=" + rawFilterValue(filter))
                    .addStatement(invoke("handleGetAll", tenantScoped))
                    .addStatement("$T.assertThat(exchange.status()).isEqualTo($T.OK)", ASSERTIONS, HTTP_STATUS)
                    .addStatement("$T.assertThat(service.listQuery.filter().$L()).isEqualTo($L)",
                            ASSERTIONS, filter.name(), filterSample(filter))
                    .build()));

        refusal(type, "handleGetAllRespondsBadRequestOnAnUnknownParameter",
                "A name the route does not read is refused, not ignored: a mistyped filter would\n"
                        + "otherwise answer every row as if it had matched.\n",
                basePath + "?no-such-parameter=1", handlerType, exchangeType, stubType, tenantScoped);
        refusal(type, "handleGetAllRespondsBadRequestOnAPropertyThatCannotBeSorted",
                null, basePath + "?sort=no-such-property,asc", handlerType, exchangeType, stubType,
                tenantScoped);
        refusal(type, "handleGetAllRespondsBadRequestOnANegativePage",
                null, basePath + "?page=-1", handlerType, exchangeType, stubType, tenantScoped);
        refusal(type, "handleGetAllRespondsBadRequestOnASizeAboveTheMaximum",
                null, basePath + "?size=" + (ListQuerySupport.MAX_SIZE + 1), handlerType, exchangeType,
                stubType, tenantScoped);
    }

    private void refusal(TypeSpec.Builder type, String name, String javadoc, String target,
                         ClassName handlerType, ClassName exchangeType, ClassName stubType,
                         boolean tenantScoped) {
        MethodSpec.Builder test = test(name);
        if (javadoc != null) {
            test.addJavadoc(javadoc);
        }
        type.addMethod(test
                .addStatement("$T service = new $T()", stubType, stubType)
                .addStatement("$T handler = newHandler(service)", handlerType)
                .addStatement("$T exchange = $T.get($S)", exchangeType, exchangeType, target)
                .addStatement(invoke("handleGetAll", tenantScoped))
                .addStatement("$T.assertThat(exchange.status()).isEqualTo($T.BAD_REQUEST)",
                        ASSERTIONS, HTTP_STATUS)
                .addStatement("$T.assertThat(service.listQuery).isNull()", ASSERTIONS)
                .build());
    }

    /**
     * A query-string value for a filter of this kind, matching {@link #filterSample}; {@code null}
     * for a kind with no literal this generator can write — an enum, whose constants it does not know.
     */
    private static String rawFilterValue(ListQuerySupport.Property filter) {
        return switch (filter.kind()) {
            case UUID -> FIXED_ID;
            case STRING -> "sample";
            case BOOL -> "true";
            case INT, LONG -> String.valueOf(KernelTestSamples.SAMPLE_NUMBER);
            default -> null;
        };
    }

    /** The Java value {@link #rawFilterValue} parses to, boxed as the filter component holds it. */
    private static CodeBlock filterSample(ListQuerySupport.Property filter) {
        return switch (filter.kind()) {
            case UUID -> CodeBlock.of("$T.fromString($S)", UUID, FIXED_ID);
            case STRING -> CodeBlock.of("$S", "sample");
            case BOOL -> CodeBlock.of("$T.TRUE", Boolean.class);
            case INT -> CodeBlock.of("$T.valueOf($L)", Integer.class, KernelTestSamples.SAMPLE_NUMBER);
            case LONG -> CodeBlock.of("$T.valueOf($LL)", Long.class, KernelTestSamples.SAMPLE_NUMBER);
            default -> throw new IllegalArgumentException("no sample for " + filter.kind());
        };
    }

    private MethodSpec getByIdFoundTest(ClassName entityType, ClassName handlerType,
                                        ClassName exchangeType, ClassName stubType, String basePath,
                                  boolean tenantScoped) {
        return test("handleGetByIdRespondsOkWhenTheEntityExists")
                .addStatement("$T found = new $T()", entityType, entityType)
                .addStatement("$T service = new $T()", stubType, stubType)
                .addStatement("service.byId = $T.of(found)", OPTIONAL)
                .addStatement("$T handler = newHandler(service)", handlerType)
                .addStatement("$T exchange = $T.get($S).withPathParam($S, $S)",
                        exchangeType, exchangeType, basePath + "/" + FIXED_ID, "id", FIXED_ID)
                .addStatement(invoke("handleGetById", tenantScoped))
                .addStatement("$T.assertThat(exchange.status()).isEqualTo($T.OK)", ASSERTIONS, HTTP_STATUS)
                .addStatement("$T.assertThat(exchange.body()).isSameAs(found)", ASSERTIONS)
                .build();
    }

    private MethodSpec getByIdAbsentTest(ClassName handlerType, ClassName exchangeType,
                                         ClassName stubType, String basePath,
                                  boolean tenantScoped) {
        return test("handleGetByIdRespondsNotFoundWhenTheEntityIsAbsent")
                .addStatement("$T service = new $T()", stubType, stubType)
                .addStatement("service.byId = $T.empty()", OPTIONAL)
                .addStatement("$T handler = newHandler(service)", handlerType)
                .addStatement("$T exchange = $T.get($S).withPathParam($S, $S)",
                        exchangeType, exchangeType, basePath + "/" + FIXED_ID, "id", FIXED_ID)
                .addStatement(invoke("handleGetById", tenantScoped))
                .addStatement("$T.assertThat(exchange.status()).isEqualTo($T.NOT_FOUND)",
                        ASSERTIONS, HTTP_STATUS)
                .build();
    }

    private MethodSpec getByIdMalformedTest(ClassName handlerType, ClassName exchangeType,
                                            ClassName stubType, String basePath,
                                  boolean tenantScoped) {
        return test("handleGetByIdRespondsBadRequestOnAMalformedId")
                .addJavadoc("The id guard runs before the service is consulted, so a malformed path\n")
                .addJavadoc("parameter must never reach it.\n")
                .addStatement("$T service = new $T()", stubType, stubType)
                .addStatement("$T handler = newHandler(service)", handlerType)
                .addStatement("$T exchange = $T.get($S).withPathParam($S, $S)",
                        exchangeType, exchangeType, basePath + "/not-a-uuid", "id", "not-a-uuid")
                .addStatement(invoke("handleGetById", tenantScoped))
                .addStatement("$T.assertThat(exchange.status()).isEqualTo($T.BAD_REQUEST)",
                        ASSERTIONS, HTTP_STATUS)
                .addStatement("$T.assertThat(service.lookedUp).isNull()", ASSERTIONS)
                .build();
    }

    private MethodSpec deleteTest(ClassName handlerType, ClassName exchangeType,
                                  ClassName stubType, String basePath,
                                  boolean tenantScoped) {
        return test("handleDeleteRespondsNoContentAndDelegatesTheId")
                .addJavadoc("The row is there — {@code rowExists} defaults true — so this is the\n")
                .addJavadoc("delete that actually removes something. See the sibling test for the\n")
                .addJavadoc("other side of that flag.\n")
                .addStatement("$T service = new $T()", stubType, stubType)
                .addStatement("$T handler = newHandler(service)", handlerType)
                .addStatement("$T exchange = $T.delete($S).withPathParam($S, $S)",
                        exchangeType, exchangeType, basePath + "/" + FIXED_ID, "id", FIXED_ID)
                .addStatement(invoke("handleDelete", tenantScoped))
                .addStatement("$T.assertThat(exchange.status()).isEqualTo($T.NO_CONTENT)",
                        ASSERTIONS, HTTP_STATUS)
                .addStatement("$T.assertThat(service.deleted).isEqualTo($T.fromString($S))",
                        ASSERTIONS, UUID, FIXED_ID)
                .build();
    }

    /**
     * D7 / ADR-076. The status this route gives when no row matched, which until 0.8.0 was 500 —
     * and which this test could not have caught, because the double returned quietly where the
     * real service propagates the repository's rejection.
     */
    private MethodSpec deleteAbsentTest(ClassName handlerType, ClassName exchangeType,
                                        ClassName stubType, String basePath,
                                  boolean tenantScoped) {
        return test("handleDeleteRespondsNotFoundWhenNoRowMatched")
                .addJavadoc("A {@code DELETE} of an id no row carries — including the second\n")
                .addJavadoc("attempt of a retried delete, which matches nothing either.\n")
                .addStatement("$T service = new $T()", stubType, stubType)
                .addStatement("service.rowExists = false")
                .addStatement("$T handler = newHandler(service)", handlerType)
                .addStatement("$T exchange = $T.delete($S).withPathParam($S, $S)",
                        exchangeType, exchangeType, basePath + "/" + FIXED_ID, "id", FIXED_ID)
                .addStatement(invoke("handleDelete", tenantScoped))
                .addStatement("$T.assertThat(exchange.status()).isEqualTo($T.NOT_FOUND)",
                        ASSERTIONS, HTTP_STATUS)
                .addStatement("$T.assertThat(service.deleted).isNull()", ASSERTIONS)
                .build();
    }

    private MethodSpec createMissingBodyTest(ClassName handlerType, ClassName exchangeType,
                                             ClassName stubType, String basePath,
                                  boolean tenantScoped) {
        return test("handleCreateRespondsBadRequestWhenTheBodyIsMissing")
                .addJavadoc("A bodyless {@code POST} is rejected by the body guard, before the\n")
                .addJavadoc("service is consulted — so nothing is persisted on a malformed request.\n")
                .addStatement("$T service = new $T()", stubType, stubType)
                .addStatement("$T handler = newHandler(service)", handlerType)
                .addStatement("$T exchange = $T.post($S)", exchangeType, exchangeType, basePath)
                .addStatement(invoke("handleCreate", tenantScoped))
                .addStatement("$T.assertThat(exchange.status()).isEqualTo($T.BAD_REQUEST)",
                        ASSERTIONS, HTTP_STATUS)
                .addStatement("$T.assertThat(service.saved).isNull()", ASSERTIONS)
                .build();
    }

    private MethodSpec updateMalformedIdTest(ClassName handlerType, ClassName exchangeType,
                                             ClassName stubType, String basePath,
                                  boolean tenantScoped) {
        return test("handleUpdateRespondsBadRequestOnAMalformedId")
                .addJavadoc("The path-id guard runs before the body guard, so a malformed id is\n")
                .addJavadoc("rejected without the body ever being read.\n")
                .addStatement("$T service = new $T()", stubType, stubType)
                .addStatement("$T handler = newHandler(service)", handlerType)
                .addStatement("$T exchange = $T.put($S).withPathParam($S, $S)",
                        exchangeType, exchangeType, basePath + "/not-a-uuid", "id", "not-a-uuid")
                .addStatement(invoke("handleUpdate", tenantScoped))
                .addStatement("$T.assertThat(exchange.status()).isEqualTo($T.BAD_REQUEST)",
                        ASSERTIONS, HTTP_STATUS)
                .addStatement("$T.assertThat(service.updatedId).isNull()", ASSERTIONS)
                .build();
    }

    private MethodSpec updateMissingBodyTest(ClassName handlerType, ClassName exchangeType,
                                             ClassName stubType, String basePath,
                                  boolean tenantScoped) {
        return test("handleUpdateRespondsBadRequestWhenTheBodyIsMissing")
                .addJavadoc("A well-formed id is not enough: the body guard still rejects, and the\n")
                .addJavadoc("service is never reached.\n")
                .addStatement("$T service = new $T()", stubType, stubType)
                .addStatement("$T handler = newHandler(service)", handlerType)
                .addStatement("$T exchange = $T.put($S).withPathParam($S, $S)",
                        exchangeType, exchangeType, basePath + "/" + FIXED_ID, "id", FIXED_ID)
                .addStatement(invoke("handleUpdate", tenantScoped))
                .addStatement("$T.assertThat(exchange.status()).isEqualTo($T.BAD_REQUEST)",
                        ASSERTIONS, HTTP_STATUS)
                .addStatement("$T.assertThat(service.updatedId).isNull()", ASSERTIONS)
                .build();
    }

    // ---------------------------------------------------------------------------------------
    // Decode failures — whose fault a body that did not decode is.
    // ---------------------------------------------------------------------------------------

    /**
     * Emits the four decode-failure cases on {@code handleCreate}: a CALLER refusal answering 400,
     * and a SYSTEM kernel exception, a JDK exception and an unbound decoder registry, each
     * answering 500.
     *
     * <p>The caller-fault case asserts {@code decodedType} as well as the status. A bodyless
     * request also answers 400, before any decoder runs, so the status alone would pass on a
     * handler that never reached the decoder; {@code decodedType} is set only by a decode that ran.
     *
     * <p>The two server-fault probes are the ones a type-based mapping gets wrong: a kernel
     * exception the kernel leaves at {@code FaultOrigin.SYSTEM}, and a JDK
     * {@link IllegalArgumentException} — the type the handler's own 400 catch names, so a decoder
     * failure re-thrown unwrapped would be answered as the caller's. Neither depends on validation
     * rules, so every entity gets all four.
     */
    private void addDecodeFaultTests(TypeSpec.Builder type, ClassName entityType,
                                     ClassName handlerType, ClassName exchangeType,
                                     ClassName bodyType, ClassName stubType, String basePath,
                                     boolean tenantScoped) {
        type.addMethod(decodeFaultTest("handleCreateRespondsBadRequestWhenTheDecoderRejectsTheBody",
                        handlerType, exchangeType, bodyType, stubType, basePath, tenantScoped,
                        CodeBlock.of("$T.malformedBody($T.class.getName(), 0L, null)",
                                REQUEST_BODY_DECODE_EXCEPTION, entityType))
                .addJavadoc("A body the decoder cannot bind is the caller's fault\n")
                .addJavadoc("({@code FaultOrigin.CALLER}), so it answers 400 and nothing is saved.\n")
                .addStatement("$T.assertThat(exchange.status()).isEqualTo($T.BAD_REQUEST)",
                        ASSERTIONS, HTTP_STATUS)
                .addStatement("$T.assertThat(body.decodedType).isEqualTo($T.class)",
                        ASSERTIONS, entityType)
                .addStatement("$T.assertThat(service.saved).isNull()", ASSERTIONS)
                .build());
        type.addMethod(decodeFaultTest(
                        "handleCreateRespondsServerErrorWhenTheDecoderFailsWithASystemFault",
                        handlerType, exchangeType, bodyType, stubType, basePath, tenantScoped,
                        CodeBlock.of("new $T($S, $S) { }", EXERIS_KERNEL_EXCEPTION,
                                "EX-TEST-0001", "decoder failed server-side"))
                .addJavadoc("A kernel exception left at {@code FaultOrigin.SYSTEM} — an allocation\n")
                .addJavadoc("failure inside the decoder, say — is the deployment's fault: 500, never\n")
                .addJavadoc("downgraded to 400.\n")
                .addStatement("$T.assertThat(exchange.status()).isEqualTo($T.INTERNAL_SERVER_ERROR)",
                        ASSERTIONS, HTTP_STATUS)
                .addStatement("$T.assertThat(service.saved).isNull()", ASSERTIONS)
                .build());
        type.addMethod(decodeFaultTest(
                        "handleCreateRespondsServerErrorWhenTheDecoderFailsWithAJdkException",
                        handlerType, exchangeType, bodyType, stubType, basePath, tenantScoped,
                        CodeBlock.of("new $T($S)", IllegalArgumentException.class, "decoder defect"))
                .addJavadoc("A JDK exception carries no fault origin, so it is the deployment's\n")
                .addJavadoc("(kernel ADR-083) — even an {@code IllegalArgumentException}, the type\n")
                .addJavadoc("the handler's own 400 answers.\n")
                .addStatement("$T.assertThat(exchange.status()).isEqualTo($T.INTERNAL_SERVER_ERROR)",
                        ASSERTIONS, HTTP_STATUS)
                .addStatement("$T.assertThat(service.saved).isNull()", ASSERTIONS)
                .build());
        type.addMethod(test("handleCreateRespondsServerErrorWhenNoDecoderRegistryIsBound")
                .addJavadoc("A body arrives but no decoder registry is bound around the dispatch: a\n")
                .addJavadoc("deployment fault, answered 500 rather than blamed on the body.\n")
                .addStatement("$T service = new $T()", stubType, stubType)
                .addStatement("$T body = new $T()", bodyType, bodyType)
                .addStatement("$T handler = newHandler(service, body)", handlerType)
                .addStatement("$T exchange = $T.post($S, body)", exchangeType, exchangeType, basePath)
                .addStatement(invoke("handleCreate", tenantScoped))
                .addStatement("$T.assertThat(exchange.status()).isEqualTo($T.INTERNAL_SERVER_ERROR)",
                        ASSERTIONS, HTTP_STATUS)
                .addStatement("$T.assertThat(body.decodedType).isNull()", ASSERTIONS)
                .addStatement("$T.assertThat(service.saved).isNull()", ASSERTIONS)
                .build());
    }

    /** A {@code handleCreate} dispatch whose decoder throws {@code failure}; assertions are the caller's. */
    private static MethodSpec.Builder decodeFaultTest(String name, ClassName handlerType,
                                                      ClassName exchangeType, ClassName bodyType,
                                                      ClassName stubType, String basePath,
                                                      boolean tenantScoped, CodeBlock failure) {
        MethodSpec.Builder m = test(name)
                .addStatement("$T service = new $T()", stubType, stubType)
                .addStatement("$T body = new $T()", bodyType, bodyType)
                .addStatement("body.failure = $L", failure)
                .addStatement("$T handler = newHandler(service, body)", handlerType)
                .addStatement("$T exchange = $T.post($S, body)", exchangeType, exchangeType, basePath);
        if (tenantScoped) {
            return m.addStatement("$T.where($T.HTTP_REQUEST_BODY_DECODER_REGISTRY, body)\n"
                            + ".where($T.STORAGE_CONTEXT, TENANT_SCOPE)\n"
                            + ".run(() -> handler.handleCreate(exchange))",
                    SCOPED_VALUE, HTTP_KERNEL_PROVIDERS, KERNEL_PROVIDERS);
        }
        return m.addStatement("$T.where($T.HTTP_REQUEST_BODY_DECODER_REGISTRY, body)\n"
                        + ".run(() -> handler.handleCreate(exchange))",
                SCOPED_VALUE, HTTP_KERNEL_PROVIDERS);
    }

    // ---------------------------------------------------------------------------------------
    // @Validation (T10) — the paths past a successful decode.
    // ---------------------------------------------------------------------------------------

    /**
     * Emits the {@code @Validation} cases: one accept at the baseline, one reject per rule, and —
     * where the rule has a boundary — one accept sitting exactly on it.
     *
     * <p><b>The boundary is the point.</b> A reject case on its own is nearly circular: the rule
     * and the value both come from the same {@code minLength = 3}, so an emitter that wrote
     * {@code <=} instead of {@code <} would still reject a 2-character string and the test would
     * still be green. Driving length 3 through and expecting {@code 201} is what pins inclusiveness,
     * because that case fails the moment the operator slips. The pair is the test; neither half
     * carries it alone.
     *
     * <p><b>And the accept case is load-bearing for a second reason.</b> A decode the decoder
     * refuses as the caller's fault also lands on {@code 400 BAD_REQUEST}, so a suite of reject-only
     * cases could go green without once reaching a validation guard. {@code 201 CREATED} can only
     * come out the far end of a decode that worked, so it is what makes the rejects mean what they
     * say.
     *
     * <p>Nothing is emitted at all unless every rule-carrying field has a synthesizable valid value:
     * a rejection case that sets one field to a bad value and leaves another invalid would be
     * rejected for the wrong field and pass anyway. A required field constrained by a
     * {@code pattern} is the case that trips this — a regex has no synthesizable member, so that
     * entity gets no validation cases rather than misleading ones.
     */
    private void addValidationTests(TypeSpec.Builder type, DomainMetadata metadata,
                                    ClassName entityType, ClassName handlerType,
                                    ClassName exchangeType, ClassName bodyType, ClassName stubType,
                                    String basePath, boolean tenantScoped) {
        List<KernelValidationRules.FieldRules> rules =
                KernelValidationRules.of(metadata.fields());
        if (rules.isEmpty()) {
            return;
        }

        Optional<Map<String, CodeBlock>> baselineOrEmpty = baselineFor(rules);
        if (baselineOrEmpty.isEmpty()) {
            return;
        }
        Map<String, CodeBlock> baseline = baselineOrEmpty.get();

        Scaffold scaffold = new Scaffold(entityType, handlerType, exchangeType, bodyType, stubType,
                basePath, rules, baseline, tenantScoped);

        type.addMethod(scaffold.create("handleCreateRespondsCreatedWhenEveryRuleIsSatisfied",
                        null, null)
                .addJavadoc("Every rule satisfied, each bounded field sitting exactly on its\n")
                .addJavadoc("boundary: the rules are inclusive, so this must pass <em>through</em>.\n")
                .addJavadoc("<p>It is also the only case here that proves the decode path ran at\n")
                .addJavadoc("all — a body the decoder refuses answers 400, the same status a\n")
                .addJavadoc("rejection does.\n")
                .addStatement("$T.assertThat(exchange.status()).isEqualTo($T.CREATED)",
                        ASSERTIONS, HTTP_STATUS)
                .addStatement("$T.assertThat(service.saved).isSameAs(decoded)", ASSERTIONS)
                .addStatement("$T.assertThat(body.decodedType).isEqualTo($T.class)",
                        ASSERTIONS, entityType)
                .build());

        for (KernelValidationRules.FieldRules fr : rules) {
            for (KernelValidationRules.Rule rule : fr.rules()) {
                for (Probe probe : probesFor(fr, rule)) {
                    type.addMethod(probe.accept()
                            ? scaffold.accept(fr, probe)
                            : scaffold.reject(fr, probe));
                }
            }
        }

        // One case proving handleUpdate runs the same guard. The per-rule sharpness lives on
        // handleCreate; what this adds is that the guard is wired into the second route too —
        // which is a separate emitter call, and so a separate thing to get wrong.
        //
        // It scans every rule for the first one that yields a reject, rather than reading the
        // first field's first rule: several kinds yield no probe at all (a pattern always, a
        // zero minLength, a bound that does not fit its field's type), so anchoring on position
        // would make this case's existence depend on field-declaration order.
        for (KernelValidationRules.FieldRules fr : rules) {
            Probe reject = fr.rules().stream()
                    .flatMap(rule -> probesFor(fr, rule).stream())
                    .filter(p -> !p.accept())
                    .findFirst().orElse(null);
            if (reject != null) {
                type.addMethod(scaffold.update(fr, reject));
                break;
            }
        }
    }

    /** One staged value for one field, and what the handler owes in response. */
    private record Probe(String nameSuffix, CodeBlock value, boolean accept, String why) {
    }

    /**
     * The cases a single rule earns. A rule with no boundary (not-null) earns one reject; a bounded
     * rule earns a reject just outside and an accept exactly on it. A rule contributes nothing when
     * its values cannot be synthesized without ambiguity — see the guards below, each of which is a
     * case that would otherwise pass for a reason other than the rule under test.
     */
    private static List<Probe> probesFor(KernelValidationRules.FieldRules fr,
                                         KernelValidationRules.Rule rule) {
        // A pattern has no synthesizable member and no synthesizable near-miss, so neither the
        // rule itself nor its field's other rules can be driven: a too-short string almost
        // certainly fails the pattern too, and then the reject proves nothing about length.
        if (fr.has(KernelValidationRules.Kind.PATTERN)
                && rule.kind() != KernelValidationRules.Kind.NOT_NULL) {
            return List.of();
        }
        String type = fr.field().type();

        return switch (rule.kind()) {
            case NOT_NULL -> List.of(new Probe("WhenNull", CodeBlock.of("null"), false,
                    "a required field the body left out"));
            case PATTERN -> List.of();
            case MIN_LENGTH -> {
                int bound = rule.bound().intValue();
                Integer maxLength = fr.field().maxLength();
                if (bound < 1 || bound > MAX_EMITTED_STRING) {
                    // A minLength of 0 has no value below it — the emitted guard is unreachable.
                    yield List.of();
                }
                List<Probe> probes = new ArrayList<>();
                probes.add(new Probe("ShorterThanMinLength", string(bound - 1), false,
                        "one character short of minLength " + bound));
                if (maxLength == null || maxLength >= bound) {
                    probes.add(new Probe("AtMinLength", string(bound), true,
                            "exactly minLength " + bound + ", which is inclusive"));
                }
                yield List.copyOf(probes);
            }
            case MAX_LENGTH -> {
                int bound = rule.bound().intValue();
                Integer minLength = fr.field().minLength();
                if (bound >= MAX_EMITTED_STRING) {
                    yield List.of();
                }
                List<Probe> probes = new ArrayList<>();
                probes.add(new Probe("LongerThanMaxLength", string(bound + 1), false,
                        "one character past maxLength " + bound));
                if (minLength == null || minLength <= bound) {
                    probes.add(new Probe("AtMaxLength", string(bound), true,
                            "exactly maxLength " + bound + ", which is inclusive"));
                }
                yield List.copyOf(probes);
            }
            case MIN -> {
                long bound = rule.bound();
                Long max = fr.field().max();
                List<Probe> probes = new ArrayList<>();
                if (bound != Long.MIN_VALUE && numeric(type, bound - 1) != null) {
                    probes.add(new Probe("BelowMin", numeric(type, bound - 1), false,
                            "one below min " + bound));
                }
                if ((max == null || bound <= max) && numeric(type, bound) != null) {
                    probes.add(new Probe("AtMin", numeric(type, bound), true,
                            "exactly min " + bound + ", which is inclusive"));
                }
                yield List.copyOf(probes);
            }
            case MAX -> {
                long bound = rule.bound();
                Long min = fr.field().min();
                List<Probe> probes = new ArrayList<>();
                if (bound != Long.MAX_VALUE && numeric(type, bound + 1) != null) {
                    probes.add(new Probe("AboveMax", numeric(type, bound + 1), false,
                            "one past max " + bound));
                }
                if ((min == null || bound >= min) && numeric(type, bound) != null) {
                    probes.add(new Probe("AtMax", numeric(type, bound), true,
                            "exactly max " + bound + ", which is inclusive"));
                }
                yield List.copyOf(probes);
            }
        };
    }

    /**
     * A value that satisfies every rule on this field, or {@code null} when none can be
     * synthesized — which makes the whole entity's validation cases unemittable, because the other
     * fields' rejections would be answered by this field instead.
     */
    private static CodeBlock baselineFor(KernelValidationRules.FieldRules fr) {
        String type = fr.field().type();
        // Every check but not-null is null-guarded, so null is a legal value for an optional
        // field whatever else it carries — including a pattern.
        if (!fr.has(KernelValidationRules.Kind.NOT_NULL)
                && !KernelValidationRules.isPrimitive(type)) {
            return CodeBlock.of("null");
        }
        if (fr.has(KernelValidationRules.Kind.PATTERN)) {
            return null;
        }
        if (KernelValidationRules.isStringType(type)) {
            Integer min = fr.field().minLength();
            Integer max = fr.field().maxLength();
            int length = min != null && min > 0 ? min : 1;
            if (max != null && max < length) {
                // minLength > maxLength: no string satisfies both.
                return null;
            }
            return length > MAX_EMITTED_STRING ? null : string(length);
        }
        if (KernelValidationRules.isNumeric(type)) {
            Long min = fr.field().min();
            Long max = fr.field().max();
            if (min != null && max != null && min > max) {
                return null;
            }
            long value = min != null ? min : (max != null ? max : KernelTestSamples.SAMPLE_NUMBER);
            return numeric(type, value);
        }
        CodeBlock sample = KernelTestSamples.of(type);
        return KernelTestSamples.isNull(sample) ? null : sample;
    }

    private static CodeBlock string(int length) {
        return CodeBlock.of("$S", "a".repeat(length));
    }

    /**
     * A literal of {@code type} holding {@code value} exactly, or {@code null} when it does not fit.
     *
     * <p>Floating-point fields always yield {@code null}: the bound is a {@code long} and the
     * comparison promotes, so a case sitting one unit off a large boundary is not reliably one unit
     * off after the conversion — and a boundary case that is only approximately on the boundary
     * tests nothing.
     */
    private static CodeBlock numeric(String type, long value) {
        String simple = KernelValidationRules.simpleTypeName(type);
        return switch (simple) {
            case "int", "Integer" -> value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE
                    ? CodeBlock.of("$L", value) : null;
            case "long", "Long" -> CodeBlock.of("$LL", value);
            case "short", "Short" -> value >= Short.MIN_VALUE && value <= Short.MAX_VALUE
                    ? CodeBlock.of("(short) $L", value) : null;
            case "byte", "Byte" -> value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE
                    ? CodeBlock.of("(byte) $L", value) : null;
            case "BigDecimal" -> CodeBlock.of("$T.valueOf($LL)", BIG_DECIMAL, value);
            default -> null;
        };
    }

    /**
     * A valid value for every rule-carrying field, or empty when one cannot be synthesized —
     * a case staged with an invalid field would be rejected for the wrong reason and pass anyway.
     */
    private Optional<Map<String, CodeBlock>> baselineFor(List<KernelValidationRules.FieldRules> rules) {
        Map<String, CodeBlock> baseline = new LinkedHashMap<>();
        for (KernelValidationRules.FieldRules fr : rules) {
            CodeBlock value = baselineFor(fr);
            if (value == null) {
                return Optional.empty();
            }
            baseline.put(fr.field().name(), value);
        }
        return Optional.of(baseline);
    }

    /**
     * The caller-fault refusals — a write naming a foreign tenant, or on a UNIVERSE entity
     * a foreign shared scope — answer {@code 400}, on both body-carrying routes.
     *
     * <p>The service double raises the same typed exception the repository does. What keeps the
     * 400 from being vacuous — every guard ahead of the write also answers 400 — is the second
     * assertion: {@code service.attempted} is the decoded entity, so the request passed the body
     * guard and every {@code @Validation} rule and reached the write before it was refused.
     * Skipped, like the validation cases, when a rule-carrying field has no synthesizable valid
     * value.
     */
    private void addCallerFaultTests(TypeSpec.Builder type, DomainMetadata metadata,
                                     ClassName entityType, ClassName handlerType,
                                     ClassName exchangeType, ClassName bodyType, ClassName stubType,
                                     String basePath) {
        ClassName tenantMismatch = KernelErrorGenerator.tenantMismatchType(metadata);
        if (tenantMismatch == null) {
            return;
        }
        List<KernelValidationRules.FieldRules> rules = KernelValidationRules.of(metadata.fields());
        Optional<Map<String, CodeBlock>> baselineOrEmpty = baselineFor(rules);
        if (baselineOrEmpty.isEmpty()) {
            return;
        }
        Map<String, CodeBlock> baseline = baselineOrEmpty.get();
        Scaffold scaffold = new Scaffold(entityType, handlerType, exchangeType, bodyType, stubType,
                basePath, rules, baseline, true);
        CodeBlock foreignTenant = CodeBlock.of("new $T($T.fromString($S))", tenantMismatch, UUID, FIXED_ID);
        type.addMethod(scaffold.refused("handleCreateRespondsBadRequestForAForeignTenant",
                "handleCreate", foreignTenant));
        type.addMethod(scaffold.refused("handleUpdateRespondsBadRequestForAForeignTenant",
                "handleUpdate", foreignTenant));
        ClassName sharedScopeMismatch = KernelErrorGenerator.sharedScopeMismatchType(metadata);
        if (sharedScopeMismatch != null) {
            type.addMethod(scaffold.refused("handleCreateRespondsBadRequestForAForeignSharedScope",
                    "handleCreate", CodeBlock.of("new $T($S)", sharedScopeMismatch, FIXED_ID)));
        }
    }

    /** Emits the shared body of a validation case; only the staged value and the assertions differ. */
    private final class Scaffold {

        private final ClassName entityType;
        private final ClassName handlerType;
        private final ClassName exchangeType;
        private final ClassName bodyType;
        private final ClassName stubType;
        private final String basePath;
        private final List<KernelValidationRules.FieldRules> rules;
        private final Map<String, CodeBlock> baseline;
        private final boolean tenantScoped;

        Scaffold(ClassName entityType, ClassName handlerType, ClassName exchangeType,
                 ClassName bodyType, ClassName stubType, String basePath,
                 List<KernelValidationRules.FieldRules> rules, Map<String, CodeBlock> baseline,
                 boolean tenantScoped) {
            this.entityType = entityType;
            this.handlerType = handlerType;
            this.exchangeType = exchangeType;
            this.bodyType = bodyType;
            this.stubType = stubType;
            this.basePath = basePath;
            this.rules = rules;
            this.baseline = baseline;
            this.tenantScoped = tenantScoped;
        }

        /** A valid body the write refuses with {@code refusal}; see {@link #addCallerFaultTests}. */
        MethodSpec refused(String name, String handlerMethod, CodeBlock refusal) {
            MethodSpec.Builder m = test(name)
                    .addJavadoc("A valid body naming an owner the caller does not act as is refused by\n")
                    .addJavadoc("the repository with a typed caller fault, answered 400 (kernel ADR-083) rather\n")
                    .addJavadoc("than the 500 a bare exception gets.\n");
            stage(m, null, null);
            m.addStatement("service.refusal = $L", refusal);
            if ("handleCreate".equals(handlerMethod)) {
                m.addStatement("$T exchange = $T.post($S, body)", exchangeType, exchangeType, basePath);
            } else {
                m.addStatement("$T exchange = $T.put($S, body).withPathParam($S, $S)",
                        exchangeType, exchangeType, basePath + "/" + FIXED_ID, "id", FIXED_ID);
            }
            run(m, handlerMethod);
            return m.addStatement("$T.assertThat(exchange.status()).isEqualTo($T.BAD_REQUEST)",
                            ASSERTIONS, HTTP_STATUS)
                    .addStatement("$T.assertThat(service.attempted).isSameAs(decoded)", ASSERTIONS)
                    .build();
        }

        MethodSpec.Builder create(String name, KernelValidationRules.FieldRules perturbed,
                                  CodeBlock value) {
            MethodSpec.Builder m = test(name);
            stage(m, perturbed, value);
            m.addStatement("$T exchange = $T.post($S, body)", exchangeType, exchangeType, basePath);
            run(m, "handleCreate");
            return m;
        }

        MethodSpec accept(KernelValidationRules.FieldRules fr, Probe probe) {
            return create(caseName("handleCreateAccepts", fr, probe), fr, probe.value())
                    .addJavadoc("$L — $L.\n", label(fr), probe.why())
                    .addStatement("$T.assertThat(exchange.status()).isEqualTo($T.CREATED)",
                            ASSERTIONS, HTTP_STATUS)
                    .addStatement("$T.assertThat(service.saved).isSameAs(decoded)", ASSERTIONS)
                    .build();
        }

        MethodSpec reject(KernelValidationRules.FieldRules fr, Probe probe) {
            return create(caseName("handleCreateRejects", fr, probe), fr, probe.value())
                    .addJavadoc("$L — $L. The guard runs before the service, so nothing is saved.\n",
                            label(fr), probe.why())
                    .addStatement("$T.assertThat(exchange.status()).isEqualTo($T.BAD_REQUEST)",
                            ASSERTIONS, HTTP_STATUS)
                    .addStatement("$T.assertThat(service.saved).isNull()", ASSERTIONS)
                    .build();
        }

        MethodSpec update(KernelValidationRules.FieldRules fr, Probe probe) {
            MethodSpec.Builder m = test("handleUpdateRunsTheSameValidationGuard");
            m.addJavadoc("The guard is emitted into both body-carrying routes, by two separate\n")
                    .addJavadoc("calls — so {@code handleUpdate} losing it is its own regression.\n");
            stage(m, fr, probe.value());
            m.addStatement("$T exchange = $T.put($S, body).withPathParam($S, $S)",
                    exchangeType, exchangeType, basePath + "/" + FIXED_ID, "id", FIXED_ID);
            run(m, "handleUpdate");
            return m.addStatement("$T.assertThat(exchange.status()).isEqualTo($T.BAD_REQUEST)",
                            ASSERTIONS, HTTP_STATUS)
                    .addStatement("$T.assertThat(service.updatedId).isNull()", ASSERTIONS)
                    .build();
        }

        /** Builds the decoded entity: every rule-carrying field valid, bar the one under test. */
        private void stage(MethodSpec.Builder m, KernelValidationRules.FieldRules perturbed,
                           CodeBlock value) {
            // `body` is declared before the handler: the handler takes the allocator at
            // construction, and it must be the same double this test stages.
            m.addStatement("$T service = new $T()", stubType, stubType)
                    .addStatement("$T body = new $T()", bodyType, bodyType)
                    .addStatement("$T handler = newHandler(service, body)", handlerType)
                    .addStatement("$T decoded = new $T()", entityType, entityType);
            for (KernelValidationRules.FieldRules fr : rules) {
                CodeBlock staged = perturbed != null && perturbed.field().name().equals(fr.field().name())
                        ? value
                        : baseline.get(fr.field().name());
                m.addStatement("decoded.$L($L)", fr.mutator(), staged);
            }
            m.addStatement("body.next = decoded");
        }

        /**
         * Runs the handler with the decoder-registry slot bound.
         *
         * <p>The {@code MEMORY_ALLOCATOR} slot is not bound here: the handler takes the allocator
         * as a constructor argument and does not read that {@code ScopedValue}. The same
         * {@code RecordingRequestBody} the registry returns is what {@code newHandler} was given,
         * so the decoding context still gets exactly the instance this test staged.
         */
        private void run(MethodSpec.Builder m, String handlerMethod) {
            if (tenantScoped) {
                m.addStatement("$T.where($T.HTTP_REQUEST_BODY_DECODER_REGISTRY, body)\n"
                                + ".where($T.STORAGE_CONTEXT, TENANT_SCOPE)\n"
                                + ".run(() -> handler.$L(exchange))",
                        SCOPED_VALUE, HTTP_KERNEL_PROVIDERS, KERNEL_PROVIDERS, handlerMethod);
                return;
            }
            m.addStatement("$T.where($T.HTTP_REQUEST_BODY_DECODER_REGISTRY, body)\n"
                            + ".run(() -> handler.$L(exchange))",
                    SCOPED_VALUE, HTTP_KERNEL_PROVIDERS, handlerMethod);
        }

        private String caseName(String prefix, KernelValidationRules.FieldRules fr, Probe probe) {
            return prefix + NameCasing.pascal(fr.field().name()) + probe.nameSuffix();
        }

        private String label(KernelValidationRules.FieldRules fr) {
            return "{@code " + fr.field().name() + "}";
        }
    }

    /**
     * The nested service double. Fields are package-private and set directly by each test — a
     * generated double has no callers to protect, and accessors would be noise.
     */
    private TypeSpec stubService(ClassName entityType, ClassName serviceType,
                                 ClassName repositoryType, ClassName stubType,
                                 DomainMetadata metadata) {
        TypeName optionalOfEntity = ParameterizedTypeName.get(OPTIONAL, entityType);

        // ADR-076: the rejection the real repository raises when a write matches no row, so the
        // double has the failure mode production has. delete matches on id alone and can only
        // report a missing row; a versioned update matched on id and version together and
        // reports the pair as a conflict.
        ClassName notFound = KernelErrorGenerator.notFoundType(metadata);
        ClassName conflict = KernelErrorGenerator.versionConflictType(metadata);
        // A tenant-partitioned repository also refuses a write naming a foreign tenant.
        boolean refuses = KernelErrorGenerator.tenantMismatchType(metadata) != null;

        TypeSpec.Builder stub = TypeSpec.classBuilder(stubType.simpleName())
                .addModifiers(Modifier.STATIC, Modifier.FINAL)
                .superclass(serviceType)
                .addJavadoc("Records what the handler asked for and returns what the test staged.\n")
                .addJavadoc("<p>{@code super(null)} is safe: the generated service constructor only\n")
                .addJavadoc("assigns the repository, and no method overridden here reads it — so no\n")
                .addJavadoc("persistence engine is involved in a handler test.\n")
                .addField(FieldSpec.builder(KernelListQueryGenerator.pageType(metadata), "page")
                        .initializer("$T.of($T.of(), 0L, 0, $T.DEFAULT_SIZE)",
                                KernelListQueryGenerator.pageType(metadata), LIST,
                                KernelListQueryGenerator.listQueryType(metadata))
                        .build())
                .addField(FieldSpec.builder(KernelListQueryGenerator.listQueryType(metadata), "listQuery")
                        .addJavadoc("The list query the handler asked for; {@code null} until it asks.\n")
                        .build())
                .addField(FieldSpec.builder(optionalOfEntity, "byId")
                        .initializer("$T.empty()", OPTIONAL).build())
                .addField(FieldSpec.builder(UUID, "lookedUp").build())
                .addField(FieldSpec.builder(UUID, "deleted").build())
                .addField(FieldSpec.builder(entityType, "saved").build())
                .addField(FieldSpec.builder(UUID, "updatedId").build())
                .addField(FieldSpec.builder(TypeName.BOOLEAN, "rowExists")
                        .initializer("true")
                        .addJavadoc("Whether the row a write addresses exists. Defaults true, so\n")
                        .addJavadoc("a test that says nothing is testing the path that found\n")
                        .addJavadoc("something; set it false to take the other branch.\n")
                        .build());
        if (refuses) {
            stub.addField(FieldSpec.builder(RuntimeException.class, "refusal")
                            .addJavadoc("A caller-fault refusal {@code save} and {@code update} raise instead\n")
                            .addJavadoc("of writing, the way the repository refuses a foreign tenant.\n")
                            .addJavadoc("{@code null} — the default — writes.\n")
                            .build())
                    .addField(FieldSpec.builder(entityType, "attempted")
                            .addJavadoc("The entity a refused write was handed.\n")
                            .build());
        }
        return stub
                .addMethod(MethodSpec.constructorBuilder()
                        .addStatement("super(($T) null)", repositoryType)
                        .build())
                .addMethod(MethodSpec.methodBuilder("findPage")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(KernelListQueryGenerator.pageType(metadata))
                        .addParameter(KernelListQueryGenerator.listQueryType(metadata), "query")
                        .addStatement("this.listQuery = query")
                        .addStatement("return page")
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
                        .beginControlFlow("if (!rowExists)")
                        .addStatement("throw new $T(id)", notFound)
                        .endControlFlow()
                        .addStatement("this.deleted = id")
                        .build())
                // save/update are overridden so a guard that stopped short-circuiting is reported
                // as a failed assertion on a recorder, not as an NPE from the null repository.
                //
                // save fills an absent id, because the real repository does — the generated
                // service test asserts exactly that ("save returns the repository's result, not
                // the argument it was handed"). The handler reads saved.getId(), so a double
                // that skipped it would answer 500 from a null id production never sees.
                .addMethod(MethodSpec.methodBuilder("save")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(entityType)
                        .addParameter(entityType, "entity")
                        .addCode(refusalCheck(refuses))
                        .beginControlFlow("if (entity.$L() == null)", PrimaryKeys.getter(metadata))
                        .addStatement("entity.$L($T.fromString($S))", PrimaryKeys.setter(metadata), UUID,
                                FIXED_ID)
                        .endControlFlow()
                        .addStatement("this.saved = entity")
                        .addStatement("return entity")
                        .build())
                .addMethod(MethodSpec.methodBuilder("update")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(entityType)
                        .addParameter(UUID, "id")
                        .addParameter(entityType, "entity")
                        .addCode(refusalCheck(refuses))
                        .beginControlFlow("if (!rowExists)")
                        .addStatement("throw new $T(id)", conflict != null ? conflict : notFound)
                        .endControlFlow()
                        .addStatement("this.updatedId = id")
                        .addStatement("return entity")
                        .build())
                .build();
    }

    /** The stub's "refuse instead of writing" branch, or nothing when it cannot refuse. */
    private static CodeBlock refusalCheck(boolean refuses) {
        if (!refuses) {
            return CodeBlock.of("");
        }
        return CodeBlock.builder()
                .beginControlFlow("if (refusal != null)")
                .addStatement("this.attempted = entity")
                .addStatement("throw refusal")
                .endControlFlow()
                .build();
    }

    /**
     * The statement that dispatches one route: a bare call, or — for a tenant-partitioned entity —
     * the same call inside {@code asTenant(...)}.
     */
    private static String invoke(String handlerMethod, boolean tenantScoped) {
        String call = "handler." + handlerMethod + "(exchange)";
        return tenantScoped ? AS_TENANT + "(() -> " + call + ")" : call;
    }

    private static MethodSpec.Builder test(String name) {
        return MethodSpec.methodBuilder(name)
                .addAnnotation(TEST)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID);
    }
}
