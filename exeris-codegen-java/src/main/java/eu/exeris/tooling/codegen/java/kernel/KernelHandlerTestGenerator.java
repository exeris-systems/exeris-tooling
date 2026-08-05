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
import eu.exeris.tooling.codegen.java.support.KernelScaffold;
import eu.exeris.tooling.codegen.java.support.NameCasing;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Emits {@code <Entity>HandlerTest} — the generated test for the generated handler (T2, ADR-058).
 *
 * <h2>What it covers, and why only that</h2>
 * <p>Every route's <em>status</em>, which is this handler's actual contract with the router — a
 * regeneration that reorders a guard or drops a branch changes a status, and this catches it.
 *
 * <p>Slice a: the bodyless routes — {@code handleGetAll}, {@code handleGetById} (found, absent,
 * malformed id) and {@code handleDelete}.
 *
 * <p>Slice b: the guard paths of the body-carrying routes. {@code handleCreate} and
 * {@code handleUpdate} both reject before reading the body — {@code parseBody} throws on
 * {@code hasBody() == false} ahead of resolving any decoder, and {@code handleUpdate}'s path-id
 * guard runs ahead of that again. So these three cases need no request-body double at all, and
 * each additionally asserts that the service was never reached.
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
    private static final ClassName SCOPED_VALUE = ClassName.get("java.lang", "ScopedValue");
    private static final ClassName HTTP_KERNEL_PROVIDERS =
            ClassName.get("eu.exeris.kernel.spi.http", "HttpKernelProviders");
    private static final ClassName KERNEL_PROVIDERS =
            ClassName.get("eu.exeris.kernel.spi.context", "KernelProviders");
    private static final ClassName BIG_DECIMAL = ClassName.get("java.math", "BigDecimal");

    /**
     * The longest string literal a length case will emit. A {@code maxLength} in the thousands is a
     * database-column bound, not a guard worth driving with a literal that would dwarf the test —
     * those rules go uncovered rather than unreadable.
     */
    private static final int MAX_EMITTED_STRING = 512;

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
                .addJavadoc("<p>Covers the status each route owes the router: the bodyless CRUD\n")
                .addJavadoc("routes, the guard paths of {@code handleCreate} /\n")
                .addJavadoc("{@code handleUpdate} that reject before the body is read, and the\n")
                .addJavadoc("{@code @Validation} guards past a successful decode.\n")
                .addJavadoc("<p>Requires JUnit 5 and AssertJ on the test classpath, and nothing else.\n")
                .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain models.\n");

        ClassName bodyType = ClassName.get(
                KernelTestSupportGenerator.supportPackage(basePackage),
                KernelTestSupportGenerator.RECORDING_REQUEST_BODY);

        type.addMethod(getAllTest(entity, entityType, handlerType, exchangeType, stubType, basePath));
        type.addMethod(getByIdFoundTest(entity, entityType, handlerType, exchangeType, stubType, basePath));
        type.addMethod(getByIdAbsentTest(entity, handlerType, exchangeType, stubType, basePath));
        type.addMethod(getByIdMalformedTest(entity, handlerType, exchangeType, stubType, basePath));
        type.addMethod(deleteTest(entity, handlerType, exchangeType, stubType, basePath));
        type.addMethod(createMissingBodyTest(entity, handlerType, exchangeType, stubType, basePath));
        type.addMethod(updateMalformedIdTest(entity, handlerType, exchangeType, stubType, basePath));
        type.addMethod(updateMissingBodyTest(entity, handlerType, exchangeType, stubType, basePath));
        addValidationTests(type, metadata, entityType, handlerType, exchangeType, bodyType,
                stubType, basePath);
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

    private MethodSpec createMissingBodyTest(String entity, ClassName handlerType, ClassName exchangeType,
                                             ClassName stubType, String basePath) {
        return test("handleCreateRespondsBadRequestWhenTheBodyIsMissing")
                .addJavadoc("A bodyless {@code POST} is rejected by the body guard, before the\n")
                .addJavadoc("service is consulted — so nothing is persisted on a malformed request.\n")
                .addStatement("$T service = new $T()", stubType, stubType)
                .addStatement("$T handler = new $T(service)", handlerType, handlerType)
                .addStatement("$T exchange = $T.post($S)", exchangeType, exchangeType, basePath)
                .addStatement("handler.handleCreate(exchange)")
                .addStatement("$T.assertThat(exchange.status()).isEqualTo($T.BAD_REQUEST)",
                        ASSERTIONS, HTTP_STATUS)
                .addStatement("$T.assertThat(service.saved).isNull()", ASSERTIONS)
                .build();
    }

    private MethodSpec updateMalformedIdTest(String entity, ClassName handlerType, ClassName exchangeType,
                                             ClassName stubType, String basePath) {
        return test("handleUpdateRespondsBadRequestOnAMalformedId")
                .addJavadoc("The path-id guard runs before the body guard, so a malformed id is\n")
                .addJavadoc("rejected without the body ever being read.\n")
                .addStatement("$T service = new $T()", stubType, stubType)
                .addStatement("$T handler = new $T(service)", handlerType, handlerType)
                .addStatement("$T exchange = $T.put($S).withPathParam($S, $S)",
                        exchangeType, exchangeType, basePath + "/not-a-uuid", "id", "not-a-uuid")
                .addStatement("handler.handleUpdate(exchange)")
                .addStatement("$T.assertThat(exchange.status()).isEqualTo($T.BAD_REQUEST)",
                        ASSERTIONS, HTTP_STATUS)
                .addStatement("$T.assertThat(service.updatedId).isNull()", ASSERTIONS)
                .build();
    }

    private MethodSpec updateMissingBodyTest(String entity, ClassName handlerType, ClassName exchangeType,
                                             ClassName stubType, String basePath) {
        return test("handleUpdateRespondsBadRequestWhenTheBodyIsMissing")
                .addJavadoc("A well-formed id is not enough: the body guard still rejects, and the\n")
                .addJavadoc("service is never reached.\n")
                .addStatement("$T service = new $T()", stubType, stubType)
                .addStatement("$T handler = new $T(service)", handlerType, handlerType)
                .addStatement("$T exchange = $T.put($S).withPathParam($S, $S)",
                        exchangeType, exchangeType, basePath + "/" + FIXED_ID, "id", FIXED_ID)
                .addStatement("handler.handleUpdate(exchange)")
                .addStatement("$T.assertThat(exchange.status()).isEqualTo($T.BAD_REQUEST)",
                        ASSERTIONS, HTTP_STATUS)
                .addStatement("$T.assertThat(service.updatedId).isNull()", ASSERTIONS)
                .build();
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
     * <p><b>And the accept case is load-bearing for a second reason.</b> Everything past
     * {@code hasBody()} — an unbound decoder registry, an unbound memory allocator, a decode that
     * throws — also lands on {@code 400 BAD_REQUEST}. So a suite of reject-only cases would go green
     * on wiring that never once reached a validation guard. {@code 201 CREATED} can only come out
     * the far end of a decode that worked, so it is what makes the rejects mean what they say.
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
                                    String basePath) {
        List<KernelValidationRules.FieldRules> rules =
                KernelValidationRules.of(metadata.fields());
        if (rules.isEmpty()) {
            return;
        }

        Map<String, CodeBlock> baseline = new LinkedHashMap<>();
        for (KernelValidationRules.FieldRules fr : rules) {
            CodeBlock value = baselineFor(fr);
            if (value == null) {
                return;
            }
            baseline.put(fr.field().name(), value);
        }

        Scaffold scaffold = new Scaffold(entityType, handlerType, exchangeType, bodyType, stubType,
                basePath, rules, baseline);

        type.addMethod(scaffold.create("handleCreateRespondsCreatedWhenEveryRuleIsSatisfied",
                        null, null)
                .addJavadoc("Every rule satisfied, each bounded field sitting exactly on its\n")
                .addJavadoc("boundary: the rules are inclusive, so this must pass <em>through</em>.\n")
                .addJavadoc("<p>It is also the only case here that proves the decode path ran at\n")
                .addJavadoc("all — every failure mode past the body guard answers 400, the same\n")
                .addJavadoc("status a rejection does.\n")
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
        KernelValidationRules.FieldRules first = rules.get(0);
        Probe firstReject = probesFor(first, first.rules().get(0)).stream()
                .filter(p -> !p.accept()).findFirst().orElse(null);
        if (firstReject != null) {
            type.addMethod(scaffold.update(first, firstReject));
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

        Scaffold(ClassName entityType, ClassName handlerType, ClassName exchangeType,
                 ClassName bodyType, ClassName stubType, String basePath,
                 List<KernelValidationRules.FieldRules> rules, Map<String, CodeBlock> baseline) {
            this.entityType = entityType;
            this.handlerType = handlerType;
            this.exchangeType = exchangeType;
            this.bodyType = bodyType;
            this.stubType = stubType;
            this.basePath = basePath;
            this.rules = rules;
            this.baseline = baseline;
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
            m.addStatement("$T service = new $T()", stubType, stubType)
                    .addStatement("$T handler = new $T(service)", handlerType, handlerType)
                    .addStatement("$T body = new $T()", bodyType, bodyType)
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
         * Runs the handler with both provider slots bound. Binding the allocator is not optional:
         * {@code HttpRequestDecodingContext} rejects a null one, and an unbound {@code ScopedValue}
         * throws inside the {@code try} that maps everything to 400.
         */
        private void run(MethodSpec.Builder m, String handlerMethod) {
            m.addStatement("$T.where($T.HTTP_REQUEST_BODY_DECODER_REGISTRY, body)\n"
                            + ".where($T.MEMORY_ALLOCATOR, body)\n"
                            + ".run(() -> handler.$L(exchange))",
                    SCOPED_VALUE, HTTP_KERNEL_PROVIDERS, KERNEL_PROVIDERS, handlerMethod);
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
                .addField(FieldSpec.builder(entityType, "saved").build())
                .addField(FieldSpec.builder(UUID, "updatedId").build())
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
                // save/update are overridden so a guard that stopped short-circuiting is reported
                // as a failed assertion on a recorder, not as an NPE from the null repository.
                .addMethod(MethodSpec.methodBuilder("save")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(entityType)
                        .addParameter(entityType, "entity")
                        .addStatement("this.saved = entity")
                        .addStatement("return entity")
                        .build())
                .addMethod(MethodSpec.methodBuilder("update")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(entityType)
                        .addParameter(UUID, "id")
                        .addParameter(entityType, "entity")
                        .addStatement("this.updatedId = id")
                        .addStatement("return entity")
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
